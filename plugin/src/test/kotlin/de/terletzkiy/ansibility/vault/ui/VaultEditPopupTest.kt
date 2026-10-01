package de.terletzkiy.ansibility.vault.ui

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.psi.PsiDocumentManager
import de.terletzkiy.ansibility.semantics.vault.VaultLayout
import de.terletzkiy.ansibility.vault.VaultVectors
import de.terletzkiy.ansibility.vault.actions.VaultEditIntention
import de.terletzkiy.ansibility.vault.actions.VaultValueActions
import de.terletzkiy.ansibility.vault.actions.VaultValuePsi
import de.terletzkiy.ansibility.vault.actions.VaultValueRef
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * F7.2 Edit: the popup is plain Swing, Encrypt and Replace re-encrypts with the id that decrypted the value and keeps
 * the header label, the `!vault |` line and the indentation in one undoable command whose undo text holds no value;
 * an unchanged value writes nothing, an empty one is refused, unsaved edits ask before they are discarded, and a lock
 * closes the popup.
 */
class VaultEditPopupTest : VaultUiTestCase() {
    private lateinit var root: String
    private val path get() = "$root/group_vars/all/vault.yml"

    override fun setUp() {
        super.setUp()
        root = defaultRoot(cfg = "[defaults]\nvault_identity_list = dev@dev.pw, prod@prod.pw\n")
        write("$root/dev.pw", "${VaultVectors.DEV}\n")
        write("$root/prod.pw", "${VaultVectors.PROD}\n")
        val text = "# vault values\n" +
            "vault_db_password: !vault |  # rotate yearly\n" +
            VaultVectors.encrypt("hello world", VaultVectors.PW1).formatLines().joinToString("") { "  $it\n" } +
            "nested:\n" +
            "  vault_dev: !vault |\n" +
            VaultVectors.encrypt("dev secret", VaultVectors.DEV, "dev").formatLines().joinToString("") { "    $it\n" } +
            // v13-style: labelled dev, but only prod's secret decrypts it
            VaultVectors.inline("vault_mislabelled", VaultVectors.encrypt("label lies", VaultVectors.PROD, "dev")) +
            "after: 1\n"
        write(path, text)
        root(root)
        myFixture.configureFromExistingVirtualFile(vf(path))
    }

    /** The key's offset in the editor's document (which may differ from the file on disk). */
    private fun offsetOf(key: String): Int {
        val text = documentText()
        val match = Regex("(?m)^\\s*${Regex.escape(key)}:").find(text) ?: error("no key $key")
        return match.range.first + match.value.indexOf(key)
    }

    private fun scalar(key: String): YAMLScalar = runReadActionBlocking {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        VaultValuePsi.vaultScalarAt(myFixture.file.findElementAt(offsetOf(key))!!)!!
    }

    private fun envelopeOf(key: String): String = runReadActionBlocking { scalar(key).textValue }

    private fun edit(key: String): VaultEditSession {
        val ref = runReadActionBlocking { VaultValueRef.of(scalar(key)) }!!
        VaultValueActions.getInstance(project).edit(ref, myFixture.editor)
        awaitAction()
        return VaultEditService.getInstance(project).session ?: error("no edit popup; feedback: ${feedback()}")
    }

    private fun submit(session: VaultEditSession, text: String, newline: Boolean = false) {
        session.textArea.text = text
        session.endsWithNewline.isSelected = newline
        session.submit()
        awaitAction()
    }

    private fun documentText(): String = myFixture.editor.document.text

    fun testEditRoundTripKeepsLabelLayoutAndUndoRestoresTheOldEnvelope() {
        val before = documentText()
        val oldEnvelope = envelopeOf("vault_db_password")
        val session = edit("vault_db_password")
        assertEquals("hello world", session.textArea.text)
        assertFalse(session.endsWithNewline.isSelected)
        assertEquals(1, popups.size)

        submit(session, "new secret")
        assertEquals(EditCloseReason.WRITTEN, session.closeReason)
        assertEquals("the popup's plaintext is zeroed", 0, session.originalSize)
        assertEquals("the text area is emptied", "", session.textArea.text)
        val after = documentText()
        val newEnvelope = envelopeOf("vault_db_password")
        assertTrue(newEnvelope.startsWith("\$ANSIBLE_VAULT;1.1;AES256\n"))
        assertEquals("new secret", decryptWith(newEnvelope, VaultVectors.PW1))
        assertTrue("the key line is kept", after.contains("vault_db_password: !vault |  # rotate yearly\n  \$ANSIBLE_VAULT;1.1;AES256\n"))
        assertTrue("the +2 indentation and 80 columns are kept", VaultLayout.blocks(after).first().relativeIndent == 2)
        val body = after.substringAfter("# rotate yearly\n").substringBefore("nested:").lines().filter { it.isNotEmpty() }
        assertTrue(body.all { it.startsWith("  ") && it.length <= 82 && it.trim() == it.trim().lowercase() || it.contains("ANSIBLE_VAULT") })
        assertEquals("the rest of the file is untouched", before.substringAfter("nested:"), after.substringAfter("nested:"))

        val editor: TextEditor = TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
        val undo = UndoManager.getInstance(project)
        val texts = undo.getUndoActionNameAndDescription(editor)
        assertTrue(texts.first, texts.first.contains("Encrypt Vault Value"))
        for (text in listOf(texts.first, texts.second)) assertFalse(text, text.contains("new secret") || text.contains("hello world"))
        undo.undo(editor)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(oldEnvelope, envelopeOf("vault_db_password"))
        assertEquals(before, documentText())
    }

    fun testANestedLabelledValueKeepsItsIdAndIndentation() {
        val session = edit("vault_dev")
        assertEquals("dev secret", session.textArea.text)
        submit(session, "dev two", newline = true)
        val envelope = envelopeOf("vault_dev")
        assertTrue(envelope, envelope.startsWith("\$ANSIBLE_VAULT;1.2;AES256;dev\n"))
        assertEquals("dev two\n", decryptWith(envelope, VaultVectors.DEV))
        assertTrue(documentText().contains("  vault_dev: !vault |\n    \$ANSIBLE_VAULT;1.2;AES256;dev\n"))
    }

    fun testAMislabelledValueStaysMislabelledLikeAnsibleVaultEdit() {
        val session = edit("vault_mislabelled")
        assertEquals("prod", session.identity)
        submit(session, "still lies")
        val envelope = envelopeOf("vault_mislabelled")
        assertTrue(envelope, envelope.startsWith("\$ANSIBLE_VAULT;1.2;AES256;dev\n"))
        assertEquals("encrypted with the secret that decrypted it", "still lies", decryptWith(envelope, VaultVectors.PROD))
        assertNull(decryptWith(envelope, VaultVectors.DEV))
    }

    fun testAnUnchangedValueWritesNothing() {
        val document = FileDocumentManager.getInstance().getDocument(vf(path))!!
        val stamp = document.modificationStamp
        val session = edit("vault_db_password")
        submit(session, "hello world")
        assertEquals(EditCloseReason.UNCHANGED, session.closeReason)
        assertEquals(stamp, document.modificationStamp)
        assertEquals(0, session.originalSize)
    }

    fun testAnEmptyValueIsRefused() {
        val stamp = myFixture.editor.document.modificationStamp
        val session = edit("vault_db_password")
        submit(session, "")
        assertFalse(session.isClosed)
        assertEquals("Ansible refuses to encrypt an empty value.", session.errorText)
        assertEquals(stamp, myFixture.editor.document.modificationStamp)
    }

    fun testUnsavedChangesAskBeforeTheyAreDiscarded() {
        val session = edit("vault_db_password")
        assertTrue("a clean popup closes without asking", session.requestCancel())
        assertEquals(0, prompts.discardQuestions)
        session.textArea.text = "changed"
        prompts.discard = false
        assertFalse("keep editing", session.requestCancel())
        prompts.discard = true
        assertTrue(session.requestCancel())
        assertEquals(2, prompts.discardQuestions)
        popups.single().cancel()
        assertEquals(EditCloseReason.CANCELLED, session.closeReason)
        assertEquals(0, session.originalSize)
    }

    fun testLockingClosesTheEditPopup() {
        val session = edit("vault_db_password")
        secrets.lockAll()
        waitFor("not closed on lock") { session.isClosed }
        assertEquals(EditCloseReason.LOCKED, session.closeReason)
        assertEquals(0, session.originalSize)
    }

    fun testProjectCloseClosesTheEditPopup() {
        val session = edit("vault_db_password")
        VaultEditService.getInstance(project).dispose()
        assertEquals(EditCloseReason.PROJECT_CLOSED, session.closeReason)
    }

    fun testTheRevealPopupOpensTheEditPopup() {
        de.terletzkiy.ansibility.api.VaultOperations.getInstance(project).reveal(location(path, "vault_db_password"), myFixture.editor)
        waitFor("no reveal") { VaultRevealService.getInstance(project).session != null }
        val reveal = VaultRevealService.getInstance(project).session!!
        reveal.edit()
        assertEquals(RevealCloseReason.EDITING, reveal.closeReason)
        awaitAction()
        assertEquals("hello world", VaultEditService.getInstance(project).session?.textArea?.text)
    }

    fun testTheEditIntentionOpensThePopup() {
        myFixture.editor.caretModel.moveToOffset(offsetOf("vault_db_password"))
        val intention = myFixture.availableIntentions.single { it.familyName == "Ansible Vault" && it.text == VaultEditIntention().text }
        myFixture.launchAction(intention)
        awaitAction()
        assertEquals("hello world", VaultEditService.getInstance(project).session?.textArea?.text)
    }
}
