package de.terletzkiy.ansibility.workspace

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.packageDependencies.DependencyValidationManager
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.psi.search.scope.packageSet.NamedScope
import com.intellij.psi.search.scope.packageSet.NamedScopesHolder
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.api.ScopeChoice
import de.terletzkiy.ansibility.api.WorkspaceScope
import de.terletzkiy.ansibility.api.WorkspaceScopeService
import de.terletzkiy.ansibility.context.AnsibleStructureListener
import de.terletzkiy.ansibility.model.role.GoldenRoots
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.AnsibilitySettingsListener
import de.terletzkiy.ansibility.settings.AnsibilityWorkspaceState
import de.terletzkiy.ansibility.settings.GoldenRoot
import de.terletzkiy.ansibility.settings.ProjectSettings
import de.terletzkiy.ansibility.settings.RootKeys
import de.terletzkiy.ansibility.settings.WorkspaceState
import de.terletzkiy.ansibility.toolwindow.model.WorkspaceSnapshotBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ConcurrentHashMap

/** Where a scope listed by the selector comes from (plan amendment R9, F9.1). */
enum class ScopeOrigin {
    /** A shared scope of `DependencyValidationManager` (the XML files in `.idea/scopes`). */
    SHARED,

    /** A local scope of `NamedScopeManager` (`workspace.xml`). */
    LOCAL,

    /** A predefined, file-level scope (Project Files, Open Files, Changed Files …; D47). */
    PREDEFINED,
}

/** One named scope of the selector with its coverage, or null coverage while it is still being computed. */
data class ScopeEntry(val scope: NamedScope, val holder: NamedScopesHolder, val origin: ScopeOrigin, val coverage: ScopeCoverage?) {
    val scopeId: String get() = scope.scopeId
}

/**
 * What the scope selector lists (F9.1 "Shows"): the user scopes of both holders sorted by the display order of the
 * first root they cover (scopes that cover no root last, scopes still being computed keep their holder order), the
 * predefined scopes, and the roots no user scope covers ([uncovered], null until every user scope's coverage is known).
 */
data class ScopeCatalog(
    val roots: List<AnsibleRoot>,
    val userScopes: List<ScopeEntry>,
    val predefinedScopes: List<ScopeEntry>,
    val uncovered: List<AnsibleRoot>?,
)

/** A root as "Choose roots…" offers it: its `settings.RootKeys` key, label and, for nested roots, the parent's label. */
data class RootOption(val key: String, @Nls val label: String, @Nls val nestedIn: String?)

/**
 * The project's [WorkspaceScopeService] (plan amendment R9, F9.1 and A.15; WU WS1).
 *
 * - **Choice:** stored per user as `WorkspaceState.scope` ([ScopeChoices]), written through
 *   [AnsibilityWorkspaceState.update]; a reload of `workspace.xml` is picked up through [AnsibilitySettingsListener].
 * - **Named scopes:** both holders ([NamedScopesHolder.getAllNamedScopeHolders]: the local `NamedScopeManager` and the
 *   shared `DependencyValidationManager`), plus the predefined scopes. A [NamedScopesHolder.ScopeListener] on each
 *   holder bumps [modificationTracker] and drops the coverage cache. A stored scope that disappears falls back to
 *   All roots with the label "falcon (deleted)" until the user picks again; the stored choice is kept.
 * - **Current file's root:** the root of the selected editor's file, followed through
 *   [FileEditorManagerListener.selectionChanged] (and re-read after structure changes) on a background coroutine.
 *   The tracker moves only when that root, or whether an Ansible file is selected at all, changes; with no Ansible
 *   file selected the last root is kept.
 * - **Coverage** of named scopes ([ScopeCoverageWalker]) is cached per scope, keyed by the holder stamp, the Ansible
 *   structure, the project roots and the project settings. It is computed ahead in the background for the current
 *   named scope and, once the selector was opened, for every listed scope. Dynamic predefined scopes (Open Files,
 *   Changed Files) are re-evaluated on [refresh] (tool-window activation and its Refresh button).
 * - [modificationTracker] = choice + editor root (under Current file's root) + holders + refreshes + structure +
 *   project roots + project settings (also the drift settings: the golden root decides [WorkspaceScope.references],
 *   plan amendment R24); [WorkspaceScopeListener.TOPIC] is published after every move (project roots
 *   through [ModuleRootListener], project settings through [AnsibilitySettingsListener.projectSettingsChanged]).
 *
 * VFS-only and DumbAware; [current] is cheap from any thread. It narrows lists only, never resolution: no resolver,
 * completion, documentation or inspection reads it (R9 rule; DEV.md rule 6 is unchanged).
 */
class WorkspaceScopeServiceImpl(private val project: Project, private val cs: CoroutineScope) : WorkspaceScopeService, Disposable {
    /** What the selected editor shows, as far as the scope cares. */
    private enum class EditorStatus { NO_FILE, OTHER_FILE, DETACHED_FILE, ANSIBLE_FILE }

    /** The last root of a selected Ansible file (kept while other files are selected) and the current status. */
    private data class EditorState(val rootDir: VirtualFile?, val status: EditorStatus) {
        companion object {
            val NONE = EditorState(null, EditorStatus.NO_FILE)
        }
    }

    private sealed interface EditorEvent {
        data class Selected(val file: VirtualFile?) : EditorEvent

        /** Read the selection again (start-up, structure changes, a switch to Current file's root). */
        data object Reread : EditorEvent
    }

    private class CoverageEntry(val stamp: Long, val setText: String?, val coverage: ScopeCoverage)

    private data class Listed(val scope: NamedScope, val holder: NamedScopesHolder, val origin: ScopeOrigin)

    private val choiceTracker = SimpleModificationTracker()
    private val scopesTracker = SimpleModificationTracker()

    override val modificationTracker: ModificationTracker = ModificationTracker {
        choiceTracker.modificationCount + coverageStamp() + AnsibilityProjectSettings.getInstance(project).driftModificationTracker.modificationCount
    }

    @Volatile
    private var cached: WorkspaceScopeImpl? = null

    @Volatile
    private var editor: EditorState = EditorState.NONE

    @Volatile
    private var catalogRequested = false

    private val coverages = ConcurrentHashMap<String, CoverageEntry>()
    private val editorEvents = Channel<EditorEvent>(Channel.CONFLATED)
    private val warmups = Channel<Unit>(Channel.CONFLATED)

    init {
        for (holder in NamedScopesHolder.getAllNamedScopeHolders(project)) {
            holder.addScopeListener(NamedScopesHolder.ScopeListener { scopesChanged() }, this)
        }
        val connection = project.messageBus.connect(this)
        connection.subscribe(
            AnsibilitySettingsListener.TOPIC,
            object : AnsibilitySettingsListener {
                override fun workspaceStateChanged(old: WorkspaceState, new: WorkspaceState) {
                    if (old.scope != new.scope) choiceChanged(ScopeChoices.decode(new.scope))
                }

                override fun projectSettingsChanged(old: ProjectSettings, new: ProjectSettings) = inputsChanged()
            },
        )
        connection.subscribe(AnsibleStructureListener.TOPIC, AnsibleStructureListener { inputsChanged() })
        connection.subscribe(
            ModuleRootListener.TOPIC,
            object : ModuleRootListener {
                override fun rootsChanged(event: ModuleRootEvent) = inputsChanged()
            },
        )
        connection.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    editorEvents.trySend(EditorEvent.Selected(event.newFile))
                }
            },
        )
        cs.launch(Dispatchers.Default) {
            for (event in editorEvents) guarded("following the editor") { onEditorEvent(event) }
        }
        cs.launch(Dispatchers.Default) {
            for (ignored in warmups) guarded("computing scope coverage") { warmUp() }
        }
        editorEvents.trySend(EditorEvent.Reread)
    }

    // ------------------------------------------------------------------------------------------------ the contract

    override fun current(): WorkspaceScope = currentScope()

    /** [current] with the implementation type (labels, coverage without walking, the named scope). */
    fun currentScope(): WorkspaceScopeImpl {
        val stamp = modificationTracker.modificationCount
        cached?.let { if (it.stamp == stamp) return it }
        val scope = build(stamp)
        cached = scope
        if (scope.namedScope != null && scope.coverageIfComputed() == null) requestWarmup()
        return scope
    }

    override fun set(choice: ScopeChoice) {
        val text = ScopeChoices.encode(choice)
        AnsibilityWorkspaceState.getInstance(project).update { it.copy(scope = text) }
    }

    /** The stored choice. */
    fun choice(): ScopeChoice = ScopeChoices.decode(AnsibilityWorkspaceState.getInstance(project).snapshot.scope)

    /**
     * Re-evaluates dynamic scopes (Open Files, Changed Files) and every coverage: drops the coverage cache and moves
     * [modificationTracker]. The tool window calls it when it is shown and on Refresh (F9.1 D47).
     */
    fun refresh() {
        scopesTracker.incModificationCount()
        coverages.clear()
        changed()
        if (catalogRequested || cached?.namedScope != null) requestWarmup()
    }

    // ------------------------------------------------------------------------------------------------ the selector

    /**
     * The scopes the selector lists, with the coverage known so far (never walks on the calling thread; the first call
     * starts computing every listed scope's coverage in the background). Cheap; any thread.
     */
    fun catalog(): ScopeCatalog {
        catalogRequested = true
        val roots = displayRoots()
        val listed = listScopes()
        val entries = listed.map { ScopeEntry(it.scope, it.holder, it.origin, coverageOf(it.scope, it.holder, compute = false)) }
        if (entries.any { it.coverage == null }) requestWarmup()
        val users = entries.filter { it.origin != ScopeOrigin.PREDEFINED }
        val index = roots.withIndex().associate { (i, root) -> root to i }
        val sortedUsers = users.sortedBy { entry ->
            val coverage = entry.coverage
            when {
                coverage == null -> Int.MAX_VALUE - 1
                coverage.roots.isEmpty() -> Int.MAX_VALUE
                else -> coverage.roots.minOf { index[it] ?: Int.MAX_VALUE - 2 }
            }
        }
        val uncovered = if (users.any { it.coverage == null }) null else roots.filter { root -> users.none { root in it.coverage!!.roots } }
        return ScopeCatalog(roots, sortedUsers, entries.filter { it.origin == ScopeOrigin.PREDEFINED }, uncovered)
    }

    /** The non-detached roots as "Choose roots…" offers them, display order. */
    fun rootOptions(): List<RootOption> {
        val roots = displayRoots()
        val byDir = roots.associateBy { it.dir }
        return roots.map { root ->
            RootOption(RootKeys.keyOf(project, root.dir), root.displayName, root.parentDir?.let { byDir[it]?.displayName })
        }
    }

    /** The root of the last selected Ansible file (kept while other files are selected), or null. */
    fun editorRoot(): AnsibleRoot? = editor.rootDir?.let { dir -> displayRoots().firstOrNull { it.dir == dir } }

    /** The cached coverage of [scope], or null when it is not computed yet for the current stamps. */
    fun cachedCoverage(scope: NamedScope, holder: NamedScopesHolder): ScopeCoverage? = coverageOf(scope, holder, compute = false)

    // ------------------------------------------------------------------------------------------------ building

    private fun build(stamp: Long): WorkspaceScopeImpl {
        val all = workspace().roots()
        val roots = WorkspaceSnapshotBuilder.displayOrder(all.filter { !it.detached })
        val references = references(roots)
        return when (val choice = choice()) {
            ScopeChoice.AllRoots -> fixed(
                choice, ScopeMembership.Everything, ScopeCoverage.all(roots), references,
                AnsibilityScopeBundle.message("scope.label.all"), problem = null, chosen = emptyList(), stamp = stamp,
            )
            ScopeChoice.CurrentFileRoot -> {
                val state = editor
                val root = state.rootDir?.let { dir -> roots.firstOrNull { it.dir == dir } }
                val problem = when {
                    state.status == EditorStatus.DETACHED_FILE -> AnsibilityScopeBundle.message("scope.problem.detachedFile")
                    state.status != EditorStatus.ANSIBLE_FILE || root == null -> AnsibilityScopeBundle.message("scope.problem.noAnsibleFile")
                    else -> null
                }
                val label = root?.let { AnsibilityScopeBundle.message("scope.label.current.root", it.displayName) }
                    ?: AnsibilityScopeBundle.message("scope.label.current")
                directories(choice, listOfNotNull(root), roots, references, label, problem, stamp)
            }
            is ScopeChoice.Roots -> {
                val keys = roots.associateWith { RootKeys.keyOf(project, it.dir) }
                val chosen = roots.filter { keys.getValue(it) in choice.keys }
                val missing = (choice.keys - keys.values.toSet()).sorted()
                val problem = when {
                    choice.keys.isEmpty() -> AnsibilityScopeBundle.message("scope.problem.noRootsChosen")
                    missing.isNotEmpty() -> AnsibilityScopeBundle.message("scope.problem.rootsMissing", missing.joinToString(", "))
                    else -> null
                }
                directories(choice, chosen, roots, references, rootsLabel(chosen), problem, stamp)
            }
            is ScopeChoice.Named -> {
                val found = findScope(choice.scopeId)
                if (found == null) {
                    fixed(
                        choice, ScopeMembership.Everything, ScopeCoverage.all(roots), references,
                        AnsibilityScopeBundle.message("scope.label.deleted", choice.scopeId),
                        AnsibilityScopeBundle.message("scope.problem.deleted", choice.scopeId), emptyList(), stamp,
                    )
                } else {
                    val (scope, holder) = found
                    val source = object : WorkspaceScopeImpl.CoverageSource {
                        override fun cached(): ScopeCoverage? = coverageOf(scope, holder, compute = false)
                        override fun compute(): ScopeCoverage = coverageOf(scope, holder, compute = true)!!
                    }
                    WorkspaceScopeImpl(
                        project, choice, scope, holder, ScopeMembership.Named(scope), source, references,
                        fixedProblem = null, label = scope.presentableName, chosenRoots = emptyList(), stamp = stamp,
                    )
                }
            }
        }
    }

    private fun fixed(
        choice: ScopeChoice,
        membership: ScopeMembership,
        coverage: ScopeCoverage,
        references: List<AnsibleRoot>,
        @Nls label: String,
        @Nls problem: String?,
        chosen: List<AnsibleRoot>,
        stamp: Long,
    ): WorkspaceScopeImpl {
        val source = object : WorkspaceScopeImpl.CoverageSource {
            override fun cached(): ScopeCoverage = coverage
            override fun compute(): ScopeCoverage = coverage
        }
        return WorkspaceScopeImpl(project, choice, null, null, membership, source, references, problem, label, chosen, stamp)
    }

    private fun directories(
        choice: ScopeChoice,
        chosen: List<AnsibleRoot>,
        roots: List<AnsibleRoot>,
        references: List<AnsibleRoot>,
        @Nls label: String,
        @Nls problem: String?,
        stamp: Long,
    ): WorkspaceScopeImpl {
        val dirs = chosen.map { it.dir }
        val membership = if (dirs.isEmpty()) ScopeMembership.Nothing else ScopeMembership.Directories(dirs)
        return fixed(choice, membership, ScopeCoverage.ofDirectories(roots, dirs), references, label, problem, chosen, stamp)
    }

    @Nls
    private fun rootsLabel(chosen: List<AnsibleRoot>): String = when (chosen.size) {
        0 -> AnsibilityScopeBundle.message("scope.label.roots.none")
        1, 2 -> chosen.joinToString(" + ") { it.displayName }
        else -> AnsibilityScopeBundle.message("scope.label.roots.more", chosen.take(2).joinToString(" + ") { it.displayName }, chosen.size - 2)
    }

    private fun findScope(scopeId: String): Pair<NamedScope, NamedScopesHolder>? {
        for (holder in NamedScopesHolder.getAllNamedScopeHolders(project)) {
            holder.getScope(scopeId)?.let { return it to holder }
        }
        return null
    }

    private fun listScopes(): List<Listed> {
        val holders = NamedScopesHolder.getAllNamedScopeHolders(project)
        val seen = HashSet<String>()
        val result = ArrayList<Listed>()
        for (holder in holders) {
            val origin = if (holder is DependencyValidationManager) ScopeOrigin.SHARED else ScopeOrigin.LOCAL
            for (scope in holder.editableScopes) {
                if (seen.add(scope.scopeId)) result += Listed(scope, holder, origin)
            }
        }
        for (holder in holders) {
            for (scope in holder.predefinedScopes) {
                if (scope.value != null && seen.add(scope.scopeId)) result += Listed(scope, holder, ScopeOrigin.PREDEFINED)
            }
        }
        return result
    }

    // ------------------------------------------------------------------------------------------------ coverage

    private fun coverageOf(scope: NamedScope, holder: NamedScopesHolder, compute: Boolean): ScopeCoverage? {
        val key = holder.javaClass.name + "\u0000" + scope.scopeId
        val stamp = coverageStamp()
        val text = scope.value?.text
        coverages[key]?.let { if (it.stamp == stamp && it.setText == text) return it.coverage }
        if (!compute || project.isDisposed) return null
        val coverage = readLocked {
            val all = workspace().roots()
            val roots = WorkspaceSnapshotBuilder.displayOrder(all.filter { !it.detached })
            val filter = GlobalSearchScopesCore.filterScope(project, scope)
            ScopeCoverageWalker.compute(project, roots, all) { filter.contains(it) }
        }
        coverages[key] = CoverageEntry(stamp, text, coverage)
        return coverage
    }

    /**
     * The roots kept as drift references outside the scope (plan amendment R24, D177): none without a golden root,
     * every role library for "first role library" (each name's reference may be in any of them), else the chosen root.
     */
    private fun references(roots: List<AnsibleRoot>): List<AnsibleRoot> =
        when (val golden = AnsibilityProjectSettings.getInstance(project).settings.drift.golden) {
            GoldenRoot.None -> emptyList()
            GoldenRoot.FirstRoleLibrary -> roots.filter { it.kind == RootKind.ROLE_LIBRARY }
            is GoldenRoot.Root -> listOfNotNull(GoldenRoots.resolve(project, golden, roots).root)
            // R25: outside the project, so no root of it is kept as a reference.
            GoldenRoot.Git, GoldenRoot.Folder -> emptyList()
        }

    private fun coverageStamp(): Long =
        scopesTracker.modificationCount +
            workspace().structureTracker.modificationCount +
            ProjectRootManager.getInstance(project).modificationCount +
            AnsibilityProjectSettings.getInstance(project).modificationTracker.modificationCount

    private fun requestWarmup() {
        warmups.trySend(Unit)
    }

    private suspend fun warmUp() {
        if (project.isDisposed) return
        val scope = currentScope()
        scope.namedScope?.let { named -> readAction { coverageOf(named, scope.holder!!, compute = true) } }
        if (!catalogRequested) return
        for (listed in readAction { listScopes() }) {
            readAction { coverageOf(listed.scope, listed.holder, compute = true) }
        }
    }

    // ------------------------------------------------------------------------------------------------ events

    private fun scopesChanged() {
        scopesTracker.incModificationCount()
        coverages.clear()
        changed()
        if (catalogRequested || choice() is ScopeChoice.Named) requestWarmup()
    }

    private fun choiceChanged(choice: ScopeChoice) {
        choiceTracker.incModificationCount()
        changed()
        when (choice) {
            ScopeChoice.CurrentFileRoot -> editorEvents.trySend(EditorEvent.Reread)
            is ScopeChoice.Named -> requestWarmup()
            else -> Unit
        }
    }

    /** The Ansible structure, the project roots or the project settings changed: roots and coverage may differ. */
    private fun inputsChanged() {
        changed()
        editorEvents.trySend(EditorEvent.Reread)
        if (catalogRequested || choice() is ScopeChoice.Named) requestWarmup()
    }

    private suspend fun onEditorEvent(event: EditorEvent) {
        if (project.isDisposed) return
        val file = when (event) {
            is EditorEvent.Selected -> event.file
            EditorEvent.Reread -> withContext(Dispatchers.EDT) {
                if (project.isDisposed) null else FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
            }
        }
        val root = if (file == null) null else readAction { if (file.isValid && !project.isDisposed) workspace().rootFor(file) else null }
        editorSelected(file, root)
    }

    /** Records that [file] (in [root], or in no root) is the selected editor's file. */
    @TestOnly
    internal fun editorSelectedForTests(file: VirtualFile?, root: AnsibleRoot?) = editorSelected(file, root)

    private fun editorSelected(file: VirtualFile?, root: AnsibleRoot?) {
        val status = when {
            file == null -> EditorStatus.NO_FILE
            root == null -> EditorStatus.OTHER_FILE
            root.detached -> EditorStatus.DETACHED_FILE
            else -> EditorStatus.ANSIBLE_FILE
        }
        val previous = editor
        val next = EditorState(if (status == EditorStatus.ANSIBLE_FILE) root!!.dir else previous.rootDir, status)
        if (next == previous) return
        editor = next
        if (choice() == ScopeChoice.CurrentFileRoot) {
            choiceTracker.incModificationCount()
            changed()
        }
    }

    /** Forgets the editor state, the coverage cache and the catalog request (light test projects outlive a test). */
    @TestOnly
    fun resetForTests() {
        editor = EditorState.NONE
        catalogRequested = false
        coverages.clear()
        cached = null
        scopesTracker.incModificationCount()
    }

    /** Runs [work], logging a failure instead of ending the event loop (cancellation still propagates). */
    private suspend fun guarded(what: String, work: suspend () -> Unit) {
        try {
            work()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Workspace scope: $what failed", e)
        }
    }

    private fun changed() {
        if (!project.isDisposed) project.messageBus.syncPublisher(WorkspaceScopeListener.TOPIC).scopeChanged()
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private fun displayRoots(): List<AnsibleRoot> =
        if (project.isDisposed) emptyList() else WorkspaceSnapshotBuilder.displayOrder(workspace().roots().filter { !it.detached })

    private fun workspace(): AnsibleWorkspace = AnsibleWorkspace.getInstance(project)

    private fun <T> readLocked(action: () -> T): T =
        if (ApplicationManager.getApplication().isReadAccessAllowed) action() else runReadActionBlocking(action)

    override fun dispose() {
        editorEvents.close()
        warmups.close()
        coverages.clear()
        cached = null
    }

    companion object {
        private val LOG = logger<WorkspaceScopeServiceImpl>()

        /** The implementation, or null when another [WorkspaceScopeService] is registered (tests). */
        fun getInstance(project: Project): WorkspaceScopeServiceImpl? = WorkspaceScopeService.getInstance(project) as? WorkspaceScopeServiceImpl
    }
}
