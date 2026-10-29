package de.terletzkiy.ansibility.vault.regenerate

import com.intellij.codeInsight.intention.impl.preview.IntentionPreviewPopupUpdateProcessor
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.history.LocalHistory
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.testFramework.IndexingTestUtil
import de.terletzkiy.ansibility.vault.VaultVectors
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Plan amendment R29's security rules for Regenerate Secrets… (as F7.13): after a regeneration, no old plaintext and no
 * new value is in any file or document, the word index, the undo and redo texts, the documents after undo and redo,
 * Local History, the log, a notification, the dialog's rows, an intention preview or a `toString()`. The generator is
 * seeded, and the new values are read back by decrypting the written envelopes with the test passwords.
 */
class RegenerateSecretHygieneTest : RegenerateTestCase() {
    private val dbPath get() = "$root/group_vars/db/vault.yml"
    private val appPath get() = "$root/group_vars/app/vault.yml"
    private val newPath get() = "$root/group_vars/new/vault.yml"

    /** Word-index friendly sentinels (one identifier each). */
    private val oldDb = "ANSIBILITY_SENTINEL_91"
    private val oldApp = "ANSIBILITY_SENTINEL_92"
    private val placeholder = "ANSIBILITY_SENTINEL_93"

    private fun filesWithWord(word: String): List<String> {
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val found = LinkedHashSet<String>()
        runReadActionBlocking {
            val helper = PsiSearchHelper.getInstance(project)
            val scope = GlobalSearchScope.projectScope(project)
            helper.processAllFilesWithWord(word, scope, { found += it.name; true }, true)
            helper.processAllFilesWithWordInLiterals(word, scope) { found += it.name; true }
            helper.processAllFilesWithWordInComments(word, scope) { found += it.name; true }
            helper.processAllFilesWithWordInText(word, scope, { found += it.name; true }, true)
        }
        return found.toList()
    }

    /** Every revision Local History keeps of [file], as text. */
    private fun history(file: VirtualFile): List<String> {
        val history = LocalHistory.getInstance()
        val stamps = ArrayList<Long>()
        history.getByteContent(file) { stamps += it; false }
        return stamps.distinct().mapNotNull { stamp -> history.getByteContent(file) { it == stamp }?.let { String(it, Charsets.UTF_8) } }
    }

    private fun documents(): List<String> {
        val documents = FileDocumentManager.getInstance()
        return listOf(dbPath, appPath, newPath).mapNotNull { documents.getCachedDocument(vf(it))?.text }
    }

    fun testNoOldOrNewValueLeaksFromARegeneration() {
        write(dbPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt(oldDb, VaultVectors.PW1)))
        write(appPath, VaultVectors.inline("vault_app_password", VaultVectors.encrypt(oldApp, VaultVectors.DEV, "dev")))
        write(newPath, "vault_new_password: $placeholder\n")
        ready()
        assertEquals("the word index sees the placeholder before", listOf("vault.yml"), filesWithWord(placeholder))
        val shown = ArrayList<String>()
        val newValues = ArrayList<String>()
        // The placeholder's root has three ids: it gets default.
        prompts.identity = { "default" }

        val log = capture {
            regeneratePrompts.onValues = { request ->
                val dialog = RegenerateDialog(project, request)
                try {
                    request.rows.forEach { shown += dialog.textOf(it); shown += it.toString() }
                    shown += request.toString()
                } finally {
                    dialog.close(com.intellij.openapi.ui.DialogWrapper.CANCEL_EXIT_CODE)
                }
                true
            }
            regenerate(vf("$root/group_vars"))
            shown += feedback()
            newValues += valueOf(dbPath, "vault_db_password")!!
            newValues += valueOf(appPath, "vault_app_password", VaultVectors.DEV)!!
            newValues += valueOf(newPath, "vault_new_password")!!

            // The intention on one of the new values: its preview and its chooser.
            myFixture.configureFromExistingVirtualFile(vf(dbPath))
            myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("vault_db_password") + 2)
            val intention = myFixture.availableIntentions.single { it.text == VaultRegenerateIntention().text }
            shown += (IntentionPreviewPopupUpdateProcessor.getPreviewInfo(project, intention, myFixture.file, myFixture.editor) as IntentionPreviewInfo.Html).content().toString()
            myFixture.launchAction(intention)
            awaitRun()
            shown += feedback()
            shown += regeneratePrompts.generatorRequests.map { "${it.presetText} $it" }
            FileDocumentManager.getInstance().saveAllDocuments()
            newValues += valueOf(dbPath, "vault_db_password")!!
        }

        assertEquals("four new values, all different", 4, newValues.toSet().size)
        val secrets = listOf(oldDb, oldApp, placeholder) + newValues
        val undoTexts = ArrayList<String>()
        val undo = UndoManager.getInstance(project)
        val editor = TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
        undo.getUndoActionNameAndDescription(editor).let { undoTexts += "${it.first} | ${it.second}" }

        for (secret in secrets) {
            for (path in listOf(dbPath, appPath, newPath)) assertFalse("$secret in $path", text(path).contains(secret))
            for (document in documents()) assertFalse("$secret in a document", document.contains(secret))
            for (line in log) assertFalse("$secret in the log: $line", line.contains(secret))
            for (text in shown + undoTexts) assertFalse("$secret in '$text'", text.contains(secret))
        }
        for (sentinel in listOf(oldDb, oldApp, placeholder)) assertEmpty("$sentinel in the word index", filesWithWord(sentinel))
        for (password in listOf(VaultVectors.PW1, VaultVectors.DEV)) for (line in log) assertFalse("a password in the log: $line", line.contains(password))
        assertTrue("the vault code's own lines were captured: $log", log.any { it.startsWith("vault ") })

        // Local History: the vault values were never plaintext, and no new value ever is.
        for (path in listOf(dbPath, appPath)) {
            val revisions = history(vf(path))
            assertTrue("Local History records $path", revisions.isNotEmpty())
            for (revision in revisions) for (secret in listOf(oldDb, oldApp) + newValues) assertFalse("$secret in Local History of $path", revision.contains(secret))
        }
        for (revision in history(vf(newPath))) for (secret in newValues) assertFalse("$secret in Local History", revision.contains(secret))

        // Undo and redo of the last regeneration: envelopes only.
        myFixture.configureFromExistingVirtualFile(vf(appPath))
        TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            assertTrue(undo.isUndoAvailable(null))
            undo.undo(null)
            for (document in documents()) for (secret in secrets) assertFalse("$secret after undo", document.contains(secret))
            undo.redo(null)
            for (document in documents()) for (secret in secrets) assertFalse("$secret after redo", document.contains(secret))
        } finally {
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
        }
    }

    /** Runs [block] with every logger writing into the returned list as well (the plugin's debug lines included). */
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

    /** Platform debug output is passed on but not searched (DocumentImpl's trace echoes every document change). */
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
