package de.terletzkiy.ansibility.model.role

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.Topic
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.golden.AnsibilityGoldenBundle
import de.terletzkiy.ansibility.golden.remote.GoldenMirrorListener
import de.terletzkiy.ansibility.golden.remote.GoldenMirrorState
import de.terletzkiy.ansibility.golden.remote.GoldenMirrors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.TestOnly
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The external golden root of plan amendment R25 (a git mirror, D193–D199, or a folder outside the project, X125) as
 * the role catalog uses it: its directories in the VFS, and a synthetic [root] that owns the golden copies below
 * [rolesDir].
 *
 * [root] is never one of `AnsibleWorkspace.roots()` (D199): it is not resolved, indexed, inspected, searched, run or
 * decrypted through, and no Ansible lookup falls back to it. It exists only so that a [RoleCopy] of a golden role can
 * name its owner ("golden") the way a local copy names its root. Files below [baseDir] are read-only (D198).
 */
class ExternalGolden internal constructor(
    /** The state the directories were resolved for (its commit, ref, history depth); `GoldenMirrors.state()` is the live one. */
    val state: GoldenMirrorState,
    /** The mirror's (or folder's) top directory. */
    val baseDir: VirtualFile,
    /** The directory whose child directories are the golden roles. */
    val rolesDir: VirtualFile,
) {
    val kind: GoldenMirrorState.Kind get() = state.kind

    /** The repository or folder name ("golden"): the wording's golden name. */
    val name: String get() = state.name

    /** The synthetic owner of the golden copies: a role library at [baseDir] whose roles path is [rolesDir]. */
    val root: AnsibleRoot = AnsibleRoot(
        dir = baseDir,
        kind = RootKind.ROLE_LIBRARY,
        detached = false,
        parentDir = null,
        rolesDirs = listOf(rolesDir),
        environmentsDir = null,
        displayName = state.name,
    )

    /** The golden copy's row in the Roles tab: "golden (git)", "golden (folder)". */
    @get:Nls
    val label: String
        get() = AnsibilityGoldenBundle.message(if (kind == GoldenMirrorState.Kind.GIT) "external.label.git" else "external.label.folder", name)

    /** Whether [file] is [baseDir] or below it. */
    fun contains(file: VirtualFile): Boolean = file.isInLocalFileSystem && containsPath(file.path)

    /** Whether the system-independent [path] is [baseDir]'s or below it. Pure string work. */
    fun containsPath(path: String): Boolean = isBelow(path, baseDir.path)

    /** The role directories below [rolesDir] (`RoleLayout.isRole`), by name. VFS only. */
    fun roleDirs(): List<VirtualFile> {
        if (!rolesDir.isValid || !rolesDir.isDirectory) return emptyList()
        return rolesDir.children.orEmpty()
            .filter {
                ProgressManager.checkCanceled()
                it.isValid && it.isDirectory && RoleLayout.isRole(it)
            }
            .sortedBy { it.name }
    }

    /** Whether this is the external golden root of [other] (the same kind, name and directories), whatever the commit. */
    internal fun sameLocation(other: GoldenMirrorState): Boolean =
        state.kind == other.kind && state.name == other.name && state.baseDir == other.baseDir && state.rolesDir == other.rolesDir &&
            baseDir.isValid && rolesDir.isValid

    override fun toString(): String = "ExternalGolden(${state.kind}, ${state.name}, ${state.shortCommit}, ${baseDir.path})"

    companion object {
        /** Whether the system-independent [path] is [base] or below it. */
        internal fun isBelow(path: String, base: String): Boolean {
            val trimmed = base.trimEnd('/')
            if (trimmed.isEmpty()) return false
            return path == trimmed || path.startsWith("$trimmed/")
        }
    }
}

/**
 * A change of the external golden root (plan amendment R25), published by [ExternalGoldenRoot] once the VFS holds the
 * new files ([new] null: there is none any more).
 */
class ExternalGoldenChange(val old: ExternalGolden?, val new: ExternalGolden?) {
    /** Another mirror or folder, or none before or after: a new golden root, as when the setting changes. */
    val locationChanged: Boolean = when {
        old == null || new == null -> old !== new
        else -> old.kind != new.kind || old.name != new.name || old.baseDir != new.baseDir || old.rolesDir != new.rolesDir
    }

    /**
     * The same mirror at another commit (a fetch brought one, X128). Never for the first clone: the mirror had a
     * commit before.
     */
    val commitChanged: Boolean =
        !locationChanged && old != null && new != null && old.state.commit != null && new.state.commit != null && old.state.commit != new.state.commit

    override fun toString(): String = "ExternalGoldenChange($old -> $new, location=$locationChanged, commit=$commitChanged)"
}

/**
 * Published on the project bus (background thread) when the external golden root's files changed: [beforeChange]
 * while the catalog and the drift still show the old commit (the VFS already holds the new files), then [changed] once
 * the catalog follows ([ExternalGoldenRoot.modificationTracker] bumped).
 */
interface ExternalGoldenListener {
    fun beforeChange(change: ExternalGoldenChange) {}

    fun changed(change: ExternalGoldenChange)

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<ExternalGoldenListener> = Topic(ExternalGoldenListener::class.java, Topic.BroadcastDirection.NONE)
    }
}

/**
 * The external golden root in the VFS (plan amendment R25, D197–D199): resolves `GoldenMirrors.state()`'s directories
 * to [VirtualFile]s for the role catalog, and tells the catalog, drift, the tool window and the read-only guards when
 * they change.
 *
 * - **Resolution** runs on a background thread outside read actions, after every [GoldenMirrorListener] event (this
 *   service listens through `projectListeners`): `LocalFileSystem.refreshAndFindFileByNioFile`, because the mirror
 *   service's own VFS refresh after a fetch covers only paths the VFS knows already; for a new location, a new commit
 *   or a new fetch a synchronous recursive refresh of the roles directory follows, so the catalog and drift see the
 *   fetched files. Then [ExternalGoldenListener.beforeChange], [modificationTracker] is bumped (the catalog depends on
 *   it) and [ExternalGoldenListener.changed].
 * - [current] never blocks and works on any thread: the last resolution, or, until the background one has run, the
 *   directories the VFS knows already (no refresh).
 * - [isUnder] is the read-only guards' test (D198): a path below the external golden's base directory, whether the
 *   VFS knows it or not.
 */
@Service(Service.Level.PROJECT)
class ExternalGoldenRoot(private val project: Project, private val scope: CoroutineScope) {
    @Volatile
    private var resolved: ExternalGolden? = null

    @Volatile
    private var lastState: GoldenMirrorState? = null

    @Volatile
    private var errorAt: Instant? = null

    private val tracker = SimpleModificationTracker()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val workerStarted = AtomicBoolean()
    private val mutex = Mutex()
    private val passes = AtomicLong()

    /** The clock of [errorSince]; tests replace it. */
    @Volatile
    internal var clock: () -> Instant = Instant::now

    /** Bumps whenever [current] may answer differently (another mirror or folder, a new commit, a new fetch). */
    val modificationTracker: ModificationTracker get() = tracker

    /** How many resolutions ran (tests wait on it). */
    val resolutionCount: Long get() = passes.get()

    /**
     * The external golden root, or null: none is set, it has no directory yet (not fetched, folder missing), or the VFS
     * does not know its directories yet (the background resolution follows and bumps [modificationTracker]). Never
     * blocks; any thread, read actions included.
     */
    fun current(): ExternalGolden? {
        if (project.isDisposed) return null
        val state = GoldenMirrors.getInstance(project).state() ?: return null
        val base = state.baseDir ?: return null
        val roles = state.rolesDir ?: return null
        val known = resolved
        if (known != null && known.sameLocation(state)) return known
        request()
        val files = LocalFileSystem.getInstance()
        val baseFile = files.findFileByNioFile(base)?.takeIf { it.isValid && it.isDirectory } ?: return null
        val rolesFile = files.findFileByNioFile(roles)?.takeIf { it.isValid && it.isDirectory } ?: return null
        return ExternalGolden(state, baseFile, rolesFile)
    }

    /**
     * Whether [file] lies in the external golden root's base directory (the mirror or the folder), by path: also before
     * the VFS knows the directory. False without an external golden root. Cheap; any thread.
     */
    fun isUnder(file: VirtualFile): Boolean = file.isInLocalFileSystem && isUnderPath(file.path)

    /** [isUnder] for a system-independent absolute [path]. */
    fun isUnderPath(path: String): Boolean {
        if (project.isDisposed) return false
        val state = GoldenMirrors.getInstance(project).state() ?: return false
        val base = state.baseDir ?: return resolved?.containsPath(path) == true
        if (ExternalGolden.isBelow(path, FileUtil.toSystemIndependentName(base.toString()))) return true
        // The VFS path of the same directory may differ (a linked parent such as /var on macOS).
        return resolved?.takeIf { it.state.baseDir == base }?.containsPath(path) == true
    }

    /** [isUnder] for a [path] of the file system. */
    fun isUnder(path: Path): Boolean = isUnderPath(FileUtil.toSystemIndependentName(path.toAbsolutePath().normalize().toString()))

    /**
     * Since when the mirror's current error has been reported (the header's "fetch failed 3 min ago"): the end of the
     * last failed attempt; null without an error.
     */
    fun errorSince(): Instant? = errorAt

    /** A state change of the mirror service ([GoldenMirrorListener], background thread): resolves again in the background. */
    internal fun stateChanged(state: GoldenMirrorState?) {
        val previous = lastState
        lastState = state
        when {
            state?.error == null -> errorAt = null
            previous?.error == null || previous.error != state.error || previous.fetching && !state.fetching || errorAt == null -> errorAt = clock()
        }
        request()
    }

    private fun request() {
        if (project.isDisposed) return
        if (workerStarted.compareAndSet(false, true)) {
            scope.launch(Dispatchers.IO) {
                for (unused in requests) {
                    try {
                        resolve()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        LOG.warn("The external golden root could not be resolved (${e.javaClass.name})")
                    }
                }
            }
        }
        requests.trySend(Unit)
    }

    /**
     * One resolution: the directories of the current state from the VFS (refreshed), and the change events when the
     * catalog must follow. Background thread, no read lock.
     */
    private suspend fun resolve() = mutex.withLock {
        passes.incrementAndGet()
        val state = if (project.isDisposed) null else GoldenMirrors.getInstance(project).state()
        val old = resolved
        val next = state?.let { load(it, old) }
        if (old == null && next == null) return@withLock
        if (old != null && next != null && !ExternalGoldenChange(old, next).locationChanged &&
            old.state.commit == next.state.commit && old.state.fetchedAt == next.state.fetchedAt &&
            old.state.ref == next.state.ref && old.state.historyDepth == next.state.historyDepth
        ) {
            // Only the fetching flags, errors or pauses changed: nothing the catalog shows.
            resolved = next
            return@withLock
        }
        val change = ExternalGoldenChange(old, next)
        if (!project.isDisposed) project.messageBus.syncPublisher(ExternalGoldenListener.TOPIC).beforeChange(change)
        resolved = next
        tracker.incModificationCount()
        if (!project.isDisposed) project.messageBus.syncPublisher(ExternalGoldenListener.TOPIC).changed(change)
    }

    /** [state]'s directories in the VFS, refreshed when they are new or hold a new fetch; null when they do not exist. */
    private fun load(state: GoldenMirrorState, old: ExternalGolden?): ExternalGolden? {
        val base = state.baseDir ?: return null
        val roles = state.rolesDir ?: return null
        val files = LocalFileSystem.getInstance()
        val baseFile = files.refreshAndFindFileByNioFile(base)?.takeIf { it.isDirectory } ?: return null
        val rolesFile = files.refreshAndFindFileByNioFile(roles)?.takeIf { it.isDirectory } ?: return null
        val fresh = old == null || old.baseDir != baseFile || old.rolesDir != rolesFile || !old.baseDir.isValid ||
            old.state.commit != state.commit || old.state.fetchedAt != state.fetchedAt
        // Synchronous: the catalog and drift must see the fetched files when the change is published.
        if (fresh) VfsUtil.markDirtyAndRefresh(false, true, true, rolesFile)
        if (!baseFile.isValid || !rolesFile.isValid) return null
        return ExternalGolden(state, baseFile, rolesFile)
    }

    /** Resolves now and waits (tests). */
    @TestOnly
    suspend fun syncForTests() = resolve()

    /** Forgets everything (the light test project outlives a test). */
    @TestOnly
    fun resetForTests() {
        resolved = null
        lastState = null
        errorAt = null
        clock = Instant::now
        tracker.incModificationCount()
    }

    companion object {
        private val LOG = logger<ExternalGoldenRoot>()

        fun getInstance(project: Project): ExternalGoldenRoot = project.service()
    }
}

/** Forwards the mirror service's state changes to [ExternalGoldenRoot] (`projectListeners` in `ansibility-golden.xml`). */
class ExternalGoldenStateListener(private val project: Project) : GoldenMirrorListener {
    override fun stateChanged(state: GoldenMirrorState?) {
        if (!project.isDisposed) ExternalGoldenRoot.getInstance(project).stateChanged(state)
    }
}
