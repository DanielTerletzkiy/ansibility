package de.terletzkiy.ansibility.golden.vcs.impl

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsUtil
import de.terletzkiy.ansibility.golden.history.LocalChangesLookup

/**
 * The VCS side of [LocalChangesLookup] (plan amendment R24, D188), registered only by the optional fragment
 * `ansibility-vcs.xml`, so this class (and the VCS classes it uses) loads only with `com.intellij.modules.vcs`.
 *
 * Public API only: `ProjectLevelVcsManager.getVcsFor` (is the directory under a VCS at all), then the change list
 * manager's cached state: `ChangeListManager.getChangesIn(VirtualFile)` (modified, added, deleted and moved files below
 * the directory) and `getUnversionedFilesPaths` with `FilePath.isUnder` (new files git does not track yet). Ignored
 * files do not count. The answer is as fresh as the change list manager's last refresh.
 */
class PushLocalChangesLookup : LocalChangesLookup {
    override fun hasLocalChanges(project: Project, dir: VirtualFile): Boolean? {
        if (project.isDisposed || !dir.isValid || !dir.isDirectory) return null
        ProjectLevelVcsManager.getInstance(project).getVcsFor(dir) ?: return null
        val changes = ChangeListManager.getInstance(project)
        if (changes.getChangesIn(dir).isNotEmpty()) return true
        val path = VcsUtil.getFilePath(dir)
        return changes.unversionedFilesPaths.any { it.isUnder(path, false) }
    }
}
