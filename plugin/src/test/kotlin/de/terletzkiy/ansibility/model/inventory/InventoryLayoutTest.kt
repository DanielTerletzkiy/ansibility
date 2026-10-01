package de.terletzkiy.ansibility.model.inventory

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import de.terletzkiy.ansibility.api.VarFile
import de.terletzkiy.ansibility.api.VarsLayer

/**
 * [InventoryServiceImpl] on the synthetic `testData/model-inventory/layout` tree, which covers the rules the infra
 * fixture does not: host ranges, `ansible_group_priority`, `hosts.yaml`, JSON and extension-less vars files,
 * hidden and backup entries, a directory hiding a same-named file, nested vars directories, orphans, and molecule
 * platforms with groups, children, an inline inventory and linked group_vars. The expectations were checked
 * against `ansible-inventory` 2.21.
 */
class InventoryLayoutTest : InventoryTestCase() {
    override fun setUp() {
        super.setUp()
        copyTree("model-inventory/layout", LAYOUT)
        refreshRoots()
    }

    private val service: InventoryServiceImpl get() = inventories as InventoryServiceImpl

    private fun dev() = inventories.inventories(root(LAYOUT)).single { it.environment == "dev" }

    private fun List<VarFile>.paths() = map { "${rel(it.file).removePrefix("$LAYOUT/")} ${it.layer.name}" }

    fun testEnvironmentsNeedAHostsFile() {
        val envs = inventories.inventories(root(LAYOUT))
        assertEquals("README.md is not an environment; hosts.yaml counts", listOf("dev", "staging"), envs.map { it.environment })
        assertEquals("$LAYOUT/environments/staging/hosts.yaml", rel(envs[1].hostsFile))
        assertEquals(listOf("stage1"), envs[1].hosts.keys.toList())
    }

    fun testGroupsDepthPriorityAndHostRanges() {
        val dev = dev()
        val web = dev.groups.getValue("web")
        assertEquals(5, web.priority)
        assertEquals("ansible_group_priority is not a variable", listOf("tier"), web.inlineVarKeys)
        assertEquals(2, web.depth)
        assertEquals(listOf("frontends"), web.parents)
        assertEquals(listOf("web", "zeta", "lb"), dev.groups.getValue("frontends").children)
        assertEquals("host ranges expand", listOf("lb1", "lb2"), dev.groups.getValue("lb").hosts)
        assertEquals(emptyList<String>(), dev.groups.getValue("empty").hosts)
        assertEquals("the top-level key, not the children entry", "$LAYOUT/environments/dev/hosts.yml:12", at(web.location))
        assertEquals(
            "all, then (depth, priority, name): zeta (2, 1) before web (2, 5)",
            listOf("all", "frontends", "zeta", "web"),
            dev.hosts.getValue("web01.example.com").groups,
        )
        assertEquals("{{ host_ips['web02'] }}", dev.hosts.getValue("web02").ansibleHost)
        assertEquals(listOf("all", "db"), dev.hosts.getValue("db1").groups)
    }

    fun testInventoryVarFilesFollowTheVarsPlugin() {
        assertEquals(
            listOf(
                "environments/dev/group_vars/all.yml INVENTORY_GROUP_VARS_ALL",
                // sort_groups: db, empty, frontends, ungrouped (depth 1), lb, zeta (2, 1), web (2, 5)
                "environments/dev/group_vars/db.json INVENTORY_GROUP_VARS",
                "environments/dev/group_vars/lb INVENTORY_GROUP_VARS",
                "environments/dev/group_vars/zeta.yml INVENTORY_GROUP_VARS",
                // web/ hides web.yml; .hidden.yml and notes.md are skipped; sub/ is read after the files, sorted
                "environments/dev/group_vars/web/10-base.yml INVENTORY_GROUP_VARS",
                "environments/dev/group_vars/web/20-extra.yaml INVENTORY_GROUP_VARS",
                "environments/dev/group_vars/web/sub/30-nested.yml INVENTORY_GROUP_VARS",
                // orphans last
                "environments/dev/group_vars/orphan.yml INVENTORY_GROUP_VARS",
                // a host name with dots, as a file without extension
                "environments/dev/host_vars/web01.example.com INVENTORY_HOST_VARS",
                "environments/dev/host_vars/web02/vars.yml INVENTORY_HOST_VARS",
                "environments/dev/host_vars/web02/vault.yml INVENTORY_HOST_VARS",
            ),
            dev().varFiles.paths(),
        )
        assertEquals("orphan", dev().varFiles.single { it.file.name == "orphan.yml" }.group)
    }

    fun testPlaybookVarFilesOfTheRoot() {
        assertEquals(
            listOf(
                "group_vars/all/vars.yml PLAYBOOK_GROUP_VARS_ALL",
                "group_vars/web.yml PLAYBOOK_GROUP_VARS",
                "host_vars/web01.example.com.yml PLAYBOOK_HOST_VARS",
            ),
            inventories.playbookVarFiles(root(LAYOUT)).paths(),
        )
        assertEquals("web01.example.com", inventories.playbookVarFiles(root(LAYOUT)).last().host)
    }

    fun testMoleculePseudoInventory() {
        val scenario = service.moleculeInventories(root(LAYOUT)).single()
        assertEquals("app", scenario.roleName)
        assertEquals(vf("$LAYOUT/roles/app/molecule/default"), scenario.scenarioDir)
        val inventory = scenario.inventory
        assertEquals(scenario.configFile, inventory.hostsFile)
        assertEquals(
            listOf("app_master-\${MOLECULE_RUN_ID:-local}", "app_backup-\${MOLECULE_RUN_ID:-local}", "app_plain"),
            inventory.hosts.keys.toList(),
        )
        assertTrue("molecule-only groups count as defined", inventory.groups.keys.containsAll(listOf("keepalived_master", "keepalived_backup", "extra_group")))
        assertEquals(listOf("keepalived_nodes"), inventory.groups.getValue("keepalived_master").children)
        assertEquals(listOf("app_master-\${MOLECULE_RUN_ID:-local}"), inventory.groups.getValue("keepalived_nodes").hosts)
        assertEquals("$LAYOUT/roles/app/molecule/default/molecule.yml:8", at(inventory.groups.getValue("keepalived_master").location))
        assertEquals("$LAYOUT/roles/app/molecule/default/molecule.yml:5", at(inventory.hosts.values.first().location))
        assertEquals(
            "a platform without groups is ungrouped; the inline inventory adds extra_group",
            listOf("all", "extra_group"),
            inventory.hosts.getValue("app_plain").groups,
        )
        assertEquals(
            listOf("roles/app/molecule/default/linked_vars/keepalived_backup.yml MOLECULE_INVENTORY"),
            inventory.varFiles.paths(),
        )

        val inline = scenario.inlineVars.associateBy { it.name }
        assertEquals("all", inline.getValue("system_hostname").group)
        assertEquals("molecule.example.test", inline.getValue("system_hostname").preview)
        assertNull("vault_* values are never previewed", inline.getValue("vault_molecule_secret").preview)
        assertEquals("keepalived_master", inline.getValue("keepalived_state").group)
        assertEquals("app_plain", inline.getValue("app_port").host)
        assertEquals("$LAYOUT/roles/app/molecule/default/molecule.yml:32", at(inline.getValue("app_port").location))
        assertTrue(scenario.inlineVars.all { it.layer == VarsLayer.MOLECULE_INVENTORY })
    }

    fun testModelIsCachedAndFollowsStructureAndYamlChanges() {
        val first = inventories.inventories(root(LAYOUT))
        assertSame("cached while nothing changes", first[0], inventories.inventories(root(LAYOUT))[0])

        val document = FileDocumentManager.getInstance().getDocument(vf("$LAYOUT/environments/dev/hosts.yml"))!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText("all:\n  hosts:\n    solo:\n") }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals("an unsaved hosts.yml edit invalidates through the YAML PSI tracker", listOf("solo"), dev().hosts.keys.toList())

        WriteAction.runAndWait<Throwable> { vf("$LAYOUT/environments/staging").delete(this) }
        refreshRoots()
        assertEquals(listOf("dev"), inventories.inventories(root(LAYOUT)).map { it.environment })
    }

    private companion object {
        const val LAYOUT = "layout"
    }
}
