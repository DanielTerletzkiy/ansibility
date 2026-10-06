package de.terletzkiy.ansibility.context.switching

import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext

/** What a root's context can switch to (F8.1 popup lists) and the play-keeping rules of a switch. */
@RequiresInfraFixture
class ContextChoicesTest : ContextSwitchingTestCase() {
    private val choices: ContextChoices get() = ContextChoices(project)

    fun testEnvironmentsWithTheirHostCounts() {
        assertEquals(
            listOf(EnvironmentOption("ops", 1), EnvironmentOption("prod", 2), EnvironmentOption("test", 7)),
            choices.environments(root("repos/falcon/ansible")),
        )
        assertEquals("a nested root lists its parent's environments", choices.environments(root("repos/pelican/ansible")), choices.environments(root(DANGER_ZONE)))
        assertEquals(emptyList<EnvironmentOption>(), choices.environments(root("golden")))
    }

    fun testHostsWithAddressesAndTheSharedAddressBadge() {
        val prod = choices.hosts(root("repos/falcon/ansible"), "prod")
        assertEquals(listOf("prod-prod1", "prod-prod2"), prod.map { it.host })
        assertEquals(HostOption("prod", "prod-prod1", "192.0.2.29", 1), prod.first())
        assertEquals("192.0.2.29", hostBadge(prod.first()))

        val test1 = choices.hosts(root("repos/falcon/ansible"), "test").first { it.host == "test-test1" }
        assertEquals(7, test1.sharedBy)
        assertEquals("1 of 7 names on 192.0.2.43", hostBadge(test1))

        val all = choices.hosts(root("repos/falcon/ansible"), null)
        assertEquals("every environment, in environment and inventory order", 10, all.size)
        assertEquals(listOf("ops", "prod", "test"), all.map { it.environment }.distinct())
    }

    fun testPlaysThatHitTheSelection() {
        val falcon = root("repos/falcon/ansible")
        val onProd1 = choices.plays(falcon, "prod", "prod-prod1").map { it.label }
        assertTrue(onProd1.toString(), "playbook-setup-system.yml › KeepAliveD" in onProd1)
        val onTest1 = choices.plays(falcon, "test", "test-test1").map { it.label }
        assertFalse("KeepAliveD runs only in prod: $onTest1", "playbook-setup-system.yml › KeepAliveD" in onTest1)
        assertTrue(onTest1.toString(), "playbook-setup-system.yml › System" in onTest1)
        val keepalived = choices.plays(falcon, "prod", null).single { it.label == "playbook-setup-system.yml › KeepAliveD" }
        assertEquals("playbook-setup-system.yml#4", keepalived.key)
        assertEquals("playbook-setup-system.yml › KeepAliveD", choices.playLabel(falcon, keepalived.key))
        assertNull(choices.playLabel(falcon, "gone.yml#0"))
    }

    fun testANestedRootListsItsOwnPlaysFirstWithKeysRelativeToItself() {
        val nested = root(DANGER_ZONE)
        val plays = choices.plays(nested, "prod", "prod-replisync1")
        val first = plays.first()
        assertTrue(first.label, first.label.startsWith("danger_zone/database/"))
        assertFalse("own keys are relative to the nested root: ${first.key}", first.key.startsWith("danger_zone"))
        val imported = plays.firstOrNull { it.label.startsWith("playbook-setup-replisync.yml") }
        assertNotNull("the parent's plays follow: ${plays.map { it.label }}", imported)
        assertTrue(imported!!.key, imported.key.startsWith("../../playbook-setup-replisync.yml#"))
    }

    fun testSwitchingKeepsThePlayOnlyWhileItStillHits() {
        val falcon = root("repos/falcon/ansible")
        val keepalived = playKey("repos/falcon/ansible", systemPlaybook, "KeepAliveD")
        val current = RootContext(EnvironmentChoice.Named("prod"), "prod-prod1", keepalived)
        assertEquals(RootContext(EnvironmentChoice.Named("prod"), "prod-prod2", keepalived), choices.withHost(falcon, current, "prod", "prod-prod2"))
        assertEquals("KeepAliveD does not run on test-test1", RootContext(EnvironmentChoice.Named("test"), "test-test1"), choices.withHost(falcon, current, "test", "test-test1"))
        assertEquals(RootContext(EnvironmentChoice.Named("prod"), null, keepalived), choices.withEnvironment(falcon, current, "prod"))
        assertEquals(RootContext(EnvironmentChoice.All, null, keepalived), choices.withEnvironment(falcon, current, null))
        assertEquals(RootContext(EnvironmentChoice.Named("ops")), choices.withEnvironment(falcon, current, "ops"))
        assertEquals(RootContext(EnvironmentChoice.Named("prod"), "prod-prod1"), choices.withPlay(current, null))
    }

    fun testSwitchableRootsPutThePreferredRootFirstAndSkipRootsWithoutInventory() {
        val roots = choices.switchableRoots(root("repos/platform/ansible")).map { it.displayName }
        assertEquals("platform", roots.first())
        assertTrue(roots.toString(), roots.containsAll(listOf("falcon", "pelican", "pelican › danger_zone/database")))
        assertFalse("golden has no inventory", "golden" in roots)
        assertEquals("falcon", choices.switchableRoots(null).first().displayName)
    }
}
