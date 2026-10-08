package de.terletzkiy.ansibility.vault.vcs.impl

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.FileStatusListener
import com.intellij.openapi.vcs.FileStatusManager
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.vault.vcs.TrackedStatus
import de.terletzkiy.ansibility.vault.vcs.TrackedStatusLookup

/**
 * The VCS side of [TrackedStatusLookup] (plan amendment R21, D162), registered only by the optional fragment
 * `ansibility-vcs.xml`, so this class (and the VCS classes it uses) loads only with `com.intellij.modules.vcs`.
 *
 * Public API only: `ProjectLevelVcsManager.getVcsFor` (is the file in a VCS root at all) and `getVcsRootFor` (its
 * repository, for the Vault tab's grouping), `ChangeListManager`'s
 * cached statuses (`isIgnoredFile`, `isUnversioned`, `getStatus`), and `FileStatusManager.addFileStatusListener`
 * with a disposable (the listener topic is internal API). The change list manager answers from its last refresh, so
 * right after startup a new file may count as tracked until the refresh reports it; the listener then restarts the
 * daemon for it ([de.terletzkiy.ansibility.vault.vcs.TrackedStatuses]).
 */
class VcsTrackedStatusLookup : TrackedStatusLookup {
    override fun status(project: Project, file: VirtualFile): TrackedStatus {
        if (project.isDisposed || !file.isValid) return TrackedStatus.NO_VCS
        ProjectLevelVcsManager.getInstance(project).getVcsFor(file) ?: return TrackedStatus.NO_VCS
        val changes = ChangeListManager.getInstance(project)
        return when {
            changes.isIgnoredFile(file) -> TrackedStatus.IGNORED
            changes.isUnversioned(file) -> TrackedStatus.UNTRACKED
            else -> of(changes.getStatus(file))
        }
    }

    override fun watch(project: Project, parent: Disposable, changed: (VirtualFile?) -> Unit) {
        FileStatusManager.getInstance(project).addFileStatusListener(
            object : FileStatusListener {
                override fun fileStatusesChanged() = changed(null)

                override fun fileStatusChanged(virtualFile: VirtualFile) = changed(virtualFile)
            },
            parent,
        )
    }

    /** `ProjectLevelVcsManager.getVcsRootFor`: the root of the repository [file] is mapped to, or null. */
    override fun repositoryOf(project: Project, file: VirtualFile): VirtualFile? {
        if (project.isDisposed || !file.isValid) return null
        return ProjectLevelVcsManager.getInstance(project).getVcsRootFor(file)
    }

    /**
     * After the VCS mappings are initialized (`ProjectLevelVcsManager.runAfterInitialization`) and, with an active VCS,
     * after the change list manager's next refresh (`ChangeListManager.invokeAfterUpdate`), so untracked and ignored
     * files are known; at once in a project without any active VCS.
     */
    override fun whenReady(project: Project, ready: () -> Unit) {
        if (project.isDisposed) return
        val vcs = ProjectLevelVcsManager.getInstance(project)
        vcs.runAfterInitialization {
            if (project.isDisposed) return@runAfterInitialization
            if (!vcs.hasActiveVcss()) ready() else ChangeListManager.getInstance(project).invokeAfterUpdate(false) { ready() }
        }
    }

    companion object {
        /** The [TrackedStatus] of a [FileStatus] the change list manager reports for a file inside a VCS root. */
        fun of(status: FileStatus): TrackedStatus = when (status) {
            FileStatus.IGNORED -> TrackedStatus.IGNORED
            FileStatus.UNKNOWN -> TrackedStatus.UNTRACKED
            FileStatus.ADDED -> TrackedStatus.ADDED
            else -> TrackedStatus.TRACKED
        }
    }
}
