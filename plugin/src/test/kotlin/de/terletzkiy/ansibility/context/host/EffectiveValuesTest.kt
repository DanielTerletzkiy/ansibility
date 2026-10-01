package de.terletzkiy.ansibility.context.host

import de.terletzkiy.ansibility.api.HostScopeOrigin
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.api.VarsLayer
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext

/**
 * M4.7 acceptance 2–4 at model level on the infra fixture: effective values per host, "Explain precedence" chains and
 * "This definition" statuses. The fixture has no `build` environment and no `keepalived` role; the role is added here
 * with the real repo's `defaults/main.yml:2` (`keepalived_priority: 100`).
 */
class EffectiveValuesTest : HostContextTestCase() {
    override fun addFixtureFiles() {
        add(KEEPALIVED_DEFAULTS, "---\nkeepalived_priority: 100\nkeepalived_is_master: false")
        add(
            "$FALCON/roles/keepalived/tasks/main.yml",
            """
            ---
            - name: Configure keepalived
              ansible.builtin.template:
                src: keepalived.conf.j2
                dest: /etc/keepalived/keepalived.conf
            """,
        )
        add(KEEPALIVED_TEMPLATE, "vrrp_instance VI_1 {\n    priority {{ keepalived_priority }}\n}")
    }

    /** Acceptance 2: `{{ postfix_relayhost }}` in `main.cf.j2` is won by playbook `group_vars/all/vars.yml:156` on every System host. */
    fun testPostfixRelayhostIsWonByPlaybookGroupVarsOnEverySystemHost() {
        val scope = context.hostScope(vf(POSTFIX_TEMPLATE), offsetOf(POSTFIX_TEMPLATE, "postfix_relayhost"))
        val origin = scope.origin as HostScopeOrigin.RoleReach
        assertEquals("postfix", origin.role)
        assertEquals(listOf("System"), origin.plays.map { it.name })
        assertEquals(listOf("ops/ops-ops1", "prod/prod-prod1", "prod/prod-prod2", "test/test-test1"), hosts(scope))

        val breakdown = context.effective(scope, "postfix_relayhost")
        val group = breakdown.groups.single()
        val winner = group.winner!!
        assertEquals("$FALCON_PLAYBOOK_ALL:156", at(winner))
        assertEquals(VarsLayer.PLAYBOOK_GROUP_VARS_ALL, winner.layer)
        assertEquals("relayinternal.mx.example.de", winner.preview)
        assertEquals(hosts(scope), labels(group))
        assertEquals(listOf("System"), group.plays.map { it.name })
        assertEquals(
            setOf("$FALCON_PROD_ALL:471", "$FALCON_TEST_ALL:323", "$POSTFIX_DEFAULTS:2"),
            group.shadowed.map { at(it) }.toSet(),
        )
        val defaults = group.shadowed.single { it.layer == VarsLayer.ROLE_DEFAULTS }
        assertEquals("postfix", defaults.role)
        assertEquals("System", defaults.play?.name)
        assertEquals(emptyList<Any>(), breakdown.undefinedOn)

        val molecule = breakdown.molecule.single()
        val moleculeWinner = molecule.winner!!
        assertEquals("$POSTFIX_MOLECULE:56", at(moleculeWinner))
        assertEquals(VarsLayer.MOLECULE_INVENTORY, moleculeWinner.layer)
        assertTrue(molecule.hosts.all { it.isMolecule && it.environment == "molecule:postfix/default" })
        assertEquals(listOf("$POSTFIX_DEFAULTS:2"), molecule.shadowed.map { at(it) })
    }

    fun testWithEnvProdTheBreakdownIsLimitedToProdHosts() {
        context.setSelection(root(FALCON), RootContext(EnvironmentChoice.Named("prod")))
        val scope = context.hostScope(vf(POSTFIX_TEMPLATE), offsetOf(POSTFIX_TEMPLATE, "postfix_relayhost"))
        assertFalse(scope.overriddenSelection)
        assertEquals(listOf("prod/prod-prod1", "prod/prod-prod2"), hosts(scope))
        assertEquals(listOf("ops/ops-ops1", "prod/prod-prod1", "prod/prod-prod2", "test/test-test1"), scope.fileHosts.map(::label))
        val breakdown = context.effective(scope, "postfix_relayhost")
        assertEquals(listOf("prod/prod-prod1", "prod/prod-prod2"), labels(breakdown.groups.single()))
        assertEquals(setOf("$FALCON_PROD_ALL:471", "$POSTFIX_DEFAULTS:2"), breakdown.groups.single().shadowed.map { at(it) }.toSet())
        assertEquals("molecule outcomes only while no environment is selected", emptyList<Any>(), breakdown.molecule)
    }

    /** Acceptance 2, host prod-prod1: Explain precedence shows L2 → L4 (shadowed) → L5 (winner). */
    fun testExplainPrecedenceForProdProd1() {
        context.setSelection(root(FALCON), RootContext(EnvironmentChoice.Named("prod"), "prod-prod1"))
        val scope = context.hostScope(vf(POSTFIX_TEMPLATE))
        val target = scope.targets.single()
        assertEquals("prod/prod-prod1@System", label(target))
        val chain = impl.explain(target, "postfix_relayhost", runningRole = "postfix")
        assertEquals("postfix", chain.runningRole)
        assertEquals(
            listOf(
                "$POSTFIX_DEFAULTS:2 ROLE_DEFAULTS SHADOWED",
                "$FALCON_PROD_ALL:471 INVENTORY_GROUP_VARS_ALL SHADOWED",
                "$FALCON_PLAYBOOK_ALL:156 PLAYBOOK_GROUP_VARS_ALL WINNER",
            ),
            chain.steps.map { "${at(it.source)} ${it.source.layer} ${it.outcome}" },
        )
        assertEquals("$FALCON_PLAYBOOK_ALL:156", at(chain.winner!!.source))
        assertEquals(emptyList<Any>(), chain.runtimeMarkers)
        assertEquals(emptyList<Any>(), chain.unknownSources)

        val playLevel = context.explain(target, "postfix_relayhost")
        assertNull("the interface explains a play-level task", playLevel.runningRole)
        assertEquals(chain.steps, playLevel.steps)
    }

    /** Acceptance 3: `{{ keepalived_priority }}` is 150 on prod-prod1 (host_vars) and 100 on prod-prod2 (defaults). */
    fun testKeepalivedPriorityDiffersPerHost() {
        val scope = context.hostScope(vf(KEEPALIVED_TEMPLATE), offsetOf(KEEPALIVED_TEMPLATE, "keepalived_priority"))
        assertEquals(listOf("prod/prod-prod1", "prod/prod-prod2"), hosts(scope))
        assertEquals(listOf("KeepAliveD"), (scope.origin as HostScopeOrigin.RoleReach).plays.map { it.name })
        val breakdown = context.effective(scope, "keepalived_priority")
        assertEquals(
            listOf("$FALCON/environments/prod/host_vars/prod-prod1/vars.yml:16 [prod/prod-prod1]", "$KEEPALIVED_DEFAULTS:2 [prod/prod-prod2]"),
            breakdown.groups.map { "${at(it.winner!!)} ${labels(it)}" },
        )
        assertEquals("150", breakdown.groups[0].winner!!.preview)
        assertEquals(VarsLayer.INVENTORY_HOST_VARS, breakdown.groups[0].winner!!.layer)
        assertEquals(listOf("$KEEPALIVED_DEFAULTS:2"), breakdown.groups[0].shadowed.map { at(it) })
        assertEquals("100", breakdown.groups[1].winner!!.preview)
        assertEquals(VarsLayer.ROLE_DEFAULTS, breakdown.groups[1].winner!!.layer)

        val reach = context.reach(root(FALCON), "keepalived")
        assertEquals(setOf("prod"), reach.targets.map { it.host.environment }.toSet())
        assertNull(reach.emptyReason)
    }

    /** Acceptance 4: platform `database.yml:22` wins on three prod hosts and is shadowed on prod-training1 by `mysql.yml:63`. */
    fun testDefinitionStatusOfPlatformDatabaseBufferPoolSize() {
        val path = "$PLATFORM/environments/prod/group_vars/database.yml"
        val status = context.definitionStatus(definition(root(PLATFORM), "percona_config_innodb_buffer_pool_size", path, 22))
        assertEquals(listOf("prod/prod-platform1", "prod/prod-mlflow1", "prod/prod-alias1"), status.winsOn.map(::label))
        assertEquals(listOf("prod/prod-training1"), status.shadowedOn.keys.map(::label))
        val winner = status.shadowedOn.values.single()
        assertEquals("$PLATFORM/environments/prod/host_vars/prod-training1/mysql.yml:63", at(winner))
        assertEquals(VarsLayer.INVENTORY_HOST_VARS, winner.layer)
        assertEquals("4G", winner.preview)
        assertNull(status.notLoadedReason)
    }

    /** Acceptance 4: falcon prod `group_vars/all/vars.yml:471` is ineffective (shadowed by L5 on both prod hosts). */
    fun testFalconProdRelayhostIsIneffective() {
        val status = context.definitionStatus(definition(root(FALCON), "postfix_relayhost", FALCON_PROD_ALL, 471))
        assertEquals(emptyList<Any>(), status.winsOn)
        assertEquals(listOf("prod/prod-prod1", "prod/prod-prod2"), status.shadowedOn.keys.map(::label))
        assertTrue(status.shadowedOn.values.all { at(it) == "$FALCON_PLAYBOOK_ALL:156" })
        assertNull(status.notLoadedReason)

        val winner = context.definitionStatus(definition(root(FALCON), "postfix_relayhost", FALCON_PLAYBOOK_ALL, 156))
        val wins = winner.winsOn.map(::label)
        assertTrue(wins.containsAll(listOf("ops/ops-ops1", "prod/prod-prod1", "prod/prod-prod2", "test/test-test1")))
        assertEquals("playbook group_vars/all apply to every host of every environment", 10, wins.distinct().size)
        assertEquals(emptyMap<Any, Any>(), winner.shadowedOn)
    }

    fun testRoleDefaultsStatusIncludesMoleculeHosts() {
        val status = context.definitionStatus(definition(root(FALCON), "postfix_relayhost", POSTFIX_DEFAULTS, 2))
        assertEquals(emptyList<Any>(), status.winsOn)
        assertEquals(
            listOf("ops/ops-ops1", "prod/prod-prod1", "prod/prod-prod2", "test/test-test1"),
            status.shadowedOn.keys.filter { !it.isMolecule }.map(::label),
        )
        assertTrue("molecule hosts shadow the default too", status.shadowedOn.keys.any { it.isMolecule })
        assertEquals("$POSTFIX_MOLECULE:56", at(status.shadowedOn.entries.first { it.key.isMolecule }.value))
    }

    fun testMoleculeAndInlineInventoryDefinitions() {
        val molecule = context.definitionStatus(definition(root(FALCON), "postfix_relayhost", POSTFIX_MOLECULE, 56))
        assertEquals(2, molecule.winsOn.size)
        assertTrue(molecule.winsOn.all { it.environment == "molecule:postfix/default" })

        val hostsFile = "$FALCON/environments/prod/hosts.yml"
        val user = context.definitionStatus(definition(root(FALCON), "ansible_user", hostsFile, 4))
        assertEquals("all.vars apply to every host of the environment", listOf("prod/prod-prod1", "prod/prod-prod2"), user.winsOn.map(::label))
        val address = context.definitionStatus(definition(root(FALCON), "ansible_host", hostsFile, 8))
        assertEquals("a host entry applies to its host only", listOf("prod/prod-prod1"), address.winsOn.map(::label))
    }

    fun testUndefinedHostsAndUnreachedRoles() {
        val scope = context.hostScope(vf(POSTFIX_TEMPLATE))
        val breakdown = context.effective(scope, "keepalived_priority")
        assertEquals(listOf("prod/prod-prod1"), breakdown.groups.flatMap { labels(it) })
        assertEquals(listOf("ops/ops-ops1", "prod/prod-prod2", "test/test-test1"), breakdown.undefinedOn.map(::label))

        val unreached = context.reach(root(FALCON), "totp-token")
        assertEquals(emptyList<Any>(), unreached.targets)
        assertEquals("No play of falcon applies totp-token", unreached.emptyReason)
        val totp = context.hostScope(vf("$FALCON/roles/totp-token/tasks/main.yml"))
        assertEquals(emptyList<Any>(), totp.targets)
        assertEquals("No play of falcon applies totp-token", totp.emptyReason)
    }

    fun testSpecOptionsAndRuntimeDefinitionsHaveNoHostStatus() {
        val spec = VarService.getInstance(project).symbol(root(FALCON), "postfix_relayhost").definitions.first { it.kind == VarDefKind.SPEC_OPTION }
        val status = context.definitionStatus(spec)
        assertEquals(emptyList<Any>(), status.winsOn)
        assertEquals("A declaration, not a value", status.notLoadedReason)
    }

    fun testExecutionViewAddsRoleDefaultsToTheInventoryView() {
        context.setSelection(root(FALCON), RootContext(EnvironmentChoice.Named("prod"), "prod-prod2"))
        val target = context.hostScope(vf(KEEPALIVED_TEMPLATE)).targets.single()
        val inventory = context.inventoryView(target)!!
        assertNull("role defaults are no inventory variable", inventory["keepalived_priority"])
        assertEquals(effective.inventoryView(root(FALCON), "prod", "prod-prod2", vf(FALCON)), inventory)
        val execution = context.executionView(target, "keepalived")!!
        assertEquals("$KEEPALIVED_DEFAULTS:2", at(execution["keepalived_priority"]!!.winner))
        assertEquals("keepalived", execution["keepalived_priority"]!!.winner.role)
        assertEquals("$FALCON_PLAYBOOK_ALL:156", at(execution["postfix_relayhost"]!!.winner))
        assertNull("no play, no execution view", context.executionView(target.copy(play = null), "keepalived"))
    }

    private companion object {
        const val KEEPALIVED_DEFAULTS = "$FALCON/roles/keepalived/defaults/main.yml"
        const val KEEPALIVED_TEMPLATE = "$FALCON/roles/keepalived/templates/keepalived.conf.j2"
    }
}
