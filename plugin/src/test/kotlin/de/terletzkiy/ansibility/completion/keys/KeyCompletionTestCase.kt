package de.terletzkiy.ansibility.completion.keys

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.fixtures.InfraTestData

/**
 * Base for key and value completion tests: sub-trees of the sanitised infra fixture (line numbers identical to the
 * real repo) or of this area's `testData/completion/keys` become Ansible roots of the light project, and completion
 * runs through the plugin's real dispatcher and extensions. Single items are not auto-inserted, so every test sees the
 * popup.
 */
abstract class KeyCompletionTestCase : BasePlatformTestCase() {
    private var autocomplete = true

    override fun getTestDataPath(): String = InfraTestData.testDataPath.toString()

    override fun setUp() {
        super.setUp()
        val settings = CodeInsightSettings.getInstance()
        autocomplete = settings.AUTOCOMPLETE_ON_CODE_COMPLETION
        settings.AUTOCOMPLETE_ON_CODE_COMPLETION = false
    }

    override fun tearDown() {
        try {
            CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** Copies `infra/<path>` for each path to the same project path and re-detects the roots. */
    protected fun copyInfra(vararg paths: String) {
        for (path in paths) myFixture.copyDirectoryToProject("${InfraTestData.INFRA}/$path", path)
        refreshRoots()
    }

    /** Copies this area's `completion/keys/<name>` test data to project path [name]. */
    protected fun copyKeysData(name: String) {
        myFixture.copyDirectoryToProject("$KEYS_DATA/$name", name)
        refreshRoots()
    }

    protected fun createFile(path: String, text: String): VirtualFile = myFixture.tempDirFixture.createFile(path, text).also { refreshRoots() }

    protected fun refreshRoots() {
        (AnsibleWorkspaceImpl.getInstance(project) ?: error("AnsibleWorkspace is not AnsibleWorkspaceImpl")).structureChanged()
    }

    /** The text of 1-based [line] of [path] as it is on disk. */
    protected fun lineText(path: String, line: Int): String {
        val file = myFixture.findFileInTempDir(path) ?: error("missing $path")
        return String(file.contentsToByteArray(), file.charset).lines()[line - 1]
    }

    /** Checks that 1-based [line] of [path] is [expected] (the plan's line references stay true). */
    protected fun assertLine(path: String, line: Int, expected: String) = assertEquals("$path:$line", expected, lineText(path, line))

    /**
     * Opens [path], inserts [text] (with [CARET]) as a new line before 1-based [line], and completes there. With [save],
     * documents changed by earlier steps are saved first; without it, other unsaved documents stay unsaved.
     */
    protected fun completeInsertingLine(path: String, line: Int, text: String, save: Boolean = true): List<LookupElement> =
        edit(path, save) { document -> document.getLineStartOffset(line - 1) to "$text\n" }

    /** Opens [path], replaces 1-based [line] by [text] (with [CARET]), and completes there. */
    protected fun completeReplacingLine(path: String, line: Int, text: String): List<LookupElement> = edit(path) { document ->
        val start = document.getLineStartOffset(line - 1)
        val end = document.getLineEndOffset(line - 1)
        WriteCommandAction.runWriteCommandAction(project) { document.deleteString(start, end) }
        start to text
    }

    /** Creates [path] from [text] (with [CARET]) and completes there. */
    protected fun completeIn(path: String, text: String): List<LookupElement> {
        val caret = text.indexOf(CARET)
        check(caret >= 0) { "no caret in $text" }
        createFile(path, text.replace(CARET, ""))
        myFixture.configureFromTempProjectFile(path)
        myFixture.editor.caretModel.moveToOffset(caret)
        return complete()
    }

    private fun edit(path: String, save: Boolean = true, change: (com.intellij.openapi.editor.Document) -> Pair<Int, String>): List<LookupElement> {
        // A previous completion in the same test left the document modified; keep memory and disk in sync.
        if (save) FileDocumentManager.getInstance().saveAllDocuments()
        myFixture.configureFromTempProjectFile(path)
        val document = myFixture.editor.document
        val (offset, text) = change(document)
        val caret = text.indexOf(CARET)
        check(caret >= 0) { "no caret in $text" }
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(offset, text.replace(CARET, "")) }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        myFixture.editor.caretModel.moveToOffset(offset + caret)
        return complete()
    }

    /** Completes at the caret and returns this package's items (other sources and contributors are left out). */
    protected fun complete(): List<LookupElement> {
        myFixture.completeBasic()
        return ours()
    }

    /** The items of the open popup that key or value completion added, in popup order. */
    protected fun ours(): List<LookupElement> = myFixture.lookupElements.orEmpty().filter { it.`object` is VarLookupDoc }

    protected fun names(items: List<LookupElement>): List<String> = items.map { it.lookupString }

    protected fun item(items: List<LookupElement>, name: String): LookupElement =
        items.firstOrNull { it.lookupString == name } ?: error("no item $name among ${names(items)}")

    protected fun presentation(items: List<LookupElement>, name: String): LookupElementPresentation =
        LookupElementPresentation.renderElement(item(items, name))

    /** Selects the item [name] of the open popup and returns the editor text afterwards. */
    protected fun accept(items: List<LookupElement>, name: String): String {
        myFixture.lookup.currentItem = item(items, name)
        myFixture.finishLookup(com.intellij.codeInsight.lookup.Lookup.NORMAL_SELECT_CHAR)
        return myFixture.editor.document.text
    }

    /** The text of the editor's caret line. */
    protected fun caretLine(): String {
        val document = myFixture.editor.document
        val line = document.getLineNumber(myFixture.editor.caretModel.offset)
        return document.getText(com.intellij.openapi.util.TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))
    }

    companion object {
        const val CARET = "<caret>"
        const val KEYS_DATA = "completion/keys"
        const val FALCON = "repos/falcon"
        const val PROD_VARS = "$FALCON/ansible/environments/prod/group_vars/all/vars.yml"
        const val OPS_VARS = "$FALCON/ansible/environments/ops/group_vars/all/vars.yml"
        const val PROD1_VARS = "$FALCON/ansible/environments/prod/host_vars/prod-prod1/vars.yml"
        const val OPS_HOSTS = "$FALCON/ansible/environments/ops/hosts.yml"
        const val MINI = "mini"
    }
}
