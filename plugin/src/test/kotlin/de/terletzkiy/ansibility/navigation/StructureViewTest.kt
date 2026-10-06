package de.terletzkiy.ansibility.navigation

import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl

/** Plan X55: plays, sections, blocks and tasks in the structure view and the breadcrumbs. */
class StructureViewTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("ansible.cfg", "[defaults]\n")
        myFixture.addFileToProject("inventory/hosts.yml", "all:\n  hosts:\n    h1:\n")
        myFixture.addFileToProject("site.yml", PLAYBOOK)
        myFixture.addFileToProject("roles/web/tasks/main.yml", TASKS)
        myFixture.addFileToProject("inventory/group_vars/all.yml", "web_port: 80\n")
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
    }

    fun testPlaybookOutline() {
        assertEquals(
            """
            Deploy web · hosts: web
              pre_tasks · 1 item
                Wait for the network · ansible.builtin.wait_for_connection
              roles · 2 items
                common
                web
              tasks · 1 item
                ansible.builtin.include_role web
            import_playbook other.yml
            """.trimIndent(),
            outline("site.yml"),
        )
    }

    fun testTaskFileOutlineNestsBlocks() {
        assertEquals(
            """
            Install packages · ansible.builtin.apt
            Configure · block
              block · 2 items
                Render config · ansible.builtin.template
                ansible.builtin.import_tasks extra.yml
              rescue · 1 item
                ansible.builtin.debug
            """.trimIndent(),
            outline("roles/web/tasks/main.yml"),
        )
    }

    fun testVarsFilesKeepTheYamlOutline() {
        assertEquals("YAML document\n  web_port · 80", outline("inventory/group_vars/all.yml"))
    }

    fun testBreadcrumbsNameTheBlockAndTask() {
        myFixture.configureFromTempProjectFile("roles/web/tasks/main.yml")
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("src: app.conf.j2"))
        val crumbs = myFixture.breadcrumbsAtCaret.map { it.text }
        assertEquals(listOf("Document 1/1", "Configure", "block", "Render config", "ansible.builtin.template:", "src:"), crumbs)
    }

    private fun outline(path: String): String = runReadActionBlocking {
        val file = myFixture.psiManager.findFile(myFixture.findFileInTempDir(path))!!
        val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(file) as TreeBasedStructureViewBuilder
        val model = builder.createStructureViewModel(null)
        try {
            buildString { model.root.children.forEach { print(it, 0) } }.trimEnd()
        } finally {
            model.dispose()
        }
    }

    private fun StringBuilder.print(element: TreeElement, depth: Int) {
        val presentation = element.presentation
        append("  ".repeat(depth)).append(listOfNotNull(presentation.presentableText, presentation.locationString).joinToString(" · ")).append('\n')
        element.children.forEach { print(it, depth + 1) }
    }

    private companion object {
        val PLAYBOOK = """
            - name: Deploy web
              hosts: web
              pre_tasks:
                - name: Wait for the network
                  ansible.builtin.wait_for_connection:
              roles:
                - common
                - role: web
                  tags: [web]
              tasks:
                - ansible.builtin.include_role:
                    name: web
            - ansible.builtin.import_playbook: other.yml
        """.trimIndent() + "\n"

        val TASKS = """
            - name: Install packages
              ansible.builtin.apt:
                name: nginx
            - name: Configure
              when: web_enabled
              block:
                - name: Render config
                  ansible.builtin.template:
                    src: app.conf.j2
                    dest: /etc/app.conf
                - ansible.builtin.import_tasks: extra.yml
              rescue:
                - ansible.builtin.debug:
                    msg: failed
        """.trimIndent() + "\n"
    }
}
