package de.terletzkiy.ansibility.golden.align

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import de.terletzkiy.ansibility.golden.AnsibilityGoldenBundle.message
import de.terletzkiy.ansibility.golden.GoldenTarget
import de.terletzkiy.ansibility.golden.GoldenTargets
import de.terletzkiy.ansibility.golden.compare.CompareWithGoldenAction
import de.terletzkiy.ansibility.golden.compare.GoldenActionTexts
import de.terletzkiy.ansibility.model.role.RoleCatalog
import org.jetbrains.annotations.Nls

/**
 * "Merge into Golden…" (plan amendment R24, D184): the selected copy (a role copy row or file of the tool window, the
 * editor's file, the Project view's selection) is merged into the golden copy of its role. Hidden without a golden
 * copy, on the golden copy itself and when the golden copy is in the external golden root (plan amendment R25, D198:
 * the mirror or folder is read-only). The Roles tab's details offer it as a button.
 */
class MergeIntoGoldenAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val target = targetOf(e.project, e)
        e.presentation.isEnabledAndVisible = target != null
        if (target != null) e.presentation.text = GoldenActionTexts.forPlace(e, message("action.align.mergeInto.menu"))
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = targetOf(project, e) ?: return
        AlignService.getInstance(project).mergeIntoGolden(target)
    }

    private fun targetOf(project: Project?, e: AnActionEvent): GoldenTarget? {
        val target = CompareWithGoldenAction.goldenTargetOf(project, e) ?: return null
        val golden = RoleCatalog.getInstance(project ?: return null).snapshot().reference(target.copy.name) ?: return null
        return target.takeIf { !golden.isExternal }
    }
}

/**
 * "Align with Golden…" (plan amendment R24, D184, the "help me align" bonus): the golden copy is merged into the
 * selected copy. Hidden without a golden copy and on the golden copy itself.
 */
class AlignWithGoldenAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val target = CompareWithGoldenAction.goldenTargetOf(e.project, e)
        e.presentation.isEnabledAndVisible = target != null
        if (target != null) e.presentation.text = GoldenActionTexts.forPlace(e, message("action.align.with.menu"))
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = CompareWithGoldenAction.goldenTargetOf(project, e) ?: return
        AlignService.getInstance(project).alignWithGolden(target)
    }
}

/**
 * "Align Role…" (plan amendment R24, D184): an opening step picks the target (by default the golden copy) and the
 * source (by default the selected copy) among every copy of the role, then the merge workspace. Shown when the role
 * has another copy; it works without a golden root too.
 */
class AlignRoleAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val target = targetOf(e)
        e.presentation.isEnabledAndVisible = target != null
        if (target != null) e.presentation.text = GoldenActionTexts.forPlace(e, menuText())
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = targetOf(e) ?: return
        AlignService.getInstance(project).alignRole(target)
    }

    @Nls
    private fun menuText(): String = message("action.align.role.menu")

    private fun targetOf(e: AnActionEvent): GoldenTarget? {
        val project = e.project?.takeIf { !it.isDisposed } ?: return null
        val target = GoldenTargets.of(project, e.dataContext) ?: return null
        return target.takeIf { RoleCatalog.getInstance(project).snapshot().copies(it.copy.name).size > 1 }
    }
}
