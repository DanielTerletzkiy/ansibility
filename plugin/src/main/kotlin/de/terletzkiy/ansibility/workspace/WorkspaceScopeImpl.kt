package de.terletzkiy.ansibility.workspace

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.psi.search.scope.packageSet.NamedScope
import com.intellij.psi.search.scope.packageSet.NamedScopesHolder
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.ScopeChoice
import de.terletzkiy.ansibility.api.WorkspaceScope
import org.jetbrains.annotations.Nls

/** Which files a [WorkspaceScopeImpl] contains, besides the rule that a file must lie in a non-detached root. */
internal sealed interface ScopeMembership {
    /** Every file of a non-detached root. */
    data object Everything : ScopeMembership

    /** Files at or below one of [dirs] (chosen roots, the current file's root); nested roots follow their parent. */
    data class Directories(val dirs: List<VirtualFile>) : ScopeMembership

    /** Files the named scope's package set contains, evaluated with its own holder. */
    class Named(val scope: NamedScope) : ScopeMembership

    /** No file (no root chosen, no Ansible file selected yet). */
    data object Nothing : ScopeMembership
}

/**
 * One evaluation of the stored [ScopeChoice] (plan amendment R9, F9.1): immutable, built by
 * [WorkspaceScopeServiceImpl.current] and valid until its [WorkspaceScopeServiceImpl.modificationTracker] moves.
 *
 * - [contains] is live and cheap: the file must lie in a non-detached root (detached worktrees never join a scope,
 *   DEV.md rule 6) and, for a named scope, its package set must contain it.
 * - [roots] and [partial] come from the coverage. For All roots, Current file's root and Choose roots… they follow
 *   from directories alone; for a named scope they need a walk over the roots' own files ([ScopeCoverageWalker]),
 *   which the service caches and computes ahead in the background. The first access to a named scope's [roots]
 *   before that finished walks on the calling thread (tens of milliseconds on the target repo), so call it from a
 *   background thread, or use [coverageIfComputed].
 * - It narrows lists only: nothing that resolves, completes, documents or inspects reads it.
 */
class WorkspaceScopeImpl internal constructor(
    private val project: Project,
    override val choice: ScopeChoice,
    /** The named scope of a [ScopeChoice.Named] choice; null for other choices and for a deleted scope. */
    val namedScope: NamedScope?,
    /** The holder [namedScope] was found in (shared `DependencyValidationManager` or local `NamedScopeManager`). */
    val holder: NamedScopesHolder?,
    private val membership: ScopeMembership,
    private val coverageSource: CoverageSource,
    /** Every non-detached role library (`golden`), display order, whether inside the scope or not: [roots] tells. */
    override val references: List<AnsibleRoot>,
    @Nls private val fixedProblem: String?,
    /** Short label of the choice, as the selector shows it ("All roots", "falcon", "falcon (deleted)", "falcon + heron"). */
    @Nls val label: String,
    /** The roots a [ScopeChoice.Roots] or [ScopeChoice.CurrentFileRoot] choice resolved to (before nested roots join). */
    val chosenRoots: List<AnsibleRoot>,
    /** The service stamp this scope was built for. */
    internal val stamp: Long,
) : WorkspaceScope {

    /** How the coverage is obtained: already known, or computed (and cached by the service) on first need. */
    internal interface CoverageSource {
        /** The coverage when known without a walk, else null. */
        fun cached(): ScopeCoverage?

        /** The coverage, walking when it is not known yet. */
        fun compute(): ScopeCoverage
    }

    private val coverage: ScopeCoverage by lazy(LazyThreadSafetyMode.PUBLICATION) { coverageSource.cached() ?: readLocked { coverageSource.compute() } }

    private val filter: GlobalSearchScope? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        (membership as? ScopeMembership.Named)?.let { GlobalSearchScopesCore.filterScope(project, it.scope) }
    }

    /** The coverage when it is known without walking, else null (for UI code on the EDT). */
    fun coverageIfComputed(): ScopeCoverage? = coverageSource.cached()

    /** The coverage; may walk the roots on the calling thread (see the class comment). */
    fun coverage(): ScopeCoverage = coverage

    override val roots: List<AnsibleRoot> get() = coverage.roots

    override val partial: Set<AnsibleRoot> get() = coverage.partial

    @get:Nls
    override val problem: String?
        get() = fixedProblem ?: matchesNothing(coverage)

    /** [problem] without walking: null for a named scope whose coverage is not known yet. */
    @Nls
    fun problemIfComputed(): String? = fixedProblem ?: coverageSource.cached()?.let(::matchesNothing)

    override fun contains(file: VirtualFile): Boolean {
        if (!file.isValid || project.isDisposed) return false
        return readLocked {
            val root = AnsibleWorkspace.getInstance(project).rootFor(file)
            root != null && !root.detached && admits(file)
        }
    }

    /** The membership rule alone, without the root check ([ScopeCoverageWalker] already walks own files only). */
    internal fun admits(file: VirtualFile): Boolean = when (val rule = membership) {
        ScopeMembership.Everything -> true
        ScopeMembership.Nothing -> false
        is ScopeMembership.Directories -> rule.dirs.any { ScopeCoverage.isAncestorOrSelf(it, file) }
        is ScopeMembership.Named -> filter?.contains(file) == true
    }

    override val searchScope: GlobalSearchScope by lazy(LazyThreadSafetyMode.PUBLICATION) { WorkspaceSearchScope(project, this) }

    override fun toString(): String = "WorkspaceScope($choice, $label)"

    @Nls
    private fun matchesNothing(coverage: ScopeCoverage): String? =
        if (namedScope != null && coverage.roots.isEmpty()) AnsibilityScopeBundle.message("scope.problem.matchesNothing", label) else null

    private fun <T> readLocked(action: () -> T): T =
        if (ApplicationManager.getApplication().isReadAccessAllowed) action() else runReadActionBlocking(action)
}

/**
 * [WorkspaceScope.searchScope]: the workspace scope as a [GlobalSearchScope] over project content, for cross-root
 * enumeration (F9.8, F9.9) only, never for resolution.
 */
private class WorkspaceSearchScope(project: Project, private val scope: WorkspaceScopeImpl) : GlobalSearchScope(project) {
    override fun contains(file: VirtualFile): Boolean = scope.contains(file)

    override fun isSearchInModuleContent(aModule: Module): Boolean = true

    override fun isSearchInLibraries(): Boolean = false

    @Nls
    override fun getDisplayName(): String = AnsibilityScopeBundle.message("searchScope.name", scope.label)

    override fun toString(): String = "WorkspaceSearchScope(${scope.choice})"
}
