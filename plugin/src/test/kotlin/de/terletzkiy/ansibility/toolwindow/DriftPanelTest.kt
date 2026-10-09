package de.terletzkiy.ansibility.toolwindow

import com.intellij.ide.DataManager
import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Anchor
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Constraints
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.ShortcutSet
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerOrReplaceServiceInstance
import com.intellij.ui.components.ActionLink
import com.intellij.ui.tree.TreeVisitor
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import de.terletzkiy.ansibility.golden.GoldenDataKeys
import de.terletzkiy.ansibility.golden.GoldenTestSupport
import de.terletzkiy.ansibility.golden.history.LastChange
import de.terletzkiy.ansibility.golden.history.LastChangeLookup
import de.terletzkiy.ansibility.golden.history.LastChanges
import de.terletzkiy.ansibility.model.drift.DriftFixture
import de.terletzkiy.ansibility.model.drift.RoleDriftService
import de.terletzkiy.ansibility.model.role.ModelFixture
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.GoldenRoot
import de.terletzkiy.ansibility.settings.SettingsTestSupport
import de.terletzkiy.ansibility.toolwindow.model.AnsibleTreeNode
import de.terletzkiy.ansibility.toolwindow.model.GoldenActionIds
import de.terletzkiy.ansibility.toolwindow.model.RoleNameNode
import de.terletzkiy.ansibility.toolwindow.model.TreeContext
import de.terletzkiy.ansibility.toolwindow.model.TreeView
import de.terletzkiy.ansibility.toolwindow.model.VariantGroupNode
import de.terletzkiy.ansibility.toolwindow.model.WorkspaceNode
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JButton
import javax.swing.tree.TreePath

/**
 * The drift view in the Swing tool window, headless (plan amendment R24, D177–D181): drift is requested only for a
 * shown Roles tab with a golden root, rows follow the drift events, the header line and its offer, Drifted Only, the
 * data the golden actions get, Enter/F4 on a differing file, the details' links and buttons, and "Use as Golden Root".
 */
class DriftPanelTest : BasePlatformTestCase() {
    private lateinit var service: RoleDriftService
    private val registered = ArrayList<String>()

    /** The plugin's own golden actions, taken out for the test so it controls which ones exist (restored after). */
    private val hidden = LinkedHashMap<String, AnAction>()

    /**
     * The ids in each menu of the golden actions before they were taken out: unregistering an action removes it from
     * every group and registering it again does not put it back, so the menus are restored from this (later tests in
     * the same JVM check where the actions sit).
     */
    private val menus = LinkedHashMap<DefaultActionGroup, List<String?>>()

    override fun getTestDataPath(): String = ModelFixture.testDataPath

    override fun setUp() {
        super.setUp()
        DriftFixture.copy(myFixture)
        service = DriftFixture.freshService(project, testRootDisposable)
        project.registerOrReplaceServiceInstance(RoleDriftService::class.java, service, testRootDisposable)
        val actions = ActionManager.getInstance()
        for (groupId in MENUS) {
            val group = actions.getAction(groupId) as? DefaultActionGroup ?: continue
            menus[group] = group.getChildActionsOrStubs().map(actions::getId)
        }
        for (id in GoldenActionIds.COPY_ACTIONS) {
            val action = actions.getAction(id) ?: continue
            hidden[id] = action
            actions.unregisterAction(id)
        }
    }

    /** Puts each hidden action back into its menus, after the id it followed. */
    private fun restoreMenus() {
        val actions = ActionManager.getInstance()
        for ((group, ids) in menus) {
            for ((index, id) in ids.withIndex()) {
                val action = hidden[id] ?: continue
                val present = group.getChildActionsOrStubs().mapNotNull(actions::getId)
                if (id in present) continue
                val after = ids.subList(0, index).filterNotNull().lastOrNull { it in present }
                group.add(action, if (after != null) Constraints(Anchor.AFTER, after) else Constraints.FIRST)
            }
        }
    }

    override fun tearDown() {
        try {
            registered.forEach(ActionManager.getInstance()::unregisterAction)
            hidden.forEach { (id, action) -> ActionManager.getInstance().registerAction(id, action) }
            restoreMenus()
            FileEditorManager.getInstance(project).openFiles.forEach(FileEditorManager.getInstance(project)::closeFile)
            FileDocumentManager.getInstance().saveAllDocuments()
            SettingsTestSupport.resetAll(project)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun golden(golden: GoldenRoot) = AnsibilityProjectSettings.getInstance(project).update { it.copy(drift = it.drift.copy(golden = golden)) }

    private fun dir(team: String, role: String = "web"): VirtualFile = DriftFixture.file(myFixture, DriftFixture.roleDir(team, role))

    private fun panel(view: TreeView): AnsibleToolWindowPanel {
        val panel = AnsibleToolWindowPanel(project, view).also { Disposer.register(testRootDisposable, it) }
        PlatformTestUtil.waitWithEventsDispatching("the tool window did not load", { panel.refreshCount > 0 }, TIMEOUT)
        PlatformTestUtil.waitWhileBusy(panel.tree)
        return panel
    }

    private fun waitForRefresh(panel: AnsibleToolWindowPanel, after: Long) {
        PlatformTestUtil.waitWithEventsDispatching("the tool window did not refresh", { panel.refreshCount > after }, TIMEOUT)
        PlatformTestUtil.waitWhileBusy(panel.tree)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    private fun nodeOf(path: TreePath?): AnsibleTreeNode? = path?.let { TreeUtil.getLastUserObject(AnsibleNodeDescriptor::class.java, it) }?.node

    /** The key of the node reached by [names] in the headless model of [panel]'s snapshot. */
    private fun keyOf(panel: AnsibleToolWindowPanel, names: List<String>): String {
        var node: AnsibleTreeNode = WorkspaceNode(panel.snapshot, panel.view, panel.driftedOnly, panel.groupByVariant)
        for (name in names) {
            val children = runReadActionBlocking { node.children(TreeContext.NONE) }
            node = children.firstOrNull { it.presentation().name == name } ?: error("no $name in ${children.map { it.presentation().name }}")
        }
        return node.key
    }

    private fun select(panel: AnsibleToolWindowPanel, vararg names: String): TreePath {
        val key = keyOf(panel, names.toList())
        val visitor = TreeVisitor { path ->
            val node = nodeOf(path)
            when {
                node == null -> TreeVisitor.Action.CONTINUE
                node.key == key -> TreeVisitor.Action.INTERRUPT
                key.startsWith(node.childKeyBase + "/") -> TreeVisitor.Action.CONTINUE
                else -> TreeVisitor.Action.SKIP_CHILDREN
            }
        }
        val found = PlatformTestUtil.waitForPromise(TreeUtil.promiseSelect(panel.tree, visitor), TIMEOUT * 1000L)
            ?: error("no ${names.joinToString(" › ")} in\n${PlatformTestUtil.print(panel.tree, false)}")
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        PlatformTestUtil.waitWithEventsDispatching("no details for ${names.joinToString(" › ")}", { panel.detailsNode?.key == key }, TIMEOUT)
        return found
    }

    private fun rendered(panel: AnsibleToolWindowPanel, name: String): String? {
        for (row in 0 until panel.tree.rowCount) {
            val descriptor = TreeUtil.getLastUserObject(AnsibleNodeDescriptor::class.java, panel.tree.getPathForRow(row)) ?: continue
            if (descriptor.node.presentation().name == name && descriptor.node.parent is WorkspaceNode) {
                return descriptor.presentation.coloredText.joinToString("") { it.text }
            }
        }
        return null
    }

    /** The rendered text of the row of the node [key], or null when it is not shown. */
    private fun renderedRow(panel: AnsibleToolWindowPanel, key: String): String? {
        for (row in 0 until panel.tree.rowCount) {
            val descriptor = TreeUtil.getLastUserObject(AnsibleNodeDescriptor::class.java, panel.tree.getPathForRow(row)) ?: continue
            if (descriptor.node.key == key) return descriptor.presentation.coloredText.joinToString("") { it.text }
        }
        return null
    }

    private fun topRows(panel: AnsibleToolWindowPanel): List<String> =
        (0 until panel.tree.rowCount).mapNotNull { row -> nodeOf(panel.tree.getPathForRow(row))?.takeIf { it.parent is WorkspaceNode }?.presentation()?.name }

    private var productionDataManager = false

    /** The tree's data context as the IDE builds it (the panel's `uiDataSnapshot`). */
    private fun context(panel: AnsibleToolWindowPanel): DataContext {
        if (!productionDataManager) {
            HeadlessDataManager.fallbackToProductionDataManager(testRootDisposable)
            productionDataManager = true
        }
        return DataManager.getInstance().getDataContext(panel.tree)
    }

    private fun shortcutAction(panel: AnsibleToolWindowPanel, shortcuts: ShortcutSet): AnAction =
        ActionUtil.getActions(panel.tree).first { it.shortcutSet.shortcuts.toList() == shortcuts.shortcuts.toList() }

    private fun perform(panel: AnsibleToolWindowPanel, action: AnAction) {
        action.actionPerformed(TestActionEvent.createTestEvent(action, context(panel)))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    /** What a registered golden action received: the copy and the path of each call. Named like the real action. */
    private class Recorder(text: String) : DumbAwareAction(text) {
        val calls = ArrayList<Pair<VirtualFile?, String?>>()

        override fun actionPerformed(e: AnActionEvent) {
            calls += e.getData(GoldenDataKeys.ROLE_COPY) to e.getData(GoldenDataKeys.ROLE_PATH)
        }
    }

    private fun register(id: String): Recorder {
        val recorder = Recorder(hidden[id]?.templateText ?: "Recorded Golden Action")
        ActionManager.getInstance().registerAction(id, recorder)
        registered += id
        return recorder
    }

    // ------------------------------------------------------------------ D178: no golden root

    fun testWithoutAGoldenRootNothingIsComputedAndThereIsNoHeader() {
        val panel = panel(TreeView.ROLES)
        panel.tabShown = true
        Thread.sleep(300)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals("D178: the drift service is never asked", 0, service.counters.copiesWalked)
        assertNull(service.cached("web"))

        // Drift is opt-in: no "No golden root" reminder and no offer, even with a role library (feedback 2026-10-09).
        val header = panel.rolesHeader
        assertFalse("no header without a golden root", header.component.isVisible)
        assertEquals("", header.text)
        assertEquals(emptyList<String>(), header.links.map { it.text })
    }

    fun testTheHeaderAppearsOnceAGoldenRootIsChosen() {
        val panel = panel(TreeView.ROLES)
        assertFalse(panel.rolesHeader.component.isVisible)
        val before = panel.refreshCount
        golden(GoldenRoot.Root("golden"))
        waitForRefresh(panel, before)
        assertTrue(panel.rolesHeader.component.isVisible)
        assertEquals("nothing computed yet: the count is not known", "Golden root: golden · 4 names · … drifting", panel.rolesHeader.text)
        assertEquals(listOf("Change…"), panel.rolesHeader.links.map { it.text })
        var opened = 0
        panel.openSettings = { opened++ }
        assertTrue(panel.rolesHeader.click("Change…"))
        assertEquals("Change… opens the settings page", 1, opened)
    }

    fun testAMissingGoldenRootIsNamed() {
        golden(GoldenRoot.Root("repos/hawk/ansible"))
        val panel = panel(TreeView.ROLES)
        assertEquals("Golden root repos/hawk/ansible not found", panel.rolesHeader.text)
        assertEquals(listOf("Choose…"), panel.rolesHeader.links.map { it.text })
        panel.tabShown = true
        Thread.sleep(200)
        assertEquals(0, service.counters.copiesWalked)
    }

    // ------------------------------------------------------------------ D179: lazy computation and live rows

    fun testDriftIsComputedOnlyWhileTheRolesTabIsShown() {
        golden(GoldenRoot.FirstRoleLibrary)
        val repos = panel(TreeView.REPOS)
        val roles = panel(TreeView.ROLES)
        repos.tabShown = true
        Thread.sleep(300)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals("neither the Repos tab nor a hidden Roles tab starts drift", 0, service.counters.copiesWalked)
        assertEquals("…", rendered(roles, "web")!!.substringAfter("8 copies · "))

        roles.tabShown = true
        DriftFixture.waitFor("every name computed") { listOf("app-same", "base", "solo", "web").all(service::isCurrent) }
        PlatformTestUtil.waitWithEventsDispatching("the rows follow the drift events", { rendered(roles, "web")?.contains("6 differ from golden") == true }, TIMEOUT)
        PlatformTestUtil.waitWithEventsDispatching("the other rows too", { rendered(roles, "base")?.contains("identical everywhere") == true }, TIMEOUT)
        assertEquals("Golden root: golden · 4 names · 1 drifting", roles.rolesHeader.text)
        assertTrue(roles.driftUpdates > 0)
    }

    fun testAnEditUpdatesTheRowAndTheSelectedDetails() {
        golden(GoldenRoot.FirstRoleLibrary)
        service.documentDebounce = kotlin.time.Duration.parse("50ms")
        val roles = panel(TreeView.ROLES)
        roles.tabShown = true
        DriftFixture.waitFor("every name computed") { listOf("app-same", "base", "solo", "web").all(service::isCurrent) }
        PlatformTestUtil.waitWithEventsDispatching("rows", { rendered(roles, "base")?.contains("identical everywhere") == true }, TIMEOUT)
        select(roles, "base", "same")
        assertEquals("= golden", roles.detailsView.details!!.subtitle)

        DriftFixture.write(myFixture, "${DriftFixture.roleDir("same", "base")}/tasks/extra.yml", "---\n- name: Extra\n  ansible.builtin.meta: noop\n")
        PlatformTestUtil.waitWithEventsDispatching("the name's row follows", { rendered(roles, "base")?.contains("1 differs from golden (tasks/templates)") == true }, TIMEOUT)
        PlatformTestUtil.waitWithEventsDispatching(
            "the selected copy's details follow",
            { roles.detailsView.details?.subtitle == "Δ tasks/templates · 1 only here" },
            TIMEOUT,
        )
    }

    fun testTheReposTabComputesOnlyTheSelectedCopysNameAndFollowsDrift() {
        golden(GoldenRoot.FirstRoleLibrary)
        val repos = panel(TreeView.REPOS)
        repos.tabShown = true
        val key = keyOf(repos, listOf("mol", "Roles (2)", "base"))
        Thread.sleep(200)
        assertEquals("the Repos tab never runs the all-names pass", 0, service.counters.copiesWalked)

        // Review fix U3: the details never say "being computed" while nothing computes; the selection's name is.
        select(repos, "mol", "Roles (2)", "base")
        PlatformTestUtil.waitWithEventsDispatching(
            "the selected copy's drift is computed and its details follow",
            { repos.detailsView.details?.subtitle == "= golden" },
            TIMEOUT,
        )
        assertFalse(repos.detailsView.details!!.sections.flatMap { it.items }.any { it.text.contains("being computed") })
        assertEquals("only base's three copies were walked", 3L, service.counters.copiesWalked)
        assertFalse("no other name", listOf("app-same", "solo", "web").any(service::isComputed))
        PlatformTestUtil.waitWithEventsDispatching("the copy's row follows", { renderedRow(repos, key)?.contains("= golden") == true }, TIMEOUT)

        val web = keyOf(repos, listOf("mol", "Roles (2)", "web"))
        DriftFixture.await { service.driftAll() }
        PlatformTestUtil.waitWithEventsDispatching(
            "an unselected copy's row follows the drift event",
            { renderedRow(repos, web) == "web  ≈ molecule only · 1 file  no play applies this copy" },
            TIMEOUT,
        )
        assertTrue(repos.driftUpdates > 0)
    }

    fun testDriftedOnlyFiltersTheRoleNames() {
        golden(GoldenRoot.FirstRoleLibrary)
        DriftFixture.await { service.driftAll() }
        val roles = panel(TreeView.ROLES)
        assertEquals(listOf("app-same", "base", "solo", "web"), topRows(roles))
        roles.driftedOnly = true
        PlatformTestUtil.waitWhileBusy(roles.tree)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(listOf("web"), topRows(roles))
        roles.driftedOnly = false
        PlatformTestUtil.waitWhileBusy(roles.tree)
        assertEquals(4, topRows(roles).size)
    }

    // ------------------------------------------------------------------ the data contract and activation

    fun testGoldenActionsGetTheCopyAndThePath() {
        golden(GoldenRoot.FirstRoleLibrary)
        DriftFixture.await { service.driftAll() }
        val roles = panel(TreeView.ROLES)

        select(roles, "web", "missing", "Differences from golden (12)", "Tasks/templates", "tasks/main.yml")
        var context = context(roles)
        assertEquals(dir("missing"), context.getData(GoldenDataKeys.ROLE_COPY))
        assertEquals("tasks/main.yml", context.getData(GoldenDataKeys.ROLE_PATH))
        assertEquals(DriftFixture.file(myFixture, "${DriftFixture.roleDir("missing")}/tasks/main.yml"), context.getData(CommonDataKeys.VIRTUAL_FILE))

        select(roles, "web", "missing", "Differences from golden (12)", "Only in golden", "meta/argument_specs.yml")
        context = context(roles)
        assertEquals(dir("missing"), context.getData(GoldenDataKeys.ROLE_COPY))
        assertEquals("meta/argument_specs.yml", context.getData(GoldenDataKeys.ROLE_PATH))
        assertNull("no file of this copy, and never golden's", context.getData(CommonDataKeys.VIRTUAL_FILE))

        select(roles, "web", "missing")
        context = context(roles)
        assertEquals(dir("missing"), context.getData(GoldenDataKeys.ROLE_COPY))
        assertNull(context.getData(GoldenDataKeys.ROLE_PATH))

        select(roles, "web", "missing", "tasks", "main.yml")
        context = context(roles)
        assertEquals(dir("missing"), context.getData(GoldenDataKeys.ROLE_COPY))
        assertEquals("tasks/main.yml", context.getData(GoldenDataKeys.ROLE_PATH))
    }

    fun testEnterAndF4RunCompareWithGoldenWhenItIsRegistered() {
        golden(GoldenRoot.FirstRoleLibrary)
        DriftFixture.await { service.driftAll() }
        val roles = panel(TreeView.ROLES)
        val files = FileEditorManager.getInstance(project)

        select(roles, "web", "missing", "Differences from golden (12)", "Tasks/templates", "tasks/main.yml")
        perform(roles, shortcutAction(roles, CommonShortcuts.ENTER))
        assertEquals("without Compare, Enter opens this copy's file", listOf(DriftFixture.file(myFixture, "${DriftFixture.roleDir("missing")}/tasks/main.yml")), files.selectedFiles.toList())
        files.openFiles.forEach(files::closeFile)

        select(roles, "web", "missing", "Differences from golden (12)", "Only in golden", "meta/argument_specs.yml")
        perform(roles, shortcutAction(roles, CommonShortcuts.ENTER))
        assertTrue("a file only golden has opens nothing", files.openFiles.isEmpty())

        val compare = register(GoldenActionIds.COMPARE_WITH_GOLDEN)
        perform(roles, shortcutAction(roles, CommonShortcuts.ENTER))
        select(roles, "web", "missing", "Differences from golden (12)", "Tasks/templates", "tasks/main.yml")
        perform(roles, shortcutAction(roles, CommonShortcuts.getEditSource()))
        assertEquals(listOf(dir("missing") to "meta/argument_specs.yml", dir("missing") to "tasks/main.yml"), compare.calls)
        assertTrue("Compare replaces opening the file", files.openFiles.isEmpty())
    }

    // ------------------------------------------------------------------ D180: the details' links and buttons

    fun testTheDetailsOfferOnlyRegisteredActions() {
        golden(GoldenRoot.FirstRoleLibrary)
        DriftFixture.await { service.driftAll() }
        val roles = panel(TreeView.ROLES)
        select(roles, "web", "missing")
        val component = roles.detailsView.component
        assertTrue("no golden action is registered: no buttons", UIUtil.findComponentsOfType(component, JButton::class.java).none { it !is ActionLink })
        assertTrue("and no Compare links", UIUtil.findComponentsOfType(component, ActionLink::class.java).none { it.text == "Compare" })

        val compare = register(GoldenActionIds.COMPARE_WITH_GOLDEN)
        val push = register(GoldenActionIds.PUSH_TO_REPOS)
        select(roles, "web", "mol")
        select(roles, "web", "missing")
        val links = UIUtil.findComponentsOfType(roles.detailsView.component, ActionLink::class.java).filter { it.text == "Compare" }
        assertEquals("one Compare per differing file", 12, links.size)
        val buttons = UIUtil.findComponentsOfType(roles.detailsView.component, JButton::class.java).filter { it !is ActionLink }
        assertEquals("the registered actions only, by their menu texts", listOf("Compare with Golden", "Push Role to Repos…"), buttons.map { it.text })

        links.first().doClick()
        buttons.last().doClick()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(listOf(dir("missing") to "handlers/main.yml"), compare.calls)
        assertEquals(listOf(dir("missing") to null), push.calls)
    }

    // ------------------------------------------------------------------ D179: the header while the worker runs

    fun testTheHeaderCountIsALowerBoundUntilEveryNameIsKnown() {
        golden(GoldenRoot.FirstRoleLibrary)
        val roles = panel(TreeView.ROLES)
        assertEquals("Golden root: golden · 4 names · … drifting", roles.rolesHeader.text)
        DriftFixture.await { service.drift("web") }
        PlatformTestUtil.waitWithEventsDispatching(
            "one drifting name known, three not yet",
            { roles.rolesHeader.text == "Golden root: golden · 4 names · 1+ drifting" },
            TIMEOUT,
        )
        DriftFixture.await { service.driftAll() }
        PlatformTestUtil.waitWithEventsDispatching(
            "every name known: the exact count",
            { roles.rolesHeader.text == "Golden root: golden · 4 names · 1 drifting" },
            TIMEOUT,
        )
    }

    // ------------------------------------------------------------------ X123: Group by Variant

    private fun toolbarTexts(panel: AnsibleToolWindowPanel): List<String?> {
        panel.actionToolbar.updateActionsAsync()
        PlatformTestUtil.waitWithEventsDispatching("the toolbar did not update", { panel.actionToolbar.actions.isNotEmpty() }, TIMEOUT)
        return panel.actionToolbar.actions.map { it.templateText }
    }

    fun testGroupByVariantIsOfferedOnlyWithAGoldenRoot() {
        val roles = panel(TreeView.ROLES)
        assertFalse(toolbarTexts(roles).contains("Group by Variant"))
        val before = roles.refreshCount
        golden(GoldenRoot.FirstRoleLibrary)
        waitForRefresh(roles, before)
        PlatformTestUtil.waitWithEventsDispatching("next to Drifted Only", {
            val texts = toolbarTexts(roles)
            texts.indexOf("Group by Variant") == texts.indexOf("Drifted Only") + 1 && texts.contains("Drifted Only")
        }, TIMEOUT)
    }

    fun testGroupByVariantKeepsTheSelectedCopy() {
        golden(GoldenRoot.FirstRoleLibrary)
        DriftFixture.await { service.driftAll() }
        val roles = panel(TreeView.ROLES)
        select(roles, "web", "mol")
        val key = roles.selectedNode()!!.key

        roles.groupByVariant = true
        PlatformTestUtil.waitWithEventsDispatching(
            "the copy stays selected, below its variant group",
            { roles.selectedNode()?.key == key && roles.selectedNode()?.parent is VariantGroupNode },
            TIMEOUT,
        )
        assertEquals("Variant B: mol, mol2 (2)", roles.selectedNode()!!.parent!!.presentation().name)
        PlatformTestUtil.waitWithEventsDispatching("its details", { roles.detailsNode?.key == key }, TIMEOUT)
        assertEquals("web in mol", roles.detailsView.details?.title)

        // An edit moves the copy into a group of its own: the selection follows it.
        DriftFixture.write(myFixture, "${DriftFixture.roleDir("mol")}/files/extra.txt", "only in mol\n")
        DriftFixture.await { service.driftAll() }
        PlatformTestUtil.waitWithEventsDispatching(
            "the selection follows the copy into its new group",
            { roles.selectedNode()?.key == key && roles.selectedNode()?.parent?.presentation()?.name == "Variant C: mol (1)" },
            TIMEOUT,
        )

        roles.groupByVariant = false
        PlatformTestUtil.waitWithEventsDispatching(
            "and back in the flat list",
            { roles.selectedNode()?.key == key && roles.selectedNode()?.parent is RoleNameNode },
            TIMEOUT,
        )
    }

    fun testAnExpandedVariantGroupStaysExpandedWhenTheDriftChanges() {
        golden(GoldenRoot.FirstRoleLibrary)
        DriftFixture.await { service.driftAll() }
        val roles = panel(TreeView.ROLES)
        roles.groupByVariant = true
        PlatformTestUtil.waitWhileBusy(roles.tree)
        val group = select(roles, "web", "Variant B: mol, mol2 (2)")
        roles.tree.expandPath(group)
        PlatformTestUtil.waitWhileBusy(roles.tree)
        val groupKey = roles.selectedNode()!!.key
        val updates = roles.driftUpdates

        // spec, specmol and tasks become one variant, larger than mol's: mol's group is C now, with the same key.
        DriftFixture.joinSpecsVariant(myFixture)
        DriftFixture.await { service.driftAll() }
        PlatformTestUtil.waitWithEventsDispatching("the drift event was applied", { roles.driftUpdates > updates }, TIMEOUT)
        PlatformTestUtil.waitWhileBusy(roles.tree)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        val row = (0 until roles.tree.rowCount).map { roles.tree.getPathForRow(it) }.single { nodeOf(it)?.key == groupKey }
        assertEquals("Variant C: mol, mol2 (2)", nodeOf(row)!!.presentation().name)
        assertTrue("still expanded:\n${PlatformTestUtil.print(roles.tree, false)}", roles.tree.isExpanded(row))
    }

    // ------------------------------------------------------------------ D180: "Last changed" arrives in the background

    private class SlowLookup(private val answers: Map<String, LastChange>, private val reportEachLookup: Boolean = false) : LastChangeLookup {
        val calls = AtomicInteger()

        @Volatile
        private var changed: ((VirtualFile?) -> Unit)? = null

        override fun lastChange(project: Project, file: VirtualFile): LastChange? = null

        override fun lastChangeUnder(project: Project, dir: VirtualFile): LastChange? {
            calls.incrementAndGet()
            Thread.sleep(100)
            if (reportEachLookup) changed?.invoke(null)
            return answers.entries.firstOrNull { dir.path.endsWith("/" + it.key) }?.value
        }

        override fun watch(project: Project, parent: com.intellij.openapi.Disposable, changed: (VirtualFile?) -> Unit) {
            this.changed = changed
        }
    }

    private fun day(year: Int, month: Int, day: Int) = LocalDate.of(year, month, day).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()

    fun testLastChangedIsLookedUpInTheBackgroundAndTheDetailsFollow() {
        val lookup = SlowLookup(
            mapOf(
                DriftFixture.roleDir("golden") to LastChange("alice", day(2026, 9, 12), "fix verify", "a1b2c3d4"),
                DriftFixture.roleDir("mol") to LastChange("bob", day(2025, 3, 1), "tune verify", "e5f6a7b8"),
            ),
        )
        ExtensionTestUtil.maskExtensions(LastChangeLookup.EP_NAME, listOf(lookup), testRootDisposable)
        LastChanges.getInstance(project).clearForTests()
        golden(GoldenRoot.FirstRoleLibrary)
        DriftFixture.await { service.driftAll() }
        val roles = panel(TreeView.ROLES)
        select(roles, "web", "mol")
        PlatformTestUtil.waitWithEventsDispatching(
            "the details follow the answers",
            { roles.detailsView.details?.section("Last changed")?.items?.size == 3 },
            TIMEOUT,
        )
        assertEquals(
            listOf(
                "golden: 2026-09-12 · alice · fix verify",
                "mol: 2025-03-01 · bob · tune verify",
                "golden changed this role more recently (2026-09-12) than mol (2025-03-01)",
            ),
            roles.detailsView.details!!.section("Last changed")!!.items.map { it.text },
        )
        assertTrue(roles.lastChangeUpdates > 0)
        assertEquals("one lookup per side", 2, lookup.calls.get())

        // A side without an answer is looked up once: the details do not loop.
        select(roles, "web", "spec")
        PlatformTestUtil.waitWithEventsDispatching("the golden side shows", { roles.detailsView.details?.section("Last changed")?.items?.size == 1 }, TIMEOUT)
        Thread.sleep(300)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals("spec's side was looked up once", 3, lookup.calls.get())
    }

    fun testAVcsChangeReportDuringEveryLookupDoesNotLoop() {
        // The platform refreshes file statuses after every save, VFS refresh and write: a report during each lookup.
        val lookup = SlowLookup(
            mapOf(
                DriftFixture.roleDir("golden") to LastChange("alice", day(2026, 9, 12), "fix verify", "a1b2c3d4"),
                DriftFixture.roleDir("mol") to LastChange("bob", day(2025, 3, 1), "tune verify", "e5f6a7b8"),
            ),
            reportEachLookup = true,
        )
        ExtensionTestUtil.maskExtensions(LastChangeLookup.EP_NAME, listOf(lookup), testRootDisposable)
        LastChanges.getInstance(project).clearForTests()
        golden(GoldenRoot.FirstRoleLibrary)
        DriftFixture.await { service.driftAll() }
        val roles = panel(TreeView.ROLES)
        select(roles, "web", "mol")
        PlatformTestUtil.waitWithEventsDispatching(
            "the answers show although each was reported changed at once",
            { roles.detailsView.details?.section("Last changed")?.items?.size == 3 },
            TIMEOUT,
        )
        Thread.sleep(1500)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue("the details were computed again at most once, for the answers: ${roles.lastChangeUpdates}", roles.lastChangeUpdates <= 1)
        assertTrue("a bounded number of lookups, no loop: ${lookup.calls.get()}", lookup.calls.get() <= 8)
    }

    fun testWithoutVcsTheDetailsHaveNoLastChanged() {
        ExtensionTestUtil.maskExtensions(LastChangeLookup.EP_NAME, emptyList(), testRootDisposable)
        golden(GoldenRoot.FirstRoleLibrary)
        DriftFixture.await { service.driftAll() }
        val roles = panel(TreeView.ROLES)
        val lookups = LastChanges.getInstance(project).lookupCount
        select(roles, "web", "mol")
        Thread.sleep(200)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertNull(roles.detailsView.details!!.section("Last changed"))
        assertEquals(0L, roles.lastChangeUpdates)
        assertEquals(lookups, LastChanges.getInstance(project).lookupCount)
    }

    // ------------------------------------------------------------------ test isolation

    fun testTheHiddenGoldenActionsGoBackIntoTheirMenus() {
        val actions = ActionManager.getInstance()
        assertEquals("the plugin registers all four", GoldenActionIds.COPY_ACTIONS, hidden.keys.toList())
        assertTrue("hidden from the menus during the test", menus.keys.all { group -> group.getChildActionsOrStubs().mapNotNull(actions::getId).none { it in hidden } })
        hidden.forEach { (id, action) -> actions.registerAction(id, action) }
        restoreMenus()
        for ((group, ids) in menus) assertEquals(ids.filterNotNull(), group.getChildActionsOrStubs().mapNotNull(actions::getId))
        hidden.clear()
    }

    // ------------------------------------------------------------------ D177: Use as Golden Root

    fun testUseAsGoldenRootOnARootRow() {
        val repos = panel(TreeView.REPOS)
        val action = ActionManager.getInstance().getAction("Ansibility.Golden.SetGoldenRoot")
        assertNotNull("registered", action)
        val popup = ActionManager.getInstance().getAction(AnsibleToolWindowPanel.POPUP_GROUP) as DefaultActionGroup
        assertTrue("in the tool window's popup menu", popup.getChildActionsOrStubs().any { ActionManager.getInstance().getId(it) == "Ansibility.Golden.SetGoldenRoot" })
        fun event() = TestActionEvent.createTestEvent(
            action,
            SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(PlatformCoreDataKeys.CONTEXT_COMPONENT, repos.tree).build(),
        )

        select(repos, "same")
        val offered = event()
        action.update(offered)
        assertTrue(offered.presentation.isEnabledAndVisible)
        assertEquals("Ansibility: Use same as Golden Root", offered.presentation.text)
        action.actionPerformed(event())
        assertEquals(GoldenRoot.Root("repos/same/ansible"), AnsibilityProjectSettings.getInstance(project).settings.drift.golden)

        val again = event()
        action.update(again)
        assertFalse("hidden on the golden root itself", again.presentation.isEnabledAndVisible)

        select(repos, "same", "Roles (4)")
        val other = event()
        action.update(other)
        assertFalse("hidden on other rows", other.presentation.isEnabledAndVisible)
    }

    private companion object {
        const val TIMEOUT = 30

        /** The menus the golden actions are added to (ansibility-golden.xml): the tool window's and the submenu. */
        val MENUS = listOf(AnsibleToolWindowPanel.POPUP_GROUP, GoldenTestSupport.SUBMENU)
    }
}
