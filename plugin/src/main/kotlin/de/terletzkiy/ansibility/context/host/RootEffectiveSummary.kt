package de.terletzkiy.ansibility.context.host

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.EvalTarget
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.api.PlayRef
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VarsLayer
import de.terletzkiy.ansibility.model.effective.SourceOrigin
import de.terletzkiy.ansibility.model.effective.VarSourceRefs
import de.terletzkiy.ansibility.semantics.inventory.InventoryGraph
import de.terletzkiy.ansibility.semantics.precedence.ExecutionIndex
import de.terletzkiy.ansibility.semantics.precedence.SourcedValue
import de.terletzkiy.ansibility.semantics.precedence.VarOwner
import de.terletzkiy.ansibility.semantics.yaml.YVault

/**
 * One definition a [RootEffectiveSummary] knows: where it is written, its layer and owner, never its value. Consumers
 * that show a value join it with the definition's `VarDefinition` (index preview) or `VarSourceRef` by [location], so the
 * one vault-safe preview rule stays where it is.
 */
class SummaryDefinition internal constructor(
    /** The index in the summary's definition table, or -1 for a definition only a running-role query met. */
    val id: Int,
    val name: String,
    val location: SourceLocation,
    val layer: VarsLayer,
    /** The owning group of an inventory-level group definition (`all` included); null otherwise. */
    val group: String?,
    /** The owning host of an inventory-level host definition; null otherwise. */
    val host: String?,
    /** The role of an execution-layer definition (role defaults and vars, entry vars, params). */
    val role: String?,
    /** The play of an execution-layer definition. */
    val play: PlayRef?,
    /** Whether the value is a `!vault` envelope (a flag only; the envelope is never kept). */
    val isVault: Boolean,
) {
    override fun toString(): String = "SummaryDefinition($name ${layer.name} ${location.file.name}@${location.offset})"
}

/** Where one definition takes effect over the contexts of a [RootEffectiveSummary] ([RootEffectiveSummary.status]). */
class SummaryStatus internal constructor(
    val definition: SummaryDefinition,
    /** Hosts on which the definition wins in at least one context. */
    val winsOn: List<HostKey>,
    /** Hosts that load the definition but where another one wins in every context, with that winner. */
    val shadowedOn: Map<HostKey, SummaryDefinition>,
)

/**
 * The background summary of one root's effective values (plan amendment R7/R8, A.9 change 3): for completion's hot path
 * and the P001/P002 checks, without evaluating anything on the EDT or per keystroke.
 *
 * - **Contexts** are every reachable (env, host, play) of the root's inventory, deduplicated by (host, playbook dir, role
 *   list) for plays without inputs of their own, plus an inventory-only context for each host no play reaches. Plays are
 *   evaluated for play-level tasks (no running role). Molecule scenarios are no contexts: their few hosts are evaluated
 *   on demand ([winner] answers for them too).
 * - **Definitions** are interned: one [SummaryDefinition] per (source, name), holding locations and layers only.
 * - **Statistics** per definition: the hosts it wins on and the hosts that load it while another definition wins there
 *   ([status], [neverWinning]: the P001/P002 candidates), like `AnsibleContextService.definitionStatus` over the
 *   summary's contexts.
 * - **Winners** of any name for any target ([winner]) come from the target's cached inventory view and the precomputed
 *   execution index of its (play, running role) inputs: two map lookups, so a completion list asks for hundreds of
 *   tails at no measurable cost.
 *
 * It is a [de.terletzkiy.ansibility.model.inventory.ModelCache] value of [RootEffectiveSummaries], current exactly
 * while every view and input it was built from is; never mutated, safe to share between threads. Queries need a read
 * action.
 */
class RootEffectiveSummary internal constructor(
    /** The inventory root (a nested root shares its parent's summary). */
    val root: AnsibleRoot,
    /** The evaluated contexts, in environment, inventory and play order. */
    val contexts: List<EvalTarget>,
    /** Every host of the contexts, in context order. */
    val hosts: List<HostKey>,
    private val definitions: List<SummaryDefinition>,
    private val ids: Map<SourceKey, Int>,
    private val byLocation: Map<LocationKey, Int>,
    private val byName: Map<String, IntArray>,
    private val winsOn: Array<IntArray>,
    private val shadowedOn: Array<IntArray>,
    private val shadowedBy: Array<IntArray>,
    /** Engine and model time of the build, in nanoseconds. */
    val buildNanos: Long,
    private val resolver: Resolver,
) {
    /** A definition by the id of its engine source, its name and its key offset (inline blocks of one file share an id). */
    internal data class SourceKey(val originId: String, val name: String, val offset: Int) {
        companion object {
            fun of(value: SourcedValue) = SourceKey(value.source.originId, value.name, value.keyRange?.start ?: -1)
        }
    }

    /** A definition by where its key is written. */
    internal data class LocationKey(val file: VirtualFile, val offset: Int, val name: String)

    /** Reads the cached view and execution inputs a [winner] query needs (bound to the project's services). */
    internal fun interface Resolver {
        fun evaluation(target: EvalTarget, runningRole: String?): Evaluation?
    }

    /** The number of interned definitions. */
    val definitionCount: Int get() = definitions.size

    /** Every interned definition of [name]. */
    fun definitions(name: String): List<SummaryDefinition> = byName[name]?.map { definitions[it] }.orEmpty()

    /** The interned definition of [name] whose key is written at [location], or null when no context loads it. */
    fun definitionAt(location: SourceLocation, name: String): SummaryDefinition? =
        byLocation[LocationKey(location.file, location.offset, name)]?.let(definitions::get)

    /** Where the definition of [name] at [location] takes effect, or null when no context of the root loads it. */
    fun status(location: SourceLocation, name: String): SummaryStatus? {
        val id = byLocation[LocationKey(location.file, location.offset, name)] ?: return null
        return statusOf(id)
    }

    /** Definitions that some context loads but none lets win: the candidates of P001 (and, with an equal value, P002). */
    fun neverWinning(): List<SummaryDefinition> =
        definitions.indices.filter { winsOn[it].isEmpty() && shadowedOn[it].isNotEmpty() }.map { definitions[it] }

    /** The status of every interned definition, in table order. */
    fun statuses(): List<SummaryStatus> = definitions.indices.map(::statusOf)

    /** [neverWinning] with the hosts and the winners that shadow each one. */
    fun neverWinningStatuses(): List<SummaryStatus> = neverWinning().map { statusOf(it.id) }

    /**
     * The definition of [name] that wins for a task of [runningRole] (null: a play-level task) in [target], or null when
     * nothing defines it there or the target's host is gone. Exactly the winner of `AnsibleContextService.explain` and of
     * `PrecedenceEngine.effectiveOf`; definitions only a running role brings in (a private `include_role`'s defaults) are
     * returned with id -1.
     */
    fun winner(name: String, target: EvalTarget, runningRole: String? = null): SummaryDefinition? =
        resolver.evaluation(target, runningRole)?.let { winnerIn(name, it, it.inputs.index(it.host.engine, it.host.view.host, it.host.view.groups)) }

    /** [winner] for each of [targets], in order. */
    fun winners(name: String, targets: List<EvalTarget>, runningRole: String? = null): List<SummaryDefinition?> = scope(targets, runningRole).winners(name)

    /**
     * [targets] resolved once for many names (completion asks for hundreds): each [Scope.winners] call is then a few map
     * lookups per target. Targets whose host is gone answer null.
     */
    fun scope(targets: List<EvalTarget>, runningRole: String? = null): Scope {
        val resolved = targets.map { target ->
            ProgressManager.checkCanceled()
            resolver.evaluation(target, runningRole)?.let { it to it.inputs.index(it.host.engine, it.host.view.host, it.host.view.groups) }
        }
        return Scope(targets, resolved)
    }

    /** Targets resolved for repeated [winners] queries ([scope]); valid while the summary is current. */
    inner class Scope internal constructor(
        val targets: List<EvalTarget>,
        private val resolved: List<Pair<Evaluation, ExecutionIndex>?>,
    ) {
        /** The winner of [name] in each target, in target order (null: undefined there, or the host is gone). */
        fun winners(name: String): List<SummaryDefinition?> = resolved.map { it?.let { (evaluation, index) -> winnerIn(name, evaluation, index) } }
    }

    private fun winnerIn(name: String, evaluation: Evaluation, index: ExecutionIndex): SummaryDefinition? {
        val host = evaluation.host
        val inventoryWinner = host.view[name]?.winner
        index.above(name)?.let { return definitionOf(it, evaluation.inputs.origins) }
        if (inventoryWinner != null) return definitionOf(inventoryWinner, host.origins)
        return index.defaults(name)?.let { definitionOf(it, evaluation.inputs.origins) }
    }

    /**
     * An estimate of the resident size in bytes: the definition table and its maps, the per-definition host arrays and
     * the context lists (object headers and references counted at 16 and 4 bytes, strings shared with the views).
     */
    fun estimatedBytes(): Long {
        val definitionBytes = definitions.size.toLong() * (DEFINITION_BYTES + 3 * MAP_ENTRY_BYTES)
        val arrayBytes = (winsOn.asSequence() + shadowedOn.asSequence() + shadowedBy.asSequence()).sumOf { ARRAY_HEADER + 4L * it.size }
        val nameBytes = byName.values.sumOf { MAP_ENTRY_BYTES + ARRAY_HEADER + 4L * it.size }
        val contextBytes = (contexts.size + hosts.size).toLong() * CONTEXT_BYTES
        return definitionBytes + arrayBytes + nameBytes + contextBytes
    }

    private fun statusOf(id: Int): SummaryStatus {
        val shadowed = LinkedHashMap<HostKey, SummaryDefinition>()
        shadowedOn[id].forEachIndexed { i, host -> shadowed[hosts[host]] = definitions[shadowedBy[id][i]] }
        return SummaryStatus(definitions[id], winsOn[id].map { hosts[it] }, shadowed)
    }

    private fun definitionOf(value: SourcedValue, origins: Map<String, SourceOrigin>): SummaryDefinition? =
        ids[SourceKey.of(value)]?.let(definitions::get) ?: summaryDefinition(-1, value, origins)

    internal companion object {
        private const val DEFINITION_BYTES = 64L
        private const val MAP_ENTRY_BYTES = 48L
        private const val ARRAY_HEADER = 16L
        private const val CONTEXT_BYTES = 64L

        /** The summary form of an engine definition, or null when its source has no known origin. */
        fun summaryDefinition(id: Int, value: SourcedValue, origins: Map<String, SourceOrigin>): SummaryDefinition? {
            val origin = origins[value.source.originId] ?: return null
            val owner = value.source.owner
            val inventoryLevel = value.source.layer.isInventoryLevel
            return SummaryDefinition(
                id = id,
                name = value.name,
                location = SourceLocation(origin.file, value.keyRange?.start ?: 0),
                layer = VarSourceRefs.layerOf(value.source.layer),
                group = if (!inventoryLevel) null else when (owner) {
                    VarOwner.All -> InventoryGraph.ALL
                    is VarOwner.Group -> owner.name
                    is VarOwner.Host -> null
                },
                host = (owner as? VarOwner.Host)?.name?.takeIf { inventoryLevel },
                role = origin.role,
                play = origin.play,
                isVault = value.value is YVault,
            )
        }
    }
}

/** Builds a [RootEffectiveSummary] from the cached model (call inside a read action; cancellable). */
internal object SummaryBuilder {
    fun build(root: AnsibleRoot, model: ContextModel, evaluator: ContextEvaluator): RootEffectiveSummary {
        val start = System.nanoTime()
        val owner = model.inventoryRoot(root)
        val hits = model.playHits(owner)
        val targets = LinkedHashSet<EvalTarget>()
        val reached = HashSet<HostKey>()
        for (hit in hits) {
            for ((environment, hosts) in hit.hostsByEnvironment) {
                for (host in hosts) {
                    ProgressManager.checkCanceled()
                    val key = model.hostKey(owner, environment, host)
                    reached += key
                    targets += EvalTarget(key, hit.play, hit.play.playbookDir)
                }
            }
        }
        for (environment in model.environments(owner)) {
            for (host in environment.graph.hosts.keys) {
                val key = model.hostKey(owner, environment.name, host)
                if (key !in reached) targets += EvalTarget(key, null, model.defaultPlaybookDir(owner))
            }
        }
        val contexts = model.deduplicate(model.sorted(owner, targets, hits.map { it.play }))

        val tables = Tables()
        for (context in contexts) {
            ProgressManager.checkCanceled()
            val evaluation = evaluator.evaluation(context, runningRole = null, scopeRoot = owner) ?: continue
            val host = tables.host(context.host)
            val view = evaluation.host.engine.executionView(evaluation.host.view, evaluation.inputs.sources)
            for (effective in view.vars.values) {
                val winner = tables.intern(effective.winner, evaluation.origins) ?: continue
                tables.wins[winner] += host
                for (shadow in effective.shadowed) {
                    val id = tables.intern(shadow, evaluation.origins) ?: continue
                    if (id != winner) tables.shadowed[id].putIfAbsent(host, winner)
                }
            }
        }
        val resolver = RootEffectiveSummary.Resolver { target, runningRole -> evaluator.evaluation(target, runningRole, owner) }
        return tables.summary(owner, contexts, System.nanoTime() - start, resolver)
    }

    /** The tables of one build: interned definitions, hosts, and per definition the hosts it wins on and is shadowed on. */
    private class Tables {
        val definitions = ArrayList<SummaryDefinition>()
        val ids = HashMap<RootEffectiveSummary.SourceKey, Int>()
        val byLocation = HashMap<RootEffectiveSummary.LocationKey, Int>()
        val byName = HashMap<String, MutableList<Int>>()
        val wins = ArrayList<LinkedHashSet<Int>>()
        val shadowed = ArrayList<LinkedHashMap<Int, Int>>()
        val hosts = LinkedHashMap<HostKey, Int>()

        fun host(key: HostKey): Int = hosts.getOrPut(key) { hosts.size }

        fun intern(value: SourcedValue, origins: Map<String, SourceOrigin>): Int? {
            val key = RootEffectiveSummary.SourceKey.of(value)
            ids[key]?.let { return it }
            val definition = RootEffectiveSummary.summaryDefinition(definitions.size, value, origins) ?: return null
            ids[key] = definition.id
            definitions += definition
            byLocation.putIfAbsent(RootEffectiveSummary.LocationKey(definition.location.file, definition.location.offset, definition.name), definition.id)
            byName.getOrPut(definition.name) { ArrayList() } += definition.id
            wins.add(LinkedHashSet())
            shadowed.add(LinkedHashMap())
            return definition.id
        }

        fun summary(root: AnsibleRoot, contexts: List<EvalTarget>, nanos: Long, resolver: RootEffectiveSummary.Resolver): RootEffectiveSummary {
            // A host on which a definition wins in one context is not "shadowed" there, whatever other contexts say.
            for (id in definitions.indices) shadowed[id].keys.removeAll(wins[id])
            return RootEffectiveSummary(
                root = root,
                contexts = contexts,
                hosts = hosts.keys.toList(),
                definitions = definitions.toList(),
                ids = ids,
                byLocation = byLocation,
                byName = byName.mapValues { it.value.toIntArray() },
                winsOn = Array(definitions.size) { wins[it].toIntArray() },
                shadowedOn = Array(definitions.size) { shadowed[it].keys.toIntArray() },
                shadowedBy = Array(definitions.size) { shadowed[it].values.toIntArray() },
                buildNanos = nanos,
                resolver = resolver,
            )
        }
    }
}
