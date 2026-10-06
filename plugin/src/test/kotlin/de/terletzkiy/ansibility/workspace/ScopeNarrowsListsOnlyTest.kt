package de.terletzkiy.ansibility.workspace

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.api.ScopeChoice
import de.terletzkiy.ansibility.dispatch.AnsibleGotoDeclarationHandler
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.navigation.RefTargetElement
import java.util.concurrent.TimeUnit

/**
 * The R9 rule "traversal is workspace-wide; resolution is root-scoped" (F9.1, acceptance 9): under a scope that
 * excludes falcon, a reference in an falcon file resolves to the same falcon targets, role lookups are unchanged, and the
 * file's highlighting is identical. The scope narrows lists only.
 */
@RequiresInfraFixture
class ScopeNarrowsListsOnlyTest : WorkspaceScopeTestCase() {
    private val playbook = "repos/falcon/ansible/playbook-setup-system.yml"

    fun testAReferenceIntoAnOutOfScopeRootStillResolves() {
        copyInfra()
        addScope("heron", "file:repos/heron//*")
        val offset = offsetOf(playbook, 25, "role: system", "role: ".length + 2)
        val underAll = gotoTargets(offset)
        assertFalse("role: system resolves under All roots", underAll.isEmpty())
        assertTrue(underAll.toString(), underAll.all { it.startsWith("repos/falcon/ansible/roles/system") })

        service.set(ScopeChoice.Named("heron"))
        val scope = service.current()
        assertEquals(listOf("heron"), names(scope.roots))
        assertFalse("falcon is outside the scope", scope.contains(vf(playbook)))
        assertFalse(scope.contains(vf("repos/falcon/ansible/roles/system/tasks/main.yml")))
        assertEquals("Ctrl+B is unchanged under a scope that excludes falcon", underAll, gotoTargets(offset))

        val falcon = root("falcon")
        assertEquals(
            "role lookups stay root-scoped and complete",
            vf("repos/falcon/ansible/roles/system"),
            runReadActionBlocking { RoleRegistry.getInstance(project).roles(falcon).single { it.name == "system" }.dir },
        )
    }

    fun testHighlightingIsIdenticalUnderAnyScope() {
        copyInfra()
        addScope("heron", "file:repos/heron//*")
        myFixture.configureFromExistingVirtualFile(vf("repos/falcon/ansible/roles/system/tasks/main.yml"))
        val underAll = highlights()
        service.set(ScopeChoice.Named("heron"))
        assertEquals("the Problems view never changes with the scope", underAll, highlights())
        service.set(ScopeChoice.Roots(setOf("golden")))
        assertEquals(underAll, highlights())
    }

    private fun highlights(): List<String> =
        myFixture.doHighlighting().map { "${it.severity}:${it.startOffset}-${it.endOffset}:${it.description}" }.sorted()

    private fun offsetOf(path: String, line: Int, marker: String, delta: Int): Int {
        val text = VfsUtilCore.loadText(vf(path))
        val lineStart = StringUtil.lineColToOffset(text, line - 1, 0)
        val index = text.indexOf(marker, lineStart)
        check(index >= 0 && StringUtil.offsetToLineNumber(text, index) == line - 1) { "'$marker' not on line $line of $path" }
        return index + delta
    }

    /** Ctrl+B through the plugin's Go to Declaration entry point, on a pooled thread in a read action. */
    private fun gotoTargets(offset: Int): List<String> = ApplicationManager.getApplication().executeOnPooledThread<List<String>> {
        runReadActionBlocking {
            val file = PsiManager.getInstance(project).findFile(vf(playbook))!!
            AnsibleGotoDeclarationHandler().getGotoDeclarationTargets(file.findElementAt(offset), offset, null)
                ?.map(::describe).orEmpty()
        }
    }.get(60, TimeUnit.SECONDS)

    private fun describe(element: PsiElement): String {
        val base = myFixture.tempDirFixture.getFile("")!!
        val file = when (element) {
            is RefTargetElement -> element.file
            is PsiDirectory -> element.virtualFile
            is PsiFile -> element.virtualFile
            else -> element.containingFile?.virtualFile
        }
        return file?.let { VfsUtilCore.getRelativePath(it, base) } ?: element.toString()
    }
}
