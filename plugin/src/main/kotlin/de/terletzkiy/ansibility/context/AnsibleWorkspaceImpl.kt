package de.terletzkiy.ansibility.context

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListenerBackgroundable
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.ProjectLayoutService
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.PathSettings
import de.terletzkiy.ansibility.settings.layout.LayoutSettings
import java.util.concurrent.ConcurrentHashMap

/**
 * The project's [AnsibleWorkspace]: root detection over the project content and cached query-time
 * classification of files (plan A.5, A.8, A.9).
 *
 * - Roots come from one [RootDetector] walk over the content roots and the project dir, cached until
 *   [structureTracker], the project roots or the project settings ([AnsibilityProjectSettings.modificationTracker])
 *   change.
 * - [contextOf] results are cached per file with the same stamp; results that needed the content probe also
 *   depend on the file's modification stamp.
 * - The path settings ([PathSettings]) are read at query time: files matching an ignored-path glob have no context
 *   (and the root walk does not enter such directories), and [PathSettings.detachedRule] off makes no root detached.
 *   Molecule files are always classified (plan amendment R20, D150); an ignored-path glob for `molecule` folders is
 *   the way to have them skipped.
 * - A [BulkFileListenerBackgroundable] on `VFS_CHANGES_BG` bumps [structureTracker] for the changes that
 *   [StructureChangeFilter] considers structural and then publishes [AnsibleStructureListener.TOPIC].
 *
 * All methods may be called from any thread; they take a read lock themselves when the caller holds none.
 */
class AnsibleWorkspaceImpl(private val project: Project) : AnsibleWorkspace, Disposable {
    private val tracker = SimpleModificationTracker()
    private val classifier = AnsibleFileClassifier(
        layoutOf = { root -> ProjectLayoutService.getInstance(project).layout(root) },
    )
    private val contexts = ConcurrentHashMap<VirtualFile, CachedContext>()

    @Volatile
    private var contextsStamp = -1L

    @Volatile
    private var scannedBasePaths: List<String> = emptyList()

    private val scanValue: CachedValue<RootScan> = CachedValuesManager.getManager(project).createCachedValue(
        { CachedValueProvider.Result.create(scan(), tracker, ProjectRootManager.getInstance(project), settings().modificationTracker) },
        false,
    )

    private class CachedContext(val stamp: Long, val contentStamp: Long, val context: FileContext?)

    init {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES_BG,
            object : BulkFileListenerBackgroundable {
                override fun after(events: List<VFileEvent>) {
                    val filter = StructureChangeFilter(scannedBasePaths)
                    if (events.any(filter::isRelevant)) structureChanged()
                }
            },
        )
    }

    override val structureTracker: ModificationTracker
        get() = tracker

    override fun roots(): List<AnsibleRoot> = currentScan().roots

    override fun rootFor(file: VirtualFile): AnsibleRoot? = currentScan().rootFor(file)

    override fun contextOf(file: VirtualFile): FileContext? {
        if (!file.isValid || file.isDirectory) return null
        val stamp = stamp()
        if (stamp != contextsStamp) {
            contexts.clear()
            contextsStamp = stamp
        }
        contexts[file]?.let { cached ->
            if (cached.stamp == stamp && (cached.contentStamp == NO_PROBE || cached.contentStamp == file.modificationStamp)) {
                return cached.context
            }
        }
        val root = rootFor(file)?.takeUnless { settings().isIgnored(file) }
        val result = if (root == null) null else readLocked { classifier.classify(file, root) }
        val context = result?.context
        if (contexts.size >= MAX_CACHED_CONTEXTS) contexts.clear()
        contexts[file] = CachedContext(stamp, if (result?.usedContentProbe == true) file.modificationStamp else NO_PROBE, context)
        return context
    }

    /** Distinct detached worktrees inside the project (X01), sorted by path. */
    fun detachedWorktrees(): List<DetachedWorktree> = currentScan().worktrees

    /** The worktree a detached [root] belongs to, or null for a normal root. */
    fun worktreeOf(root: AnsibleRoot): DetachedWorktree? = currentScan().worktreeOf(root)

    /** The parsed `ansible.cfg` of a PROJECT root, or of a NESTED_PLAYBOOK root's parent. */
    fun configOf(root: AnsibleRoot): AnsibleCfg? = currentScan().configs[root.parentDir ?: root.dir]

    /** Bumps [structureTracker], drops cached contexts and notifies [AnsibleStructureListener]s. */
    override fun refreshStructure() = structureChanged()

    fun structureChanged() {
        tracker.incModificationCount()
        contexts.clear()
        if (!project.isDisposed) project.messageBus.syncPublisher(AnsibleStructureListener.TOPIC).structureChanged()
    }

    override fun dispose() {
        contexts.clear()
    }

    private fun stamp(): Long =
        tracker.modificationCount + ProjectRootManager.getInstance(project).modificationCount + settings().modificationTracker.modificationCount

    private fun settings(): AnsibilityProjectSettings = AnsibilityProjectSettings.getInstance(project)

    private fun currentScan(): RootScan = if (project.isDisposed) RootScan.EMPTY else readLocked { scanValue.value }

    private fun scan(): RootScan {
        val bases = scanBases()
        scannedBasePaths = bases.map { it.path }
        val fileIndex = ProjectFileIndex.getInstance(project)
        val settings = settings()
        val isIgnored = settings.ignoredPathMatcher()
        return RootDetector(
            isExcluded = { dir -> fileIndex.isExcluded(dir) || isIgnored(dir) },
            detachedRule = settings.settings.paths.detachedRule,
            rolesPathOverride = { dir -> LayoutSettings.getInstance(project).of(dir).override.rolesPath },
        ).detect(bases)
    }

    /** Content roots of all modules, plus the project dir when it is not among them. */
    private fun scanBases(): List<VirtualFile> =
        (ProjectRootManager.getInstance(project).contentRoots.toList() + listOfNotNull(project.guessProjectDir()))
            .filter { it.isValid && it.isDirectory }
            .distinct()

    private fun <T> readLocked(action: () -> T): T =
        if (ApplicationManager.getApplication().isReadAccessAllowed) action() else runReadActionBlocking(action)

    companion object {
        private const val NO_PROBE = -1L
        private const val MAX_CACHED_CONTEXTS = 100_000

        fun getInstance(project: Project): AnsibleWorkspaceImpl? = AnsibleWorkspace.getInstance(project) as? AnsibleWorkspaceImpl
    }
}
