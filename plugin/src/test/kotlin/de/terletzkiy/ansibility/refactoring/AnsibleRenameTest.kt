package de.terletzkiy.ansibility.refactoring

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.refactoring.rename.PsiElementRenameHandler
import com.intellij.refactoring.rename.RenameHandlerRegistry
import de.terletzkiy.ansibility.inspections.undefined.UndefinedTestCase

/** Shift+F6 on Ansible names: a variable with its spec option, defaults, inventory and uses; a role with its references. */
class AnsibleRenameTest : UndefinedTestCase() {
    override fun addFixtureFiles() {
        add(
            "$ROLE/meta/argument_specs.yml",
            """
            ---
            argument_specs:
              main:
                short_description: cache
                options:
                  webcache_port:
                    type: int
                  webcache_port_extra:
                    type: int
            """,
        )
        add("$ROLE/meta/main.yml", "---\ngalaxy_info:\n  role_name: webcache\ndependencies: []")
        add("$ROLE/defaults/main.yml", "---\nwebcache_port: 8080\nwebcache_port_extra: 1")
        add(
            "$ROLE/tasks/main.yml",
            """
            ---
            - name: Show
              ansible.builtin.debug:
                msg: "{{ webcache_port + webcache_port_extra }}"
              when: webcache_port > 0
            - name: Loop
              ansible.builtin.debug:
                msg: "{{ item }}"
              loop: [1, 2]
            """,
        )
        add("$ROLE/templates/cache.conf.j2", "listen {{ webcache_port }}\n")
        add("$FALCON/environments/prod/host_vars/prod-prod1/webcache.yml", "---\nwebcache_port: 9090")
        add("$FALCON/webcache-site.yml", "---\n- name: Cache\n  hosts: all\n  roles:\n    - webcache\n")
        add(
            "$FALCON/roles/consumer/tasks/main.yml",
            "---\n- name: Pull in the cache\n  ansible.builtin.include_role:\n    name: webcache\n",
        )
        add("$FALCON/roles/consumer/meta/main.yml", "---\ndependencies:\n  - role: webcache\n")
    }

    fun testVariableRenameReachesSpecDefaultsInventoryAndUses() {
        caret("$ROLE/tasks/main.yml", "webcache_port +")
        rename("webcache_listen")
        assertText("$ROLE/meta/argument_specs.yml", "webcache_listen:\n", "webcache_port_extra:")
        assertText("$ROLE/defaults/main.yml", "webcache_listen: 8080", "webcache_port_extra: 1")
        assertText("$ROLE/tasks/main.yml", "{{ webcache_listen + webcache_port_extra }}", "when: webcache_listen > 0")
        assertText("$ROLE/templates/cache.conf.j2", "listen {{ webcache_listen }}")
        assertText("$FALCON/environments/prod/host_vars/prod-prod1/webcache.yml", "webcache_listen: 9090")
    }

    fun testLoopItemIsRefused() {
        caret("$ROLE/tasks/main.yml", "item }}")
        val symbol = com.intellij.openapi.application.runReadActionBlocking { VarRenamer.symbolAt(myFixture.file, myFixture.caretOffset) }!!
        assertNotNull(com.intellij.openapi.application.runReadActionBlocking { VarRenamer.refusal(project, symbol) })
        assertNotNull(VarRenamer.invalidName("1abc"))
        assertNotNull(VarRenamer.invalidName("not"))
        assertNull(VarRenamer.invalidName("fine_name"))
    }

    fun testRoleRenameMovesTheDirectoryAndEveryReference() {
        caret("$FALCON/webcache-site.yml", "webcache\n")
        rename("edgecache")
        assertNotNull(myFixture.findFileInTempDir("$FALCON/roles/edgecache/tasks/main.yml"))
        assertNull(myFixture.findFileInTempDir("$ROLE/tasks/main.yml"))
        assertText("$FALCON/webcache-site.yml", "    - edgecache\n")
        assertText("$FALCON/roles/consumer/tasks/main.yml", "name: edgecache")
        assertText("$FALCON/roles/consumer/meta/main.yml", "- role: edgecache")
        assertText("$FALCON/roles/edgecache/meta/main.yml", "role_name: edgecache")
        // The role's prefixed variables follow it, with all their uses.
        assertText("$FALCON/roles/edgecache/defaults/main.yml", "edgecache_port: 8080", "edgecache_port_extra: 1")
        assertText("$FALCON/roles/edgecache/tasks/main.yml", "{{ edgecache_port + edgecache_port_extra }}")
        assertText("$FALCON/environments/prod/host_vars/prod-prod1/webcache.yml", "edgecache_port: 9090")
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
        assertTrue("an Ansibility handler: $handler", handler is VarRenameHandler || handler is RoleRenameHandler)
        handler!!.invoke(project, myFixture.editor, myFixture.file, context)
    }

    private fun assertText(path: String, vararg parts: String) {
        val text = FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir(path) ?: error("missing $path"))!!.text
        for (part in parts) assertTrue("'$part' in $path:\n$text", part in text)
    }

    private companion object {
        const val ROLE = "$FALCON/roles/webcache"
    }
}
