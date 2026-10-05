package de.terletzkiy.ansibility.context.host

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.EvalTarget
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.api.PlayRef
import de.terletzkiy.ansibility.api.RuntimeMarker
import de.terletzkiy.ansibility.api.RuntimeMarkerKind
import de.terletzkiy.ansibility.api.VarSourceRef
import de.terletzkiy.ansibility.index.JinjaBearing
import de.terletzkiy.ansibility.index.ValueSummary
import de.terletzkiy.ansibility.model.effective.ExecutionSources
import de.terletzkiy.ansibility.model.effective.SourceOrigin
import de.terletzkiy.ansibility.model.effective.VarSourceRefs
import de.terletzkiy.ansibility.model.role.RoleLayout
import de.terletzkiy.ansibility.semantics.precedence.EffectiveVar
import de.terletzkiy.ansibility.semantics.precedence.SourcedValue
import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.YEmpty
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.semantics.yaml.YVault

/**
 * What a definition's value loads as, for presentation (the tool window's Type column, WU HA7a). Derived from the
 * loaded YAML only: a `!vault` value is [VAULT] and is never decrypted, a string carrying Jinja markers is [TEMPLATE]
 * (its runtime type is only known when it renders).
 */
enum class ValueKind {
    STR, INT, FLOAT, BOOL, NULL, DATE, LIST, DICT, TEMPLATE, VAULT,

    /** A bare `=` scalar, which PyYAML cannot load. */
    UNLOADABLE,
    ;

    companion object {
        /** The kind of a loaded [value] (YAML 1.1 implicit typing; quoted and block scalars are strings). */
        fun of(value: YValue): ValueKind = when (value) {
            is YVault -> VAULT
            is YEmpty -> NULL
            is YSeq -> LIST
            is YMap -> DICT
            is YScalar -> when (val resolved = value.resolved) {
                Resolved.Null -> NULL
                is Resolved.Bool -> BOOL
                is Resolved.Int -> INT
                is Resolved.Float -> FLOAT
                is Resolved.Timestamp -> DATE
                Resolved.Unloadable -> UNLOADABLE
                is Resolved.Str -> if (value.tag != UNSAFE_TAG && JinjaBearing.hasTemplateMarkers(resolved.value)) TEMPLATE else STR
            }
        }

        private const val UNSAFE_TAG = "!unsafe"
    }
}

/** One definition of a full view: the API reference (vault-safe preview) and the [kind] of its value. */
class TypedSourceRef(val ref: VarSourceRef, val kind: ValueKind)

/**
 * One variable of a full view (WU HA7a): the definition that wins, the ones it shadows (runner-up first) and, under
 * `hash_behaviour = merge`, the shadowed ones whose dictionaries still contribute keys.
 */
class TypedEffectiveVar(
    val name: String,
    val winner: TypedSourceRef,
    val shadowed: List<TypedSourceRef>,
    val mergedFrom: List<TypedSourceRef>,
)

/**
 * Where one play runs (the public form of the context model's play hits): its matched hosts per environment in
 * environment order, by ansible-core's host pattern rules. [templated] marks a `hosts:` pattern that is a template, which
 * static evaluation cannot resolve; such a play matches nothing here. Implicit hosts (`localhost` when the inventory does
 * not define it) are left out.
 */
class PlayMatch(val play: PlayRef, val hostsByEnvironment: Map<String, List<String>>, val templated: Boolean) {
    /** The matched hosts of [environment], in inventory order (empty when the play matches none there). */
    fun hostsIn(environment: String): List<String> = hostsByEnvironment[environment].orEmpty()

    /** Whether the play runs on [host] of [environment]. */
    fun runsOn(environment: String, host: String): Boolean = host in hostsIn(environment)

    /** The number of hosts matched over every environment. */
    val hostCount: Int get() = hostsByEnvironment.values.sumOf { it.size }
}

/**
 * Small read APIs over the host context's model for presentation (WU HA7: the tool window's Effective vars, Targeted
 * by and matched hosts per environment). They answer from the same cached model and rules as
 * [de.terletzkiy.ansibility.api.AnsibleContextService] (play hits, inventory views, execution inputs), never depend on
 * the selection, never run a process and never decrypt: vault values stay value-free markers and previews follow the
 * one vault-safe rule ([addressHidden] applies it to host addresses). A read lock is taken when the caller holds none;
 * [runtimeMarkers] also needs smart mode.
 */
class HostContextReads private constructor(private val project: Project, private val impl: AnsibleContextServiceImpl) {
    private val model: ContextModel get() = impl.model

    /** The identity of [host] of [environment] in [root] (a nested root's hosts belong to its parent). */
    fun hostKey(root: AnsibleRoot, environment: String, host: String): HostKey = readLocked { model.hostKey(root, environment, host) }

    /** The root whose environments [root] uses: the parent of a nested playbook root, else [root] itself. */
    fun inventoryRoot(root: AnsibleRoot): AnsibleRoot = readLocked { model.inventoryRoot(root) }

    /** The playbook dir of contexts without a play: [root]'s own directory (null for a role library). */
    fun defaultPlaybookDir(root: AnsibleRoot): VirtualFile? = model.defaultPlaybookDir(root)

    /**
     * Every play of [root]'s family (the inventory root and the nested playbook roots inside it) that runs against its
     * environments, with its matched hosts, in play-graph order (playbooks by path, plays in file order). Molecule
     * playbooks run against their scenario and are not listed.
     */
    fun playMatches(root: AnsibleRoot): List<PlayMatch> = readLocked { model.playHits(root).map(::match) }

    /** The matches of [play] in [root]'s environments, computed on the spot for a play outside [playMatches]. */
    fun playMatch(root: AnsibleRoot, play: PlayRef): PlayMatch = readLocked { match(model.hitsOf(root, play)) }

    /** The plays of [root]'s family that run on [host], in [playMatches] order. */
    fun playsOn(root: AnsibleRoot, host: HostKey): List<PlayMatch> =
        playMatches(root).filter { it.runsOn(host.environment, host.host) }

    /**
     * The full typed view of [target] in [scopeRoot]: the InventoryView (levels 3–10) without a play, the ExecutionView
     * of a play-level task (role defaults and vars of the play's roles, play `vars:` and `vars_files` on top) with one.
     * Sorted by name; null when the target's environment or host no longer exists.
     */
    fun typedView(target: EvalTarget, scopeRoot: AnsibleRoot): List<TypedEffectiveVar>? = readLocked {
        val evaluation = impl.evaluator.evaluation(target, runningRole = null, scopeRoot = scopeRoot) ?: return@readLocked null
        val vars = if (target.play == null) {
            evaluation.host.view.vars
        } else {
            evaluation.host.engine.executionView(evaluation.host.view, evaluation.inputs.sources).vars
        }
        vars.values.sortedBy { it.name }.mapNotNull { effective ->
            ProgressManager.checkCanceled()
            typed(effective, evaluation.origins)
        }
    }

    /**
     * The tasks of [target]'s play that may replace each of [names] at runtime (`set_fact`, `register`, `include_vars`),
     * for names that have any. Empty without a play. Needs smart mode (the variable index).
     *
     * A `set_fact` or `register` in one of the play's role directories counts only in the role's `tasks/` and
     * `handlers/`: the playbooks of its molecule scenarios and tests never run with the role.
     */
    fun runtimeMarkers(target: EvalTarget, scopeRoot: AnsibleRoot, names: Collection<String>): Map<String, List<RuntimeMarker>> = readLocked {
        val play = target.play ?: return@readLocked emptyMap()
        val evaluation = impl.evaluator.evaluation(target, runningRole = null, scopeRoot = scopeRoot) ?: return@readLocked emptyMap()
        val sources = ExecutionSources.getInstance(project)
        val roleDirs = evaluation.inputs.roleDirs
        val markers = LinkedHashMap<String, List<RuntimeMarker>>()
        for (name in names) {
            ProgressManager.checkCanceled()
            val found = sources.runtimeMarkers(evaluation.root, evaluation.inputs, name).filter { runs(it, play.file, roleDirs) }
            if (found.isNotEmpty()) markers[name] = found
        }
        markers
    }

    /** Whether [marker] is in a task file that runs: the [playFile] itself, or a role's `tasks/` or `handlers/`. */
    private fun runs(marker: RuntimeMarker, playFile: VirtualFile, roleDirs: List<VirtualFile>): Boolean {
        if (marker.kind == RuntimeMarkerKind.INCLUDE_VARS) return true
        val file = marker.location.file
        if (file == playFile) return true
        val role = roleDirs.firstOrNull { VfsUtilCore.isAncestor(it, file, true) } ?: return true
        return VfsUtilCore.getRelativePath(file, role)?.substringBefore('/') in RUNNING_PARTS
    }

    /**
     * Whether the address of [host] must stay hidden by the one vault-safe preview rule ([ValueSummary.preview]), for
     * its `ansible_host` as the inventory facts evaluate it (the inventory view with [playbookDir]): the winning
     * definition is a `!vault` value or is written in a vault file (`host_vars/h1/vault.yml`), or it is a template that
     * names a `vault_*` variable or key, or whose variable's winning definition is hidden that way. Never decrypts.
     */
    fun addressHidden(host: HostKey, playbookDir: VirtualFile?, scopeRoot: AnsibleRoot): Boolean = readLocked {
        val evaluation = impl.evaluator.evaluation(EvalTarget(host, null, playbookDir), runningRole = null, scopeRoot = scopeRoot)
            ?: return@readLocked false
        val view = evaluation.host.view
        fun hidden(effective: EffectiveVar): Boolean {
            val file = evaluation.origins[effective.winner.source.originId]?.file
            return ValueSummary.isSecret(effective.name, effective.value) || file != null && ValueSummary.isVaultFileName(file.name)
        }
        val address = view[ANSIBLE_HOST] ?: return@readLocked false
        if (hidden(address)) return@readLocked true
        val template = (address.value as? YScalar)?.text?.takeIf(JinjaBearing::hasTemplateMarkers) ?: return@readLocked false
        if (VAULT_NAME.containsMatchIn(template)) return@readLocked true
        var secret = false
        InventoryFactsBuilder.evaluateSimple(template) { name -> view[name]?.also { secret = hidden(it) }?.value }
        secret
    }

    private fun match(hits: PlayHits) = PlayMatch(hits.play, hits.hostsByEnvironment, hits.templated)

    private fun typed(effective: EffectiveVar, origins: Map<String, SourceOrigin>): TypedEffectiveVar? {
        val winner = typed(effective.winner, origins) ?: return null
        return TypedEffectiveVar(
            name = effective.name,
            winner = winner,
            shadowed = effective.shadowed.mapNotNull { typed(it, origins) },
            mergedFrom = effective.mergedFrom.mapNotNull { typed(it, origins) },
        )
    }

    private fun typed(value: SourcedValue, origins: Map<String, SourceOrigin>): TypedSourceRef? =
        VarSourceRefs.ref(value, origins)?.let { TypedSourceRef(it, ValueKind.of(value.value)) }

    private fun <T> readLocked(action: () -> T): T =
        if (ApplicationManager.getApplication().isReadAccessAllowed) action() else runReadActionBlocking(action)

    companion object {
        private const val ANSIBLE_HOST = "ansible_host"

        /** The parts of a role whose tasks run with it. */
        private val RUNNING_PARTS = setOf(RoleLayout.TASKS, RoleLayout.HANDLERS)

        /** A `vault_*` variable or key anywhere in a template (`{{ vault_ip }}`, `{{ ips.vault_web }}`). */
        private val VAULT_NAME = Regex("""(?<![A-Za-z0-9_])vault_""")

        /** The reads of [project]'s context service, or null when it is not [AnsibleContextServiceImpl]. */
        fun getInstance(project: Project): HostContextReads? =
            AnsibleContextServiceImpl.getInstance(project)?.let { HostContextReads(project, it) }
    }
}
