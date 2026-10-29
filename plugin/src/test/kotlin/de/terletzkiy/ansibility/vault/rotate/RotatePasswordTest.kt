package de.terletzkiy.ansibility.vault.rotate

import com.intellij.history.LocalHistory
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.command.undo.UnexpectedUndoException
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import de.terletzkiy.ansibility.api.VaultDecryptResult
import de.terletzkiy.ansibility.api.VaultOperations
import de.terletzkiy.ansibility.api.VaultPurpose
import de.terletzkiy.ansibility.vault.VaultVectors
import de.terletzkiy.ansibility.vault.reencrypt.ReencryptionEngine
import de.terletzkiy.ansibility.vault.reencrypt.ReencryptionWriter
import de.terletzkiy.ansibility.vault.secrets.VaultUserState
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Plan amendment R29, Part 1 (D205–D213): what a rotation re-encrypts (everything the current password opens, whatever
 * the label; headers kept), what it leaves alone, the order of its effects, all or nothing, not undoable, the password
 * file (atomic, 0600, no newline, consent kept), the IDE password store before the first vault file, and the id unlocked
 * with the new password afterwards.
 */
class RotatePasswordTest : RotationTestCase() {
    private val keyPath get() = "$root/files/ssl/demo.key"

    /** A vault file with values of every kind the rotation tells apart. */
    private fun mixedValues() {
        write(
            vaultPath,
            VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)) +
                // Labelled "new" but opened by the default password: rotated, and it keeps "new" (D207, D208).
                VaultVectors.inline("vault_api_token", VaultVectors.encrypt("Api0Old0Value0001", VaultVectors.PW1, "new")) +
                // Another id's value: not this rotation's.
                VaultVectors.inline("vault_vrrp_password", VaultVectors.encrypt("Vrrp0001", VaultVectors.DEV, "dev")) +
                // Unlabelled (default) but encrypted with something else: left alone with the reason.
                VaultVectors.inline("vault_stray_password", VaultVectors.encrypt("Stray0Value0001", "a-teammates-password")) +
                "vault_broken: !vault |\n  \$ANSIBLE_VAULT;1.1;AES256\n  zz\n",
        )
        write(keyPath, VaultVectors.encrypt("synthetic key material\n", VaultVectors.PW1).formatBytes())
    }

    fun testEveryEnvelopeTheCurrentPasswordOpensIsRotatedAndKeepsItsHeader() {
        mixedValues()
        ready()
        val before = text(vaultPath)

        rotate()

        val request = rotationPrompts.requests.single()
        assertEquals("default", request.identity.label)
        val group = request.identity.groups.single()
        assertEquals(Destination.PASSWORD_FILE, group.source.destination)
        assertTrue(group.checked && group.write)
        val review = rotationPrompts.reviews.single()
        val falcon = review.roots.single()
        assertEquals(2, falcon.values)
        assertEquals(1, falcon.vaultFiles)
        assertEquals(listOf("files/ssl/demo.key", "group_vars/all/vault.yml"), falcon.files.sorted())
        assertTrue(review.leftAlone.toString(), review.leftAlone.any { it.contains("vault_stray_password") && it.contains("the current password does not open it") })
        assertTrue(review.leftAlone.toString(), review.leftAlone.any { it.contains("vault_broken") && it.contains("it does not parse") })
        assertFalse("another id's value is not this rotation's", review.leftAlone.any { it.contains("vault_vrrp_password") })

        val after = text(vaultPath)
        assertTrue(after, after.contains("vault_db_password: !vault |\n  \$ANSIBLE_VAULT;1.1;AES256\n"))
        assertTrue(after, after.contains("vault_api_token: !vault |\n  \$ANSIBLE_VAULT;1.2;AES256;new\n"))
        assertEquals("Db0Old0Value0001", valueOf(vaultPath, "vault_db_password", newPassword))
        assertEquals("Api0Old0Value0001", valueOf(vaultPath, "vault_api_token", newPassword))
        assertNull("the old password no longer opens it", valueOf(vaultPath, "vault_db_password", VaultVectors.PW1))
        for (key in listOf("vault_vrrp_password", "vault_stray_password", "vault_broken")) assertEquals(key, envelopeOf(before, key), envelopeOf(after, key))
        val key = text(keyPath)
        assertTrue(key, key.startsWith("\$ANSIBLE_VAULT;1.1;AES256\n"))
        assertEquals("synthetic key material\n", decryptWith(key, newPassword))

        val password = base.resolve("$root/.vault-pass")
        assertEquals("written without a trailing newline", newPassword, text("$root/.vault-pass"))
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(password))
        val summary = service.lastSummary!!
        assertEquals(2, summary.values)
        assertEquals(1, summary.vaultFiles)
        assertTrue(summary.text, summary.text.contains("Vault id 'default' rotated: 2 values and 1 vault file in 2 files of"))
        assertTrue(summary.text, summary.text.contains("Git history keeps the old envelopes"))
        assertTrue(summary.text, summary.text.contains("CI credentials"))
    }

    fun testTheIdIsUnlockedWithTheNewPasswordAndTheNextUnlockAsksNothing() {
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        ready()
        val falcon = root(root)

        rotate()

        val location = location(vaultPath, "vault_db_password")
        val decrypted = await { VaultOperations.getInstance(project).decrypt(location, VaultPurpose.REVEAL) }
        assertTrue("decrypts without a prompt: $decrypted", decrypted is VaultDecryptResult.Decrypted)
        (decrypted as VaultDecryptResult.Decrypted).plaintext.use { assertEquals("Db0Old0Value0001", it.read { bytes -> String(bytes) }) }
        assertEmpty(prompter.passwordRequests)
        assertEquals("the IDE password store has the new password", newPassword, stored(falcon))
        assertEquals("and the old one as its previous password", VaultVectors.PW1, previous(falcon))

        // Locked and unlocked again: the rewritten password file is read without a consent question.
        secrets.lockAll()
        val consents = prompter.consentRequests.size
        assertTrue(await { secrets.unlock(falcon) } is de.terletzkiy.ansibility.api.VaultUnlockResult.Unlocked)
        assertEquals("the consent was renewed for the new content", consents, prompter.consentRequests.size)
        val again = await { VaultOperations.getInstance(project).decrypt(location, VaultPurpose.REVEAL) }
        assertTrue(again is VaultDecryptResult.Decrypted)
        (again as VaultDecryptResult.Decrypted).plaintext.close()
    }

    fun testCancelAtAnyPointBeforeWritingChangesNothingAndStoresNothing() {
        mixedValues()
        ready()
        val falcon = root(root)
        val before = bytes(vaultPath)
        val password = bytes("$root/.vault-pass")

        // The dialog.
        rotationPrompts.onRotation = { null }
        rotate()
        // The review.
        rotationPrompts.onRotation = { NewPassword(newPassword.toCharArray(), generated = false) }
        rotationPrompts.onReview = { false }
        rotate()
        // A preflight failure: a new envelope that does not verify.
        rotationPrompts.onReview = { true }
        // The last digit of the ciphertext changes after encrypting (the inner hex stays hex): the HMAC no longer matches.
        ReencryptionEngine.tamperForTests = { text ->
            val body = text.trimEnd('\n')
            val high = body[body.length - 2]
            val low = body.last()
            val changed = if (high == '6') (if (low == '6') '5' else low + 1) else (if (low == '9') '8' else low + 1)
            body.dropLast(1) + changed + "\n"
        }
        rotate()
        ReencryptionEngine.tamperForTests = null

        assertTrue(before.contentEquals(bytes(vaultPath)))
        assertTrue(password.contentEquals(bytes("$root/.vault-pass")))
        assertNull(stored(falcon))
        assertNull(previous(falcon))
        assertEmpty(VaultUserState.getInstance().previousPasswords())
        assertNull(service.lastSummary)
        val reports = feedback()
        assertEquals(reports.toString(), 1, reports.size)
        assertTrue(reports.single(), reports.single().startsWith("Nothing was rotated: a new envelope in"))
    }

    fun testACancelledUnlockRotatesNothing() {
        val owl = projectRoot("owl")
        write("$owl/group_vars/all/vault.yml", VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        root = owl
        ready()
        val before = bytes("$owl/group_vars/all/vault.yml")

        rotate()

        assertEquals("the unlock asked for the password", 1, prompter.passwordRequests.size)
        assertTrue(before.contentEquals(bytes("$owl/group_vars/all/vault.yml")))
        assertEquals(listOf("Rotate Vault Password was cancelled; nothing was written or stored."), feedback())
        assertTrue(credentials.entries.isEmpty())
    }

    fun testAFileChangedAfterThePreflightStopsEverythingBeforeTheFirstByte() {
        mixedValues()
        ready()
        val falcon = root(root)
        val before = bytes(vaultPath)
        rotationPrompts.onReview = {
            WriteAction.run<Throwable> { vf(keyPath).setBinaryContent(VaultVectors.encrypt("changed meanwhile\n", VaultVectors.PW1).formatBytes()) }
            true
        }

        rotate()

        assertTrue(before.contentEquals(bytes(vaultPath)))
        assertEquals(VaultVectors.PW1, text("$root/.vault-pass").trim())
        assertNull(stored(falcon))
        assertEquals(listOf("Nothing was rotated: demo.key changed after the preflight. Run Rotate Vault Password again."), feedback())
    }

    fun testAWriteFailureMidwayPutsTheWrittenFilesBackAndTheStoreToo() {
        write("$root/group_vars/all/a.yml", VaultVectors.inline("vault_a_password", VaultVectors.encrypt("A0Old0Value0001", VaultVectors.PW1)))
        write("$root/group_vars/all/b.yml", VaultVectors.inline("vault_b_password", VaultVectors.encrypt("B0Old0Value0001", VaultVectors.PW1)))
        ready()
        val falcon = root(root)
        val a = bytes("$root/group_vars/all/a.yml")
        val b = bytes("$root/group_vars/all/b.yml")
        val written = ArrayList<String>()
        ReencryptionWriter.failWriteForTests = { file -> (file.name == "b.yml").also { if (!it) written += file.name } }

        rotate()

        assertEquals("a.yml was written first", listOf("a.yml", "a.yml"), written)
        assertTrue("a.yml has its old bytes again", a.contentEquals(bytes("$root/group_vars/all/a.yml")))
        assertTrue(b.contentEquals(bytes("$root/group_vars/all/b.yml")))
        assertEquals(VaultVectors.PW1, text("$root/.vault-pass").trim())
        assertNull("the password store is put back", stored(falcon))
        assertNull(previous(falcon))
        assertEmpty(VaultUserState.getInstance().previousPasswords())
        assertFalse(VaultUserState.getInstance().rememberedLabels(registry.discovery(falcon).canonicalRootPath).contains("default"))
        assertEquals(listOf("Nothing was rotated: writing b.yml failed, and the files written before it have their old content again."), feedback())
    }

    fun testThePasswordStoreHasTheNewPasswordBeforeTheFirstVaultFileChanges() {
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        ready()
        val falcon = root(root)
        val before = text(vaultPath)
        val seen = ArrayList<String>()
        service.onStepForTests = { step ->
            seen += "$step: store=${stored(falcon) == newPassword} files=${if (text(vaultPath) == before) "old" else "new"} source=${if (text("$root/.vault-pass").trim() == newPassword) "new" else "old"}"
        }

        rotate()

        assertEquals(
            listOf(
                "STORE: store=false files=old source=old",
                "FILES: store=true files=old source=old",
                "SOURCES: store=true files=new source=old",
                "MEMORY: store=true files=new source=new",
            ),
            seen,
        )
    }

    fun testNoUndoBringsBackTheOldEnvelopesOfAnOpenFile() {
        write(vaultPath, "# vault values\n" + VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        ready()
        val file = vf(vaultPath)
        val editor = FileEditorManager.getInstance(project).openFile(file, true).filterIsInstance<TextEditor>().single()
        val document = editor.editor.document
        // An edit before the rotation, so the editor has an undo history.
        WriteCommandAction.runWriteCommandAction(project, "Edit", null, { document.insertString(0, "# edited\n") })
        FileDocumentManager.getInstance().saveDocument(document)

        rotate()
        assertEquals("Db0Old0Value0001", valueOf(vaultPath, "vault_db_password", newPassword))
        assertTrue(document.text, document.text.contains(envelopeOf(text(vaultPath), "vault_db_password").lines().first()))

        val undo = UndoManager.getInstance(project)
        assertEquals("Undo names the rotation", "Undo Rotate Vault Password", undo.getUndoActionNameAndDescription(editor).second)
        var refusals = 0
        TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            repeat(3) {
                if (undo.isUndoAvailable(editor)) {
                    try {
                        undo.undo(editor)
                    } catch (refused: Throwable) {
                        // The refusal: unit-test mode reports it as a logged error, either the UnexpectedUndoException of
                        // the refusing action or the platform's own "changes that cannot be undone" (the atomic write of
                        // review decision 6 reloads the document as an external change, which is not undoable).
                        val chain = generateSequence(refused) { it.cause }
                        assertTrue(refused.toString(), chain.any { it is UnexpectedUndoException || it.message.orEmpty().contains("cannot be undone") })
                        refusals++
                    }
                }
            }
        } finally {
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
        }
        // Since the atomic writes (review, decision 6) the reload is an external change: every Undo past the rotation
        // is refused, not only the first (edits made before the rotation can no longer be undone in that file).
        assertTrue("the rotation refused its undo", refusals >= 1)
        FileDocumentManager.getInstance().saveAllDocuments()
        assertEquals("the document still holds the new envelope", "Db0Old0Value0001", decryptWith(envelopeOf(document.text, "vault_db_password"), newPassword))
        assertEquals("and so does the file", "Db0Old0Value0001", valueOf(vaultPath, "vault_db_password", newPassword))
        FileEditorManager.getInstance(project).closeFile(file)
    }

    fun testAPasswordFileThatCannotBeWrittenLeavesTheRestRotatedAndSaysSo() {
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        ready()
        val falcon = root(root)
        val shown = java.util.concurrent.CopyOnWriteArrayList<com.intellij.notification.Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(
            com.intellij.notification.Notifications.TOPIC,
            object : com.intellij.notification.Notifications {
                override fun notify(notification: com.intellij.notification.Notification) {
                    shown += notification
                }
            },
        )
        // The root directory refuses new files, so the temporary file next to .vault-pass cannot be created.
        val directory = base.resolve(root)
        val permissions = Files.getPosixFilePermissions(directory)
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            rotate()
        } finally {
            Files.setPosixFilePermissions(directory, permissions)
        }

        assertEquals("the vault files are rotated", "Db0Old0Value0001", valueOf(vaultPath, "vault_db_password", newPassword))
        assertEquals("the password file keeps the old password", VaultVectors.PW1, text("$root/.vault-pass").trim())
        assertEquals("the IDE password store has the new one", newPassword, stored(falcon))
        val decrypted = await { VaultOperations.getInstance(project).decrypt(location(vaultPath, "vault_db_password"), VaultPurpose.REVEAL) }
        assertTrue("unlocked with the new password", decrypted is VaultDecryptResult.Decrypted)
        (decrypted as VaultDecryptResult.Decrypted).plaintext.close()
        val summary = service.lastSummary!!
        assertTrue(summary.text, summary.text.contains("Could not write "))
        val notification = shown.single { it.content == summary.text }
        assertEquals(RotationService.GROUP, notification.groupId)
        assertEquals("Ansibility Vault", notification.title)
        assertEquals(listOf("Regenerate Secrets…", "Copy New Password", "Check"), notification.actions.map { it.templatePresentation.text })

        // D213: Regenerate Secrets… for the same roots.
        service.regenerateAfter(summary)
        val regenerate = de.terletzkiy.ansibility.vault.regenerate.RegenerateService.getInstance(project).lastRun!!
        waitFor("regeneration timed out") { regenerate.isCompleted }
        assertEquals(listOf("vault_db_password"), regeneratePrompts.valueRequests.single().rows.map { it.keyPath })
    }

    fun testLocalHistoryLabelsTheRotation() {
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        ready()

        rotate()

        val label = service.lastSummary!!.historyLabel!!
        assertTrue("the label before the write exists", await { LocalHistory.getInstance().isLabelValid(project, label) })
        val stamps = ArrayList<Long>()
        LocalHistory.getInstance().getByteContent(vf(vaultPath)) { stamps += it; false }
        assertTrue("Local History has revisions of the file", stamps.isNotEmpty())
    }
}
