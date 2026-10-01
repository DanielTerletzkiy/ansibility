package de.terletzkiy.ansibility.context.host

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Restarts the daemon for the open files of a root when its selection changes (plan amendment R7/R8, A.9 change 6,
 * second bullet): host-mode presentation (the `when:` greying of F8.9) depends on the selected env, host and play, while
 * inspections never do (D32).
 *
 * Restarting costs a re-highlight of every open file of the root, so it happens only while some host-mode presentation
 * is registered ([register]); until then a selection change restarts nothing. Only open files of the roots whose stored
 * selection changed are restarted (a nested root shares its parent's env and host, so both count). Never on the EDT
 * except for the restart call itself; never in a read action that a write could wait for.
 */
@Service(Service.Level.PROJECT)
class HostModeRestarts(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private val consumers = AtomicInteger()
    private val restarts = AtomicLong()

    init {
        project.messageBus.connect(this).subscribe(
            AnsibilitySettingsListener.TOPIC,
            object : AnsibilitySettingsListener {
                override fun workspaceStateChanged(old: WorkspaceState, new: WorkspaceState) {
                    if (consumers.get() == 0) return
                    // Follow editor (D33) changes the host scope of files outside the selection in every root.
                    val changed = if (old.followEditor != new.followEditor) {
                        old.roots.keys + new.roots.keys + ALL_ROOTS
                    } else {
                        (old.roots.keys + new.roots.keys).filterTo(HashSet()) { old.root(it) != new.root(it) }
                    }
                    if (changed.isNotEmpty()) restartRoots(changed)
                }
            },
        )
    }

    override fun dispose() {
        consumers.set(0)
    }

    /** Daemon restarts done for selection changes (for tests). */
    val restartCount: Long get() = restarts.get()

    /**
     * Registers selection-dependent highlighting until [parent] is disposed: from now on a selection change restarts the
     * open files of its root.
     */
    fun register(parent: Disposable) {
        consumers.incrementAndGet()
        Disposer.register(parent) { consumers.decrementAndGet() }
    }

    /** Restarts the daemon for the open files of [root] (and of the roots sharing its selection) now. */
    fun restart(root: AnsibleRoot) = restartRoots(setOf(RootKeys.keyOf(project, root.dir)))

    private fun restartRoots(keys: Set<String>) {
        if (project.isDisposed) return
        scope.launch(Dispatchers.Default) {
            val open = withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project).openFiles.toList() }
            val psiFiles = readAction {
                val workspace = AnsibleWorkspace.getInstance(project)
                open.filter { it.isValid && (ALL_ROOTS in keys || selectionKeys(workspace.rootFor(it)).any(keys::contains)) }
                    .mapNotNull { PsiManager.getInstance(project).findFile(it) }
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

    /** The workspace-state keys whose change affects files of [root]: its own, and its parent's for a nested root. */
    private fun selectionKeys(root: AnsibleRoot?): List<String> {
        if (root == null) return emptyList()
        val own = RootKeys.keyOf(project, root.dir)
        val parent = root.parentDir?.takeIf { root.kind == RootKind.NESTED_PLAYBOOK }?.let { RootKeys.keyOf(project, it) }
        return listOfNotNull(own, parent)
    }

    companion object {
        private const val RESTART_REASON = "ansibility: Ansible context selection changed"

        /** Marker key: restart the open files of every root (Follow editor changed). */
        private const val ALL_ROOTS = "\u0000all-roots"

        fun getInstance(project: Project): HostModeRestarts = project.service()
    }
}
