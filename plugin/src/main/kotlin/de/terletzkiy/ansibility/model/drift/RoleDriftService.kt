package de.terletzkiy.ansibility.model.drift

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VFileProperty
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
import de.terletzkiy.ansibility.model.role.RoleCatalog
import de.terletzkiy.ansibility.model.role.RoleCatalogSnapshot
import de.terletzkiy.ansibility.model.role.RoleCopy
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.AnsibilitySettingsListener
import de.terletzkiy.ansibility.settings.ProjectSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Debug counters of [RoleDriftService] ("only that one copy is rehashed", the R9 acceptance step 8). Monotonic over
 * the service's lifetime; tests compare deltas. They count files and copies, never hold a hash or a byte.
 */
class DriftCounters internal constructor() {
    internal val hashed = AtomicLong()
    internal val documents = AtomicLong()
    internal val walked = AtomicLong()
    internal val sizeOnly = AtomicLong()
    internal val sensitiveReads = AtomicLong()
    internal val vaults = AtomicLong()

    /** Files whose bytes were read from the VFS and hashed. */
    val filesHashed: Long get() = hashed.get()

    /** Unsaved documents whose text was hashed instead of the file on disk. */
    val documentsHashed: Long get() = documents.get()

    /** Role-copy walks (each a VFS listing plus stamp checks). */
    val copiesWalked: Long get() = walked.get()

    /** Sensitive files fingerprinted by length only, their content never read ([SensitiveContent.SIZE_ONLY]). */
    val sizeOnlyFiles: Long get() = sizeOnly.get()

    /** Files with a sensitive name ([DriftRules.isSensitivePath]) whose bytes were read to hash them; 0 in size-only mode. */
    val sensitiveContentReads: Long get() = sensitiveReads.get()

    /** Whole-file vaults (recognised by their header) that were hashed: ciphertext only, never decrypted. */
    val wholeFileVaults: Long get() = vaults.get()

    override fun toString(): String =
        "DriftCounters(hashed=$filesHashed, documents=$documentsHashed, walks=$copiesWalked, sizeOnly=$sizeOnlyFiles, " +
            "sensitiveReads=$sensitiveContentReads, vaults=$wholeFileVaults)"
}

/**
 * Role drift vs the role library (plan amendment R9, F9.5): for every role name of [RoleCatalog], how each copy
 * differs from the reference copy (golden), by path, and which copies are byte-identical (variants).
 *
 * **Fingerprints.** A copy's fingerprint is the sorted `(path, length, content hash)` list over its directory, without
 * [DriftRules.isSkippedDirectory] directories, [DriftRules.isSkippedFile] files, symlinked directories and the
 * project's ignored paths. File hashes (SHA-256, truncated to 128 bits) are cached per `VirtualFile` and keyed by
 * `(modificationStamp, length)`; a file with an unsaved document is hashed from the document text (in the file's
 * line separators, charset and BOM, so undoing an edit restores the on-disk identity), keyed by the document stamp.
 * Every computation re-walks the copy, so a result is always as fresh as the VFS and the open documents; a warm walk
 * only checks stamps and rehashes edited files.
 *
 * **Tiers and variants** follow [DriftRules]; with [DriftOptions.ignoreMolecule] molecule files take no part.
 *
 * **Laziness.** Nothing is computed until a caller asks: [drift], [driftAll] and [compare] compute what they need;
 * [request] (the Roles tab, a Roles node, a drift action) starts the background worker, which computes the requested
 * names first and then every other name, role by role, publishing [RoleDriftListener.TOPIC] for each name whose result
 * changed. Afterwards it follows edits: its own `VFS_CHANGES_BG` listener and a document listener (debounced
 * [documentDebounce], 500 ms) mark only the owning copy dirty, structure and settings changes re-check the catalog,
 * and only names whose result changed are published.
 *
 * **Threading.** All work runs on background dispatchers in the service's coroutine scope, one read action per role
 * copy with `checkCanceled()` per file; never on the EDT, never a process. Cancelled with the project. The
 * suspending queries switch to a background dispatcher themselves; call them from a coroutine, never through
 * `runBlocking` on the EDT or inside a read action (their read actions would wait for a write action that the
 * blocked thread holds up). Read-action and EDT callers use [cached].
 *
 * **Secrets.** Only hashes are kept, in memory, never persisted; the bytes are dropped right after hashing (zeroed
 * for sensitive files), neither contents nor hashes are logged, and nothing is ever decrypted. Sensitive files
 * ([DriftRules.isSensitivePath], whole-file vaults) are fingerprinted like any file but reported in
 * [DriftPaths.sensitive], so their content is never shown.
 */
@Service(Service.Level.PROJECT)
class RoleDriftService(private val project: Project, private val scope: CoroutineScope) : Disposable {
    /** A file of a copy as the walk found it: where its bytes come from and the stamp and length they are valid for. */
    private class Listed(val relPath: String, val file: VirtualFile, val document: Document?, val stamp: Long, val length: Long) {
        val fromDocument: Boolean get() = document != null

        fun sameSource(other: Listed): Boolean = fromDocument == other.fromDocument && stamp == other.stamp && length == other.length
    }

    /**
     * The fingerprint of one copy as computed at [generation] of its [CopyState]: per file, in walk order, the
     * `VirtualFile`, the stamp its hash is valid for (the document stamp for an unsaved document) and its [FileEntry].
     * Parallel arrays keep the resident size near 100 bytes per file (plan budget: under 1 MB for the whole repo).
     */
    private class CopyFingerprint(
        val generation: Long,
        private val files: Array<VirtualFile>,
        private val stamps: LongArray,
        private val fromDocument: BooleanArray,
        val entries: List<FileEntry>,
    ) {
        /** Where each file is, to reuse its hash in the next walk. */
        fun index(): Map<VirtualFile, Int> = HashMap<VirtualFile, Int>(files.size * 2).also { map -> files.forEachIndexed { i, file -> map[file] = i } }

        /** The entry of file [index] when [item] still has the same source, stamp and length, at [item]'s path. */
        fun reuse(index: Int, item: Listed): FileEntry? {
            if (fromDocument[index] != item.fromDocument || stamps[index] != item.stamp) return null
            val entry = entries[index]
            if (!item.fromDocument && entry.length != item.length) return null
            return entry.withPath(item.relPath)
        }

        companion object {
            fun of(generation: Long, listing: List<Listed>, entries: List<FileEntry>): CopyFingerprint = CopyFingerprint(
                generation,
                Array(listing.size) { listing[it].file },
                LongArray(listing.size) { listing[it].stamp },
                BooleanArray(listing.size) { listing[it].fromDocument },
                entries,
            )
        }
    }

    private class CopyState {
        /** Bumped when an edit below the copy is seen; a fingerprint is current while its generation matches. */
        val generation = AtomicLong()
        val mutex = Mutex()

        @Volatile
        var fingerprint: CopyFingerprint? = null
    }

    private data class OptionsState(val version: Long, val options: DriftOptions)

    /** What a name's result was computed from; a different value means the result is stale. */
    private data class NameInputs(val copies: List<RoleCopy>, val generations: List<Long>, val optionsVersion: Long, val settingsStamp: Long)

    private class NameResult(val inputs: NameInputs, val drift: RoleDrift?)

    private val states = ConcurrentHashMap<VirtualFile, CopyState>()
    private val results = ConcurrentHashMap<String, NameResult>()
    private val nameLocks = ConcurrentHashMap<String, Mutex>()
    private val lock = Any()
    private val queue = ArrayDeque<String>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val active = AtomicBoolean()
    private val workerStarted = AtomicBoolean()
    private val pendingDocumentCopies: MutableSet<VirtualFile> = ConcurrentHashMap.newKeySet()
    private var documentFlush: Job? = null
    private val tracker = SimpleModificationTracker()
    private val hashing = Dispatchers.IO.limitedParallelism(HASH_PARALLELISM)

    @Volatile
    private var optionsState = OptionsState(0, DriftOptions())

    @Volatile
    private var lastCatalog: RoleCatalogSnapshot? = null

    /** How long typing pauses before an unsaved edit is re-fingerprinted in the background. */
    @Volatile
    internal var documentDebounce: Duration = DOCUMENT_DEBOUNCE

    /** How sensitive files are fingerprinted; corpus tests on the real repo use [SensitiveContent.SIZE_ONLY]. */
    @Volatile
    internal var sensitiveContent: SensitiveContent = SensitiveContent.HASH

    /** Debug counters (files hashed, documents hashed, copies walked). */
    val counters: DriftCounters = DriftCounters()

    /** Bumps whenever a published result changes (the same moments as [RoleDriftListener.TOPIC]). */
    val modificationTracker: ModificationTracker get() = tracker

    /**
     * The comparison options. Setting different options makes every result stale; the background worker (when
     * started) recomputes them and publishes the names whose result changed.
     */
    var options: DriftOptions
        get() = optionsState.options
        set(value) {
            synchronized(lock) {
                if (value == optionsState.options) return
                optionsState = OptionsState(optionsState.version + 1, value)
            }
            wakeWorker()
        }

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(
            VirtualFileManager.VFS_CHANGES_BG,
            object : BulkFileListenerBackgroundable {
                override fun after(events: List<VFileEvent>) = onVfsEvents(events)
            },
        )
        connection.subscribe(AnsibleStructureListener.TOPIC, AnsibleStructureListener { wakeWorker() })
        connection.subscribe(
            AnsibilitySettingsListener.TOPIC,
            object : AnsibilitySettingsListener {
                override fun projectSettingsChanged(old: ProjectSettings, new: ProjectSettings) = wakeWorker()
            },
        )
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) = onDocumentChanged(event.document)
            },
            this,
        )
    }

    // ---------------------------------------------------------------- queries

    /**
     * The last computed drift of [name], or null while it was never computed. Never computes, never blocks: safe on
     * the EDT and inside read actions (tree renderers, Go to Related). The result may be older than the latest edit;
     * a fresh one follows through [RoleDriftListener.TOPIC] once the worker runs ([request]).
     */
    fun cached(name: String): RoleDrift? = results[name]?.drift

    /** The last computed drift of [copy], or null. Like [cached]. */
    fun cached(copy: RoleCopy): CopyDrift? = cached(copy.name)?.copyOf(copy.dir)

    /**
     * Whether [cached] of [name] reflects the current catalog, every edit seen so far and the current options (no
     * debounced document edit pending). Reads the catalog, so it may take a read lock; for tests and diagnostics.
     */
    @TestOnly
    internal fun isCurrent(name: String): Boolean {
        val catalog = RoleCatalog.getInstance(project).snapshot()
        if (pendingDocumentCopies.any { catalog.copyOf(it)?.name == name }) return false
        return results[name] != null && !isStale(name, catalog)
    }

    /**
     * Starts (or re-prioritises) the background computation: [names] first, in the given order (the visible rows),
     * then every other role name. Never blocks; safe on the EDT.
     */
    fun request(names: Collection<String> = emptyList()) {
        if (project.isDisposed) return
        active.set(true)
        enqueue(names)
        ensureWorker()
        wake.trySend(Unit)
    }

    /**
     * The current drift of [name], computed now if anything changed (only edited files are rehashed); null when no
     * root has a role of that name. Runs on a background dispatcher whatever the caller's context.
     */
    suspend fun drift(name: String): RoleDrift? = withContext(Dispatchers.Default) { computeName(name) }

    /** The current drift of every role name, in name order. */
    suspend fun driftAll(): List<RoleDrift> = withContext(Dispatchers.Default) {
        val names = catalog().names
        coroutineScope { names.map { name -> async(hashing) { computeName(name) } }.awaitAll() }.filterNotNull()
    }

    /** The current drift of [copy], or null when the catalog no longer has it. */
    suspend fun driftOf(copy: RoleCopy): CopyDrift? = drift(copy.name)?.copyOf(copy.dir)

    /**
     * The paths in which [second] differs from [first] ([DriftPaths.onlyInReference] lists the files only [first]
     * has), for "Compare with… another copy" and "Same file in other repos". Honours [options].
     */
    suspend fun compare(first: RoleCopy, second: RoleCopy): DriftPaths = withContext(Dispatchers.Default) {
        val options = optionsState.options
        DriftRules.compare(
            DriftRules.effective(fingerprint(first).entries, options),
            DriftRules.effective(fingerprint(second).entries, options),
        )
    }

    // ---------------------------------------------------------------- computation

    private suspend fun computeName(name: String): RoleDrift? = lockOf(name).withLock {
        val catalog = catalog()
        val copies = catalog.copies(name)
        if (copies.isEmpty()) {
            if (results.remove(name) != null) changed(name)
            return@withLock null
        }
        copies.forEach { stateOf(it.dir) }
        val options = optionsState
        val inputs = inputsOf(copies, options)
        val fingerprints = copies.map { fingerprint(it).entries }
        val referenceIndex = copies.indexOfFirst { it.isReference }.takeIf { it >= 0 }
        val evaluations = DriftRules.evaluate(fingerprints, referenceIndex, options.options)
        val copyDrifts = copies.zip(evaluations) { copy, e -> CopyDrift(copy, e.tier, e.paths, e.variant, e.fileCount) }
        val variants = copyDrifts.groupBy { it.variant }.toSortedMap().map { (index, list) -> RoleVariant(index, list.map { it.copy }) }
        val drift = RoleDrift(name, referenceIndex?.let(copies::get), copyDrifts, variants, options.options)
        val previous = results.put(name, NameResult(inputs, drift))
        if (previous?.drift != drift) changed(name)
        drift
    }

    /** The fingerprint of [copy]: a fresh walk, reusing every file hash whose stamp and length did not change. */
    private suspend fun fingerprint(copy: RoleCopy): CopyFingerprint {
        val state = stateOf(copy.dir)
        state.mutex.withLock {
            val generation = state.generation.get()
            val previous = state.fingerprint
            val previousIndex = previous?.index()
            val ignored = AnsibilityProjectSettings.getInstance(project).ignoredPathMatcher()
            val policy = sensitiveContent
            // Survives a restart of the read action (a write action cancels it), so no file is hashed twice.
            val resumed = HashMap<VirtualFile, Pair<Listed, FileEntry>>()
            val (listing, entries) = withContext(hashing) {
                readAction {
                    val listing = walk(copy.dir, ignored)
                    listing to listing.map { item ->
                        previousIndex?.get(item.file)?.let { previous.reuse(it, item) }
                            ?: resumed[item.file]?.takeIf { (seen, _) -> seen.sameSource(item) }?.second?.withPath(item.relPath)
                            ?: hash(item, policy).also { resumed[item.file] = item to it }
                    }
                }
            }
            counters.walked.incrementAndGet()
            return CopyFingerprint.of(generation, listing, entries).also { state.fingerprint = it }
        }
    }

    /** Every file of the role directory [dir] that takes part in drift. Call in a read action. */
    private fun walk(dir: VirtualFile, ignored: (VirtualFile) -> Boolean): List<Listed> {
        if (!dir.isValid || !dir.isDirectory) return emptyList()
        val documents = FileDocumentManager.getInstance()
        val result = ArrayList<Listed>()
        fun visit(current: VirtualFile, prefix: String) {
            for (child in current.children.orEmpty()) {
                ProgressManager.checkCanceled()
                val name = child.name
                if (child.isDirectory) {
                    if (DriftRules.isSkippedDirectory(name) || child.`is`(VFileProperty.SYMLINK) || ignored(child)) continue
                    visit(child, "$prefix$name/")
                } else if (!DriftRules.isSkippedFile(name) && !ignored(child)) {
                    // Paths repeat across the copies of a name: interned, they are stored once.
                    val relPath = "$prefix$name".intern()
                    val document = if (documents.isFileModified(child)) documents.getCachedDocument(child) else null
                    result += if (document != null) Listed(relPath, child, document, document.modificationStamp, -1)
                    else Listed(relPath, child, null, child.modificationStamp, child.length)
                }
            }
        }
        visit(dir, "")
        return result
    }

    /** Hashes one listed file. Call in a read action. */
    private fun hash(item: Listed, policy: SensitiveContent): FileEntry {
        val sensitiveName = DriftRules.isSensitivePath(item.relPath)
        val document = item.document
        return when {
            sensitiveName && policy == SensitiveContent.SIZE_ONLY -> {
                counters.sizeOnly.incrementAndGet()
                FileEntry.sizeOnly(item.relPath, item.file.length)
            }
            document != null -> {
                counters.documents.incrementAndGet()
                digest(item.relPath, encode(item.file, document), sensitiveName)
            }
            !item.file.isValid -> FileEntry.unreadable(item.relPath, item.length, sensitiveName)
            else -> try {
                val bytes = item.file.contentsToByteArray(false)
                counters.hashed.incrementAndGet()
                digest(item.relPath, bytes, sensitiveName)
            } catch (e: IOException) {
                LOG.debug("Cannot read ${item.file.path} for role drift (${e.javaClass.simpleName})")
                FileEntry.unreadable(item.relPath, item.length, sensitiveName)
            }
        }
    }

    private fun digest(relPath: String, bytes: ByteArray, sensitiveName: Boolean): FileEntry {
        val entry = FileEntry.of(relPath, bytes, sensitiveName)
        if (entry.isSensitive) {
            if (sensitiveName) counters.sensitiveReads.incrementAndGet() else counters.vaults.incrementAndGet()
            bytes.fill(0)
        }
        return entry
    }

    /** The bytes saving [document] would write: the file's line separator, charset and BOM. */
    private fun encode(file: VirtualFile, document: Document): ByteArray {
        val separator = FileDocumentManager.getInstance().getLineSeparator(file, project)
        val text = StringUtil.convertLineSeparators(document.immutableCharSequence.toString(), separator)
        val body = text.toByteArray(file.charset)
        val bom = file.bom
        return if (bom != null && bom.isNotEmpty()) bom + body else body
    }

    // ---------------------------------------------------------------- background worker

    private fun ensureWorker() {
        if (workerStarted.compareAndSet(false, true)) scope.launch(Dispatchers.Default) { runWorker() }
    }

    private suspend fun runWorker() {
        while (true) {
            val name = try {
                nextStaleName()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                null
            } catch (e: Exception) {
                LOG.warn("Role drift: cannot read the role catalog (${e.javaClass.name})")
                null
            }
            if (name == null) {
                wake.receive()
                continue
            }
            try {
                computeName(name)
            } catch (e: CancellationException) {
                // A cancelled step (not the service): try again shortly.
                currentCoroutineContext().ensureActive()
                delay(RETRY_DELAY)
            } catch (e: Exception) {
                LOG.warn("Role drift of '$name' failed (${e.javaClass.name})")
                recordFailure(name)
            }
        }
    }

    /** The next name to compute: requested names first, then (once started) any stale name of the catalog. */
    private suspend fun nextStaleName(): String? {
        val catalog = catalog()
        while (true) {
            val next = synchronized(lock) { queue.removeFirstOrNull() } ?: break
            if (isStale(next, catalog)) return next
        }
        if (!active.get()) return null
        return catalog.names.firstOrNull { isStale(it, catalog) }
    }

    private fun isStale(name: String, catalog: RoleCatalogSnapshot): Boolean {
        val copies = catalog.copies(name)
        if (copies.isEmpty()) return results.containsKey(name)
        val result = results[name] ?: return true
        return result.inputs != inputsOf(copies, optionsState)
    }

    /** Keeps the previous result of [name] but marks its inputs as tried, so a failing name is retried only after a change. */
    private fun recordFailure(name: String) {
        val copies = lastCatalog?.copies(name).orEmpty()
        results[name] = NameResult(inputsOf(copies, optionsState), results[name]?.drift)
    }

    private fun enqueue(names: Collection<String>) {
        if (names.isEmpty()) return
        synchronized(lock) {
            for (name in names.reversed()) {
                queue.remove(name)
                queue.addFirst(name)
            }
        }
    }

    /** Re-checks staleness in the background, if the worker was started. */
    private fun wakeWorker() {
        if (active.get()) wake.trySend(Unit)
    }

    // ---------------------------------------------------------------- invalidation

    private fun onVfsEvents(events: List<VFileEvent>) {
        val catalog = lastCatalog ?: return
        val dirty = LinkedHashSet<RoleCopy>()
        for (event in events) {
            for (path in pathsOf(event)) catalog.copyContainingPath(path)?.let(dirty::add)
        }
        if (dirty.isEmpty()) return
        val names = dirty.mapNotNull { markDirty(it.dir) }.distinct()
        if (active.get()) {
            enqueue(names)
            wake.trySend(Unit)
        }
    }

    private fun pathsOf(event: VFileEvent): List<String> = when (event) {
        is VFileMoveEvent -> listOf(event.oldPath, event.newPath)
        is VFilePropertyChangeEvent -> if (event.isRename) listOf(event.oldPath, event.newPath) else emptyList()
        is VFileCopyEvent -> listOf("${event.newParent.path}/${event.newChildName}")
        is VFileContentChangeEvent, is VFileCreateEvent, is VFileDeleteEvent -> listOf(event.path)
        else -> emptyList()
    }

    /** Called on the EDT for every document change: only remembers the owning copy and restarts the debounce. */
    private fun onDocumentChanged(document: Document) {
        val catalog = lastCatalog ?: return
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        val copy = catalog.copyContaining(file) ?: return
        pendingDocumentCopies += copy.dir
        synchronized(lock) {
            documentFlush?.cancel()
            documentFlush = scope.launch {
                delay(documentDebounce)
                flushDocuments()
            }
        }
    }

    private fun flushDocuments() {
        val dirs = pendingDocumentCopies.toList()
        pendingDocumentCopies.removeAll(dirs.toSet())
        val names = dirs.mapNotNull(::markDirty).distinct()
        if (names.isNotEmpty() && active.get()) {
            enqueue(names)
            wake.trySend(Unit)
        }
    }

    /** Marks the copy at [dir] dirty; returns its role name. */
    private fun markDirty(dir: VirtualFile): String? {
        states[dir]?.generation?.incrementAndGet()
        return lastCatalog?.copyOf(dir)?.name
    }

    // ---------------------------------------------------------------- state

    private suspend fun catalog(): RoleCatalogSnapshot {
        val snapshot = readAction { RoleCatalog.getInstance(project).snapshot() }
        val previous = lastCatalog
        lastCatalog = snapshot
        if (previous != null && previous !== snapshot) prune(snapshot)
        return snapshot
    }

    /** Drops the state of copies and names the catalog no longer has, publishing the removed names. */
    private fun prune(catalog: RoleCatalogSnapshot) {
        states.keys.removeIf { catalog.copyOf(it) == null }
        for (name in results.keys.toList()) {
            if (catalog.copies(name).isEmpty() && results.remove(name) != null) {
                nameLocks.remove(name)
                changed(name)
            }
        }
    }

    private fun inputsOf(copies: List<RoleCopy>, options: OptionsState): NameInputs = NameInputs(
        copies,
        copies.map { states[it.dir]?.generation?.get() ?: -1L },
        options.version,
        AnsibilityProjectSettings.getInstance(project).modificationTracker.modificationCount,
    )

    private fun stateOf(dir: VirtualFile): CopyState = states.computeIfAbsent(dir) { CopyState() }

    private fun lockOf(name: String): Mutex = nameLocks.computeIfAbsent(name) { Mutex() }

    private fun changed(name: String) {
        tracker.incModificationCount()
        if (!project.isDisposed) project.messageBus.syncPublisher(RoleDriftListener.TOPIC).driftChanged(name)
    }

    override fun dispose() {
        synchronized(lock) {
            documentFlush?.cancel()
            queue.clear()
        }
        states.clear()
        results.clear()
    }

    companion object {
        private val LOG = logger<RoleDriftService>()

        /** Typing pauses this long before the edited copy is re-fingerprinted (plan amendment R9, F9.5). */
        val DOCUMENT_DEBOUNCE: Duration = 500.milliseconds

        private val RETRY_DELAY: Duration = 100.milliseconds

        /** Role copies hashed in parallel by [driftAll] (the cold pass is I/O-bound). */
        private const val HASH_PARALLELISM = 4

        fun getInstance(project: Project): RoleDriftService = project.service()
    }
}
