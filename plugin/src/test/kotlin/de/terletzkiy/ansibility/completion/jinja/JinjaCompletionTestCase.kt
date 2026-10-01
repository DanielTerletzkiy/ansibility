package de.terletzkiy.ansibility.completion.jinja

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.documentation.DocumentationData
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.LookupElementDocumentationTargetProvider
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.lang.jinja.filetype.J2Mappings
import java.util.concurrent.TimeUnit

/**
 * Base for the Jinja completion platform tests: sub-trees of the sanitised infra fixture (line numbers identical to the
 * real repo) or synthetic files become Ansible roots of the light project; the plugin's real extensions complete.
 */
abstract class JinjaCompletionTestCase : BasePlatformTestCase() {
    private var autocomplete = true

    override fun getTestDataPath(): String = InfraTestData.testDataPath.toString()

    override fun setUp() {
        super.setUp()
        // Since M5 Jinja in YAML is injected: keep the host editor when a file is re-opened with the caret inside a
        // fragment, so the helpers' offsets stay host-file coordinates (completion itself still runs in the fragment).
        myFixture.setCaresAboutInjection(false)
        autocomplete = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
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

    protected fun createFile(path: String, text: String): VirtualFile = myFixture.tempDirFixture.createFile(path, text.trimIndent() + "\n").also { refreshRoots() }

    protected fun refreshRoots() {
        (AnsibleWorkspaceImpl.getInstance(project) ?: error("AnsibleWorkspace is not AnsibleWorkspaceImpl")).structureChanged()
    }

    protected fun vf(path: String): VirtualFile = myFixture.findFileInTempDir(path) ?: error("missing $path")

    protected fun psi(path: String): PsiFile = runReadActionBlocking { PsiManager.getInstance(project).findFile(vf(path)) } ?: error("no PSI for $path")

    /** The offset of [marker] on 1-based [line] of [path], plus [delta]; fails when the line does not contain it. */
    protected fun offsetAt(path: String, line: Int, marker: String, delta: Int = 0): Int {
        val text = VfsUtilCore.loadText(vf(path))
        val lineStart = StringUtil.lineColToOffset(text, line - 1, 0)
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        val index = text.substring(lineStart, lineEnd).indexOf(marker)
        check(index >= 0) { "'$marker' not on line $line of $path: ${text.substring(lineStart, lineEnd)}" }
        return lineStart + index + delta
    }

    /** Opens [path] and completes at [offset]; the lookup items (empty when none). */
    protected fun completeAt(path: String, offset: Int): List<LookupElement> {
        myFixture.configureFromExistingVirtualFile(vf(path))
        myFixture.editor.caretModel.moveToOffset(offset)
        return myFixture.completeBasic()?.toList().orEmpty()
    }

    /**
     * Replaces [old] on 1-based [line] of [path] with [new] in the editor (unsaved, like typing) and completes at the
     * end of [new] (or at `<caret>` inside it).
     */
    protected fun completeAfterEdit(
        path: String,
        line: Int,
        old: String,
        new: String,
        around: (() -> List<LookupElement>) -> List<LookupElement> = { it() },
    ): List<LookupElement> {
        val start = offsetAt(path, line, old)
        myFixture.configureFromExistingVirtualFile(vf(path))
        val caretInNew = new.indexOf(CARET).let { if (it < 0) new.length else it }
        val text = new.replace(CARET, "")
        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.replaceString(start, start + old.length, text)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        myFixture.editor.caretModel.moveToOffset(start + caretInNew)
        return around { myFixture.completeBasic()?.toList().orEmpty() }
    }

    /** Closes the lookup and restores the editor text of [path] from disk (undoes [completeAfterEdit]). */
    protected fun reset(path: String) {
        myFixture.lookup?.hideLookup(true)
        val file = vf(path)
        val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file) ?: return
        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().reloadFromDisk(document)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    /** The lookup strings of this plugin's Jinja items (word completion and other contributors left out). */
    protected fun strings(items: List<LookupElement>): List<String> = items.filter { it.`object` is JinjaLookupItem }.map { it.lookupString }

    protected fun item(items: List<LookupElement>, lookupString: String): LookupElement =
        items.firstOrNull { it.lookupString == lookupString && it.`object` is JinjaLookupItem } ?: error("no '$lookupString' in ${strings(items)}")

    protected fun presentation(element: LookupElement): LookupElementPresentation = LookupElementPresentation.renderElement(element)

    protected fun priority(element: LookupElement): Double = (element as? PrioritizedLookupElement<*>)?.priority ?: error("$element is not prioritized")

    /** `type · tail` of an item, as the popup shows it (tail trimmed). */
    protected fun describe(items: List<LookupElement>, lookupString: String): String {
        val presentation = presentation(item(items, lookupString))
        return "${presentation.typeText} |${presentation.tailText.orEmpty()}"
    }

    /** Ctrl+Q on [element] in the completion popup, through the registered lookup-element documentation providers. */
    protected fun popupDocumentation(element: LookupElement): DocumentationTarget {
        val file = myFixture.file
        val offset = myFixture.editor.caretModel.offset
        return inBackgroundReadAction { LOOKUP_DOCS.extensionList.firstNotNullOfOrNull { it.documentationTarget(file, element, offset) } }
            ?: error("no popup documentation for ${element.lookupString}")
    }

    protected fun html(target: DocumentationTarget): String = inBackgroundReadAction { (target.computeDocumentation() as DocumentationData).html }

    protected fun <T> inBackgroundReadAction(action: () -> T): T =
        ApplicationManager.getApplication().executeOnPooledThread<T> { runReadActionBlocking(action) }.get(60, TimeUnit.SECONDS)

    /**
     * Types `.j2` files inside roots as [type] for [action] (`*.j2` mapped to it, with "Keep YAML for .j2" on since the
     * M5 overrider claims them otherwise; [de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaFileType] keeps the claim
     * over a `*.j2 → YAML` mapping), then restores the mapping and the settings.
     */
    protected fun withJ2As(type: FileType, action: () -> Unit) = J2Mappings.withJ2As(type, action)

    companion object {
        const val CARET = "<caret>"

        /** The platform's EP, looked up by name (its `EP_NAME` field is internal API). */
        val LOOKUP_DOCS: com.intellij.openapi.extensions.ExtensionPointName<LookupElementDocumentationTargetProvider> =
            com.intellij.openapi.extensions.ExtensionPointName.create("com.intellij.platform.backend.documentation.lookupElementTargetProvider")

        /** The card's text as a reader sees it: tags removed, entities decoded, whitespace collapsed. */
        fun plain(html: String): String {
            val noTags = html.replace(Regex("</?(b|i|code|span|a|font)(\\s[^>]*)?>"), "").replace(Regex("<[^>]+>"), " ")
            val decoded = noTags
                .replace(Regex("&#(\\d+);")) { String(Character.toChars(it.groupValues[1].toInt())) }
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&#39;", "'").replace("&amp;", "&")
            return decoded.replace(Regex("\\s+"), " ").trim()
        }
    }
}
