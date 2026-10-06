package de.terletzkiy.ansibility.layout

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.InventoryService
import de.terletzkiy.ansibility.api.ProjectLayoutService
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.api.VarsLayer
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.context.ContextPresentation
import de.terletzkiy.ansibility.context.InventoryShape
import de.terletzkiy.ansibility.context.TargetVersion
import de.terletzkiy.ansibility.model.effective.HostViews

/** Projects outside the `environments/<env>/hosts.yml` convention (plan amendment R10): flat, `inventory/` and cfg layouts. */
class FlatLayoutTest : BasePlatformTestCase() {

    fun testRootHostsIniWithoutCfgIsOneHiddenEnvironment() {
        add("hosts.ini", "[web]\nweb1 ansible_host=10.0.0.1 http_port=8080\nweb2\n\n[db]\ndb1\n")
        add("group_vars/web.yml", "nginx_workers: 4\n")
        add("site.yml", "- hosts: web\n  roles:\n    - web\n")
        add("roles/web/tasks/main.yml", "- debug: msg=hi\n")
        val root = singleRoot()
        assertEquals(RootKind.PROJECT, root.kind)
        val layout = layout(root)
        assertEquals(listOf("hosts"), layout.inventories.map { it.id })
        assertTrue(layout.isSingleInventory)

        val hosts = context("hosts.ini")
        assertEquals(FileKind.INVENTORY_INI, hosts.kind)
        assertEquals("hosts", hosts.environment)

        val vars = context("group_vars/web.yml")
        assertEquals(VarsLayer.INVENTORY_GROUP_VARS, vars.layer)
        assertEquals("the inventory dir is the playbook dir (D57)", VarsLayer.PLAYBOOK_GROUP_VARS, vars.playbookLayer)

        val inventory = runReadActionBlocking { InventoryService.getInstance(project).inventories(root) }.single()
        assertEquals(listOf("web1", "web2", "db1"), inventory.hosts.keys.toList())
        assertEquals("10.0.0.1", inventory.hosts.getValue("web1").ansibleHost)
        val view = runReadActionBlocking { HostViews.getInstance(project).view(root, "hosts", "web1", root.dir) }!!
        assertNotNull("group_vars next to hosts.ini apply", view.view["nginx_workers"])
        assertNotNull("inline INI host vars apply", view.view["http_port"])

        val shape = runReadActionBlocking { InventoryShape.of(project, root) }
        assertEquals("Ansibility: ${root.displayName} · All hosts", ContextPresentation.statusText(root, TargetVersion.UNKNOWN, shape))
    }

    fun testInventoryDirWithoutCfgIsOneEnvironmentPerFile() {
        add("inventory/prod.yml", "all:\n  children:\n    web:\n      hosts:\n        prod-web1:\n")
        add("inventory/staging.ini", "[web]\nstage-web1\n")
        add("inventory/group_vars/all.yml", "domain: example.org\n")
        add("inventory/README.md", "notes\n")
        add("site.yml", "- hosts: web\n  tasks: []\n")
        val root = singleRoot()
        assertEquals(RootKind.PROJECT, root.kind)
        assertEquals(listOf("prod", "staging"), layout(root).inventories.map { it.id })
        assertFalse(layout(root).isSingleInventory)

        assertEquals(FileKind.INVENTORY, context("inventory/prod.yml").kind)
        assertEquals(FileKind.INVENTORY_INI, context("inventory/staging.ini").kind)
        val shared = context("inventory/group_vars/all.yml")
        assertEquals(VarsLayer.INVENTORY_GROUP_VARS_ALL, shared.layer)
        assertEquals(listOf("prod", "staging"), shared.environments)
        assertNull(shared.playbookLayer)

        val inventories = runReadActionBlocking { InventoryService.getInstance(project).inventories(root) }
        assertEquals(listOf("prod" to listOf("prod-web1"), "staging" to listOf("stage-web1")), inventories.map { it.environment to it.hosts.keys.toList() })
        val view = runReadActionBlocking { HostViews.getInstance(project).view(root, "staging", "stage-web1", root.dir) }!!
        assertNotNull(view.view["domain"])
    }

    fun testCfgInventoryListIsOneMergedEnvironment() {
        add("ansible.cfg", "[defaults]\ninventory = ./staging.ini, ./production.ini\n")
        add("staging.ini", "[web]\nstage1\n")
        add("production.ini", "[web]\nprod1\n[web:vars]\ntier=prod\n")
        val root = singleRoot()
        val def = layout(root).inventories.single()
        assertTrue(def.isDefault)
        assertEquals(2, def.sources.size)
        val inventory = runReadActionBlocking { InventoryService.getInstance(project).inventories(root) }.single()
        assertEquals(listOf("stage1", "prod1"), inventory.hosts.keys.toList())
        assertEquals(FileKind.INVENTORY_INI, context("production.ini").kind)
        val view = runReadActionBlocking { HostViews.getInstance(project).view(root, def.id, "stage1", root.dir) }!!
        assertNotNull("[web:vars] of the second file reaches a host of the first", view.view["tier"])
    }

    fun testCfgEntryInsideAConventionEnvironmentOnlyMarksItDefault() {
        add("ansible.cfg", "[defaults]\ninventory = environments/prod/hosts.yml\n")
        add("environments/prod/hosts.yml", "all:\n  hosts:\n    p1:\n")
        add("environments/test/hosts.yml", "all:\n  hosts:\n    t1:\n")
        val root = singleRoot()
        val inventories = layout(root).inventories
        assertEquals(listOf("prod", "test"), inventories.map { it.id })
        assertEquals(listOf(true, false), inventories.map { it.isDefault })
        assertTrue(inventories.all { it.isConvention })
    }

    fun testToolIniFilesAreNotInventories() {
        add("tox.ini", "[tox]\nenvlist = py3\n")
        add("site.yml", "- hosts: all\n  tasks: []\n")
        add("roles/web/tasks/main.yml", "- debug: msg=hi\n")
        val root = singleRoot()
        assertEquals(RootKind.PROJECT, root.kind)
        assertFalse(runReadActionBlocking { InventoryService.getInstance(project).hasInventory(root) })
    }

    fun testRolesWithoutPlaybookOrInventoryStayARoleLibrary() {
        add("roles/web/tasks/main.yml", "- debug: msg=hi\n")
        add("README.md", "x\n")
        assertEquals(RootKind.ROLE_LIBRARY, singleRoot().kind)
    }

    fun testSuperRepoKeepsTheRootsBelowIt() {
        add("inventory/hosts.ini", "[all]\nx1\n")
        add("site.yml", "- hosts: all\n  tasks: []\n")
        add("golden/roles/base/tasks/main.yml", "- debug: msg=hi\n")
        add("repos/app/ansible/ansible.cfg", "[defaults]\n")
        add("repos/app/ansible/environments/prod/hosts.yml", "all:\n  hosts:\n    a1:\n")
        val roots = roots()
        assertEquals(
            listOf("golden" to RootKind.ROLE_LIBRARY, "ansible" to RootKind.PROJECT),
            roots.map { it.dir.name to it.kind },
        )
    }

    private fun add(path: String, text: String): VirtualFile = myFixture.addFileToProject(path, text).virtualFile

    private fun roots(): List<AnsibleRoot> {
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
        return runReadActionBlocking { AnsibleWorkspace.getInstance(project).roots() }
    }

    private fun singleRoot(): AnsibleRoot = roots().single()

    private fun layout(root: AnsibleRoot) = runReadActionBlocking { ProjectLayoutService.getInstance(project).layout(root) }

    private fun context(path: String): FileContext {
        val file = myFixture.findFileInTempDir(path)
        return runReadActionBlocking { AnsibleWorkspace.getInstance(project).contextOf(file) }!!
    }
}
