package de.terletzkiy.ansibility.model.inventory

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.concurrency.ThreadingAssertions
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Whether a [ModelCache] holds model state (inventories, views, plays, reach, execution inputs, file scopes) or
 * presentation state that depends on a selection. "Switching env, host or play recomputes 0 model caches" (plan
 * amendment R7/R8, Testing §5) is measured over the [MODEL] caches.
 */
enum class ModelCacheKind { MODEL, PRESENTATION }

/** Monotonic counters of one [ModelCache] over the project's lifetime; tests compare deltas ([ModelCaches.snapshot]). */
class ModelCacheStats internal constructor(val name: String, val kind: ModelCacheKind) {
    private val computed = AtomicLong()
    private val invalidated = AtomicLong()
    private val hit = AtomicLong()

    /** Values computed: first computations and recomputations of stale entries. */
    val computations: Long get() = computed.get()

    /** Entries that were found stale (an input changed) and recomputed; a subset of [computations]. */
    val invalidations: Long get() = invalidated.get()

    /** Lookups answered by a current entry. */
    val hits: Long get() = hit.get()

    internal fun computedOne() = computed.incrementAndGet()

    internal fun invalidatedOne() = invalidated.incrementAndGet()

    internal fun hitOne() = hit.incrementAndGet()

    override fun toString(): String = "$name(computations=$computations, invalidations=$invalidations, hits=$hits)"
}

/**
 * Records what a [ModelCache] value is computed from (DEV.md rule 9; plan amendment R7/R8, A.9).
 *
 * While a cache computes a value on a thread, everything the computation reads becomes an input of the new entry:
 * - every file a model loader reads, by its committed content stamp ([file]; [VarsDocuments.load] and the other
 *   loaders call it) or by its saved stamp ([savedFile], for content read from disk such as `ansible.cfg`);
 * - every value of another [ModelCache] it reads (recorded by the cache itself), so invalidation is transitive;
 * - [external] values of services that keep their own caches, compared by equality.
 *
 * Calls outside a computation record nothing. The entry stays valid while none of its inputs changes and the layout
 * stamp ([ModelCaches.layout]: the Ansible structure, the file tree, the project roots, the settings and the target
 * versions) stays the same; edits of other files never invalidate it.
 */
object ModelInputs {
    private val current = ThreadLocal<Frame?>()

    /** Records [file] by its committed content: typing changes the stamp once the document is committed. */
    fun file(project: Project, file: VirtualFile) {
        current.get()?.content?.putIfAbsent(file, contentStamp(project, file))
    }

    /** Records [file] by its saved content (VFS modification stamp), for inputs read from disk rather than from PSI. */
    fun savedFile(file: VirtualFile) {
        current.get()?.saved?.putIfAbsent(file, savedStamp(file))
    }

    /**
     * Records a value of a service outside the model caches: the entry stays valid while [read] returns a value equal to
     * [value]. [read] runs again only after something changed in the project, and never records inputs itself.
     */
    fun <T> external(value: T, read: () -> T) {
        current.get()?.externals?.add(External(value, read))
    }

    /** Runs [action] without recording into the computation of the calling thread (for reads that must not become inputs). */
    fun <T> untracked(action: () -> T): T {
        val frame = current.get() ?: return action()
        current.set(null)
        try {
            return action()
        } finally {
            current.set(frame)
        }
    }

    /**
     * A stamp that changes whenever the committed content of [file] does: the document's stamp at its last commit while
     * a document is loaded (typing changes it once committed), else the file's own stamp. A saved document gives the file
     * the document's stamp, so dropping and reloading a document keeps the stamp. [INVALID] for a deleted file.
     */
    fun contentStamp(project: Project, file: VirtualFile): Long {
        if (!file.isValid) return INVALID
        val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return file.modificationStamp
        return PsiDocumentManager.getInstance(project).getLastCommittedStamp(document)
    }

    /** The VFS modification stamp of [file] (its saved content), or [INVALID] for a deleted file. */
    fun savedStamp(file: VirtualFile): Long = if (file.isValid) file.modificationStamp else INVALID

    /** The stamp of a deleted file. */
    const val INVALID: Long = -1L

    internal fun upstream(entry: ModelEntry<*>) {
        current.get()?.upstream?.put(entry, Unit)
    }

    /** Runs [compute] with a fresh frame on this thread and returns its value with the inputs it recorded. */
    internal fun <T> recording(compute: () -> T): Pair<T, Frame> {
        val parent = current.get()
        val frame = Frame()
        current.set(frame)
        try {
            return compute() to frame
        } finally {
            current.set(parent)
        }
    }

    /** The inputs one computation recorded. */
    internal class Frame {
        val content = LinkedHashMap<VirtualFile, Long>()
        val saved = LinkedHashMap<VirtualFile, Long>()
        val upstream = IdentityHashMap<ModelEntry<*>, Unit>()
        val externals = ArrayList<External<*>>()
    }

    internal class External<T>(private val value: T, private val read: () -> T) {
        fun isCurrent(): Boolean = untracked(read) == value
    }
}

/**
 * A cache of model values held by a project service and validated by content stamps (DEV.md rule 9; plan amendment
 * R7/R8, A.9). PSI of files nobody has open may be collected at any time, so nothing here is attached to PSI; values
 * are PSI-free and stay valid exactly as long as the inputs [ModelInputs] recorded while computing them.
 *
 * - **Validation** is lazy and cheap: an entry is checked at most once per project epoch ([ModelCaches.epoch], which
 *   moves on any PSI, VFS, structure, root, settings or target-version change), by comparing the layout stamp, the
 *   stamps of its files, its external values and, recursively, its upstream entries. Between changes a lookup is a map
 *   read and one comparison.
 * - **Transitivity:** a value read from another cache while computing becomes an upstream input, so an edit of one
 *   vars file invalidates exactly the entries built from it and the entries built from those.
 * - **Counters** ([stats]) count computations, invalidations and hits for tests and budgets.
 * - **Memory:** a layout change ([ModelCaches.layout]) makes every entry stale, so the first lookup after one drops them
 *   all at once (unless [retainStale] keeps the last values readable through [last]); stale chains of upstream entries
 *   are released with them.
 *
 * Call [get] and [peek] inside a read action; computations call `ProgressManager.checkCanceled()` themselves. A
 * value computed concurrently by two threads is computed twice and the later one is kept; both are correct.
 */
class ModelCache<K : Any, V>(
    private val project: Project,
    name: String,
    kind: ModelCacheKind = ModelCacheKind.MODEL,
    private val maxSize: Int = DEFAULT_MAX_SIZE,
    /** Keep stale entries across layout changes, so [last] still answers (background-built values). */
    private val retainStale: Boolean = false,
) {
    private val caches: ModelCaches = ModelCaches.getInstance(project)
    private val entries = ConcurrentHashMap<K, ModelEntry<V>>()

    /** The layout stamp of the entries held; a different one means they are all stale. */
    @Volatile
    private var entriesLayout = Long.MIN_VALUE

    /** The counters of this cache. */
    val stats: ModelCacheStats = caches.register(name, kind)

    /** The number of entries held (current or not). */
    val size: Int get() = entries.size

    /** The value of [key]: the cached one while its inputs are unchanged, else the result of [compute] (then cached). */
    fun get(key: K, compute: () -> V): V {
        ThreadingAssertions.assertReadAccess()
        val epoch = caches.epoch()
        val layout = caches.layout()
        dropStale(layout)
        val existing = entries[key]
        if (existing != null) {
            if (existing.isCurrent(epoch)) {
                stats.hitOne()
                ModelInputs.upstream(existing)
                return existing.value
            }
            stats.invalidatedOne()
        }
        val (value, frame) = ModelInputs.recording(compute)
        val entry = ModelEntry(project, caches, value, layout, epoch, frame)
        if (entries.size >= maxSize) entries.clear()
        entries[key] = entry
        stats.computedOne()
        ModelInputs.upstream(entry)
        return value
    }

    /**
     * The cached value of [key] when it is current, else null without computing anything (for background-built values
     * that consumers only read when they are ready). A current value read inside a computation becomes its input.
     */
    fun peek(key: K): V? {
        ThreadingAssertions.assertReadAccess()
        val existing = entries[key] ?: return null
        if (!existing.isCurrent(caches.epoch())) return null
        ModelInputs.upstream(existing)
        return existing.value
    }

    /** The cached value of [key] whether or not it is still current (the last complete one), or null. */
    fun last(key: K): V? = entries[key]?.value

    /** The keys of every entry held, current or not. */
    fun keys(): Set<K> = entries.keys.toSet()

    /** Drops every entry. */
    fun clear() = entries.clear()

    /** Drops every entry when the layout moved on since they were computed: none of them can be current any more. */
    private fun dropStale(layout: Long) {
        if (layout == entriesLayout) return
        if (!retainStale) entries.clear()
        entriesLayout = layout
    }

    companion object {
        /** Large enough for every key of the target repo; past it, a cache drops everything (keys of vanished roots go too). */
        const val DEFAULT_MAX_SIZE: Int = 4096
    }
}

/** One cached value of a [ModelCache] with the inputs it was computed from. */
internal class ModelEntry<T>(
    private val project: Project,
    private val caches: ModelCaches,
    val value: T,
    private val layout: Long,
    epoch: Long,
    frame: ModelInputs.Frame,
) {
    private val contentFiles: Array<VirtualFile> = frame.content.keys.toTypedArray()
    private val contentStamps: LongArray = frame.content.values.toLongArray()
    private val savedFiles: Array<VirtualFile> = frame.saved.keys.toTypedArray()
    private val savedStamps: LongArray = frame.saved.values.toLongArray()
    private val upstream: Array<ModelEntry<*>> = frame.upstream.keys.toTypedArray()
    private val externals: Array<ModelInputs.External<*>> = frame.externals.toTypedArray()

    /** The epoch at which the entry was last known to be current. */
    @Volatile
    private var checked: Long = epoch

    @Volatile
    private var stale = false

    /** Whether every input is unchanged; checked at most once per [epoch]. A stale entry never becomes current again. */
    fun isCurrent(epoch: Long): Boolean {
        if (stale) return false
        if (checked == epoch) return true
        val current = layout == caches.layout() &&
            contentFiles.indices.all { ModelInputs.contentStamp(project, contentFiles[it]) == contentStamps[it] } &&
            savedFiles.indices.all { ModelInputs.savedStamp(savedFiles[it]) == savedStamps[it] } &&
            externals.all { it.isCurrent() } &&
            upstream.all { it.isCurrent(epoch) }
        if (current) checked = epoch else stale = true
        return current
    }
}
