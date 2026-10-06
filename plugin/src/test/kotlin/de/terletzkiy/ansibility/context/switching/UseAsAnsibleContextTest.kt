package de.terletzkiy.ansibility.context.switching

import com.intellij.ide.DataManager
import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.ui.tree.TreeVisitor
import com.intellij.util.ui.tree.TreeUtil
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext
import de.terletzkiy.ansibility.toolwindow.AnsibleNodeDescriptor
import de.terletzkiy.ansibility.toolwindow.AnsibleToolWindowPanel
import de.terletzkiy.ansibility.toolwindow.model.AnsibleTreeNode
import de.terletzkiy.ansibility.toolwindow.model.TreeContext
import de.terletzkiy.ansibility.toolwindow.model.WorkspaceNode
import de.terletzkiy.ansibility.toolwindow.model.WorkspaceSnapshotBuilder
import de.terletzkiy.ansibility.api.PlayGraph
import javax.swing.tree.TreePath

/**
 * "Ansibility: Use as Ansible Context" (F8.1) from the tool window's environment, group and host nodes and from inventory files,
 * and the tool-window context button that joins the toolbar.
 */
@RequiresInfraFixture
class UseAsAnsibleContextTest : ContextSwitchingTestCase() {
    private val useAs get() = ActionManager.getInstance().getAction(ToolWindowContextAction.USE_AS_CONTEXT_ACTION_ID)

    private val treeContext = TreeContext { PlayGraph.getInstance(project).playsOf(it) }

    /** The headless tree node reached by these presentation names. */
    private fun node(vararg names: String): AnsibleTreeNode {
        var node: AnsibleTreeNode = WorkspaceNode(runReadActionBlocking { WorkspaceSnapshotBuilder.build(project) })
        for (name in names) {
            node = runReadActionBlocking { node.children(treeContext) }.firstOrNull { it.presentation().name == name }
                ?: error("no $name under ${node.presentation().name}")
        }
        return node
    }

    private fun panel(): AnsibleToolWindowPanel {
        val panel = AnsibleToolWindowPanel.create(project, testRootDisposable)
        PlatformTestUtil.waitWithEventsDispatching("the tool window did not load", { panel.refreshCount > 0 }, TIMEOUT_SECONDS)
        PlatformTestUtil.waitWhileBusy(panel.tree)
        return panel
    }

    private fun select(panel: AnsibleToolWindowPanel, vararg names: String) {
        val key = node(*names).key
        val visitor = TreeVisitor { path: TreePath ->
            val node = TreeUtil.getLastUserObject(AnsibleNodeDescriptor::class.java, path)?.node
            when {
                node == null -> TreeVisitor.Action.CONTINUE
                node.key == key -> TreeVisitor.Action.INTERRUPT
                key.startsWith(node.key + "/") -> TreeVisitor.Action.CONTINUE
                else -> TreeVisitor.Action.SKIP_CHILDREN
            }
        }
        assertNotNull(PlatformTestUtil.waitForPromise(TreeUtil.promiseSelect(panel.tree, visitor), TIMEOUT_SECONDS * 1000L))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    fun testTheActionIsRegisteredForTheEditorAndTheToolWindowPopup() {
        assertTrue(useAs is UseAsAnsibleContextAction)
        val popup = ActionManager.getInstance().getAction("Ansibility.ToolWindow.Popup") as DefaultActionGroup
        assertTrue(popup.getChildren(null).contains(useAs))
        val editorPopup = ActionManager.getInstance().getAction("EditorPopupMenu") as DefaultActionGroup
        assertTrue(editorPopup.getChildren(null).contains(useAs))
        assertEquals(ActionUpdateThread.BGT, useAs.actionUpdateThread)
    }

    fun testNodesStandForTheirEnvironmentOrHost() {
        val falcon = root("repos/falcon/ansible")
        assertEquals(ContextTarget(falcon, "prod", "prod-prod1"), UseAsAnsibleContextAction.targetOf(node("falcon", "Environments", "prod", "Hosts", "prod-prod1")))
        assertEquals(ContextTarget(falcon, "prod"), UseAsAnsibleContextAction.targetOf(node("falcon", "Environments", "prod")))
        assertEquals("a group stands for its environment", ContextTarget(falcon, "prod"),
            UseAsAnsibleContextAction.targetOf(node("falcon", "Environments", "prod", "Groups", "keepalived")))
        assertNull(UseAsAnsibleContextAction.targetOf(node("falcon", "Environments")))
        assertNull(UseAsAnsibleContextAction.targetOf(node("falcon")))
    }

    fun testUseAsAnsibleContextFromAHostNodeOfTheToolWindow() {
        HeadlessDataManager.fallbackToProductionDataManager(testRootDisposable)
        val panel = panel()
        select(panel, "falcon", "Environments", "prod", "Hosts", "prod-prod1")
        val data = DataManager.getInstance().getDataContext(panel.tree)
        val event = updated(useAs, data)
        assertTrue(event.presentation.isEnabledAndVisible)
        assertEquals("Ansibility: Use prod › prod-prod1 as Ansible Context", event.presentation.text)
        perform(useAs, data)
        assertEquals(RootContext(EnvironmentChoice.Named("prod"), "prod-prod1"), context.selection(root("repos/falcon/ansible")))

        select(panel, "falcon", "Environments", "test")
        assertEquals("Ansibility: Use test as Ansible Context", updated(useAs, DataManager.getInstance().getDataContext(panel.tree)).presentation.text)
        select(panel, "falcon", "Environments")
        assertFalse("not on container nodes", updated(useAs, DataManager.getInstance().getDataContext(panel.tree)).presentation.isEnabledAndVisible)
    }

    fun testTheToolWindowContextButtonShowsTheSelectedNodesRoot() {
        HeadlessDataManager.fallbackToProductionDataManager(testRootDisposable)
        val button = ActionManager.getInstance().getAction("Ansibility.ToolWindow.Context")
        assertTrue(button is ToolWindowContextAction)
        val extra = ActionManager.getInstance().getAction(AnsibleToolWindowPanel.TOOLBAR_EXTRA_GROUP) as DefaultActionGroup
        assertTrue("joins the toolbar through the extra group", extra.getChildren(null).contains(button))

        val panel = panel()
        select(panel, "platform", "Environments", "prod")
        assertEquals("platform · All envs", updated(button, DataManager.getInstance().getDataContext(panel.tree)).presentation.text)
        context.setSelection(root("repos/falcon/ansible"), RootContext(EnvironmentChoice.Named("prod"), "prod-prod1"))
        select(panel, "falcon", "Environments", "prod", "Hosts", "prod-prod1")
        assertEquals("falcon · prod › prod-prod1", updated(button, DataManager.getInstance().getDataContext(panel.tree)).presentation.text)

        val data = DataManager.getInstance().getDataContext(panel.tree)
        val popup = children(ToolWindowContextAction.popupGroup(project, panel.tree), data).single() as ContextPopupGroup
        val lines = texts(children(popup, data), data)
        assertEquals(listOf("Environment: prod", "Host: prod-prod1", "Play: Auto"), lines.take(3))
        assertTrue("offers the selected node: $lines", "Ansibility: Use prod › prod-prod1 as Ansible Context" in lines)
    }

    fun testInventoryFilesStandForTheirHostOrEnvironment() {
        val falcon = root("repos/falcon/ansible")
        assertEquals(ContextTarget(falcon, "prod", "prod-prod1"), UseAsAnsibleContextAction.fileTarget(project, vf(prod1Vars), -1))
        assertEquals(ContextTarget(falcon, "prod"), UseAsAnsibleContextAction.fileTarget(project, vf("repos/falcon/ansible/environments/prod/group_vars/all/vars.yml"), -1))
        val hosts = vf(falconProdHosts)
        assertEquals(ContextTarget(falcon, "prod", "prod-prod2"), UseAsAnsibleContextAction.fileTarget(project, hosts, offsetOf(falconProdHosts, "prod-prod2:")))
        assertEquals(ContextTarget(falcon, "prod"), UseAsAnsibleContextAction.fileTarget(project, hosts, offsetOf(falconProdHosts, "keepalived:")))
        assertNull("playbook-level group_vars apply to every environment", UseAsAnsibleContextAction.fileTarget(project, vf("repos/falcon/ansible/group_vars/all/vars.yml"), -1))
        assertNull(UseAsAnsibleContextAction.fileTarget(project, vf(postfixTasks), -1))
    }

    fun testUseAsAnsibleContextFromTheEditor() {
        myFixture.configureFromExistingVirtualFile(vf(prod1Vars))
        val data = dataContext(vf(prod1Vars))
        assertEquals("Ansibility: Use prod › prod-prod1 as Ansible Context", updated(useAs, data).presentation.text)
        perform(useAs, data)
        assertEquals(RootContext(EnvironmentChoice.Named("prod"), "prod-prod1"), context.selection(root("repos/falcon/ansible")))
        assertFalse("hidden in role files", updated(useAs, dataContext(vf(postfixTasks))).presentation.isEnabledAndVisible)
    }

    private companion object {
        const val TIMEOUT_SECONDS = 30
    }
}
