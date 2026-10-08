package de.terletzkiy.ansibility.vault.monitor

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListenerBackgroundable
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import de.terletzkiy.ansibility.context.AnsibleStructureListener
import de.terletzkiy.ansibility.index.secrets.SecretIndex
import de.terletzkiy.ansibility.settings.AnsibilitySettingsListener
import de.terletzkiy.ansibility.settings.ProjectSettings
import de.terletzkiy.ansibility.vault.VaultLog
import de.terletzkiy.ansibility.vault.vcs.TrackedStatus
import de.terletzkiy.ansibility.vault.vcs.TrackedStatuses
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.atomic.AtomicLong

/**
 * The workspace's vault and secret health (plan amendment R21, D164–D166): a [SecretSnapshot] of every finding per
 * repository, kept current in the background, for the Vault tab, the tool window's badge and the notifications.
 *
 * - Built by [SecretSnapshotBuilder] in a smart read action (it waits for indexing) on this service's own coroutine
 *   scope; requests are conflated and debounced by [debounceMillis], and a newer request cancels a build still running.
 * - Refreshed when the project opens ([SecretHealthActivity]), when indexing ends, when the Ansible structure or the
 *   Ansibility settings change, when the plaintext key allowlist changes (`PlaintextKeyExclusions.setAllowlist`), when
 *   the VCS status of a file the last snapshot looked at changed (`TrackedStatuses.addListener`: the VCS reports
 *   "statuses changed" after every refresh of its change lists, so the statuses the snapshot was built with are
 *   compared first, cheaply, and only a real change rebuilds it), and on VFS events below candidate paths of
 *   `ansibility.secrets` or on directories ([isRelevant]): saves, pulls, branch switches and checkouts from a terminal.
 * - Until the VCS knows the statuses (`TrackedStatuses.whenReady`; a late answer is logged after [READY_TIMEOUT_MS] and
 *   still awaited) the snapshot leaves the ANS-V108 findings out ([SecretSnapshot.statusesKnown]) and the notifier
 *   announces nothing, so a git-ignored key is never shown or announced as committed.
 * - Each new snapshot is published on [SecretHealthListener.TOPIC] (old and new) and handed to [SecretNotifier] on the
 *   EDT.
 *
 * Nothing here opens a file, decrypts or logs content: the snapshot holds kinds, lines, names and statuses.
 */
@Service(Service.Level.PROJECT)
class SecretHealthService(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private val lock = Any()
    private val built = AtomicLong()

    /** The subscriptions and the build loop of a started service; null while stopped. */
    @Volatile
    private var running: Running? = null

    private class Running(val parent: Disposable, val requests: Channel<Unit>, val statusChecks: Channel<Unit>)

    @Volatile
    private var current: SecretSnapshot = SecretSnapshot.NOT_COMPUTED

    /** The VCS statuses the current snapshot was built with (see [statusChanged]). */
    @Volatile
    private var builtStatuses: Map<VirtualFile, TrackedStatus> = emptyMap()

    /** True once the VCS reported the file statuses (`TrackedStatuses.whenReady`). */
    @Volatile
    private var statusesKnown = false

    /** The last snapshot ([SecretSnapshot.NOT_COMPUTED] before the first). Any thread. */
    val snapshot: SecretSnapshot get() = current

    /** How long a burst of events is collected before a build. */
    @Volatile
    var debounceMillis: Long = DEBOUNCE_MS
        @TestOnly set

    /** How long the notifier waits for the VCS at most. */
    @Volatile
    var readyTimeoutMillis: Long = READY_TIMEOUT_MS
        @TestOnly set

    /** How many snapshots were built and handed on (published, notified) so far (tests wait on it). */
    val buildCount: Long get() = built.get()

    /** True once [start] ran (and the service was not stopped since). */
    val isStarted: Boolean get() = running != null

    /** Subscribes to the change sources and schedules the first build; later calls do nothing. Any thread. */
    fun start() {
        val started = synchronized(lock) {
            if (running != null || project.isDisposed) return
            val parent = Disposer.newDisposable(this, "Ansibility Vault health")
            Running(parent, Channel(Channel.CONFLATED), Channel(Channel.CONFLATED)).also { running = it }
        }
        subscribe(started.parent)
        val statuses = TrackedStatuses.getInstance(project)
        statuses.addListener(started.parent, ::statusChanged)
        val loop = scope.launch(Dispatchers.Default) {
            started.requests.consumeAsFlow().collectLatest {
                delay(debounceMillis)
                val built = try {
                    val known = statusesKnown
                    smartReadAction(project) { SecretSnapshotBuilder.buildWithStatuses(project, statuses::status, known) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    VaultLog.failure(VaultLog.Operation.STATUS, e)
                    return@collectLatest
                }
                if (running === started) {
                    builtStatuses = built.statuses
                    publish(built.snapshot)
                }
            }
        }
        val checks = scope.launch(Dispatchers.Default) {
            started.statusChecks.consumeAsFlow().collectLatest {
                delay(debounceMillis)
                val changed = readAction { builtStatuses.any { (file, status) -> !file.isValid || statuses.status(file) != status } }
                if (changed) requestRefresh() else statusChecksWithoutChange.incrementAndGet()
            }
        }
        val ready = scope.launch(Dispatchers.Default) { awaitStatuses(started) }
        Disposer.register(started.parent) {
            loop.cancel()
            checks.cancel()
            ready.cancel()
            started.requests.close()
            started.statusChecks.close()
        }
        requestRefresh()
    }

    /**
     * A VCS status event (a file, or null for many): compares the statuses the current snapshot was built with, after the
     * debounce, and rebuilds only when one of them changed. Events for files the snapshot never looked at are dropped.
     */
    private fun statusChanged(file: VirtualFile?) {
        if (file != null && !builtStatuses.containsKey(file)) return
        running?.statusChecks?.trySend(Unit)
    }

    private val statusChecksWithoutChange = AtomicLong()

    /** How many status events were checked and found no change that matters (tests). */
    @get:TestOnly
    val unchangedStatusChecks: Long get() = statusChecksWithoutChange.get()

    private fun subscribe(parent: Disposable) {
        val connection = project.messageBus.connect(parent)
        connection.subscribe(
            VirtualFileManager.VFS_CHANGES_BG,
            object : BulkFileListenerBackgroundable {
                override fun after(events: List<VFileEvent>) {
                    if (events.any(::isRelevant)) requestRefresh()
                }
            },
        )
        connection.subscribe(AnsibleStructureListener.TOPIC, AnsibleStructureListener { requestRefresh() })
        connection.subscribe(
            AnsibilitySettingsListener.TOPIC,
            object : AnsibilitySettingsListener {
                override fun projectSettingsChanged(old: ProjectSettings, new: ProjectSettings) = requestRefresh()
            },
        )
        connection.subscribe(
            DumbService.DUMB_MODE,
            object : DumbService.DumbModeListener {
                override fun exitDumbMode() = requestRefresh()
            },
        )
    }

    /** Asks for a new snapshot once the service runs ([start]); cheap, any thread. */
    fun requestRefresh() {
        running?.requests?.trySend(Unit)
    }

    /** Makes [snapshot] current, publishes it when it changed and hands it to the notifier; then counts the build. */
    private suspend fun publish(snapshot: SecretSnapshot) {
        val old = current
        current = snapshot
        if (!project.isDisposed) {
            if (old != snapshot) project.messageBus.syncPublisher(SecretHealthListener.TOPIC).snapshotChanged(old, snapshot)
            withContext(Dispatchers.EDT) {
                if (!project.isDisposed) SecretNotifier.getInstance(project).snapshotChanged(snapshot)
            }
        }
        built.incrementAndGet()
    }

    /**
     * Waits until the VCS knows the statuses, then builds a snapshot with the ANS-V108 findings and tells the notifier.
     * An answer later than [readyTimeoutMillis] is logged and still awaited: before it, an ignored file would count as
     * committed.
     */
    private suspend fun awaitStatuses(started: Running) {
        val ready = CompletableDeferred<Unit>()
        TrackedStatuses.getInstance(project).whenReady { ready.complete(Unit) }
        if (withTimeoutOrNull(readyTimeoutMillis) { ready.await() } == null) {
            VaultLog.event(VaultLog.Operation.STATUS, VaultLog.Event.STATUSES_LATE)
            ready.await()
        }
        if (running !== started) return
        statusesKnown = true
        withContext(Dispatchers.EDT) {
            if (!project.isDisposed) SecretNotifier.getInstance(project).statusesReady()
        }
        requestRefresh()
    }

    override fun dispose() {
        running = null
    }

    /** True once the VCS reported the statuses (tests). */
    @get:TestOnly
    val areStatusesKnown: Boolean get() = statusesKnown

    /** Stops the service and forgets its snapshot (tests start it themselves and stop it in `tearDown`). */
    @TestOnly
    fun stopForTests() {
        val stopped = synchronized(lock) { running.also { running = null } }
        stopped?.let { Disposer.dispose(it.parent) }
        current = SecretSnapshot.NOT_COMPUTED
        builtStatuses = emptyMap()
        statusesKnown = false
        statusChecksWithoutChange.set(0)
    }

    companion object {
        /** Collects a burst of events (a save of several files, a pull, a branch switch) into one build. */
        const val DEBOUNCE_MS: Long = 1000

        /** After this long without the VCS's first statuses a line is logged (they are still awaited). */
        const val READY_TIMEOUT_MS: Long = 60_000

        fun getInstance(project: Project): SecretHealthService = project.service()

        /**
         * Whether a VFS [event] can change a snapshot: a file `ansibility.secrets` indexes (its path decides) was
         * created, changed, deleted, moved, copied or renamed, or a directory was (its files come and go with it).
         */
        fun isRelevant(event: VFileEvent): Boolean = when (event) {
            is VFileContentChangeEvent -> SecretIndex.isCandidatePath(event.path)
            is VFileCreateEvent -> event.isDirectory || SecretIndex.isCandidatePath(event.path)
            is VFileDeleteEvent -> event.file.isDirectory || SecretIndex.isCandidatePath(event.path)
            is VFileMoveEvent -> event.file.isDirectory || SecretIndex.isCandidatePath(event.oldPath) || SecretIndex.isCandidatePath(event.newPath)
            is VFileCopyEvent -> event.file.isDirectory || SecretIndex.isCandidatePath("${event.newParent.path}/${event.newChildName}")
            is VFilePropertyChangeEvent ->
                event.isRename && (event.file.isDirectory || SecretIndex.isCandidatePath(event.oldPath) || SecretIndex.isCandidatePath(event.newPath))
            else -> false
        }
    }
}

/** Starts the [SecretHealthService] when a project opens (its first snapshot waits for indexing). */
class SecretHealthActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Tests start the service themselves: a light project outlives a test class.
        if (ApplicationManager.getApplication().isUnitTestMode) return
        SecretHealthService.getInstance(project).start()
    }
}
