package de.terletzkiy.ansibility.context.host

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.DefinitionStatus
import de.terletzkiy.ansibility.api.EffectiveBreakdown
import de.terletzkiy.ansibility.api.EffectiveVars
import de.terletzkiy.ansibility.api.EvalTarget
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.api.HostScope
import de.terletzkiy.ansibility.api.HostScopeOrigin
import de.terletzkiy.ansibility.api.InventoryFacts
import de.terletzkiy.ansibility.api.PrecedenceChain
import de.terletzkiy.ansibility.api.RoleReach
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarDefinition
import de.terletzkiy.ansibility.api.VarSourceRef
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.model.inventory.ModelCache
import de.terletzkiy.ansibility.model.inventory.ModelCacheKind
import de.terletzkiy.ansibility.model.inventory.ModelCaches
import de.terletzkiy.ansibility.model.inventory.VarsDocuments
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.AnsibilitySettingsListener
import de.terletzkiy.ansibility.settings.AnsibilityWorkspaceState
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext
import de.terletzkiy.ansibility.settings.RootKeys
import de.terletzkiy.ansibility.settings.WorkspaceState
import org.jetbrains.annotations.Nls

/**
 * The project's [AnsibleContextService] (plan amendment R7/R8, A.14; WU HA1).
 *
 * - **Selection.** Stored per user in [AnsibilityWorkspaceState] under the root's `RootKeys` key. Nested playbook roots
 *   share their parent's environment and host (stored under the parent's key) and keep their own play. [setSelection]
 *   applies the selection rules: a host needs a named environment, so a host chosen under All gets its environment
 *   when exactly one environment defines it (and is dropped when none or several do). A stored part that no longer
 *   exists is kept and reported ([selectionProblem]); scopes fall back to its environment (or All, or Auto).
 * - **Scopes.** [FileScopes] infers what a file applies to; [Selections] intersects it with the selection (the file
 *   scope wins when they are disjoint) or builds the file-free [selectionScope]. Inspections use [allHostsScope]
 *   (D32), which ignores the selection.
 * - **Evaluation.** Single-name evaluation per target (`PrecedenceEngine.effectiveOf`) on the inventory view
 *   (`model.effective.HostViews`, `MoleculeViews`) and the execution inputs (`model.effective.ExecutionSources`) of
 *   the target's play and the scope's running role (the role of a role file or template, a molecule scenario's role).
 * - **Caching** (plan amendment R7/R8, A.9; WU HA2). File scopes, reach, play hits, addresses and definition statuses
 *   are [ModelCache] entries that depend only on what they were computed from (the inventory views per (root, env,
 *   playbook dir), the play graphs per playbook, the execution inputs per (play, running role), and the files read);
 *   typing in a role's tasks invalidates none of them unless a template's render contexts change. Host scopes and
 *   selection scopes are presentation entries keyed by the selection; the selection is never part of a model key, so
 *   switching env, host or play recomputes no model cache ([ModelCaches.snapshot] counts it). [modelTracker] stays the
 *   coarse "some model input may have changed" signal for consumers' own caches. The per-root background summary is
 *   [RootEffectiveSummaries].
 *
 * Every query takes a read lock when the caller holds none and needs smart mode (render contexts, runtime markers and
 * the variable index). Nothing here runs a process or decrypts a vault value: vault values stay `YVault` markers and
 * outcomes are grouped by location.
 */
class AnsibleContextServiceImpl(private val project: Project) : AnsibleContextService, Disposable {
    private val selectionCounter = SimpleModificationTracker()

    private val varsTracker: ModificationTracker by lazy { VarsDocuments.tracker(project) }

    override val selectionTracker: ModificationTracker = selectionCounter

    override val modelTracker: ModificationTracker = ModificationTracker {
        AnsibleWorkspace.getInstance(project).structureTracker.modificationCount +
            varsTracker.modificationCount +
            AnsibilityProjectSettings.getInstance(project).modificationTracker.modificationCount +
            TargetVersionDetector.getInstance(project).modificationTracker.modificationCount +
            ProjectRootManager.getInstance(project).modificationCount
    }

    internal val model = ContextModel(project)
    private val selections = Selections(project, model)
    private val reaches = RoleReaches(project, model)
    private val scopes = FileScopes(project, model, reaches::reach)
    internal val evaluator = ContextEvaluator(project, model)
    private val facts = InventoryFactsBuilder(project, model, evaluator)

    private data class FileScopeKey(val root: AnsibleRoot, val file: VirtualFile, val bucket: String)

    private data class HostScopeKey(
        val root: AnsibleRoot,
        val file: VirtualFile,
        val bucket: String,
        val selection: RootContext,
        val followEditor: Boolean,
    )

    private data class SelectionScopeKey(val root: AnsibleRoot, val selection: RootContext)

    private data class StatusKey(val file: VirtualFile, val offset: Int, val name: String, val kind: VarDefKind)

    private val fileScopes = ModelCache<FileScopeKey, FileScope>(project, "context.fileScopes", maxSize = MAX_CACHED)
    private val hostScopes = ModelCache<HostScopeKey, HostScope>(project, "presentation.hostScopes", ModelCacheKind.PRESENTATION, MAX_CACHED)
    private val selectionScopes = ModelCache<SelectionScopeKey, HostScope>(project, "presentation.selectionScopes", ModelCacheKind.PRESENTATION, MAX_CACHED)
    private val statuses = ModelCache<StatusKey, DefinitionStatus>(project, "context.statuses", maxSize = MAX_CACHED)

    init {
        project.messageBus.connect(this).subscribe(
            AnsibilitySettingsListener.TOPIC,
            object : AnsibilitySettingsListener {
                override fun workspaceStateChanged(old: WorkspaceState, new: WorkspaceState) {
                    // HA3: Follow editor changes what hostScope returns, so it counts as a selection change.
                    if (old.roots != new.roots || old.followEditor != new.followEditor) selectionCounter.incModificationCount()
                }
            },
        )
    }

    override fun dispose() {
        fileScopes.clear()
        hostScopes.clear()
        selectionScopes.clear()
        statuses.clear()
    }

    /** The scope's inventory root ([AnsibleRoot] whose environments a nested root shares). */
    internal fun inventoryRoot(root: AnsibleRoot): AnsibleRoot = model.inventoryRoot(root)

    // ------------------------------------------------------------------------------------------------ selection

    override fun selection(root: AnsibleRoot): RootContext {
        val state = AnsibilityWorkspaceState.getInstance(project).snapshot
        val parentDir = root.parentDir?.takeIf { root.kind == RootKind.NESTED_PLAYBOOK } ?: return state.root(RootKeys.keyOf(project, root.dir))
        val shared = state.root(RootKeys.keyOf(project, parentDir))
        return RootContext(shared.environment, shared.host, state.root(RootKeys.keyOf(project, root.dir)).play)
    }

    override fun setSelection(root: AnsibleRoot, context: RootContext) {
        val normalised = readLocked { normalise(root, context) }
        val state = AnsibilityWorkspaceState.getInstance(project)
        val parentDir = root.parentDir?.takeIf { root.kind == RootKind.NESTED_PLAYBOOK }
        if (parentDir == null) {
            state.updateContext(root) { normalised }
            return
        }
        val parentKey = RootKeys.keyOf(project, parentDir)
        val ownKey = RootKeys.keyOf(project, root.dir)
        state.update { current ->
            current
                .withRoot(parentKey, current.root(parentKey).copy(environment = normalised.environment, host = normalised.host))
                .withRoot(ownKey, RootContext(play = normalised.play))
        }
    }

    /** The selection rules: blank parts are absent, and a host chosen under All gets its one environment. */
    private fun normalise(root: AnsibleRoot, context: RootContext): RootContext {
        val play = context.play?.takeIf { it.isNotBlank() }
        val host = context.host?.takeIf { it.isNotBlank() }
        if (host == null || context.environment is EnvironmentChoice.Named) return RootContext(context.environment, host, play)
        val environments = model.environments(root).filter { it.graph.host(host) != null }
        return if (environments.size == 1) {
            RootContext(EnvironmentChoice.Named(environments.single().name), host, play)
        } else {
            RootContext(EnvironmentChoice.All, null, play)
        }
    }

    /**
     * What is wrong with the stored selection of [root] ("prod-db9 is no longer in environments/prod/hosts.yml"), or
     * null. Scopes fall back to the part that still exists; the stored selection is kept.
     */
    @Nls
    fun selectionProblem(root: AnsibleRoot): String? = readLocked { selections.resolve(root, selection(root)).problem }

    // ------------------------------------------------------------------------------------------------ scopes

    override fun hostScope(file: VirtualFile, offset: Int): HostScope =
        hostScope(file, offset, AnsibilityWorkspaceState.getInstance(project).snapshot.followEditor)

    /**
     * [hostScope] as if Follow editor (D33, `WorkspaceState.followEditor`) were [followEditor] (HA3: the context banner
     * compares the file scope with the selection whatever the setting). With Follow editor on, a file scope disjoint from
     * the selection wins ([HostScope.overriddenSelection]); off, the selection applies as-is, so such a file has no
     * targets and its empty reason says it is not loaded for the selection.
     */
    fun hostScope(file: VirtualFile, offset: Int, followEditor: Boolean): HostScope = readLocked {
        val (root, context) = locate(file)
        scope(root, file, context, offset, selection(root), followEditor)
    }

    override fun allHostsScope(file: VirtualFile): HostScope = readLocked {
        val (root, context) = locate(file)
        scope(root, file, context, -1, RootContext.DEFAULT, followEditor = true)
    }

    override fun selectionScope(root: AnsibleRoot, context: RootContext): HostScope = readLocked {
        selectionScopes.get(SelectionScopeKey(root, context)) {
            selections.selectionScope(root, selections.resolve(root, context), HostScopeOrigin.Selection)
        }
    }

    /** The root and context of [file]; a file outside every Ansible root has no host scope. */
    private fun locate(file: VirtualFile): Pair<AnsibleRoot, FileContext?> {
        val workspace = AnsibleWorkspace.getInstance(project)
        val context = workspace.contextOf(file)
        val root = context?.root ?: workspace.rootFor(file) ?: throw IllegalArgumentException("${file.path} is not inside an Ansible root")
        return root to context
    }

    private fun scope(
        root: AnsibleRoot,
        file: VirtualFile,
        context: FileContext?,
        offset: Int,
        selection: RootContext,
        followEditor: Boolean,
    ): HostScope {
        val bucket = scopes.bucket(file, context, offset)
        return hostScopes.get(HostScopeKey(root, file, bucket, selection, followEditor)) {
            val scope = selections.intersect(root, fileScope(root, file, context, offset, bucket), selections.resolve(root, selection))
            if (followEditor || !scope.overriddenSelection) scope else notLoadedForSelection(scope)
        }
    }

    /** HA3, Follow editor off: the disjoint file scope does not win, so the file is not loaded for the selection. */
    private fun notLoadedForSelection(scope: HostScope): HostScope {
        val selection = scope.selection
        val environment = (selection.environment as? EnvironmentChoice.Named)?.name
        val label = when {
            environment == null -> AnsibilityHostBundle.message("selection.all.environments")
            selection.host == null -> environment
            else -> "$environment › ${selection.host}"
        }
        return scope.copy(
            targets = emptyList(),
            overriddenSelection = false,
            emptyReason = AnsibilityHostBundle.message("scope.empty.not.loaded.for.selection", label),
        )
    }

    /**
     * The selection-free scope of [file] at [offset] whose caret-dependent part is [bucket] ([FileScopes.bucket]); the
     * offset only matters through the bucket, so every offset of one bucket shares the entry.
     */
    internal fun fileScope(root: AnsibleRoot, file: VirtualFile, context: FileContext?, offset: Int, bucket: String): FileScope =
        fileScopes.get(FileScopeKey(root, file, bucket)) { scopes.of(root, file, context, if (bucket.isEmpty()) -1 else offset) }

    // ------------------------------------------------------------------------------------------------ evaluation

    override fun effective(scope: HostScope, name: String): EffectiveBreakdown = readLocked {
        val role = runningRole(scope)
        val molecule = moleculeCompanions(scope, role)
        evaluator.breakdown(name, scope.targets, molecule, role, scope.root)
    }

    override fun definitionStatus(definition: VarDefinition): DefinitionStatus = readLocked {
        when (definition.kind) {
            VarDefKind.SPEC_OPTION -> notLoaded(AnsibilityHostBundle.message("definition.declaration"))
            VarDefKind.SET_FACT, VarDefKind.REGISTER, VarDefKind.VARS_PROMPT, VarDefKind.LOOP_VAR, VarDefKind.INDEX_VAR,
            VarDefKind.JINJA_LOCAL, VarDefKind.TEMPLATE_VARS,
            -> notLoaded(AnsibilityHostBundle.message("definition.runtime"))
            VarDefKind.BLOCK_VARS, VarDefKind.TASK_VARS, VarDefKind.INCLUDE_PARAMS ->
                notLoaded(AnsibilityHostBundle.message("definition.task.scoped"))
            VarDefKind.ROLE_PARAMS -> notLoaded(AnsibilityHostBundle.message("definition.role.params"))
            else -> {
                val location = definition.location
                statuses.get(StatusKey(location.file, location.offset, definition.name, definition.kind)) { computeStatus(definition) }
            }
        }
    }

    private fun computeStatus(definition: VarDefinition): DefinitionStatus {
        val file = definition.location.file
        val workspace = AnsibleWorkspace.getInstance(project)
        val context = workspace.contextOf(file)
        val root = context?.root ?: workspace.rootFor(file) ?: return notLoaded(null)
        val bucket = scopes.bucket(file, context, definition.location.offset)
        val fileScope = fileScope(root, file, context, definition.location.offset, bucket)
        val scope = selections.intersect(root, fileScope, selections.resolve(root, RootContext.DEFAULT))
        val role = fileScope.runningRole
        val targets = scope.targets + moleculeCompanions(scope, role)
        val own = Location(file, definition.location.offset)
        val wins = LinkedHashSet<HostKey>()
        val shadowed = LinkedHashMap<HostKey, VarSourceRef>()
        for (target in targets.distinct()) {
            ProgressManager.checkCanceled()
            val evaluation = evaluator.evaluation(target, role, root) ?: continue
            val effective = evaluation.effectiveOf(definition.name) ?: continue
            val winner = evaluation.ref(effective) ?: continue
            when {
                Location.of(winner) == own -> wins += target.host
                effective.shadowed.any { shadow -> evaluation.origins[shadow.source.originId]?.file == file && shadow.keyRange?.start == own.offset } ->
                    shadowed.putIfAbsent(target.host, winner)
            }
        }
        wins.forEach(shadowed::remove)
        val reason = if (wins.isEmpty() && shadowed.isEmpty()) {
            scope.emptyReason ?: AnsibilityHostBundle.message("definition.not.loaded", root.displayName)
        } else {
            null
        }
        return DefinitionStatus(wins.toList(), shadowed, reason)
    }

    private fun notLoaded(@Nls reason: String?) = DefinitionStatus(emptyList(), emptyMap(), reason)

    override fun inventoryFacts(scope: HostScope): InventoryFacts = readLocked { facts.facts(scope) }

    override fun reach(root: AnsibleRoot, role: String): RoleReach = readLocked { reaches.reach(root, role) }

    override fun inventoryView(target: EvalTarget): EffectiveVars? = readLocked { evaluator.inventoryView(target) }

    override fun executionView(target: EvalTarget, runningRole: String?): EffectiveVars? = readLocked { evaluator.executionView(target, runningRole) }

    override fun explain(target: EvalTarget, name: String): PrecedenceChain = explain(target, name, runningRole = null)

    /**
     * [explain] for a task of [runningRole], whose defaults and vars are re-applied last (the chain a role file's or
     * template's hover explains). The interface method explains a play-level task.
     */
    fun explain(target: EvalTarget, name: String, runningRole: String?): PrecedenceChain = readLocked {
        evaluator.explain(target, name, runningRole)
    }

    /** The role whose tasks run in [scope]'s contexts: a role file's or template's role, a molecule scenario's role. */
    fun runningRole(scope: HostScope): String? = readLocked {
        when (val origin = scope.origin) {
            is HostScopeOrigin.RoleReach -> origin.role
            is HostScopeOrigin.Molecule -> model.molecules(scope.root).firstOrNull { it.scenarioDir == origin.scenarioDir }?.roleName
                ?: RoleRegistry.getInstance(project).roleOf(origin.scenarioDir)?.ref?.name
            else -> null
        }
    }

    /**
     * The molecule hosts that accompany a role scope (F8.10: "role files show molecule outcomes on a separate line"):
     * every scenario of the role, while the selection names no inventory environment.
     */
    private fun moleculeCompanions(scope: HostScope, role: String?): List<EvalTarget> {
        if (role == null || scope.origin !is HostScopeOrigin.RoleReach || scope.targets.any { it.host.isMolecule }) return emptyList()
        if (scope.selection.environment is EnvironmentChoice.Named) return emptyList()
        return model.molecules(scope.root).filter { it.roleName == role }.flatMap { model.moleculeTargets(scope.root, it) }
    }

    private fun <T> readLocked(action: () -> T): T =
        if (ApplicationManager.getApplication().isReadAccessAllowed) action() else runReadActionBlocking(action)

    companion object {
        private const val MAX_CACHED = 8192

        /** This project's service, when it is this implementation. */
        fun getInstance(project: Project): AnsibleContextServiceImpl? = AnsibleContextService.getInstance(project) as? AnsibleContextServiceImpl
    }
}
