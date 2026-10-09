package de.terletzkiy.ansibility.golden.vcs.impl

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.AbstractVcsHelper
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vcs.merge.MergeData
import com.intellij.openapi.vcs.merge.MergeDialogCustomizer
import com.intellij.openapi.vcs.merge.MergeProvider2
import com.intellij.openapi.vcs.merge.MergeSession
import com.intellij.openapi.vcs.merge.MergeSessionEx
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.ColumnInfo
import com.intellij.vcsUtil.VcsUtil
import de.terletzkiy.ansibility.golden.AnsibilityGoldenBundle.message
import de.terletzkiy.ansibility.golden.align.AlignException
import de.terletzkiy.ansibility.golden.align.AlignResolution
import de.terletzkiy.ansibility.golden.align.AlignRow
import de.terletzkiy.ansibility.golden.align.AlignSession
import de.terletzkiy.ansibility.golden.align.AlignSide
import de.terletzkiy.ansibility.golden.align.RoleMergeDialog
import de.terletzkiy.ansibility.golden.history.LastChanges
import kotlin.time.Duration.Companion.milliseconds

/**
 * The merge workspace of Align as the platform's Conflicts dialog (plan amendment R24, D185), the one of a Git merge:
 * `AbstractVcsHelper.showMergeDialogWithResult` with [AlignMergeProvider] and [AlignMergeCustomizer]. Registered only
 * by the optional fragment `ansibility-vcs.xml` (it loads with `com.intellij.modules.vcs`). Public API only; the
 * dialog class itself is never referenced.
 *
 * How 262's dialog uses the provider (verified in its bytecode):
 * - Accept Yours/Theirs call [MergeSessionEx.acceptFilesRevisions] on a background thread under a modal progress, then
 *   [MergeSessionEx.conflictResolvedForFiles]; the dialog's own `setBinaryContent` path never runs for a session.
 * - With the iterative flow (registry `vcs.merge.conflict.iterative.resolution`, on by default), Accept on a text file
 *   whose merge viewer is built in goes through the platform's merge model instead: the target's document gets the
 *   chosen side, it is saved, and the dialog reports the resolution when it closes. Files it treats as binary always
 *   come to [MergeSessionEx.acceptFilesRevisions].
 * - Merge… (and a double click) build the three-way request from [AlignMergeProvider.loadRevisions] with the row's
 *   file as output; its callback saves the document and reports Merged, Accepted Yours or Accepted Theirs.
 */
class AlignVcsMergeDialog : RoleMergeDialog {
    override fun show(project: Project, session: AlignSession) {
        AbstractVcsHelper.getInstance(project).showMergeDialogWithResult(session.rows.map { it.file }, AlignMergeProvider(session), AlignMergeCustomizer(session))
    }
}

/**
 * The committed revision a side of the merge window shows (U1), so the platform puts `DiffVcsDataKeys.REVISION_INFO`
 * on that side (`MergeUtils.putRevisionInfos`) and its Annotate (Git blame) works in the merge window.
 */
fun interface SideRevisionLookup {
    /** The path and current revision of [file] when its content is that revision's (tracked, unchanged), else null. Background. */
    fun revisionOf(file: VirtualFile): Pair<FilePath, VcsRevisionNumber>?
}

/**
 * The VCS's answer for [SideRevisionLookup]: the file is under a VCS, its status is "not changed" (so its bytes are
 * the current revision's) and the VCS names its current revision. Public API only.
 */
class VcsSideRevisions(private val project: Project) : SideRevisionLookup {
    override fun revisionOf(file: VirtualFile): Pair<FilePath, VcsRevisionNumber>? {
        if (project.isDisposed || !file.isValid || !file.isInLocalFileSystem) return null
        val vcs = ProjectLevelVcsManager.getInstance(project).getVcsFor(file) ?: return null
        if (ChangeListManager.getInstance(project).getStatus(file) != FileStatus.NOT_CHANGED) return null
        val revision = vcs.diffProvider?.getCurrentRevision(file) ?: return null
        return VcsUtil.getFilePath(file) to revision
    }
}

/**
 * The rows of an [AlignSession] as merge conflicts (plan amendment R24, D185/D186): "yours" is the target, "theirs"
 * the source, and the base is the target, so every difference is an incoming, non-conflicting change.
 */
class AlignMergeProvider(
    val session: AlignSession,
    private val revisionLookup: SideRevisionLookup = VcsSideRevisions(session.project),
) : MergeProvider2 {
    /**
     * The sides of [file]'s row (D186): CURRENT = the target, ORIGINAL = CURRENT, LAST = the source; a missing side is
     * an empty array; key and vault files have three empty sides. A target or source changed since the comparison
     * fails with the reason, which the dialog shows.
     *
     * U1 (D183, blame in the merge window): a side whose file is tracked, unchanged against its current revision and
     * has no unsaved document gets its path and revision (`LAST_FILE_PATH`/`LAST_REVISION_NUMBER`, `CURRENT_…`), so the
     * platform's Annotate works on it; the last change of each side is looked up for the panel titles
     * ([AlignMergeCustomizer]) within [TITLE_BUDGET]. Never for key and vault files. Background thread.
     */
    override fun loadRevisions(file: VirtualFile): MergeData {
        val row = rowOf(file)
        val revisions = try {
            session.revisions(row)
        } catch (e: AlignException) {
            throw VcsException(e.message.orEmpty())
        }
        val data = MergeData().apply {
            CURRENT = revisions.target
            ORIGINAL = revisions.base
            LAST = revisions.source
            CONFLICT_TYPE = revisions.conflictType
        }
        if (!row.entry.sensitive) {
            row.entry.sourceFile?.let(::revisionOf)?.let { (path, number) ->
                data.LAST_FILE_PATH = path
                data.LAST_REVISION_NUMBER = number
            }
            row.entry.targetFile?.let(::revisionOf)?.let { (path, number) ->
                data.CURRENT_FILE_PATH = path
                data.CURRENT_REVISION_NUMBER = number
            }
            if (!row.acceptWholeOnly) LastChanges.getInstance(session.project).lastChanges(listOf(row.entry.targetFile, row.entry.sourceFile), TITLE_BUDGET)
        }
        return data
    }

    /** The revision of a side's [file] when the bytes the window shows are that revision's: no unsaved document. */
    private fun revisionOf(file: VirtualFile): Pair<FilePath, VcsRevisionNumber>? {
        if (!file.isValid || FileDocumentManager.getInstance().isFileModified(file)) return null
        return revisionLookup.revisionOf(file)
    }

    /** Not called while there is a session ([createMergeSession]); the session records resolutions. */
    override fun conflictResolvedForFile(file: VirtualFile) = Unit

    /** Files on one side only, binary files, key and vault files are accepted whole (and never written by the dialog). */
    override fun isBinary(file: VirtualFile): Boolean = session.row(file)?.acceptWholeOnly ?: true

    override fun createMergeSession(files: List<VirtualFile>): MergeSession = AlignMergeSession(session)

    internal fun rowOf(file: VirtualFile): AlignRow = session.row(file) ?: throw VcsException(message("align.unknownFile"))

    companion object {
        /** How long [loadRevisions] waits for the last change of the two sides (as Compare's titles, D183). */
        val TITLE_BUDGET = 1500.milliseconds
    }
}

/**
 * The dialog's session (plan amendment R24, D185): a Target and a Source column with each side's state, Merge… only
 * for changed text files, and accepting through [AlignSession.accept] (Accept Theirs writes through `RoleWriter`,
 * undoable, with the stale and read-only checks; Accept Yours writes nothing).
 */
class AlignMergeSession(val session: AlignSession) : MergeSessionEx {
    override fun getMergeInfoColumns(): Array<ColumnInfo<*, *>> = arrayOf(
        Side(session.texts.targetColumn) { session.texts.targetStatus(it) },
        Side(session.texts.sourceColumn) { session.texts.sourceStatus(it) },
    )

    override fun canMerge(file: VirtualFile): Boolean = session.row(file)?.canMerge == true

    override fun conflictResolvedForFile(file: VirtualFile, resolution: MergeSession.Resolution) = conflictResolvedForFiles(listOf(file), resolution)

    /** Accept Yours (nothing written) or Accept Theirs (the source written); a failure keeps the files unresolved. */
    override fun acceptFilesRevisions(files: List<VirtualFile>, resolution: MergeSession.Resolution) {
        val rows = files.mapNotNull { session.row(it) }
        val side = if (resolution == MergeSession.Resolution.AcceptedTheirs) AlignSide.SOURCE else AlignSide.TARGET
        try {
            session.accept(rows, side)
        } catch (e: AlignException) {
            throw VcsException(e.message.orEmpty())
        }
    }

    override fun conflictResolvedForFiles(files: List<VirtualFile>, resolution: MergeSession.Resolution) {
        val rows = files.mapNotNull { session.row(it) }
        session.resolved(rows, resolutionOf(resolution))
    }

    /** One side's column: the state of that side of each row ("Changed", "Only in source", "Missing"). */
    private inner class Side(name: String, private val status: (AlignRow) -> String) : ColumnInfo<VirtualFile, String>(name) {
        override fun valueOf(item: VirtualFile): String? = session.row(item)?.let(status)

        override fun getMaxStringValue(): String = session.rows.map(status).maxByOrNull { it.length } ?: name
    }

    companion object {
        fun resolutionOf(resolution: MergeSession.Resolution): AlignResolution = when (resolution) {
            MergeSession.Resolution.Merged -> AlignResolution.MERGED
            MergeSession.Resolution.AcceptedYours -> AlignResolution.KEPT
            MergeSession.Resolution.AcceptedTheirs -> AlignResolution.TAKEN_FROM_SOURCE
        }
    }
}

/**
 * The texts of the dialog and its viewer (plan amendment R24, D185): "Align web: golden ← falcon", "Target golden ·
 * source falcon · 2 key/vault files left out", panels "golden (target)", "Result", "falcon (source)" with each side's
 * last change when `LastChanges` has it already ("falcon (source) · 2026-09-12 · alice", D183/U1), columns Target and
 * Source. Only overrides (the class is meant to be overridden, never called).
 */
class AlignMergeCustomizer(private val session: AlignSession) : MergeDialogCustomizer() {
    override fun getMultipleFileDialogTitle(): String = session.texts.dialogTitle

    override fun getMultipleFileMergeDescription(files: Collection<VirtualFile>): String = session.texts.description

    override fun getMergeWindowTitle(file: VirtualFile): String =
        session.row(file)?.let { session.texts.mergeWindowTitle(it.relPath) } ?: session.texts.dialogTitle

    override fun getLeftPanelTitle(file: VirtualFile): String =
        session.texts.targetPanel(session.row(file)?.entry?.targetFile?.takeUnless { session.row(file)?.entry?.sensitive == true }?.let(::lastChangeOf))

    override fun getCenterPanelTitle(file: VirtualFile): String = session.texts.resultPanel

    override fun getRightPanelTitle(file: VirtualFile, revisionNumber: VcsRevisionNumber?): String =
        session.texts.sourcePanel(session.row(file)?.entry?.sourceFile?.takeUnless { session.row(file)?.entry?.sensitive == true }?.let(::lastChangeOf))

    /** The cached last change of [file] (never a lookup here: titles are built on the EDT). */
    private fun lastChangeOf(file: VirtualFile) = if (file.isValid) LastChanges.getInstance(session.project).cached(file) else null

    override fun getColumnNames(): List<String> = listOf(session.texts.targetColumn, session.texts.sourceColumn)
}
