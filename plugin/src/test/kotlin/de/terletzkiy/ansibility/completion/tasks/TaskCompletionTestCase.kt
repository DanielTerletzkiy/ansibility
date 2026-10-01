package de.terletzkiy.ansibility.completion.tasks

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import de.terletzkiy.ansibility.docs.DocsTestCase

/**
 * Base of the task completion tests: the docs area's fixture setup (golden targets ansible-core 2.18.8 through
 * `golden/docker`), completion through the plugin's real dispatcher, and helpers to read the popup.
 *
 * Auto-insertion of a single item is off, so every test sees the popup; it is restored afterwards.
 */
abstract class TaskCompletionTestCase : DocsTestCase() {
    private var savedAutoInsert = true

    override fun setUp() {
        super.setUp()
        val settings = CodeInsightSettings.getInstance()
        savedAutoInsert = settings.AUTOCOMPLETE_ON_CODE_COMPLETION
        settings.AUTOCOMPLETE_ON_CODE_COMPLETION = false
    }

    override fun tearDown() {
        try {
            CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = savedAutoInsert
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** Creates [path] from [text] (with a `<caret>` marker), completes there and returns the lookup strings in popup order. */
    protected fun complete(path: String, text: String): List<String> {
        val caret = text.indexOf(CARET)
        check(caret >= 0) { "no $CARET in the text" }
        closeLookup()
        myFixture.tempDirFixture.createFile(path, text.replace(CARET, ""))
        refreshRoots()
        myFixture.configureFromTempProjectFile(path)
        myFixture.editor.caretModel.moveToOffset(caret)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    /**
     * Inserts [text] (with a `<caret>` marker) at the start of 1-based [line] of the project file [path] (lines
     * before it keep their numbers), completes there and returns the lookup strings in popup order.
     */
    protected fun completeInserted(path: String, line: Int, text: String): List<String> {
        val caret = text.indexOf(CARET)
        check(caret >= 0) { "no $CARET in the text" }
        closeLookup()
        // Re-configuring an open file whose document has unsaved edits would report a memory-disk conflict.
        val open = myFixture.editor?.document?.let { FileDocumentManager.getInstance().getFile(it) }
        if (open != vf(path)) myFixture.configureFromTempProjectFile(path)
        val document = myFixture.editor.document
        val start = if (line - 1 >= document.lineCount) document.textLength else document.getLineStartOffset(line - 1)
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(start, text.replace(CARET, "")) }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        myFixture.editor.caretModel.moveToOffset(start + caret)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    /** Closes the popup of an earlier completion in the same test. */
    private fun closeLookup() {
        if (myFixture.editor == null) return
        (myFixture.lookup as? LookupImpl)?.hideLookup(true)
    }

    /** The items of the open popup that task completion contributed. */
    protected fun ours(): List<LookupElement> = myFixture.lookupElements.orEmpty().filter { it.`object` is TaskLookupObject }

    /** The lookup strings of [ours], in popup order. */
    protected fun ourStrings(): List<String> = ours().map { it.lookupString }

    /** The one item of ours named [lookupString]. */
    protected fun item(lookupString: String): LookupElement =
        ours().singleOrNull { it.lookupString == lookupString } ?: error("no single item $lookupString in ${ourStrings()}")

    protected fun presentation(lookupString: String): LookupElementPresentation = LookupElementPresentation.renderElement(item(lookupString))

    /** Selects the item [lookupString] in the open popup with Enter. */
    protected fun select(lookupString: String) {
        val lookup = myFixture.lookup as LookupImpl
        lookup.currentItem = item(lookupString)
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
    }

    /** The editor text with `<caret>` at the caret. */
    protected fun textWithCaret(): String {
        val text = myFixture.editor.document.text
        val caret = myFixture.editor.caretModel.offset
        return text.substring(0, caret) + CARET + text.substring(caret)
    }

    companion object {
        const val CARET = "<caret>"
        const val PLAYBOOK_SYSTEM = "repos/falcon/ansible/playbook-setup-system.yml"
        const val SCRATCH_TASKS = "$HAPROXY/tasks/completion.yml"
    }
}
