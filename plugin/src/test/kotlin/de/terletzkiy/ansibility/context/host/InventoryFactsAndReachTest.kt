package de.terletzkiy.ansibility.context.host

import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.semantics.yaml.YEntry
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YVault
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext

/** `inventoryFacts` (groups, `group_names`, addresses, shared addresses) and role reach on the infra fixture. */
@RequiresInfraFixture
class InventoryFactsAndReachTest : HostContextTestCase() {
    fun testGroupsAndGroupNamesPerEnvironment() {
        val facts = context.inventoryFacts(context.selectionScope(root(FALCON), RootContext(EnvironmentChoice.Named("prod"))))
        val prod = facts.environments.single()
        assertEquals("prod", prod.environment)
        assertEquals(listOf("prod-prod1", "prod-prod2"), prod.groups["keepalived"])
        assertEquals(listOf("prod-prod1"), prod.groups["database_primary"])
        assertEquals(listOf("prod-prod1", "prod-prod2"), prod.groups["all"])
        val prod1 = facts.host(HostKey(FALCON, "prod", "prod-prod1"))!!
        assertEquals(
            listOf("app_mono", "app_services", "database", "database_primary", "haproxy", "keepalived", "keycloak", "monitoring_client", "oauth2_proxy", "system"),
            prod1.groupNames,
        )
        assertEquals("192.0.2.29", prod1.address)
        assertNull(prod1.addressTemplate)
        assertEquals(emptyList<Any>(), prod1.sharesAddressWith)
    }

    fun testSharedAddresses() {
        val facts = context.inventoryFacts(context.selectionScope(root(FALCON), RootContext(EnvironmentChoice.Named("test"))))
        val test1 = facts.host(HostKey(FALCON, "test", "test-test1"))!!
        assertEquals("192.0.2.43", test1.address)
        assertEquals("6 other names on the same address", 6, test1.sharesAddressWith.size)
        assertTrue(test1.sharesAddressWith.all { it.environment == "test" && it.host.startsWith("preview-") })
    }

    fun testTemplatedAddressesAreEvaluatedWithThePlaybookDir() {
        val facts = context.inventoryFacts(context.selectionScope(root(PLATFORM), RootContext(EnvironmentChoice.Named("prod"))))
        val training = facts.host(HostKey(PLATFORM, "prod", "prod-training1"))!!
        assertEquals("host_ips lives in playbook-level group_vars/all.yml", "192.0.2.14", training.address)
        assertEquals("{{ host_ips['prod-training1'] }}", training.addressTemplate)
    }

    fun testFactsOfMoleculeScenarios() {
        val facts = context.inventoryFacts(context.hostScope(vf(POSTFIX_MOLECULE)))
        val scenario = facts.environments.single()
        assertEquals("molecule:postfix/default", scenario.environment)
        assertEquals(2, scenario.hosts.size)
        assertTrue(scenario.hosts.all { it.address == null && it.sharesAddressWith.isEmpty() })
        assertEquals(scenario.hosts.map { it.key.host }, scenario.groups["ungrouped"])
    }

    fun testSimpleTemplateEvaluation() {
        val map = YMap(listOf(YEntry(YScalar("a", ScalarStyle.PLAIN), YScalar("10.0.0.1", ScalarStyle.PLAIN))))
        val values = mapOf("host_ips" to map, "plain" to YScalar("1.2.3.4", ScalarStyle.PLAIN), "secret" to YVault(), "nested" to YScalar("{{ x }}", ScalarStyle.PLAIN))
        fun eval(text: String) = InventoryFactsBuilder.evaluateSimple(text) { values[it] }
        assertEquals("10.0.0.1", eval("{{ host_ips['a'] }}"))
        assertEquals("10.0.0.1", eval("{{ host_ips[\"a\"] }}"))
        assertEquals("10.0.0.1", eval("{{host_ips.a}}"))
        assertEquals("1.2.3.4", eval("{{ plain }}"))
        assertNull(eval("{{ host_ips['b'] }}"))
        assertNull(eval("{{ secret }}"))
        assertNull("nested templates are not followed", eval("{{ nested }}"))
        assertNull(eval("{{ plain | default('x') }}"))
        assertNull(eval("prefix-{{ plain }}"))
    }

    fun testRoleReachThroughPlaysAndPatterns() {
        val reach = context.reach(root(FALCON), "postfix")
        assertEquals(listOf("System"), reach.plays.map { it.name })
        assertEquals(listOf("ops/ops-ops1@System", "prod/prod-prod1@System", "prod/prod-prod2@System", "test/test-test1@System"), reach.targets.map(::label))
        assertFalse(reach.approximate)
        assertNull(reach.emptyReason)
        assertSame("cached", reach, context.reach(root(FALCON), "postfix"))

        val haproxy = context.reach(root(FALCON), "haproxy")
        assertEquals(listOf("prod/prod-prod1", "prod/prod-prod2"), haproxy.targets.map { label(it.host) }.distinct())
    }

    fun testTemplatedPatternsMakeReachApproximate() {
        add(
            "$FALCON/playbook-templated.yml",
            """
            ---
            - name: Templated
              hosts: "{{ target_hosts }}"
              roles:
                - role: postfix
            """,
        )
        refreshRoots()
        val reach = context.reach(root(FALCON), "postfix")
        assertTrue(reach.approximate)
        assertTrue(reach.plays.any { it.name == "Templated" })
        assertTrue("a templated pattern counts nowhere", reach.targets.none { it.play?.name == "Templated" })
    }
}
