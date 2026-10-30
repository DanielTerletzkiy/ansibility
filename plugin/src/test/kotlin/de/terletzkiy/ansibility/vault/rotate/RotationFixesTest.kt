package de.terletzkiy.ansibility.vault.rotate

import com.intellij.history.LocalHistory
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import de.terletzkiy.ansibility.api.VaultDecryptResult
import de.terletzkiy.ansibility.api.VaultFailure
import de.terletzkiy.ansibility.api.VaultOperations
import de.terletzkiy.ansibility.api.VaultPurpose
import de.terletzkiy.ansibility.api.VaultSourceKind
import de.terletzkiy.ansibility.api.VaultUnlockResult
import de.terletzkiy.ansibility.vault.VaultVectors
import de.terletzkiy.ansibility.vault.identity.ExplicitIdentity
import de.terletzkiy.ansibility.vault.identity.PasswordManagerReader
import de.terletzkiy.ansibility.vault.identity.VaultPasswordSafeKeys
import de.terletzkiy.ansibility.vault.identity.VaultProjectSettings
import de.terletzkiy.ansibility.vault.reencrypt.ReencryptionWriter
import de.terletzkiy.ansibility.vault.secrets.VaultConsentDecision
import de.terletzkiy.ansibility.vault.secrets.VaultUserState
import de.terletzkiy.ansibility.vault.tab.DecryptedVaultFile
import de.terletzkiy.ansibility.vault.tab.DecryptedVaultTabs
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The fixes of the Part 1 review (plan amendment R29) that the review's own tests ([RotationRegressionTest]) do not
 * cover, one or more per decision: the overriding rule is that a rotation never leaves vault files that no durably
 * stored password opens, and never overwrites a stored password that is not the old password being rotated. Synthetic
 * passwords and values only.
 */
class RotationFixesTest : RotationTestCase() {
    private fun rootKey(relative: String): String = registry.discovery(root(relative)).rootKey

    private fun reveal(relative: String, key: String): VaultDecryptResult =
        await { VaultOperations.getInstance(project).decrypt(location(relative, key), VaultPurpose.REVEAL) }
            .also { (it as? VaultDecryptResult.Decrypted)?.plaintext?.close() }

    private fun another(): String = NewPassword.generate(SecureRandom()).use { String(it.chars()) }

    private fun notifications(): MutableList<Notification> {
        val shown = CopyOnWriteArrayList<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    shown += notification
                }
            },
        )
        return shown
    }

    // ------------------------------------------------------------------------------------------------ decision 1

    /** Decision 1: a teammate's password pasted into `.vault-pass` while the review is open stops the rotation before anything is written or stored. */
    fun testAPasswordFileChangedDuringTheReviewIsNeverOverwritten() {
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        ready()
        val falcon = root(root)
        val before = bytes(vaultPath)
        val teammate = another()
        rotationPrompts.onReview = {
            Files.write(base.resolve("$root/.vault-pass"), "$teammate\n".toByteArray())
            true
        }

        rotate()

        assertEquals("the teammate's password is kept", teammate, text("$root/.vault-pass").trim())
        assertTrue("no vault file changed", before.contentEquals(bytes(vaultPath)))
        assertNull("nothing stored", stored(falcon))
        val stop = feedback().single()
        assertTrue(stop, stop.startsWith("Nothing was rotated: ") && stop.contains(".vault-pass changed since the IDE last read it"))
    }

    /** Decision 1: a stale remembered entry (not the password that opens the files) is never overwritten. */
    fun testAStaleRememberedEntryIsNeverOverwritten() {
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        ready()
        val falcon = root(root)
        val canonical = registry.discovery(falcon).canonicalRootPath
        val stale = another()
        credentials.write(VaultPasswordSafeKeys.serviceName(canonical, "default"), "default", stale.toCharArray(), memoryOnly = false)
        VaultUserState.getInstance().remember(canonical, "default", persistent = true)
        assertTrue(await { secrets.unlock(falcon) } is VaultUnlockResult.Unlocked)
        val before = bytes(vaultPath)

        rotate()

        assertEquals("the remembered entry keeps what it held", stale, stored(falcon))
        assertTrue(before.contentEquals(bytes(vaultPath)))
        assertEquals(VaultVectors.PW1, text("$root/.vault-pass").trim())
        val stop = feedback().single()
        assertTrue(stop, stop.contains("the IDE password store entry of 'default' for ") && stop.contains("holds another password"))
    }

    /** Decision 11: an envelope of another id whose password cannot be checked without asking stops the rotation. */
    fun testAnotherIdThatIsLockedStopsTheRotationWhenTheOldPasswordOpensItsEnvelopes() {
        val kite = projectRoot("kite", "[defaults]\nvault_identity_list = default@.vault-pass, prod@prompt\n")
        write("$kite/.vault-pass", "${VaultVectors.PW1}\n")
        write(
            "$kite/group_vars/all/vault.yml",
            VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Kite0Old0Value001", VaultVectors.PW1)) +
                VaultVectors.inline("vault_prod_password", VaultVectors.encrypt("Prod0Old0Value001", VaultVectors.PW1, "prod")),
        )
        root = kite
        ready()
        val before = bytes("$kite/group_vars/all/vault.yml")

        rotate(vf(kite))

        assertTrue(before.contentEquals(bytes("$kite/group_vars/all/vault.yml")))
        assertEquals("nothing was asked for prod", 0, prompter.passwordRequests.size)
        val stop = feedback().single()
        assertTrue(stop, stop.contains("also opens envelopes labelled 'prod', which has its own password. Unlock 'prod' first"))
    }

    // ------------------------------------------------------------------------------------------------ decision 2

    /** Decision 2: a password file of the chain that was never read (no consent) stops the rotation and is named. */
    fun testAPasswordFileWithoutConsentStopsTheRotation() {
        val kite = projectRoot("kite", "[defaults]\nvault_identity_list = default@.vault-pass, prod@prod.pw\n")
        write("$kite/.vault-pass", "${VaultVectors.PW1}\n")
        write("$kite/prod.pw", VaultVectors.encrypt(VaultVectors.PROD, VaultVectors.PW1).formatBytes())
        write("$kite/group_vars/all/vault.yml", VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Kite0Old0Value001", VaultVectors.PW1)))
        root = kite
        ready()
        val before = bytes("$kite/prod.pw")

        rotate(vf(kite))

        assertTrue(before.contentEquals(bytes("$kite/prod.pw")))
        assertEquals(VaultVectors.PW1, text("$kite/.vault-pass").trim())
        val stop = feedback().single()
        assertTrue(stop, stop.contains("prod.pw, the password file of 'prod' in ") && stop.contains("was not read yet"))
    }

    /** Decision 2: another root's vaulted password file that the old password opens keeps it (that root is not rotated), with a note. */
    fun testAnotherRootsVaultedPasswordFileIsLeftAloneWithANote() {
        val heron = projectRoot("heron", "[defaults]\nvault_identity_list = default@.vault-pass, prod@prod.pw\n")
        write("$heron/.vault-pass", "${VaultVectors.PW1}\n")
        write("$heron/prod.pw", VaultVectors.encrypt(VaultVectors.PROD, VaultVectors.PW1).formatBytes())
        write("$heron/group_vars/all/vault.yml", VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Heron0Old0Value001", VaultVectors.PW1)))
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Falcon0Old0Value01", VaultVectors.PW1)))
        ready()
        assertTrue(await { secrets.unlock(root(heron)) } is VaultUnlockResult.Unlocked)
        val prod = bytes("$heron/prod.pw")

        rotate(vf(root))

        assertEquals("Falcon0Old0Value01", valueOf(vaultPath, "vault_db_password", newPassword))
        assertTrue("heron's password file keeps the old password", prod.contentEquals(bytes("$heron/prod.pw")))
        val left = rotationPrompts.reviews.single().leftAlone
        assertTrue(left.toString(), left.any { it.contains("prod.pw") && it.contains("which is not included; it keeps the old password") })
    }

    // ------------------------------------------------------------------------------------------------ decisions 4 and 17

    /**
     * Decisions 4 and 17: the scan skips `.git`; leftover temporary files go at the next rotation and the next unlock.
     * Since round 2 (decision 9) only files older than a minute count as leftovers, so the test dates them back.
     */
    fun testTheScanSkipsGitAndLeftoverTemporaryFilesAreRemoved() {
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        val inGit = write("$root/.git/stored.vault.txt", VaultVectors.encrypt("not this one\n", VaultVectors.PW1).formatBytes())
        val leftover = write("$root/group_vars/all/.ansibility-rotate-123.tmp", "an old temporary file")
        Files.setLastModifiedTime(leftover, FileTime.fromMillis(System.currentTimeMillis() - 120_000))
        ready()
        val gitBefore = Files.readAllBytes(inGit)

        rotate()

        assertEquals("Db0Old0Value0001", valueOf(vaultPath, "vault_db_password", newPassword))
        assertTrue("a vault below .git is not touched", gitBefore.contentEquals(Files.readAllBytes(inGit)))
        assertFalse("the rotation's scan removed the leftover", Files.exists(leftover))

        val next = write("$root/.ansibility-rotate-456.tmp", "another one")
        Files.setLastModifiedTime(next, FileTime.fromMillis(System.currentTimeMillis() - 120_000))
        secrets.lockAll()
        assertTrue(await { secrets.unlock(root(root)) } is VaultUnlockResult.Unlocked)
        assertFalse("the unlock removed the leftover next to the password file", Files.exists(next))
    }

    // ------------------------------------------------------------------------------------------------ decision 5

    /**
     * Decision 5: a password file named by a `.env.local` that was not read yet is not written; the dialog and the
     * summary say so. Since round 2 (decision 4) the rotation asks to read every `.env.local` before it groups the roots:
     * the test declines that first question (the unlock's own consent is given), so the file stays unresolved.
     */
    fun testAPasswordFileNamedByAnUnreadEnvLocalIsNotWritten() {
        write("$root/.env.local", "ANSIBLE_LOCAL_VAULT_PASSWORD_FILE=.vault-pass\n")
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        ready()
        val falcon = root(root)
        val asked = java.util.concurrent.atomic.AtomicInteger()
        prompter.onConsent = { if (asked.getAndIncrement() == 0) VaultConsentDecision.NotNow else VaultConsentDecision.UseAll }

        rotate()

        val group = rotationPrompts.requests.single().identity.groups.single()
        assertFalse("not writable while .env.local is unread", group.writable)
        val text = RotationTexts.destination(group)
        assertTrue(text, text.startsWith("Not written: ") && text.contains(".env.local was not read yet"))
        assertEquals("Db0Old0Value0001", valueOf(vaultPath, "vault_db_password", newPassword))
        assertEquals("the password file keeps the old password", VaultVectors.PW1, text("$root/.vault-pass").trim())
        assertEquals(newPassword, stored(falcon))
        val summary = service.lastSummary!!
        assertTrue(summary.text, summary.text.contains("Update now: "))
        assertNotNull("pending until the file holds the new password", VaultUserState.getInstance().pendingRotation(registry.discovery(falcon).canonicalRootPath, "default"))
    }

    // ------------------------------------------------------------------------------------------------ decision 6

    /** Decision 6: when a written file cannot be put back, the new password stays stored and unlocked, and Copy New Password is offered. */
    fun testAFileThatCannotBePutBackKeepsTheNewPasswordStoredAndUnlocked() {
        write("$root/group_vars/all/a.yml", VaultVectors.inline("vault_a_password", VaultVectors.encrypt("A0Old0Value0001", VaultVectors.PW1)))
        write("$root/group_vars/all/b.yml", VaultVectors.inline("vault_b_password", VaultVectors.encrypt("B0Old0Value0001", VaultVectors.PW1)))
        ready()
        val falcon = root(root)
        val shown = notifications()
        val calls = HashMap<String, Int>()
        // a.yml is written, b.yml fails, and putting a.yml back fails too.
        ReencryptionWriter.failWriteForTests = { file ->
            val call = (calls[file.name] ?: 0) + 1
            calls[file.name] = call
            file.name == "b.yml" || file.name == "a.yml" && call == 2
        }

        rotate()

        assertEquals("a.yml holds the new envelope", "A0Old0Value0001", valueOf("$root/group_vars/all/a.yml", "vault_a_password", newPassword))
        assertEquals("b.yml keeps the old one", "B0Old0Value0001", valueOf("$root/group_vars/all/b.yml", "vault_b_password", VaultVectors.PW1))
        assertEquals("the new password stays stored", newPassword, stored(falcon))
        assertEquals("the password file keeps the old one", VaultVectors.PW1, text("$root/.vault-pass").trim())
        assertNull(service.lastSummary)
        val notification = shown.single { it.content.startsWith("The rotation stopped halfway") }
        assertTrue(notification.content, notification.content.contains("a.yml"))
        assertEquals(listOf("Copy New Password"), notification.actions.map { it.templatePresentation.text })
        ReencryptionWriter.failWriteForTests = null
        assertTrue("both open in this session", reveal("$root/group_vars/all/a.yml", "vault_a_password") is VaultDecryptResult.Decrypted)
        assertTrue(reveal("$root/group_vars/all/b.yml", "vault_b_password") is VaultDecryptResult.Decrypted)
    }

    // ------------------------------------------------------------------------------------------------ decision 9

    /** Decision 9: the pending rotation survives the IDE (application state) and ends when the source gives the new password. */
    fun testAPendingRotationEndsWhenTheSourceGivesTheNewPassword() {
        var managed = VaultVectors.PW1
        secrets.setPasswordManagersForTests(PasswordManagerReader { _, _, _ -> managed.toByteArray() })
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        ready()
        VaultProjectSettings.getInstance(project).update(rootKey(root)) {
            it.copy(identities = listOf(ExplicitIdentity("default", VaultSourceKind.ONE_PASSWORD, "op://Private/vault/password")))
        }
        val falcon = root(root)
        val canonical = registry.discovery(falcon).canonicalRootPath

        rotate()

        val state = VaultUserState.getInstance()
        assertNotNull(state.pendingRotation(canonical, "default"))
        state.loadState(state.state)
        assertNotNull("kept in the saved state", state.pendingRotation(canonical, "default"))

        managed = newPassword
        secrets.lockAll()
        assertTrue(await { secrets.unlock(falcon) } is VaultUnlockResult.Unlocked)
        assertNull("the manager gives the new password now", state.pendingRotation(canonical, "default"))
    }

    // ------------------------------------------------------------------------------------------------ decision 12

    /** Decision 12: a password file that is a link to a file outside the repository is named with its target and the note. */
    fun testALinkedPasswordFileIsNamedWithItsTargetAndOutsideTheRepository() {
        val target = write("home/secrets/falcon.pw", "${VaultVectors.PW1}\n")
        val link = base.resolve("$root/.vault-pass")
        Files.delete(link)
        Files.createSymbolicLink(link, target)
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        ready()

        rotate()

        val group = rotationPrompts.requests.single().identity.groups.single()
        val text = RotationTexts.destination(group)
        assertTrue(text, text.contains(".vault-pass → ") && text.contains("falcon.pw") && text.endsWith("(outside the repository)"))
        assertTrue("the link stays a link", Files.isSymbolicLink(link))
        assertEquals(newPassword, String(Files.readAllBytes(target)))
    }

    // ------------------------------------------------------------------------------------------------ decision 13

    /** Decision 13 (X129): the hint also comes after the idle lock, when a teammate rotated everything and nothing opens any more. */
    fun testTheTeammateHintComesAfterALockToo() {
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        ready()
        val falcon = root(root)
        assertTrue(await { secrets.unlock(falcon) } is VaultUnlockResult.Unlocked)
        assertTrue(reveal(vaultPath, "vault_db_password") is VaultDecryptResult.Decrypted)
        RotationHints.getInstance(project).drainForTests()

        // A pull brings envelopes of the teammate's new password; the idle lock clears every verification record.
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0New0Value0001", another())))
        vf(vaultPath)
        secrets.lockAll()
        assertTrue(await { secrets.unlock(falcon) } is VaultUnlockResult.Unlocked)

        assertEquals(VaultFailure.WRONG_SECRET, (reveal(vaultPath, "vault_db_password") as VaultDecryptResult.Failed).failure)
        val hint = RotationHints.getInstance(project).drainForTests().single()
        assertTrue(hint, hint.startsWith("The vault password for 'default' may have been rotated."))
    }

    // ------------------------------------------------------------------------------------------------ decision 18

    /** Decision 18: a whole-file vault whose decrypted tab has unsaved edits stops the rotation. */
    fun testAModifiedDecryptedTabStopsTheRotation() {
        val keyPath = "$root/files/demo.key"
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        write(keyPath, VaultVectors.encrypt("synthetic key material\n", VaultVectors.PW1).formatBytes())
        ready()
        val tabs = DecryptedVaultTabs.getInstance(project)
        try {
            tabs.open(vf(keyPath))
            waitFor("the tab did not open") { tabs.lastAction?.isCompleted == true && tabs.openSessions.isNotEmpty() }
            val tab = FileEditorManager.getInstance(project).openFiles.filterIsInstance<DecryptedVaultFile>().single()
            val document = FileDocumentManager.getInstance().getDocument(tab)!!
            WriteCommandAction.runWriteCommandAction(project) { document.setText("synthetic key material, edited\n") }
            val before = bytes(vaultPath)

            rotate()

            assertTrue(before.contentEquals(bytes(vaultPath)))
            assertEquals(listOf("Nothing was rotated: save or discard the decrypted tab of demo.key first."), feedback())
            assertNull(stored(root(root)))
        } finally {
            val editors = FileEditorManager.getInstance(project)
            editors.openFiles.filterIsInstance<DecryptedVaultFile>().forEach {
                it.session?.closingQuietly = true
                editors.closeFile(it)
            }
            tabs.openSessions.forEach { Disposer.dispose(it) }
        }
    }

    // ------------------------------------------------------------------------------------------------ decision 19 (U1)

    /**
     * Decision 19 (U1): `.vault-pass` was open in an editor (its content in the VFS) before the rotation rewrote it. The
     * observation, kept as a test: Local History holds no revision with the old or the new password.
     */
    fun testLocalHistoryKeepsNoPasswordOfAnOpenPasswordFile() {
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        ready()
        val password = vf("$root/.vault-pass")
        FileEditorManager.getInstance(project).openFile(password, true)
        try {
            rotate()
            assertEquals(newPassword, text("$root/.vault-pass"))

            val stamps = ArrayList<Long>()
            LocalHistory.getInstance().getByteContent(password) { stamps += it; false }
            val revisions = stamps.mapNotNull { stamp -> LocalHistory.getInstance().getByteContent(password) { it == stamp }?.let { String(it) } }
            assertFalse("Local History keeps the old password: $stamps", revisions.any { it.contains(VaultVectors.PW1) })
            assertFalse("Local History keeps the new password: $stamps", revisions.any { it.contains(newPassword) })
        } finally {
            FileEditorManager.getInstance(project).closeFile(password)
        }
    }

    // ------------------------------------------------------------------------------------------------ decision 20 (U4)

    /** Decision 20 (U4): Check after the discovery was rebuilt decrypts a vaulted password file only with the secrets before it, as Ansible does. */
    fun testCheckAfterTheDiscoveryWasRebuiltKeepsAnsiblesOrder() {
        val kite = projectRoot("kite", "[defaults]\nvault_identity_list = default@.vault-pass, dev@dev.pw\n")
        // default's file is vaulted with dev's password, which Ansible loads only after it: it cannot be loaded.
        write("$kite/.vault-pass", VaultVectors.encrypt(VaultVectors.PW1, VaultVectors.DEV, "dev").formatBytes())
        write("$kite/dev.pw", "${VaultVectors.DEV}\n")
        root = kite
        ready()
        val kiteRoot = root(kite)
        assertTrue(await { secrets.unlock(kiteRoot) } is VaultUnlockResult.Unlocked)
        val stale = registry.discovery(kiteRoot).identities.first { it.label == "default" }

        registry.invalidate()
        assertNotSame("rebuilt", stale, registry.discovery(kiteRoot).identities.first { it.label == "default" })

        assertNull("only the secrets before it may open it", await { secrets.loadSourceAgain(kiteRoot, stale) })
    }
}
