package de.terletzkiy.ansibility.workspace

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.replaceService
import de.terletzkiy.ansibility.api.ScopeChoice
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.toolwindow.AnsibleToolWindowPanel
import de.terletzkiy.ansibility.workspace.actions.AnsibleScopeComboAction
import de.terletzkiy.ansibility.workspace.actions.EditScopesAction
import de.terletzkiy.ansibility.workspace.actions.OpenScopeInProjectViewAction
import de.terletzkiy.ansibility.workspace.actions.ScopeCommands
import de.terletzkiy.ansibility.workspace.actions.ScopePopup
import de.terletzkiy.ansibility.workspace.actions.SwitchScopeAction

/**
 * The scope selector's actions (F9.1): registration and texts, the combo's text in toolbars only, the popup's rows
 * (choices, coverage, the deleted scope, the uncovered-roots footer), what each item stores or opens, and when
 * "Open in Project View" is available. Dialogs and the Project view go through a recording [ScopeUi].
 */
@RequiresInfraFixture
class ScopeActionsTest : WorkspaceScopeTestCase() {
    private lateinit var ui: RecordingScopeUi

    override fun setUp() {
        super.setUp()
        ui = RecordingScopeUi()
        ApplicationManager.getApplication().replaceService(ScopeUi::class.java, ui, testRootDisposable)
    }

    private val manager: ActionManager get() = ActionManager.getInstance()

    // ------------------------------------------------------------------------------------------------ registration

    fun testActionsAreRegisteredWithTheirTextsAndTheComboLeadsTheToolbarGroup() {
        val expected = mapOf(
            COMBO to AnsibleScopeComboAction::class.java,
            SWITCH to SwitchScopeAction::class.java,
            EDIT to EditScopesAction::class.java,
            OPEN to OpenScopeInProjectViewAction::class.java,
        )
        for ((id, type) in expected) {
            val action = manager.getAction(id)
            assertInstanceOf(action, type)
            assertTrue("$id is DumbAware", action is DumbAware)
            assertEquals("$id updates on a background thread", ActionUpdateThread.BGT, action.actionUpdateThread)
        }
        assertEquals("Ansibility: Switch Scope…", manager.getAction(SWITCH).templatePresentation.text)
        assertEquals("Ansibility: Edit Scopes…", manager.getAction(EDIT).templatePresentation.text)
        assertEquals("Ansibility: Open Scope in Project View", manager.getAction(OPEN).templatePresentation.text)
        assertEquals("Ansibility: Workspace Scope", manager.getAction(COMBO).templatePresentation.text)
        assertTrue(manager.getAction(SWITCH).templatePresentation.description.contains("named scope"))

        val group = manager.getAction(AnsibleToolWindowPanel.TOOLBAR_EXTRA_GROUP) as DefaultActionGroup
        assertEquals("[Scope ▾] comes first (D45)", COMBO, manager.getId(group.getChildActionsOrStubs().first()))
    }

    // ------------------------------------------------------------------------------------------------ the combo

    fun testTheComboShowsTheScopeInToolbarsOnly() {
        copyInfra()
        val combo = manager.getAction(COMBO)
        val inToolbar = update(combo, ActionUiKind.TOOLBAR, ActionPlaces.TOOLBAR)
        assertTrue(inToolbar.isEnabledAndVisible)
        assertEquals("Scope: All roots", inToolbar.text)
        assertTrue(inToolbar.description, inToolbar.description.startsWith("Workspace scope All roots: falcon + heron + pelican + 4 more"))
        assertTrue(inToolbar.description, inToolbar.description.contains("hover, completion, Ctrl+B and inspections stay the same"))
        assertFalse("Find Action offers Switch Scope… instead", update(combo, ActionUiKind.SEARCH_POPUP, ActionPlaces.ACTION_SEARCH).isVisible)

        addScope("prod_only", "file:repos/falcon/ansible/environments/prod//*")
        service.set(ScopeChoice.Named("prod_only"))
        assertEquals("names keep their underscores (no mnemonic)", "Scope: prod_only", update(combo, ActionUiKind.TOOLBAR, ActionPlaces.TOOLBAR).text)
        service.set(ScopeChoice.Named("gone"))
        val deleted = update(combo, ActionUiKind.TOOLBAR, ActionPlaces.TOOLBAR)
        assertEquals("Scope: gone (deleted)", deleted.text)
        assertTrue(deleted.description, deleted.description.contains("Scope 'gone' was deleted"))
    }

    // ------------------------------------------------------------------------------------------------ the popup

    fun testThePopupListsEveryChoiceWithItsCoverage() {
        copyInfra()
        addScope("hawk", "file:repos/hawk//*")
        addScope("pelican", "file:repos/pelican//*")
        addScope("falcon", "file:repos/falcon//*")
        addScope("mine", "file:repos/wren//*", local)
        service.set(ScopeChoice.Named("falcon"))
        service.catalog()
        waitFor("coverage is computed") { service.catalog().let { c -> (c.userScopes + c.predefinedScopes).all { it.coverage != null } } }

        val rows = rows(ScopePopup.group(project))
        val head = rows.takeWhile { it != "-- Other scopes" }
        assertEquals(
            listOf(
                "( ) All roots | 7 roots",
                "( ) Current file's root | no Ansible file selected",
                "-- Project scopes",
                "(x) falcon | falcon",
                "( ) pelican | pelican + pelican › danger_zone/database",
                "( ) mine (local) | wren",
                "( ) hawk | matches no Ansible root",
            ),
            head,
        )
        val tail = rows.dropWhile { it != "--" }
        assertEquals(
            listOf(
                "--",
                "( ) Choose roots…",
                "[disabled] Not covered by a named scope: heron, platform, golden",
                "--",
                "Edit Scopes…",
                "Open in Project View",
            ),
            tail,
        )
        val other = rows.subList(head.size + 1, rows.size - tail.size)
        assertTrue("predefined scopes are listed: $other", other.isNotEmpty() && other.all { it.startsWith("( ) ") })
    }

    fun testADeletedScopeStaysSelectedInThePopup() {
        copyInfra()
        service.set(ScopeChoice.Named("falcon"))
        val rows = rows(ScopePopup.group(project))
        assertTrue(rows.toString(), "(x) falcon (deleted) | deleted; all roots are shown" in rows)
        assertTrue(rows.toString(), "-- Project scopes" in rows)
        assertTrue("no footer without user scopes", rows.none { it.contains("Not covered") })
    }

    fun testChoosingAnItemStoresIt() {
        copyInfra()
        addScope("falcon", "file:repos/falcon//*")
        val choice = ScopePopup.group(project).getChildActionsOrStubs()
            .filterIsInstance<ScopePopup.ChoiceAction>().single { it.choice == ScopeChoice.Named("falcon") }
        choice.actionPerformed(event(choice))
        assertEquals(ScopeChoice.Named("falcon"), service.choice())
        choice.actionPerformed(event(choice))
        assertEquals("choosing the selected item again keeps it", ScopeChoice.Named("falcon"), service.choice())
        val current = ScopePopup.group(project).getChildActionsOrStubs()
            .filterIsInstance<ScopePopup.ChoiceAction>().single { it.choice == ScopeChoice.CurrentFileRoot }
        current.actionPerformed(event(current))
        assertEquals(ScopeChoice.CurrentFileRoot, service.choice())
    }

    fun testChooseRootsOpensTheDialogAndStoresTheKeys() {
        copyInfra()
        val choose = ScopePopup.group(project).getChildActionsOrStubs().filterIsInstance<ScopePopup.ChooseRootsAction>().single()
        ui.chooseResult = setOf("repos/heron/ansible", "golden")
        choose.actionPerformed(event(choose))
        assertEquals(ScopeChoice.Roots(setOf("repos/heron/ansible", "golden")), service.choice())
        val (options, initial) = ui.chooseCalls.single()
        assertEquals("every root is offered", 7, options.size)
        assertEquals("under All roots every root starts checked", options.map { it.key }.toSet(), initial)

        val again = ScopePopup.group(project).getChildActionsOrStubs().filterIsInstance<ScopePopup.ChooseRootsAction>().single()
        assertEquals("( ) Choose roots… | heron + golden".replace("( )", "(x)"), row(again))
        ui.chooseResult = null
        again.actionPerformed(event(again))
        assertEquals("cancel keeps the choice", ScopeChoice.Roots(setOf("repos/heron/ansible", "golden")), service.choice())
        assertEquals("the stored roots start checked", setOf("repos/heron/ansible", "golden"), ui.chooseCalls.last().second)
    }

    fun testTheChooseRootsDialogNeedsOneRoot() {
        copyInfra()
        val dialog = ChooseRootsDialog(project, service.rootOptions(), setOf("repos/falcon/ansible"))
        try {
            assertEquals(setOf("repos/falcon/ansible"), dialog.selection())
            assertTrue(dialog.isOKActionEnabled)
            dialog.checkBoxes.getValue("repos/falcon/ansible").doClick()
            assertTrue(dialog.selection().isEmpty())
            assertFalse("OK needs a root", dialog.isOKActionEnabled)
            dialog.checkBoxes.getValue("golden").doClick()
            dialog.checkBoxes.getValue("repos/pelican/ansible").doClick()
            assertEquals("display order", listOf("repos/pelican/ansible", "golden"), dialog.selection().toList())
            assertEquals(7, dialog.checkBoxes.size)
        } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
    }

    // ------------------------------------------------------------------------------------------------ edit, open

    fun testEditScopesPreselectsTheCurrentUserScope() {
        copyInfra()
        val edit = manager.getAction(EDIT)
        assertTrue(update(edit, ActionUiKind.SEARCH_POPUP, ActionPlaces.ACTION_SEARCH).isEnabledAndVisible)
        edit.actionPerformed(event(edit))
        addScope("falcon", "file:repos/falcon//*")
        service.set(ScopeChoice.Named("falcon"))
        edit.actionPerformed(event(edit))
        val predefined = service.catalog().predefinedScopes.first()
        service.set(ScopeChoice.Named(predefined.scopeId))
        edit.actionPerformed(event(edit))
        assertEquals("All roots, a user scope, a predefined scope", listOf(null, "falcon", null), ui.editCalls)
    }

    fun testOpenInProjectViewNeedsANamedScopeOrOneRoot() {
        copyInfra()
        val open = manager.getAction(OPEN)
        assertFalse("All roots has no place in the Project view", update(open, ActionUiKind.SEARCH_POPUP, ActionPlaces.ACTION_SEARCH).isEnabled)
        assertTrue(update(open, ActionUiKind.SEARCH_POPUP, ActionPlaces.ACTION_SEARCH).isVisible)
        open.actionPerformed(event(open))
        assertTrue(ui.openCalls.isEmpty())

        addScope("falcon", "file:repos/falcon//*")
        service.set(ScopeChoice.Named("falcon"))
        assertTrue(update(open, ActionUiKind.SEARCH_POPUP, ActionPlaces.ACTION_SEARCH).isEnabled)
        open.actionPerformed(event(open))

        service.set(ScopeChoice.Roots(setOf("repos/heron/ansible")))
        open.actionPerformed(event(open))
        service.editorSelectedForTests(vf("repos/pelican/ansible/ansible.cfg"), root("pelican"))
        service.set(ScopeChoice.CurrentFileRoot)
        open.actionPerformed(event(open))
        assertEquals(
            listOf(
                ProjectViewTarget.ScopePane("falcon", "falcon"),
                ProjectViewTarget.Directory(vf("repos/heron/ansible")),
                ProjectViewTarget.Directory(vf("repos/pelican/ansible")),
            ),
            ui.openCalls,
        )

        service.set(ScopeChoice.Roots(setOf("repos/heron/ansible", "golden")))
        assertFalse("several roots have no single place", update(open, ActionUiKind.SEARCH_POPUP, ActionPlaces.ACTION_SEARCH).isEnabled)
        assertNull(ScopeCommands.projectViewTarget(project))
    }

    fun testSwitchScopeBuildsTheSelectorPopup() {
        copyInfra()
        val switch = manager.getAction(SWITCH)
        assertTrue(update(switch, ActionUiKind.SEARCH_POPUP, ActionPlaces.ACTION_SEARCH).isEnabledAndVisible)
        val popup = SwitchScopeAction.popup(project, context(project))
        try {
            assertEquals("Ansibility Workspace Scope", popup.listStep.title)
            assertTrue(popup.listStep.values.isNotEmpty())
        } finally {
            popup.cancel()
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private fun context(project: Project) = SimpleDataContext.getProjectContext(project)

    private fun event(action: AnAction, uiKind: ActionUiKind = ActionUiKind.POPUP, place: String = ActionPlaces.POPUP): AnActionEvent =
        AnActionEvent.createEvent(action, context(project), action.templatePresentation.clone(), place, uiKind, null)

    private fun update(action: AnAction, uiKind: ActionUiKind, place: String): Presentation {
        val event = event(action, uiKind, place)
        action.update(event)
        return event.presentation
    }

    /** One popup row as text: `(x) text | secondary` for choices, `-- title` for separators, `[disabled]` marks. */
    private fun row(action: AnAction): String {
        if (action is Separator) return listOfNotNull("--", action.text).joinToString(" ")
        val presentation = update(action, ActionUiKind.POPUP, ActionPlaces.POPUP)
        val prefix = when {
            action is ScopePopup.ChoiceAction || action is ScopePopup.ChooseRootsAction -> if (Toggleable.isSelected(presentation)) "(x) " else "( ) "
            !presentation.isEnabled -> "[disabled] "
            else -> ""
        }
        val secondary = presentation.getClientProperty(ActionUtil.SECONDARY_TEXT)?.let { " | $it" }.orEmpty()
        return prefix + presentation.text + secondary
    }

    private fun rows(group: DefaultActionGroup): List<String> = group.getChildActionsOrStubs().map(::row)

    /** Records what the actions ask the UI for. */
    private class RecordingScopeUi : ScopeUi {
        var chooseResult: Set<String>? = null
        val chooseCalls = ArrayList<Pair<List<RootOption>, Set<String>>>()
        val editCalls = ArrayList<String?>()
        val openCalls = ArrayList<ProjectViewTarget>()

        override fun chooseRoots(project: Project, options: List<RootOption>, initial: Set<String>): Set<String>? {
            chooseCalls += options to initial
            return chooseResult
        }

        override fun editScopes(project: Project, scopeName: String?) {
            editCalls += scopeName
        }

        override fun openInProjectView(project: Project, target: ProjectViewTarget) {
            openCalls += target
        }
    }

    private companion object {
        const val COMBO = "Ansibility.Scope.Combo"
        const val SWITCH = "Ansibility.Scope.Switch"
        const val EDIT = "Ansibility.Scope.EditScopes"
        const val OPEN = "Ansibility.Scope.OpenInProjectView"
    }
}
