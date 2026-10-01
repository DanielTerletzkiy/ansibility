package de.terletzkiy.ansibility.semantics.inventory

import de.terletzkiy.ansibility.semantics.yaml.SourceRange
import de.terletzkiy.ansibility.semantics.yaml.YValue

/** A place in one inventory source. [sourceIndex] is the position of the source in the parsed list (`-i` order). */
data class InventoryLocation(val sourceIndex: Int, val range: SourceRange?)

/**
 * One inline variable block of an inventory source: a group's `vars:` mapping, or the mapping under one host entry.
 *
 * Blocks are kept per definition site (instead of being folded into one map) so that precedence can report every
 * shadowed definition and apply `hash_behaviour=merge` exactly as `Group.set_variable`/`Host.set_variable` do.
 */
data class InlineVars(
    /** The variables in Python dict order (duplicate keys: first position, last value). */
    val entries: Map<String, YValue>,
    /** The range of each key scalar, for navigation. */
    val keyRanges: Map<String, SourceRange>,
    val location: InventoryLocation,
)

/** How serious an [InventoryProblem] is, in ansible-core's terms. */
enum class ProblemSeverity {
    /** ansible-core fails to parse the inventory source. */
    ERROR,

    /** ansible-core prints a warning and skips the offending part. */
    WARNING,

    /** ansible-core accepts it silently or only mentions it at high verbosity (e.g. unusual group names). */
    WEAK_WARNING,
}

/**
 * Something ansible-core would reject (ERROR: the YAML plugin fails the whole source) or warn about while parsing.
 * The parser records the problem and keeps going, so the IDE still sees the rest of the inventory.
 */
data class InventoryProblem(val severity: ProblemSeverity, val message: String, val location: InventoryLocation)

/**
 * The inventory as ansible-core's `InventoryData` holds it after parsing and `reconcile_inventory()`:
 * every group (including the implicit `all` and `ungrouped`) and every host, with the parent/child DAG,
 * group depth and priority, and the inline variables of the inventory file(s).
 *
 * Maps iterate in the order Ansible created the objects (`all`, `ungrouped`, then document order).
 */
class InventoryGraph(
    val groups: Map<String, Group>,
    val hosts: Map<String, Host>,
    val problems: List<InventoryProblem>,
) {
    /**
     * A group. [hosts] are the hosts listed directly under it; [children] and [parents] are group names in the
     * order the edges were added. [depth] is ansible-core's group depth (longest path from `all`, which is 0) and
     * [priority] is `ansible_group_priority` (default 1). Both feed [SortGroups].
     */
    data class Group(
        val name: String,
        val parents: List<String>,
        val children: List<String>,
        val hosts: List<String>,
        val varSections: List<InlineVars>,
        val depth: Int,
        val priority: Int,
        /** Every key occurrence that defines or references the group (top-level key or `children` entry). */
        val definitions: List<InventoryLocation>,
    ) {
        /** Inline variables folded with `hash_behaviour=replace` (the precedence engine applies `merge` itself). */
        val vars: Map<String, YValue> by lazy { foldReplace(varSections) }

        /** True for `all` and `ungrouped` when no inventory source mentions them. */
        val isImplicit: Boolean get() = definitions.isEmpty() && (name == ALL || name == UNGROUPED)
    }

    /**
     * A host. [groups] holds every group the host belongs to, ancestors and `all` included, in the order
     * ansible-core added them to `Host.groups`.
     */
    data class Host(
        val name: String,
        val groups: List<String>,
        val varSections: List<InlineVars>,
        /** Every host-pattern key that produced this host. */
        val definitions: List<InventoryLocation>,
    ) {
        /** Inline variables folded with `hash_behaviour=replace` (the precedence engine applies `merge` itself). */
        val vars: Map<String, YValue> by lazy { foldReplace(varSections) }

        /** `ansible_host` as written; may be a Jinja template such as `{{ host_ips['x'] }}`. */
        val ansibleHost: YValue? get() = vars["ansible_host"]

        /** The `group_names` magic variable: sorted group names without `all`. */
        val groupNames: List<String> get() = groups.filter { it != ALL }.sortedWith(Py.STRING_ORDER)
    }

    fun group(name: String): Group? = groups[name]

    fun host(name: String): Host? = hosts[name]

    /** Transitive parents of [group] (not including itself), nearest first. */
    fun ancestorsOf(group: String): Set<String> {
        val seen = LinkedHashSet<String>()
        var frontier = groups[group]?.parents.orEmpty()
        while (frontier.isNotEmpty()) {
            val next = ArrayList<String>()
            for (name in frontier) if (seen.add(name)) next += groups[name]?.parents.orEmpty()
            frontier = next
        }
        seen.remove(group)
        return seen
    }

    /** [group] followed by its transitive children, breadth first (`get_descendants(include_self, preserve_ordering)`). */
    fun selfAndDescendantsOf(group: String): List<String> {
        if (group !in groups) return emptyList()
        val ordered = LinkedHashSet<String>()
        ordered += group
        var frontier = listOf(group)
        while (frontier.isNotEmpty()) {
            val next = ArrayList<String>()
            for (name in frontier) for (child in groups[name]?.children.orEmpty()) if (ordered.add(child)) next += child
            frontier = next
        }
        return ordered.toList()
    }

    /** All hosts of [group] and its descendants, deduplicated (`Group.get_hosts()`). */
    fun hostsOf(group: String): List<String> {
        val result = LinkedHashSet<String>()
        for (name in selfAndDescendantsOf(group)) result += groups[name]?.hosts.orEmpty()
        return result.toList()
    }

    /**
     * The groups of [host] in `sort_groups` order (depth, priority, name). `all` is left out unless [includeAll],
     * matching `VariableManager.get_vars`, which applies `all` separately.
     */
    fun sortedGroupsOf(host: String, includeAll: Boolean = false): List<Group> {
        val names = hosts[host]?.groups.orEmpty()
        return SortGroups.sort(names.mapNotNull { groups[it] }.filter { includeAll || it.name != ALL })
    }

    companion object {
        const val ALL = "all"
        const val UNGROUPED = "ungrouped"

        /** An inventory with only the implicit `all` and `ungrouped` groups. */
        fun empty(): InventoryGraph = YamlInventoryParser.parseAll(emptyList())

        private fun foldReplace(sections: List<InlineVars>): Map<String, YValue> {
            val out = LinkedHashMap<String, YValue>()
            for (section in sections) out.putAll(section.entries)
            return out
        }
    }
}

/** ansible-core's `inventory.helpers.sort_groups`: order by (depth, priority, name). */
object SortGroups {
    val ORDER: Comparator<InventoryGraph.Group> =
        compareBy<InventoryGraph.Group> { it.depth }.thenBy { it.priority }.thenBy(Py.STRING_ORDER) { it.name }

    fun sort(groups: Collection<InventoryGraph.Group>): List<InventoryGraph.Group> = groups.sortedWith(ORDER)
}
