package de.terletzkiy.ansibility.semantics.precedence

import de.terletzkiy.ansibility.semantics.inventory.InventoryGraph
import de.terletzkiy.ansibility.semantics.precedence.VarLayer.Family
import de.terletzkiy.ansibility.semantics.yaml.SourceRange
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YValue

/** One definition of a variable: the source it comes from and the value it gives there. */
data class SourcedValue(val name: String, val source: VarSource, val value: YValue) {
    /** Where the key is written in the source, when known. */
    val keyRange: SourceRange? get() = source.keyRanges[name]
}

/**
 * The effective value of one variable for one host, with the definitions it beat.
 *
 * [shadowed] lists every other definition from the highest precedence down (the runner-up first).
 * Under `hash_behaviour=merge`, [value] may be a dictionary merged from several definitions; [mergedFrom] lists the
 * shadowed definitions that still contributed keys to it (highest first). Under `replace` it is always empty.
 */
data class EffectiveVar(
    val name: String,
    val value: YValue,
    val winner: SourcedValue,
    val shadowed: List<SourcedValue>,
    val mergedFrom: List<SourcedValue> = emptyList(),
) {
    /** Every definition in the order ansible-core applies them (lowest precedence first, winner last). */
    val definitions: List<SourcedValue> get() = shadowed.asReversed() + winner
}

/**
 * The inventory-level variables of one host (levels 3–10): what `ansible-inventory -i … --playbook-dir … --list`
 * reports under `_meta.hostvars`, with every definition kept.
 */
class InventoryView internal constructor(
    val host: String,
    /** The host's groups without `all`, in `sort_groups` order (the order group variables are applied in). */
    val groups: List<String>,
    val vars: Map<String, EffectiveVar>,
    /** Every applicable source in the order ansible-core combines them. */
    val appliedSources: List<VarSource>,
    internal val nodes: List<PrecedenceEngine.Node>,
) {
    operator fun get(name: String): EffectiveVar? = vars[name]

    /** Just the effective values, in first-definition order. */
    fun values(): Map<String, YValue> = vars.mapValuesTo(LinkedHashMap()) { it.value.value }
}

/**
 * The variables a task on one host sees (levels 2–19 without facts and magic variables): role defaults below the
 * [inventory] view, then play vars, `vars_files`, role vars, block/task vars, `include_vars`, `set_fact`/`register`
 * markers, role params and extra vars on top, as `VariableManager.get_vars` layers them.
 */
class ExecutionView internal constructor(
    val host: String,
    val inventory: InventoryView,
    val vars: Map<String, EffectiveVar>,
    val appliedSources: List<VarSource>,
) {
    operator fun get(name: String): EffectiveVar? = vars[name]

    fun values(): Map<String, YValue> = vars.mapValuesTo(LinkedHashMap()) { it.value.value }
}

/**
 * The winners of one context's execution layers, per name ([PrecedenceEngine.executionIndex]): the definition in the
 * layers above the inventory levels that wins, and the winning role default. Which definition wins depends only on
 * precedence order, never on `hash_behaviour` (a merged dictionary keeps the highest definition as its winner).
 */
class ExecutionIndex internal constructor(
    private val above: Map<String, SourcedValue>,
    private val defaults: Map<String, SourcedValue>,
) {
    /** The winning definition of [name] above the inventory levels (play vars … extra vars), or null. */
    fun above(name: String): SourcedValue? = above[name]

    /** The winning role default of [name], or null. */
    fun defaults(name: String): SourcedValue? = defaults[name]

    /**
     * The winner of [name] for a task when [inventoryWinner] wins the inventory levels: a definition above them, else
     * the inventory winner, else the role default.
     */
    fun winnerOf(name: String, inventoryWinner: SourcedValue?): SourcedValue? = above[name] ?: inventoryWinner ?: defaults[name]

    /** Every name the execution layers define. */
    val names: Set<String> get() = above.keys + defaults.keys
}

/**
 * Port of `VariableManager.get_vars` for inventory and play variables.
 *
 * Inventory levels follow [precedence] (`VARIABLE_PRECEDENCE`, default [PrecedenceEntry.DEFAULT]):
 * `all_inventory` (inline `all` vars), `groups_inventory` (inline vars of the host's groups in `sort_groups` order),
 * `all_plugins_inventory` / `all_plugins_play` (`group_vars/all` next to the inventory / the playbook),
 * `groups_plugins_inventory` / `groups_plugins_play` (`group_vars/<group>`); then the host's inline vars,
 * inventory `host_vars` and playbook `host_vars`. Several files of one group load in [VarSource.order].
 *
 * Values are combined with [hashBehaviour]; because `merge_hash` is not associative, the engine reproduces
 * ansible-core's exact grouping (per inventory source, per group, per slot) instead of folding a flat list.
 */
class PrecedenceEngine(
    val hashBehaviour: HashBehaviour = HashBehaviour.REPLACE,
    val precedence: List<PrecedenceEntry> = PrecedenceEntry.DEFAULT,
) {
    /** A node of the combination tree; a fold combines its children left to right, starting from `{}`. */
    internal sealed interface Node

    internal class Leaf(val source: VarSource, val entries: Map<String, YValue>) : Node

    /** [shallow] folds use `dict |=` whatever the hash behaviour (block and task vars). */
    internal class Fold(val children: List<Node>, val shallow: Boolean = false) : Node

    /**
     * The inventory view of [host], or null when the host is not in [graph]. [sources] may contain any layers;
     * only inventory levels that apply to the host are used (`all`, the host's groups, the host itself).
     */
    fun inventoryView(graph: InventoryGraph, host: String, sources: List<VarSource>): InventoryView? {
        val h = graph.host(host) ?: return null
        val hostGroups = graph.sortedGroupsOf(host).map { it.name }
        val groupRank = hostGroups.withIndex().associate { (i, g) -> g to i }

        val all = ArrayList<VarSource>()
        val byGroup = ArrayList<Pair<Int, VarSource>>()
        val own = ArrayList<VarSource>()
        for (source in sources) {
            if (!source.layer.isInventoryLevel) continue
            when (val owner = normalise(source.owner)) {
                VarOwner.All -> all += source
                is VarOwner.Group -> groupRank[owner.name]?.let { byGroup += it to source }
                is VarOwner.Host -> if (owner.name == h.name) own += source
            }
        }

        fun ofFamily(list: List<VarSource>, family: Family) = list.filter { it.layer.family == family }
        fun groupedOfFamily(family: Family) = byGroup.filter { it.second.layer.family == family }

        val nodes = ArrayList<Node>()
        for (entry in precedence) {
            nodes += when (entry) {
                // all_group.get_vars(): the inline blocks of `all`, in the order they were set.
                PrecedenceEntry.ALL_INVENTORY -> fold(ofFamily(all, Family.INVENTORY_FILE).sortedWith(INLINE_ORDER))
                // get_group_vars(host_groups): one combine per group, groups in sort_groups order.
                PrecedenceEntry.GROUPS_INVENTORY -> Fold(
                    groupedOfFamily(Family.INVENTORY_FILE).groupBy({ it.first }, { it.second }).toSortedMap()
                        .values.map { fold(it.sortedWith(INLINE_ORDER)) },
                )
                PrecedenceEntry.ALL_PLUGINS_INVENTORY -> perSource(ofFamily(all, Family.INVENTORY_ADJACENT).map { 0 to it })
                PrecedenceEntry.ALL_PLUGINS_PLAY -> perSource(ofFamily(all, Family.PLAYBOOK_ADJACENT).map { 0 to it })
                PrecedenceEntry.GROUPS_PLUGINS_INVENTORY -> perSource(groupedOfFamily(Family.INVENTORY_ADJACENT))
                PrecedenceEntry.GROUPS_PLUGINS_PLAY -> perSource(groupedOfFamily(Family.PLAYBOOK_ADJACENT))
            }
        }
        // host.get_vars(), then _plugins_inventory([host]) and _plugins_play([host]).
        nodes += fold(ofFamily(own, Family.INVENTORY_FILE).sortedWith(INLINE_ORDER))
        nodes += perSource(ofFamily(own, Family.INVENTORY_ADJACENT).map { 0 to it })
        nodes += perSource(ofFamily(own, Family.PLAYBOOK_ADJACENT).map { 0 to it })

        val evaluated = evaluate(Fold(nodes))
        return InventoryView(h.name, hostGroups, evaluated, leaves(nodes), nodes)
    }

    /**
     * The execution view on top of [inventory]. Only execution layers of [sources] are used; a source owned by a
     * group applies when the host is in that group, one owned by a host only to that host.
     *
     * Within a layer, sources apply in ([VarSource.sourceIndex], [VarSource.order]) order, so role defaults and
     * role vars are given in play order with the running role last (ansible-core re-applies the task's own role).
     */
    fun executionView(inventory: InventoryView, sources: List<VarSource>): ExecutionView {
        val nodes = executionNodes(inventory, sources)
        return ExecutionView(inventory.host, inventory, evaluate(Fold(nodes)), leaves(nodes))
    }

    /**
     * The effective value of the single variable [name] for a task on [inventory]'s host: exactly
     * `executionView(inventory, sources)[name]`, but only [name] is evaluated (plan amendment R7/R8, A.14). Hover
     * cards and templated chains ask for one name per context instead of the ~200 of a full view.
     *
     * [sources] are the execution-layer sources of the context (role defaults, play vars, `vars_files`, role vars,
     * block/task vars, runtime markers, role params, extra vars), filtered and ordered exactly as [executionView]
     * does; inventory-level sources in it are ignored, because [inventory] already holds levels 3–10. Null when no
     * source defines [name] for the host.
     */
    fun effectiveOf(name: String, inventory: InventoryView, sources: List<VarSource>): EffectiveVar? =
        effective(Fold(executionNodes(inventory, sources)), name)

    /**
     * The execution-layer winners of [sources] for a task on [host], a member of [groups] (its groups without `all`),
     * precomputed per name ([ExecutionIndex]), so that a name's winner over any inventory view costs two map lookups:
     * `executionIndex(view.host, view.groups, sources).winnerOf(name, view[name]?.winner)` is the winner of
     * `effectiveOf(name, view, sources)`. Sources are filtered and ordered exactly as [executionView] does.
     */
    fun executionIndex(host: String, groups: Collection<String>, sources: List<VarSource>): ExecutionIndex {
        val memberOf = groups.toSet()
        val byLayer = sources
            .filter { source ->
                !source.layer.isInventoryLevel && when (val owner = normalise(source.owner)) {
                    VarOwner.All -> true
                    is VarOwner.Group -> owner.name in memberOf
                    is VarOwner.Host -> owner.name == host
                }
            }
            .groupBy { it.layer }
            .mapValues { (_, list) -> list.sortedWith(INLINE_ORDER) }
        fun index(layers: List<VarLayer>): Map<String, SourcedValue> {
            val out = HashMap<String, SourcedValue>()
            for (layer in layers) {
                for (source in byLayer[layer].orEmpty()) {
                    for ((name, value) in source.entries) {
                        if (layer == VarLayer.BLOCK_TASK_VARS && name in TASK_VARS_DROPPED) continue
                        out[name] = SourcedValue(name, source, value)
                    }
                }
            }
            return out
        }
        return ExecutionIndex(index(ABOVE_INVENTORY), index(listOf(VarLayer.ROLE_DEFAULTS)))
    }

    /** Convenience: [inventoryView] and [executionView] from one mixed list of sources. */
    fun executionView(graph: InventoryGraph, host: String, sources: List<VarSource>): ExecutionView? {
        val inventory = inventoryView(graph, host, sources) ?: return null
        return executionView(inventory, sources)
    }

    // ------------------------------------------------------------------ evaluation

    /** The combination tree of the execution view: role defaults below [inventory], the play and task inputs above. */
    private fun executionNodes(inventory: InventoryView, sources: List<VarSource>): List<Node> {
        val memberOf = inventory.groups.toSet()
        val applicable = sources.filter { source ->
            !source.layer.isInventoryLevel && when (val owner = normalise(source.owner)) {
                VarOwner.All -> true
                is VarOwner.Group -> owner.name in memberOf
                is VarOwner.Host -> owner.name == inventory.host
            }
        }
        val byLayer = applicable.groupBy { it.layer }.mapValues { (_, list) -> list.sortedWith(INLINE_ORDER) }
        fun leavesOf(layer: VarLayer): List<Node> = byLayer[layer].orEmpty().map { Leaf(it, it.entries) }

        val nodes = ArrayList<Node>()
        nodes += leavesOf(VarLayer.ROLE_DEFAULTS)
        nodes += inventory.nodes
        nodes += leavesOf(VarLayer.PLAY_VARS)
        nodes += leavesOf(VarLayer.VARS_FILES)
        nodes += leavesOf(VarLayer.ROLE_VARS)
        // task.get_vars(): parent blocks then the task, combined with `|=`, without `tags` and `when`.
        nodes += Fold(
            byLayer[VarLayer.BLOCK_TASK_VARS].orEmpty().map { Leaf(it, it.entries - TASK_VARS_DROPPED) },
            shallow = true,
        )
        nodes += leavesOf(VarLayer.INCLUDE_VARS)
        nodes += leavesOf(VarLayer.SET_FACT_REGISTER)
        nodes += leavesOf(VarLayer.ROLE_PARAMS)
        nodes += leavesOf(VarLayer.EXTRA_VARS)
        return nodes
    }

    private fun fold(sources: List<VarSource>): Fold = Fold(sources.map { Leaf(it, it.entries) })

    /** `get_vars_from_inventory_sources` / `_plugins_play`: one fold per source directory, files flat inside. */
    private fun perSource(ranked: List<Pair<Int, VarSource>>): Fold {
        val bySource = ranked.groupBy { it.second.sourceIndex }.toSortedMap()
        return Fold(
            bySource.values.map { list ->
                Fold(list.sortedWith(compareBy<Pair<Int, VarSource>> { it.first }.thenBy { it.second.order }).map { Leaf(it.second, it.second.entries) })
            },
        )
    }

    private fun leaves(nodes: List<Node>): List<VarSource> {
        val out = ArrayList<VarSource>()
        fun walk(node: Node) {
            when (node) {
                is Leaf -> out += node.source
                is Fold -> node.children.forEach(::walk)
            }
        }
        nodes.forEach(::walk)
        return out
    }

    /** Partial result for one key: its value, the definitions that produced it and every definition seen. */
    private class Partial(val value: YValue, val contributors: List<SourcedValue>, val definitions: List<SourcedValue>)

    private fun evaluate(root: Fold): Map<String, EffectiveVar> {
        val names = LinkedHashSet<String>()
        fun collect(node: Node) {
            when (node) {
                is Leaf -> names += node.entries.keys
                is Fold -> node.children.forEach(::collect)
            }
        }
        collect(root)
        val out = LinkedHashMap<String, EffectiveVar>()
        for (name in names) out[name] = effective(root, name) ?: continue
        return out
    }

    /** The effective value of [name] in the tree [root], or null when no leaf defines it. */
    private fun effective(root: Fold, name: String): EffectiveVar? {
        val partial = evaluate(root, name, shallow = false) ?: return null
        return EffectiveVar(
            name = name,
            value = partial.value,
            winner = partial.definitions.last(),
            shadowed = partial.definitions.dropLast(1).asReversed(),
            mergedFrom = partial.contributors.dropLast(1).asReversed(),
        )
    }

    private fun evaluate(node: Node, name: String, shallow: Boolean): Partial? = when (node) {
        is Leaf -> node.entries[name]?.let { value ->
            val definition = SourcedValue(name, node.source, value)
            Partial(value, listOf(definition), listOf(definition))
        }
        is Fold -> {
            var acc: Partial? = null
            for (child in node.children) {
                val next = evaluate(child, name, shallow || node.shallow) ?: continue
                acc = if (acc == null) next else combine(acc, next, shallow || node.shallow)
            }
            acc
        }
    }

    /** `combine_vars` restricted to one key: `merge_hash` merges two mappings, everything else is replaced. */
    private fun combine(lower: Partial, higher: Partial, shallow: Boolean): Partial {
        val definitions = lower.definitions + higher.definitions
        return if (!shallow && hashBehaviour == HashBehaviour.MERGE && lower.value is YMap && higher.value is YMap) {
            Partial(CombineVars.mergeHash(lower.value, higher.value), lower.contributors + higher.contributors, definitions)
        } else {
            Partial(higher.value, higher.contributors, definitions)
        }
    }

    private companion object {
        val INLINE_ORDER: Comparator<VarSource> = compareBy<VarSource> { it.sourceIndex }.thenBy { it.order }
        val TASK_VARS_DROPPED = setOf("tags", "when")

        /** The execution layers above the inventory levels, in the order [executionNodes] applies them. */
        val ABOVE_INVENTORY = listOf(
            VarLayer.PLAY_VARS, VarLayer.VARS_FILES, VarLayer.ROLE_VARS, VarLayer.BLOCK_TASK_VARS, VarLayer.INCLUDE_VARS,
            VarLayer.SET_FACT_REGISTER, VarLayer.ROLE_PARAMS, VarLayer.EXTRA_VARS,
        )

        fun normalise(owner: VarOwner): VarOwner =
            if (owner is VarOwner.Group && owner.name == InventoryGraph.ALL) VarOwner.All else owner
    }
}
