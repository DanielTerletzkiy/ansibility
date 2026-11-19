package de.terletzkiy.ansibility.vars.hints

import com.intellij.codeInsight.hints.declarative.DeclarativeInlayHintsSettings
import com.intellij.codeInsight.hints.declarative.impl.DeclarativeInlayHintsPassFactory
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.context.host.HostModeRestarts
import de.terletzkiy.ansibility.settings.AnsibilityWorkspaceState
import de.terletzkiy.ansibility.settings.RootContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps the variable source hints current (plan amendment R31, D234, D236; the R31 fixes, decision 7):
 * - **What an editor shows.** Each hint pass records on its editor the selection it was computed for and whether it
 *   showed any hint ([record]). Whether a hint shows never depends on the selection, only its text does, so an editor
 *   without hints never needs a recompute when the selection changes.
 * - **Context switches.** The first hint pass of a project registers the hints with [HostModeRestarts] (the
 *   selection-change hook), counted only while the provider is on: a selection change then recomputes the visible
 *   editors of the changed roots whose hints are stale, with one daemon restart. An editor that was in the background
 *   is recomputed when it is selected ([FileEditorManagerListener.selectionChanged]), so no tab keeps the text of an
 *   earlier selection.
 * - **The toggle:** [setEnabled] flips the platform's own switch for the provider
 *   ([DeclarativeInlayHintsSettings.setProviderEnabled], application level: every project) and recomputes the hints of
 *   every open editor, as the platform's "Disable hints" action does
 *   ([DeclarativeInlayHintsPassFactory.scheduleRecompute], public and unannotated in 262: a daemon restart alone keeps
 *   the hints while the PSI is unchanged).
 */
@Service(Service.Level.PROJECT)
class VarSourceHintsRefresh(private val project: Project) : Disposable {
    private val following = AtomicBoolean()
    private val shownRecomputes = AtomicLong()

    /** Background editors recomputed when they were shown, their hints computed for another selection (for tests). */
    val shownRecomputeCount: Long get() = shownRecomputes.get()

    /** Registers the hints with the selection-change hook and the editor selection once per project. */
    fun followContextSwitches() {
        if (!following.compareAndSet(false, true)) return
        HostModeRestarts.getInstance(project).registerInlays(this, { isEnabled() }) { editor -> isStale(project, editor) }
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    val editor = (event.newEditor as? TextEditor)?.editor ?: return
                    if (!isEnabled() || !isStale(project, editor)) return
                    DeclarativeInlayHintsPassFactory.scheduleRecompute(editor, project)
                    shownRecomputes.incrementAndGet()
                }
            },
        )
    }

    override fun dispose() = Unit

    /** What one hint pass of an editor was computed for: its root's selection and Follow editor, and whether it showed hints. */
    internal class State(val root: AnsibleRoot, val selection: RootContext, val followEditor: Boolean, val hasHints: Boolean)

    companion object {
        private val STATE = Key.create<State>("ansibility.variableSources.state")

        fun getInstance(project: Project): VarSourceHintsRefresh = project.service()

        /**
         * True when the provider is on (its own switch, the default being on). The global "show inlay hints" switch is not
         * consulted: its `InlayHintsSettings` is `@ApiStatus.Experimental` in 262; with hints off globally, a switch still
         * recomputes a visible editor that showed hints before, at the cost of one re-highlight.
         */
        fun isEnabled(): Boolean = isProviderEnabled()

        /** The platform's switch for the provider; on by default (D236). */
        fun isProviderEnabled(): Boolean = DeclarativeInlayHintsSettings.getInstance().isProviderEnabled(VarSourceHints.PROVIDER_ID) ?: true

        /** The selection of [root] now, read before a hint pass computes anything (a switch during the pass leaves it stale). */
        internal fun snapshot(project: Project, root: AnsibleRoot, hasHints: Boolean): State =
            State(root, AnsibleContextService.getInstance(project).selection(root), followEditor(project), hasHints)

        /** Records what a hint pass of [editor] showed. */
        internal fun record(editor: Editor, state: State) = editor.putUserData(STATE, state)

        /** Whether [editor] shows hints computed for another selection than its root's current one. */
        internal fun isStale(project: Project, editor: Editor): Boolean {
            val state = editor.getUserData(STATE) ?: return false
            if (!state.hasHints) return false
            return state.selection != AnsibleContextService.getInstance(project).selection(state.root) || state.followEditor != followEditor(project)
        }

        private fun followEditor(project: Project): Boolean = AnsibilityWorkspaceState.getInstance(project).snapshot.followEditor

        /** Turns the hints on or off for every project and recomputes the open editors' hints. Call on the EDT. */
        fun setEnabled(enabled: Boolean) {
            DeclarativeInlayHintsSettings.getInstance().setProviderEnabled(VarSourceHints.PROVIDER_ID, enabled)
            recomputeOpenEditors()
        }

        /** Recomputes the declarative inlay hints of every open editor of every project. Call on the EDT. */
        fun recomputeOpenEditors() {
            for (editor in EditorFactory.getInstance().allEditors) {
                val project = editor.project ?: continue
                if (project.isDisposed) continue
                DeclarativeInlayHintsPassFactory.scheduleRecompute(editor, project)
            }
        }
    }
}
