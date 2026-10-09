package de.terletzkiy.ansibility.golden.take

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import de.terletzkiy.ansibility.golden.AnsibilityGoldenBundle.message
import de.terletzkiy.ansibility.golden.GoldenDataKeys
import de.terletzkiy.ansibility.golden.GoldenTarget
import de.terletzkiy.ansibility.golden.GoldenTargets
import de.terletzkiy.ansibility.golden.compare.GoldenActionTexts
import de.terletzkiy.ansibility.model.role.RoleCatalog
import org.jetbrains.annotations.Nls

/**
 * The single-file takes (plan amendment R24, X121), from one file: the editor's file, a role file row or a
 * "Differences from golden" row of the Ansibility tool window (`GoldenDataKeys.ROLE_COPY` + `ROLE_PATH`, also a file
 * only golden has), or the Project view's single selected file ([GoldenTargets.of]).
 *
 * Shown when the role has a golden copy and the selection is a file path (not a folder) that exists in this copy or in
 * golden; hidden on the golden copy itself. Texts by place like the other golden actions: short in menus,
 * "Ansibility: …" in Find Action.
 */
abstract class TakeFileAction(private val direction: TakeDirection) : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    @Nls
    protected abstract fun menuText(): String

    override fun update(e: AnActionEvent) {
        val target = targetOf(e.project, e)
        e.presentation.isEnabledAndVisible = target != null
        if (target != null) e.presentation.text = GoldenActionTexts.forPlace(e, menuText())
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = targetOf(project, e) ?: return
        val service = TakeService.getInstance(project)
        if (direction == TakeDirection.FROM_GOLDEN) service.takeGoldens(target) else service.takeIntoGolden(target)
    }

    companion object {
        /**
         * The selected file of a copy that has a golden copy other than itself, or null. VFS only (BGT `update`):
         * the path must name a file, not a folder, in this copy or in golden.
         */
        fun targetOf(project: Project?, e: AnActionEvent): GoldenTarget? {
            if (project == null || project.isDisposed) return null
            if (e.getData(GoldenDataKeys.ROLE_COPY) == null && (e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.size ?: 1) > 1) return null
            val target = GoldenTargets.of(project, e.dataContext) ?: return null
            val relPath = target.relPath ?: return null
            val golden = RoleCatalog.getInstance(project).snapshot().reference(target.copy.name) ?: return null
            if (golden.dir == target.copy.dir) return null
            val here = target.copy.dir.findFileByRelativePath(relPath)?.takeIf { it.isValid }
            val there = golden.dir.findFileByRelativePath(relPath)?.takeIf { it.isValid }
            if (here?.isDirectory == true || there?.isDirectory == true) return null
            return target.takeIf { here != null || there != null }
        }
    }
}

/**
 * "Take Golden's Version" (X121): golden's file replaces this copy's, is created here when only golden has it, or this
 * copy's file is deleted (after a question) when golden has none.
 */
class TakeGoldensAction : TakeFileAction(TakeDirection.FROM_GOLDEN) {
    override fun menuText(): String = message("action.take.goldens.menu")
}

/**
 * "Take This into Golden" (X121): this copy's file replaces golden's, is created in golden when only this copy has
 * it, or golden's file is deleted (after a question) when this copy has none.
 */
class TakeIntoGoldenAction : TakeFileAction(TakeDirection.INTO_GOLDEN) {
    override fun menuText(): String = message("action.take.into.menu")
}
