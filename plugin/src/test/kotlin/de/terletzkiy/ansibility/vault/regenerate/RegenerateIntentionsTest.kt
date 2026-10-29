package de.terletzkiy.ansibility.vault.regenerate

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.impl.preview.IntentionPreviewPopupUpdateProcessor
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.fileEditor.FileDocumentManager
import de.terletzkiy.ansibility.semantics.vault.GeneratorKind
import de.terletzkiy.ansibility.semantics.vault.SecretGenerator
import de.terletzkiy.ansibility.vault.VaultVectors

/**
 * Plan amendment R29: the intention "Regenerate secret" on one `!vault` value (X97, a small chooser preset from the
 * shape) and "Generate and encrypt" on a plaintext `vault_*` value (ANS-X001's case), with previews that name no value.
 */
class RegenerateIntentionsTest : RegenerateTestCase() {
    private val vaultPath get() = "$root/group_vars/all/vault.yml"
    private val regenerateText = VaultRegenerateIntention().text
    private val generateText = VaultGenerateAndEncryptIntention().text

    private fun open(path: String) = myFixture.configureFromExistingVirtualFile(vf(path))

    private fun caretAt(key: String, delta: Int = 0) {
        val text = myFixture.editor.document.text
        myFixture.editor.caretModel.moveToOffset(text.indexOf("$key:") + delta)
    }

    private fun texts(): Set<String> = myFixture.availableIntentions.map { it.text }.toSet()

    private fun intention(text: String): IntentionAction = myFixture.availableIntentions.single { it.text == text }

    private fun preview(text: String): String {
        val info = IntentionPreviewPopupUpdateProcessor.getPreviewInfo(project, intention(text), myFixture.file, myFixture.editor)
        return (info as? IntentionPreviewInfo.Html)?.content()?.toString() ?: error("$text: no HTML preview but $info")
    }

    fun testRegenerateSecretOffersTheShapeAndKeepsIdAndHeader() {
        write(vaultPath, VaultVectors.inline("vault_api_token", VaultVectors.encrypt("0f1e2d3c4b5a69788796a5b4c3d2e1f0", VaultVectors.DEV, "dev")) +
            "plain: 1\n")
        ready()
        open(vaultPath)
        caretAt("vault_api_token", 3)
        assertTrue(texts().contains(regenerateText))
        val attempts = crypto.decryptAttempts
        val preview = preview(regenerateText)
        assertTrue(preview, preview.contains("vault_api_token"))
        assertFalse(preview, preview.contains("0f1e2d3c") || preview.contains("ANSIBLE_VAULT"))
        assertEquals("previews never decrypt", attempts, crypto.decryptAttempts)

        myFixture.launchAction(intention(regenerateText))
        awaitRun()

        val request = regeneratePrompts.generatorRequests.single()
        assertEquals("vault_api_token", request.keyName)
        assertEquals(SecretGenerator(GeneratorKind.HEX, 32), request.preset)
        assertEquals("same as now: 32 hex", request.presetText)
        FileDocumentManager.getInstance().saveAllDocuments()
        val after = text(vaultPath)
        assertTrue(after, after.startsWith("vault_api_token: !vault |\n  \$ANSIBLE_VAULT;1.2;AES256;dev\n"))
        assertTrue(after.endsWith("plain: 1\n"))
        val value = valueOf(vaultPath, "vault_api_token", VaultVectors.DEV)!!
        assertTrue(value, value.length == 32 && value.all { it in "0123456789abcdef" } && value != "0f1e2d3c4b5a69788796a5b4c3d2e1f0")
        assertTrue(feedback().single().startsWith("1 secret regenerated in 1 file."))
        assertFalse("nothing on a plain value", run {
            caretAt("plain", 1)
            texts().contains(regenerateText)
        })
    }

    fun testCancellingTheChooserWritesNothing() {
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Old0Db0Password0Value0001", VaultVectors.PW1)))
        ready()
        open(vaultPath)
        val before = myFixture.editor.document.text
        regeneratePrompts.onGenerator = { null }
        caretAt("vault_db_password")

        myFixture.launchAction(intention(regenerateText))
        awaitRun()

        assertEquals(before, myFixture.editor.document.text)
        assertEmpty(feedback())
    }

    fun testGenerateAndEncryptReplacesAPlaceholderWithANewValue() {
        val single = defaultRoot("heron")
        val path = "$single/group_vars/all/vault.yml"
        write(path, "vault_new_password: \"\"\nvault_other_password: CHANGEME  # set me\ndb_password: plain\nvault_done: !vault |\n" +
            VaultVectors.encrypt("x", VaultVectors.PW1).formatLines().joinToString("") { "  $it\n" })
        root(single)
        ready()
        open(path)
        caretAt("vault_new_password", 2)
        assertTrue("on the key of an empty placeholder", texts().contains(generateText))
        val preview = preview(generateText)
        assertTrue(preview, preview.contains("vault_new_password"))
        caretAt("db_password", 2)
        assertFalse("not on other keys", texts().contains(generateText))
        caretAt("vault_done", 2)
        assertFalse("not on a !vault value", texts().contains(generateText))
        caretAt("vault_other_password", "vault_other_password: ".length + 2)
        assertTrue("on the value too", texts().contains(generateText))

        myFixture.launchAction(intention(generateText))
        awaitRun()

        assertEquals("32 alphanumeric", regeneratePrompts.generatorRequests.single().presetText)
        assertEmpty("the only id: nothing to ask", prompts.identityRequests)
        FileDocumentManager.getInstance().saveAllDocuments()
        val after = text(path)
        assertTrue(after, after.contains("vault_other_password: !vault | # set me\n  \$ANSIBLE_VAULT;1.1;AES256\n"))
        assertFalse(after.contains("CHANGEME"))
        val value = valueOf(path, "vault_other_password")!!
        assertTrue(value, value.length == 32 && value.all { it.isLetterOrDigit() })
    }
}
