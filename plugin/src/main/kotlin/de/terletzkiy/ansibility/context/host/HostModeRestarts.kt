package de.terletzkiy.ansibility.context.host

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.hints.declarative.impl.DeclarativeInlayHintsPassFactory
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiManager
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.settings.AnsibilitySettingsListener
import de.terletzkiy.ansibility.settings.RootKeys
import de.terletzkiy.ansibility.settings.WorkspaceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Brings selection-dependent presentation up to date when a root's selection changes (plan amendment R7/R8, A.9 change
 * 6, second bullet): host-mode presentation (the `when:` greying of F8.9, the variable source hints of plan amendment
 * R31) depends on the selected env, host and play, while inspections never do (D32).
 *
 * - **Highlighting consumers** ([register]): a selection change restarts the daemon for the open files of the roots
 *   whose stored selection changed (a nested root shares its parent's env and host, so both count), and nothing while
 *   none is registered.
 * - **Inlay consumers** ([registerInlays], the variable source hints): the daemon skips declarative inlay hints while
 *   the PSI is unchanged, and the only public way to recompute them,
 *   [DeclarativeInlayHintsPassFactory.scheduleRecompute] (unannotated in 262, what the platform's own "Disable hints"
 *   action calls), resets one editor and restarts the daemon for the whole project. So a selection change recomputes
 *   only the **visible** editors (the selected editor of each window) of the changed roots whose hints are stale, all in
 *   one EDT task, which the daemon serves with one restart (plan amendment R31 fixes, decision 7): normally one editor,
 *   and no restart at all while the consumer is inactive (its provider off) or no such editor shows. An editor that
 *   becomes visible later is the consumer's own business (it recomputes it then). The restart re-highlights the visible
 *   editors with their unchanged inspections: their results never depend on the selection.
 *
 * Never on the EDT except for the restart calls themselves; never in a read action that a write could wait for.
 */
@Service(Service.Level.PROJECT)
class HostModeRestarts(private val project: Project, private val scope: CoroutineScope) : Disposable {
    /** One registered presentation: whether it shows anything now, and for inlays which editor shows stale hints. */
    private class Consumer(val active: () -> Boolean, val stale: ((Editor) -> Boolean)?)

    private val consumers = CopyOnWriteArrayList<Consumer>()
    private val restarts = AtomicLong()
    private val inlayRecomputes = AtomicLong()
    private val inlaySwitches = AtomicLong()

    init {
        project.messageBus.connect(this).subscribe(
            AnsibilitySettingsListener.TOPIC,
            object : AnsibilitySettingsListener {
                override fun workspaceStateChanged(old: WorkspaceState, new: WorkspaceState) {
                    val active = consumers.filter { it.active() }
                    if (active.isEmpty()) return
                    // Follow editor (D33) changes the host scope of files outside the selection in every root.
                    val changed = if (old.followEditor != new.followEditor) {
                        old.roots.keys + new.roots.keys + ALL_ROOTS
                    } else {
                        (old.roots.keys + new.roots.keys).filterTo(HashSet()) { old.root(it) != new.root(it) }
                    }
                    if (changed.isEmpty()) return
                    if (active.any { it.stale == null }) restartRoots(changed)
                    val inlays = active.mapNotNull { it.stale }
                    if (inlays.isNotEmpty()) recomputeInlays(changed, inlays)
                }
            },
        )
    }

    override fun dispose() {
        consumers.clear()
    }

    /** Daemon restarts of single files done for highlighting consumers (for tests). */
    val restartCount: Long get() = restarts.get()

    /** Editors whose declarative inlay hints a selection change had recomputed (for tests). */
    val inlayRecomputeCount: Long get() = inlayRecomputes.get()

    /** Selection changes that recomputed some editor's inlay hints, each with one daemon restart (for tests). */
    val inlaySwitchCount: Long get() = inlaySwitches.get()

    /**
     * Registers selection-dependent highlighting until [parent] is disposed: from now on a selection change restarts the
     * open files of its root.
     */
    fun register(parent: Disposable) = add(parent, Consumer({ true }, null))

    /**
     * Registers selection-dependent declarative inlay hints until [parent] is disposed: while [active], a selection change
     * recomputes the visible editors of the changed roots for which [stale] holds (called on the EDT).
     */
    fun registerInlays(parent: Disposable, active: () -> Boolean, stale: (Editor) -> Boolean) = add(parent, Consumer(active, stale))

    private fun add(parent: Disposable, consumer: Consumer) {
        consumers += consumer
        Disposer.register(parent) { consumers.remove(consumer) }
    }

    /** Restarts the daemon for the open files of [root] (and of the roots sharing its selection) now, for highlighting consumers. */
    fun restart(root: AnsibleRoot) = restartRoots(setOf(RootKeys.keyOf(project, root.dir)))

    private fun restartRoots(keys: Set<String>) {
        if (project.isDisposed) return
        scope.launch(Dispatchers.Default) {
            val open = withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project).openFiles.toList() }
            val psiFiles = readAction {
                val workspace = AnsibleWorkspace.getInstance(project)
                open.filter { it.isValid && inRoots(workspace.rootFor(it), keys) }.mapNotNull { PsiManager.getInstance(project).findFile(it) }
            }
            if (psiFiles.isEmpty()) return@launch
            withContext(Dispatchers.EDT) {
                if (project.isDisposed) return@withContext
                for (psiFile in psiFiles) {
                    DaemonCodeAnalyzer.getInstance(project).restart(psiFile, RESTART_REASON)
                    restarts.incrementAndGet()
                }
            }
        }
    }

    /** Recomputes the stale hints of the visible editors of the roots of [keys], in one EDT task (one daemon restart). */
    private fun recomputeInlays(keys: Set<String>, stale: List<(Editor) -> Boolean>) {
        if (project.isDisposed) return
        scope.launch(Dispatchers.Default) {
            val visible = withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project).selectedEditors.mapNotNull { it as? TextEditor } }
            if (visible.isEmpty()) return@launch
            val inRoots = readAction {
                val workspace = AnsibleWorkspace.getInstance(project)
                visible.filter { editor -> editor.file?.takeIf { it.isValid }?.let { inRoots(workspace.rootFor(it), keys) } == true }
            }
            if (inRoots.isEmpty()) return@launch
            withContext(Dispatchers.EDT) {
                if (project.isDisposed) return@withContext
                val editors = inRoots.map { it.editor }.distinct().filter { editor -> !editor.isDisposed && stale.any { it(editor) } }
                if (editors.isEmpty()) return@withContext
                // Each call restarts the whole daemon; calls in one EDT task are served by one daemon pass.
                for (editor in editors) {
                    DeclarativeInlayHintsPassFactory.scheduleRecompute(editor, project)
                    inlayRecomputes.incrementAndGet()
                }
                inlaySwitches.incrementAndGet()
            }
        }
    }

    private fun inRoots(root: AnsibleRoot?, keys: Set<String>): Boolean = ALL_ROOTS in keys || selectionKeys(root).any(keys::contains)

    /** The workspace-state keys whose change affects files of [root]: its own, and its parent's for a nested root. */
    private fun selectionKeys(root: AnsibleRoot?): List<String> {
        if (root == null) return emptyList()
        val own = RootKeys.keyOf(project, root.dir)
        val parent = root.parentDir?.takeIf { root.kind == RootKind.NESTED_PLAYBOOK }?.let { RootKeys.keyOf(project, it) }
        return listOfNotNull(own, parent)
    }

    companion object {
        private const val RESTART_REASON = "ansibility: Ansible context selection changed"

        /** Marker key: every root changes (Follow editor changed). */
        private const val ALL_ROOTS = "\u0000all-roots"

        fun getInstance(project: Project): HostModeRestarts = project.service()
    }
}
