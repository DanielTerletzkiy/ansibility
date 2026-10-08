package de.terletzkiy.ansibility.vault.vcs

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import de.terletzkiy.ansibility.vault.VaultLog
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where a file stands in version control, as far as ANS-V108 cares (plan amendment R21, D162): committed or staged files
 * are reported as they are, untracked ones as a warning ("encrypt it before you commit, or .gitignore it"), ignored ones
 * not at all; a file outside any VCS root, or every file of an IDE without VCS support, is [NO_VCS].
 */
enum class TrackedStatus {
    /** Under version control and committed (unchanged or modified). */
    TRACKED,

    /** Added to version control (staged), not committed yet. */
    ADDED,

    /** Inside a VCS root but not under version control, and not ignored. */
    UNTRACKED,

    /** Ignored by the VCS (`.gitignore`). */
    IGNORED,

    /** Not in any VCS root, or the IDE has no VCS support. */
    NO_VCS,
}

/**
 * The VCS status of files (plan amendment R21, D162). Implemented by `VcsTrackedStatusLookup`, which only the optional
 * fragment `ansibility-vcs.xml` registers (it loads with `com.intellij.modules.vcs`), so no always-loaded class touches
 * a VCS class. Callers use [TrackedStatuses], never this point directly; without an extension every file is
 * [TrackedStatus.NO_VCS].
 */
interface TrackedStatusLookup {
    /** The status of [file] in [project]. Any thread; may read a read-action-free VCS cache only. */
    fun status(project: Project, file: VirtualFile): TrackedStatus

    /**
     * Calls [changed] whenever statuses may have changed, with the file, or null when many may have (a refresh after a
     * pull, a branch switch, a `.gitignore` edit), until [parent] is disposed. Any thread.
     */
    fun watch(project: Project, parent: Disposable, changed: (VirtualFile?) -> Unit)

    /**
     * Calls [ready] once the VCS knows the statuses of the project's files (its roots are mapped and its first refresh
     * is done), or at once when it never will (no VCS). Any thread; [ready] may run on any thread. The monitoring
     * waits for it before it notifies, so a git-ignored key is never announced as committed right after startup.
     */
    fun whenReady(project: Project, ready: () -> Unit) = ready()

    /**
     * The root directory of the VCS repository [file] belongs to (a git work tree, a submodule), or null outside every
     * repository or without VCS support. The Vault tab files a finding outside every Ansible root under its repository.
     * Any thread; a mapping lookup, no I/O.
     */
    fun repositoryOf(project: Project, file: VirtualFile): VirtualFile? = null

    companion object {
        val EP_NAME: ExtensionPointName<TrackedStatusLookup> = ExtensionPointName("de.terletzkiy.ansibility.trackedStatusLookup")
    }
}

/**
 * The project's view of [TrackedStatusLookup] (plan amendment R21, D162): [status] of a file, and daemon restarts for
 * the open files whose findings depend on it ([dependsOnStatus]) when the VCS reports a change, so a file that becomes
 * ignored, staged or committed is re-highlighted. Only a file whose status differs from the one its findings were made
 * with is restarted: the VCS reports "statuses changed" after every refresh of its change lists (each save, each frame
 * activation), almost always without a change that matters here. Bursts of status events are merged into one check on
 * the EDT. Starts watching with the first [status] call; nothing is read from the files themselves.
 */
@Service(Service.Level.PROJECT)
class TrackedStatuses(private val project: Project) : Disposable {
    private val lock = Any()

    @Volatile
    private var watched: TrackedStatusLookup? = null
    private var watch: Disposable? = null

    /** The files whose findings depend on their status, with the status those findings were made with. */
    private val dependent = ConcurrentHashMap<VirtualFile, TrackedStatus>()
    private val pendingFiles: MutableSet<VirtualFile> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var pendingAll = false
    private val scheduled = AtomicBoolean()
    private val restarted = CopyOnWriteArrayList<VirtualFile>()
    private val listeners = CopyOnWriteArrayList<(VirtualFile?) -> Unit>()

    /** The VCS status of [file]; [TrackedStatus.NO_VCS] without a lookup or when it fails. Any thread. */
    fun status(file: VirtualFile): TrackedStatus {
        val lookup = lookup() ?: return TrackedStatus.NO_VCS
        return try {
            lookup.status(project, file)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            VaultLog.failure(VaultLog.Operation.STATUS, e, file.path)
            TrackedStatus.NO_VCS
        }
    }

    /**
     * Remembers [file] as one whose findings were made with [status] and depend on it, or forgets it ([status] null).
     * Any thread.
     */
    fun dependsOnStatus(file: VirtualFile, status: TrackedStatus?) {
        if (status != null) dependent[file] = status else dependent -= file
    }

    /** The VCS repository root of [file] ([TrackedStatusLookup.repositoryOf]); null without a lookup or when it fails. Any thread. */
    fun repositoryOf(file: VirtualFile): VirtualFile? {
        val lookup = lookup() ?: return null
        return try {
            lookup.repositoryOf(project, file)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            VaultLog.failure(VaultLog.Operation.STATUS, e, file.path)
            null
        }
    }

    /**
     * Calls [listener] whenever the VCS reports changed statuses (with the file, or null when many may have changed: a
     * refresh after a pull, a branch switch, a `.gitignore` edit) until [parent] is disposed; starts watching. For the
     * monitoring (plan amendment R21, D165), which re-reads every status. Any thread; [listener] runs on any thread.
     */
    fun addListener(parent: Disposable, listener: (VirtualFile?) -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
        lookup()
    }

    /**
     * Calls [ready] once the VCS knows the statuses ([TrackedStatusLookup.whenReady]); at once without a lookup or when
     * it fails. Any thread.
     */
    fun whenReady(ready: () -> Unit) {
        val lookup = lookup() ?: return ready()
        try {
            lookup.whenReady(project, ready)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            VaultLog.failure(VaultLog.Operation.STATUS, e)
            ready()
        }
    }

    /** The current lookup, watched once per lookup instance (a test may replace it). */
    private fun lookup(): TrackedStatusLookup? {
        val lookup = TrackedStatusLookup.EP_NAME.extensionList.firstOrNull() ?: return null
        if (watched !== lookup) {
            synchronized(lock) {
                if (watched !== lookup && !project.isDisposed) {
                    watch?.let(Disposer::dispose)
                    val parent = Disposer.newDisposable(this, "Ansibility VCS status watch")
                    watch = parent
                    watched = lookup
                    lookup.watch(project, parent, ::changed)
                }
            }
        }
        return lookup
    }

    private fun changed(file: VirtualFile?) {
        for (listener in listeners) listener(file)
        if (file == null) {
            if (dependent.isEmpty()) return
            pendingAll = true
        } else {
            if (!dependent.containsKey(file)) return
            pendingFiles += file
        }
        if (!scheduled.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater({
            scheduled.set(false)
            restartOpenFiles()
        }, project.disposed)
    }

    /**
     * Restarts the daemon for the open files among the changed ones that depend on their status, when their status is no
     * longer the one their findings were made with. EDT.
     */
    private fun restartOpenFiles() {
        val all = pendingAll
        pendingAll = false
        val files = HashSet(pendingFiles)
        pendingFiles.removeAll(files)
        val psiManager = PsiManager.getInstance(project)
        val daemon = DaemonCodeAnalyzer.getInstance(project)
        for (file in FileEditorManager.getInstance(project).openFiles) {
            val seen = dependent[file] ?: continue
            if (!file.isValid || !(all || file in files) || status(file) == seen) continue
            dependent[file] = status(file)
            psiManager.findFile(file)?.let {
                daemon.restart(it, REASON)
                restarted += file
            }
        }
    }

    /** The files restarted so far, in order (tests). */
    @get:TestOnly
    val restartedForTests: List<VirtualFile> get() = restarted.toList()

    override fun dispose() {
        dependent.clear()
        pendingFiles.clear()
        listeners.clear()
    }

    companion object {
        private const val REASON = "Ansibility: VCS status of a file with a plaintext key changed"

        fun getInstance(project: Project): TrackedStatuses = project.service()
    }
}
