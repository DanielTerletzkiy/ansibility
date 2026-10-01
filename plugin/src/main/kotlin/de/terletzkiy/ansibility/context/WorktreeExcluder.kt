package de.terletzkiy.ansibility.context

import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * X01 "Exclude worktree from project" (decision D9): adds a detached worktree as an excluded folder of the
 * module whose content root contains it. This edits the module (`.iml`) and hides the tree from every IDE
 * feature, Find in Files included, so it only ever runs on an explicit click.
 */
object WorktreeExcluder {

    /**
     * Excludes [dir] from the module containing it. Returns false when no module content root contains the
     * directory. Must be called on the EDT (the model commit takes the write lock itself).
     */
    fun exclude(project: Project, dir: VirtualFile): Boolean {
        val module = ModuleUtilCore.findModuleForFile(dir, project) ?: return false
        var excluded = false
        ModuleRootModificationUtil.updateModel(module) { model ->
            val entry = model.contentEntries.firstOrNull { entry ->
                entry.file?.let { VfsUtilCore.isAncestor(it, dir, false) } == true
            } ?: return@updateModel
            if (dir !in entry.excludeFolderFiles) entry.addExcludeFolder(dir)
            excluded = true
        }
        return excluded
    }
}
