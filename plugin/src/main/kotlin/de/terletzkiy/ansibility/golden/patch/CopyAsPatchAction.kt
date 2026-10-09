package de.terletzkiy.ansibility.golden.patch

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
import de.terletzkiy.ansibility.model.drift.DriftCategory
import de.terletzkiy.ansibility.model.drift.DriftRules
import de.terletzkiy.ansibility.model.drift.RoleDriftService
import de.terletzkiy.ansibility.model.role.RoleCatalog
import de.terletzkiy.ansibility.model.role.RoleCopy

/**
 * "Copy as Patch for Golden…" (plan amendment R25, X126): from a role copy that is not the golden copy (a copy row or
 * a "Differences from golden" row of the Ansibility tool window, a role file in the editor or the Project view), a
 * unified diff that turns golden into this copy ([PatchService]).
 *
 * Hidden without a golden copy, on the golden copy itself, for a path in neither copy, for several selected files, and
 * when the drift known now ([RoleDriftService.cached], never computed here) says nothing differs: for a copy row, an
 * identical copy; for a file or folder, no differing path there. A path drift does not look at (`molecule/` while
 * drift ignores it) stays offered. Texts by place like the other golden actions: short in menus, "Ansibility: …" in
 * Find Action.
 */
class CopyAsPatchAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val target = targetOf(e.project, e)
        e.presentation.isEnabledAndVisible = target != null
        if (target != null) e.presentation.text = GoldenActionTexts.forPlace(e, message("action.patch.menu"))
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = targetOf(project, e) ?: return
        PatchService.getInstance(project).copyAsPatch(target)
    }

    companion object {
        /** The selection's target when the patch applies to it (see the class comment). VFS and cached drift only. */
        fun targetOf(project: Project?, e: AnActionEvent): GoldenTarget? {
            if (project == null || project.isDisposed) return null
            if (e.getData(GoldenDataKeys.ROLE_COPY) == null && (e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.size ?: 1) > 1) return null
            val target = GoldenTargets.of(project, e.dataContext) ?: return null
            val golden = RoleCatalog.getInstance(project).snapshot().reference(target.copy.name) ?: return null
            if (golden.dir == target.copy.dir) return null
            val relPath = target.relPath
            if (relPath != null) {
                val normalized = GoldenTargets.normalize(relPath) ?: return null
                val here = target.copy.dir.findFileByRelativePath(normalized)?.takeIf { it.isValid }
                val there = golden.dir.findFileByRelativePath(normalized)?.takeIf { it.isValid }
                if (here == null && there == null) return null
            }
            return target.takeUnless { knownSame(project, target, golden) }
        }

        /**
         * Whether the cached drift of [target]'s role, computed against [golden], says nothing differs in the
         * selection. Not known (never computed, another golden copy, a path drift ignores): false.
         */
        private fun knownSame(project: Project, target: GoldenTarget, golden: RoleCopy): Boolean {
            val service = RoleDriftService.getInstance(project)
            val drift = service.cached(target.copy.name) ?: return false
            if (drift.reference?.dir != golden.dir || drift.options != service.options) return false
            val copy = drift.copyOf(target.copy.dir) ?: return false
            val relPath = target.relPath ?: return copy.paths.isEmpty
            if (drift.options.ignoreMolecule && DriftRules.categoryOf(relPath) == DriftCategory.MOLECULE) return false
            return copy.paths.all.none { it == relPath || it.startsWith("$relPath/") }
        }
    }
}
