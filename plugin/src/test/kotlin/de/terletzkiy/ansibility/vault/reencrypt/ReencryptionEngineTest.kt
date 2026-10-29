package de.terletzkiy.ansibility.vault.reencrypt

import com.intellij.history.LocalHistory
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.VaultFailure
import de.terletzkiy.ansibility.api.VaultPurpose
import de.terletzkiy.ansibility.semantics.vault.SecretBytes
import de.terletzkiy.ansibility.vault.VaultVectors
import de.terletzkiy.ansibility.vault.actions.VaultFileOperations
import de.terletzkiy.ansibility.vault.actions.VaultValuePsi
import de.terletzkiy.ansibility.vault.actions.VaultValueRef
import de.terletzkiy.ansibility.vault.regenerate.RegenerateTestCase

/**
 * The shared re-encryption engine of plan amendment R29: an explicit secret decrypts and encrypts keeping each header
 * (Part 1's seam), a new envelope that does not verify is never written, the all-or-nothing write (mode 2) writes
 * nothing when a document changed after the preflight and names the file, one undo restores every old envelope across
 * files, and Local History has the labels.
 */
class ReencryptionEngineTest : RegenerateTestCase() {
    private val engine get() = ReencryptionEngine(project)
    private val writer get() = ReencryptionWriter(project) { VaultFileOperations.FileModes.POSIX }

    override fun tearDown() {
        try {
            ReencryptionEngine.tamperForTests = null
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** The `!vault` values of [relative] as the engine's targets. */
    private fun values(relative: String): List<VaultValueRef> = runReadActionBlocking {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        VaultValuePsi.vaultScalars(PsiManager.getInstance(project).findFile(vf(relative))!!).mapNotNull(VaultValueRef::of)
    }

    /** Items that give every value of [files] a new value [value] with the id and header it has (Part 2's items). */
    private fun regenerateItems(root: AnsibleRoot, vararg files: String, value: String = "New0Value0For0Tests0001"): List<ReencryptItem> =
        files.flatMap(::values).map { ref ->
            ReencryptItem(root, ReencryptTarget.Value(ref), Decryption.RootSecrets, NewPlaintext.Replace(SharedValue(1) { value.toByteArray() }), Encryption.SameAsBefore)
        }

    fun testAnExplicitSecretDecryptsAndEncryptsKeepingEachHeader() {
        val path = "$root/group_vars/all/vault.yml"
        write(path, VaultVectors.inline("vault_plain_header", VaultVectors.encrypt("alpha", VaultVectors.PW1)) +
            VaultVectors.inline("vault_new_header", VaultVectors.encrypt("beta", VaultVectors.PW1, "new")) +
            VaultVectors.inline("vault_other_password", VaultVectors.encrypt("gamma", VaultVectors.DEV, "dev")))
        write("$root/files/demo.key", VaultVectors.encrypt("whole\n", VaultVectors.PW1, "new").formatBytes())
        ready()
        val falcon = root(root)
        val current = SecretBytes.of(VaultVectors.PW1.toByteArray())
        val rotated = SecretBytes.of("rotated-pass-29".toByteArray())
        try {
            val items = values(path).map { ref ->
                ReencryptItem(falcon, ReencryptTarget.Value(ref), Decryption.Explicit(current), NewPlaintext.Keep, Encryption.Explicit(rotated, null, keepHeader = true))
            } + ReencryptItem(falcon, ReencryptTarget.WholeFile(vf("$root/files/demo.key")), Decryption.Explicit(current), NewPlaintext.Keep, Encryption.Explicit(rotated, null, keepHeader = true))
            val plan = await { engine.prepare(items, ReencryptionRun(VaultPurpose.REKEY)) }

            val outcomes = plan.items.map { it.outcome }
            assertTrue(outcomes.toString(), outcomes[0] is ItemOutcome.Ready && outcomes[1] is ItemOutcome.Ready && outcomes[3] is ItemOutcome.Ready)
            assertEquals("only the id's current password, not the chain", VaultFailure.WRONG_SECRET, (outcomes[2] as ItemOutcome.Failed).failure)
            assertEquals("1.1 stays an unlabelled 1.1", "\$ANSIBLE_VAULT;1.1;AES256", plan.items[0].ready!!.envelope.headerLine())
            assertEquals("new keeps new", "\$ANSIBLE_VAULT;1.2;AES256;new", plan.items[1].ready!!.envelope.headerLine())
            assertEquals("\$ANSIBLE_VAULT;1.2;AES256;new", plan.items[3].ready!!.envelope.headerLine())
            assertNotNull("a whole file carries the stamp it was read at", plan.items[3].stamp)
            assertEquals("whole\n", decryptWith(plan.items[3].ready!!.envelope.format(), "rotated-pass-29"))
            assertEmpty("an explicit secret unlocks nothing", prompter.consentRequests)
            assertEmpty(secrets.unlockedLabels(registry.discovery(falcon)))

            assertTrue(writer.writeAll(plan, "Rotate", "Before", "After") is ReencryptionWriter.AllOutcome.NotWritten)
            val valuesOnly = PreparedPlan(plan.items.filter { it.item.target is ReencryptTarget.Value }, plan.sequences)
            val written = writer.writeAll(valuesOnly, "Rotate", "Before", "After")
            assertTrue(written.toString(), written is ReencryptionWriter.AllOutcome.Written)
            assertEquals("alpha", valueOf(path, "vault_plain_header", "rotated-pass-29"))
            assertEquals("beta", valueOf(path, "vault_new_header", "rotated-pass-29"))
            assertEquals("left alone", "gamma", valueOf(path, "vault_other_password", VaultVectors.DEV))
        } finally {
            current.zero()
            rotated.zero()
        }
    }

    fun testANewEnvelopeThatDoesNotVerifyIsLeftOutAndNeverWritten() {
        val path = "$root/group_vars/all/vault.yml"
        write(path, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Old0Db0Password0Value0001", VaultVectors.PW1)))
        ready()
        val before = bytes(path)
        // The last digit of the ciphertext changes after encrypting: the HMAC no longer matches.
        ReencryptionEngine.tamperForTests = { text ->
            val body = text.trimEnd('\n')
            val high = body[body.length - 2]
            val low = body.last()
            val changed = if (high == '6') (if (low == '6') '5' else low + 1) else (if (low == '9') '8' else low + 1)
            body.dropLast(1) + changed + "\n"
        }

        regenerate(vf(path))

        assertTrue("nothing written", before.contentEquals(bytes(path)))
        val report = feedback().single()
        assertTrue(report, report.contains("No secret was regenerated.") && report.contains("vault_db_password in vault.yml (the new envelope did not decrypt again)"))

        // R18's Rekey verifies too, and leaves the file alone.
        prompts.identity = { "prod" }
        VaultFileOperations.getInstance(project).rekey(listOf(vf(path)))
        waitFor("rekey timed out") { VaultFileOperations.getInstance(project).lastOperation?.isCompleted == true }
        assertTrue(before.contentEquals(bytes(path)))
        val rekey = feedback().last()
        assertTrue(rekey, rekey.contains("vault.yml: a new envelope did not decrypt again with its secret, so nothing was written"))
    }

    fun testAnAllOrNothingWriteStopsWhenADocumentChangedAfterThePreflight() {
        val a = "$root/group_vars/app/vault.yml"
        val b = "$root/group_vars/db/vault.yml"
        write(a, VaultVectors.inline("vault_app_password", VaultVectors.encrypt("Old0App0Password000001", VaultVectors.PW1)))
        write(b, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Old0Db0Password0000001", VaultVectors.PW1)))
        ready()
        val falcon = root(root)
        val beforeA = bytes(a)
        val items = regenerateItems(falcon, a, b)
        val plan = await { engine.prepare(items, ReencryptionRun(VaultPurpose.REKEY)) }
        assertTrue(plan.items.all { it.ready != null })

        val document = FileDocumentManager.getInstance().getDocument(vf(b))!!
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "# edited meanwhile\n") }
        PsiDocumentManager.getInstance(project).commitDocument(document)
        val outcome = writer.writeAll(plan, "Regenerate 2 secrets", "Before", "After")

        val stale = (outcome as ReencryptionWriter.AllOutcome.NotWritten).stale
        assertTrue(stale is ReencryptionWriter.Stale.Changed)
        assertEquals("the summary names this file", vf(b), stale.file)
        assertTrue("nothing written, not even the unchanged file", beforeA.contentEquals(bytes(a)))
        assertEquals("Old0App0Password000001", valueOf(a, "vault_app_password"))
        FileDocumentManager.getInstance().saveDocument(document)
    }

    fun testOneUndoRestoresEveryOldEnvelopeAcrossFilesAndLocalHistoryHasTheLabels() {
        val a = "$root/group_vars/app/vault.yml"
        val b = "$root/group_vars/db/vault.yml"
        write(a, VaultVectors.inline("vault_app_password", VaultVectors.encrypt("Old0App0Password000001", VaultVectors.PW1)))
        write(b, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Old0Db0Password0000001", VaultVectors.PW1, "new")))
        ready()
        val falcon = root(root)
        val beforeA = text(a)
        val beforeB = text(b)
        val items = regenerateItems(falcon, a, b)
        val plan = await { engine.prepare(items, ReencryptionRun(VaultPurpose.REKEY)) }

        val outcome = writer.writeAll(plan, "Regenerate 2 secrets", "Before regenerating 2 secrets", "Regenerated 2 secrets")

        val written = outcome as ReencryptionWriter.AllOutcome.Written
        assertEquals(2, written.values)
        assertEquals(listOf(vf(a), vf(b)), written.files)
        assertEquals("New0Value0For0Tests0001", valueOf(a, "vault_app_password"))
        assertEquals("New0Value0For0Tests0001", valueOf(b, "vault_db_password"))
        assertTrue(text(b).contains("\$ANSIBLE_VAULT;1.2;AES256;new\n"))
        val label = written.labelBefore!!
        assertTrue("the label before the write exists", await { LocalHistory.getInstance().isLabelValid(project, label) })

        undoOnce()
        assertEquals(beforeA, text(a))
        assertEquals(beforeB, text(b))
    }
}
