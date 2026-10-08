package de.terletzkiy.ansibility.context.host

import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.api.HostScopeOrigin
import de.terletzkiy.ansibility.context.MoleculeNavigationFixture
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext

/** Per-file inference of the hosts a file applies to (plan amendment R7/R8, "Per-file inference" table). */
@RequiresInfraFixture
class HostScopeInferenceTest : HostContextTestCase() {
    override fun addFixtureFiles() {
        // pelican has no playbook-level group_vars in the fixture; one makes the danger-zone playbook dir visible.
        add("$PELICAN/group_vars/all.yml", "---\npelican_playbook_level: true")
    }

    fun testInventoryHostVarsApplyToTheirHostInEveryPlayThatHitsIt() {
        val scope = context.hostScope(vf("$FALCON/environments/prod/host_vars/prod-prod1/vars.yml"))
        assertEquals(HostScopeOrigin.HostVars(HostKey(FALCON, "prod", "prod-prod1")), scope.origin)
        assertEquals(listOf("prod/prod-prod1"), hosts(scope))
        val plays = scope.targets.mapNotNull { it.play?.name }
        assertTrue(plays.containsAll(listOf("System", "KeepAliveD", "Proxy")))
        assertTrue("plain plays with the same roles are one context", plays.count { it == "Ping all hosts serially" } <= 1)
        assertEquals(scope.targets, scope.targets.distinct())
        assertTrue(scope.targets.all { it.playbookDir == vf(FALCON) })
    }

    fun testGroupVarsApplyToTheGroupsHostsChildrenIncluded() {
        val contracting = context.hostScope(vf("$PLATFORM/environments/prod/group_vars/contracting/mysql_users.yml"))
        assertEquals(HostScopeOrigin.GroupVars("prod", "contracting"), contracting.origin)
        assertEquals("via the child group analytics", listOf("prod/prod-mlflow1", "prod/prod-training1"), hosts(contracting))

        val all = context.hostScope(vf(FALCON_PROD_ALL))
        assertEquals(HostScopeOrigin.GroupVars("prod", "all"), all.origin)
        assertEquals(listOf("prod/prod-prod1", "prod/prod-prod2"), hosts(all))

        val orphan = context.hostScope(vf("$PLATFORM/environments/prod/group_vars/app_platform.yml"))
        assertEquals(emptyList<Any>(), orphan.targets)
        assertEquals("app_platform is not a group of prod: this file is never loaded", orphan.emptyReason)
    }

    fun testPlaybookLevelGroupVarsApplyInEveryEnvironmentOnlyInPlaysOfTheirDirectory() {
        val falcon = context.hostScope(vf(FALCON_PLAYBOOK_ALL))
        assertEquals(HostScopeOrigin.GroupVars(null, "all"), falcon.origin)
        assertEquals(setOf("ops", "prod", "test"), falcon.hosts.map { it.environment }.toSet())

        val pelican = context.hostScope(vf("$PELICAN/group_vars/all.yml"))
        assertTrue(pelican.targets.isNotEmpty())
        assertTrue("a danger-zone play does not load ansible/group_vars", pelican.targets.all { it.playbookDir == vf(PELICAN) })
        val clone = context.hostScope(vf("$DANGER_ZONE/playbook-clone-to-replisync.yml"))
        val dirs = clone.targets.map { it.playbookDir }.toSet()
        assertEquals("own plays and the imported plays", setOf(vf(DANGER_ZONE), vf(PELICAN)), dirs)
        val own = clone.targets.first { it.playbookDir == vf(DANGER_ZONE) }
        val imported = clone.targets.first { it.playbookDir == vf(PELICAN) }
        assertNull(context.inventoryView(own)!!["pelican_playbook_level"])
        assertEquals("$PELICAN/group_vars/all.yml:2", at(context.inventoryView(imported)!!["pelican_playbook_level"]!!.winner))
    }

    fun testHostsYmlFollowsTheCaret() {
        val path = "$FALCON/environments/prod/hosts.yml"
        val group = context.hostScope(vf(path), offset(path, 47, 4))
        assertEquals(HostScopeOrigin.InventoryEntry("prod", "keepalived", null), group.origin)
        assertEquals(listOf("prod/prod-prod1", "prod/prod-prod2"), hosts(group))

        val host = context.hostScope(vf(path), offset(path, 7, 6))
        assertEquals(HostScopeOrigin.InventoryEntry("prod", null, "prod-prod1"), host.origin)
        assertEquals(listOf("prod/prod-prod1"), hosts(host))

        val file = context.hostScope(vf(path))
        assertEquals(HostScopeOrigin.InventoryEntry("prod", null, null), file.origin)
        assertEquals(listOf("prod/prod-prod1", "prod/prod-prod2"), hosts(file))

        val platform = "$PLATFORM/environments/prod/hosts.yml"
        val child = context.hostScope(vf(platform), offset(platform, 80, 6))
        assertEquals("the innermost children group", HostScopeOrigin.InventoryEntry("prod", "analytics", null), child.origin)
        assertEquals(listOf("prod/prod-mlflow1", "prod/prod-training1"), hosts(child))
        val nestedHost = context.hostScope(vf(platform), offset(platform, 82, 10))
        assertEquals(HostScopeOrigin.InventoryEntry("prod", null, "prod-mlflow1"), nestedHost.origin)
        val parent = context.hostScope(vf(platform), offset(platform, 78, 2))
        assertEquals(HostScopeOrigin.InventoryEntry("prod", "contracting", null), parent.origin)
        assertEquals(listOf("prod/prod-mlflow1", "prod/prod-training1"), hosts(parent))
    }

    fun testPlaybookCaretSelectsThePlay() {
        val path = "$FALCON/playbook-setup-system.yml"
        val keepalived = context.hostScope(vf(path), offsetOf(path, "hosts: keepalived"))
        assertEquals("KeepAliveD", (keepalived.origin as HostScopeOrigin.Play).play.name)
        assertEquals(listOf("prod/prod-prod1@KeepAliveD", "prod/prod-prod2@KeepAliveD"), targets(keepalived))

        val file = context.hostScope(vf(path))
        assertEquals(setOf("ops", "prod", "test"), file.hosts.map { it.environment }.toSet())
        assertTrue(file.targets.any { it.play?.name == "KeepAliveD" })
        assertTrue(file.targets.any { it.play?.name == "System" && it.host.environment == "ops" })
    }

    fun testRoleFilesApplyWhereTheRoleRuns() {
        val defaults = context.hostScope(vf(POSTFIX_DEFAULTS))
        assertEquals("postfix", (defaults.origin as HostScopeOrigin.RoleReach).role)
        assertEquals(listOf("ops/ops-ops1", "prod/prod-prod1", "prod/prod-prod2", "test/test-test1"), hosts(defaults))
        assertEquals("role files and templates agree", hosts(defaults), hosts(context.hostScope(vf(POSTFIX_TEMPLATE))))
        assertEquals("postfix", impl.runningRole(defaults))
    }

    fun testMoleculeFilesUseTheirScenario() {
        val scope = context.hostScope(vf(POSTFIX_MOLECULE))
        assertEquals(HostScopeOrigin.Molecule(vf(POSTFIX_MOLECULE).parent), scope.origin)
        assertEquals(
            listOf("molecule:postfix/default/postfix_deb13-\${MOLECULE_RUN_ID:-local}", "molecule:postfix/default/postfix_deb12-\${MOLECULE_RUN_ID:-local}"),
            hosts(scope),
        )
        assertTrue(scope.targets.all { it.play?.name == "Converge" })
        assertEquals("postfix", impl.runningRole(scope))
        val group = context.effective(scope, "postfix_relayhost").molecule.single()
        assertEquals("$POSTFIX_MOLECULE:56", at(group.winner!!))
    }

    fun testGoldenRolesUseTheirMoleculeScenarios() {
        // R20/D156: in cards and the status bar only while "Show Molecule in navigation and search" is on; elsewhere
        // (Template Preview, banners, Show Ansible Context) and in inspections always.
        val hidden = (context as AnsibleContextServiceImpl).cardScope(vf("golden/roles/postfix/defaults/main.yml"))
        assertEquals(emptyList<Any>(), hidden.targets)
        assertEquals("golden has no inventory", hidden.emptyReason)
        assertTrue(context.allHostsScope(vf("golden/roles/postfix/defaults/main.yml")).hosts.let { it.isNotEmpty() && it.all { host -> host.isMolecule } })
        val kept = context.hostScope(vf("golden/roles/postfix/defaults/main.yml"))
        assertTrue("hostScope keeps them with the setting off", kept.hosts.isNotEmpty() && kept.hosts.all { it.isMolecule })
        MoleculeNavigationFixture.showInNavigationUntil(project, testRootDisposable)
        assertEquals(kept.targets, (context as AnsibleContextServiceImpl).cardScope(vf("golden/roles/postfix/defaults/main.yml")).targets)
        val scope = context.hostScope(vf("golden/roles/postfix/defaults/main.yml"))
        assertTrue(scope.targets.isNotEmpty())
        assertTrue("golden has no inventory: scenarios are its only contexts", scope.hosts.all { it.isMolecule })
        assertEquals(setOf("molecule:postfix/default", "molecule:postfix/disabled-inbound-email-processing"), scope.hosts.map { it.environment }.toSet())
        assertEquals("golden", scope.hosts.first().root)
    }

    fun testOtherFilesFollowTheSelection() {
        val cfg = context.hostScope(vf("$FALCON/ansible.cfg"))
        assertEquals(HostScopeOrigin.RootWide, cfg.origin)
        assertEquals(10, cfg.hosts.size)
        assertTrue("inventory-only contexts with the root as playbook dir", cfg.targets.all { it.play == null && it.playbookDir == vf(FALCON) })
        context.setSelection(root(FALCON), RootContext(EnvironmentChoice.Named("test"), "test-test1"))
        assertEquals(listOf("test/test-test1"), hosts(context.hostScope(vf("$FALCON/ansible.cfg"))))
    }

    fun testDisjointSelectionIsOverriddenByTheFileScope() {
        context.setSelection(root(FALCON), RootContext(EnvironmentChoice.Named("test"), "test-test1"))
        val hostVars = context.hostScope(vf("$FALCON/environments/prod/host_vars/prod-prod1/vars.yml"))
        assertTrue(hostVars.overriddenSelection)
        assertEquals(listOf("prod/prod-prod1"), hosts(hostVars))
        assertEquals(RootContext(EnvironmentChoice.Named("test"), "test-test1"), hostVars.selection)

        val template = context.hostScope(vf(POSTFIX_TEMPLATE))
        assertFalse(template.overriddenSelection)
        assertEquals(listOf("test/test-test1"), hosts(template))
    }

    fun testAllHostsScopeIgnoresTheSelection() {
        val all = context.allHostsScope(vf(POSTFIX_TEMPLATE))
        context.setSelection(root(FALCON), RootContext(EnvironmentChoice.Named("prod"), "prod-prod1"))
        assertEquals(all.targets, context.allHostsScope(vf(POSTFIX_TEMPLATE)).targets)
        assertEquals(RootContext.DEFAULT, context.allHostsScope(vf(POSTFIX_TEMPLATE)).selection)
        assertEquals(listOf("prod/prod-prod1"), hosts(context.hostScope(vf(POSTFIX_TEMPLATE))))
    }

    fun testFilesOutsideRootsHaveNoScope() {
        val readme = add("README.md", "# not ansible")
        try {
            context.hostScope(readme)
            fail("a file outside every Ansible root has no host scope")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("README.md"))
        }
    }
}
