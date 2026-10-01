package de.terletzkiy.ansibility.lang.jinja.injection

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.lang.jinja.psi.AnsibleJinjaFile
import org.jetbrains.yaml.psi.YAMLScalar
import java.util.concurrent.TimeUnit

/**
 * Base of the Jinja-in-YAML tests (WU C4/C5): sub-trees of the sanitised infra fixture (line numbers identical to the
 * real repo) or synthetic files become Ansible roots of the light project, with the plugin's real extensions.
 */
abstract class JinjaInjectionTestCase : BasePlatformTestCase() {
    /** One injected Ansible Jinja fragment of a YAML file. */
    data class Fragment(
        /** The host scalar's range in the file. */
        val scalar: TextRange,
        val mode: JinjaInjectionMode?,
        /** The fragment's whole text (expression mode includes the `{{ ` prefix and ` }}` suffix). */
        val text: String,
        /** The host range the fragment's text maps to. */
        val hostRange: TextRange,
        /** `description at <context>` of each Jinja syntax error in the fragment. */
        val errors: List<String>,
    )

    override fun getTestDataPath(): String = InfraTestData.testDataPath.toString()

    /** Copies `infra/<path>` for each path to the same project path and re-detects the roots. */
    protected fun copyInfra(vararg paths: String) {
        for (path in paths) myFixture.copyDirectoryToProject("${InfraTestData.INFRA}/$path", path)
        refreshRoots()
    }

    protected fun createFile(path: String, text: String): VirtualFile =
        myFixture.tempDirFixture.createFile(path, text.trimIndent() + "\n").also { refreshRoots() }

    protected fun refreshRoots() {
        (AnsibleWorkspaceImpl.getInstance(project) ?: error("AnsibleWorkspace is not AnsibleWorkspaceImpl")).structureChanged()
    }

    protected fun vf(path: String): VirtualFile = myFixture.findFileInTempDir(path) ?: error("missing $path")

    protected fun psi(path: String): PsiFile = runReadActionBlocking { PsiManager.getInstance(project).findFile(vf(path)) } ?: error("no PSI for $path")

    protected fun text(path: String): String = VfsUtilCore.loadText(vf(path))

    /** The offset of [marker] on 1-based [line] of [path], plus [delta]; fails when the line does not contain it. */
    protected fun offsetAt(path: String, line: Int, marker: String, delta: Int = 0): Int {
        val text = text(path)
        val lineStart = StringUtil.lineColToOffset(text, line - 1, 0)
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        val index = text.substring(lineStart, lineEnd).indexOf(marker)
        check(index >= 0) { "'$marker' not on line $line of $path: ${text.substring(lineStart, lineEnd)}" }
        return lineStart + index + delta
    }

    /**
     * Every Ansible Jinja fragment injected into the YAML scalars of [path], in file order (the platform lists a
     * fragment once per host range, so a scalar split into several places is counted once).
     */
    protected fun fragments(path: String): List<Fragment> = inBackgroundReadAction {
        val file = PsiManager.getInstance(project).findFile(vf(path)) ?: error("no PSI for $path")
        val manager = InjectedLanguageManager.getInstance(project)
        PsiTreeUtil.findChildrenOfType(file, YAMLScalar::class.java).flatMap { scalar ->
            manager.getInjectedPsiFiles(scalar).orEmpty().map { it.first }.distinct().mapNotNull { element ->
                val injected = element as? AnsibleJinjaFile ?: return@mapNotNull null
                val text = injected.text
                val errors = PsiTreeUtil.findChildrenOfType(injected, PsiErrorElement::class.java).map { error ->
                    val at = error.textRange.startOffset
                    "${error.errorDescription} at <${text.substring(maxOf(0, at - 30), minOf(text.length, at + 30))}>"
                }
                Fragment(
                    scalar.textRange, injected.getUserData(JinjaInjectionMode.KEY), text,
                    manager.injectedToHost(injected, TextRange(0, text.length)), errors,
                )
            }
        }
    }

    /** The fragment injected into the scalar that contains [offset]; fails when there is none. */
    protected fun fragmentAt(path: String, offset: Int): Fragment =
        fragments(path).firstOrNull { it.scalar.containsOffset(offset) } ?: error("no fragment at $path:$offset")

    /** Runs [action] in a read action on a pooled thread, as the platform runs highlighting and navigation. */
    protected fun <T> inBackgroundReadAction(action: () -> T): T =
        ApplicationManager.getApplication().executeOnPooledThread<T> { runReadActionBlocking(action) }.get(60, TimeUnit.SECONDS)
}
