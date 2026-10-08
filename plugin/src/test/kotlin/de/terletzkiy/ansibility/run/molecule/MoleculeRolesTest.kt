package de.terletzkiy.ansibility.run.molecule

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.ui.tree.TreeVisitor
import com.intellij.util.ui.tree.TreeUtil
import de.terletzkiy.ansibility.api.ScopeChoice
import de.terletzkiy.ansibility.api.WorkspaceScope
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

/**
 * Molecule in the Ansibility tool window (plan amendments R16, R19): role markers, which roles a selection tests (a
 * role-name row: its copies in the scope picker's roots, D143), the buttons.
 */
class MoleculeRolesTest : VaultTestCase() {
    private lateinit var falcon: String
    private lateinit var tern: String
    private lateinit var heron: String
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
        // A third repo without a copy of web and without Molecule tests.
        heron = projectRoot("heron")
        write("$heron/roles/cache/tasks/main.yml", "- name: One\n  ansible.builtin.debug: {}\n")
        root(falcon)
        root(tern)
        root(heron)
    }

    override fun tearDown() {
        try {
            WorkspaceScopeService.getInstance(project).set(ScopeChoice.AllRoots)
            MoleculeResults.getInstance(project).resetForTests()
            MoleculeLauncher.executeForTests = null
            MoleculeBatchLauncher.executeForTests = null
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun tab(view: TreeView) = WorkspaceNode(runReadActionBlocking { WorkspaceSnapshotBuilder.build(project) }, view)

    private fun children(node: AnsibleTreeNode): List<AnsibleTreeNode> = runReadActionBlocking { node.children(TreeContext.NONE) }

    private fun child(node: AnsibleTreeNode, name: String): AnsibleTreeNode = children(node).first { it.presentation().name == name }

    private fun scope(): WorkspaceScope = WorkspaceScopeService.getInstance(project).current()

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

    private fun rolesPanel(): AnsibleToolWindowPanel {
        val panel = AnsibleToolWindowPanel(project, TreeView.ROLES).also { Disposer.register(testRootDisposable, it) }
        PlatformTestUtil.waitWithEventsDispatching("the tool window did not load", { panel.refreshCount > 0 }, 30)
        PlatformTestUtil.waitWhileBusy(panel.tree)
        return panel
    }

    /** An event from the rows of [panel]. */
    private fun event(action: AnAction, panel: AnsibleToolWindowPanel) = TestActionEvent.createTestEvent(
        action, SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(PlatformCoreDataKeys.CONTEXT_COMPONENT, panel.tree).build(),
    )

    /** The messages of the info dialogs shown from now on (answered with OK). */
    private fun infoMessages(): List<String> {
        val shown = ArrayList<String>()
        TestDialogManager.setTestDialog(TestDialog { message ->
            shown += message
            Messages.OK
        })
        return shown
    }

    fun testRolesWithScenariosShowTheirLastResult() {
        val web = child(tab(TreeView.ROLES), "web") as RoleNameNode
        assertEquals(NodeMarker.TESTS, web.presentation().marker)
        assertNull("no scenarios", child(tab(TreeView.ROLES), "db").presentation().marker)
        val results = MoleculeResults.getInstance(project)
        results.finished(MoleculeSpec(falconWeb), emptyList(), 0)
        results.finished(MoleculeSpec(ternWeb), emptyList(), 1)
        // R19/D143 (superseding R17/D122's "all its copies, whatever the scope"): the marker sums the copies in scope.
        assertEquals("All roots: every copy, the worst first", NodeMarker.TESTS_FAILED, web.presentation().marker)
        assertEquals(
            mapOf("falcon" to NodeMarker.TESTS_PASSED, "tern" to NodeMarker.TESTS_FAILED),
            children(web).associate { it.presentation().name to it.presentation().marker },
        )
        inScope(falcon) { assertEquals("a role row sums up its copies in scope", NodeMarker.TESTS_PASSED, web.presentation().marker) }
        inScope(tern) { assertEquals(NodeMarker.TESTS_FAILED, web.presentation().marker) }
        inScope(heron) { assertNull("no copy in scope, no marker", web.presentation().marker) }
        results.started(MoleculeSpec(ternWeb))
        assertEquals(NodeMarker.TESTS_RUNNING, web.presentation().marker)
        inScope(falcon) { assertEquals("a run outside the scope does not show", NodeMarker.TESTS_PASSED, web.presentation().marker) }
        assertSame(AnsibilityToolWindowIcons.Molecule, AnsibilityToolWindowIcons.of(NodeMarker.TESTS))
    }

    fun testARoleRowSaysHowManyOfItsCopiesAreInScope() {
        val web = child(tab(TreeView.ROLES), "web")
        assertEquals("All roots: no count", "web  2 copies · falcon, tern", web.presentation().text)
        inScope(falcon) { assertEquals("web  2 copies · falcon, tern · 1 in scope", web.presentation().text) }
        inScope(heron) { assertEquals("web  2 copies · falcon, tern · none in scope", web.presentation().text) }
        assertEquals("every copy of a role in scope: no count", "cache  1 copy · heron", inScope(heron) { child(tab(TreeView.ROLES), "cache").presentation().text })
    }

    fun testTheSelectionPicksTheRolesToTest() {
        val web = child(tab(TreeView.ROLES), "web")
        assertEquals("All roots: a role row runs every copy", setOf(falconWeb, ternWeb), dirs(MoleculeTargets.ofNodes(listOf(web), scope())))
        inScope(falcon) {
            // R19/D143 replaces R17/D122's "the scope does not narrow a role row".
            assertEquals("the scope narrows a role row", setOf(falconWeb), dirs(MoleculeTargets.ofNodes(listOf(web), scope())))
            val ternCopy = child(web, "tern")
            assertEquals("a copy stands for itself", setOf(ternWeb), dirs(MoleculeTargets.ofNodes(listOf(ternCopy), scope())))
            assertEquals("a role row and a copy outside the scope: both, each once", listOf(falconWeb, ternWeb),
                MoleculeTargets.ofNodes(listOf(web, ternCopy, child(web, "falcon")), scope()).map { it.spec.roleDir })
            val snapshot = runReadActionBlocking { WorkspaceSnapshotBuilder.build(project) }
            assertEquals(setOf(falconWeb), dirs(MoleculeTargets.inScope(snapshot, scope())))
        }
        inScope(heron) {
            assertTrue("no copy in scope: nothing, no fallback to every copy", MoleculeTargets.ofNodes(listOf(web), scope()).isEmpty())
            assertEquals("explicit picks stand for themselves whatever the scope", setOf(falconWeb),
                dirs(MoleculeTargets.ofNodes(listOf(child(tab(TreeView.REPOS), "falcon")), scope())))
        }
        assertTrue("no scenarios, nothing to test", MoleculeTargets.ofNodes(listOf(child(tab(TreeView.ROLES), "db")), scope()).isEmpty())

        val falconRoot = child(tab(TreeView.REPOS), "falcon")
        val roles = children(falconRoot).filterIsInstance<RolesNode>().single()
        assertEquals("a repo's Roles folder: its roles with scenarios", listOf(falconWeb), MoleculeTargets.ofNodes(listOf(roles), scope()).map { it.spec.roleDir })
        assertEquals("a repo: the same", listOf(falconWeb), MoleculeTargets.ofNodes(listOf(falconRoot), scope()).map { it.spec.roleDir })
        val copy = child(roles, "web")
        val scenario = child(child(copy, "molecule"), "default")
        assertEquals(listOf(falconWeb to "default"), MoleculeTargets.ofNodes(listOf(scenario), scope()).map { it.spec.roleDir to it.spec.scenario })
        assertEquals("the whole role covers its scenario", listOf(falconWeb to ""), MoleculeTargets.ofNodes(listOf(scenario, copy), scope()).map { it.spec.roleDir to it.spec.scenario })
        assertEquals(listOf("web" to "falcon"), MoleculeTargets.ofNodes(listOf(copy), scope()).map { it.name to it.label })
    }

    fun testTheButtonsRunTheSelectionOrEveryRoleInScope() {
        val batches = ArrayList<List<MoleculeTarget>>()
        MoleculeBatchLauncher.executeForTests = { batches += it }
        val singles = ArrayList<MoleculeSpec>()
        MoleculeLauncher.executeForTests = { settings, _ -> singles += (settings.configuration as MoleculeConfiguration).spec }
        val panel = rolesPanel()
        val toolbar = ActionManager.getInstance().getAction("Ansibility.RunMoleculeTests")
        assertTrue(event(toolbar, panel).also { toolbar.update(it) }.presentation.isEnabledAndVisible)

        // Nothing selected: every role with scenarios in scope, after a confirmation.
        TestDialogManager.setTestDialog(TestDialog.NO)
        toolbar.actionPerformed(event(toolbar, panel))
        assertTrue("cancelled", batches.isEmpty())
        TestDialogManager.setTestDialog(TestDialog.OK)
        toolbar.actionPerformed(event(toolbar, panel))
        assertEquals(setOf(falconWeb, ternWeb), dirs(batches.single()))
        inScope(tern) {
            toolbar.actionPerformed(event(toolbar, panel))
            assertEquals("with nothing selected, the roles of the roots in scope (one: a run of its own)", listOf(MoleculeSpec(ternWeb)), singles)
        }
        batches.clear()
        singles.clear()

        // Two copies selected (Cmd-click): the toolbar and the context menu run just those, without asking.
        val web = child(tab(TreeView.ROLES), "web")
        select(panel, child(web, "falcon"), child(web, "tern"))
        assertEquals(2, panel.selectedNodes().size)
        TestDialogManager.setTestDialog(TestDialog.NO)
        toolbar.actionPerformed(event(toolbar, panel))
        assertEquals(setOf(falconWeb, ternWeb), dirs(batches.last()))
        val menu = ActionManager.getInstance().getAction("Ansibility.RunMoleculeTest")
        assertEquals("Run Molecule Tests (2 Roles)", event(menu, panel).also { menu.update(it) }.presentation.text)
        menu.actionPerformed(event(menu, panel))
        assertEquals(2, batches.size)

        // One copy: a run of its own.
        select(panel, child(web, "tern"))
        assertEquals("Run Molecule Test on 'web'", event(menu, panel).also { menu.update(it) }.presentation.text)
        menu.actionPerformed(event(menu, panel))
        assertEquals(listOf(MoleculeSpec(ternWeb)), singles)
        singles.clear()

        // The role-name row in scope tern: its tern copy, named in the menu; the toolbar runs the same without asking.
        select(panel, web)
        inScope(tern) {
            assertEquals("Run Molecule Test on 'web' (tern)", event(menu, panel).also { menu.update(it) }.presentation.text)
            menu.actionPerformed(event(menu, panel))
            toolbar.actionPerformed(event(toolbar, panel))
        }
        assertEquals(listOf(MoleculeSpec(ternWeb), MoleculeSpec(ternWeb)), singles)
        assertEquals("All roots: both copies", "Run Molecule Tests (2 Roles)", event(menu, panel).also { menu.update(it) }.presentation.text)
        inScope(heron) { assertFalse("no copy in scope: no entry", event(menu, panel).also { menu.update(it) }.presentation.isEnabledAndVisible) }

        val environments = AnsibleToolWindowPanel(project, TreeView.ENVIRONMENTS).also { Disposer.register(testRootDisposable, it) }
        val hidden = event(toolbar, environments)
        toolbar.update(hidden)
        assertFalse("not on the Environments tab", hidden.presentation.isEnabledAndVisible)
    }

    fun testASelectionWithoutTestsInScopeRunsNothing() {
        val batches = ArrayList<List<MoleculeTarget>>()
        MoleculeBatchLauncher.executeForTests = { batches += it }
        val singles = ArrayList<MoleculeSpec>()
        MoleculeLauncher.executeForTests = { settings, _ -> singles += (settings.configuration as MoleculeConfiguration).spec }
        write("$falcon/site.yml", "- name: Site\n  hosts: all\n  roles: [web]\n")
        root(falcon)
        val panel = rolesPanel()
        val toolbar = ActionManager.getInstance().getAction("Ansibility.RunMoleculeTests")
        val shown = infoMessages()
        val clear = "clear the selection (Ctrl-click the selected rows, Cmd-click on macOS)."

        // Before R19 the db row (no scenarios) made the toolbar run every role in scope.
        select(panel, child(tab(TreeView.ROLES), "db"))
        toolbar.actionPerformed(event(toolbar, panel))
        assertEquals(
            "a role without tests anywhere: no word about copies outside the scope",
            "The selection has no Molecule tests. To test every role with Molecule scenarios in the scope 'All roots', $clear", shown.last(),
        )

        // The web row in a scope without a copy of web: its tests lie outside the scope.
        select(panel, child(tab(TreeView.ROLES), "web"))
        inScope(heron) { toolbar.actionPerformed(event(toolbar, panel)) }
        assertEquals(
            "The selected role row has Molecule tests only outside the scope 'heron'. A role row runs only its copies in the scope: " +
                "expand it and select a copy to test that one. To test every role with Molecule scenarios in the scope, $clear",
            shown.last(),
        )
        // With the db row as well: the web row still explains it.
        select(panel, child(tab(TreeView.ROLES), "db"), child(tab(TreeView.ROLES), "web"))
        inScope(heron) { toolbar.actionPerformed(event(toolbar, panel)) }
        assertTrue(shown.last(), shown.last().startsWith("The selected role row has Molecule tests only outside the scope 'heron'."))

        // The Repos tab: a playbook row is no role row.
        val repos = AnsibleToolWindowPanel(project, TreeView.REPOS).also { Disposer.register(testRootDisposable, it) }
        PlatformTestUtil.waitWithEventsDispatching("the Repos tab did not load", { repos.refreshCount > 0 }, 30)
        val falconRoot = child(tab(TreeView.REPOS), "falcon")
        select(repos, child(children(falconRoot).first { it.presentation().name == "Playbooks" }, "site.yml"))
        toolbar.actionPerformed(event(toolbar, repos))
        assertEquals("The selection has no Molecule tests. To test every role with Molecule scenarios in the scope 'All roots', $clear", shown.last())
        assertEquals(4, shown.size)
        assertTrue("nothing ran", batches.isEmpty() && singles.isEmpty())

        // Nothing selected in a scope without tests (the button hides then; a click in the meantime says so).
        repos.tree.clearSelection()
        inScope(heron) { toolbar.actionPerformed(event(toolbar, repos)) }
        assertEquals("No role in the scope 'heron' has Molecule scenarios (molecule/<scenario>/molecule.yml).", shown.last())
        assertTrue("nothing ran", batches.isEmpty() && singles.isEmpty())
    }

    fun testTheRolesTabFollowsTheScopePicker() {
        val panel = rolesPanel()
        fun webRow(): String {
            val path = (0 until panel.tree.rowCount).map { panel.tree.getPathForRow(it) }
                .first { (TreeUtil.getLastUserObject(AnsibleNodeDescriptor::class.java, it)?.node as? RoleNameNode)?.name == "web" }
            return TreeUtil.getLastUserObject(AnsibleNodeDescriptor::class.java, path)!!.presentation.coloredText.joinToString("") { it.text }
        }
        assertEquals("web  2 copies · falcon, tern", webRow())
        val service = WorkspaceScopeService.getInstance(project)
        service.set(ScopeChoice.Roots(setOf(RootKeys.keyOf(project, vf(falcon)))))
        PlatformTestUtil.waitWithEventsDispatching("the row did not follow the scope: ${webRow()}", { webRow().endsWith("· 1 in scope") }, 30)
        service.set(ScopeChoice.AllRoots)
        PlatformTestUtil.waitWithEventsDispatching("the row did not follow the scope back: ${webRow()}", { webRow() == "web  2 copies · falcon, tern" }, 30)
    }

    fun testAScopeChangeReRendersOnlyTheRolesTab() {
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        // Nothing selected: a re-render would show the (empty) details again at once, which the counts would tell.
        val others = listOf(TreeView.REPOS, TreeView.ENVIRONMENTS).map { view ->
            AnsibleToolWindowPanel(project, view).also { Disposer.register(testRootDisposable, it) }
        }
        for (panel in others) {
            PlatformTestUtil.waitWithEventsDispatching("the ${panel.view} tab did not load", { panel.refreshCount > 0 }, 30)
            PlatformTestUtil.waitWhileBusy(panel.tree)
        }
        val repos = others.first()
        PlatformTestUtil.waitWithEventsDispatching("the Repos tab did not expand", { repos.tree.rowCount > 3 }, 30)
        PlatformTestUtil.waitWhileBusy(repos.tree)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        val before = others.map { it.detailsCount }

        val roles = rolesPanel()
        val web = { rendered(roles, "web") }
        WorkspaceScopeService.getInstance(project).set(ScopeChoice.Roots(setOf(RootKeys.keyOf(project, vf(falcon)))))
        PlatformTestUtil.waitWithEventsDispatching("the Roles tab did not follow the scope: ${web()}", { web().endsWith("· 1 in scope") }, 30)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals("the Repos and Environments tabs read no scope: no re-render, no details", before, others.map { it.detailsCount })
    }

    /** The rendered text of the role-name row [name] in [panel]. */
    private fun rendered(panel: AnsibleToolWindowPanel, name: String): String {
        val path = (0 until panel.tree.rowCount).map { panel.tree.getPathForRow(it) }
            .first { (TreeUtil.getLastUserObject(AnsibleNodeDescriptor::class.java, it)?.node as? RoleNameNode)?.name == name }
        return TreeUtil.getLastUserObject(AnsibleNodeDescriptor::class.java, path)!!.presentation.coloredText.joinToString("") { it.text }
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
