package de.terletzkiy.ansibility.golden.history

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.progress.runBlockingMaybeCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.annotations.TestOnly
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

/** What [LastChanges] knows of a file or directory: the latest answer, and whether it still holds ([fresh]). */
data class KnownLastChange(val value: LastChange?, val fresh: Boolean)

/**
 * The last committed change of role files and role directories (plan amendment R24: D180 "Last changed" per side,
 * D183 diff titles), over [LastChangeLookup] (the VCS one comes from the optional `ansibility-vcs.xml`).
 *
 * - **Cache**: per file (and its modification stamp) and per directory. An answer is fresh until the lookup reports a
 *   change ([LastChangeLookup.watch]: a commit, a pull, a branch switch, but also every file status refresh the
 *   platform runs after a save). Such a report never drops an answer: it keeps it as the last known one ([known]) and
 *   the next request revalidates it, so the details and diff titles keep showing it meanwhile. Concurrent requests for
 *   the same file share one lookup.
 * - **Threading**: lookups run in the service's scope, never on the EDT, on two lanes of at most two I/O threads each
 *   (git runs a process per lookup): the tool window's details, and Compare's diff titles, which so never wait behind
 *   the details. The blocking queries wait on a background thread and are cancellable; on the EDT they answer from
 *   the cache only. A lookup counts its waiters: when the last one is cancelled (a newer selection), the lookup is
 *   cancelled too, queued or running, so arrow-key navigation leaves no backlog of processes. A wait that merely ends
 *   (Compare's time budget) leaves its lookup running: the answer still fills the cache.
 * - **Without a lookup** (an IDE without VCS support) every answer is null, at once.
 */
@Service(Service.Level.PROJECT)
class LastChanges(private val project: Project, private val scope: CoroutineScope) : Disposable {
    /** An answer, fresh while [epoch] is the current one and the file still has [stamp]. */
    private class Cached(val stamp: Long, val epoch: Long, val value: LastChange?)

    /** Who waits for a lookup: the tool window's details, or Compare's titles (their own threads). */
    private enum class Lane { DETAILS, TITLES }

    private data class Key(val file: VirtualFile, val directory: Boolean, val stamp: Long, val epoch: Long, val lane: Lane)

    /** A lookup in flight and how many callers wait for it; when the last waiter is cancelled, so is the lookup. */
    private class Lookup(val key: Key) {
        lateinit var deferred: Deferred<LastChange?>
        private var waiters = 0
        private var cancelled = false

        /** Adds a waiter; false when the lookup was cancelled already (the caller starts a new one). */
        @Synchronized
        fun join(): Boolean {
            if (cancelled) return false
            waiters++
            return true
        }

        /**
         * Removes a waiter; the last one, when [cancel], cancels the lookup unless it has finished. True when it was
         * cancelled now.
         */
        @Synchronized
        fun leave(cancel: Boolean): Boolean {
            waiters--
            if (!cancel || waiters > 0 || deferred.isCompleted) return false
            cancelled = true
            deferred.cancel()
            return true
        }
    }

    /** One caller's wait for a lookup ([lookup] null: answered from the cache). */
    private class Wait(val deferred: Deferred<LastChange?>, val lookup: Lookup?)

    private val files = ConcurrentHashMap<VirtualFile, Cached>()
    private val dirs = ConcurrentHashMap<VirtualFile, Cached>()
    private val pending = ConcurrentHashMap<Key, Lookup>()
    private val epoch = AtomicLong()
    private val watched: MutableSet<LastChangeLookup> = Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap()))
    private val calls = AtomicInteger()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatchers: Map<Lane, CoroutineDispatcher> = Lane.entries.associateWith { Dispatchers.IO.limitedParallelism(LOOKUP_PARALLELISM) }

    /** Lookups started (cache misses that reached a [LastChangeLookup]); for tests. */
    val lookupCount: Int get() = calls.get()

    /** The fresh cached last change of [file], or null when unknown or being revalidated. Never computes; any thread. */
    fun cached(file: VirtualFile): LastChange? = files[file]?.takeIf { fresh(it, file, false) }?.value

    /** The fresh cached last change below [dir], or null when unknown or being revalidated. Never computes; any thread. */
    fun cachedUnder(dir: VirtualFile): LastChange? = dirs[dir]?.takeIf { fresh(it, dir, true) }?.value

    /**
     * The latest answer for [file] (the directory [file] when [directory]), also one a reported change made stale
     * (shown until the revalidation answers), or null when it was never looked up. Never computes; any thread.
     */
    fun known(file: VirtualFile, directory: Boolean): KnownLastChange? {
        val cached = (if (directory) dirs else files)[file] ?: return null
        return KnownLastChange(cached.value, fresh(cached, file, directory))
    }

    /** The last change of [file], waiting for the lookup. Background thread; on the EDT only [cached]. */
    fun lastChange(file: VirtualFile): LastChange? = query(file, false)

    /** The last change below the directory [dir] (a role copy). Background thread; on the EDT only [cachedUnder]. */
    fun lastChangeUnder(dir: VirtualFile): LastChange? = query(dir, true)

    /**
     * The last change of each of [files] (null entries stay null), looked up in parallel on the titles' own threads and
     * waited for at most [budget] in all; a side still being looked up then gets its last known answer, and answers
     * that come later go to the cache only. Background thread; on the EDT only [cached].
     */
    fun lastChanges(files: List<VirtualFile?>, budget: Duration): List<LastChange?> {
        if (ApplicationManager.getApplication().isDispatchThread) return files.map { it?.let(::cached) }
        val waits = files.map { file -> file?.let { request(it, false, Lane.TITLES) } }
        if (waits.all { it == null }) return files.map { null }
        return waiting(waits.filterNotNull()) {
            runBlockingMaybeCancellable {
                withTimeoutOrNull(budget) { waits.forEach { it?.deferred?.await() } }
                waits.mapIndexed { index, wait ->
                    val deferred = wait?.deferred ?: return@mapIndexed null
                    if (deferred.isCompleted) completed(deferred) else files[index]?.let { known(it, false)?.value }
                }
            }
        }
    }

    private fun query(file: VirtualFile, directory: Boolean): LastChange? {
        if (ApplicationManager.getApplication().isDispatchThread) return if (directory) cachedUnder(file) else cached(file)
        val wait = request(file, directory, Lane.DETAILS) ?: return null
        return waiting(listOf(wait)) {
            if (wait.deferred.isCompleted) completed(wait.deferred) else runBlockingMaybeCancellable { wait.deferred.await() }
        }
    }

    /**
     * Runs [block] (the wait for [waits]) and then leaves each wait: when [block] did not return normally (the caller
     * was cancelled), a lookup nobody else waits for is cancelled.
     */
    private inline fun <T> waiting(waits: List<Wait>, block: () -> T): T {
        var cancelled = true
        try {
            return block().also { cancelled = false }
        } finally {
            for (wait in waits) {
                val lookup = wait.lookup ?: continue
                if (lookup.leave(cancelled)) pending.remove(lookup.key, lookup)
            }
        }
    }

    /** The lookup of [file], from the cache, joined, or started on [lane]; null without any [LastChangeLookup]. */
    private fun request(file: VirtualFile, directory: Boolean, lane: Lane): Wait? {
        if (project.isDisposed || !file.isValid) return null
        val lookups = LastChangeLookup.EP_NAME.extensionList
        if (lookups.isEmpty()) return null
        watch(lookups)
        val cache = if (directory) dirs else files
        cache[file]?.takeIf { fresh(it, file, directory) }?.let { return Wait(CompletableDeferred(it.value), null) }
        val key = Key(file, directory, stampOf(file, directory), epoch.get(), lane)
        while (true) {
            val lookup = pending.computeIfAbsent(key) { start(it, lookups, cache) }
            if (lookup.join()) {
                lookup.deferred.start()
                return Wait(lookup.deferred, lookup)
            }
            pending.remove(key, lookup)
        }
    }

    private fun start(key: Key, lookups: List<LastChangeLookup>, cache: ConcurrentHashMap<VirtualFile, Cached>): Lookup {
        val lookup = Lookup(key)
        lookup.deferred = scope.async(dispatchers.getValue(key.lane), start = CoroutineStart.LAZY) {
            try {
                val value = coroutineToIndicator { _ -> lookupNow(lookups, key.file, key.directory) }
                // Kept even when a change was reported meanwhile (then as stale): it is the latest answer there is.
                // Never over an answer of a later epoch.
                cache.compute(key.file) { _, old -> if (old != null && old.epoch > key.epoch) old else Cached(key.stamp, key.epoch, value) }
                value
            } finally {
                pending.remove(key, lookup)
            }
        }
        return lookup
    }

    private fun lookupNow(lookups: List<LastChangeLookup>, file: VirtualFile, directory: Boolean): LastChange? {
        calls.incrementAndGet()
        for (lookup in lookups) {
            try {
                val answer = if (directory) lookup.lastChangeUnder(project, file) else lookup.lastChange(project, file)
                if (answer != null) return answer
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                LOG.debug("Last change of ${file.path} failed (${e.javaClass.name})")
            }
        }
        return null
    }

    /** Subscribes to each lookup's change events once. */
    private fun watch(lookups: List<LastChangeLookup>) {
        for (lookup in lookups) {
            if (!watched.add(lookup)) continue
            try {
                lookup.watch(project, this) { file -> invalidate(file) }
            } catch (e: Exception) {
                if (e is ProcessCanceledException) throw e
                LOG.debug("Cannot watch last changes (${e.javaClass.name})")
            }
        }
    }

    /**
     * Makes what [file] may affect stale (the file and every cached directory above it; everything for null): the
     * answers stay known ([known]) until a request revalidates them. A status refresh therefore never empties the
     * details or makes them look the same files up in a loop.
     */
    private fun invalidate(file: VirtualFile?) {
        if (file == null) {
            epoch.incrementAndGet()
            return
        }
        files.computeIfPresent(file) { _, cached -> cached.stale() }
        for (dir in dirs.keys) {
            if (VfsUtilCore.isAncestor(dir, file, false)) dirs.computeIfPresent(dir) { _, cached -> cached.stale() }
        }
    }

    private fun Cached.stale(): Cached = if (epoch == STALE) this else Cached(stamp, STALE, value)

    private fun fresh(cached: Cached, file: VirtualFile, directory: Boolean): Boolean =
        cached.epoch == epoch.get() && cached.stamp == stampOf(file, directory)

    private fun stampOf(file: VirtualFile, directory: Boolean): Long = if (directory) 0 else file.modificationStamp

    private fun completed(deferred: Deferred<LastChange?>): LastChange? = try {
        @OptIn(ExperimentalCoroutinesApi::class)
        deferred.getCompleted()
    } catch (e: Exception) {
        if (e is ProcessCanceledException) throw e
        null
    }

    @TestOnly
    fun clearForTests() {
        epoch.incrementAndGet()
        files.clear()
        dirs.clear()
    }

    override fun dispose() {
        files.clear()
        dirs.clear()
        pending.clear()
    }

    companion object {
        private val LOG = logger<LastChanges>()

        /** Lookups that run at once per lane (git starts a process for each). */
        private const val LOOKUP_PARALLELISM = 2

        /** The epoch of an answer a single-file change report made stale. */
        private const val STALE = -1L

        fun getInstance(project: Project): LastChanges = project.service()
    }
}
