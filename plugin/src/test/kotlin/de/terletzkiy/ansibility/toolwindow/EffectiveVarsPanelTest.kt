package de.terletzkiy.ansibility.toolwindow

import com.intellij.ide.DataManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.ui.tree.TreeVisitor
import com.intellij.util.ui.tree.TreeUtil
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext
import de.terletzkiy.ansibility.toolwindow.host.EffectivePlayChoices
import de.terletzkiy.ansibility.toolwindow.host.EffectiveTables
import de.terletzkiy.ansibility.toolwindow.host.EffectiveVarNode
import de.terletzkiy.ansibility.toolwindow.host.EffectiveVarsNode
import de.terletzkiy.ansibility.toolwindow.host.PlayChoice
import de.terletzkiy.ansibility.toolwindow.host.TargetPlaybookNode
import de.terletzkiy.ansibility.toolwindow.host.TargetedByNode
import de.terletzkiy.ansibility.toolwindow.model.AnsibleTreeNode
import de.terletzkiy.ansibility.toolwindow.model.HostNode
import de.terletzkiy.ansibility.toolwindow.model.LayerSourceNode
import javax.swing.tree.TreePath

/**
 * The Swing side of HA7a, headless: selecting a host's Effective vars shows the tree table with the play selector;
 * choosing a play, switching the Ansible context or the end of indexing recomputes the table and the tree in the
 * background (a refresh with unchanged rows keeps the table, a new table of the host keeps what was expanded); a row
 * opens its definition and is offered to popup actions (masked); the async tree loads a host's Effective vars only when
 * the host is expanded, and Expand All leaves every host's Effective vars and Targeted by collapsed; choosing Auto
 * forgets a play that no longer runs; and the toolbar and tree popup keep the groups other areas join.
 */
class EffectiveVarsPanelTest : ToolWindowTestCase() {
    private lateinit var panel: AnsibleToolWindowPanel

    override fun setUp() {
        super.setUp()
        myFixture.copyDirectoryToProject("${InfraTestData.INFRA}/$FALCON", FALCON)
        refreshRoots()
        panel = AnsibleToolWindowPanel.create(project, testRootDisposable)
        PlatformTestUtil.waitWithEventsDispatching("the tool window did not load", { panel.refreshCount > 0 }, TIMEOUT_SECONDS)
        PlatformTestUtil.waitWhileBusy(panel.tree)
    }

    private fun nodeOf(path: TreePath): AnsibleTreeNode? = TreeUtil.getLastUserObject(AnsibleNodeDescriptor::class.java, path)?.node

    /** Selects the node reached by [names], expanding only the nodes on the way, and waits for its details. */
    private fun select(vararg names: String): TreePath {
        val key = path(*names).key
        val visitor = TreeVisitor { path ->
            val node = nodeOf(path)
            when {
                node == null -> TreeVisitor.Action.CONTINUE
                node.key == key -> TreeVisitor.Action.INTERRUPT
                key.startsWith(node.key + "/") -> TreeVisitor.Action.CONTINUE
                else -> TreeVisitor.Action.SKIP_CHILDREN
            }
        }
        val found = PlatformTestUtil.waitForPromise(TreeUtil.promiseSelect(panel.tree, visitor), TIMEOUT_SECONDS * 1000L)
            ?: error("no ${names.joinToString(" › ")} in\n${PlatformTestUtil.print(panel.tree, false)}")
        PlatformTestUtil.waitWithEventsDispatching("no details for ${names.last()}", { panel.detailsNode?.key == key }, TIMEOUT_SECONDS)
        return found
    }

    private fun expand(path: TreePath) {
        panel.tree.expandPath(path)
        PlatformTestUtil.waitWhileBusy(panel.tree)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        PlatformTestUtil.waitWhileBusy(panel.tree)
    }

    private val effectivePath = arrayOf("falcon", "Environments", "prod", "Hosts", "prod-prod1", "Effective vars")

    /** The names of the visible rows (never asks a collapsed node for its children). */
    private fun visibleNames(): List<String> =
        (0 until panel.tree.rowCount).mapNotNull { TreeUtil.getLastUserObject(AnsibleNodeDescriptor::class.java, panel.tree.getPathForRow(it))?.toString() }

    /** The rendered text of the visible Effective vars row (name and grey extra), or null. */
    private fun effectiveRowText(): String? {
        val key = path(*effectivePath).key
        for (row in 0 until panel.tree.rowCount) {
            val descriptor = TreeUtil.getLastUserObject(AnsibleNodeDescriptor::class.java, panel.tree.getPathForRow(row)) ?: continue
            if (descriptor.node.key == key) return descriptor.presentation.coloredText.joinToString("") { it.text }
        }
        return null
    }

    fun testSelectingEffectiveVarsShowsTheTableWithThePlaySelector() {
        select(*effectivePath)
        val view = panel.detailsView.effectiveView ?: error("no table for ${panel.detailsView.details}")
        assertEquals("Effective vars of prod-prod1", panel.detailsView.details!!.title)
        val model = view.table.tableModel
        assertEquals(listOf("Name", "Value", "Type", "Layer", "Source", "Shadowed"), (0 until model.columnCount).map(model::getColumnName))
        assertEquals("Auto: every play on the host", 392, view.variableRows.size)
        val index = view.variableRows.indexOfFirst { it.row.name == "postfix_relayhost" }
        val row = view.table.tree.getPathForRow(index).lastPathComponent
        assertEquals(
            listOf("relayinternal.mx.example.de", "str", "L5 playbook group_vars/all", "group_vars/all/vars.yml:156", "2"),
            (1 until model.columnCount).map { model.getValueAt(row, it) },
        )
        view.table.tree.expandRow(index)
        val shadowed = view.rowAt(index + 1) as EffectiveVarsView.Row.Shadowed
        assertTrue("struck through", shadowed.struck)
        assertEquals("environments/prod/group_vars/all/vars.yml:471", shadowed.cell.source)
        assertEquals("Auto, the inventory view, then 27 plays", 29, view.selector.itemCount)
        assertEquals(PlayChoice.Auto, view.content.selected.choice)
        assertEquals("Auto: all 27 plays", view.selector.getItemAt(0).text)
    }

    fun testChoosingAPlayRecomputesTheTableAndTheTree() {
        select(*effectivePath)
        val view = panel.detailsView.effectiveView!!
        val system = (0 until view.selector.itemCount).map(view.selector::getItemAt).single { it.text == "playbook-setup-system.yml › System" }
        val shown = panel.detailsCount
        view.selector.selectedItem = system
        PlatformTestUtil.waitWithEventsDispatching(
            "the table did not follow the play selector",
            { panel.detailsCount > shown && panel.detailsView.effectiveView?.content?.table?.context?.play?.name == "System" },
            TIMEOUT_SECONDS,
        )
        val table = panel.detailsView.effectiveView!!.content.table
        assertEquals(2, table.row("postfix_relayhost")!!.shadowed.size)
        PlatformTestUtil.waitWhileBusy(panel.tree)
        PlatformTestUtil.waitWithEventsDispatching("the tree did not follow the play selector", { effectiveRowText()?.endsWith("play System") == true }, TIMEOUT_SECONDS)
        assertEquals("the selector shows the chosen play", "playbook-setup-system.yml › System", panel.detailsView.effectiveView!!.content.selected.text)
    }

    fun testTheAnsibleContextReRendersTheTreeAndTheTable() {
        select(*effectivePath)
        val root = AnsibleWorkspace.getInstance(project).roots().single { it.dir == vf(FALCON) }
        AnsibleContextService.getInstance(project).setSelection(root, RootContext(EnvironmentChoice.Named("prod"), "prod-prod1", "playbook-setup-system.yml#2"))
        PlatformTestUtil.waitWithEventsDispatching(
            "the tree did not follow the Ansible context",
            { effectiveRowText()?.endsWith("play System (Ansible context)") == true },
            TIMEOUT_SECONDS,
        )
        PlatformTestUtil.waitWithEventsDispatching(
            "the table did not follow the Ansible context",
            { panel.detailsView.effectiveView?.content?.table?.context?.fromContext == true },
            TIMEOUT_SECONDS,
        )
        assertEquals("Auto: play System (Ansible context)", panel.detailsView.effectiveView!!.selector.getItemAt(0).text)
    }

    fun testRuntimeMarkersFollowOnceIndexingHasFinished() {
        val root = AnsibleWorkspace.getInstance(project).roots().single { it.dir == vf(FALCON) }
        AnsibleContextService.getInstance(project).setSelection(root, RootContext(EnvironmentChoice.Named("prod"), "prod-prod1", "playbook-setup-system.yml#2"))
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            select(*effectivePath)
            val table = panel.detailsView.effectiveView?.content?.table ?: error("no table while indexing")
            assertEquals("the play is evaluated while indexing", "System", table.context.play?.name)
            assertFalse("its runtime markers wait for the index", table.markersKnown)
        }
        PlatformTestUtil.waitWithEventsDispatching(
            "the table did not follow the end of indexing",
            { panel.detailsView.effectiveView?.content?.table?.markersKnown == true },
            TIMEOUT_SECONDS,
        )
    }

    fun testARefreshKeepsTheTableAndANewTableKeepsWhatWasExpanded() {
        select(*effectivePath)
        val view = panel.detailsView.effectiveView!!
        val index = view.variableRows.indexOfFirst { it.row.name == "postfix_relayhost" }
        view.table.tree.expandRow(index)
        view.table.setRowSelectionInterval(index, index)

        val refreshes = panel.refreshCount
        val shown = panel.detailsCount
        panel.requestRefresh()
        PlatformTestUtil.waitWithEventsDispatching("no refresh", { panel.refreshCount > refreshes && panel.detailsCount > shown }, TIMEOUT_SECONDS)
        PlatformTestUtil.waitWhileBusy(panel.tree)
        assertSame("the same rows: the pane keeps its table, expansion and selection included", view, panel.detailsView.effectiveView)

        val system = (0 until view.selector.itemCount).map(view.selector::getItemAt).single { it.text == "playbook-setup-system.yml › System" }
        view.selector.selectedItem = system
        PlatformTestUtil.waitWithEventsDispatching(
            "the table did not follow the play selector",
            { panel.detailsView.effectiveView?.content?.table?.context?.play?.name == "System" },
            TIMEOUT_SECONDS,
        )
        val next = panel.detailsView.effectiveView!!
        assertNotSame(view, next)
        val nextIndex = next.variableRows.indexOfFirst { it.row.name == "postfix_relayhost" }
        assertTrue("the variable stays expanded", next.table.tree.isExpanded(nextIndex))
        assertEquals("and selected", nextIndex, next.table.selectedRow)
        assertTrue(next.rowAt(nextIndex + 1) is EffectiveVarsView.Row.Shadowed)
    }

    fun testARowOpensItsDefinition() {
        select(*effectivePath)
        val view = panel.detailsView.effectiveView!!
        val index = view.variableRows.indexOfFirst { it.row.name == "postfix_relayhost" }
        view.table.setRowSelectionInterval(index, index)
        assertTrue(view.openSelected())
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: error("no editor opened")
        val file = editor.virtualFile!!
        val line = VfsUtilCore.loadText(file).substring(0, editor.caretModel.offset).count { it == '\n' } + 1
        assertEquals("$FALCON/group_vars/all/vars.yml:156", "${VfsUtilCore.getRelativePath(file, myFixture.tempDirFixture.getFile("")!!)}:$line")
    }

    fun testExpandingHostsComputesNoTableAndAHostOnlyItsOwn() {
        val tables = EffectiveTables.getInstance(project)
        val before = tables.stats.computations
        expand(select("falcon", "Environments", "prod", "Hosts"))
        // Only the visible rows are read: PlatformTestUtil.print would ask collapsed nodes for their children.
        assertTrue(visibleNames().toString(), "prod-prod2" in visibleNames())
        assertEquals("listing the hosts loads no Effective vars", before, tables.stats.computations)
        expand(select("falcon", "Environments", "prod", "Hosts", "prod-prod1"))
        assertTrue(visibleNames().toString(), "Targeted by" in visibleNames())
        assertEquals(
            "expanding prod-prod1 computes its table only: ${tables.computedHosts.map { it.host }}, ${tables.stats}",
            before + 1,
            tables.stats.computations,
        )
        assertEquals("prod-prod1", tables.computedHosts.last().host)
    }

    fun testChoosingAutoForgetsAPlayThatNoLongerRuns() {
        val stale = PlayChoice.Play("playbook-setup-system.yml#99")
        val host = runReadActionBlocking { (path(*effectivePath) as EffectiveVarsNode).table()!!.context.host }
        EffectivePlayChoices.getInstance(project).set(host, stale)
        select(*effectivePath)
        val view = panel.detailsView.effectiveView!!
        assertNotNull("the pane says why Auto is shown", view.content.table.context.problem)
        assertEquals("the selector shows Auto for the stale play", PlayChoice.Auto, view.content.selected.choice)
        view.selector.selectedItem = view.selector.getItemAt(0)
        PlatformTestUtil.waitWithEventsDispatching(
            "choosing Auto did not forget the stale play",
            { panel.detailsView.effectiveView?.content?.table?.context?.problem == null },
            TIMEOUT_SECONDS,
        )
        assertEquals(PlayChoice.Auto, EffectivePlayChoices.getInstance(project).get(host))
    }

    fun testExpandAllLeavesThePerHostSubtreesCollapsed() {
        val tables = EffectiveTables.getInstance(project)
        val before = tables.stats.computations
        PlatformTestUtil.waitForPromise(panel.expandAll(), TIMEOUT_SECONDS * 3000L)
        PlatformTestUtil.waitWhileBusy(panel.tree)
        val rows = (0 until panel.tree.rowCount).map { panel.tree.getPathForRow(it) }
        val nodes = rows.mapNotNull(::nodeOf)
        assertTrue("hosts are expanded: their sources show", nodes.any { it is LayerSourceNode && it.parent is HostNode })
        val perHost = rows.filter { nodeOf(it) is EffectiveVarsNode || nodeOf(it) is TargetedByNode }
        assertTrue("every host occurrence shows Effective vars and Targeted by", perHost.size >= 2 * nodes.count { it is HostNode })
        assertTrue("…collapsed", perHost.none(panel.tree::isExpanded))
        assertTrue("no variable row is loaded", nodes.none { it is EffectiveVarNode || it is TargetPlaybookNode })
        val hosts = nodes.filterIsInstance<HostNode>().map { it.env.name to it.host.name }.distinct()
        assertTrue("at most one table per host: ${tables.stats}", tables.stats.computations - before <= hosts.size)
        assertTrue("a bounded tree: ${rows.size} rows", rows.size < 3_000)

        // The toolbar's Expand All goes the same way.
        TreeUtil.collapseAll(panel.tree, 0)
        panel.treeExpander.expandAll()
        PlatformTestUtil.waitWithEventsDispatching(
            "the toolbar's Expand All did not expand the hosts",
            { (0 until panel.tree.rowCount).any { nodeOf(panel.tree.getPathForRow(it)) is TargetedByNode } },
            TIMEOUT_SECONDS,
        )
        PlatformTestUtil.waitWhileBusy(panel.tree)
        assertTrue((0 until panel.tree.rowCount).none { nodeOf(panel.tree.getPathForRow(it)) is EffectiveVarNode })
    }

    fun testTheSelectedRowIsOfferedToPopupActions() {
        select(*effectivePath)
        val view = panel.detailsView.effectiveView!!
        val index = view.variableRows.indexOfFirst { it.row.name == "vault_alloy_tenant_api_key_prod" }
        view.table.setRowSelectionInterval(index, index)
        HeadlessDataManager.fallbackToProductionDataManager(testRootDisposable)
        val definition = EffectiveVarsView.SELECTED_DEFINITION.getData(DataManager.getInstance().getDataContext(view.table)) ?: error("no definition offered")
        assertTrue("actions get the masked cell, never a value", definition.masked)
        assertEquals("🔒 vault-encrypted", definition.value)
        assertEquals("group_vars/all/vault.yml:28", definition.source)
        assertNotNull("the popup group other areas add row actions to", ActionManager.getInstance().getAction(EffectiveVarsView.POPUP_GROUP))
    }

    fun testTheToolbarAndTheTreePopupKeepTheirGroups() {
        val toolbar = panel.toolbar as ActionToolbar
        val actions = (toolbar.actionGroup as DefaultActionGroup).childActionsOrStubs.toList()
        assertEquals("Refresh", actions.first().templateText)
        assertEquals("Refresh, expand all, collapse all, a separator and the joined group", 5, actions.size)
        assertSame("scope combo and context button (WS1, HA3)", ActionManager.getInstance().getAction(AnsibleToolWindowPanel.TOOLBAR_EXTRA_GROUP), actions.last())
        assertNotNull("the tree popup (HA3's Use as Ansible context)", ActionManager.getInstance().getAction(AnsibleToolWindowPanel.POPUP_GROUP))
    }

    private companion object {
        const val TIMEOUT_SECONDS = 20
    }
}
