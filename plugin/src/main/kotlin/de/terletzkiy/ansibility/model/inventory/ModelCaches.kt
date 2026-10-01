package de.terletzkiy.ansibility.model.inventory

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.psi.util.PsiModificationTracker
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The shared state of the project's [ModelCache]s (plan amendment R7/R8, A.9; DEV.md rule 9): the stamps entries are
 * validated against and the counters of every cache.
 *
 * - [layout] is what every model value depends on besides its own files: the Ansible structure, the [fileTree], the
 *   project roots, the Ansibility settings and the target versions (the task model reads them). It changes rarely:
 *   when a file or directory is created, deleted, moved or renamed, a structural file is saved, or a setting changes.
 * - [epoch] additionally moves on every PSI and VFS change in the project. An entry checked at the current epoch is
 *   current without looking at its inputs again, so lookups between edits cost one comparison.
 * - [snapshot] gives the counters of every cache, by name, for the counter-based budgets (Testing §5: typing in a role
 *   task file invalidates 0 inventory views; switching env, host or play recomputes 0 model caches).
 *
 * The selection is never an input of a model cache: switching env, host or play changes no stamp here.
 */
@Service(Service.Level.PROJECT)
class ModelCaches(private val project: Project) : Disposable {
    private val fileTreeCounter = SimpleModificationTracker()
    private val stats = CopyOnWriteArrayList<ModelCacheStats>()

    /**
     * Bumped by every VFS change of the file tree below the project content: a file or directory created, deleted,
     * moved, copied or renamed outside tool directories such as `.git/` ([AnsibleLayout.SKIPPED_DIRS]). Content changes
     * are not tree changes. The listener is synchronous, so the stamp moves before any later read action starts.
     */
    val fileTree: ModificationTracker = fileTreeCounter

    private val structure: ModificationTracker by lazy { AnsibleWorkspace.getInstance(project).structureTracker }
    private val settings: ModificationTracker by lazy { AnsibilityProjectSettings.getInstance(project).modificationTracker }
    private val targets: ModificationTracker by lazy { TargetVersionDetector.getInstance(project).modificationTracker }
    private val psi: PsiModificationTracker by lazy { PsiModificationTracker.getInstance(project) }

    init {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (project.isDisposed) return
                    val bases = contentBases()
                    if (events.any { changesFileTree(it, bases) }) fileTreeCounter.incModificationCount()
                }
            },
        )
    }

    override fun dispose() {
        stats.clear()
    }

    /**
     * The layout stamp: the sum of the structure, file-tree, project-root, settings and target-version counters. They
     * only grow, so the sum changes whenever one of them does.
     */
    fun layout(): Long =
        structure.modificationCount +
            fileTreeCounter.modificationCount +
            ProjectRootManager.getInstance(project).modificationCount +
            settings.modificationCount +
            targets.modificationCount

    /** [layout] plus the project's PSI and VFS modification counts: unchanged epoch, unchanged model inputs. */
    fun epoch(): Long = layout() + psi.modificationCount + VirtualFileManager.getInstance().modificationCount

    /** Registers the counters of a new cache. */
    internal fun register(name: String, kind: ModelCacheKind): ModelCacheStats = ModelCacheStats(name, kind).also { stats += it }

    /** The counters of every registered cache. */
    fun stats(): List<ModelCacheStats> = stats.toList()

    /** The current counters by cache name (caches of one name are summed). */
    fun snapshot(): Snapshot {
        val counts = LinkedHashMap<String, Counts>()
        for (cache in stats) {
            val previous = counts[cache.name]
            counts[cache.name] = Counts(
                cache.kind,
                cache.computations + (previous?.computations ?: 0),
                cache.invalidations + (previous?.invalidations ?: 0),
                cache.hits + (previous?.hits ?: 0),
            )
        }
        return Snapshot(counts)
    }

    /** Counters of one cache at one moment. */
    data class Counts(val kind: ModelCacheKind, val computations: Long, val invalidations: Long, val hits: Long)

    /** Counters of every cache at one moment ([snapshot]). */
    class Snapshot(val counts: Map<String, Counts>) {
        /** The computations since [before] per cache of [kind], only caches that computed something. */
        fun computationsSince(before: Snapshot, kind: ModelCacheKind = ModelCacheKind.MODEL): Map<String, Long> =
            delta(before, kind) { it.computations }

        /** The invalidations since [before] per cache of [kind], only caches that invalidated something. */
        fun invalidationsSince(before: Snapshot, kind: ModelCacheKind = ModelCacheKind.MODEL): Map<String, Long> =
            delta(before, kind) { it.invalidations }

        /** The computations of the cache [name] since [before]. */
        fun computationsOf(name: String, before: Snapshot): Long =
            (counts[name]?.computations ?: 0) - (before.counts[name]?.computations ?: 0)

        private fun delta(before: Snapshot, kind: ModelCacheKind, value: (Counts) -> Long): Map<String, Long> =
            counts.filterValues { it.kind == kind }
                .mapValues { (name, now) -> value(now) - (before.counts[name]?.let(value) ?: 0) }
                .filterValues { it != 0L }

        override fun toString(): String = counts.entries.joinToString(prefix = "Snapshot(", postfix = ")") { (name, c) -> "$name=${c.computations}" }
    }

    /** The paths of the project's content roots and of the project dir. */
    private fun contentBases(): List<String> =
        (ProjectRootManager.getInstance(project).contentRoots.map { it.path } + listOfNotNull(project.basePath)).distinct()

    companion object {
        fun getInstance(project: Project): ModelCaches = project.service()

        /**
         * Whether [event] changes the file tree below [bases]: a file or directory created, deleted, moved, copied or
         * renamed (a new top-level playbook, a role directory, a `vars_files` target). Content changes are not tree
         * changes, and nothing inside [AnsibleLayout.SKIPPED_DIRS] (`.git/`, `node_modules/` …) counts.
         */
        internal fun changesFileTree(event: VFileEvent, bases: List<String>): Boolean = when (event) {
            is VFileContentChangeEvent -> false
            is VFileMoveEvent -> inContent(event.oldPath, bases) || inContent(event.newPath, bases)
            is VFileCopyEvent -> inContent("${event.newParent.path}/${event.newChildName}", bases)
            is VFilePropertyChangeEvent -> event.isRename && (inContent(event.oldPath, bases) || inContent(event.newPath, bases))
            else -> inContent(event.path, bases)
        }

        /** Whether [path] lies below one of [bases] and outside the tool directories. */
        private fun inContent(path: String, bases: List<String>): Boolean {
            val base = bases.firstOrNull { path == it || path.startsWith("$it/") } ?: return false
            return path.substring(base.length).split('/').none { it in AnsibleLayout.SKIPPED_DIRS }
        }
    }
}
