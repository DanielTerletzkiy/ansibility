package de.terletzkiy.ansibility.navigation

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.SiteClassifier
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.dispatch.AnsibleGotoDeclarationHandler
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.settings.SettingsTestSupport
import java.util.concurrent.TimeUnit

/**
 * Base for the role-navigation area's platform tests: sub-trees of the sanitised infra fixture (line numbers identical
 * to the real repo) become Ansible roots of the light project, and the extensions run as the plugin registers them.
 */
abstract class RefsTestCase : BasePlatformTestCase() {
    override fun getTestDataPath(): String = InfraTestData.testDataPath.toString()

    override fun tearDown() {
        try {
            SettingsTestSupport.resetAll(project)
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

    protected fun createFile(path: String, text: String): VirtualFile = myFixture.tempDirFixture.createFile(path, text).also { refreshRoots() }

    protected fun refreshRoots() {
        (AnsibleWorkspaceImpl.getInstance(project) ?: error("AnsibleWorkspace is not AnsibleWorkspaceImpl")).structureChanged()
    }

    protected fun vf(path: String): VirtualFile = myFixture.findFileInTempDir(path) ?: error("missing $path")

    protected fun psi(path: String): PsiFile = runReadActionBlocking { PsiManager.getInstance(project).findFile(vf(path)) } ?: error("no PSI for $path")

    protected fun contextOf(path: String): FileContext = runReadActionBlocking { AnsibleWorkspace.getInstance(project).contextOf(vf(path)) } ?: error("no context for $path")

    /** The offset of [marker] on 1-based [line] of [path], plus [delta]; fails when the line does not contain it. */
    protected fun offsetAt(path: String, line: Int, marker: String, delta: Int = 0): Int {
        val text = VfsUtilCore.loadText(vf(path))
        val lineStart = StringUtil.lineColToOffset(text, line - 1, 0)
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        val index = text.substring(lineStart, lineEnd).indexOf(marker)
        check(index >= 0) { "'$marker' not on line $line of $path: ${text.substring(lineStart, lineEnd)}" }
        return lineStart + index + delta
    }

    /** The site the registered classifiers find at [offset] of [path] (the first non-null, as the dispatcher asks). */
    protected fun classify(path: String, offset: Int): AnsibleSite? = runReadActionBlocking {
        SiteClassifier.EP_NAME.extensionList.firstNotNullOfOrNull { it.classify(psi(path), offset) }
    }

    /** What our classifier alone says at [offset] of [path]. */
    protected fun ours(path: String, offset: Int): AnsibleSite? = runReadActionBlocking { RefsSiteClassifier().classify(psi(path), offset) }

    /** Ctrl+B through the plugin's Go to Declaration entry point. */
    protected fun gotoTargets(path: String, offset: Int): List<PsiElement> = inBackgroundReadAction {
        val file = psi(path)
        AnsibleGotoDeclarationHandler().getGotoDeclarationTargets(file.findElementAt(offset), offset, null)?.toList().orEmpty()
    }

    /** `path:line` of a Go to Declaration target relative to the project (`path` for a file or directory). */
    protected fun describe(element: PsiElement): String = runReadActionBlocking {
        when (element) {
            is RefTargetElement -> "${relative(element.file)}:${lineOf(element.file, element.offset)}"
            is PsiFile -> relative(element.virtualFile)
            is PsiDirectory -> relative(element.virtualFile) + "/"
            else -> element.toString()
        }
    }

    protected fun relative(file: VirtualFile): String {
        val base = myFixture.tempDirFixture.getFile("")!!
        return VfsUtilCore.getRelativePath(file, base) ?: file.path
    }

    protected fun lineOf(file: VirtualFile, offset: Int): Int = StringUtil.offsetToLineNumber(VfsUtilCore.loadText(file), offset) + 1

    /** Runs [action] in a read action on a pooled thread, as the platform runs navigation requests. */
    protected fun <T> inBackgroundReadAction(action: () -> T): T =
        ApplicationManager.getApplication().executeOnPooledThread<T> { runReadActionBlocking(action) }.get(60, TimeUnit.SECONDS)

    companion object {
        const val GOLDEN = "golden"
        const val KEYCLOAK_PLAYBOOK = "golden/playbooks/playbook-setup-keycloak.yml"
    }
}
