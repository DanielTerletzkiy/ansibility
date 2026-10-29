package de.terletzkiy.ansibility.vault.regenerate

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.ui.table.TableView
import com.intellij.util.ref.GCWatcher
import com.intellij.util.ui.UIUtil
import de.terletzkiy.ansibility.api.VaultDecryptResult
import de.terletzkiy.ansibility.api.VaultOperations
import de.terletzkiy.ansibility.api.VaultPurpose
import de.terletzkiy.ansibility.index.vault.VaultIndexQueries
import de.terletzkiy.ansibility.semantics.vault.GenerationRule
import de.terletzkiy.ansibility.semantics.vault.GeneratorKind
import de.terletzkiy.ansibility.semantics.vault.SecretGenerator
import de.terletzkiy.ansibility.vault.VaultVectors
import de.terletzkiy.ansibility.vault.actions.VaultEncryptIntention
import de.terletzkiy.ansibility.vault.actions.VaultFileOperations
import de.terletzkiy.ansibility.vault.actions.VaultValuePsi
import de.terletzkiy.ansibility.vault.actions.VaultValueRef
import de.terletzkiy.ansibility.vault.identity.VaultProjectSettings
import de.terletzkiy.ansibility.vault.reencrypt.Decryption
import de.terletzkiy.ansibility.vault.reencrypt.Encryption
import de.terletzkiy.ansibility.vault.reencrypt.NewPlaintext
import de.terletzkiy.ansibility.vault.reencrypt.PreparedPlan
import de.terletzkiy.ansibility.vault.reencrypt.ReencryptItem
import de.terletzkiy.ansibility.vault.reencrypt.ReencryptTarget
import de.terletzkiy.ansibility.vault.reencrypt.ReencryptionEngine
import de.terletzkiy.ansibility.vault.reencrypt.ReencryptionRun
import de.terletzkiy.ansibility.vault.reencrypt.ReencryptionWriter
import de.terletzkiy.ansibility.vault.reencrypt.SharedValue
import de.terletzkiy.ansibility.vault.ui.settings.VaultConfigurable
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import java.io.StringReader

/**
 * Regression tests of the review of plan amendment R29 step A (commit 10b0237, `A-review.md`) and its fixes. The first
 * six tests are the review's own, kept as written: a new value opens with the secret that opened the old one when two
 * secrets share the id (D220), flow mappings stay loadable YAML, the intentions work in an editor with unsaved edits, and
 * a repository folder never reaches into a detached worktree (DEV rule 6). The rest cover the other fixes.
 */
class RegenerateRegressionTest : RegenerateTestCase() {
    private val vaultPath get() = "$root/group_vars/all/vault.yml"

    /** Null when [text] is YAML that a YAML 1.1 loader (SnakeYAML, like PyYAML) can compose; else the parser's message. */
    private fun yamlError(text: String): String? = try {
        Yaml(LoaderOptions()).composeAll(StringReader(text)).toList()
        null
    } catch (e: Exception) {
        e.message
    }

    /** The `!vault` values of [relative], as the vault services read them (PSI, the codec's envelope text). */
    private fun values(relative: String): List<VaultValueRef> = runReadActionBlocking {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        VaultValuePsi.vaultScalars(PsiManager.getInstance(project).findFile(vf(relative))!!).mapNotNull(VaultValueRef::of)
    }

    // ------------------------------------------------------------------------------------------------ the review's tests

    /**
     * D220: "Each value with the id and header its old envelope had (the password that opened it)". Two secrets share
     * the id `default` (an explicit `vault_identity_list` entry and the root's `.vault-pass`, which Ansible both keeps);
     * the value was encrypted with the second one. The new value must open with that one.
     */
    fun testANewValueOpensWithThePasswordThatOpenedTheOldOneWhenTwoSecretsShareTheId() {
        val heron = defaultRoot("heron", cfg = "[defaults]\nvault_identity_list = default@other.pw\n")
        write("$heron/other.pw", "${VaultVectors.DEV}\n")
        val path = "$heron/group_vars/all/vault.yml"
        write(path, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Old0Db0Password0Value0001", VaultVectors.PW1)))
        root(heron)
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        regenerate(vf(path))

        assertEquals("default", row("vault_db_password").identity)
        val report = feedback().single()
        assertTrue(report, report.startsWith("1 secret regenerated in 1 file."))
        val envelope = envelopeOf(text(path), "vault_db_password")
        val withOpener = decryptWith(envelope, VaultVectors.PW1)
        val withOther = decryptWith(envelope, VaultVectors.DEV)
        assertNotNull(
            "D220: the new value must open with the password that opened the old one (.vault-pass); " +
                "it opens with that password: ${withOpener != null}, with the other 'default' secret: ${withOther != null}",
            withOpener,
        )
    }

    /** A plaintext `vault_*` placeholder in a flow mapping must not become a `!vault |` block, which YAML forbids there. */
    fun testAPlaceholderInAFlowMappingLeavesLoadableYaml() {
        val path = "$root/group_vars/all/vault.yml"
        write(path, "db: {vault_db_password: CHANGEME, port: 5432}\nother: 1\n")
        ready()
        assertNull("the input loads", yamlError(text(path)))
        prompts.identity = { "dev" }

        regenerate(vf(path))

        val after = text(path)
        val report = feedback()
        val error = yamlError(after)
        assertNull("after Regenerate Secrets ($report) the file must still load, but: $error\n$after", error)
    }

    /** D215 / X97: the intentions run in the editor, where the document usually has unsaved edits. */
    fun testGenerateAndEncryptWorksInAnEditorWithUnsavedEdits() {
        val single = defaultRoot("heron")
        val path = "$single/group_vars/all/vault.yml"
        write(path, "db_port: 5432\n")
        root(single)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        myFixture.configureFromExistingVirtualFile(vf(path))
        val document = myFixture.editor.document
        // You type the placeholder and press Alt+Enter right away.
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, "vault_new_password: CHANGEME\n") }
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(document)
        myFixture.editor.caretModel.moveToOffset(document.text.indexOf("vault_new_password") + 2)
        val intention = myFixture.availableIntentions.single { it.text == VaultGenerateAndEncryptIntention().text }

        myFixture.launchAction(intention)
        awaitRun()

        val report = feedback()
        assertFalse("the placeholder is replaced; report: $report\ndocument:\n${document.text}", "CHANGEME" in document.text)
    }

    fun testRegenerateSecretWorksInAnEditorWithUnsavedEdits() {
        val single = defaultRoot("heron")
        val path = "$single/group_vars/all/vault.yml"
        val old = VaultVectors.encrypt("Old0Db0Password0Value0001", VaultVectors.PW1)
        write(path, VaultVectors.inline("vault_db_password", old))
        root(single)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        myFixture.configureFromExistingVirtualFile(vf(path))
        val document = myFixture.editor.document
        // An unrelated edit elsewhere in the file, not yet saved.
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, "db_port: 5432\n") }
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(document)
        myFixture.editor.caretModel.moveToOffset(document.text.indexOf("vault_db_password") + 2)
        val intention = myFixture.availableIntentions.single { it.text == VaultRegenerateIntention().text }

        myFixture.launchAction(intention)
        awaitRun()

        val report = feedback()
        val oldBody = old.formatLines()[1]
        assertFalse("the value is regenerated; report: $report", oldBody in document.text)
    }

    /**
     * D215 "repositories" + DEV.md hard rule 6 (never a target from a detached worktree when the query came from
     * elsewhere): Regenerate Secrets… on a repository folder must not scan a git worktree checked out inside it.
     */
    fun testARepositoryFolderDoesNotRegenerateTheValuesOfAWorktreeInsideIt() {
        val same = "Same0Secret0Value0123456789"
        write("$root/group_vars/all/vault.yml", VaultVectors.inline("vault_db_password", VaultVectors.encrypt(same, VaultVectors.PW1)))
        write("repos/falcon/wt/.git", "gitdir: $base/repos/falcon/.git/worktrees/wt\n")
        val worktreeRoot = defaultRoot("falcon/wt")
        val worktreeVault = "$worktreeRoot/group_vars/all/vault.yml"
        write(worktreeVault, VaultVectors.inline("vault_db_password", VaultVectors.encrypt(same, VaultVectors.PW1)))
        ready()
        assertTrue("the worktree's root is detached", root(worktreeRoot).detached)
        val before = bytes(worktreeVault)

        regenerate(vf("repos/falcon"))

        val rows = regeneratePrompts.valueRequests.single().rows
        assertTrue("the main checkout's value is regenerated: ${feedback()}", rows.any { it.file == vf("$root/group_vars/all/vault.yml") })
        assertFalse(
            "no row of the detached worktree: ${rows.map { "${it.file.path.removePrefix(base.toString())} group=${it.group}" }}",
            rows.any { it.file == vf(worktreeVault) },
        )
        assertTrue("the worktree's file is untouched", before.contentEquals(bytes(worktreeVault)))
    }

    /** The same for a `!vault` value written double-quoted in a flow mapping. */
    fun testAQuotedVaultValueInAFlowMappingLeavesLoadableYaml() {
        val path = "$root/group_vars/all/vault.yml"
        val envelope = VaultVectors.encrypt("Old0Db0Password0Value0001", VaultVectors.PW1)
        val quoted = "\"" + envelope.formatLines().joinToString("\\n") + "\\n\""
        write(path, "db: {vault_db_password: !vault $quoted, port: 5432}\n")
        ready()
        assertNull("the input loads", yamlError(text(path)))

        regenerate(vf(path))

        val after = text(path)
        val report = feedback()
        val error = yamlError(after)
        assertNull("after Regenerate Secrets ($report) the file must still load, but: $error\n$after", error)
    }

    // ------------------------------------------------------------------------------------------------ fix 1: flow collections

    /**
     * A value in a flow collection is written as one double-quoted line (a `!vault` value keeps its tag, a placeholder
     * its anchor), loads as YAML, is indexed, reads back through the vault services (Reveal) and regenerates again.
     */
    fun testAFlowValueIsWrittenOnOneLineReadBackAndRegeneratedAgain() {
        val old = VaultVectors.encrypt("Old0Db0Password0Value0001", VaultVectors.PW1)
        write(
            vaultPath,
            "db: {vault_db_password: !vault \"" + old.formatLines().joinToString("\\n") + "\\n\", port: 5432}\n" +
                "apps: [{name: web, vault_web_password: &web CHANGEME}]\n" +
                "copy: *web\n",
        )
        ready()
        prompts.identity = { "dev" }

        regenerate(vf(vaultPath))

        val first = text(vaultPath)
        assertNull(first, yamlError(first))
        assertTrue(first, first.startsWith("db: {vault_db_password: !vault \"\$ANSIBLE_VAULT;1.1;AES256\\n"))
        assertTrue(first, first.contains(", port: 5432}\napps: [{name: web, vault_web_password: &web !vault \"\$ANSIBLE_VAULT;1.2;AES256;dev\\n"))
        assertTrue(first, first.endsWith("\"}]\ncopy: *web\n"))
        assertTrue(feedback().single().startsWith("2 secrets regenerated in 1 file."))
        val refs = values(vaultPath)
        assertEquals(listOf("vault_db_password", "vault_web_password"), refs.map { it.keyName })
        val db = decryptWith(refs[0].envelopeText, VaultVectors.PW1)!!
        val web = decryptWith(refs[1].envelopeText, VaultVectors.DEV)
        assertNotNull("the placeholder's new envelope reads back: ${refs[1].envelopeText.take(120)}", web)
        web!!
        assertTrue(db, db.length == 25 && db.all { it.isLetterOrDigit() } && db != "Old0Db0Password0Value0001")
        assertTrue(web, web.length == 32 && web.all { it.isLetterOrDigit() })
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertEquals("the index reads both", 2, runReadActionBlocking { VaultIndexQueries.envelopes(project, vf(vaultPath)) }.size)
        val revealed = await { VaultOperations.getInstance(project).decrypt(refs[0].location, VaultPurpose.REVEAL) }
        assertEquals("Reveal reads the one-line form", db, (revealed as VaultDecryptResult.Decrypted).plaintext.use { p -> p.read { String(it) } })

        regenerate(vf(vaultPath))

        val second = text(vaultPath)
        assertNull(second, yamlError(second))
        assertEquals(listOf(RowKind.VAULT_VALUE, RowKind.VAULT_VALUE), regeneratePrompts.valueRequests.last().rows.map { it.kind })
        val again = values(vaultPath)
        assertFalse(db == decryptWith(again[0].envelopeText, VaultVectors.PW1))
        assertFalse(web == decryptWith(again[1].envelopeText, VaultVectors.DEV))
        assertTrue(second, second.endsWith("\"}]\ncopy: *web\n"))
    }

    /** R18's Rekey and F7.3's Encrypt value share the writer: in a flow mapping they write the one-line form too. */
    fun testRekeyAndEncryptValueWriteLoadableFlowValues() {
        val old = VaultVectors.encrypt("Old0Db0Password0Value0001", VaultVectors.PW1)
        write(vaultPath, "db: {vault_db_password: !vault \"" + old.formatLines().joinToString("\\n") + "\", port: 5432}\n")
        val varsPath = "$root/group_vars/all/vars.yml"
        write(varsPath, "app: {api_token: s3cretToken, port: 8080}\n")
        ready()

        prompts.identity = { "prod" }
        VaultFileOperations.getInstance(project).rekey(listOf(vf(vaultPath)))
        waitFor("rekey timed out") { VaultFileOperations.getInstance(project).lastOperation?.isCompleted == true }
        val rekeyed = text(vaultPath)
        assertNull(rekeyed, yamlError(rekeyed))
        assertTrue(rekeyed, rekeyed.startsWith("db: {vault_db_password: !vault \"\$ANSIBLE_VAULT;1.2;AES256;prod\\n") && rekeyed.endsWith("\", port: 5432}\n"))
        assertEquals("Old0Db0Password0Value0001", decryptWith(values(vaultPath).single().envelopeText, VaultVectors.PROD))

        myFixture.configureFromExistingVirtualFile(vf(varsPath))
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("s3cretToken") + 2)
        prompts.encrypt = { "dev" }
        myFixture.launchAction(myFixture.availableIntentions.single { it.text == VaultEncryptIntention().text })
        awaitAction()
        val encrypted = myFixture.editor.document.text
        assertNull(encrypted, yamlError(encrypted))
        assertTrue(encrypted, encrypted.startsWith("app: {api_token: !vault \"\$ANSIBLE_VAULT;1.2;AES256;dev\\n") && encrypted.endsWith("\", port: 8080}\n"))
        FileDocumentManager.getInstance().saveAllDocuments()
        assertEquals("s3cretToken", decryptWith(values(varsPath).single().envelopeText, VaultVectors.DEV))
    }

    // ------------------------------------------------------------------------------------------------ fix 3: detached worktrees

    /** Selecting the worktree itself regenerates its values; equal values never form a group across the two sides. */
    fun testAWorktreeRegeneratesWhenSelectedItselfAndNeverSharesAGroupWithTheMainCheckout() {
        val same = "Same0Secret0Value0123456789"
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt(same, VaultVectors.PW1)))
        write("repos/falcon/wt/.git", "gitdir: $base/repos/falcon/.git/worktrees/wt\n")
        val worktreeRoot = defaultRoot("falcon/wt")
        val worktreeVault = "$worktreeRoot/group_vars/all/vault.yml"
        write(worktreeVault, VaultVectors.inline("vault_db_password", VaultVectors.encrypt(same, VaultVectors.PW1)))
        ready()
        assertTrue(root(worktreeRoot).detached)

        regenerate(vf(vaultPath), vf(worktreeVault))

        val rows = regeneratePrompts.valueRequests.last().rows
        assertEquals(2, rows.size)
        assertTrue("equal, but on two sides of DEV rule 6: no group", rows.all { it.group == null })
        val main = valueOf(vaultPath, "vault_db_password")!!
        val worktree = valueOf(worktreeVault, "vault_db_password")!!
        assertFalse("each got a value of its own", main == worktree)

        regenerate(vf("repos/falcon/wt"))

        assertEquals(listOf(vf(worktreeVault)), regeneratePrompts.valueRequests.last().rows.map { it.file })
        assertFalse(worktree == valueOf(worktreeVault, "vault_db_password"))
        assertEquals("the main checkout is not touched from the worktree", main, valueOf(vaultPath, "vault_db_password"))
    }

    // ------------------------------------------------------------------------------------------------ fix 7: Cancel during the write

    /** A Cancel that arrives while the write command runs no longer swallows the summary of what was written. */
    fun testACancelDuringTheWriteStillShowsTheSummary() {
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Old0Db0Password0Value0001", VaultVectors.PW1)))
        ready()
        val document = FileDocumentManager.getInstance().getDocument(vf(vaultPath))!!
        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                service.lastRun?.cancel()
            }
        }, testRootDisposable)

        regenerate(vf(vaultPath))

        assertTrue("the run was cancelled", service.lastRun!!.isCancelled)
        val report = feedback()
        assertTrue(report.toString(), report.singleOrNull()?.startsWith("1 secret regenerated in 1 file.") == true)
        assertFalse("Old0Db0Password0Value0001" == valueOf(vaultPath, "vault_db_password"))
    }

    // ------------------------------------------------------------------------------------------------ fix 8: re-lock during the dialog

    /** A root that locked while the dialog was open (auto-lock, Lock All) is unlocked again before encrypting. */
    fun testARootThatLockedWhileTheDialogWasOpenIsUnlockedAgain() {
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Old0Db0Password0Value0001", VaultVectors.PW1)))
        ready()
        val falcon = root(root)
        regeneratePrompts.onValues = {
            assertFalse("unlocked for the inspection", secrets.unlockedLabels(registry.discovery(falcon)).isEmpty())
            secrets.lockAll()
            true
        }

        regenerate(vf(vaultPath))

        val report = feedback().single()
        assertTrue(report, report.startsWith("1 secret regenerated in 1 file."))
        assertFalse("Old0Db0Password0Value0001" == valueOf(vaultPath, "vault_db_password"))
    }

    // ------------------------------------------------------------------------------------------------ fix 10: Apply to Selected

    /** The fields show the selected row's generator; Apply changes only the kind or length you changed. */
    fun testApplyToSelectedKeepsCaseAndPaddingAndChangesOnlyWhatYouChanged() {
        write(
            vaultPath,
            VaultVectors.inline("vault_api_token", VaultVectors.encrypt("0F1E2D3C4B5A69788796A5B4C3D2E1F0", VaultVectors.PW1)) +
                VaultVectors.inline("vault_session_key", VaultVectors.encrypt("q83vEjRWeJq8ELYyzw_D-8rG2RkzWl1vrc-1FnN8E2U=", VaultVectors.PW1)) +
                VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Old0Db0Password0Value0001", VaultVectors.PW1)),
        )
        ready()
        regeneratePrompts.onValues = { request ->
            val dialog = RegenerateDialog(project, request)
            try {
                val token = request.rows.single { it.keyPath == "vault_api_token" }
                val session = request.rows.single { it.keyPath == "vault_session_key" }
                val db = request.rows.single { it.keyPath == "vault_db_password" }
                dialog.select(token)
                assertEquals(SecretGenerator(GeneratorKind.HEX, 32, upperHex = true), dialog.fields.generator())
                dialog.fields.length.number = 40
                dialog.applyGenerator()
                assertEquals("the case stays", SecretGenerator(GeneratorKind.HEX, 40, upperHex = true), token.generator)
                assertEquals(GeneratorSource.CHOSEN, token.source)

                dialog.select(session)
                assertEquals(SecretGenerator(GeneratorKind.BASE64_URL, 32, padded = true), dialog.fields.generator())
                dialog.fields.length.number = 40
                dialog.applyGenerator()
                assertEquals("the padding stays", SecretGenerator(GeneratorKind.BASE64_URL, 40, padded = true), session.generator)

                // Two rows of two kinds and only the length changed: each keeps its kind and details.
                dialog.select(token, db)
                dialog.fields.length.number = 24
                dialog.applyGenerator()
                assertEquals(SecretGenerator(GeneratorKind.HEX, 24, upperHex = true), token.generator)
                assertEquals(SecretGenerator(GeneratorKind.ALPHANUMERIC, 24), db.generator)

                // Another kind: that kind's plain generator, each row's length kept.
                dialog.select(db)
                dialog.fields.kind.selectedItem = GeneratorKind.HEX
                dialog.applyGenerator()
                assertEquals(SecretGenerator(GeneratorKind.HEX, 24), db.generator)
            } finally {
                dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
            }
            true
        }

        regenerate(vf(vaultPath))

        val token = valueOf(vaultPath, "vault_api_token")!!
        assertTrue(token, token.length == 24 && token.all { it in "0123456789ABCDEF" })
        val session = valueOf(vaultPath, "vault_session_key")!!
        assertTrue("40 bytes, URL-safe, padded: $session", session.length == 56 && session.endsWith("==") && session.none { it == '+' || it == '/' })
        val db = valueOf(vaultPath, "vault_db_password")!!
        assertTrue(db, db.length == 24 && db.all { it in "0123456789abcdef" })
    }

    // ------------------------------------------------------------------------------------------------ fix 11, 12: header and symbols

    /** `1.2;AES256;default` is kept as written (a `vault_id_match` setup matches it by its label). */
    fun testAHeaderWithTheDefaultLabelIsKeptAsWritten() {
        val lines = VaultVectors.encrypt("Old0Db0Password0Value0001", VaultVectors.PW1).formatLines().toMutableList()
        lines[0] = "\$ANSIBLE_VAULT;1.2;AES256;default"
        write(vaultPath, "vault_db_password: !vault |\n" + lines.joinToString("") { "  $it\n" })
        ready()

        regenerate(vf(vaultPath))

        val after = text(vaultPath)
        assertTrue(after, after.startsWith("vault_db_password: !vault |\n  \$ANSIBLE_VAULT;1.2;AES256;default\n"))
        val value = valueOf(vaultPath, "vault_db_password")!!
        assertTrue(value, value.length == 25 && value != "Old0Db0Password0Value0001")
    }

    /** "Same as now" for a value with symbols uses only the symbols it has (a password inside a URL gets no `#`). */
    fun testSameAsNowKeepsOnlyTheSymbolsTheValueHas() {
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("db.Pass-word.2026.xyz", VaultVectors.PW1)))
        ready()

        regenerate(vf(vaultPath))

        assertEquals("same as now: 21 alphanumeric with the symbols it has now", RegenerateTexts.source(row("vault_db_password")))
        val value = valueOf(vaultPath, "vault_db_password")!!
        assertTrue(value, value.length == 21 && value[0].isLetterOrDigit() && value.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' })
    }

    // ------------------------------------------------------------------------------------------------ fix 13: weak rules

    /** A row whose new value has fewer than 16 characters is short whatever made its generator: shape, rule or you. */
    fun testARowIsShortWhenItsNewValueIsShortWhateverMadeTheGenerator() {
        write(vaultPath, VaultVectors.inline("vault_vrrp_password", VaultVectors.encrypt("Long0Enough0Password0001", VaultVectors.PW1)) +
            VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Old0Db0Password0Value0001", VaultVectors.PW1)) +
            VaultVectors.inline("vault_pin", VaultVectors.encrypt("Ab3dEf7h", VaultVectors.PW1)))
        ready()
        val rootKey = registry.discovery(root(root)).rootKey
        VaultProjectSettings.getInstance(project).update(rootKey) { it.copy(generationRules = listOf(GenerationRule("vault_vrrp_*", GeneratorKind.ALPHANUMERIC, 8))) }
        regeneratePrompts.onValues = { request ->
            val vrrp = request.rows.single { it.keyPath == "vault_vrrp_password" }
            val db = request.rows.single { it.keyPath == "vault_db_password" }
            val pin = request.rows.single { it.keyPath == "vault_pin" }
            assertTrue("a short rule", vrrp.short)
            assertTrue("a short value, same as now", pin.short)
            assertFalse(db.short)
            request.setGenerator(listOf(db), SecretGenerator(GeneratorKind.ALPHANUMERIC, 12))
            assertTrue("a short choice", db.short)
            request.setGenerator(listOf(pin), SecretGenerator(GeneratorKind.BASE64, 12))
            assertFalse("12 bytes of base64 are 16 characters", pin.short)
            false
        }

        regenerate(vf(vaultPath))

        assertEquals(1, regeneratePrompts.valueRequests.size)
    }

    /** The settings table warns on rules shorter than 16 characters and on patterns it cannot read; Apply refuses those. */
    fun testTheRulesTableWarnsOnShortRulesAndRefusesPatternsItCannotRead() {
        ready()
        val rootKey = registry.discovery(root(root)).rootKey
        VaultProjectSettings.getInstance(project).update(rootKey) {
            it.copy(generationRules = listOf(GenerationRule("vault_vrrp_password", GeneratorKind.ALPHANUMERIC, 8), GenerationRule("vault_*", GeneratorKind.HEX, 32)))
        }
        val page = VaultConfigurable(project)
        try {
            val component = page.createComponent()!!
            val roots = UIUtil.findComponentsOfType(component, ComboBox::class.java).first()
            PlatformTestUtil.waitWithEventsDispatching("the roots did not load", { roots.itemCount > 0 }, 60)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val warning = page.ruleWarningText()
            assertNotNull("a warning for the short rule", warning)
            assertTrue(warning!!, warning.contains("shorter than 16 characters") && warning.contains("vault_vrrp_password (8 alphanumeric)"))
            assertFalse(warning, warning.contains("vault_*"))

            val rules = UIUtil.findComponentsOfType(component, TableView::class.java).single { it.model.getColumnName(0) == "Key pattern" }
            rules.listTableModel.setValueAt("vault_[ab", 1, 0)
            val unreadable = page.ruleWarningText()!!
            assertTrue(unreadable, unreadable.contains("cannot be read") && unreadable.contains("vault_[ab"))
            val refused = try {
                page.apply()
                null
            } catch (e: ConfigurationException) {
                e
            }
            assertNotNull("Apply refuses a pattern it cannot read", refused)
            assertTrue(refused!!.message, refused.message!!.contains("vault_[ab"))
            assertEquals("nothing stored", listOf("vault_vrrp_password", "vault_*"), VaultProjectSettings.getInstance(project).rootSettings(rootKey).generationRules.map { it.pattern })
        } finally {
            page.disposeUIResources()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        }
    }

    // ------------------------------------------------------------------------------------------------ fix 14, 15: the write

    /**
     * The prepared plan holds the documents the preflight saw, so a garbage collection between the preflight and the
     * write cannot bring back a fresh document that looks changed; a document the IDE did replace counts as changed.
     */
    fun testAPreparedPlanHoldsItsDocumentsUntilTheWrite() {
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Old0Db0Password0Value0001", VaultVectors.PW1)))
        ready()
        val falcon = root(root)
        val items = values(vaultPath).map { ref ->
            ReencryptItem(falcon, ReencryptTarget.Value(ref), Decryption.RootSecrets, NewPlaintext.Replace(SharedValue(1) { "New0Value0For0Tests0001".toByteArray() }), Encryption.SameAsBefore)
        }
        val plan = await { ReencryptionEngine(project).prepare(items, ReencryptionRun(VaultPurpose.REKEY)) }
        val writer = ReencryptionWriter(project) { VaultFileOperations.FileModes.POSIX }

        GCWatcher.tracking(Any()).ensureCollected()

        assertSame(plan.documents[vf(vaultPath)], FileDocumentManager.getInstance().getCachedDocument(vf(vaultPath)))
        val replaced = PreparedPlan(plan.items, plan.sequences, mapOf(vf(vaultPath) to EditorFactory.getInstance().createDocument("x")))
        assertTrue(writer.staleFile(replaced) is ReencryptionWriter.Stale.Changed)
        assertNull(writer.staleFile(plan))
        assertTrue(writer.writeAll(plan, "Regenerate 1 secret", "Before", "After") is ReencryptionWriter.AllOutcome.Written)
        assertEquals("New0Value0For0Tests0001", valueOf(vaultPath, "vault_db_password"))
    }

    /**
     * When changing a document throws inside the one global command (here a document made read-only while its file stays
     * writable), the documents changed before get their old text back and nothing is written.
     */
    fun testAFailureInsideTheWriteGivesTheChangedDocumentsTheirOldTextBack() {
        val a = "$root/group_vars/app/vault.yml"
        val b = "$root/group_vars/web/vault.yml"
        write(a, VaultVectors.inline("vault_app_password", VaultVectors.encrypt("Old0App0Password000001", VaultVectors.PW1)))
        write(b, VaultVectors.inline("vault_web_password", VaultVectors.encrypt("Old0Web0Password000001", VaultVectors.PW1)))
        ready()
        val documents = FileDocumentManager.getInstance()
        val first = documents.getDocument(vf(a))!!
        val second = documents.getDocument(vf(b))!!
        val beforeFirst = first.text
        val beforeSecond = second.text
        second.setReadOnly(true)
        try {
            regenerate(vf(a), vf(b))
        } finally {
            second.setReadOnly(false)
        }

        val report = feedback().single()
        assertTrue(report, report.startsWith("Nothing was written: changing vault.yml failed"))
        assertEquals("the first document has its old text again", beforeFirst, first.text)
        assertEquals(beforeSecond, second.text)
        assertEquals("Old0App0Password000001", valueOf(a, "vault_app_password"))
        assertEquals("Old0Web0Password000001", valueOf(b, "vault_web_password"))
    }
}
