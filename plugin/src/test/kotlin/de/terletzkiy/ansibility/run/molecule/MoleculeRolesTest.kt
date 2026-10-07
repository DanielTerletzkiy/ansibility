package de.terletzkiy.ansibility.run.molecule

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.ui.tree.TreeVisitor
import com.intellij.util.ui.tree.TreeUtil
import de.terletzkiy.ansibility.api.ScopeChoice
import de.terletzkiy.ansibility.api.WorkspaceScopeService
import de.terletzkiy.ansibility.settings.RootKeys
import de.terletzkiy.ansibility.toolwindow.AnsibilityToolWindowIcons
import de.terletzkiy.ansibility.toolwindow.AnsibleNodeDescriptor
import de.terletzkiy.ansibility.toolwindow.AnsibleToolWindowPanel
import de.terletzkiy.ansibility.toolwindow.model.AnsibleTreeNode
import de.terletzkiy.ansibility.toolwindow.model.NodeMarker
import de.terletzkiy.ansibility.toolwindow.model.RoleNameNode
import de.terletzkiy.ansibility.toolwindow.model.RolesNode
import de.terletzkiy.ansibility.toolwindow.model.TreeContext
import de.terletzkiy.ansibility.toolwindow.model.TreeView
import de.terletzkiy.ansibility.toolwindow.model.WorkspaceNode
import de.terletzkiy.ansibility.toolwindow.model.WorkspaceSnapshotBuilder
import de.terletzkiy.ansibility.vault.VaultTestCase
import javax.swing.tree.TreePath

/** Molecule in the Ansibility tool window (plan amendment R16): role markers, which roles a selection tests, the buttons. */
class MoleculeRolesTest : VaultTestCase() {
    private lateinit var falcon: String
    private lateinit var tern: String
    private val falconWeb: String get() = vf("$falcon/roles/web").path
    private val ternWeb: String get() = vf("$tern/roles/web").path

    override fun setUp() {
        super.setUp()
        falcon = projectRoot("falcon")
        tern = projectRoot("tern")
        for (root in listOf(falcon, tern)) {
            write("$root/roles/web/tasks/main.yml", "- name: One\n  ansible.builtin.debug: {}\n")
            write("$root/roles/web/molecule/default/molecule.yml", "---\ndriver:\n  name: default\n")
            write("$root/roles/db/tasks/main.yml", "- name: One\n  ansible.builtin.debug: {}\n")
        }
        root(falcon)
        root(tern)
    }

    override fun tearDown() {
        try {
            WorkspaceScopeService.getInstance(project).set(ScopeChoice.AllRoots)
            MoleculeResults.getInstance(project).resetForTests()
            MoleculeLauncher.executeForTests = null
            MoleculeBatchLauncher.executeForTests = null
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
        } finally {
            super.tearDown()
        }
    }

    private fun tab(view: TreeView) = WorkspaceNode(runReadActionBlocking { WorkspaceSnapshotBuilder.build(project) }, view)

    private fun children(node: AnsibleTreeNode): List<AnsibleTreeNode> = runReadActionBlocking { node.children(TreeContext.NONE) }

    private fun child(node: AnsibleTreeNode, name: String): AnsibleTreeNode = children(node).first { it.presentation().name == name }

    private fun <T> inScope(root: String, block: () -> T): T {
        val service = WorkspaceScopeService.getInstance(project)
        service.set(ScopeChoice.Roots(setOf(RootKeys.keyOf(project, vf(root)))))
        try {
            return block()
        } finally {
            service.set(ScopeChoice.AllRoots)
        }
    }

    private fun dirs(targets: List<MoleculeTarget>): Set<String> = targets.map { it.spec.roleDir }.toSet()

    fun testRolesWithScenariosShowTheirLastResult() {
        val web = child(tab(TreeView.ROLES), "web") as RoleNameNode
        assertEquals(NodeMarker.TESTS, web.presentation().marker)
        assertNull("no scenarios", child(tab(TreeView.ROLES), "db").presentation().marker)
        val results = MoleculeResults.getInstance(project)
        results.finished(MoleculeSpec(falconWeb), emptyList(), 0)
        results.finished(MoleculeSpec(ternWeb), emptyList(), 1)
        assertEquals("the worst copy in scope", NodeMarker.TESTS_FAILED, web.presentation().marker)
        assertEquals(
            mapOf("falcon" to NodeMarker.TESTS_PASSED, "tern" to NodeMarker.TESTS_FAILED),
            children(web).associate { it.presentation().name to it.presentation().marker },
        )
        inScope(falcon) { assertEquals("a role row sums up all its copies, whatever the scope", NodeMarker.TESTS_FAILED, web.presentation().marker) }
        results.started(MoleculeSpec(ternWeb))
        assertEquals(NodeMarker.TESTS_RUNNING, web.presentation().marker)
        assertSame(AnsibilityToolWindowIcons.Molecule, AnsibilityToolWindowIcons.of(NodeMarker.TESTS))
    }

    fun testTheSelectionPicksTheRolesToTest() {
        val web = child(tab(TreeView.ROLES), "web")
        assertEquals("a role row: all its copies", setOf(falconWeb, ternWeb), dirs(MoleculeTargets.ofNodes(listOf(web))))
        inScope(falcon) {
            assertEquals("the scope does not narrow a role row", setOf(falconWeb, ternWeb), dirs(MoleculeTargets.ofNodes(listOf(web))))
            val ternCopy = child(web, "tern")
            assertEquals("a copy stands for itself", setOf(ternWeb), dirs(MoleculeTargets.ofNodes(listOf(ternCopy))))
            val snapshot = runReadActionBlocking { WorkspaceSnapshotBuilder.build(project) }
            assertEquals(setOf(falconWeb), dirs(MoleculeTargets.inScope(snapshot, WorkspaceScopeService.getInstance(project).current())))
        }
        assertTrue("no scenarios, nothing to test", MoleculeTargets.ofNodes(listOf(child(tab(TreeView.ROLES), "db"))).isEmpty())

        val falconRoot = child(tab(TreeView.REPOS), "falcon")
        val roles = children(falconRoot).filterIsInstance<RolesNode>().single()
        assertEquals("a repo's Roles folder: its roles with scenarios", listOf(falconWeb), MoleculeTargets.ofNodes(listOf(roles)).map { it.spec.roleDir })
        assertEquals("a repo: the same", listOf(falconWeb), MoleculeTargets.ofNodes(listOf(falconRoot)).map { it.spec.roleDir })
        val copy = child(roles, "web")
        val scenario = child(child(copy, "molecule"), "default")
        assertEquals(listOf(falconWeb to "default"), MoleculeTargets.ofNodes(listOf(scenario)).map { it.spec.roleDir to it.spec.scenario })
        assertEquals("the whole role covers its scenario", listOf(falconWeb to ""), MoleculeTargets.ofNodes(listOf(scenario, copy)).map { it.spec.roleDir to it.spec.scenario })
        assertEquals(listOf("web" to "falcon"), MoleculeTargets.ofNodes(listOf(copy)).map { it.name to it.label })
    }

    fun testTheButtonsRunTheSelectionOrEveryRoleInScope() {
        val batches = ArrayList<List<MoleculeTarget>>()
        MoleculeBatchLauncher.executeForTests = { batches += it }
        val singles = ArrayList<MoleculeSpec>()
        MoleculeLauncher.executeForTests = { settings, _ -> singles += (settings.configuration as MoleculeConfiguration).spec }
        val panel = AnsibleToolWindowPanel(project, TreeView.ROLES).also { Disposer.register(testRootDisposable, it) }
        PlatformTestUtil.waitWithEventsDispatching("the tool window did not load", { panel.refreshCount > 0 }, 30)
        PlatformTestUtil.waitWhileBusy(panel.tree)
        fun event(action: AnAction) = TestActionEvent.createTestEvent(
            action, SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(PlatformCoreDataKeys.CONTEXT_COMPONENT, panel.tree).build(),
        )
        val toolbar = ActionManager.getInstance().getAction("Ansibility.RunMoleculeTests")
        assertTrue(event(toolbar).also { toolbar.update(it) }.presentation.isEnabledAndVisible)

        // Nothing selected: every role with scenarios in scope, after a confirmation.
        TestDialogManager.setTestDialog(TestDialog.NO)
        toolbar.actionPerformed(event(toolbar))
        assertTrue("cancelled", batches.isEmpty())
        TestDialogManager.setTestDialog(TestDialog.OK)
        toolbar.actionPerformed(event(toolbar))
        assertEquals(setOf(falconWeb, ternWeb), dirs(batches.single()))
        inScope(tern) {
            toolbar.actionPerformed(event(toolbar))
            assertEquals("with nothing selected, the roles of the roots in scope (one: a run of its own)", listOf(MoleculeSpec(ternWeb)), singles)
        }
        batches.clear()
        singles.clear()

        // Two copies selected (Cmd-click): the toolbar and the context menu run just those, without asking.
        val web = child(tab(TreeView.ROLES), "web")
        select(panel, child(web, "falcon"), child(web, "tern"))
        assertEquals(2, panel.selectedNodes().size)
        TestDialogManager.setTestDialog(TestDialog.NO)
        toolbar.actionPerformed(event(toolbar))
        assertEquals(setOf(falconWeb, ternWeb), dirs(batches.last()))
        val menu = ActionManager.getInstance().getAction("Ansibility.RunMoleculeTest")
        assertEquals("Run Molecule Tests (2 Roles)", event(menu).also { menu.update(it) }.presentation.text)
        menu.actionPerformed(event(menu))
        assertEquals(2, batches.size)

        // One copy: a run of its own.
        select(panel, child(web, "tern"))
        assertEquals("Run Molecule Test on 'web'", event(menu).also { menu.update(it) }.presentation.text)
        menu.actionPerformed(event(menu))
        assertEquals(listOf(MoleculeSpec(ternWeb)), singles)

        val environments = AnsibleToolWindowPanel(project, TreeView.ENVIRONMENTS).also { Disposer.register(testRootDisposable, it) }
        val hidden = TestActionEvent.createTestEvent(toolbar, SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(PlatformCoreDataKeys.CONTEXT_COMPONENT, environments.tree).build())
        toolbar.update(hidden)
        assertFalse("not on the Environments tab", hidden.presentation.isEnabledAndVisible)
    }

    /** Selects the rows of [nodes] in [panel], expanding as needed. */
    private fun select(panel: AnsibleToolWindowPanel, vararg nodes: AnsibleTreeNode) {
        panel.tree.clearSelection()
        for (target in nodes) {
            val visitor = TreeVisitor { path: TreePath ->
                val node = TreeUtil.getLastUserObject(AnsibleNodeDescriptor::class.java, path)?.node
                when {
                    node == null -> TreeVisitor.Action.CONTINUE
                    node.key == target.key -> TreeVisitor.Action.INTERRUPT
                    target.key.startsWith(node.key + "/") -> TreeVisitor.Action.CONTINUE
                    else -> TreeVisitor.Action.SKIP_CHILDREN
                }
            }
            val path = PlatformTestUtil.waitForPromise(TreeUtil.promiseMakeVisible(panel.tree, visitor), 30_000L)!!
            panel.tree.addSelectionPath(path)
        }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }
}
