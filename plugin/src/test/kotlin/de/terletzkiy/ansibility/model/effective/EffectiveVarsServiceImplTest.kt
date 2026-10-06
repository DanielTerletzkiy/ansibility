package de.terletzkiy.ansibility.model.effective

import de.terletzkiy.ansibility.api.VarsLayer
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.model.inventory.InventoryTestCase

/** [EffectiveVarsServiceImpl] on sub-trees of the sanitised infra fixture (line numbers are the real repo's). */
@RequiresInfraFixture
class EffectiveVarsServiceImplTest : InventoryTestCase() {
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

    fun testPlaybookGroupVarsAllBeatInventoryGroupVarsAll() {
        val view = effective.inventoryView(root(FALCON), "prod", "prod-prod1", vf(FALCON))!!
        assertEquals("prod-prod1", view.host)
        assertEquals("prod", view.environment)
        val relayhost = view["postfix_relayhost"]!!
        assertEquals("$FALCON/group_vars/all/vars.yml:156", at(relayhost.winner))
        assertEquals(VarsLayer.PLAYBOOK_GROUP_VARS_ALL, relayhost.winner.layer)
        assertEquals("all", relayhost.winner.group)
        assertNull(relayhost.winner.host)
        assertEquals("relayinternal.mx.example.de", relayhost.winner.preview)
        assertFalse(relayhost.winner.isVault)

        val shadowed = relayhost.shadowed.single { at(it) == "$FALCON/environments/prod/group_vars/all/vars.yml:471" }
        assertEquals(VarsLayer.INVENTORY_GROUP_VARS_ALL, shadowed.layer)
        assertEquals("relay.mx.example.de", shadowed.preview)
        assertEquals(emptyList<Any>(), relayhost.mergedFrom)
    }

    fun testWithoutPlaybookDirTheEnvironmentValueWins() {
        for (playbookDir in listOf(null, vf("$FALCON/environments"))) {
            val relayhost = effective.inventoryView(root(FALCON), "prod", "prod-prod1", playbookDir)!!["postfix_relayhost"]!!
            assertEquals("playbookDir=$playbookDir", "$FALCON/environments/prod/group_vars/all/vars.yml:471", at(relayhost.winner))
            assertEquals(VarsLayer.INVENTORY_GROUP_VARS_ALL, relayhost.winner.layer)
            assertEquals(emptyList<Any>(), relayhost.shadowed)
        }
    }

    fun testInlineAndHostVarsLayers() {
        val view = effective.inventoryView(root(FALCON), "prod", "prod-prod1", vf(FALCON))!!
        val user = view["ansible_user"]!!.winner
        assertEquals(VarsLayer.INVENTORY_FILE_GROUP, user.layer)
        assertEquals("all", user.group)
        assertEquals("$FALCON/environments/prod/hosts.yml:4", at(user))
        val host = view["ansible_host"]!!.winner
        assertEquals(VarsLayer.INVENTORY_FILE_HOST, host.layer)
        assertEquals("prod-prod1", host.host)
        assertEquals("192.0.2.29", host.preview)
        val structure = view["inventory_docs_client_structure"]!!.winner
        assertEquals("app_mono", structure.group)
        assertEquals("$FALCON/environments/prod/hosts.yml:23", at(structure))
        assertTrue(view.vars.any { it.winner.layer == VarsLayer.INVENTORY_HOST_VARS && it.winner.host == "prod-prod1" })
        assertEquals("sorted by name", view.vars.map { it.name }.sorted(), view.vars.map { it.name })
    }

    fun testVaultValuesAreNeverPreviewed() {
        val view = effective.inventoryView(root(FALCON), "prod", "prod-prod1", vf(FALCON))!!
        val refs = view.vars.flatMap { entry -> (listOf(entry.winner) + entry.shadowed).map { it to entry.name } }
        assertTrue("the fixture has vault values", refs.count { it.first.isVault } > 10)
        assertTrue("plain values are previewed", refs.count { it.first.preview != null } > 100)
        for ((ref, name) in refs) {
            if (ref.isVault || name.startsWith("vault_") || ref.file.name.startsWith("vault")) {
                assertNull("$name at ${at(ref)} must not be previewed", ref.preview)
            }
        }
        val vaultRef = view["vault_alloy_tenant_api_key_build"]!!.winner
        assertTrue(vaultRef.isVault)
        assertEquals("$FALCON/group_vars/all/vault.yml:4", at(vaultRef))
    }

    fun testNestedPlaybookRootSharesTheParentInventory() {
        val nested = root(DANGER_ZONE)
        assertEquals(effective.hostsOf(root(PELICAN), "prod"), effective.hostsOf(nested, "prod"))
        val own = effective.inventoryView(nested, "prod", "prod-db1", nested.dir)!!
        assertTrue("danger_zone/database has no group_vars", own.vars.none { it.winner.layer.name.startsWith("PLAYBOOK_") })
        assertEquals(VarsLayer.INVENTORY_FILE_GROUP, own["ansible_user"]!!.winner.layer)
        assertEquals("$PELICAN/environments/prod/hosts.yml:4", at(own["ansible_user"]!!.winner))
        val parentDir = effective.inventoryView(nested, "prod", "prod-db1", vf(PELICAN))
        assertEquals("the parent's dir is in the family (imported plays)", own, parentDir)
    }

    fun testPlaybookDirOfAnotherRootIsIgnored() {
        val foreign = effective.inventoryView(root(PELICAN), "prod", "prod-prod1", vf(FALCON))!!
        val none = effective.inventoryView(root(PELICAN), "prod", "prod-prod1", null)!!
        assertEquals(none, foreign)
        assertTrue(foreign.vars.none { it.winner.file.path.contains("/falcon/") })
    }

    fun testPlatformPlaybookGroupVarsAndTemplatedAnsibleHost() {
        val view = effective.inventoryView(root(PLATFORM), "prod", "prod-training1", vf(PLATFORM))!!
        val host = view["ansible_host"]!!.winner
        assertEquals("flow scalars are previewed as written", "\"{{ host_ips['prod-training1'] }}\"", host.preview)
        val hostIps = view["host_ips"]!!.winner
        assertEquals(VarsLayer.PLAYBOOK_GROUP_VARS_ALL, hostIps.layer)
        assertEquals("$PLATFORM/group_vars/all.yml", rel(hostIps.file))
        assertTrue(view.vars.any { it.winner.layer == VarsLayer.INVENTORY_HOST_VARS && it.winner.file.parent.name == "prod-training1" })
    }

    fun testUnknownEnvironmentsHostsAndRoleLibraries() {
        assertNull(effective.inventoryView(root(FALCON), "nope", "prod-prod1", null))
        assertNull(effective.inventoryView(root(FALCON), "prod", "nobody", null))
        assertNull(effective.inventoryView(root("golden"), "prod", "prod-prod1", null))
    }

    fun testViewsAreCached() {
        val first = effective.inventoryView(root(FALCON), "prod", "prod-prod1", vf(FALCON))
        assertSame(first, effective.inventoryView(root(FALCON), "prod", "prod-prod1", vf(FALCON)))
        refreshRoots()
        val afterStructureChange = effective.inventoryView(root(FALCON), "prod", "prod-prod1", vf(FALCON))
        assertNotSame(first, afterStructureChange)
        assertEquals(first, afterStructureChange)
    }

    private companion object {
        const val FALCON = "repos/falcon/ansible"
        const val PLATFORM = "repos/platform/ansible"
        const val PELICAN = "repos/pelican/ansible"
        const val DANGER_ZONE = "repos/pelican/ansible/danger_zone/database"
    }
}
