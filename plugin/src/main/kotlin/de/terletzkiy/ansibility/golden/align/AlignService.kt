package de.terletzkiy.ansibility.golden.align

import com.intellij.history.LocalHistory
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresEdt
import de.terletzkiy.ansibility.golden.AnsibilityGoldenBundle.message
import de.terletzkiy.ansibility.golden.GoldenTarget
import de.terletzkiy.ansibility.golden.sync.PlanOptions
import de.terletzkiy.ansibility.golden.sync.RoleFilePlan
import de.terletzkiy.ansibility.golden.sync.RoleLinks
import de.terletzkiy.ansibility.golden.sync.StripperHold
import de.terletzkiy.ansibility.model.role.RoleCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Align (plan amendment R24, D184–D187): merges one copy of a role into another in the merge workspace.
 *
 * 1. Both copies are refreshed from disk (S10), then the plan is computed in the background ([RoleFilePlan],
 *    `molecule/` always (U4: Align is no drift view), key and vault files only when asked); unsaved documents of the
 *    target count as the user sees them, and every write checks that nothing changed since (the target's stamps, the
 *    source's stamps). A target that is (or lies below) a symbolic link stops it (D191), and so do unsaved changes in
 *    the source (D187: "save or discard them first").
 * 2. On the EDT, outside any read or write action: the Local History label "Before aligning web in golden" (the revert
 *    point, because the merge viewer resets the target file's undo history), then the window: the first
 *    [RoleMergeDialog] (the platform's Conflicts dialog when the VCS module is there), else [AlignFallbackDialog].
 *    While it is open, the trailing-space stripper is off for the target files it may save (S6).
 * 3. When it closes, rows it took from the source itself get the source's exact bytes and executable bit
 *    ([AlignSession.finishTaken]), then [AlignNotifier]'s summary with Push to Repos…, Run Molecule Tests and Show
 *    Local History (or "Nothing aligned", U8).
 *
 * Nothing is staged or committed; nothing is decrypted. Drift follows by itself: `RoleDriftService` listens to VFS and
 * document changes.
 */
@Service(Service.Level.PROJECT)
class AlignService(private val project: Project, private val scope: CoroutineScope) {
    /** Merge into Golden…: the golden copy is the target, [target]'s copy the source. Nothing on golden itself. */
    fun mergeIntoGolden(target: GoldenTarget): Job = scope.launch {
        val golden = readAction { RoleCatalog.getInstance(project).snapshot().reference(target.copy.name) }
        if (golden == null || golden.dir == target.copy.dir) return@launch
        alignNow(AlignRequest(target = golden, source = target.copy))
    }

    /** Align with Golden…: [target]'s copy is the target, the golden copy the source. Nothing on golden itself. */
    fun alignWithGolden(target: GoldenTarget): Job = scope.launch {
        val golden = readAction { RoleCatalog.getInstance(project).snapshot().reference(target.copy.name) }
        if (golden == null || golden.dir == target.copy.dir) return@launch
        alignNow(AlignRequest(target = target.copy, source = golden))
    }

    /** Align Role…: the opening step ([AlignUi.chooseCopies]) for [target]'s role, then the alignment chosen there. */
    fun alignRole(target: GoldenTarget): Job = scope.launch {
        val setup = withBackgroundProgress(project, message("align.progress.copies", target.copy.name)) { AlignSetup.load(project, target.copy) }
            ?: return@launch
        val request = withContext(Dispatchers.EDT) {
            if (project.isDisposed) null else AlignUi.getInstance().chooseCopies(project, setup)
        } ?: return@launch
        alignNow(request)
    }

    /** Aligns [request]: plan, label, window, summary. */
    fun align(request: AlignRequest): Job = scope.launch { alignNow(request) }

    private suspend fun alignNow(request: AlignRequest) {
        if (request.target.dir == request.source.dir) return
        val target = request.target
        // D191: never written through a symbolic link (a copy that links to another copy would write that one).
        RoleLinks.linkedTo(project, target.dir)?.let { real ->
            withContext(Dispatchers.EDT) {
                if (!project.isDisposed) AlignUi.getInstance().inform(project, message("align.linkedTarget", target.name, target.root.displayName, real))
            }
            return
        }
        val session = session(request)
        val unsaved = readAction { session.unsavedSources() }
        withContext(Dispatchers.EDT) {
            if (project.isDisposed) return@withContext
            if (unsaved.isNotEmpty()) {
                // D187 (U11): what is merged is what is on disk; unsaved source edits stop the step.
                AlignUi.getInstance().inform(project, message("align.unsavedSource", request.source.root.displayName, unsaved.joinToString(", ")))
                return@withContext
            }
            if (session.rows.isEmpty()) {
                AlignUi.getInstance().inform(project, identical(session))
                return@withContext
            }
            run(session)
        }
    }

    /**
     * The session of [request] with its plan, computed in the background with a progress indicator, after both copies
     * were refreshed from disk (S10). `molecule/` always takes part (U4).
     */
    suspend fun session(request: AlignRequest): AlignSession {
        val target = request.target
        val plan = withBackgroundProgress(project, message("align.progress", target.name, target.root.displayName, request.source.root.displayName)) {
            withContext(Dispatchers.IO) {
                val dirs = listOf(request.source.dir, request.target.dir).filter { it.isValid && it.isInLocalFileSystem }
                if (dirs.isNotEmpty()) VfsUtil.markDirtyAndRefresh(false, true, true, *dirs.toTypedArray())
            }
            RoleFilePlan.compute(project, request.source, request.target, PlanOptions(ignoreMolecule = false, includeSensitive = request.includeSensitive))
        }
        return AlignSession(project, request, plan)
    }

    /**
     * Sets the revert point, shows the window for [session] and then the summary. EDT, outside any read or write
     * action; returns once the window is closed.
     */
    @RequiresEdt
    fun run(session: AlignSession) {
        ThreadingAssertions.assertEventDispatchThread()
        session.labelId = LocalHistory.getInstance().putSystemLabel(project, session.texts.label).id
        val dialog = RoleMergeDialog.EP_NAME.extensionList.firstOrNull()
        // S6: the window saves target documents itself (the platform's merge model, the merge viewer): no stripping.
        val held = session.windowFiles
        StripperHold.during(held) {
            if (dialog != null) dialog.show(project, session) else AlignFallbackDialog.show(project, session)
            if (!project.isDisposed) session.saveWindowEdits()
        }
        if (project.isDisposed) return
        session.finishTaken()
        AlignNotifier.summary(project, session)
    }

    private fun identical(session: AlignSession): String {
        val target = session.target
        val source = session.source
        val leftOut = session.plan.excluded.size
        return if (leftOut > 0) message("align.identical.leftOut", target.name, target.root.displayName, source.root.displayName, leftOut)
        else message("align.identical", target.name, target.root.displayName, source.root.displayName)
    }

    companion object {
        fun getInstance(project: Project): AlignService = project.service()
    }
}
