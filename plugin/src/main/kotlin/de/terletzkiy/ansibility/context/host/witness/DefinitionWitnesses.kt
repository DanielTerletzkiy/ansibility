package de.terletzkiy.ansibility.context.host.witness

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.EvalTarget
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.api.VarsLayer
import de.terletzkiy.ansibility.context.MoleculeVisibility
import de.terletzkiy.ansibility.context.host.AnsibleContextServiceImpl
import de.terletzkiy.ansibility.context.host.RootEffectiveSummaries
import de.terletzkiy.ansibility.context.host.RootEffectiveSummary
import de.terletzkiy.ansibility.index.JinjaBearing
import de.terletzkiy.ansibility.model.effective.ExecutionInputs
import de.terletzkiy.ansibility.model.effective.ExecutionSources
import de.terletzkiy.ansibility.model.role.RoleLayout
import de.terletzkiy.ansibility.model.task.TaskFileKind
import de.terletzkiy.ansibility.model.task.TaskFileModels
import de.terletzkiy.ansibility.model.task.TaskNode
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import org.jetbrains.annotations.Nls
import java.util.IdentityHashMap

/**
 * Where one variable has a value over the reachable contexts of a root (plan amendment R7/R8, F8.12 "witness"): for
 * every (env, host, play) a role or a set of targets runs in, whether some layer defines the name there, nothing does
 * (a **witness**: a certain "'x' is undefined" on that host if a use there is not guarded), or only something that runs
 * while the play runs might (`set_fact`, `register`, `include_vars`, `vars_prompt`, a templated `vars_files` entry).
 *
 * The answer never depends on the selected env, host or play (D32): [ofRole] evaluates the role's whole reach
 * ([AnsibleContextService.reach]); [of] evaluates exactly the targets it is given. Definitions are looked up in the
 * root's background summary ([RootEffectiveSummaries], built in the caller's read action when it is stale), so a query
 * for many names over the same targets costs a few map lookups per (name, target): build the [WitnessScope] once and
 * call [WitnessScope.report] per name.
 *
 * "Some layer defines it" means the winner of `PrecedenceEngine.effectiveOf` for a task of the running role: inventory
 * `group_vars`/`host_vars` and inline vars, playbook-level vars, role defaults and vars of every role of the play
 * (dependencies included, private roles excluded), play `vars:` and `vars_files`, role params and include params, and
 * extra vars once a context carries them as execution sources. [WitnessReport.inventoryOnlyOn] tells the contexts that
 * depend on the inventory alone. Molecule scenarios are no witnesses: the reach of a role counts real environments only.
 *
 * Used by ANS-V003 (`inspections.undefined`) and meant for HA8a's ANS-P003 (a required variable with no value for a
 * reachable context is exactly [WitnessReport.missingOn]). Call in a read action in smart mode (a read lock is taken when
 * the caller holds none); nothing here decrypts a vault value or reads one: the summary holds locations only.
 */
@Service(Service.Level.PROJECT)
class DefinitionWitnesses(private val project: Project) {

    /**
     * The witness scope of every context that applies [role] in [root] (directly, as a dependency or through
     * `include_role`/`import_role`), with [role] as the running role. [WitnessReport.emptyReason] says why there is none
     * ("No play of falcon applies totp-token").
     */
    fun ofRole(root: AnsibleRoot, role: String): WitnessScope = readLocked {
        val reach = AnsibleContextService.getInstance(project).reach(root, role)
        WitnessScope(root, reach.targets, role, reach.emptyReason)
    }

    /**
     * The witness scope of exactly [targets] of [root], evaluated for a task of [runningRole] (null: a play-level task
     * such as a playbook's own `tasks:`).
     */
    fun of(root: AnsibleRoot, targets: List<EvalTarget>, runningRole: String?): WitnessScope = readLocked {
        WitnessScope(root, targets.distinct(), runningRole, null)
    }

    /**
     * Targets resolved once for repeated [report] queries. Valid while the model is unchanged (one inspection pass, one
     * card); never cache it across PSI changes.
     */
    inner class WitnessScope internal constructor(
        val root: AnsibleRoot,
        /** The evaluated contexts, in environment, inventory and play order. */
        val targets: List<EvalTarget>,
        /** The role whose tasks the contexts run (its defaults and vars are applied last), or null for play-level tasks. */
        val runningRole: String?,
        @Nls private val emptyReason: String?,
    ) {
        private val impl = AnsibleContextServiceImpl.getInstance(project)
        private val summaryScope: RootEffectiveSummary.Scope? by lazy {
            if (targets.isEmpty()) null else RootEffectiveSummaries.getInstance(project).compute(root).scope(targets, runningRole)
        }

        /** The inputs of each target (null: its host is gone), resolved once. */
        private val inputs: List<ExecutionInputs?> by lazy {
            val inventoryRoot = impl?.inventoryRoot(root) ?: root
            targets.map { target ->
                ProgressManager.checkCanceled()
                val play = target.play
                if (impl == null) null else ExecutionSources.getInstance(project).inputs(executionRoot(play?.file, inventoryRoot), play, runningRole)
            }
        }

        private val dynamicIncludes = IdentityHashMap<ExecutionInputs, Boolean>()
        private val markers = HashMap<Pair<ExecutionInputs, String>, Boolean>()

        /**
         * Whether the contexts run Molecule scenarios (a scenario host or a Molecule play): only then do Molecule
         * definitions take part (plan amendment R20, D157).
         */
        private val seesMolecule: Boolean by lazy {
            targets.any { target -> target.host.isMolecule || target.play?.file?.let { MoleculeVisibility.isMoleculeFile(project, it) } == true }
        }

        /** Where [name] has a value, has none, or may get one at runtime, over [targets]. */
        fun report(name: String): WitnessReport = readLocked {
            val winners = summaryScope?.winners(name) ?: emptyList()
            val runtimeAnywhere = setAtRuntimeInRoot(root, name, seesMolecule)
            val defined = ArrayList<EvalTarget>()
            val inventory = ArrayList<EvalTarget>()
            val inventoryOnly = ArrayList<EvalTarget>()
            val missing = ArrayList<EvalTarget>()
            val runtime = ArrayList<EvalTarget>()
            val evaluated = ArrayList<EvalTarget>()
            for ((index, target) in targets.withIndex()) {
                ProgressManager.checkCanceled()
                val winner = winners.getOrNull(index)
                val targetInputs = inputs.getOrNull(index)
                when {
                    winner != null -> {
                        defined += target
                        if (winner.layer in INVENTORY_LAYERS) {
                            inventory += target
                            if (targetInputs?.sources?.none { name in it.entries } != false) inventoryOnly += target
                        }
                    }
                    targetInputs == null || !hostExists(target) -> continue
                    runtimeAnywhere || mayBeSetAtRuntime(targetInputs, name) -> runtime += target
                    else -> missing += target
                }
                evaluated += target
            }
            WitnessReport(name, runningRole, evaluated, defined, inventory, inventoryOnly, missing, runtime, emptyReason.takeIf { targets.isEmpty() })
        }

        private fun hostExists(target: EvalTarget): Boolean = impl?.inventoryView(target) != null

        /**
         * Whether something that runs in the context of [contextInputs] may set [name]: a templated `vars_files` entry
         * (any name), a `set_fact`/`register`/`include_vars` task of the play or its roles that names it, or an
         * `include_vars` task whose file or directory is templated or a directory (any name).
         */
        private fun mayBeSetAtRuntime(contextInputs: ExecutionInputs, name: String): Boolean {
            if (contextInputs.unknownSources.isNotEmpty()) return true
            if (dynamicIncludes.getOrPut(contextInputs) { hasDynamicIncludeVars(contextInputs) }) return true
            return markers.getOrPut(contextInputs to name) {
                val executionRoot = rootOf(contextInputs.rootDir) ?: root
                ExecutionSources.getInstance(project).runtimeMarkers(executionRoot, contextInputs, name).isNotEmpty()
            }
        }

        private fun rootOf(dir: VirtualFile): AnsibleRoot? = AnsibleWorkspace.getInstance(project).roots().firstOrNull { it.dir == dir }

        /** The root whose execution sources a play of [playFile] uses, as `ContextEvaluator.executionRoot` decides it. */
        private fun executionRoot(playFile: VirtualFile?, inventoryRoot: AnsibleRoot): AnsibleRoot =
            playFile?.let(AnsibleWorkspace.getInstance(project)::rootFor)?.takeIf { !it.detached || inventoryRoot.detached } ?: inventoryRoot
    }

    /**
     * Facts and registered results stay defined for a host for the rest of the run (also in later plays), so a
     * `set_fact`, `register` or `vars_prompt` of [name] anywhere in [root] may have set it before a context runs: static
     * evaluation cannot claim a certain failure then. Molecule playbooks never run before a production play, so their
     * tasks count only for contexts that run Molecule scenarios ([withMolecule], plan amendment R20, D157).
     */
    private fun setAtRuntimeInRoot(root: AnsibleRoot, name: String, withMolecule: Boolean): Boolean =
        VarService.getInstance(project).symbol(root, name).definitions.any {
            it.kind in RUNTIME_KINDS && (withMolecule || !MoleculeVisibility.isMolecule(project, root, it))
        }

    /** The `include_vars` tasks of the context whose source cannot be read statically. */
    private fun hasDynamicIncludeVars(inputs: ExecutionInputs): Boolean {
        val files = inputs.roleDirs.flatMap(RoleLayout::taskFiles) + listOfNotNull(inputs.play?.file)
        for (file in files) {
            ProgressManager.checkCanceled()
            val yaml = YamlFiles.yamlFile(project, file) ?: continue
            val kind = if (file == inputs.play?.file) TaskFileKind.PLAYBOOK else TaskFileKind.TASKS
            if (TaskFileModels.of(yaml, kind).tasks().any(::isDynamicIncludeVars)) return true
        }
        return false
    }

    private fun isDynamicIncludeVars(task: TaskNode): Boolean {
        val call = task.module ?: return false
        if (call.canonical !in INCLUDE_VARS && call.name !in INCLUDE_VARS) return false
        if (call.args.options.containsKey("dir")) return true
        val path = (call.args.option("file") as? YScalar)?.text ?: call.args.rawParams?.trim() ?: return true
        return path.isEmpty() || JinjaBearing.hasTemplateMarkers(path)
    }

    private fun <T> readLocked(action: () -> T): T =
        if (ApplicationManager.getApplication().isReadAccessAllowed) action() else runReadActionBlocking(action)

    companion object {
        private val INCLUDE_VARS = setOf("include_vars", "ansible.builtin.include_vars", "ansible.legacy.include_vars")

        /**
         * The inventory layers (levels 3 to 10 and molecule inventories): environment data, as opposed to the play's and
         * roles' own sources (role defaults and vars, play vars, `vars_files`, role and include params).
         */
        val INVENTORY_LAYERS: Set<VarsLayer> = VarsLayer.entries.filterTo(HashSet()) { it.level in 3..10 }

        /** Kinds of definitions that only exist once a task has run, and then for the rest of the run. */
        val RUNTIME_KINDS: Set<VarDefKind> = setOf(VarDefKind.SET_FACT, VarDefKind.REGISTER, VarDefKind.VARS_PROMPT)

        fun getInstance(project: Project): DefinitionWitnesses = project.service()
    }
}

/**
 * Where variable [name] has a value over the contexts of a [DefinitionWitnesses.WitnessScope] (targets whose host no
 * longer exists are left out).
 */
class WitnessReport internal constructor(
    val name: String,
    /** The running role the contexts were evaluated for. */
    val runningRole: String?,
    /** Every evaluated context, in scope order. */
    val evaluated: List<EvalTarget>,
    /** Contexts where some layer defines the name. */
    val definedOn: List<EvalTarget>,
    /**
     * The part of [definedOn] whose winning definition is an inventory layer ([DefinitionWitnesses.INVENTORY_LAYERS]):
     * there the value comes from environment data, not from the play or the roles that run the context.
     */
    val inventoryDefinedOn: List<EvalTarget>,
    /**
     * The part of [inventoryDefinedOn] where nothing else would define the name: no role default or var of the play's
     * roles, no play var, `vars_files` entry, role or include param. Without the inventory it would be undefined there.
     */
    val inventoryOnlyOn: List<EvalTarget>,
    /** Contexts where no layer defines the name and nothing run in the context may set it: the witnesses. */
    val missingOn: List<EvalTarget>,
    /** Contexts where no layer defines the name but a runtime source (`set_fact`, `register`, `include_vars` …) may. */
    val runtimeOn: List<EvalTarget>,
    /** Why the scope has no context at all (the role's reach is empty), or null. */
    @Nls val emptyReason: String?,
) {
    /** The first context that certainly lacks the name, or null. */
    val witness: EvalTarget? get() = missingOn.firstOrNull()

    /** The distinct hosts of [missingOn], in scope order. */
    val missingHosts: List<HostKey> get() = missingOn.map { it.host }.distinct()

    /** True when at least one context was evaluated. */
    val isReached: Boolean get() = evaluated.isNotEmpty()

    /** [missingHosts] grouped by environment, environments and hosts in scope order. */
    fun missingByEnvironment(): Map<String, List<String>> =
        missingHosts.groupByTo(LinkedHashMap(), { it.environment }, { it.host })

    override fun toString(): String =
        "WitnessReport($name: defined ${definedOn.size}, missing ${missingHosts.map { "${it.environment}/${it.host}" }}, runtime ${runtimeOn.size})"
}
