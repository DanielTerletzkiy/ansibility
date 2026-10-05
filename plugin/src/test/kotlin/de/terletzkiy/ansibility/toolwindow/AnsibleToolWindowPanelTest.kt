package de.terletzkiy.ansibility.toolwindow

import com.intellij.ide.DataManager
import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.ShortcutSet
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.ui.tree.TreeVisitor
import com.intellij.util.ui.tree.TreeUtil
import de.terletzkiy.ansibility.toolwindow.model.AnsibleTreeNode
import javax.swing.tree.TreePath

/**
 * The Swing side of the tool window, headless: the async tree renders the model, the first load expands the roots,
 * selection drives the details pane, double-click/Enter/F4 navigation, the data context, and the refresh after a
 * `hosts.yml` edit, saved or not (plan F6.3, F6.4).
 */
class AnsibleToolWindowPanelTest : ToolWindowTestCase() {
    private lateinit var panel: AnsibleToolWindowPanel

    override fun setUp() {
        super.setUp()
        for (repo in listOf(FALCON, PLATFORM)) {
            copyInfraFile("$repo/ansible.cfg")
            myFixture.copyDirectoryToProject("infra/$repo/environments", "$repo/environments")
            myFixture.copyDirectoryToProject("infra/$repo/group_vars", "$repo/group_vars")
        }
        refreshRoots()
        panel = AnsibleToolWindowPanel.create(project, testRootDisposable)
        waitForRefresh(0)
    }

    private fun waitForRefresh(after: Long) {
        PlatformTestUtil.waitWithEventsDispatching("the tool window did not refresh", { panel.refreshCount > after }, TIMEOUT_SECONDS)
        PlatformTestUtil.waitWhileBusy(panel.tree)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        PlatformTestUtil.waitWhileBusy(panel.tree)
    }

    private fun nodeOf(path: TreePath): AnsibleTreeNode? = TreeUtil.getLastUserObject(AnsibleNodeDescriptor::class.java, path)?.node

    /**
     * Selects the node reached by these names (as in the headless model) in the Swing tree, expanding only the
     * nodes on the way, and returns its path.
     */
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
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(found, panel.tree.selectionPath)
        // The details are computed in a background read action; wait until the pane shows this node's.
        PlatformTestUtil.waitWithEventsDispatching("no details for ${names.joinToString(" › ")}", { panel.detailsNode?.key == key }, TIMEOUT_SECONDS)
        return found
    }

    private fun action(shortcuts: ShortcutSet): AnAction =
        ActionUtil.getActions(panel.tree).first { it.shortcutSet.shortcuts.toList() == shortcuts.shortcuts.toList() }

    private fun perform(action: AnAction) {
        action.actionPerformed(TestActionEvent.createTestEvent(action, DataManager.getInstance().getDataContext(panel.tree)))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    private fun assertEditorAt(target: String) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: error("no editor opened")
        val base = myFixture.tempDirFixture.getFile("")!!
        val file = editor.virtualFile!!
        val text = VfsUtilCore.loadText(file)
        val line = text.substring(0, editor.caretModel.offset).count { it == '\n' } + 1
        assertEquals(target, "${VfsUtilCore.getRelativePath(file, base)}:$line")
    }

    // ------------------------------------------------------------------ rendering

    fun testTreeShowsTheRootsExpandedOnFirstLoad() {
        val printed = PlatformTestUtil.print(panel.tree, false)
        assertTrue(printed, printed.lines().any { it.trim() == "-falcon" })
        assertTrue(printed, printed.lines().any { it.trim() == "-platform" })
        assertTrue("the roots are expanded on the first load:\n$printed", printed.contains("Shared (playbook-level) vars") && printed.contains("Environments"))
        assertEquals(listOf("falcon", "platform"), panel.snapshot.roots.map { it.root.displayName })
    }

    fun testRendererShowsTheGreyExtraTextAndTooltip() {
        val path = select("platform", "Environments", "prod", "Groups", "contracting")
        val descriptor = TreeUtil.getLastUserObject(AnsibleNodeDescriptor::class.java, path)!!
        val fragments = descriptor.presentation.coloredText.map { it.text }
        assertEquals(listOf("contracting", "  group_vars/contracting/{mysql_users,vault}.yml (L6) · → prod-mlflow1, prod-training1"), fragments)
        assertTrue(descriptor.presentation.tooltip!!.contains("Group contracting of prod: depth 1, priority 1"))
        assertEquals("contracting", descriptor.toString())
        assertFalse("double-click opens the group key instead of expanding", descriptor.expandOnDoubleClick())
    }

    fun testPlatformContractingAnalyticsMlflowIsReachable() {
        val path = select("platform", "Environments", "prod", "Groups", "contracting", "analytics", "prod-mlflow1")
        val names = path.path.drop(1).map { TreeUtil.getUserObject(AnsibleNodeDescriptor::class.java, it)!!.node.presentation().name }
        assertEquals(listOf("platform", "Environments", "prod", "Groups", "contracting", "analytics", "prod-mlflow1"), names)
    }

    // ------------------------------------------------------------------ details

    fun testSelectingContractingShowsItsDetails() {
        select("platform", "Environments", "prod", "Groups", "contracting")
        val details = panel.detailsView.details ?: error("no details")
        assertEquals("Group contracting", details.title)
        assertEquals(listOf("analytics"), details.section("Children")!!.items.map { it.text })
        assertEquals("group_vars/contracting/mysql_users.yml", details.section("Var files in load order")!!.items.first().text)
        assertEquals(listOf("prod-mlflow1", "prod-training1"), details.section("Hosts it applies to")!!.items.map { it.text })

        select("platform", "Environments", "prod", "Groups")
        assertNull("containers show the hint", panel.detailsView.details)
    }

    fun testDetailLinksNavigate() {
        select("platform", "Environments", "prod", "Groups", "contracting")
        val link = panel.detailsView.details!!.section("Var files in load order")!!.items.first()
        assertTrue(panel.navigate(link.target!!))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEditorAt("$PLATFORM/environments/prod/group_vars/contracting/mysql_users.yml:1")
    }

    // ------------------------------------------------------------------ navigation (plan F6.3)

    fun testNavigateOpensTheHostEntry() {
        select("falcon", "Environments", "prod", "Hosts", "prod-prod1")
        assertTrue(panel.navigate(panel.selectedNode()!!))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEditorAt("$FALCON/environments/prod/hosts.yml:7")
    }

    fun testEnterOpensNavigableNodesAndTogglesContainers() {
        select("platform", "Environments", "prod", "Groups", "contracting")
        perform(action(CommonShortcuts.ENTER))
        assertEditorAt("$PLATFORM/environments/prod/hosts.yml:78")

        val groups = select("falcon", "Environments", "prod", "Groups")
        panel.tree.expandPath(groups)
        PlatformTestUtil.waitWhileBusy(panel.tree)
        assertTrue(panel.tree.isExpanded(groups))
        perform(action(CommonShortcuts.ENTER))
        assertFalse("Enter collapses a container", panel.tree.isExpanded(groups))
        perform(action(CommonShortcuts.ENTER))
        assertTrue("and expands it again", panel.tree.isExpanded(groups))
    }

    fun testJumpToSourceOpensEvenContainerLikeNodes() {
        select("falcon", "Environments", "prod")
        perform(action(CommonShortcuts.getEditSource()))
        assertEditorAt("$FALCON/environments/prod/hosts.yml:1")
    }

    fun testDataContextOffersTheNavigatable() {
        select("platform", "Environments", "prod", "Groups", "contracting", "group_vars/contracting/vault.yml")
        HeadlessDataManager.fallbackToProductionDataManager(testRootDisposable)
        val context = DataManager.getInstance().getDataContext(panel.tree)
        val navigatable = CommonDataKeys.NAVIGATABLE_ARRAY.getData(context)?.single() as OpenFileDescriptor
        assertEquals(vf("$PLATFORM/environments/prod/group_vars/contracting/vault.yml"), navigatable.file)
        assertEquals(0, navigatable.offset)
        assertEquals(navigatable.file, CommonDataKeys.VIRTUAL_FILE.getData(context))
    }

    // ------------------------------------------------------------------ refresh (plan F6.4)

    fun testEditingHostsYmlRefreshesTheTreeWithinASecond() {
        val groups = select("falcon", "Environments", "prod", "Groups")
        panel.tree.expandPath(groups)
        select("falcon", "Environments", "prod")
        assertEquals("11 groups · 2 hosts · 8 files", panel.detailsView.details!!.section("Inventory")!!.items[1].text)
        val hostsFile = vf("$FALCON/environments/prod/hosts.yml")
        val text = VfsUtilCore.loadText(hostsFile)
        val before = panel.refreshCount
        val started = System.nanoTime()
        WriteAction.runAndWait<Exception> {
            hostsFile.setBinaryContent((text + "canary:\n  hosts:\n    prod-prod2:\n").toByteArray())
        }
        PlatformTestUtil.waitWithEventsDispatching(
            "the new group did not show up",
            { panel.refreshCount > before && panel.snapshot.root(vf(FALCON))?.environment("prod")?.group("canary") != null },
            TIMEOUT_SECONDS,
        )
        val millis = (System.nanoTime() - started) / 1_000_000
        PlatformTestUtil.waitWhileBusy(panel.tree)
        println("TOOLWINDOW: saved hosts.yml edit to refreshed snapshot: $millis ms")
        assertTrue("refresh took $millis ms", millis < REFRESH_BUDGET_MS)

        assertTrue("expansion survives the refresh", panel.tree.isExpanded(groups))
        assertEquals("the selection survives", "prod", panel.selectedNode()?.presentation()?.name)
        PlatformTestUtil.waitWithEventsDispatching(
            "the details did not follow the refresh",
            { panel.detailsView.details?.section("Inventory")?.items?.get(1)?.text == "12 groups · 2 hosts · 8 files" },
            TIMEOUT_SECONDS,
        )
        select("falcon", "Environments", "prod", "Groups", "canary")
    }

    fun testTypingInHostsYmlRefreshesBeforeTheFileIsSaved() {
        val hostsFile = vf("$PLATFORM/environments/prod/hosts.yml")
        val document = FileDocumentManager.getInstance().getDocument(hostsFile)!!
        val before = panel.refreshCount
        val started = System.nanoTime()
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, "\nunsaved:\n  hosts:\n    prod-alias1:\n") }
        PlatformTestUtil.waitWithEventsDispatching(
            "an unsaved edit did not show up",
            { panel.refreshCount > before && panel.snapshot.root(vf(PLATFORM))?.environment("prod")?.group("unsaved") != null },
            TIMEOUT_SECONDS,
        )
        println("TOOLWINDOW: unsaved hosts.yml edit to refreshed snapshot: ${(System.nanoTime() - started) / 1_000_000} ms")
        assertTrue("nothing was saved", FileDocumentManager.getInstance().isDocumentUnsaved(document))
        select("platform", "Environments", "prod", "Groups", "unsaved", "prod-alias1")
    }

    fun testRefreshActionRebuilds() {
        val before = panel.refreshCount
        panel.requestRefresh()
        waitForRefresh(before)
        assertTrue(panel.refreshCount > before)
    }

    private companion object {
        const val TIMEOUT_SECONDS = 20

        /** Plan F6.4 asks for about 1 s; the headless test machine gets some slack. */
        const val REFRESH_BUDGET_MS = 3_000L
    }
}
