package de.terletzkiy.ansibility.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl

/** Plan X37: Ctrl+B on lookup('file' | 'template' | 'fileglob', ...) paths, along the role's search path. */
class LookupPathNavigationTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("ansible.cfg", "[defaults]\n")
        myFixture.addFileToProject("site.yml", "- hosts: all\n  roles: [app]\n")
        myFixture.addFileToProject("inventory/hosts.yml", "all:\n  hosts:\n    h1:\n")
        myFixture.addFileToProject("roles/app/files/key.pub", "ssh-ed25519 AAAA\n")
        myFixture.addFileToProject("roles/app/templates/motd.j2", "hello\n")
        myFixture.addFileToProject("roles/app/files/conf/a.cfg", "a\n")
        myFixture.addFileToProject("roles/app/files/conf/b.cfg", "b\n")
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
    }

    fun testFileLookupOpensTheRoleFile() {
        assertEquals(listOf("key.pub"), targets("- ansible.builtin.copy:\n    content: \"{{ lookup('file', 'key<caret>.pub') }}\"\n    dest: /tmp/k\n"))
    }

    fun testTemplateLookupOpensTheRoleTemplate() {
        assertEquals(listOf("motd.j2"), targets("- ansible.builtin.debug:\n    msg: \"{{ lookup('template', 'mo<caret>td.j2') }}\"\n"))
    }

    fun testFileglobOffersEveryMatch() {
        assertEquals(listOf("a.cfg", "b.cfg"), targets("- ansible.builtin.debug:\n    msg: \"{{ lookup('fileglob', 'conf/*<caret>.cfg') }}\"\n").sorted())
    }

    fun testOtherStringsAreLeftAlone() {
        assertEquals(emptyList<String>(), targets("- ansible.builtin.debug:\n    msg: \"{{ lookup('env', 'HO<caret>ME') }}\"\n"))
    }

    private fun targets(task: String): List<String> {
        val file = myFixture.addFileToProject("roles/app/tasks/main.yml", task.replace("<caret>", ""))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(task.indexOf("<caret>"))
        return GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, myFixture.caretOffset).mapNotNull { it.containingFile?.name }
    }
}
