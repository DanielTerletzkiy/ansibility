package de.terletzkiy.ansibility.runtime

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.context.AnsibleStructureListener
import de.terletzkiy.ansibility.index.AnsibleIndexQueries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Project service that feeds [LocalDocRefresher] with the modules the project uses (plan A.11 "Runtime refresh",
 * D14): once indexing is done it reads, per root, the module names of the `ansible.module.use` index
 * ([AnsibleIndexQueries.moduleNames]) in a smart read action and passes them to
 * [LocalDocRefresher.requestRefresh], which batches them into `ansible-doc` runs and skips what its cache knows.
 *
 * [start] runs the first prefetch and follows [AnsibleStructureListener]: every structure change schedules another
 * one after [debounce], and a change within that time restarts the wait, so a burst of file events costs one
 * index query. Nothing is scheduled or queried while the background refresh ([AnsibleRuntimeOptions.localDocRefresh],
 * bound to the D14 setting) is off.
 */
@Service(Service.Level.PROJECT)
class LocalDocPrefetcher(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private val started = AtomicBoolean()
    private val lock = Any()
    private var pending: Job? = null

    /** How long [schedule] waits for further structure changes before querying the index. */
    @Volatile
    internal var debounce: Duration = 2.seconds

    /** Where the collected names go; tests replace it to observe the requests without starting processes. */
    @Volatile
    internal var sink: (AnsibleRoot, Set<String>) -> Unit = { root, names -> LocalDocRefresher.getInstance(project).requestRefresh(root, names) }

    /** Whether the D14 background refresh is on. */
    val isEnabled: Boolean get() = AnsibleRuntimeOptions.getInstance().localDocRefresh

    /** Subscribes to structure changes and schedules the first prefetch. Later calls do nothing. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        project.messageBus.connect(this).subscribe(AnsibleStructureListener.TOPIC, AnsibleStructureListener { schedule() })
        schedule()
    }

    /** Schedules a [prefetch] after [debounce], replacing one that has not run yet. Never blocks. */
    fun schedule() {
        if (!isEnabled || project.isDisposed) return
        synchronized(lock) {
            pending?.cancel()
            pending = scope.launch {
                delay(debounce)
                try {
                    prefetch()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LOG.warn("Module doc prefetch failed", e)
                }
            }
        }
    }

    /**
     * Waits for smart mode, collects the module names of every root ([moduleNamesByRoot]) and requests their refresh.
     * Returns how many names were requested; 0 when the background refresh is off.
     */
    suspend fun prefetch(): Int {
        if (!isEnabled) return 0
        val byRoot = smartReadAction(project) { moduleNamesByRoot() }
        var requested = 0
        for ((root, names) in byRoot) {
            if (names.isEmpty() || !isEnabled) continue
            sink(root, names)
            requested += names.size
        }
        return requested
    }

    /** The module names used in each root, as written in the tasks. Call in a read action in smart mode. */
    fun moduleNamesByRoot(): Map<AnsibleRoot, Set<String>> =
        AnsibleWorkspace.getInstance(project).roots().associateWith { root ->
            ProgressManager.checkCanceled()
            AnsibleIndexQueries.moduleNames(project, root)
        }

    override fun dispose() {
        synchronized(lock) { pending?.cancel() }
    }

    companion object {
        private val LOG = logger<LocalDocPrefetcher>()

        fun getInstance(project: Project): LocalDocPrefetcher = project.service()
    }
}

/** Starts [LocalDocPrefetcher] after the project opened (`postStartupActivity`); tests start it themselves. */
class LocalDocPrefetchActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        LocalDocPrefetcher.getInstance(project).start()
    }
}
