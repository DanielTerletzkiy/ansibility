package de.terletzkiy.ansibility.vault.exclusion

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VFileProperty
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.NewVirtualFile
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.ID
import de.terletzkiy.ansibility.vault.VaultLog
import de.terletzkiy.ansibility.vault.monitor.SecretNotifier
import de.terletzkiy.ansibility.vault.ui.AnsibilityVaultUiBundle.message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.atomic.AtomicInteger

/**
 * Index data from before (plan amendment R30, D226; R30 review fixes, decision 2): a password file the IDE indexed while
 * it was not kept out (before R30, while the plugin was off, in Safe Mode or an older version, or in another project
 * that had its folder as content) keeps its word and trigram index data until it is indexed again. So whenever such
 * data can exist (at every project open, and for each content root added later: [PasswordFileReindexService]),
 * Ansibility walks the content roots and hands the files the entries keep out ([KeepOutRules.excludes]) that the
 * platform excludes **and** that still have word or trigram data to `FileBasedIndex.requestReindex`. For a file outside
 * the project content that drops its data and adds none (`PasswordFileExclusionTest` proves it). A clean project costs
 * a walk and no re-index.
 *
 * The walk covers the files the VFS knows below the content roots (`NewVirtualFile.iterInDbChildren`, as indexing
 * walks), in chunks of whole directories, each chunk its own short read action (a write action restarts one chunk,
 * never the whole walk). It enters symbolic links to folders (the platform indexes the files below them, one VFS file
 * per path), never a link into a folder above it or a link already followed on the way down (cycles). It skips ignored
 * names and folders excluded for other reasons. When it reaches [maxVisits] it stops, says so in the log
 * (`PASSWORD_FILES_WALK_CUT`) and the next project open or new content root walks again.
 *
 * Reads no file content (the guard reads 14 bytes of a named file with `java.nio`, never through the VFS). Local History
 * keeps earlier revisions of a file it tracked before: once per project with an excluded password file, a notification
 * says how to clear them (decision 7, [LocalHistoryNotice]).
 */
object PasswordFileReindex {
    /** How many VFS entries one walk looks at, at most (a huge project is walked up to here; the next start walks again). */
    @Volatile
    var maxVisits: Int = MAX_VISITS
        @TestOnly set

    /** Whether project opens and new content roots start the walk in tests (off: a light project outlives a test class). */
    @Volatile
    var automaticInTests: Boolean = false
        @TestOnly set

    /** True when project opens and new content roots start the walk ([PasswordFileReindexActivity], [PasswordFileRootListener]). */
    val automatic: Boolean get() = !ApplicationManager.getApplication().isUnitTestMode || automaticInTests

    /** The key a project's one-time notice is remembered by: its directory, or its location hash. */
    fun projectKey(project: Project): String = project.basePath ?: project.locationHash

    /**
     * The stale-data pass, now (decision 2: due whenever stale data can exist, which the callers decide): walks [roots]
     * (every content root of [project] when null) and re-indexes the excluded files that still have word or trigram
     * data. Returns how many files were handed to the platform. Waits for smart mode; background.
     */
    suspend fun runIfDue(project: Project, roots: List<VirtualFile>? = null): Int {
        val rules = IndexExclusionSettings.getInstance().rules()
        val start = smartReadAction(project) { roots?.filter { it.isValid } ?: ProjectRootManager.getInstance(project).contentRoots.toList() }
        val walk = ExcludedFileWalk(project, rules, start, maxVisits)
        while (!walk.isFinished && !walk.isCut) {
            val step = readAction { walk.step(CHUNK_VISITS) }
            walk.apply(step)
        }
        if (walk.isCut) VaultLog.event(VaultLog.Operation.STATUS, VaultLog.Event.PASSWORD_FILES_WALK_CUT, key = walk.visits.toString())
        val found = walk.found.toList()
        val stale = if (found.isEmpty()) emptyList() else smartReadAction(project) { found.filter { it.isValid && hasIndexData(project, it) } }
        requestReindex(stale)
        if (stale.isNotEmpty()) VaultLog.event(VaultLog.Operation.STATUS, VaultLog.Event.PASSWORD_FILES_REINDEXED, key = stale.size.toString())
        LocalHistoryNotice.showOnce(project, found)
        return stale.size
    }

    /** Asks the platform to index [files] again (for an excluded file: its old data goes, nothing new comes). */
    fun requestReindex(files: Collection<VirtualFile>) {
        val index = FileBasedIndex.getInstance()
        for (file in files) if (file.isValid) index.requestReindex(file)
    }

    /** True when [file] still has word or trigram index data (data from before it was kept out). Smart read action. */
    fun hasIndexData(project: Project, file: VirtualFile): Boolean {
        val index = FileBasedIndex.getInstance()
        return STALE_INDEXES.mapNotNull { ID.findByName<Any?, Any?>(it) }.any { index.getFileData(it, file, project).isNotEmpty() }
    }

    /**
     * The files below [project]'s content roots that the name [patterns] (with the exact paths of the list as it is now)
     * keep out and the platform excludes, in one go, as the stale-data pass walks. Read action.
     */
    fun excludedFiles(project: Project, patterns: List<String>): List<VirtualFile> {
        val rules = KeepOutRules(patterns, IndexExclusionSettings.getInstance().paths)
        val walk = ExcludedFileWalk(project, rules, ProjectRootManager.getInstance(project).contentRoots.toList(), maxVisits)
        while (!walk.isFinished && !walk.isCut) walk.apply(walk.step(CHUNK_VISITS))
        return walk.found.toList()
    }

    private const val MAX_VISITS = 1_000_000

    /** About how many VFS entries one read action of the walk looks at (whole directories). */
    private const val CHUNK_VISITS = 2_000

    /** The word index (`IdIndex`, internal API, so by name) and the trigram index (Find in Files, Search Everywhere). */
    private val STALE_INDEXES = listOf("IdIndex", "Trigram.Index")
}

/**
 * One walk of [PasswordFileReindex]: a queue of directories, worked off in [step]s (each a read action that changes
 * nothing, so a write action can restart it) whose results [apply] adds. Not thread-safe; one coroutine at a time.
 */
internal class ExcludedFileWalk(private val project: Project, private val rules: KeepOutRules, roots: Collection<VirtualFile>, private val budget: Int) {
    /** A directory to walk and the targets of the symbolic links followed on the way down to it (cycle guard). */
    class Dir(val file: VirtualFile, val links: List<VirtualFile>)

    /** What one step found: how many queued directories it took, the directories below them, the files, the entries seen. */
    class Step(val taken: Int, val dirs: List<Dir>, val files: List<VirtualFile>, val visits: Int)

    private val queue = ArrayDeque<Dir>()
    private val queued = HashSet<VirtualFile>()

    /** The excluded files the entries keep out, found so far. */
    val found = LinkedHashSet<VirtualFile>()

    /** The VFS entries looked at so far. */
    var visits: Int = 0
        private set

    init {
        for (root in roots) if (queued.add(root)) queue.addLast(Dir(root, emptyList()))
    }

    val isFinished: Boolean get() = queue.isEmpty()

    /** True when the budget is spent with directories left: the walk stopped early. */
    val isCut: Boolean get() = queue.isNotEmpty() && visits >= budget

    /** Walks whole directories from the queue until about [chunk] entries were seen or the budget is spent. Read action. */
    fun step(chunk: Int): Step {
        val fileIndex = ProjectFileIndex.getInstance(project)
        val types = FileTypeManager.getInstance()
        val dirs = ArrayList<Dir>()
        val files = ArrayList<VirtualFile>()
        val here = HashSet<VirtualFile>()
        var looked = 0
        var taken = 0
        for (dir in queue) {
            if (looked >= chunk || visits + looked >= budget) break
            taken++
            if (!dir.file.isValid) continue
            for (child in childrenOf(dir.file)) {
                ProgressManager.checkCanceled()
                looked++
                if (types.isFileIgnored(child)) continue
                if (child.isDirectory) {
                    val next = follow(dir, child, fileIndex) ?: continue
                    if (child !in queued && here.add(child)) dirs += next
                } else if (rules.excludes(child) && fileIndex.isExcluded(child)) {
                    files += child
                }
            }
        }
        return Step(taken, dirs, files, looked)
    }

    /** Takes the result of a [step] that completed. */
    fun apply(step: Step) {
        repeat(step.taken) { queue.removeFirst() }
        for (dir in step.dirs) if (queued.add(dir.file)) queue.addLast(dir)
        found += step.files
        visits += step.visits
    }

    /**
     * The directory [child] of [parent] to walk, or null: a folder excluded for another reason holds nothing of ours; a
     * symbolic link is followed unless it dangles, points to itself or a folder above it, or to a target already
     * followed on the way down.
     */
    private fun follow(parent: Dir, child: VirtualFile, fileIndex: ProjectFileIndex): Dir? {
        if (fileIndex.isExcluded(child)) return null
        if (!child.`is`(VFileProperty.SYMLINK)) return Dir(child, parent.links)
        if (VfsUtilCore.isInvalidLink(child)) return null
        val target = child.canonicalFile ?: return null
        if (target in parent.links) return null
        return Dir(child, parent.links + target)
    }

    private fun childrenOf(dir: VirtualFile): Iterable<VirtualFile> =
        if (dir is NewVirtualFile) dir.iterInDbChildren() else dir.children.orEmpty().asList()
}

/**
 * Starts [PasswordFileReindex.runIfDue] on this project's scope (decision 2): for every content root when the project
 * opens ([requestAll]), and for the content roots added later ([rootsChanged]). Requests are merged; one walk at a time.
 */
@Service(Service.Level.PROJECT)
class PasswordFileReindexService(private val project: Project, private val scope: CoroutineScope) {
    private val lock = Any()
    private val mutex = Mutex()
    private val running = AtomicInteger()
    private var everything = false
    private val pending = LinkedHashSet<String>()

    /** The content root URLs last seen, or null before the first look. */
    private var known: Set<String>? = null

    /** Walks every content root (the project opened); [roots] are its content root URLs now. */
    fun requestAll(roots: Set<String>) {
        synchronized(lock) {
            known = roots
            everything = true
        }
        kick()
    }

    /** Walks the content roots in [roots] that were not there at the last look (none at the first look). EDT or any thread. */
    fun rootsChanged(roots: Set<String>) {
        val added = synchronized(lock) {
            val before = known
            known = roots
            if (before == null) emptyList() else roots - before
        }
        if (added.isEmpty()) return
        synchronized(lock) { pending += added }
        kick()
    }

    /** For tests: true while no walk is waiting or running. */
    @TestOnly
    fun isIdle(): Boolean = running.get() == 0

    /** For tests: when set, a requested walk waits for it before it starts (so a test can look at the state before). */
    @TestOnly
    @Volatile
    var gateForTests: CompletableDeferred<Unit>? = null

    private fun kick() {
        running.incrementAndGet()
        scope.launch {
            try {
                // Let the platform start indexing a new content root first; the walk then waits for smart mode.
                delay(SETTLE_MILLIS)
                gateForTests?.await()
                mutex.withLock {
                    val (all, urls) = synchronized(lock) {
                        (everything to pending.toList()).also {
                            everything = false
                            pending.clear()
                        }
                    }
                    if (!all && urls.isEmpty()) return@withLock
                    val roots = if (all) null else readAction { urls.mapNotNull { VirtualFileManager.getInstance().findFileByUrl(it) } }
                    if (roots != null && roots.isEmpty()) return@withLock
                    try {
                        PasswordFileReindex.runIfDue(project, roots)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        VaultLog.failure(VaultLog.Operation.STATUS, e)
                    }
                }
            } finally {
                running.decrementAndGet()
            }
        }
    }

    companion object {
        private const val SETTLE_MILLIS = 1_000L

        fun getInstance(project: Project): PasswordFileReindexService = project.service()
    }
}

/** The stale-data pass when a project opens ([PasswordFileReindexService.requestAll]). */
class PasswordFileReindexActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!PasswordFileReindex.automatic) return
        val roots = readAction { ProjectRootManager.getInstance(project).contentRootUrls.toSet() }
        PasswordFileReindexService.getInstance(project).requestAll(roots)
    }
}

/** The stale-data pass for content roots added while the project is open ([PasswordFileReindexService.rootsChanged]). */
class PasswordFileRootListener(private val project: Project) : ModuleRootListener {
    override fun rootsChanged(event: ModuleRootEvent) {
        if (!PasswordFileReindex.automatic || project.isDisposed) return
        PasswordFileReindexService.getInstance(project).rootsChanged(ProjectRootManager.getInstance(project).contentRootUrls.toSet())
    }
}

/**
 * Decision 7 (D226): once per project in which an excluded password file exists, a notification that older Local
 * History revisions may still hold its plaintext, and that File › Invalidate Caches with "Clear Local History" removes
 * them. It clears nothing itself. File names only.
 */
internal object LocalHistoryNotice {
    private const val MAX_NAMES = 3

    fun showOnce(project: Project, files: Collection<VirtualFile>) {
        if (files.isEmpty() || project.isDisposed) return
        if (!IndexExclusionSettings.getInstance().markLocalHistoryNoticed(PasswordFileReindex.projectKey(project))) return
        val names = files.map { it.name }.distinct().sorted()
        val shown = names.take(MAX_NAMES).joinToString(", ") + if (names.size > MAX_NAMES) ", …" else ""
        NotificationGroupManager.getInstance().getNotificationGroup(SecretNotifier.WARNINGS_GROUP_ID)
            .createNotification(message("notification.title"), message("exclusion.history.message", shown, names.size), NotificationType.INFORMATION)
            .setDisplayId(DISPLAY_ID)
            .notify(project)
    }

    const val DISPLAY_ID: String = "ansibility.vault.localHistory"
}
