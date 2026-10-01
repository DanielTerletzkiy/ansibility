package de.terletzkiy.ansibility.runtime

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.schema.DocSnapshot
import java.lang.ref.SoftReference
import java.util.concurrent.atomic.AtomicReferenceArray

/** The documentation lines shipped with the plugin (`plugin/src/main/resources/ansible-data`, plan A.11). */
enum class BundledLine(val resource: String) {
    /** ansible-core 2.18.8 with the collections pinned by the target repo's Docker images. */
    PINNED("/ansible-data/core-2.18.8.json.gz"),

    /** The latest ansible-core line with the Ansible package's collections. */
    LATEST("/ansible-data/core-latest.json.gz"),
    ;

    /** The line consulted last, after the target's line and the local install. */
    val other: BundledLine get() = if (this == PINNED) LATEST else PINNED
}

/**
 * Application service holding the bundled documentation snapshots. Each line is parsed on first use (0.1–0.3 s
 * and about 10 MB for 750 modules) and kept by a soft reference, so memory pressure may drop it and a later call
 * parses it again. [warmUp] parses them ahead of time on a background thread.
 */
@Service(Service.Level.APP)
class DocSnapshotStore {
    private val cache = AtomicReferenceArray<SoftReference<DocSnapshot>?>(BundledLine.entries.size)
    private val locks = BundledLine.entries.map { Any() }

    /** The snapshot of [line], parsing it when it is not cached. Thread-safe; concurrent callers share one parse. */
    fun snapshot(line: BundledLine): DocSnapshot {
        cache.get(line.ordinal)?.get()?.let { return it }
        synchronized(locks[line.ordinal]) {
            cache.get(line.ordinal)?.get()?.let { return it }
            val started = System.nanoTime()
            val loaded = load(line)
            LOG.info("Loaded ${line.resource} (${loaded.modules.size} modules) in ${(System.nanoTime() - started) / 1_000_000} ms")
            cache.set(line.ordinal, SoftReference(loaded))
            return loaded
        }
    }

    /** The snapshot that serves [target] (see [lineFor]). */
    fun snapshotFor(target: CoreVersion?): DocSnapshot = snapshot(lineFor(target))

    /** True when [line] is parsed and still cached. */
    fun isLoaded(line: BundledLine): Boolean = cache.get(line.ordinal)?.get() != null

    /** Parses [lines] now unless they are cached. Call from a background thread. */
    fun warmUp(lines: Collection<BundledLine> = BundledLine.entries) {
        for (line in lines) snapshot(line)
    }

    private fun load(line: BundledLine): DocSnapshot {
        val stream = DocSnapshotStore::class.java.getResourceAsStream(line.resource)
            ?: error("Bundled documentation snapshot ${line.resource} is missing from the plugin")
        return DocSnapshot.load(stream)
    }

    companion object {
        private val LOG = logger<DocSnapshotStore>()

        /** The first core line served by [BundledLine.LATEST]. */
        val LATEST_FROM: CoreVersion = CoreVersion(2, 19)

        fun getInstance(): DocSnapshotStore = service()

        /** Which bundled line serves a target: 2.18.x (and older) → pinned; 2.19 and later → latest; unknown → pinned. */
        fun lineFor(target: CoreVersion?): BundledLine =
            if (target != null && target >= LATEST_FROM) BundledLine.LATEST else BundledLine.PINNED
    }
}
