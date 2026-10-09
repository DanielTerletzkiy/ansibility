package de.terletzkiy.ansibility.golden.push

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import de.terletzkiy.ansibility.golden.AnsibilityGoldenBundle.message
import de.terletzkiy.ansibility.golden.GoldenTargets
import de.terletzkiy.ansibility.golden.compare.GoldenActionTexts
import de.terletzkiy.ansibility.model.role.RoleCatalog
import de.terletzkiy.ansibility.model.role.RoleCopy

/**
 * "Push Role to Repos…" (plan amendment R24, D188): the role copy of the selection ([GoldenTargets.of]: the tool
 * window's `GoldenDataKeys.ROLE_COPY`, or a file inside a role copy) is pushed to the copies and roots the dialog's
 * user ticks ([PushService]). It works from a bare data context holding the project and `ROLE_COPY` (the details
 * pane's button, the end of Align).
 *
 * Shown for any role copy that has another copy or a root without the role; it does not need a golden root.
 */
class PushToReposAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val source = sourceOf(e)
        e.presentation.isEnabledAndVisible = source != null
        if (source != null) e.presentation.text = GoldenActionTexts.forPlace(e, message("action.push.menu"))
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val source = sourceOf(e) ?: return
        PushService.getInstance(project).push(source)
    }

    /** The selection's role copy when there is somewhere to push it. */
    private fun sourceOf(e: AnActionEvent): RoleCopy? {
        val project = e.project?.takeIf { !it.isDisposed } ?: return null
        val copy = GoldenTargets.of(project, e.dataContext)?.copy ?: return null
        val catalog = RoleCatalog.getInstance(project).snapshot()
        if (catalog.copies(copy.name).any { it.dir != copy.dir }) return copy
        return copy.takeIf { PushRows.rootsWithout(project, catalog, copy.name).isNotEmpty() }
    }
}
