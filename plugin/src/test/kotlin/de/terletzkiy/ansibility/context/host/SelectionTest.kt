package de.terletzkiy.ansibility.context.host

import de.terletzkiy.ansibility.api.HostScopeOrigin
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.api.PlayRef
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.settings.AnsibilityWorkspaceState
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext
import de.terletzkiy.ansibility.settings.SettingsTestSupport

/** The selection (root → env | All, host | All, play | Auto): rules, persistence, nested roots, stale parts, trackers. */
@RequiresInfraFixture
class SelectionTest : HostContextTestCase() {
    private fun play(path: String, name: String): PlayRef =
        PlayGraph.getInstance(project).playsOf(vf(path)).single { it.ref.name == name }.ref

    fun testTheDefaultIsAllAllAuto() {
        assertEquals(RootContext.DEFAULT, context.selection(root(FALCON)))
        val scope = context.selectionScope(root(FALCON))
        assertEquals(HostScopeOrigin.Selection, scope.origin)
        assertEquals(10, scope.hosts.size)
        assertNull(scope.emptyReason)
    }

    fun testAHostChosenUnderAllGetsItsEnvironment() {
        context.setSelection(root(FALCON), RootContext(EnvironmentChoice.All, "prod-prod1"))
        assertEquals(RootContext(EnvironmentChoice.Named("prod"), "prod-prod1"), context.selection(root(FALCON)))
        context.setSelection(root(FALCON), RootContext(EnvironmentChoice.All, "nobody", play = " "))
        assertEquals("an unknown host cannot be placed: dropped", RootContext.DEFAULT, context.selection(root(FALCON)))
    }

    fun testTheSelectionSurvivesARestart() {
        val keepalived = PlayKeys.of(root(FALCON), play("$FALCON/playbook-setup-system.yml", "KeepAliveD"))
        assertEquals("playbook-setup-system.yml#4", keepalived)
        context.setSelection(root(FALCON), RootContext(EnvironmentChoice.Named("prod"), "prod-prod1", keepalived))
        val (xml, bean) = SettingsTestSupport.xmlRoundTrip(AnsibilityWorkspaceState.getInstance(project).state, AnsibilityWorkspaceState.StateBean())
        assertTrue(xml, xml.contains("host=\"prod-prod1\"") && xml.contains("play=\"playbook-setup-system.yml#4\""))
        AnsibilityWorkspaceState.getInstance(project).loadState(AnsibilityWorkspaceState.StateBean())
        assertEquals(RootContext.DEFAULT, context.selection(root(FALCON)))
        AnsibilityWorkspaceState.getInstance(project).loadState(bean)
        assertEquals(RootContext(EnvironmentChoice.Named("prod"), "prod-prod1", keepalived), context.selection(root(FALCON)))
        assertEquals(listOf("prod/prod-prod1@KeepAliveD"), targets(context.selectionScope(root(FALCON))))
    }

    fun testNestedRootsShareEnvironmentAndHostButKeepTheirPlay() {
        val nested = root(DANGER_ZONE)
        val clone = PlayKeys.of(nested, play("$DANGER_ZONE/playbook-clone-to-replisync.yml", "Clone to replisync"))
        context.setSelection(nested, RootContext(EnvironmentChoice.Named("prod"), "prod-replisync1", clone))
        assertEquals(RootContext(EnvironmentChoice.Named("prod"), "prod-replisync1"), context.selection(root(PELICAN)))
        assertEquals(RootContext(EnvironmentChoice.Named("prod"), "prod-replisync1", clone), context.selection(nested))
        val target = context.selectionScope(nested).targets.single()
        assertEquals("prod/prod-replisync1@Clone to replisync", label(target))
        assertEquals("nested hosts are the parent's machines", PELICAN, target.host.root)
        assertEquals(vf(DANGER_ZONE), target.playbookDir)

        context.setSelection(root(PELICAN), RootContext(EnvironmentChoice.Named("prod"), "prod-db1"))
        assertEquals(RootContext(EnvironmentChoice.Named("prod"), "prod-db1", clone), context.selection(nested))
        assertEquals("the parent keeps its own (empty) play", RootContext(EnvironmentChoice.Named("prod"), "prod-db1"), context.selection(root(PELICAN)))
    }

    fun testPlayKeysClimbOutOfANestedRootForImportedPlays() {
        val nested = root(DANGER_ZONE)
        val imported = PlayGraph.getInstance(project).executionOrder(vf("$DANGER_ZONE/playbook-clone-to-replisync.yml")).last()
        val key = PlayKeys.of(nested, imported)
        assertTrue(key, key.startsWith("../../playbook-setup-replisync.yml#"))
        assertEquals(imported, PlayKeys.resolve(project, nested, key))
        assertNull(PlayKeys.resolve(project, nested, "missing.yml#0"))
        assertNull(PlayKeys.resolve(project, nested, "playbook-clone-to-replisync.yml#99"))
        assertNull(PlayKeys.resolve(project, nested, "no-index"))
    }

    fun testStalePartsAreKeptReportedAndFallBack() {
        context.setSelection(root(FALCON), RootContext(EnvironmentChoice.Named("prod"), "prod-db9", "gone.yml#0"))
        assertEquals("kept as stored", RootContext(EnvironmentChoice.Named("prod"), "prod-db9", "gone.yml#0"), context.selection(root(FALCON)))
        assertEquals(
            "prod-db9 is no longer in environments/prod/hosts.yml; Play gone.yml#0 no longer exists in falcon",
            impl.selectionProblem(root(FALCON)),
        )
        val scope = context.selectionScope(root(FALCON))
        assertEquals("falls back to the environment", listOf("prod/prod-prod1", "prod/prod-prod2"), hosts(scope))
        assertTrue(scope.targets.all { it.play == null })

        context.setSelection(root(FALCON), RootContext(EnvironmentChoice.Named("build")))
        assertEquals("build is no longer an environment of falcon", impl.selectionProblem(root(FALCON)))
        assertEquals("falls back to All", 10, context.selectionScope(root(FALCON)).hosts.size)
        assertNull(impl.selectionProblem(root(PLATFORM)))
    }

    fun testASelectedPlayNarrowsAllToTheHostsItMatches() {
        val keepalived = PlayKeys.of(root(FALCON), play("$FALCON/playbook-setup-system.yml", "KeepAliveD"))
        val all = context.selectionScope(root(FALCON), RootContext(play = keepalived))
        assertEquals(listOf("prod/prod-prod1@KeepAliveD", "prod/prod-prod2@KeepAliveD"), targets(all))
        val elsewhere = context.selectionScope(root(FALCON), RootContext(EnvironmentChoice.Named("test"), "test-test1", keepalived))
        assertEquals(emptyList<Any>(), elsewhere.targets)
        assertEquals("Play KeepAliveD does not run on test-test1", elsewhere.emptyReason)
    }

    fun testRootsWithoutInventoryHaveEmptySelectionScopes() {
        val scope = context.selectionScope(root("golden"))
        assertEquals(emptyList<Any>(), scope.targets)
        assertEquals("golden has no inventory", scope.emptyReason)
    }

    fun testSelectionChangesBumpOnlyTheSelectionTracker() {
        val model = context.modelTracker.modificationCount
        val selection = context.selectionTracker.modificationCount
        val target = context.hostScope(vf(POSTFIX_TEMPLATE)).targets.first()
        val view = context.inventoryView(target)
        context.setSelection(root(FALCON), RootContext(EnvironmentChoice.Named("prod"), "prod-prod2"))
        assertTrue(context.selectionTracker.modificationCount > selection)
        assertEquals(model, context.modelTracker.modificationCount)
        assertSame("switching the selection recomputes no model view", view, context.inventoryView(target))
        val again = context.selectionTracker.modificationCount
        context.setSelection(root(FALCON), RootContext(EnvironmentChoice.Named("prod"), "prod-prod2"))
        assertEquals("an unchanged selection publishes nothing", again, context.selectionTracker.modificationCount)
    }

    fun testModelChangesBumpTheModelTracker() {
        val model = context.modelTracker.modificationCount
        add("$FALCON/environments/prod/group_vars/haproxy.yml", "---\nhaproxy_extra: 1")
        refreshRoots()
        assertTrue(context.modelTracker.modificationCount > model)
    }
}
