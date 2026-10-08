package de.terletzkiy.ansibility.run.molecule

import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings as PlatformRunnerSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.ModificationTracker
import com.intellij.packageDependencies.DependencyValidationManager
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.search.scope.packageSet.NamedScope
import com.intellij.psi.search.scope.packageSet.PackageSetFactory
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.replaceService
import com.intellij.ui.tree.TreeVisitor
import com.intellij.util.ui.tree.TreeUtil
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.api.ScopeChoice
import de.terletzkiy.ansibility.api.WorkspaceScope
import de.terletzkiy.ansibility.api.WorkspaceScopeService
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.MoleculeSettings
import de.terletzkiy.ansibility.settings.ProjectSettings
import de.terletzkiy.ansibility.settings.RootKeys
import de.terletzkiy.ansibility.toolwindow.AnsibleNodeDescriptor
import de.terletzkiy.ansibility.toolwindow.AnsibleToolWindowPanel
import de.terletzkiy.ansibility.toolwindow.model.AnsibleTreeNode
import de.terletzkiy.ansibility.toolwindow.model.RoleNameNode
import de.terletzkiy.ansibility.toolwindow.model.RootSnapshot
import de.terletzkiy.ansibility.toolwindow.model.TreeContext
import de.terletzkiy.ansibility.toolwindow.model.TreeView
import de.terletzkiy.ansibility.toolwindow.model.WorkspaceNode
import de.terletzkiy.ansibility.toolwindow.model.WorkspaceSnapshot
import de.terletzkiy.ansibility.toolwindow.model.WorkspaceSnapshotBuilder
import de.terletzkiy.ansibility.toolwindow.model.rootsKnown
import de.terletzkiy.ansibility.vault.VaultTestCase
import de.terletzkiy.ansibility.workspace.ScopeCoverage
import de.terletzkiy.ansibility.workspace.ScopeMembership
import de.terletzkiy.ansibility.workspace.WorkspaceScopeImpl
import java.nio.file.Files
import java.util.Collections

/**
 * When the Molecule run UI shows (plan amendment R19, D140): the snapshot knows which roles have tests, the toolbar's
 * Run Molecule Tests follows the scope and the scenarios as they come and go, "Run Molecule tests" off hides it all
 * (R20, D152: the navigation switch does not), and a scenario file gets its ▶ only next to a `molecule.yml`.
 */
class MoleculeVisibilityTest : VaultTestCase() {
    private object TestRunner : ProgramRunner<PlatformRunnerSettings> {
        override fun getRunnerId(): String = "AnsibilityMoleculeVisibilityTest"
        override fun canRun(executorId: String, profile: RunProfile): Boolean = true
        override fun execute(environment: ExecutionEnvironment) = Unit
    }

    private lateinit var falcon: String
    private lateinit var tern: String
    private val nested: String get() = "$falcon/maintenance"
    private val runTests: AnAction get() = ActionManager.getInstance().getAction("Ansibility.RunMoleculeTests")
    private var scopesBefore: Array<NamedScope> = emptyArray()

    override fun setUp() {
        super.setUp()
        falcon = projectRoot("falcon")
        tern = projectRoot("tern")
        write("$falcon/roles/web/tasks/main.yml", TASKS)
        write("$falcon/roles/web/molecule/default/molecule.yml", CONFIG)
        write("$falcon/roles/web/molecule/default/converge.yml", "- name: Converge\n  hosts: all\n  roles: [web]\n")
        // A folder of `molecule` without a molecule.yml is no scenario.
        write("$falcon/roles/web/molecule/orphan/converge.yml", "- name: Converge\n  hosts: all\n  roles: [web]\n")
        write("$falcon/roles/web/molecule/orphan/verify.yml", "- name: Verify\n  hosts: all\n  tasks: []\n")
        write("$falcon/roles/db/tasks/main.yml", TASKS)
        write("$falcon/roles/db/molecule/notes/README.md", "no scenario here\n")
        // A nested playbook root whose own role has no tests (its parent's web has).
        write("$nested/playbook-database.yml", "- name: Database\n  hosts: all\n  roles: [dbsetup]\n")
        write("$nested/roles/dbsetup/tasks/main.yml", TASKS)
        write("$tern/roles/db/tasks/main.yml", TASKS)
        // A role library whose role has a scenario.
        write("golden/roles/lib/tasks/main.yml", TASKS)
        write("golden/roles/lib/molecule/default/molecule.yml", CONFIG)
        root(falcon)
        root(tern)
        scopesBefore = DependencyValidationManager.getInstance(project).editableScopes
    }

    override fun tearDown() {
        try {
            WorkspaceScopeService.getInstance(project).set(ScopeChoice.AllRoots)
            DependencyValidationManager.getInstance(project).scopes = scopesBefore
            AnsibilityProjectSettings.getInstance(project).update { it.copy(molecule = MoleculeSettings()) }
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

    private fun snapshot(): WorkspaceSnapshot = runReadActionBlocking { WorkspaceSnapshotBuilder.build(project) }

    private fun rootOf(snapshot: WorkspaceSnapshot, relative: String): RootSnapshot = snapshot.root(vf(relative)) ?: error("no root $relative")

    private fun scope(): WorkspaceScope = WorkspaceScopeService.getInstance(project).current()

    private fun roots(vararg relative: String) = ScopeChoice.Roots(relative.map { RootKeys.keyOf(project, vf(it)) }.toSet())

    private fun panel(view: TreeView): AnsibleToolWindowPanel {
        val panel = AnsibleToolWindowPanel(project, view).also { Disposer.register(testRootDisposable, it) }
        PlatformTestUtil.waitWithEventsDispatching("the tool window did not load", { panel.refreshCount > 0 }, 30)
        PlatformTestUtil.waitWhileBusy(panel.tree)
        return panel
    }

    /** What the toolbar's update says about Run Molecule Tests in [panel]. */
    private fun offered(panel: AnsibleToolWindowPanel): Boolean = event(runTests, panel).also { runTests.update(it) }.presentation.isEnabledAndVisible

    /** Whether the toolbar of [panel] shows Run Molecule Tests now, after its own last update (none is asked for here). */
    private fun shown(panel: AnsibleToolWindowPanel): Boolean = panel.actionToolbar.actions.any { it === runTests }

    private fun event(action: AnAction, panel: AnsibleToolWindowPanel) = TestActionEvent.createTestEvent(
        action, SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(PlatformCoreDataKeys.CONTEXT_COMPONENT, panel.tree).build(),
    )

    private fun fileEvent(action: AnAction, relative: String) = TestActionEvent.createTestEvent(
        action, SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.VIRTUAL_FILE, vf(relative)).build(),
    )

    /** Changes the tree with [change], rescans and waits for [panel]'s next snapshot. */
    private fun changeAndWait(panel: AnsibleToolWindowPanel, change: () -> Unit) {
        val before = panel.refreshCount
        change()
        refresh()
        PlatformTestUtil.waitWithEventsDispatching("no new snapshot", { panel.refreshCount > before }, 30)
    }

    private fun children(node: AnsibleTreeNode): List<AnsibleTreeNode> = runReadActionBlocking { node.children(TreeContext.NONE) }

    private fun child(node: AnsibleTreeNode, name: String): AnsibleTreeNode = children(node).first { it.presentation().name == name }

    fun testTheSnapshotKnowsWhichRolesHaveTests() {
        val snapshot = snapshot()
        val falconRoot = rootOf(snapshot, falcon)
        assertEquals("only a role with molecule/<s>/molecule.yml", setOf(vf("$falcon/roles/web")), falconRoot.testedRoles)
        assertTrue(snapshot.hasTestedRoles(falconRoot))
        assertFalse(snapshot.hasTestedRoles(rootOf(snapshot, tern)))
        val nestedRoot = rootOf(snapshot, nested)
        assertEquals(RootKind.NESTED_PLAYBOOK, nestedRoot.kind)
        assertFalse("a nested root's own role has no tests", snapshot.hasTestedRoles(nestedRoot))
        val golden = rootOf(snapshot, "golden")
        assertEquals(RootKind.ROLE_LIBRARY, golden.kind)
        assertEquals("a role library's roles count", setOf(vf("golden/roles/lib")), golden.testedRoles)
    }

    fun testTheButtonAndARunOfTheScopeAgree() {
        val snapshot = snapshot()
        val service = WorkspaceScopeService.getInstance(project)
        for (choice in listOf(ScopeChoice.AllRoots, roots(falcon), roots(tern), roots("golden"), roots(nested), ScopeChoice.Roots(emptySet()))) {
            service.set(choice)
            val runs = MoleculeTargets.inScope(snapshot, scope())
            assertEquals(choice.toString(), runs.isNotEmpty(), MoleculeTargets.anyInScope(snapshot, scope()))
            assertTrue("known without a walk", scope().rootsKnown())
        }
        service.set(roots(tern, nested))
        assertFalse("roots without tests", MoleculeTargets.anyInScope(snapshot, scope()))
        service.set(roots(falcon))
        assertEquals(listOf(vf("$falcon/roles/web").path), MoleculeTargets.inScope(snapshot, scope()).map { it.spec.roleDir })
    }

    fun testACopyBelongsToTheRootThatContainsItWhoeverElseListsIt() {
        // falcon's roles_path makes the nested root list falcon's roles too; tern's reaches the role library's.
        write("$falcon/ansible.cfg", "[defaults]\nroles_path = roles\n")
        write("$tern/ansible.cfg", "[defaults]\nroles_path = roles:../../../golden/roles\n")
        root(falcon)
        root(tern)
        val snapshot = snapshot()
        val nestedRoot = rootOf(snapshot, nested)
        val ternRoot = rootOf(snapshot, tern)
        assertTrue("the nested root lists falcon's web", vf("$falcon/roles/web") in nestedRoot.testedRoles)
        assertTrue("tern lists the library's lib", vf("golden/roles/lib") in ternRoot.testedRoles)
        assertFalse("but neither belongs to it", snapshot.hasTestedRoles(nestedRoot) || snapshot.hasTestedRoles(ternRoot))

        val owners = snapshot.roleCopies.associate { (root, role) -> role.dir to root.root.displayName }
        assertEquals("falcon's web is falcon's, not the deeper nested root's", "falcon", owners[vf("$falcon/roles/web")])
        assertEquals("the library's lib is the library's, not tern's", "golden", owners[vf("golden/roles/lib")])
        assertEquals("the nested root keeps its own role", rootOf(snapshot, nested).root.displayName, owners[vf("$nested/roles/dbsetup")])
        val rows = children(WorkspaceNode(snapshot, TreeView.ROLES)).associateBy { it.presentation().name }
        assertEquals(listOf("falcon"), children(rows.getValue("web")).map { it.presentation().name })
        assertEquals(listOf("golden"), children(rows.getValue("lib")).map { it.presentation().name })

        // The role-name rows and the toolbar pick by the same root: they agree in every scope.
        val service = WorkspaceScopeService.getInstance(project)
        for (choice in listOf(ScopeChoice.AllRoots, roots(falcon), roots(nested), roots(tern), roots("golden"), roots(tern, nested))) {
            service.set(choice)
            val fromRows = rows.values.filterIsInstance<RoleNameNode>().flatMap { MoleculeTargets.ofNodes(listOf(it), scope()) }
            val fromToolbar = MoleculeTargets.inScope(snapshot, scope())
            assertEquals(choice.toString(), fromToolbar.map { it.spec.roleDir }.toSet(), fromRows.map { it.spec.roleDir }.toSet())
            assertEquals(choice.toString(), fromToolbar.isNotEmpty(), MoleculeTargets.anyInScope(snapshot, scope()))
        }
        service.set(roots(nested))
        val web = rows.getValue("web")
        assertEquals("web  1 copy · falcon · none in scope", web.presentation().text)
        assertNull("no marker for a copy outside the scope", web.presentation().marker)
        assertFalse("the button hides under the nested root", MoleculeTargets.anyInScope(snapshot, scope()))
        service.set(roots(tern))
        assertFalse("the library's role is not tern's", MoleculeTargets.anyInScope(snapshot, scope()))
        service.set(roots(falcon))
        assertEquals(listOf("web" to "falcon"), MoleculeTargets.inScope(snapshot, scope()).map { it.name to it.label })
    }

    fun testTheButtonShowsOnlyWithTestsInTheScope() {
        val service = WorkspaceScopeService.getInstance(project)
        for (view in listOf(TreeView.REPOS, TreeView.ROLES)) {
            val panel = panel(view)
            assertTrue("$view: All roots", offered(panel))
            service.set(roots(tern))
            assertFalse("$view: a root without scenarios", offered(panel))
            service.set(roots(tern, "golden"))
            assertTrue("$view: a role library's role counts", offered(panel))
            service.set(ScopeChoice.Roots(emptySet()))
            assertFalse("$view: no root", offered(panel))
            service.set(ScopeChoice.AllRoots)
        }
        assertFalse("never on the Environments tab", offered(panel(TreeView.ENVIRONMENTS)))
    }

    fun testACopyOutsideTheScopeStillRunsFromItsRow() {
        val repos = panel(TreeView.REPOS)
        WorkspaceScopeService.getInstance(project).set(roots(tern))
        assertFalse(offered(repos))
        val falconRoot = children(WorkspaceNode(snapshot())).first { it.presentation().name == "falcon" }
        select(repos, child(children(falconRoot).first { it.presentation().name.startsWith("Roles") }, "web"))
        val menu = ActionManager.getInstance().getAction("Ansibility.RunMoleculeTest")
        val presentation = event(menu, repos).also { menu.update(it) }.presentation
        assertTrue("the selection decides, not the scope", presentation.isEnabledAndVisible)
        assertEquals("Run Molecule Test on 'web'", presentation.text)
    }

    fun testTheButtonFollowsScenariosAsTheyComeAndGo() {
        // The real toolbar finds its panel through the tree's data context, which the headless data manager lacks.
        HeadlessDataManager.fallbackToProductionDataManager(testRootDisposable)
        WorkspaceScopeService.getInstance(project).set(roots(tern))
        val panel = panel(TreeView.ROLES)
        PlatformTestUtil.waitWithEventsDispatching("the toolbar did not update", { panel.actionToolbar.actions.isNotEmpty() }, 30)
        assertFalse(offered(panel))
        assertFalse(shown(panel))

        changeAndWait(panel) { write("$tern/roles/db/molecule/default/converge.yml", "- name: Converge\n  hosts: all\n  roles: [db]\n") }
        assertFalse("a scenario folder without molecule.yml", offered(panel))

        changeAndWait(panel) { write("$tern/roles/db/molecule/default/molecule.yml", CONFIG) }
        PlatformTestUtil.waitWithEventsDispatching("a scenario appeared, the update did not offer the button", { offered(panel) }, 30)
        // The panel updates its toolbar after the snapshot: the button shows without a mouse move.
        PlatformTestUtil.waitWithEventsDispatching("the toolbar did not show the button", { shown(panel) }, 30)

        changeAndWait(panel) { Files.delete(base.resolve("$tern/roles/db/molecule/default/molecule.yml")) }
        PlatformTestUtil.waitWithEventsDispatching("the scenario is gone, the update still offers the button", { !offered(panel) }, 30)
        PlatformTestUtil.waitWithEventsDispatching("the toolbar still shows the button", { !shown(panel) }, 30)

        // A scope change updates the toolbar too.
        WorkspaceScopeService.getInstance(project).set(ScopeChoice.AllRoots)
        PlatformTestUtil.waitWithEventsDispatching("the toolbar did not follow the scope", { shown(panel) }, 30)
    }

    fun testTheButtonFollowsANamedScopeOnceItsRootsAreKnown() {
        HeadlessDataManager.fallbackToProductionDataManager(testRootDisposable)
        // A named scope over tern (no Molecule tests) whose roots the scope service is still working out, and which
        // publishes nothing when it is done.
        val (scope, walks) = namedScopeOver(tern)
        replaceScopeService(scope)
        val panel = panel(TreeView.REPOS)
        PlatformTestUtil.waitWithEventsDispatching("the roots were never worked out", { walks.isNotEmpty() }, 30)
        PlatformTestUtil.waitWithEventsDispatching("the toolbar kept the button of the unknown scope", { panel.actionToolbar.actions.isNotEmpty() && !shown(panel) }, 30)
        assertFalse(offered(panel))
        assertTrue("worked out off the EDT", walks.none { it })
    }

    fun testWithoutRolesWithTestsTheToolbarWorksOutNoScope() {
        HeadlessDataManager.fallbackToProductionDataManager(testRootDisposable)
        // Run Molecule tests off: no role has tests, so the button hides whatever the scope covers.
        AnsibilityProjectSettings.getInstance(project).update { it.copy(molecule = it.molecule.copy(runTests = false)) }
        val (scope, walks) = namedScopeOver(tern)
        replaceScopeService(scope)
        val panel = panel(TreeView.REPOS)
        PlatformTestUtil.waitWithEventsDispatching("the toolbar did not update", { panel.actionToolbar.actions.isNotEmpty() }, 30)
        assertFalse(shown(panel))
        // A walk would start right after the snapshot; give it time to show.
        val until = System.currentTimeMillis() + 500
        while (System.currentTimeMillis() < until) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }
        assertTrue("nothing to decide, nothing walked", walks.isEmpty())
    }

    fun testANamedScopeRunsTheCopiesItCovers() {
        val singles = ArrayList<MoleculeSpec>()
        MoleculeLauncher.executeForTests = { settings, _ -> singles += (settings.configuration as MoleculeConfiguration).spec }
        write("$tern/roles/web/tasks/main.yml", TASKS)
        write("$tern/roles/web/molecule/default/molecule.yml", CONFIG)
        refresh()
        val panel = panel(TreeView.ROLES)
        val pattern = "file:${vf(tern).path.removePrefix(vf("").path).trimStart('/')}//*"
        DependencyValidationManager.getInstance(project).addScope(NamedScope("tern-only", PackageSetFactory.getInstance().compile(pattern)))
        WorkspaceScopeService.getInstance(project).set(ScopeChoice.Named("tern-only"))
        select(panel, children(WorkspaceNode(snapshot(), TreeView.ROLES)).first { it.presentation().name == "web" })
        // The scope's roots may still be worked out in the background: the run waits for them, off the EDT.
        runTests.actionPerformed(event(runTests, panel))
        PlatformTestUtil.waitWithEventsDispatching("nothing ran", { singles.isNotEmpty() }, 30)
        assertEquals("the role row's copy in the named scope", listOf(MoleculeSpec(vf("$tern/roles/web").path)), singles)
    }

    fun testAScopeStillBeingComputedIsNeverWalkedOnTheEdt() {
        val singles = ArrayList<MoleculeSpec>()
        MoleculeLauncher.executeForTests = { settings, _ -> singles += (settings.configuration as MoleculeConfiguration).spec }
        write("$tern/roles/web/tasks/main.yml", TASKS)
        write("$tern/roles/web/molecule/default/molecule.yml", CONFIG)
        refresh()
        // The panel opens under All roots: it has nothing to work out, so the run below is the only walk.
        val panel = panel(TreeView.REPOS)
        val (scope, walks) = namedScopeOver(tern)
        replaceScopeService(scope)
        val web = children(WorkspaceNode(snapshot(), TreeView.ROLES)).first { it.presentation().name == "web" }
        assertEquals("on the EDT the row waits for the scope's roots", "web  2 copies · falcon, tern", web.presentation().text)
        assertTrue("the button counts every root meanwhile", MoleculeTargets.anyInScope(snapshot(), scope))
        assertTrue("nothing walked yet", walks.isEmpty())

        // Nothing selected: the run works out the roots in the background, then runs the roles of the scope.
        runTests.actionPerformed(event(runTests, panel))
        PlatformTestUtil.waitWithEventsDispatching("nothing ran", { singles.isNotEmpty() }, 30)
        assertEquals(listOf(MoleculeSpec(vf("$tern/roles/web").path)), singles)
        assertEquals("one walk, off the EDT", listOf(false), walks.toList())
    }

    /**
     * A named scope over [relative] whose coverage is not computed yet, and the threads it was walked on (true: the
     * EDT). Working it out walks the roots' files; it is known afterwards.
     */
    private fun namedScopeOver(relative: String): Pair<WorkspaceScopeImpl, List<Boolean>> {
        val roots = runReadActionBlocking { AnsibleWorkspace.getInstance(project).roots() }.filter { !it.detached }
        val coverage = ScopeCoverage.ofDirectories(roots, listOf(vf(relative)))
        val walks = Collections.synchronizedList(ArrayList<Boolean>())
        val source = object : WorkspaceScopeImpl.CoverageSource {
            @Volatile
            var computed = false

            override fun cached(): ScopeCoverage? = if (computed) coverage else null

            override fun compute(): ScopeCoverage {
                walks += ApplicationManager.getApplication().isDispatchThread
                computed = true
                return coverage
            }
        }
        val scope = WorkspaceScopeImpl(
            project, ScopeChoice.Named("only-$relative"), null, null, ScopeMembership.Everything, source, emptyList(), null, "only-$relative", emptyList(), 0,
        )
        return scope to walks
    }

    /** Replaces the workspace scope service with one whose scope is always [scope]. */
    private fun replaceScopeService(scope: WorkspaceScope) = project.replaceService(
        WorkspaceScopeService::class.java,
        object : WorkspaceScopeService {
            override fun current(): WorkspaceScope = scope
            override fun set(choice: ScopeChoice) = Unit
            override val modificationTracker: ModificationTracker = ModificationTracker.NEVER_CHANGED
        },
        testRootDisposable,
    )

    /** Changes the settings with [change] and waits for [panel]'s next snapshot: no file changes, no rescan. */
    private fun settingsAndWait(panel: AnsibleToolWindowPanel, change: (ProjectSettings) -> ProjectSettings) {
        val before = panel.refreshCount
        AnsibilityProjectSettings.getInstance(project).update(change)
        PlatformTestUtil.waitWithEventsDispatching("no new snapshot after the settings change", { panel.refreshCount > before }, 30)
    }

    /** Whether every Molecule run entry point shows for falcon's web (button, Project view, tool window row, marker, ▶). */
    private fun runUi(panel: AnsibleToolWindowPanel): List<Boolean> {
        val menu = ActionManager.getInstance().getAction("Ansibility.RunMoleculeTest")
        val falconRoot = children(WorkspaceNode(snapshot())).first { it.presentation().name == "falcon" }
        val webCopy = child(children(falconRoot).first { it.presentation().name.startsWith("Roles") }, "web")
        return listOf(
            offered(panel),
            fileEvent(menu, "$falcon/roles/web").also { menu.update(it) }.presentation.isEnabledAndVisible,
            MoleculeTargets.ofNodes(listOf(webCopy), scope()).isNotEmpty(),
            webCopy.presentation().marker != null,
            gutter("$falcon/roles/web/molecule/default/molecule.yml") != null,
            gutter("$falcon/roles/web/molecule/default/converge.yml") != null,
        )
    }

    fun testRunMoleculeTestsOffHidesTheMoleculeRunUi() {
        val panel = panel(TreeView.REPOS)
        assertEquals(List(6) { true }, runUi(panel))

        // Only the run switch hides it (R20, D152), and the change reaches the open panel without a rescan.
        settingsAndWait(panel) { it.copy(molecule = it.molecule.copy(runTests = false)) }
        PlatformTestUtil.waitWithEventsDispatching("the toolbar button", { !offered(panel) }, 30)
        assertEquals("button, Project view, tool window, marker, gutter of molecule.yml and converge.yml", List(6) { false }, runUi(panel))

        settingsAndWait(panel) { it.copy(molecule = it.molecule.copy(runTests = true)) }
        PlatformTestUtil.waitWithEventsDispatching("the toolbar button is back", { offered(panel) }, 30)
        assertEquals(List(6) { true }, runUi(panel))
    }

    fun testASavedMoleculeConfigurationStillRunsWithRunMoleculeTestsOff() {
        // D152: the switch hides the entry points; a saved configuration (Run/Debug list, Rerun) still runs.
        val settings = MoleculeLauncher.settingsFor(project, MoleculeSpec(vf("$falcon/roles/web").path, "default", MoleculeCommand.CONVERGE))
        AnsibilityProjectSettings.getInstance(project).update { it.copy(molecule = it.molecule.copy(runTests = false)) }
        val configuration = settings.configuration as MoleculeConfiguration
        configuration.checkConfiguration()
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        assertTrue(configuration.getState(executor, ExecutionEnvironment(executor, TestRunner, settings, project)) is MoleculeRunState)
    }

    fun testMoleculeTestsRunWhileMoleculeIsHiddenFromNavigation() {
        // The user's case (R20): Molecule out of navigation and search, tests still offered and run.
        val panel = panel(TreeView.REPOS)
        assertEquals("the defaults", MoleculeSettings(runTests = true, showInNavigation = false), AnsibilityProjectSettings.getInstance(project).settings.molecule)
        assertEquals(List(6) { true }, runUi(panel))
        settingsAndWait(panel) { it.copy(molecule = MoleculeSettings(runTests = true, showInNavigation = true)) }
        assertEquals("the navigation switch never touches the run UI", List(6) { true }, runUi(panel))
        settingsAndWait(panel) { it.copy(molecule = MoleculeSettings(runTests = true, showInNavigation = false)) }
        assertEquals(List(6) { true }, runUi(panel))

        val singles = ArrayList<MoleculeSpec>()
        MoleculeLauncher.executeForTests = { settings, _ -> singles += (settings.configuration as MoleculeConfiguration).spec }
        val gutter = gutter("$falcon/roles/web/molecule/default/converge.yml")!!
        gutter.actions.first().actionPerformed(fileEvent(gutter.actions.first(), "$falcon/roles/web/molecule/default/converge.yml"))
        PlatformTestUtil.waitWithEventsDispatching("nothing ran", { singles.isNotEmpty() }, 30)
        assertEquals(listOf(MoleculeSpec(vf("$falcon/roles/web").path, "default", MoleculeCommand.CONVERGE)), singles)
    }

    fun testTheGutterNeedsAMoleculeYmlNextToTheFile() {
        assertNotNull(gutter("$falcon/roles/web/molecule/default/molecule.yml"))
        assertNotNull(gutter("$falcon/roles/web/molecule/default/converge.yml"))
        assertNull("converge.yml without molecule.yml", gutter("$falcon/roles/web/molecule/orphan/converge.yml"))
        assertNull("verify.yml without molecule.yml", gutter("$falcon/roles/web/molecule/orphan/verify.yml"))
    }

    /** Selects the row of [node] in [panel], expanding as needed. */
    private fun select(panel: AnsibleToolWindowPanel, node: AnsibleTreeNode) {
        val visitor = TreeVisitor { path ->
            val visited = TreeUtil.getLastUserObject(AnsibleNodeDescriptor::class.java, path)?.node
            when {
                visited == null -> TreeVisitor.Action.CONTINUE
                visited.key == node.key -> TreeVisitor.Action.INTERRUPT
                node.key.startsWith(visited.key + "/") -> TreeVisitor.Action.CONTINUE
                else -> TreeVisitor.Action.SKIP_CHILDREN
            }
        }
        panel.tree.selectionPath = PlatformTestUtil.waitForPromise(TreeUtil.promiseMakeVisible(panel.tree, visitor), 30_000L)!!
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    /** The ▶ of the Molecule file at [relative], or null. */
    private fun gutter(relative: String): RunLineMarkerContributor.Info? {
        myFixture.configureFromExistingVirtualFile(vf(relative))
        val contributor = MoleculeRunLineMarkerContributor()
        return runReadActionBlocking { SyntaxTraverser.psiTraverser(myFixture.file).filter { it.firstChild == null }.toList().firstNotNullOfOrNull(contributor::getInfo) }
    }

    private companion object {
        const val TASKS = "- name: One\n  ansible.builtin.debug: {}\n"
        const val CONFIG = "---\ndriver:\n  name: default\n"
    }
}
