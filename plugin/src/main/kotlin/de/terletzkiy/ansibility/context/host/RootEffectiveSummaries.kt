package de.terletzkiy.ansibility.context.host

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiModificationTracker
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.context.AnsibleStructureListener
import de.terletzkiy.ansibility.model.inventory.ModelCache
import de.terletzkiy.ansibility.model.inventory.ModelCacheStats
import de.terletzkiy.ansibility.settings.AnsibilitySettingsListener
import de.terletzkiy.ansibility.settings.ProjectSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The per-root [RootEffectiveSummary]s, built in the background (plan amendment R7/R8, A.9 changes 3 and 6).
 *
 * - **Reading.** [current] returns the summary of a root when it is up to date and otherwise null (and asks for a
 *   build): completion's hot path then falls back to the index previews, hover and inspections compute their own small
 *   scopes. [latest] returns the last complete summary even when it is stale. [compute] builds synchronously in the
 *   caller's read action (tests, batch callers).
 * - **Building.** A summary is a [ModelCache] entry over the views, play graphs and execution inputs it was built from,
 *   so it is stale exactly when one of them is. Roots are built on demand ([current], [request]) and then kept current:
 *   PSI, VFS, structure and settings changes wake a coroutine that waits until 500 ms passed without a change
 *   ([DEBOUNCE_MILLIS]), then rebuilds the stale summaries root by root, each in one cancellable `smartReadAction`.
 *   Edits that change no input (a role's tasks, a template) leave the summaries current and nothing is rebuilt.
 * - **Daemon.** When a rebuilt summary changes where a definition takes effect, the daemon restarts for the open files
 *   that hold such definitions (`DaemonCodeAnalyzer.restart(psiFile, reason)`), and only for those.
 *
 * Summaries hold locations, never values, so nothing secret is kept; nothing here decrypts a vault or runs a process.
 * In unit-test mode the background worker is off unless a test switches it on ([setBackgroundEnabled]), so counter
 * checks of other tests never race with it.
 */
@Service(Service.Level.PROJECT)
class RootEffectiveSummaries(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private val cache = ModelCache<AnsibleRoot, RootEffectiveSummary>(project, CACHE_NAME, maxSize = MAX_ROOTS, retainStale = true)
    private val requested: MutableSet<AnsibleRoot> = ConcurrentHashMap.newKeySet()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val workerStarted = AtomicBoolean()
    private val restarts = AtomicLong()
    private val backgroundBuilds = AtomicLong()
    private val busy = AtomicBoolean()

    @Volatile
    private var lastChangeNanos = 0L

    @Volatile
    private var backgroundEnabled = !ApplicationManager.getApplication().isUnitTestMode

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(PsiModificationTracker.TOPIC, PsiModificationTracker.Listener { changed() })
        connection.subscribe(AnsibleStructureListener.TOPIC, AnsibleStructureListener { changed() })
        connection.subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) = changed()
            },
        )
        connection.subscribe(
            AnsibilitySettingsListener.TOPIC,
            object : AnsibilitySettingsListener {
                override fun projectSettingsChanged(old: ProjectSettings, new: ProjectSettings) = changed()
            },
        )
    }

    override fun dispose() {
        wake.close()
        requested.clear()
        cache.clear()
    }

    /** The counters of the summary cache (one computation per root build). */
    val stats: ModelCacheStats get() = cache.stats

    /** Builds done by the background worker (a subset of [stats] computations). */
    val backgroundBuildCount: Long get() = backgroundBuilds.get()

    /** Daemon restarts the worker requested for open files whose definitions changed status. */
    val restartCount: Long get() = restarts.get()

    /**
     * The up-to-date summary of [root] (of its inventory root for a nested root), or null when none is built yet or the
     * last one is stale; then a background build is requested. Never computes anything itself.
     */
    fun current(root: AnsibleRoot): RootEffectiveSummary? = readLocked {
        val owner = inventoryRoot(root)
        cache.peek(owner).also { if (it == null) request(owner) }
    }

    /** The last complete summary of [root], current or not, or null when none was built. */
    fun latest(root: AnsibleRoot): RootEffectiveSummary? = readLocked { cache.last(inventoryRoot(root)) }

    /**
     * The summary of [root], built now in the caller's read action when it is missing or stale (cancellable). Keeps the
     * root current in the background from then on.
     */
    fun compute(root: AnsibleRoot): RootEffectiveSummary = readLocked {
        val owner = inventoryRoot(root)
        requested += owner
        build(owner)
    }

    /** Asks for [root]'s summary to be built in the background (no-op when it is current) and kept current afterwards. */
    fun request(root: AnsibleRoot) {
        requested += root
        signal()
    }

    /** Drops every summary (benchmarks measure building them again). */
    @TestOnly
    fun clear() = cache.clear()

    /** Whether the background worker is between a wake-up and the end of its rebuilds (tests wait for it to finish). */
    @get:TestOnly
    val isBusy: Boolean get() = busy.get()

    /** Whether the background worker runs (off in unit-test mode by default). */
    @TestOnly
    fun setBackgroundEnabled(enabled: Boolean, parent: Disposable) {
        val previous = backgroundEnabled
        backgroundEnabled = enabled
        Disposer.register(parent) { backgroundEnabled = previous }
        if (enabled) signal()
    }

    private fun changed() {
        if (requested.isEmpty()) return
        lastChangeNanos = System.nanoTime()
        signal()
    }

    private fun signal() {
        if (!backgroundEnabled || project.isDisposed) return
        if (workerStarted.compareAndSet(false, true)) scope.launch(Dispatchers.Default) { work() }
        wake.trySend(Unit)
    }

    private suspend fun work() {
        while (!wake.receiveCatching().isClosed) {
            busy.set(true)
            try {
                while (true) {
                    val waitMillis = DEBOUNCE_MILLIS - (System.nanoTime() - lastChangeNanos) / NANOS_PER_MILLI
                    if (waitMillis <= 0) break
                    delay(waitMillis)
                }
                if (backgroundEnabled) rebuildStale()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Ansibility: summary build failed (${e.javaClass.simpleName})", e)
            } finally {
                busy.set(false)
            }
        }
    }

    /** Rebuilds every requested summary that is stale, root by root, and restarts the daemon where statuses changed. */
    private suspend fun rebuildStale() {
        for (root in requested.toList()) {
            val result = smartReadAction(project) {
                if (!root.dir.isValid || root !in AnsibleWorkspace.getInstance(project).roots()) {
                    requested.remove(root)
                    null
                } else if (cache.peek(root) != null) {
                    null
                } else {
                    val previous = cache.last(root)
                    val next = build(root)
                    backgroundBuilds.incrementAndGet()
                    previous?.let { changedFiles(it, next) }
                }
            }
            if (!result.isNullOrEmpty()) restartDaemon(result)
        }
    }

    private fun build(root: AnsibleRoot): RootEffectiveSummary {
        val impl = AnsibleContextServiceImpl.getInstance(project) ?: error("the Ansible context service is not AnsibleContextServiceImpl")
        return cache.get(root) { SummaryBuilder.build(root, impl.model, impl.evaluator) }
    }

    private fun inventoryRoot(root: AnsibleRoot): AnsibleRoot = AnsibleContextServiceImpl.getInstance(project)?.inventoryRoot(root) ?: root

    /** The files holding a definition whose status differs between [old] and [new] (or that only one of them knows). */
    private fun changedFiles(old: RootEffectiveSummary, new: RootEffectiveSummary): Set<VirtualFile> {
        val files = LinkedHashSet<VirtualFile>()
        val seen = HashSet<RootEffectiveSummary.LocationKey>()
        for (status in new.statuses()) {
            val definition = status.definition
            seen += RootEffectiveSummary.LocationKey(definition.location.file, definition.location.offset, definition.name)
            val before = old.status(definition.location, definition.name)
            if (before == null || !sameStatus(before, status)) files += definition.location.file
        }
        for (status in old.statuses()) {
            val definition = status.definition
            if (RootEffectiveSummary.LocationKey(definition.location.file, definition.location.offset, definition.name) !in seen) {
                files += definition.location.file
            }
        }
        return files
    }

    private fun sameStatus(a: SummaryStatus, b: SummaryStatus): Boolean =
        a.winsOn == b.winsOn &&
            a.shadowedOn.keys == b.shadowedOn.keys &&
            a.shadowedOn.all { (host, winner) -> b.shadowedOn[host]?.location == winner.location }

    private suspend fun restartDaemon(files: Set<VirtualFile>) {
        val open = withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project).openFiles.toSet() }
        val targets = files.filter { it in open }
        if (targets.isEmpty()) return
        val psiFiles = readAction { targets.mapNotNull { if (it.isValid) PsiManager.getInstance(project).findFile(it) else null } }
        withContext(Dispatchers.EDT) {
            if (project.isDisposed) return@withContext
            for (psiFile in psiFiles) {
                DaemonCodeAnalyzer.getInstance(project).restart(psiFile, RESTART_REASON)
                restarts.incrementAndGet()
            }
        }
    }

    private fun <T> readLocked(action: () -> T): T =
        if (ApplicationManager.getApplication().isReadAccessAllowed) action() else runReadActionBlocking(action)

    companion object {
        /** The [ModelCache] name of the summaries ([de.terletzkiy.ansibility.model.inventory.ModelCaches.snapshot]). */
        const val CACHE_NAME: String = "summary.root"

        /** How long the model must stay unchanged before stale summaries are rebuilt. */
        const val DEBOUNCE_MILLIS: Long = 500

        private const val NANOS_PER_MILLI = 1_000_000L
        private const val MAX_ROOTS = 256
        private const val RESTART_REASON = "ansibility: effective summary changed"
        private val LOG = logger<RootEffectiveSummaries>()

        fun getInstance(project: Project): RootEffectiveSummaries = project.service()
    }
}
