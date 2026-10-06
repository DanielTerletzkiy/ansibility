package de.terletzkiy.ansibility.vars

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.refactoring.rename.PsiElementRenameHandler
import com.intellij.refactoring.rename.RenameHandlerRegistry
import de.terletzkiy.ansibility.refactoring.VarRenameHandler
import de.terletzkiy.ansibility.refactoring.VarRenamer
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.api.VarsLayer

/** Inline variables of INI inventories (plan amendment R10, D65): definitions, Ctrl+B and names from the parsed model. */
class IniInventoryVarsTest : VarsTestCase() {

    private fun setUpProject() {
        myFixture.addFileToProject("hosts.ini", "[web]\nweb1 http_port=8080\n\n[web:vars]\nnginx_user=www\n")
        myFixture.addFileToProject("site.yml", "- hosts: web\n  roles:\n    - web\n")
        myFixture.addFileToProject("roles/web/tasks/main.yml", "- debug:\n    msg: \"{{ http_port }} {{ nginx_user }}\"\n")
        refreshRoots()
    }

    fun testInlineIniVarsAreDefinitions() {
        setUpProject()
        val root = runReadActionBlocking { AnsibleWorkspace.getInstance(project).roots().single() }
        val port = inBackgroundReadAction { VarService.getInstance(project).symbol(root, "http_port") }.definitions.single()
        assertEquals(VarDefKind.INVENTORY_INLINE, port.kind)
        assertEquals(VarsLayer.INVENTORY_FILE_HOST, port.layer)
        assertEquals("web1", port.host)
        assertEquals("hosts", port.environment)
        assertEquals("8080", port.preview)
        assertEquals("hosts.ini:2", describe(port.location))

        val user = inBackgroundReadAction { VarService.getInstance(project).symbol(root, "nginx_user") }.definitions.single()
        assertEquals(VarsLayer.INVENTORY_FILE_GROUP, user.layer)
        assertEquals("web", user.group)
        assertEquals("hosts.ini:5", describe(user.location))

        val names = inBackgroundReadAction { VarService.getInstance(project).allNames(root) }
        assertTrue(names.containsAll(listOf("http_port", "nginx_user")))
    }

    fun testRenameFromATaskEditsTheIniKey() {
        setUpProject()
        caret("roles/web/tasks/main.yml", "http_port }}")
        rename("listen_port")
        assertText("hosts.ini", "web1 listen_port=8080\n")
        assertText("roles/web/tasks/main.yml", "{{ listen_port }}")
    }

    fun testRenameAndHighlightFromTheIniKey() {
        setUpProject()
        caret("hosts.ini", "nginx_user=")
        val symbol = runReadActionBlocking { VarRenamer.symbolAt(myFixture.file, myFixture.caretOffset) }
        assertEquals("nginx_user", symbol?.name)
        rename("web_user")
        assertText("hosts.ini", "web_user=www")
        assertText("roles/web/tasks/main.yml", "{{ web_user }}")
    }

    private fun caret(path: String, marker: String) {
        myFixture.configureFromExistingVirtualFile(vf(path))
        val at = myFixture.editor.document.text.indexOf(marker)
        assertTrue("'$marker' in $path", at >= 0)
        myFixture.editor.caretModel.moveToOffset(at + 1)
    }

    private fun rename(newName: String) {
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.EDITOR, myFixture.editor)
            .add(CommonDataKeys.PSI_FILE, myFixture.file)
            .add(PsiElementRenameHandler.DEFAULT_NAME, newName)
            .build()
        val handler = RenameHandlerRegistry.getInstance().getRenameHandler(context)
        assertTrue("the variable rename handler: $handler", handler is VarRenameHandler)
        handler!!.invoke(project, myFixture.editor, myFixture.file, context)
    }

    private fun assertText(path: String, vararg parts: String) {
        val text = FileDocumentManager.getInstance().getDocument(vf(path))!!.text
        for (part in parts) assertTrue("'$part' in $path:\n$text", part in text)
    }

    fun testCtrlBFromATaskGoesToTheIniLine() {
        setUpProject()
        val tasks = "roles/web/tasks/main.yml"
        assertEquals(listOf("hosts.ini:2"), gotoTargets(tasks, offsetAt(tasks, 2, "http_port", 2)).map(::describe))
        assertEquals(listOf("hosts.ini:5"), gotoTargets(tasks, offsetAt(tasks, 2, "nginx_user", 2)).map(::describe))
    }
}
