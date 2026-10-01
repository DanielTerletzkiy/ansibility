package de.terletzkiy.ansibility.workspace

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VFileProperty
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings

/** How much of one root's own files a scope contains (plan amendment R9, F9.1 "Coverage is shown, never hidden"). */
enum class RootCoverage {
    /** Every own file of the root is inside the scope. */
    FULL,

    /** Some own files are inside, some are not (a user scope narrower than the root, e.g. `prod-only`). */
    PARTIAL,

    /** No own file is inside. */
    NONE,
}

/**
 * The coverage of every non-detached root by one scope, in display order. Immutable.
 *
 * A root's **own files** are the files below its directory that belong to no other root: a nested playbook root's
 * files count for the nested root only, and detached worktrees never count. So `pelican` and `pelican › danger_zone/database`
 * are judged separately; a recursive pattern over `repos/pelican` covers both fully.
 */
class ScopeCoverage(coverage: Map<AnsibleRoot, RootCoverage>) {
    private val byRoot: Map<AnsibleRoot, RootCoverage> = LinkedHashMap(coverage)

    /** The roots that intersect the scope ([RootCoverage.FULL] or [RootCoverage.PARTIAL]), display order. */
    val roots: List<AnsibleRoot> = byRoot.filterValues { it != RootCoverage.NONE }.keys.toList()

    /** The roots only partly inside. */
    val partial: Set<AnsibleRoot> = byRoot.filterValues { it == RootCoverage.PARTIAL }.keys

    /** The roots entirely inside, display order. */
    val full: List<AnsibleRoot> = byRoot.filterValues { it == RootCoverage.FULL }.keys.toList()

    /** Every root that was judged, display order. */
    val judged: List<AnsibleRoot> get() = byRoot.keys.toList()

    /** The coverage of [root]; [RootCoverage.NONE] for roots that were not judged (detached ones). */
    fun of(root: AnsibleRoot): RootCoverage = byRoot[root] ?: RootCoverage.NONE

    override fun toString(): String = byRoot.entries.joinToString(prefix = "ScopeCoverage(", postfix = ")") { "${it.key.displayName}=${it.value}" }

    companion object {
        /** Coverage by directory: every root at or below one of [dirs] is full, every other root none. No walk. */
        fun ofDirectories(roots: List<AnsibleRoot>, dirs: Collection<VirtualFile>): ScopeCoverage =
            ScopeCoverage(roots.associateWith { root -> if (dirs.any { isAncestorOrSelf(it, root.dir) }) RootCoverage.FULL else RootCoverage.NONE })

        /** Every root full (All roots, or a deleted named scope that fell back to All). */
        fun all(roots: List<AnsibleRoot>): ScopeCoverage = ScopeCoverage(roots.associateWith { RootCoverage.FULL })

        internal fun isAncestorOrSelf(ancestor: VirtualFile, file: VirtualFile): Boolean {
            var current: VirtualFile? = file
            while (current != null) {
                if (current == ancestor) return true
                current = current.parent
            }
            return false
        }
    }
}

/**
 * Computes [ScopeCoverage] of a file predicate by walking each root's own files (VFS only, no PSI, no content).
 *
 * The walk leaves out other roots' directories (nested and detached roots), [AnsibleLayout.SKIPPED_DIRS], files and
 * directories excluded from the project, the user's ignored paths and symlinked directories. It stops a root as soon
 * as it has seen one file inside and one outside ([RootCoverage.PARTIAL]); a root without own files is judged by its
 * directory. At most [MAX_FILES_PER_ROOT] files are tested per root. Needs a read lock; checks for cancellation per file.
 */
object ScopeCoverageWalker {
    /** Upper bound of tested files per root; beyond it the root is judged on the files seen. */
    const val MAX_FILES_PER_ROOT: Int = 100_000

    /**
     * The coverage of [roots] (non-detached, display order) by [contains]. [allRoots] are every root of the workspace,
     * detached ones included: their directories are never entered.
     */
    fun compute(project: Project, roots: List<AnsibleRoot>, allRoots: List<AnsibleRoot>, contains: (VirtualFile) -> Boolean): ScopeCoverage {
        val fileIndex = ProjectFileIndex.getInstance(project)
        val ignored = AnsibilityProjectSettings.getInstance(project).ignoredPathMatcher()
        val rootDirs = allRoots.mapTo(HashSet()) { it.dir }
        val result = LinkedHashMap<AnsibleRoot, RootCoverage>()
        for (root in roots) {
            result[root] = judge(root.dir, rootDirs, contains) { dir -> fileIndex.isExcluded(dir) || ignored(dir) }
        }
        return ScopeCoverage(result)
    }

    private fun judge(
        rootDir: VirtualFile,
        rootDirs: Set<VirtualFile>,
        contains: (VirtualFile) -> Boolean,
        isSkipped: (VirtualFile) -> Boolean,
    ): RootCoverage {
        if (!rootDir.isValid) return RootCoverage.NONE
        var inside = 0
        var outside = 0
        val pending = ArrayDeque<VirtualFile>()
        pending.addLast(rootDir)
        walk@ while (pending.isNotEmpty()) {
            val dir = pending.removeLast()
            for (child in dir.children) {
                ProgressManager.checkCanceled()
                if (!child.isValid) continue
                if (child.isDirectory) {
                    val enter = child !in rootDirs && child.name !in AnsibleLayout.SKIPPED_DIRS &&
                        !child.`is`(VFileProperty.SYMLINK) && !isSkipped(child)
                    if (enter) pending.addLast(child)
                    continue
                }
                if (isSkipped(child)) continue
                if (contains(child)) inside++ else outside++
                if ((inside > 0 && outside > 0) || inside + outside >= MAX_FILES_PER_ROOT) break@walk
            }
        }
        return when {
            inside > 0 && outside > 0 -> RootCoverage.PARTIAL
            inside > 0 -> RootCoverage.FULL
            outside > 0 -> RootCoverage.NONE
            contains(rootDir) -> RootCoverage.FULL
            else -> RootCoverage.NONE
        }
    }
}
