package de.terletzkiy.ansibility.vault.ui

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.testFramework.IndexingTestUtil
import de.terletzkiy.ansibility.api.VaultOperations
import de.terletzkiy.ansibility.vault.VaultVectors
import de.terletzkiy.ansibility.vault.actions.PlainValueRef
import de.terletzkiy.ansibility.vault.actions.VaultValueActions
import de.terletzkiy.ansibility.vault.actions.VaultValuePsi
import de.terletzkiy.ansibility.vault.actions.VaultValueRef
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import java.awt.datatransfer.DataFlavor
import java.util.concurrent.CopyOnWriteArrayList

/**
 * F7.13 for the vault UI (plan amendment R7/R8, "Testing additions" §3): after Reveal (eye and Copy), Edit, Rekey,
 * Change id and Encrypt value, no synthetic sentinel is in the files, the IDE word index, the log, an undo or redo
 * text, a notification or hint, the `toString()` of a session or reference, the clipboard after 30 s or the IDE's
 * paste history, and no password is in the log.
 */
class VaultUiSecretHygieneTest : VaultUiTestCase() {
    private lateinit var root: String
    private val vaultPath get() = "$root/group_vars/all/vault.yml"
    private val varsPath get() = "$root/group_vars/all/vars.yml"

    /** Word-index friendly sentinels (one identifier each). */
    private val revealed = "ANSIBILITY_SENTINEL_81"
    private val original = "ANSIBILITY_SENTINEL_82"
    private val edited = "ANSIBILITY_SENTINEL_83"
    private val plain = "ANSIBILITY_SENTINEL_84"
    private val sentinels get() = listOf(revealed, original, edited, plain)

    override fun setUp() {
        super.setUp()
        root = defaultRoot(cfg = "[defaults]\nvault_identity_list = dev@dev.pw\n")
        write("$root/dev.pw", "${VaultVectors.DEV}\n")
        write(vaultPath, VaultVectors.inline("vault_revealed", VaultVectors.encrypt(revealed, VaultVectors.PW1)) +
            VaultVectors.inline("vault_edited", VaultVectors.encrypt(original, VaultVectors.PW1)))
        write(varsPath, "api_token: $plain\nother: 1\n")
        root(root)
    }

    private fun scalar(key: String): YAMLScalar = runReadActionBlocking {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val text = myFixture.editor.document.text
        val offset = Regex("(?m)^${Regex.escape(key)}:").find(text)!!.range.first
        val keyValue = myFixture.file.findElementAt(offset)!!.parent as YAMLKeyValue
        keyValue.value as YAMLScalar
    }

    private fun vaultRef(key: String): VaultValueRef = runReadActionBlocking { VaultValueRef.of(VaultValuePsi.vaultScalarAt(scalar(key))!!)!! }

    private fun undoText(): String {
        val texts = UndoManager.getInstance(project).getUndoActionNameAndDescription(TextEditorProvider.getInstance().getTextEditor(myFixture.editor))
        return "${texts.first} | ${texts.second}"
    }

    private fun filesWithWord(word: String): List<String> {
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val found = LinkedHashSet<String>()
        runReadActionBlocking {
            val helper = PsiSearchHelper.getInstance(project)
            val scope = GlobalSearchScope.projectScope(project)
            // Every context of the word index: code, string literals, comments and plain text.
            helper.processAllFilesWithWord(word, scope, { found += it.name; true }, true)
            helper.processAllFilesWithWordInLiterals(word, scope) { found += it.name; true }
            helper.processAllFilesWithWordInComments(word, scope) { found += it.name; true }
            helper.processAllFilesWithWordInText(word, scope, { found += it.name; true }, true)
        }
        return found.toList()
    }

    fun testNoValueLeaksFromTheUiFlows() {
        assertEquals("the word index sees the plain value before it is encrypted", listOf("vars.yml"), filesWithWord(plain))
        val undoTexts = ArrayList<String>()
        val reported = ArrayList<String>()
        val printed = ArrayList<String>()
        val actions = VaultValueActions.getInstance(project)

        val log = capture {
            myFixture.configureFromExistingVirtualFile(vf(vaultPath))

            // Reveal: the eye and Copy, then the timeout.
            VaultOperations.getInstance(project).reveal(location(vaultPath, "vault_revealed"), myFixture.editor)
            waitFor("no reveal popup") { VaultRevealService.getInstance(project).session != null }
            val reveal = VaultRevealService.getInstance(project).session!!
            reveal.toggle()
            assertTrue(reveal.shows(revealed))
            reveal.copy()
            printed += reveal.toString()
            scheduler.advance(VaultUiTimings.REVEAL_MILLIS)
            assertTrue(reveal.isClosed)

            // Edit: the original value becomes another one.
            actions.edit(vaultRef("vault_edited"), myFixture.editor)
            awaitAction()
            val edit = VaultEditService.getInstance(project).session!!
            printed += edit.toString()
            edit.textArea.text = edited
            edit.submit()
            awaitAction()
            assertEquals(EditCloseReason.WRITTEN, edit.closeReason)
            undoTexts += undoText()

            // Rekey to dev, then Change id back to default (dev does not decrypt it any more: a rekey after asking).
            prompts.identity = { "dev" }
            actions.rekey(vaultRef("vault_revealed").also { printed += it.toString() }, myFixture.editor)
            awaitAction()
            undoTexts += undoText()
            prompts.identity = { "default" }
            actions.changeId(vaultRef("vault_revealed"), myFixture.editor)
            awaitAction()
            undoTexts += undoText()

            // Encrypt value: the plain value leaves the file.
            myFixture.configureFromExistingVirtualFile(vf(varsPath))
            val plainRef = runReadActionBlocking { PlainValueRef.of(scalar("api_token"))!! }
            printed += plainRef.toString()
            prompts.encrypt = { "default" }
            actions.encrypt(plainRef, myFixture.editor)
            awaitAction()
            undoTexts += undoText()

            reported += feedback()
            scheduler.advance(VaultUiTimings.CLIPBOARD_MILLIS)
        }

        FileDocumentManager.getInstance().saveAllDocuments()
        val vaultText = String(vf(vaultPath).contentsToByteArray())
        val varsText = String(vf(varsPath).contentsToByteArray())
        assertTrue(varsText, varsText.startsWith("api_token: !vault |\n  \$ANSIBLE_VAULT;1.1;AES256\n"))
        assertTrue(vaultText.contains("vault_edited: !vault |\n  \$ANSIBLE_VAULT;1.1;AES256\n"))
        assertTrue("the vault code's own lines were captured: $log", log.any { it.startsWith("vault decrypt: DECRYPTED") })
        assertEquals(4, undoTexts.size)
        assertEquals("every flow reported its result: $reported", 4, reported.size)

        for (sentinel in sentinels) {
            assertFalse("$sentinel in a file", vaultText.contains(sentinel) || varsText.contains(sentinel))
            assertEmpty("$sentinel in the word index", filesWithWord(sentinel))
            for (line in log) assertFalse("$sentinel in the log: $line", line.contains(sentinel))
            for (text in undoTexts + reported + printed) assertFalse("$sentinel in '$text'", text.contains(sentinel))
            assertFalse("$sentinel in the paste history", CopyPasteManager.getInstance().allContents.any { it.getTransferData(DataFlavor.stringFlavor) == sentinel })
        }
        assertFalse(CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor).orEmpty().contains("SENTINEL"))
        for (password in listOf(VaultVectors.PW1, VaultVectors.DEV)) {
            for (line in log) assertFalse("a password in the log: $line", line.contains(password))
        }
    }

    /**
     * Runs [block] with every logger writing into the returned list as well: info, warnings and errors of every
     * category, and debug and trace lines of the plugin's own categories. Platform debug and trace output is passed on
     * but not searched: it is off by default in the IDE (the test logger turns it on), and `DocumentImpl`'s trace,
     * for one, echoes every document change, including the plain value Encrypt replaces.
     */
    private fun capture(block: () -> Unit): List<String> {
        val lines = CopyOnWriteArrayList<String>()
        val previous = Logger.getFactory()
        val sink = Sink(lines)
        Logger.setFactory(CapturingFactory(previous, sink))
        try {
            block()
        } finally {
            sink.closed = true
            Logger.setFactory(previous)
        }
        return lines
    }

    private class Sink(val lines: MutableList<String>) {
        @Volatile
        var closed = false

        fun record(message: String?, error: Throwable?, details: Array<out String> = emptyArray()) {
            if (closed) return
            message?.let(lines::add)
            details.forEach(lines::add)
            var cause = error
            while (cause != null) {
                lines += cause.toString()
                cause = cause.cause
            }
        }
    }

    private class CapturingFactory(private val delegate: Logger.Factory, private val sink: Sink) : Logger.Factory {
        override fun getLoggerInstance(category: String): Logger =
            CapturingLogger(delegate.getLoggerInstance(category), sink, ours = category.removePrefix("#").startsWith("de.terletzkiy.ansibility"))
    }

    private class CapturingLogger(private val delegate: Logger, private val sink: Sink, private val ours: Boolean) : Logger() {
        override fun isDebugEnabled(): Boolean = ours && !sink.closed || delegate.isDebugEnabled

        override fun debug(message: String?, t: Throwable?) {
            if (ours) sink.record(message, t)
            if (delegate.isDebugEnabled) delegate.debug(message, t)
        }

        override fun info(message: String?, t: Throwable?) {
            sink.record(message, t)
            delegate.info(message, t)
        }

        override fun warn(message: String?, t: Throwable?) {
            sink.record(message, t)
            delegate.warn(message, t)
        }

        override fun error(message: String?, t: Throwable?, vararg details: String) {
            sink.record(message, t, details)
            delegate.error(message, t, *details)
        }
    }
}
