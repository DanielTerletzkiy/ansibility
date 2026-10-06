package de.terletzkiy.ansibility.workspace

import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.workspace.actions.NewRoleAction

/** Plan X64 (New Ansible Role) and X69 (live templates in task files only). */
class AuthoringExtrasTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("ansible.cfg", "[defaults]\n")
        myFixture.addFileToProject("site.yml", "- hosts: all\n  roles: []\n")
        myFixture.addFileToProject("inventory/hosts.yml", "all:\n  hosts:\n    h1:\n")
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
    }

    fun testNewRoleCreatesTheConventionalLayout() {
        val base = myFixture.tempDirFixture.getFile("")!!
        val tasks = NewRoleAction.create(project, base, "web")!!
        assertEquals("roles/web/tasks/main.yml", VfsUtilCore.getRelativePath(tasks, base))
        for (path in listOf("defaults/main.yml", "handlers/main.yml", "meta/main.yml", "meta/argument_specs.yml")) {
            assertNotNull(path, base.findFileByRelativePath("roles/web/$path"))
        }
        assertNull("an existing role is never overwritten", NewRoleAction.create(project, base.findChild("roles")!!, "web"))
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
        val root = runReadActionBlocking { AnsibleWorkspace.getInstance(project).roots().single() }
        assertEquals(listOf("web"), runReadActionBlocking { RoleRegistry.getInstance(project).roles(root).map { it.name } })
    }

    fun testTaskTemplateExpandsInTaskFilesOnly() {
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
        val tasks = myFixture.addFileToProject("roles/app/tasks/main.yml", "")
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
        myFixture.configureFromExistingVirtualFile(tasks.virtualFile)
        myFixture.type("task\t")
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.startsWith("- name: Describe the task"))
        val other = myFixture.addFileToProject("inventory/group_vars/all.yml", "")
        myFixture.configureFromExistingVirtualFile(other.virtualFile)
        myFixture.type("task\t")
        assertFalse(myFixture.editor.document.text.contains("- name:"))
    }
}
