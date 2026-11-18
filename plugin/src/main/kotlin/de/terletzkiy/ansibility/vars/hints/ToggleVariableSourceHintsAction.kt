package de.terletzkiy.ansibility.vars.hints

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.vars.AnsibilityVarsBundle

/**
 * "Ansibility: Toggle Variable Source Hints" (plan amendment R31, D236): Find Action, assignable to a shortcut, and the
 * editor's right-click menu of files in Ansible roots. It flips the platform's switch of the hint provider, the same one
 * as Settings › Editor › Inlay Hints and the hint's own "Disable" menu, so it applies to every project; its text says
 * "Show …" or "Hide …" by the current state.
 */
class ToggleVariableSourceHintsAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val shown = VarSourceHintsRefresh.isProviderEnabled()
        e.presentation.text = AnsibilityVarsBundle.message(if (shown) "hints.action.hide" else "hints.action.show")
        if (e.isFromContextMenu) {
            val project = e.project
            val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
            e.presentation.isEnabledAndVisible = project != null && !project.isDisposed && file != null && !file.isDirectory &&
                AnsibleWorkspace.getInstance(project).rootFor(file) != null
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        VarSourceHintsRefresh.setEnabled(!VarSourceHintsRefresh.isProviderEnabled())
    }
}
