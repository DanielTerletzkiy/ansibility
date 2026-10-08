package de.terletzkiy.ansibility.api

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.settings.RootContext
import org.jetbrains.annotations.Nls

// The Ansible context (plan amendment R7/R8, A.14): (workspace scope, root, env | All, host | All, play | Auto).
// env, host and play are per root and live in `settings.RootContext`; the workspace scope is R9's
// `WorkspaceScopeService` (api/WorkspaceScope.kt). Neither service delegates to the other.

/**
 * One host of one environment of one root. The same name (`prod-prod1`) can name a different machine in every root,
 * so a host is only ever identified together with its root and environment.
 */
data class HostKey(
    /** The root's `settings.RootKeys` key (`repos/falcon/ansible`). Nested playbook roots use their parent's key. */
    val root: String,
    /** `environments/<name>`, or [moleculeEnvironment] for a molecule scenario's pseudo-inventory. */
    val environment: String,
    val host: String,
) {
    /** True for a host of a molecule scenario's pseudo-inventory (plan F8.10). */
    val isMolecule: Boolean get() = environment.startsWith(MOLECULE_PREFIX)

    companion object {
        /** The environment prefix of molecule pseudo-inventories. */
        const val MOLECULE_PREFIX: String = "molecule:"

        /** `molecule:<role>/<scenario>`, or `molecule:<scenario>` for a root-level `molecule/` directory. */
        fun moleculeEnvironment(role: String?, scenario: String): String =
            if (role == null) "$MOLECULE_PREFIX$scenario" else "$MOLECULE_PREFIX$role/$scenario"
    }
}

/**
 * What one host evaluates under: [play] null means the InventoryView only (levels 3–10, comparable with
 * `ansible-inventory --host`); with a play, the ExecutionView adds role defaults, play vars, `vars_files`, role vars,
 * runtime markers and role params. [playbookDir] decides the playbook-level `group_vars`/`host_vars` (a play's
 * [PlayRef.playbookDir]; null reproduces `ansible-inventory` without `--playbook-dir`).
 */
data class EvalTarget(val host: HostKey, val play: PlayRef?, val playbookDir: VirtualFile?)

/** Why a [HostScope] holds the hosts it holds; the UI words it "applies to". */
sealed interface HostScopeOrigin {
    /** No file narrows the selection (tool window, R9's WS9), or the file kind does not narrow it. */
    data object Selection : HostScopeOrigin

    /** `environments/<env>/host_vars/<host>` (file or directory form). */
    data class HostVars(val host: HostKey) : HostScopeOrigin

    /** A `group_vars` file or directory of [group]; [environment] null for playbook-level `group_vars`. */
    data class GroupVars(val environment: String?, val group: String) : HostScopeOrigin

    /** The caret in `environments/<env>/hosts.yml`: a group block, a host entry, or elsewhere (both null). */
    data class InventoryEntry(val environment: String, val group: String?, val host: String?) : HostScopeOrigin

    /** A role file or template: the hosts of the [plays] that apply [role] (dependencies and includes counted). */
    data class RoleReach(val role: String, val plays: List<PlayRef>) : HostScopeOrigin

    /** The caret inside one play of a playbook. */
    data class Play(val play: PlayRef) : HostScopeOrigin

    /** A file of a molecule scenario: that scenario's pseudo-inventory only. */
    data class Molecule(val scenarioDir: VirtualFile) : HostScopeOrigin

    /** No inventory narrows the file (a golden role without molecule scenarios, other files): root-wide behaviour. */
    data object RootWide : HostScopeOrigin
}

/**
 * The hosts a file applies to, intersected with the root's selection (plan amendment R7/R8, "Per-file inference").
 * Effective scope = file scope ∩ selection; when the two are disjoint, the file scope wins and [overriddenSelection]
 * is set (the context banner explains it); when the file scope itself is empty, [targets] is empty and
 * [emptyReason] says why, and features fall back to root-wide behaviour.
 */
data class HostScope(
    val root: AnsibleRoot,
    /** The stored selection the scope was computed for (All / All / Auto by default, D33). */
    val selection: RootContext,
    val origin: HostScopeOrigin,
    /** The evaluation contexts after the intersection, deduplicated by (env, host, playbook dir, role list). */
    val targets: List<EvalTarget>,
    /** The file's hosts before the intersection, for "not loaded for test-test1" texts. */
    val fileHosts: List<HostKey>,
    /** The file scope and the selection were disjoint, so the file scope was used. */
    val overriddenSelection: Boolean,
    /** Why [targets] is empty ("keepalived runs on no host of test"), or null. */
    @Nls val emptyReason: String?,
) {
    /** The distinct hosts of [targets], in target order. */
    val hosts: List<HostKey> get() = targets.map { it.host }.distinct()
}

/**
 * One outcome of a variable on a set of hosts: the definition that wins there ([winner] null: the hosts reach no
 * definition of the name) and the definitions it shadows, runner-up first. Outcomes are grouped by winner location,
 * never by value, so vault values group like any other value; environments are never merged.
 */
data class OutcomeGroup(
    val winner: VarSourceRef?,
    val hosts: List<HostKey>,
    val shadowed: List<VarSourceRef>,
    /** The plays the outcome was evaluated in (empty for inventory-only evaluation). */
    val plays: List<PlayRef>,
)

/** The per-host outcome of one variable name in a [HostScope] (the "Effective" card section, F8.2). */
data class EffectiveBreakdown(
    val name: String,
    /** Outcomes of the scope's inventory hosts, the largest group first. */
    val groups: List<OutcomeGroup>,
    /** Hosts of the scope where no definition applies. */
    val undefinedOn: List<HostKey>,
    /** Outcomes of molecule scenario hosts, shown on their own collapsed line (F8.10). */
    val molecule: List<OutcomeGroup>,
)

/** Where one definition takes effect, over every reachable (env, host, play) of its root (F8.2 "This definition"). */
data class DefinitionStatus(
    /** Hosts on which the definition wins. */
    val winsOn: List<HostKey>,
    /** Hosts that load the definition but where another one wins, with that winner. */
    val shadowedOn: Map<HostKey, VarSourceRef>,
    /** Why no host loads the definition at all ("app_platform is not a group of prod: this file is never loaded"), or null. */
    @Nls val notLoadedReason: String?,
)

/** How a [ChainStep] ended up in the effective value. */
enum class ChainOutcome {
    /** The definition that wins. */
    WINNER,

    /** Replaced by a later definition. */
    SHADOWED,

    /** Shadowed, but its dictionary still contributes keys under `hash_behaviour = merge`. */
    MERGED,
}

/** One definition in an "Explain precedence" chain. */
data class ChainStep(val source: VarSourceRef, val outcome: ChainOutcome)

/** What may replace a value only at runtime, so the static chain cannot know its value. */
enum class RuntimeMarkerKind(val layer: VarsLayer) {
    INCLUDE_VARS(VarsLayer.INCLUDE_VARS),
    SET_FACT(VarsLayer.SET_FACT_REGISTER),
    REGISTER(VarsLayer.SET_FACT_REGISTER),
}

/** "May be replaced at runtime by set_fact at …": a task that sets [name] while the play runs. */
data class RuntimeMarker(val name: String, val kind: RuntimeMarkerKind, val location: SourceLocation)

/**
 * The full ordered layer chain of one variable for one evaluation context (F8.2 "Explain precedence"): every
 * definition the context loads, lowest precedence first, so the last [ChainOutcome.WINNER] step is the effective one.
 */
data class PrecedenceChain(
    val name: String,
    val target: EvalTarget,
    /** The role whose task asks (it is re-applied last among role defaults and role vars), or null. */
    val runningRole: String?,
    val steps: List<ChainStep>,
    /** Tasks that may replace the value at runtime (`include_vars`, `set_fact`, `register`). */
    val runtimeMarkers: List<RuntimeMarker>,
    /** `vars_files` entries with a templated path: they may define the name, which is unknown statically. */
    val unknownSources: List<SourceLocation>,
) {
    /** The winning step, or null when no definition applies. */
    val winner: ChainStep? get() = steps.lastOrNull { it.outcome == ChainOutcome.WINNER }
}

/** One host of an environment as references see it (F8.8, F8.9; the X02 and tool-window badges). */
data class HostFacts(
    val key: HostKey,
    /** `ansible_host`, evaluated with the playbook dir when it is templated; null when unset (the name is the address). */
    val address: String?,
    /** `ansible_host` as written when it is a template (shown in grey next to [address]), else null. */
    val addressTemplate: String?,
    /** `group_names`: the host's groups without `all`, sorted as ansible-core sorts them. */
    val groupNames: List<String>,
    /** Other hosts of the same root with the same [address] ("1 of 7 names on 198.51.100.10"). */
    val sharesAddressWith: List<HostKey>,
)

/** One environment (or molecule scenario) as references see it: `groups` and the hosts with their facts. */
data class EnvironmentFacts(
    val environment: String,
    /** `groups`: every group of the environment (`all` and `ungrouped` included) with its hosts, children included, in inventory order. */
    val groups: Map<String, List<String>>,
    val hosts: List<HostFacts>,
)

/** Inventory facts of a [HostScope]'s environments, in environment order. */
data class InventoryFacts(val environments: List<EnvironmentFacts>) {
    fun environment(name: String): EnvironmentFacts? = environments.firstOrNull { it.environment == name }

    fun host(key: HostKey): HostFacts? = environment(key.environment)?.hosts?.firstOrNull { it.key == key }
}

/**
 * Where a role runs (F8.11): every (env, host, play) of its root that applies it, directly, as a `meta/main.yml`
 * dependency, or through `include_role`/`import_role`.
 */
data class RoleReach(
    val root: AnsibleRoot,
    val role: String,
    /** The evaluation contexts (each with its play and playbook dir), deduplicated, in env, inventory and play order. */
    val targets: List<EvalTarget>,
    /** The plays that apply the role, in play-graph order. */
    val plays: List<PlayRef>,
    /** A templated `hosts:` pattern makes the reach approximate ("approximate (templated pattern)"). */
    val approximate: Boolean,
    /** Why [targets] is empty ("no play of falcon applies totp-token"), or null. */
    @Nls val emptyReason: String?,
)

/**
 * The one Ansible context service (plan amendment R7/R8, A.14; implemented by WU HA1 in the host area,
 * `ansibility-host.xml`). Every result is scoped to one root (DEV.md rule 6).
 *
 * Presentation features (cards, completion tails, Ctrl+B labels, the tool window, the status bar, X75) follow the
 * selection through [hostScope] or [selectionScope]. Inspections never do (D32): they use [allHostsScope], so
 * switching env, host or play never changes a problem descriptor.
 *
 * Threading: every query needs a read action in smart mode and calls `ProgressManager.checkCanceled()`; nothing
 * here runs a process or decrypts a vault value. [setSelection] may be called from any thread.
 */
interface AnsibleContextService {
    /** The stored selection of [root] (nested playbook roots share their parent's env and host, but keep their own play). */
    fun selection(root: AnsibleRoot): RootContext

    /**
     * Stores [context] for [root] in the per-user workspace state, applying the selection rules (a host needs a named
     * env, a play needs a host or narrows All). Publishes `AnsibilitySettingsListener.workspaceStateChanged` and bumps
     * [selectionTracker], never [modelTracker].
     */
    fun setSelection(root: AnsibleRoot, context: RootContext)

    /**
     * The hosts [file] applies to at [offset] (or file level for a negative offset), intersected with the selection,
     * for presentation features. Cached per (file, offset bucket, selection, model stamps).
     */
    fun hostScope(file: VirtualFile, offset: Int = -1): HostScope

    /** The selection-free scope of [file] (selection All / All / Auto), for inspections (D32). */
    fun allHostsScope(file: VirtualFile): HostScope

    /** The scope of [context] in [root] without any file (origin [HostScopeOrigin.Selection]): tool window, R9's WS9. */
    fun selectionScope(root: AnsibleRoot, context: RootContext = selection(root)): HostScope

    /**
     * The per-host outcome of variable [name] over [scope]'s targets (single-name evaluation, `PrecedenceEngine.effectiveOf`).
     * Presentation: a role scope's Molecule companions ([EffectiveBreakdown.molecule]) are added only while "Show Molecule
     * in navigation and search" is on (plan amendment R20, D153).
     */
    fun effective(scope: HostScope, name: String): EffectiveBreakdown

    /**
     * Where [definition] takes effect over every reachable context of its root. Backed by the cached model; the root's
     * background summary gives the same statuses for inventory-level definitions. Presentation (cards, the all-repos
     * panel): Molecule hosts count only while "Show Molecule in navigation and search" is on (plan amendment R20, D153,
     * D156), so inspections never use it.
     */
    fun definitionStatus(definition: VarDefinition): DefinitionStatus

    /** `groups`, `group_names`, addresses and shared addresses of [scope]'s environments (InventoryView only). */
    fun inventoryFacts(scope: HostScope): InventoryFacts

    /** Where role [role] of [root] runs. */
    fun reach(root: AnsibleRoot, role: String): RoleReach

    /** The full InventoryView (levels 3–10) of [target]; null when its environment or host no longer exists. */
    fun inventoryView(target: EvalTarget): EffectiveVars?

    /** The full ExecutionView of [target] with [runningRole] applied last; null without a play or when the host is gone. */
    fun executionView(target: EvalTarget, runningRole: String?): EffectiveVars?

    /** The ordered chain of every definition of [name] that [target] loads ("Explain precedence"). */
    fun explain(target: EvalTarget, name: String): PrecedenceChain

    /** Bumped when a model input changes (inventories, var files, plays, roles); never by a selection change. */
    val modelTracker: ModificationTracker

    /** Bumped when any root's selection changes; only presentation caches depend on it. */
    val selectionTracker: ModificationTracker

    companion object {
        fun getInstance(project: Project): AnsibleContextService = project.service()
    }
}
