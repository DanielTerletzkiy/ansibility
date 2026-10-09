package de.terletzkiy.ansibility.toolwindow

import com.intellij.ide.DataManager
import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
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
import de.terletzkiy.ansibility.golden.RecordingDiffUi
import de.terletzkiy.ansibility.golden.history.LastChangeLookup
import de.terletzkiy.ansibility.golden.push.RecordingPushUi
import de.terletzkiy.ansibility.golden.push.PushTestSupport
import de.terletzkiy.ansibility.model.drift.DriftFixture
import de.terletzkiy.ansibility.model.drift.RoleDriftService
import de.terletzkiy.ansibility.model.role.ModelFixture
import de.terletzkiy.ansibility.settings.SettingsTestSupport
import de.terletzkiy.ansibility.toolwindow.model.AnsibleTreeNode
import de.terletzkiy.ansibility.toolwindow.model.GoldenActionIds
import de.terletzkiy.ansibility.toolwindow.model.TreeContext
import de.terletzkiy.ansibility.toolwindow.model.TreeView
import de.terletzkiy.ansibility.toolwindow.model.WorkspaceNode
import javax.swing.JButton
import javax.swing.tree.TreePath

/**
 * The Roles tab with the plugin's REAL golden actions (plan amendment R24, D180/D182/D188), headless: Enter on a
 * "Differences from golden" file row runs the registered Compare with Golden, whose diff chain starts at that file; a
 * drifting copy's details offer the five registered actions as buttons, and Push to Repos opens the Push dialog for
 * that copy. Only the windows are replaced ([RecordingDiffUi], [RecordingPushUi]); the actions, the tree and the data
 * contract are the production ones.
 */
class GoldenActionsPanelTest : BasePlatformTestCase() {
    private lateinit var service: RoleDriftService
    private lateinit var diffs: RecordingDiffUi
    private lateinit var pushes: RecordingPushUi

    override fun getTestDataPath(): String = ModelFixture.testDataPath

    override fun setUp() {
        super.setUp()
        DriftFixture.copy(myFixture)
        service = DriftFixture.freshService(project, testRootDisposable)
        project.registerOrReplaceServiceInstance(RoleDriftService::class.java, service, testRootDisposable)
        DriftFixture.useGolden(project, testRootDisposable)
        diffs = RecordingDiffUi().also { GoldenTestSupport.install(it, testRootDisposable) }
        pushes = RecordingPushUi().also { PushTestSupport.install(it, testRootDisposable) }
        // No VCS in the light project: the details look up no last change.
        ExtensionTestUtil.maskExtensions(LastChangeLookup.EP_NAME, emptyList(), testRootDisposable)
        // The tree's data context as the IDE builds it (the panel's uiDataSnapshot).
        HeadlessDataManager.fallbackToProductionDataManager(testRootDisposable)
    }

    override fun tearDown() {
        try {
            FileEditorManager.getInstance(project).openFiles.forEach(FileEditorManager.getInstance(project)::closeFile)
            FileDocumentManager.getInstance().saveAllDocuments()
            SettingsTestSupport.resetAll(project)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun dir(team: String): VirtualFile = DriftFixture.file(myFixture, DriftFixture.roleDir(team))

    private fun rolesTab(): AnsibleToolWindowPanel {
        DriftFixture.await { service.driftAll() }
        val panel = AnsibleToolWindowPanel(project, TreeView.ROLES).also { Disposer.register(testRootDisposable, it) }
        PlatformTestUtil.waitWithEventsDispatching("the tool window did not load", { panel.refreshCount > 0 }, TIMEOUT)
        PlatformTestUtil.waitWhileBusy(panel.tree)
        return panel
    }

    private fun nodeOf(path: TreePath?): AnsibleTreeNode? = path?.let { TreeUtil.getLastUserObject(AnsibleNodeDescriptor::class.java, it) }?.node

    private fun select(panel: AnsibleToolWindowPanel, vararg names: String) {
        var node: AnsibleTreeNode = WorkspaceNode(panel.snapshot, panel.view)
        for (name in names) {
            val children = runReadActionBlocking { node.children(TreeContext.NONE) }
            node = children.firstOrNull { it.presentation().name == name } ?: error("no $name in ${children.map { it.presentation().name }}")
        }
        val key = node.key
        val visitor = TreeVisitor { path ->
            val visited = nodeOf(path)
            when {
                visited == null -> TreeVisitor.Action.CONTINUE
                visited.key == key -> TreeVisitor.Action.INTERRUPT
                key.startsWith(visited.childKeyBase + "/") -> TreeVisitor.Action.CONTINUE
                else -> TreeVisitor.Action.SKIP_CHILDREN
            }
        }
        PlatformTestUtil.waitForPromise(TreeUtil.promiseSelect(panel.tree, visitor), TIMEOUT * 1000L) ?: error("no ${names.joinToString(" › ")}")
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        PlatformTestUtil.waitWithEventsDispatching("no details for ${names.joinToString(" › ")}", { panel.detailsNode?.key == key }, TIMEOUT)
    }

    private fun press(panel: AnsibleToolWindowPanel, shortcut: com.intellij.openapi.actionSystem.ShortcutSet) {
        val action: AnAction = ActionUtil.getActions(panel.tree).first { it.shortcutSet.shortcuts.toList() == shortcut.shortcuts.toList() }
        action.actionPerformed(TestActionEvent.createTestEvent(action, DataManager.getInstance().getDataContext(panel.tree)))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    private fun buttons(panel: AnsibleToolWindowPanel): List<JButton> =
        UIUtil.findComponentsOfType(panel.detailsView.component, JButton::class.java).filter { it !is ActionLink }

    fun testEnterOnADifferingFileRunsTheRegisteredCompareWithGolden() {
        assertTrue(
            "the plugin's own action is registered",
            ActionManager.getInstance().getAction(GoldenActionIds.COMPARE_WITH_GOLDEN)?.javaClass?.name.orEmpty().startsWith("de.terletzkiy.ansibility.golden."),
        )
        val roles = rolesTab()
        select(roles, "web", "missing", "Differences from golden (12)", "Tasks/templates", "tasks/main.yml")
        press(roles, CommonShortcuts.ENTER)
        GoldenTestSupport.waitFor("the diff chain is shown") { diffs.chains.isNotEmpty() }
        val chain = diffs.chains.single()
        assertEquals("every differing file of missing", 12, chain.requests.size)
        assertEquals("the chain starts at the file of the row", "tasks/main.yml", chain.requests[chain.index].name)
        assertTrue("Compare replaces opening the file", FileEditorManager.getInstance(project).openFiles.isEmpty())
    }

    fun testTheDetailsButtonsRunTheRegisteredActionsAndPushOpensTheDialog() {
        for (id in GoldenActionIds.COPY_ACTIONS) assertNotNull("$id is registered", ActionManager.getInstance().getAction(id))
        val roles = rolesTab()
        select(roles, "web", "missing")
        assertEquals(
            "a drifting copy offers all five, by their menu texts",
            listOf("Compare with Golden", "Align with Golden…", "Merge into Golden…", "Push Role to Repos…", "Copy as Patch for Golden…"),
            buttons(roles).map { it.text },
        )

        buttons(roles).single { it.text == "Push Role to Repos…" }.doClick()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        GoldenTestSupport.waitFor("the Push dialog opens") { pushes.models.isNotEmpty() }
        assertEquals("pushed from that copy", dir("missing"), pushes.models.single().source.dir)

        buttons(roles).single { it.text == "Compare with Golden" }.doClick()
        GoldenTestSupport.waitFor("the diff chain is shown") { diffs.chains.isNotEmpty() }
        assertEquals("a button stands for the copy: the chain starts at its first differing file", 0, diffs.chains.single().index)

        select(roles, "web", "golden")
        assertEquals("the golden copy offers Push only", listOf("Push Role to Repos…"), buttons(roles).map { it.text })
    }

    fun testEachDetailsButtonIsNamedLikeTheMenuItemOfItsAction() {
        val roles = rolesTab()
        select(roles, "web", "missing")
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(GoldenDataKeys.ROLE_COPY, dir("missing")).build()
        val menuTexts = GoldenActionIds.COPY_ACTIONS.map { id ->
            val action = ActionManager.getInstance().getAction(id)
            val event = AnActionEvent.createEvent(action, context, action.templatePresentation.clone(), ActionPlaces.PROJECT_VIEW_POPUP, ActionUiKind.POPUP, null)
            runReadActionBlocking { action.update(event) }
            event.presentation.text
        }
        assertEquals("one name per action: the details button and the menu item", menuTexts, buttons(roles).map { it.text })
    }

    private companion object {
        const val TIMEOUT = 30
    }
}
