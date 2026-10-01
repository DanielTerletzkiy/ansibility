package de.terletzkiy.ansibility.model.inventory

import de.terletzkiy.ansibility.api.EffectiveVarsService
import de.terletzkiy.ansibility.api.InventoryService
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.api.VarFile
import de.terletzkiy.ansibility.api.VarsLayer
import de.terletzkiy.ansibility.model.effective.EffectiveVarsServiceImpl

/** [InventoryServiceImpl] on sub-trees of the sanitised infra fixture (line numbers are the real repo's). */
class InventoryServiceImplTest : InventoryTestCase() {
    override fun setUp() {
        super.setUp()
        for (repo in listOf(FALCON, PLATFORM)) {
            copyInfraFile("$repo/ansible.cfg")
            copyInfraTree("$repo/environments")
            copyInfraTree("$repo/group_vars")
        }
        copyInfraFile("$PELICAN/ansible.cfg")
        copyInfraTree("$PELICAN/environments")
        copyInfraTree(DANGER_ZONE)
        copyInfraTree("golden/roles/haproxy")
        refreshRoots()
    }

    private fun VarFile.describe(): String = "${rel(file)} ${layer.name} env=$environment group=$group host=$host"

    fun testServicesAreRegistered() {
        assertInstanceOf(InventoryService.getInstance(project), InventoryServiceImpl::class.java)
        assertInstanceOf(EffectiveVarsService.getInstance(project), EffectiveVarsServiceImpl::class.java)
    }

    fun testFalconEnvironments() {
        val falcon = inventories.inventories(root(FALCON))
        assertEquals(listOf("ops", "prod", "test"), falcon.map { it.environment })
        val prod = falcon.single { it.environment == "prod" }
        assertEquals(vf(FALCON), prod.rootDir)
        assertEquals("$FALCON/environments/prod/hosts.yml", rel(prod.hostsFile))
        assertEquals(
            listOf(
                "all", "ungrouped", "database", "database_primary", "app_mono", "system", "app_services", "keycloak",
                "oauth2_proxy", "haproxy", "keepalived", "monitoring_client",
            ),
            prod.groups.keys.toList(),
        )
        assertEquals(listOf("prod-prod1", "prod-prod2"), prod.hosts.keys.toList())
    }

    fun testGroupsAndHostsOfFalconProd() {
        val prod = inventories.inventories(root(FALCON)).single { it.environment == "prod" }
        val all = prod.groups.getValue("all")
        assertEquals(listOf("ansible_user", "ansible_port"), all.inlineVarKeys)
        assertEquals(0, all.depth)
        assertEquals("$FALCON/environments/prod/hosts.yml:2", at(all.location))
        assertTrue(all.children.containsAll(listOf("ungrouped", "database", "app_mono", "monitoring_client")))

        val appMono = prod.groups.getValue("app_mono")
        assertEquals(listOf("inventory_docs_client_structure"), appMono.inlineVarKeys)
        assertEquals(listOf("prod-prod1", "prod-prod2"), appMono.hosts)
        assertEquals(listOf("all"), appMono.parents)
        assertEquals(1, appMono.depth)
        assertEquals(1, appMono.priority)
        assertEquals("$FALCON/environments/prod/hosts.yml:18", at(appMono.location))
        assertEquals("$FALCON/environments/prod/hosts.yml:12", at(prod.groups.getValue("database").location))
        assertNull("ungrouped is implicit", prod.groups.getValue("ungrouped").location)

        val host = prod.hosts.getValue("prod-prod1")
        assertEquals("192.0.2.29", host.ansibleHost)
        assertEquals("the all.hosts entry, not a group's", "$FALCON/environments/prod/hosts.yml:7", at(host.location))
        assertEquals("all", host.groups.first())
        assertEquals(
            "all, then sort_groups (depth, priority, name)",
            listOf(
                "all", "app_mono", "app_services", "database", "database_primary", "haproxy", "keepalived", "keycloak",
                "monitoring_client", "oauth2_proxy", "system",
            ),
            host.groups,
        )
    }

    fun testInventoryVarFilesOfFalconProdInLoadOrder() {
        val prod = inventories.inventories(root(FALCON)).single { it.environment == "prod" }
        val env = "$FALCON/environments/prod"
        assertEquals(
            listOf(
                "$env/group_vars/all/vars.yml INVENTORY_GROUP_VARS_ALL env=prod group=all host=null",
                "$env/group_vars/all/vault.yml INVENTORY_GROUP_VARS_ALL env=prod group=all host=null",
                "$env/group_vars/keycloak/vars.yml INVENTORY_GROUP_VARS env=prod group=keycloak host=null",
                "$env/group_vars/keycloak/vault.yml INVENTORY_GROUP_VARS env=prod group=keycloak host=null",
                "$env/host_vars/prod-prod1/vars.yml INVENTORY_HOST_VARS env=prod group=null host=prod-prod1",
                "$env/host_vars/prod-prod1/vault.yml INVENTORY_HOST_VARS env=prod group=null host=prod-prod1",
                "$env/host_vars/prod-prod2/vars.yml INVENTORY_HOST_VARS env=prod group=null host=prod-prod2",
                "$env/host_vars/prod-prod2/vault.yml INVENTORY_HOST_VARS env=prod group=null host=prod-prod2",
            ),
            prod.varFiles.map { it.describe() },
        )
    }

    fun testPlaybookVarFiles() {
        assertEquals(
            listOf(
                "$FALCON/group_vars/all/vars.yml PLAYBOOK_GROUP_VARS_ALL env=null group=all host=null",
                "$FALCON/group_vars/all/vault.yml PLAYBOOK_GROUP_VARS_ALL env=null group=all host=null",
            ),
            inventories.playbookVarFiles(root(FALCON)).map { it.describe() },
        )
        assertEquals(
            listOf("all.yml" to VarsLayer.PLAYBOOK_GROUP_VARS_ALL) +
                listOf("app_alias.yml", "chronod.yml", "monitoring_client.yml", "system.yml").map { it to VarsLayer.PLAYBOOK_GROUP_VARS },
            inventories.playbookVarFiles(root(PLATFORM)).map { it.file.name to it.layer },
        )
    }

    fun testPlatformProdContractingAndAnalytics() {
        val prod = inventories.inventories(root(PLATFORM)).single { it.environment == "prod" }
        val contracting = prod.groups.getValue("contracting")
        assertEquals(listOf("analytics"), contracting.children)
        assertEquals("hosts: is entirely commented out", emptyList<String>(), contracting.hosts)
        assertEquals(1, contracting.depth)
        assertEquals("$PLATFORM/environments/prod/hosts.yml:78", at(contracting.location))

        val analytics = prod.groups.getValue("analytics")
        assertEquals(listOf("contracting"), analytics.parents)
        assertEquals(listOf("prod-mlflow1", "prod-training1"), analytics.hosts)
        assertEquals(2, analytics.depth)
        assertEquals("$PLATFORM/environments/prod/hosts.yml:80", at(analytics.location))

        val training = prod.hosts.getValue("prod-training1")
        assertEquals("templated ansible_host stays a template", "{{ host_ips['prod-training1'] }}", training.ansibleHost)
        assertTrue(training.groups.containsAll(listOf("contracting", "analytics")))
        assertTrue("a parent group sorts before its child", training.groups.indexOf("contracting") < training.groups.indexOf("analytics"))

        assertEquals(
            listOf("mysql_users.yml", "vault.yml"),
            prod.varFiles.filter { it.group == "contracting" }.map { it.file.name },
        )
        assertTrue(prod.varFiles.filter { it.group == "contracting" }.all { it.layer == VarsLayer.INVENTORY_GROUP_VARS })
    }

    fun testHostVarsOfProdTraining1InLoadOrder() {
        val prod = inventories.inventories(root(PLATFORM)).single { it.environment == "prod" }
        val files = prod.varFiles.filter { it.host == "prod-training1" }
        assertEquals(listOf("chronod.yml", "mysql.yml", "users.yml", "vars.yml", "vault.yml"), files.map { it.file.name })
        assertTrue(files.all { it.layer == VarsLayer.INVENTORY_HOST_VARS && it.environment == "prod" })
    }

    fun testPlatformGroupVarsMixFilesAndDirectoriesAndKeepOrphansLast() {
        val prod = inventories.inventories(root(PLATFORM)).single { it.environment == "prod" }
        val groupFiles = prod.varFiles.filter { it.group != null }
        assertEquals("all.yml", groupFiles.first().file.name)
        assertEquals(VarsLayer.INVENTORY_GROUP_VARS_ALL, groupFiles.first().layer)
        assertEquals(listOf("vars.yml", "vault.yml"), groupFiles.filter { it.group == "app_alias" }.map { it.file.name })
        assertFalse("app_platform is not a group of platform prod", "app_platform" in prod.groups)
        assertEquals("the orphan comes last", "app_platform.yml", groupFiles.last().file.name)
        assertEquals("app_platform", groupFiles.last().group)
    }

    fun testNestedPlaybookRootSharesItsParentsInventories() {
        val nested = root(DANGER_ZONE)
        assertEquals(RootKind.NESTED_PLAYBOOK, nested.kind)
        val shared = inventories.inventories(nested)
        assertEquals(listOf("prod"), shared.map { it.environment })
        assertEquals(inventories.inventories(root(PELICAN)), shared)
        assertEquals("the inventory belongs to the parent root", vf(PELICAN), shared.single().rootDir)
        assertEquals(
            listOf("prod-prod1", "prod-prod2", "prod-db1", "prod-db2", "prod-replisync1"),
            shared.single().hosts.keys.toList(),
        )
        assertEquals("plays defined in danger_zone/database load no playbook group_vars", emptyList<VarFile>(), inventories.playbookVarFiles(nested))
    }

    fun testRoleLibraryHasNoInventoriesButMoleculeScenarios() {
        val golden = root("golden")
        assertEquals(RootKind.ROLE_LIBRARY, golden.kind)
        assertEquals(emptyList<Any>(), inventories.inventories(golden))

        val molecule = inventories.moleculeInventories(golden).single()
        assertEquals("haproxy", molecule.roleName)
        assertEquals("golden/roles/haproxy/molecule/default/molecule.yml", rel(molecule.configFile))
        assertEquals("default", molecule.inventory.environment)
        assertEquals(
            listOf("haproxy_deb13-\${MOLECULE_RUN_ID:-local}", "haproxy_deb12-\${MOLECULE_RUN_ID:-local}"),
            molecule.inventory.hosts.keys.toList(),
        )
        assertEquals("platforms without groups are ungrouped", listOf("all", "ungrouped"), molecule.inventory.hosts.values.first().groups)
        val inline = molecule.inlineVars.map { "${it.name} group=${it.group} host=${it.host} ${at(it.location)}" }
        assertTrue(inline.toString(), "system_hostname group=all host=null golden/roles/haproxy/molecule/default/molecule.yml:55" in inline)
        assertTrue(
            inline.toString(),
            "system_apt_debian_version group=null host=haproxy_deb12-\${MOLECULE_RUN_ID:-local} golden/roles/haproxy/molecule/default/molecule.yml:66" in inline,
        )
        assertTrue(molecule.inlineVars.all { it.layer == VarsLayer.MOLECULE_INVENTORY })
    }

    fun testUnknownRootsAndEnvironmentsAreEmpty() {
        assertEquals(emptyList<String>(), effective.hostsOf(root(FALCON), "nope"))
        assertEquals(listOf("prod-prod1", "prod-prod2"), effective.hostsOf(root(FALCON), "prod"))
        assertEquals(emptyList<String>(), effective.hostsOf(root("golden"), "prod"))
    }

    private companion object {
        const val FALCON = "repos/falcon/ansible"
        const val PLATFORM = "repos/platform/ansible"
        const val PELICAN = "repos/pelican/ansible"
        const val DANGER_ZONE = "repos/pelican/ansible/danger_zone/database"
    }
}
