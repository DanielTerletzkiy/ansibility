package de.terletzkiy.ansibility.golden.vcs.impl

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FileStatusListener
import com.intellij.openapi.vcs.FileStatusManager
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.history.VcsFileRevision
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.history.VcsHistoryProviderEx
import com.intellij.vcsUtil.VcsUtil
import de.terletzkiy.ansibility.golden.history.LastChange
import de.terletzkiy.ansibility.golden.history.LastChangeLookup
import java.time.Instant

/**
 * The VCS side of [LastChangeLookup] (plan amendment R24, D183), registered only by the optional fragment
 * `ansibility-vcs.xml`, so this class (and the VCS classes it uses) loads only with `com.intellij.modules.vcs`.
 *
 * Public API only: `ProjectLevelVcsManager.getVcsFor` → `AbstractVcs.getVcsHistoryProvider`; the last revision through
 * `VcsHistoryProviderEx.getLastRevision(FilePath)` (git implements it: one `git log -1` per path), else the first
 * revision of `createSessionFor`. Untracked and ignored files have none (`ChangeListManager`'s cached statuses).
 * Directories need `supportsHistoryForDirectories`. Changes are watched through
 * `FileStatusManager.addFileStatusListener` (a commit changes the statuses of the committed files).
 */
class VcsLastChangeLookup : LastChangeLookup {
    override fun lastChange(project: Project, file: VirtualFile): LastChange? = lookup(project, file, directory = false)

    override fun lastChangeUnder(project: Project, dir: VirtualFile): LastChange? = lookup(project, dir, directory = true)

    override fun watch(project: Project, parent: Disposable, changed: (VirtualFile?) -> Unit) {
        FileStatusManager.getInstance(project).addFileStatusListener(
            object : FileStatusListener {
                override fun fileStatusesChanged() = changed(null)

                override fun fileStatusChanged(virtualFile: VirtualFile) = changed(virtualFile)
            },
            parent,
        )
    }

    private fun lookup(project: Project, file: VirtualFile, directory: Boolean): LastChange? {
        if (project.isDisposed || !file.isValid || file.isDirectory != directory) return null
        val vcs = ProjectLevelVcsManager.getInstance(project).getVcsFor(file) ?: return null
        val changes = ChangeListManager.getInstance(project)
        if (changes.isIgnoredFile(file) || (!directory && changes.isUnversioned(file))) return null
        val provider = vcs.vcsHistoryProvider ?: return null
        if (directory && !provider.supportsHistoryForDirectories()) return null
        val path = VcsUtil.getFilePath(file)
        val revision = if (provider is VcsHistoryProviderEx) provider.getLastRevision(path)
        else provider.createSessionFor(path)?.revisionList?.firstOrNull()
        return revision?.let(::of)
    }

    companion object {
        /** A revision as a [LastChange]: the subject is the message's first line, the hash cut to eight characters. */
        fun of(revision: VcsFileRevision): LastChange? {
            val date = revision.revisionDate ?: return null
            val number = revision.revisionNumber.asString()
            return LastChange(
                author = revision.author.orEmpty(),
                date = Instant.ofEpochMilli(date.time),
                subject = revision.commitMessage.orEmpty().lineSequence().firstOrNull().orEmpty().trim(),
                shortHash = if (number.length > SHORT_HASH && number.all { it.isLetterOrDigit() }) number.take(SHORT_HASH) else number,
            )
        }

        private const val SHORT_HASH = 8
    }
}
