package de.terletzkiy.ansibility.workspace.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import de.terletzkiy.ansibility.workspace.AnsibilityScopeBundle
import de.terletzkiy.ansibility.workspace.WorkspaceScopeServiceImpl
import javax.swing.JComponent

/** The plugin's scope service of the event's project, or null (no project, or a replaced service). */
private fun AnActionEvent.scopeService(): WorkspaceScopeServiceImpl? =
    project?.takeUnless { it.isDisposed }?.let { WorkspaceScopeServiceImpl.getInstance(it) }

/**
 * `[Scope: All roots ▾]`, the first item of the Ansibility tool window's toolbar (F9.1, D45; joins
 * `Ansibility.ToolWindow.Toolbar.Extra` from `ansibility-workspace.xml`). The popup is [ScopePopup].
 *
 * Shown in toolbars only: Find Action offers "Ansibility: Switch Scope…" ([SwitchScopeAction]) instead, which opens the
 * same popup. Uses the current `createPopupActionGroup(JComponent, DataContext)` overload (the one-argument form is
 * deprecated).
 */
class AnsibleScopeComboAction : ComboBoxAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val service = e.scopeService()
        if (service == null || !e.isFromActionToolbar) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val scope = service.currentScope()
        e.presentation.isEnabledAndVisible = true
        e.presentation.setText(ScopeCommands.comboText(scope), false)
        e.presentation.description = ScopeCommands.comboTooltip(scope)
    }

    override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup =
        CommonDataKeys.PROJECT.getData(dataContext)?.takeUnless { it.isDisposed }?.let(ScopePopup::group) ?: DefaultActionGroup()

    /** The "not covered by a named scope" footer is a disabled line. */
    override fun shouldShowDisabledActions(): Boolean = true
}

/** "Ansibility: Switch Scope…" (Find Action): the scope selector's popup, wherever the action is invoked. */
class SwitchScopeAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.scopeService() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        popup(project, e.dataContext).showInBestPositionFor(e.dataContext)
    }

    companion object {
        /** The selector as a list popup titled "Ansibility Workspace Scope", with speed search and the disabled footer line. */
        fun popup(project: Project, dataContext: DataContext) = JBPopupFactory.getInstance().createActionGroupPopup(
            AnsibilityScopeBundle.message("popup.title"),
            ScopePopup.group(project),
            dataContext,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true,
        )
    }
}

/** "Ansibility: Edit Scopes…": the platform's Scopes settings, on the current named scope when it is a user scope. */
class EditScopesAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.scopeService() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        ScopeCommands.editScopes(e.project ?: return)
    }
}

/**
 * "Ansibility: Open Scope in Project View": the Project view's Scope pane on the current named scope, or the Project pane
 * with the one root of "Current file's root" or a single chosen root selected. Disabled for All roots and for several
 * chosen roots, which have no single place in the Project view.
 */
class OpenScopeInProjectViewAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val service = e.scopeService()
        e.presentation.isVisible = service != null
        e.presentation.isEnabled = project != null && service != null && ScopeCommands.projectViewTarget(project) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        ScopeCommands.openInProjectView(e.project ?: return)
    }
}
