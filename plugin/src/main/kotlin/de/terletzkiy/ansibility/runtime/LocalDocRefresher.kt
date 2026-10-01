package de.terletzkiy.ansibility.runtime

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.LocalAnsibleInstall
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.json.Json
import de.terletzkiy.ansibility.semantics.schema.Routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** Documentation of the local install that serves one root (see [LocalDocRefresher.localDocs]). */
class LocalDocs(val install: LocalAnsibleInstall, val catalog: LocalDocCatalog) {
    val core: CoreVersion get() = catalog.core
    val label: String get() = AnsibilityRuntimeBundle.message("source.local", catalog.core.toString())
}

/**
 * Project service: the background runtime refresh of module docs from the local install (plan A.11, D14).
 *
 * [refresh] runs `ansible-doc -t module -j <fqcn…>` in batches for the modules the cache does not know yet,
 * normalises the output like the bundled snapshots ([AnsibleDocNormalizer]) and stores it under
 * `<system dir>/ansibility/docs/<hash(core, collections)>.json.gz` (nothing is ever written into the repository).
 * When new docs arrive, [docsTracker] is bumped and highlighting restarts. [requestRefresh] batches requests from
 * doc lookups. Everything is off when [AnsibleRuntimeOptions.localDocRefresh] is off.
 */
@Service(Service.Level.PROJECT)
class LocalDocRefresher(private val project: Project, private val scope: CoroutineScope) {
    private val tracker = SimpleModificationTracker()
    private val catalogs = ConcurrentHashMap<String, LocalDocCatalog>()
    private val rootInstalls = ConcurrentHashMap<String, RootBinding>()
    private val keyLocks = ConcurrentHashMap<String, Mutex>()
    private val pending = ConcurrentHashMap<String, Pending>()
    private val unavailableUntil = ConcurrentHashMap<String, Long>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val collectionCache = ConcurrentHashMap<LocalAnsibleInstall, Pair<Long, Map<String, String>>>()

    /** Bumped whenever local docs for some root change. */
    val docsTracker: ModificationTracker get() = tracker

    /** How long [requestRefresh] waits to batch requests. */
    @Volatile
    internal var debounce: Duration = 500.milliseconds

    /** Replaces [cacheDirectory] (tests point it at a temporary directory). */
    @Volatile
    internal var cacheDirectoryOverride: Path? = null

    val isEnabled: Boolean get() = AnsibleRuntimeOptions.getInstance().localDocRefresh

    /** The cache directory, `<system dir>/ansibility/docs`. */
    val cacheDirectory: Path get() = cacheDirectoryOverride ?: PathManager.getSystemDir().resolve("ansibility").resolve("docs")

    /** The local docs serving [root], or null when nothing was refreshed for it or the refresh is off. Never blocks. */
    fun localDocs(root: AnsibleRoot): LocalDocs? {
        if (!isEnabled) return null
        val binding = rootInstalls[root.dir.url] ?: return null
        return catalogs[binding.key]?.let { LocalDocs(binding.install, it) }
    }

    /**
     * Schedules a [refresh] of [fqcns] for [root] after [debounce], together with every other request that arrives
     * meanwhile. Names the root's local docs already know (documented or not found) are skipped. Never blocks.
     */
    fun requestRefresh(root: AnsibleRoot, fqcns: Collection<String>) {
        if (!isEnabled || fqcns.isEmpty()) return
        val until = unavailableUntil[root.dir.url]
        if (until != null && System.currentTimeMillis() < until) return
        val known = localDocs(root)?.catalog
        val fresh = fqcns.map(Routing::normalise).filter { known?.knows(it) != true && it !in failed }
        if (fresh.isEmpty()) return
        val batch = pending.computeIfAbsent(root.dir.url) { Pending(root) }
        batch.names.addAll(fresh)
        if (!batch.scheduled.compareAndSet(false, true)) return
        batch.job = scope.launch {
            delay(debounce)
            batch.scheduled.set(false)
            val names = batch.names.toList()
            batch.names.removeAll(names.toSet())
            if (names.isEmpty()) return@launch
            try {
                refresh(batch.root, names)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Local doc refresh failed for ${batch.root.displayName}", e)
            }
        }
    }

    /**
     * Refreshes [fqcns] for [root] now and returns how many modules were added. Runs on [Dispatchers.IO]; never
     * call it from a read action. Does nothing when the refresh is off or no local `ansible` is found.
     */
    suspend fun refresh(root: AnsibleRoot, fqcns: Collection<String>): Int {
        if (!isEnabled) return 0
        val names = fqcns.map(Routing::normalise).toSortedSet()
        val config = ansibleConfigOf(root)
        val toolchain = AnsibleToolchain.getInstance()
        val install = toolchain.probe(project, config)
        if (install == null) {
            unavailableUntil[root.dir.url] = System.currentTimeMillis() + UNAVAILABLE_RETRY.inWholeMilliseconds
            return 0
        }
        unavailableUntil.remove(root.dir.url)
        val collections = withContext(Dispatchers.IO) { collectionVersions(install, toolchain) }
        val key = LocalDocCatalog.keyOf(install.coreVersion, collections)
        return keyLocks.computeIfAbsent(key) { Mutex() }.withLock {
            var catalog = catalogs[key] ?: withContext(Dispatchers.IO) { LocalDocCatalog.read(cacheFile(key)) }?.takeIf { it.key == key }
            val rebound = rootInstalls.put(root.dir.url, RootBinding(key, install))?.key != key
            val wanted = names.filter { catalog?.knows(it) != true && it !in failed }
            var added = 0
            if (wanted.isNotEmpty()) {
                val docTool = withContext(Dispatchers.IO) { docToolOf(install, toolchain) }
                if (docTool != null) {
                    val fetched = fetch(docTool, wanted, config)
                    val normalised = AnsibleDocNormalizer.modules(fetched.docs, sitePackagesOf(install))
                    val base = catalog ?: LocalDocCatalog.empty(install.coreVersion, collections)
                    catalog = base.merge(normalised, fetched.notFound)
                    added = normalised.modules.size
                    if (added > 0 || fetched.notFound.isNotEmpty()) {
                        val toWrite = catalog
                        withContext(Dispatchers.IO) {
                            runCatching { LocalDocCatalog.write(cacheFile(key), toWrite) }
                                .onFailure { LOG.info("Could not write the doc cache: ${it.message}") }
                        }
                    }
                } else {
                    LOG.info("No ansible-doc next to ${install.executable}; local docs are not refreshed")
                    failed += wanted
                }
            }
            if (catalog != null) {
                val previous = catalogs.put(key, catalog)
                if (added > 0 || rebound || previous == null) announce()
            }
            added
        }
    }

    private suspend fun fetch(docTool: Path, names: List<String>, config: Path?): Fetched {
        val runner = ProcessRunner.getInstance()
        val docs = LinkedHashMap<String, Any?>()
        val notFound = LinkedHashSet<String>()

        suspend fun runBatch(batch: List<String>): Boolean {
            val result = runner.run(ToolCommand(docTool, listOf("-t", "module", "-j") + batch, config))
            val parsed = if (result.isSuccess) runCatching { Json.parseObject(result.stdout.ifBlank { "{}" }) }.getOrNull() else null
            if (parsed == null) {
                if (batch.size == 1) {
                    LOG.info("ansible-doc ${batch.single()}: ${result.describe()}")
                    failed += batch.single()
                }
                return false
            }
            docs.putAll(parsed)
            batch.filterTo(notFound) { it !in parsed }
            return true
        }

        for (batch in names.chunked(BATCH)) {
            if (!runBatch(batch) && batch.size > 1) {
                for (name in batch) runBatch(listOf(name))
            }
        }
        return Fetched(docs, notFound)
    }

    /** `ansible-doc` next to the probed `ansible` (so both belong to one install), else the usual lookup. */
    private fun docToolOf(install: LocalAnsibleInstall, toolchain: AnsibleToolchain): Path? {
        val sibling = runCatching { Path.of(install.executable).resolveSibling(AnsibleTool.ANSIBLE_DOC.executableName) }.getOrNull()
        if (sibling != null && AnsibleToolchain.isExecutable(sibling)) return sibling
        return toolchain.locate(AnsibleTool.ANSIBLE_DOC, project)
    }

    private fun announce() {
        tracker.incModificationCount()
        if (!project.isDisposed) DaemonCodeAnalyzer.getInstance(project).restart("ansibility docs")
    }

    /** Collection versions of [install], re-read at most every [COLLECTIONS_TTL] (about a hundred small files). */
    private fun collectionVersions(install: LocalAnsibleInstall, toolchain: AnsibleToolchain): Map<String, String> {
        val now = System.currentTimeMillis()
        collectionCache[install]?.takeIf { now - it.first < COLLECTIONS_TTL.inWholeMilliseconds }?.let { return it.second }
        return toolchain.collectionVersions(install).also { collectionCache[install] = now to it }
    }

    /** Forgets every catalog, binding and pending request (the light test project outlives a test). */
    @TestOnly
    internal fun resetForTests() {
        pending.values.forEach { it.job?.cancel() }
        pending.clear()
        catalogs.clear()
        rootInstalls.clear()
        unavailableUntil.clear()
        failed.clear()
        collectionCache.clear()
    }

    private fun cacheFile(key: String): Path = cacheDirectory.resolve("$key.json.gz")

    private class Pending(val root: AnsibleRoot) {
        val names: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val scheduled = AtomicBoolean()

        @Volatile
        var job: Job? = null
    }

    private class RootBinding(val key: String, val install: LocalAnsibleInstall)

    private class Fetched(val docs: Map<String, Any?>, val notFound: Set<String>)

    companion object {
        private val LOG = logger<LocalDocRefresher>()

        /** Modules per `ansible-doc` call (as in the snapshot generator). */
        const val BATCH: Int = 60

        /** How long a root without a local `ansible` is left alone before lookups ask again. */
        private val UNAVAILABLE_RETRY: Duration = 5.minutes

        private val COLLECTIONS_TTL: Duration = 1.minutes

        fun getInstance(project: Project): LocalDocRefresher = project.service()

        /** The root's `ansible.cfg` (a nested playbook root uses its parent's), as a local path. */
        fun ansibleConfigOf(root: AnsibleRoot): Path? {
            for (dir in listOfNotNull(root.dir, root.parentDir)) {
                val cfg = dir.fileSystem.getNioPath(dir)?.resolve("ansible.cfg") ?: continue
                if (Files.isRegularFile(cfg)) return cfg
            }
            return null
        }

        private fun sitePackagesOf(install: LocalAnsibleInstall): String? =
            install.moduleLocation?.let { runCatching { Path.of(it).parent?.toString() }.getOrNull() }
    }
}
