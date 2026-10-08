package de.terletzkiy.ansibility.vault.actions

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.IntentionActionBean
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.codeInsight.intention.impl.preview.IntentionPreviewPopupUpdateProcessor
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.ExtensionTestUtil
import de.terletzkiy.ansibility.coexist.vault.VaultEditorLookup
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.vault.VaultVectors
import de.terletzkiy.ansibility.vault.ui.VaultUiTestCase
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * F7.3–F7.6 and the availability of every vault intention: where they are offered (vault values; plain values of
 * Ansible file kinds, never keys, anchors, tagged values or argument specs), their HTML previews (never the value
 * nor the ciphertext), Vault Editor coexistence, and what they write.
 */
class VaultValueIntentionsTest : VaultUiTestCase() {
    private lateinit var root: String
    private val vaultFile get() = "$root/environments/prod/group_vars/all/vault.yml"
    private val varsFile get() = "$root/environments/prod/group_vars/all/vars.yml"

    private val vaultIntentions = listOf(
        VaultRevealIntention().text, VaultCopyIntention().text, VaultEditIntention().text, VaultDecryptToPlainIntention().text,
        VaultRekeyIntention().text, VaultChangeIdIntention().text,
    )
    private val encryptText = VaultEncryptIntention().text

    override fun setUp() {
        super.setUp()
        root = defaultRoot(cfg = "[defaults]\nvault_identity_list = dev@dev.pw, prod@prod.pw\n")
        write("$root/dev.pw", "${VaultVectors.DEV}\n")
        write("$root/prod.pw", "${VaultVectors.PROD}\n")
        write(vaultFile, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("hello world", VaultVectors.PW1)) +
            VaultVectors.inline("vault_templated", VaultVectors.encrypt("pre {{ x }} post", VaultVectors.PW1)) +
            VaultVectors.inline("vault_mislabelled", VaultVectors.encrypt("label lies", VaultVectors.PROD, "dev")) +
            "vault_quoted: !vault \"" + VaultVectors.encrypt("quoted secret", VaultVectors.PW1).format().replace("\n", "\\n") + "\"\n")
        write(varsFile, "db_port: 5432\nquoted: \"text\"\nanchored: &a value\ntagged: !!str 12\ntemplated: \"{{ other }}\"\nvault_plain: s3cret  # rotate\n" +
            "list:\n  - item\nmultiline: |\n  line one\n  line two\n")
        write("$root/roles/web/meta/argument_specs.yml", "argument_specs:\n  main:\n    options:\n      web_port:\n        type: int\n")
        write("$root/roles/web/defaults/main.yml", "web_port: 8080\n")
        root(root)
    }

    private fun open(path: String) = myFixture.configureFromExistingVirtualFile(vf(path))

    private fun caretAt(key: String, delta: Int = 0) {
        val text = myFixture.editor.document.text
        val match = Regex("(?m)^\\s*(- )?${Regex.escape(key)}:").find(text) ?: error("no $key")
        myFixture.editor.caretModel.moveToOffset(match.range.first + match.value.indexOf(key) + delta)
    }

    private fun caretOnValue(key: String) {
        val text = myFixture.editor.document.text
        val match = Regex("(?m)^\\s*${Regex.escape(key)}: *").find(text) ?: error("no $key")
        myFixture.editor.caretModel.moveToOffset(match.range.last + 2)
    }

    private fun vaultTexts(): Set<String> = myFixture.availableIntentions.filter { it.familyName == "Ansibility Vault" }.map { it.text }.toSet()

    private fun intention(text: String): IntentionAction = myFixture.availableIntentions.single { it.text == text }

    private fun valueOf(key: String): String = runReadActionBlocking {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val text = myFixture.editor.document.text
        val match = Regex("(?m)^\\s*${Regex.escape(key)}:").find(text) ?: error("no $key")
        val element = myFixture.file.findElementAt(match.range.first + match.value.indexOf(key))!!
        ((element.parent as org.jetbrains.yaml.psi.YAMLKeyValue).value as YAMLScalar).textValue
    }

    /** The preview the platform shows for [text] (computed as the platform does, on a copy of the file). */
    private fun preview(text: String): String {
        val info = IntentionPreviewPopupUpdateProcessor.getPreviewInfo(project, intention(text), myFixture.file, myFixture.editor)
        return (info as? IntentionPreviewInfo.Html)?.content()?.toString() ?: error("$text: no HTML preview but $info")
    }

    private fun launch(text: String) {
        myFixture.launchAction(intention(text))
        awaitAction()
    }

    fun testTheIntentionsAreRegisteredUnderAnsibilityVault() {
        val intentions = listOf(
            VaultRevealIntention(), VaultCopyIntention(), VaultEditIntention(), VaultEncryptIntention(), VaultDecryptToPlainIntention(),
            VaultRekeyIntention(), VaultChangeIdIntention(),
        )
        val beans = ExtensionPointName<IntentionActionBean>("com.intellij.intentionAction").extensionList
            .filter { it.className.startsWith(VaultIntentionBase::class.java.packageName + ".") }
        assertEquals(intentions.map { it.javaClass.name }.toSet(), beans.map { it.className }.toSet())
        // R19 (D141): category Ansibility › Vault like the inspections and the settings page, family "Ansibility Vault"
        // (R18: Ansibility › Ansible Vault and "Ansible Vault"); the intention texts stay unprefixed.
        for (bean in beans) assertEquals(bean.className, listOf("Ansibility", "Vault"), bean.categories?.toList())
        for (intention in intentions) {
            assertFalse("classic intentions: crypto in the background, the write on the EDT", intention.startInWriteAction())
            assertEquals("Ansibility Vault", intention.familyName)
            assertFalse(intention.text, intention.text.startsWith("Ansibility"))
        }
    }

    fun testVaultValuesOfferTheVaultIntentionsOnTheKeyAndTheValue() {
        open(vaultFile)
        caretAt("vault_db_password")
        assertEquals(vaultIntentions.toSet(), vaultTexts())
        caretAt("vault_db_password", 20)
        assertEquals("on the tag", vaultIntentions.toSet(), vaultTexts())
        caretAt("vault_quoted")
        assertEquals("quoted vault values too", vaultIntentions.toSet(), vaultTexts())
        assertEquals(PriorityAction.Priority.HIGH, VaultRevealIntention().priority)
    }

    fun testPlainValuesOfferEncryptOnlyWhereAnsibleLoadsVault() {
        open(varsFile)
        caretOnValue("db_port")
        assertEquals(setOf(encryptText), vaultTexts())
        caretAt("db_port")
        assertEmpty("never on a key", vaultTexts())
        caretAt("vault_plain")
        assertEquals("the key of a plaintext vault_* variable (ANS-X001)", setOf(encryptText), vaultTexts())
        caretOnValue("quoted")
        assertEquals(setOf(encryptText), vaultTexts())
        caretOnValue("multiline")
        assertEquals("literal blocks too", setOf(encryptText), vaultTexts())
        caretOnValue("anchored")
        assertEmpty("never on an anchored value", vaultTexts())
        caretOnValue("tagged")
        assertEmpty("never on a tagged value", vaultTexts())

        open("$root/roles/web/meta/argument_specs.yml")
        caretOnValue("type")
        assertEmpty("never in argument specs", vaultTexts())
        open("$root/roles/web/defaults/main.yml")
        caretOnValue("web_port")
        assertEquals(setOf(encryptText), vaultTexts())

        write("$root/tasks/users.yml", "- name: Add the deploy user\n  ansible.builtin.user:\n    name: deploy\n    password: s3cret\n")
        write("$root/vars/extra.yml", "api_token: abc\n")
        write("$root/docs/notes.yml", "note: plain\n")
        refresh()
        open("$root/tasks/users.yml")
        caretOnValue("password")
        assertEquals("module options in a playbook-level task file", setOf(encryptText), vaultTexts())
        open("$root/vars/extra.yml")
        caretOnValue("api_token")
        assertEquals("a vars_files / include_vars target", setOf(encryptText), vaultTexts())
        open("$root/docs/notes.yml")
        caretOnValue("note")
        assertEmpty("not in YAML Ansible never loads", vaultTexts())
    }

    fun testNothingIsOfferedOutsideAnAnsibleRoot() {
        write("elsewhere/vault.yml", VaultVectors.inline("vault_x", VaultVectors.encrypt("x", VaultVectors.PW1)) + "plain: 1\n")
        refresh()
        open("elsewhere/vault.yml")
        caretAt("vault_x")
        assertEmpty(vaultTexts())
        caretOnValue("plain")
        assertEmpty(vaultTexts())
    }

    fun testVaultEditorCoexistenceHidesEditEncryptAndLiteralGutterOnly() {
        ExtensionTestUtil.maskExtensions(VaultEditorLookup.EP_NAME, listOf(LoadedVaultEditor), testRootDisposable)
        open(vaultFile)
        caretAt("vault_db_password")
        assertEquals(vaultIntentions.toSet() - VaultEditIntention().text, vaultTexts())
        caretAt("vault_quoted")
        assertEquals("its edit does not cover quoted values", vaultIntentions.toSet(), vaultTexts())
        open(varsFile)
        caretOnValue("db_port")
        assertEmpty(vaultTexts())
    }

    fun testPreviewsDescribeWithoutShowingValueOrCiphertext() {
        open(vaultFile)
        caretAt("vault_db_password")
        val hex = myFixture.editor.document.text.lines()[2].trim()
        for (text in vaultIntentions) {
            val preview = preview(text)
            assertTrue("$text has a preview", preview.isNotBlank())
            assertFalse(preview, preview.contains("hello world") || preview.contains(hex) || preview.contains("ANSIBLE_VAULT"))
        }
        open(varsFile)
        caretOnValue("db_port")
        val preview = preview(encryptText)
        assertTrue(preview, preview.contains("db_port") && preview.contains("git history"))
        assertTrue(preview, preview.contains("dev, prod, default"))
        assertFalse(preview, preview.contains("5432"))
        assertEquals("previews never decrypt", 0, crypto.plaintextCount)
        assertEmpty(prompter.consentRequests)
    }

    fun testEncryptWarnsAboutTheTypeAndWritesABlockThatDecrypts() {
        open(varsFile)
        caretOnValue("db_port")
        prompts.encrypt = { "default" }
        launch(encryptText)
        val request = prompts.encryptRequests.single()
        assertEquals("db_port", request.keyName)
        assertEquals(listOf("YAML loads this value as int. Once encrypted, Ansible loads it as a string."), request.warnings)
        assertEquals(listOf("dev", "prod", "default"), request.choices.map { it.label })
        assertFalse(request.warnings.any { "5432" in it })
        val text = myFixture.editor.document.text
        assertTrue(text, text.startsWith("db_port: !vault |\n  \$ANSIBLE_VAULT;1.1;AES256\n"))
        assertEquals("5432", decryptWith(valueOf("db_port"), VaultVectors.PW1))
        assertTrue("the rest is untouched", text.contains("\nquoted: \"text\"\n"))
    }

    fun testEncryptKeepsASameLineCommentOnTheTagLine() {
        open(varsFile)
        caretAt("vault_plain")
        prompts.encrypt = { "dev" }
        launch(encryptText)
        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("vault_plain: !vault | # rotate\n  \$ANSIBLE_VAULT;1.2;AES256;dev\n"))
        assertEquals("s3cret", decryptWith(valueOf("vault_plain"), VaultVectors.DEV))
        assertTrue(prompts.encryptRequests.single().warnings.isEmpty())
    }

    fun testEncryptWithTheOnlyIdAndNoWarningAsksNothing() {
        val single = defaultRoot("single")
        write("$single/group_vars/all/vars.yml", "secret: \"text\"\n")
        root(single)
        open("$single/group_vars/all/vars.yml")
        caretOnValue("secret")
        launch(encryptText)
        assertEmpty(prompts.encryptRequests)
        assertEquals("text", decryptWith(valueOf("secret"), VaultVectors.PW1))
    }

    fun testEncryptPreselectsTheNeighboursLabelAndCancelWritesNothing() {
        write(varsFile, "existing: !vault |\n" + VaultVectors.encrypt("x", VaultVectors.PROD, "prod").formatLines().joinToString("") { "  $it\n" } + "next: value\n")
        refresh()
        open(varsFile)
        val before = myFixture.editor.document.text
        caretOnValue("next")
        prompts.encrypt = { null }
        launch(encryptText)
        assertEquals("prod", prompts.encryptRequests.single().preselected)
        assertEquals(before, myFixture.editor.document.text)
    }

    fun testDecryptToPlainAsksFirstAndWritesAQuotedString() {
        open(vaultFile)
        caretAt("vault_db_password")
        prompts.decrypt = false
        val before = myFixture.editor.document.text
        launch(VaultDecryptToPlainIntention().text)
        assertEquals("cancel writes nothing", before, myFixture.editor.document.text)
        assertEquals("nothing was decrypted before you agreed", 0, crypto.plaintextCount)

        prompts.decrypt = true
        launch(VaultDecryptToPlainIntention().text)
        assertEquals("vault_db_password", prompts.decryptRequests.last().keyName)
        assertTrue(myFixture.editor.document.text.startsWith("vault_db_password: \"hello world\"\nvault_templated: !vault |\n"))
        assertEquals("hello world", valueOf("vault_db_password"))
    }

    fun testDecryptToPlainKeepsJinjaLiteralWithUnsafe() {
        open(vaultFile)
        caretAt("vault_templated")
        launch(VaultDecryptToPlainIntention().text)
        assertTrue(myFixture.editor.document.text.contains("vault_templated: !unsafe \"pre {{ x }} post\"\n"))
    }

    fun testRekeyToDevWritesADevHeader() {
        open(vaultFile)
        caretAt("vault_db_password")
        prompts.identity = { "dev" }
        launch(VaultRekeyIntention().text)
        assertEquals("default", prompts.identityRequests.single().preselected)
        val envelope = valueOf("vault_db_password")
        assertTrue(envelope, envelope.startsWith("\$ANSIBLE_VAULT;1.2;AES256;dev\n"))
        assertEquals("hello world", decryptWith(envelope, VaultVectors.DEV))
        assertNull(decryptWith(envelope, VaultVectors.PW1))
    }

    fun testChangeIdRelabelsOnlyAfterTheTargetVerifiedTheValue() {
        open(vaultFile)
        caretAt("vault_mislabelled")
        val before = valueOf("vault_mislabelled")
        assertTrue(before.startsWith("\$ANSIBLE_VAULT;1.2;AES256;dev\n"))
        prompts.identity = { "prod" }
        launch(VaultChangeIdIntention().text)
        assertFalse("the current label is not offered", prompts.identityRequests.single().choices.any { it.label == "dev" })
        val after = valueOf("vault_mislabelled")
        assertTrue(after, after.startsWith("\$ANSIBLE_VAULT;1.2;AES256;prod\n"))
        assertEquals("header only: the payload is unchanged", before.substringAfter('\n'), after.substringAfter('\n'))
        assertEmpty("no rekey question", prompts.rekeyQuestions)
    }

    fun testChangeIdToAnIdThatDoesNotDecryptAsksBeforeRekeying() {
        open(vaultFile)
        caretAt("vault_db_password")
        prompts.identity = { "prod" }
        prompts.rekeyForChangeId = false
        val before = valueOf("vault_db_password")
        launch(VaultChangeIdIntention().text)
        assertEquals(listOf("prod" to "default"), prompts.rekeyQuestions.toList())
        assertEquals("declined: nothing written", before, valueOf("vault_db_password"))

        prompts.rekeyForChangeId = true
        launch(VaultChangeIdIntention().text)
        val after = valueOf("vault_db_password")
        assertTrue(after.startsWith("\$ANSIBLE_VAULT;1.2;AES256;prod\n"))
        assertEquals("hello world", decryptWith(after, VaultVectors.PROD))
    }

    fun testCopyPutsTheValueOnTheClipboard() {
        open(vaultFile)
        caretAt("vault_quoted")
        launch(VaultCopyIntention().text)
        assertEquals("quoted secret", com.intellij.openapi.ide.CopyPasteManager.getInstance().getContents<String>(java.awt.datatransfer.DataFlavor.stringFlavor))
        scheduler.advance(de.terletzkiy.ansibility.vault.ui.VaultUiTimings.CLIPBOARD_MILLIS)
        assertFalse(com.intellij.openapi.ide.CopyPasteManager.getInstance().getContents<String>(java.awt.datatransfer.DataFlavor.stringFlavor) == "quoted secret")
    }

    fun testRekeyOfAQuotedValueWritesALiteralBlockAtTheKeyIndent() {
        open(vaultFile)
        caretAt("vault_quoted")
        prompts.identity = { "default" }
        launch(VaultRekeyIntention().text)
        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("vault_quoted: !vault |\n  \$ANSIBLE_VAULT;1.1;AES256\n"))
        assertEquals("quoted secret", decryptWith(valueOf("vault_quoted"), VaultVectors.PW1))
        assertEquals(VaultEnvelope.VERSION_1_1, VaultEnvelope.versionFor("default"))
    }

    fun testALockedIdIsUnlockedLazilyWithConsent() {
        open(vaultFile)
        caretAt("vault_db_password")
        assertEmpty(prompter.consentRequests)
        prompts.identity = { "dev" }
        launch(VaultRekeyIntention().text)
        assertEquals(1, prompter.consentRequests.size)
        assertTrue(valueOf("vault_db_password").startsWith("\$ANSIBLE_VAULT;1.2;AES256;dev\n"))
    }
}

/** Ansible Vault Editor loaded, with every intention of it enabled (the coexistence gate's input, F7.12). */
private object LoadedVaultEditor : VaultEditorLookup {
    override fun isLoaded(): Boolean = true

    override fun isIntentionEnabled(className: String): Boolean = true
}
