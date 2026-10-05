package de.terletzkiy.ansibility.vars

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.documentation.DocumentationData
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.SiteClassifier
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.dispatch.AnsibleDocumentationTargetProvider
import de.terletzkiy.ansibility.dispatch.AnsibleGotoDeclarationHandler
import de.terletzkiy.ansibility.fixtures.InfraTestData
import java.util.concurrent.TimeUnit

/**
 * Base for the variables area's platform tests: sub-trees of the sanitised infra fixture (line numbers identical to
 * the real repo) or of `testData/vars` become Ansible roots of the light project. The real extensions are used, as
 * the plugin registers them.
 */
abstract class VarsTestCase : BasePlatformTestCase() {
    override fun getTestDataPath(): String = InfraTestData.testDataPath.toString()

    /** Copies `infra/<path>` for each path to the same project path and re-detects the roots. */
    protected fun copyInfra(vararg paths: String) {
        for (path in paths) myFixture.copyDirectoryToProject("${InfraTestData.INFRA}/$path", path)
        refreshRoots()
    }

    /** Copies `vars/<path>` (this area's own test data) to [target] and re-detects the roots. */
    protected fun copyVarsData(path: String, target: String = path) {
        myFixture.copyDirectoryToProject("$VARS_DATA/$path", target)
        refreshRoots()
    }

    protected fun createFile(path: String, text: String): VirtualFile = myFixture.tempDirFixture.createFile(path, text).also { refreshRoots() }

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

    /** `path:line` of [location] relative to the project, like the plan's acceptance examples. */
    protected fun describe(location: SourceLocation): String {
        val base = myFixture.tempDirFixture.getFile("")!!
        val text = VfsUtilCore.loadText(location.file)
        return "${VfsUtilCore.getRelativePath(location.file, base)}:${StringUtil.offsetToLineNumber(text, location.offset) + 1}"
    }

    protected fun describe(element: PsiElement): String = describe((element as VarTargetElement).location)

    /** The site the registered classifiers find at [offset] of [path] (the first non-null, as the dispatcher asks). */
    protected fun classify(path: String, offset: Int): AnsibleSite? = runReadActionBlocking {
        SiteClassifier.EP_NAME.extensionList.firstNotNullOfOrNull { it.classify(psi(path), offset) }
    }

    /** Hover through the plugin's documentation entry point. */
    protected fun hover(path: String, offset: Int): DocumentationTarget =
        runReadActionBlocking { AnsibleDocumentationTargetProvider().documentationTargets(psi(path), offset) }.singleOrNull()
            ?: error("no documentation target at $path:$offset")

    protected fun html(target: DocumentationTarget): String = inBackgroundReadAction { (target.computeDocumentation() as DocumentationData).html }

    /** The card's text as a reader sees it: tags removed, entities decoded, whitespace collapsed. */
    protected fun text(html: String): String = plain(html)

    protected fun hint(target: DocumentationTarget): String = plain(inBackgroundReadAction { target.computeDocumentationHint() } ?: "")

    /** Ctrl+B through the plugin's Go to Declaration entry point. */
    protected fun gotoTargets(path: String, offset: Int): List<PsiElement> = inBackgroundReadAction {
        val file = psi(path)
        AnsibleGotoDeclarationHandler().getGotoDeclarationTargets(file.findElementAt(offset), offset, null)?.toList().orEmpty()
    }

    /** Runs [action] in a read action on a pooled thread, as the platform runs doc and navigation requests. */
    protected fun <T> inBackgroundReadAction(action: () -> T): T =
        ApplicationManager.getApplication().executeOnPooledThread<T> { runReadActionBlocking(action) }.get(60, TimeUnit.SECONDS)

    /** The part of [text] between [start] and the next of [ends] (or the end). */
    protected fun section(text: String, start: String, vararg ends: String): String {
        val from = text.indexOf(start)
        check(from >= 0) { "no '$start' in: $text" }
        val rest = text.substring(from + start.length)
        val to = ends.map { rest.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: rest.length
        return rest.substring(0, to)
    }

    companion object {
        const val VARS_DATA = "vars"
        val SECTIONS = arrayOf(
            "Type", "Required", "Default (spec)", "Runtime default", "Choices", "Aliases", "Options", "Declared by",
            "Set in", "This definition", "Used in",
            // Rows other areas contribute after the built-in ones (host awareness, F8.2).
            "Effect",
        )

        fun plain(html: String): String {
            val noTags = html.replace(Regex("</?(b|i|code|span|a|font)(\\s[^>]*)?>"), "").replace(Regex("<[^>]+>"), " ")
            val decoded = noTags
                .replace(Regex("&#(\\d+);")) { String(Character.toChars(it.groupValues[1].toInt())) }
                .replace(Regex("&#x([0-9a-fA-F]+);")) { String(Character.toChars(it.groupValues[1].toInt(16))) }
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&")
            return decoded.replace(Regex("\\s+"), " ").trim()
        }
    }
}
