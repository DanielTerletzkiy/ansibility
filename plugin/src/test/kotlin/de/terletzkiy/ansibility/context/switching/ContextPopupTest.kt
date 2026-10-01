package de.terletzkiy.ansibility.context.switching

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.testFramework.DumbModeTestUtils
import de.terletzkiy.ansibility.api.WidgetSegment
import de.terletzkiy.ansibility.settings.AnsibilityWorkspaceState
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext

/**
 * The context popup of the status-bar widget and the tool-window button (F8.1): what it lists, choosing environment,
 * host and play through it, persistence of the result, and how a vanished host is kept and reported.
 */
class ContextPopupTest : ContextSwitchingTestCase() {
    private fun popup(path: String = "repos/falcon/ansible", segments: List<WidgetSegment>? = emptyList()) =
        ContextPopupGroup(root(path), null, segments)

    private fun submenu(path: String, prefix: String): ActionGroup =
        children(popup(path)).filterIsInstance<ActionGroup>().single { updated(it).presentation.text.startsWith(prefix) }

    fun testThePopupListsTheSelectionSubmenusFollowEditorAndSwitchContext() {
        assertEquals(
            listOf(
                "Environment: All environments",
                "Host: All hosts",
                "Play: Auto",
                "--",
                "[x] Follow Editor",
                "--",
                "Ansibility: Switch Context…",
            ),
            texts(children(popup())),
        )
    }

    fun testTheSubmenusListEnvironmentsHostsAndPlays() {
        assertEquals(
            listOf("[x] All environments | 10 hosts", "ops | 1 host", "prod | 2 hosts", "test | 7 hosts"),
            texts(children(submenu("repos/falcon/ansible", "Environment"))),
        )
        val hosts = texts(children(submenu("repos/falcon/ansible", "Host")))
        assertEquals("[x] All hosts", hosts.first())
        assertTrue(hosts.toString(), "prod › prod-prod1 | 192.0.2.29" in hosts)
        assertTrue(hosts.toString(), "test › test-test1 | 1 of 7 names on 192.0.2.43" in hosts)
        val plays = texts(children(submenu("repos/falcon/ansible", "Play")))
        assertEquals("[x] Auto | every play that hits the selection", plays.first())
        assertTrue(plays.toString(), "playbook-setup-system.yml › KeepAliveD" in plays)
    }

    fun testChoosingEnvironmentHostAndPlayRoundTripsThroughTheStoredSelection() {
        val falcon = root("repos/falcon/ansible")
        perform(child(submenu("repos/falcon/ansible", "Environment"), "prod"))
        assertEquals(RootContext(EnvironmentChoice.Named("prod")), context.selection(falcon))
        assertEquals("Environment: prod", updated(submenu("repos/falcon/ansible", "Environment")).presentation.text)
        assertEquals("under a named environment only its hosts", listOf("[x] All hosts", "prod-prod1 | 192.0.2.29", "prod-prod2 | 192.0.2.30"),
            texts(children(submenu("repos/falcon/ansible", "Host"))))

        perform(child(submenu("repos/falcon/ansible", "Host"), "prod-prod1"))
        perform(child(submenu("repos/falcon/ansible", "Play"), "playbook-setup-system.yml › KeepAliveD"))
        val chosen = RootContext(EnvironmentChoice.Named("prod"), "prod-prod1", "playbook-setup-system.yml#4")
        assertEquals(chosen, context.selection(falcon))
        assertEquals(
            listOf("Environment: prod", "Host: prod-prod1", "Play: playbook-setup-system.yml › KeepAliveD"),
            texts(children(popup()).take(3)),
        )
        assertEquals(listOf("prod/prod-prod1@KeepAliveD"), targets(context.selectionScope(falcon)))

        val xml = persistedXml()
        assertTrue(xml, xml.contains("environment=\"prod\"") && xml.contains("host=\"prod-prod1\"") && xml.contains("play=\"playbook-setup-system.yml#4\""))
        restart()
        assertEquals("the choice survives a restart", chosen, context.selection(falcon))

        perform(child(submenu("repos/falcon/ansible", "Host"), "prod-prod2"))
        assertEquals("the play is kept while it still hits the host", RootContext(EnvironmentChoice.Named("prod"), "prod-prod2", "playbook-setup-system.yml#4"), context.selection(falcon))
        perform(child(submenu("repos/falcon/ansible", "Environment"), "All environments"))
        assertEquals(RootContext(EnvironmentChoice.All, null, "playbook-setup-system.yml#4"), context.selection(falcon))
        perform(child(submenu("repos/falcon/ansible", "Play"), "Auto"))
        assertEquals(RootContext.DEFAULT, context.selection(falcon))
        assertFalse("the default is not persisted", persistedXml().contains("repos/falcon/ansible"))
    }

    fun testAVanishedHostIsKeptReportedAndTheScopeFallsBackToItsEnvironment() {
        val falcon = root("repos/falcon/ansible")
        AnsibilityWorkspaceState.getInstance(project).updateContext(falcon) { RootContext(EnvironmentChoice.Named("prod"), "prod-db9") }
        restart()
        assertEquals("kept as stored", RootContext(EnvironmentChoice.Named("prod"), "prod-db9"), context.selection(falcon))
        val lines = texts(children(popup()))
        assertTrue(lines.toString(), "⚠ prod-db9 is no longer in environments/prod/hosts.yml (grey)" in lines)
        assertEquals("Host: prod-db9", lines[1])
        assertEquals("the scope falls back to the environment", listOf("prod/prod-prod1", "prod/prod-prod2"), hosts(context.selectionScope(falcon)))
        assertTrue(persistedXml().contains("host=\"prod-db9\""))
    }

    fun testAVanishedPlayIsKeptReportedAndTheScopeFallsBackToAuto() {
        val falcon = root("repos/falcon/ansible")
        val stored = RootContext(EnvironmentChoice.Named("prod"), "prod-prod1", "gone.yml#0")
        AnsibilityWorkspaceState.getInstance(project).updateContext(falcon) { stored }
        restart()
        assertEquals("kept as stored", stored, context.selection(falcon))
        val lines = texts(children(popup()))
        assertEquals("Play: gone.yml#0", lines[2])
        assertTrue(lines.toString(), "⚠ Play gone.yml#0 no longer exists in falcon (grey)" in lines)
        assertEquals(
            "the scope falls back to Auto on the stored host",
            targets(context.selectionScope(falcon, RootContext(EnvironmentChoice.Named("prod"), "prod-prod1"))),
            targets(context.selectionScope(falcon)),
        )
        val plays = texts(children(submenu("repos/falcon/ansible", "Play")))
        assertFalse("no listed play is checked: $plays", plays.any { it.startsWith("[x]") })
        assertTrue(persistedXml().contains("play=\"gone.yml#0\""))

        perform(child(submenu("repos/falcon/ansible", "Play"), "Auto"))
        assertEquals("choosing a play again clears the problem", RootContext(EnvironmentChoice.Named("prod"), "prod-prod1"), context.selection(falcon))
        assertFalse(texts(children(popup())).any { it.startsWith("⚠") })
    }

    fun testSegmentActionsAreListedUnderTheSegmentText() {
        val unlock = InfoAction("Unlock…")
        val segments = listOf(WidgetSegment("🔒 2 ids locked", "locked", listOf(unlock)), WidgetSegment("scope heron", null))
        val children = children(popup(segments = segments))
        val separator = children.indexOfFirst { it is Separator && it.text == "🔒 2 ids locked" }
        assertTrue(texts(children).toString(), separator > 0)
        assertSame(unlock, children[separator + 1])
        assertFalse("a segment without actions adds nothing", children.any { it is Separator && it.text == "scope heron" })
    }

    fun testRootsWithoutInventoryAndIndexingShowOneGreyLine() {
        assertEquals("golden has no inventory (grey)", texts(children(popup("golden"))).first())
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertEquals("Available when indexing finishes (grey)", texts(children(popup())).first())
        }
    }

    fun testFollowEditorTogglesTheProjectWideFlagOnly() {
        val falcon = root("repos/falcon/ansible")
        context.setSelection(falcon, RootContext(EnvironmentChoice.Named("test"), "test-test1"))
        val toggle = children(popup()).single { it is FollowEditorAction }
        perform(toggle)
        assertFalse(AnsibilityWorkspaceState.getInstance(project).snapshot.followEditor)
        assertEquals("the stored selection is unchanged", RootContext(EnvironmentChoice.Named("test"), "test-test1"), context.selection(falcon))
        assertTrue(persistedXml(), persistedXml().contains("followEditor=\"false\""))
        assertEquals("Follow Editor", texts(listOf(toggle)).single())
        perform(toggle)
        assertTrue(AnsibilityWorkspaceState.getInstance(project).snapshot.followEditor)
    }
}
