package de.terletzkiy.ansibility.semantics.inventory

import de.terletzkiy.ansibility.semantics.inventory.InventoryGraph.Companion.ALL
import de.terletzkiy.ansibility.semantics.inventory.InventoryGraph.Companion.UNGROUPED
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.semantics.yaml.SourceRange
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YValue
import java.math.BigInteger
import java.util.regex.Pattern

/**
 * Port of ansible-core's `InventoryData` together with the `Group` and `Host` mutations the inventory plugins drive
 * and the final `reconcile_inventory()`: the one model every static inventory parser writes into, so that several
 * sources of mixed formats merge in `-i` order exactly as `InventoryManager.parse_sources` merges them (hosts in the
 * order of their first appearance, the port set when a host is created, later definitions after earlier ones).
 *
 * [source] is the index of the file being parsed; it becomes [InventoryLocation.sourceIndex] of every location
 * recorded meanwhile. With [failFast], an [ProblemSeverity.ERROR] stops the current source with a [SourceFailure]
 * where ansible-core raises, leaving everything added before it in place (ansible-core keeps a failed source's
 * partial additions); without it the problem is recorded and parsing goes on, as the IDE-friendly
 * [YamlInventoryParser.parseAll] does.
 */
internal class InventoryBuilder(val failFast: Boolean = false) {
    /** A group under construction (`Group`). */
    class MGroup(val name: String) {
        var depth = 0
        var priority = 1
        val hosts = ArrayList<MHost>()
        val childGroups = ArrayList<MGroup>()
        val parentGroups = ArrayList<MGroup>()
        val sections = ArrayList<InlineVars>()
        val definitions = ArrayList<InventoryLocation>()
    }

    /** A host under construction (`Host`). */
    class MHost(val name: String) {
        val groups = ArrayList<MGroup>()
        val sections = ArrayList<InlineVars>()
        val definitions = ArrayList<InventoryLocation>()
    }

    /** One variable of an inline block, as a parser hands it over: the value with the range of its key. */
    class VarEntry(val key: String, val value: YValue, val keyRange: SourceRange?)

    /** Thrown in [failFast] mode where ansible-core raises; [problem] is already recorded in [problems]. */
    class SourceFailure(val problem: InventoryProblem) : RuntimeException(problem.message, null, false, false)

    val groups = LinkedHashMap<String, MGroup>()
    val hosts = LinkedHashMap<String, MHost>()
    val problems = ArrayList<InventoryProblem>()
    var source = 0

    init {
        addGroup(ALL, null)
        addGroup(UNGROUPED, null)
        addChild(ALL, UNGROUPED, null)
    }

    fun at(range: SourceRange?) = InventoryLocation(source, range)

    /** Records a problem; in [failFast] mode an [ProblemSeverity.ERROR] then ends the current source. */
    fun problem(severity: ProblemSeverity, message: String, range: SourceRange?) {
        val problem = InventoryProblem(severity, message, at(range))
        problems += problem
        if (failFast && severity == ProblemSeverity.ERROR) throw SourceFailure(problem)
    }

    // ------------------------------------------------------------------ InventoryData

    /** `InventoryData.add_group`; a [range] records where the source names the group. */
    fun addGroup(name: String, range: SourceRange?) {
        val existing = groups[name]
        if (existing != null) {
            if (range != null) existing.definitions += at(range)
            return
        }
        val group = MGroup(name)
        if (range != null) group.definitions += at(range)
        groups[name] = group
        if (INVALID_VARIABLE_NAME.matcher(name).find()) {
            // TRANSFORM_INVALID_GROUP_CHARS defaults to 'never': warn, keep the name.
            problem(
                ProblemSeverity.WEAK_WARNING,
                "Invalid characters were found in group name '$name' but not replaced",
                range,
            )
        }
    }

    /**
     * `InventoryData.add_host`; returns false when the host could not be added. [range] is the host pattern that
     * produced the host; a non-zero [port] becomes `ansible_port` only when the host is created (`Host(name, port)`).
     */
    fun addHost(name: String, group: String?, port: BigInteger?, range: SourceRange?): Boolean {
        if (name.isEmpty()) {
            problem(ProblemSeverity.ERROR, "Invalid empty host name provided", range)
            return false
        }
        val g = group?.let { groups[it] }
        var host = hosts[name]
        if (host == null) {
            host = MHost(name)
            hosts[name] = host
            if (port != null && port.signum() != 0) {
                // Host(name, port) sets ansible_port = int(port) when the host is first created (`if port:`).
                val scalar = YScalar(port.toString(), ScalarStyle.PLAIN, range = range)
                host.sections += InlineVars(
                    mapOf("ansible_port" to scalar),
                    range?.let { mapOf("ansible_port" to it) }.orEmpty(),
                    at(range),
                )
            }
        }
        host.definitions += at(range)
        if (g != null) groupAddHost(g, host)
        return true
    }

    /**
     * `InventoryData.set_variable` for every entry, as one inline block at [blockRange]: the entity is looked up as a
     * group first, then as a host. `ansible_group_priority` on a group sets its priority instead of a variable.
     * Entries are in Python dict order (a repeated key keeps its first position and takes the last value).
     */
    fun setVariables(entity: String, entries: List<VarEntry>, blockRange: SourceRange?) {
        val group = groups[entity]
        val host = if (group == null) hosts[entity] else null
        val values = LinkedHashMap<String, YValue>()
        val keyRanges = LinkedHashMap<String, SourceRange>()
        for (entry in entries) {
            if (group != null && entry.key == "ansible_group_priority") {
                setPriority(group, entry)
                continue
            }
            values[entry.key] = entry.value
            entry.keyRange?.let { keyRanges[entry.key] = it }
        }
        if (values.isEmpty()) return
        val section = InlineVars(values, keyRanges, at(blockRange))
        when {
            group != null -> group.sections += section
            host != null -> host.sections += section
        }
    }

    private fun setPriority(group: MGroup, entry: VarEntry) {
        val value = Py.toInt(entry.value)
        if (value == null || value.bitLength() > 31) {
            problem(
                ProblemSeverity.ERROR,
                "Invalid priority value '${(entry.value as? YScalar)?.text ?: Py.typeName(entry.value)}' for group '${group.name}'",
                entry.value.range ?: entry.keyRange,
            )
            return
        }
        group.priority = value.toInt()
    }

    /** `InventoryData.add_child`: adds a group or a host to [parent]. */
    fun addChild(parent: String, child: String, range: SourceRange?) {
        val p = groups[parent]
        if (p == null) {
            problem(ProblemSeverity.ERROR, "$parent is not a known group", range)
            return
        }
        val childGroup = groups[child]
        val childHost = hosts[child]
        when {
            childGroup != null -> addChildGroup(p, childGroup, range)
            childHost != null -> groupAddHost(p, childHost)
            else -> problem(ProblemSeverity.ERROR, "$child is not a known host nor group", range)
        }
    }

    // ------------------------------------------------------------------ Group / Host

    private fun ancestors(group: MGroup): LinkedHashSet<MGroup> {
        val seen = LinkedHashSet<MGroup>()
        var frontier: List<MGroup> = group.parentGroups
        while (frontier.isNotEmpty()) {
            val next = ArrayList<MGroup>()
            for (g in frontier) if (seen.add(g)) next += g.parentGroups
            frontier = next
        }
        return seen
    }

    private fun selfAndDescendants(group: MGroup): List<MGroup> {
        val ordered = LinkedHashSet<MGroup>()
        ordered += group
        var frontier = listOf(group)
        while (frontier.isNotEmpty()) {
            val next = ArrayList<MGroup>()
            for (g in frontier) for (c in g.childGroups) if (ordered.add(c)) next += c
            frontier = next
        }
        return ordered.toList()
    }

    private fun hostsOf(group: MGroup): List<MHost> {
        val out = LinkedHashSet<MHost>()
        for (g in selfAndDescendants(group)) out += g.hosts
        return out.toList()
    }

    /** `Group.add_child_group`. */
    private fun addChildGroup(parent: MGroup, child: MGroup, range: SourceRange?) {
        if (parent === child) {
            problem(ProblemSeverity.ERROR, "can't add group to itself: ${parent.name}", range)
            return
        }
        if (child in parent.childGroups) return
        val startAncestors = ancestors(child)
        val newAncestors = ancestors(parent)
        if (child in newAncestors) {
            problem(
                ProblemSeverity.ERROR,
                "Adding group '${child.name}' as child to '${parent.name}' creates a recursive dependency loop.",
                range,
            )
            return
        }
        newAncestors += parent
        newAncestors.removeAll(startAncestors)

        parent.childGroups += child
        child.depth = maxOf(parent.depth + 1, child.depth)
        checkChildrenDepth(child, range)

        if (child.parentGroups.none { it.name == parent.name }) {
            child.parentGroups += parent
            for (h in hostsOf(child)) for (g in newAncestors) if (g !in h.groups) h.groups += g
        }
    }

    /** `Group._check_children_depth`: pushes the new depth down to every descendant. */
    private fun checkChildrenDepth(group: MGroup, range: SourceRange?) {
        var depth = group.depth
        val startDepth = depth
        val seen = HashSet<MGroup>()
        var unprocessed: Set<MGroup> = LinkedHashSet(group.childGroups)
        while (unprocessed.isNotEmpty()) {
            seen += unprocessed
            depth += 1
            val next = LinkedHashSet<MGroup>()
            for (g in unprocessed) {
                if (g.depth < depth) {
                    g.depth = depth
                    next += g.childGroups
                }
            }
            unprocessed = next
            if (depth - startDepth > seen.size) {
                problem(ProblemSeverity.ERROR, "The group named '${group.name}' has a recursive dependency loop.", range)
                return
            }
        }
    }

    /** `Group.add_host` + `Host.add_group` (ancestors first). */
    private fun groupAddHost(group: MGroup, host: MHost) {
        if (host in group.hosts) return
        group.hosts += host
        for (g in ancestors(group)) if (g !in host.groups) host.groups += g
        if (group !in host.groups) host.groups += group
    }

    /** `Group.remove_host` + `Host.remove_group` (drops ancestors no other group of the host still needs). */
    private fun groupRemoveHost(group: MGroup, host: MHost) {
        if (!group.hosts.remove(host)) return
        hostRemoveGroup(host, group)
    }

    private fun hostRemoveGroup(host: MHost, group: MGroup) {
        if (!host.groups.remove(group)) return
        for (old in ancestors(group)) {
            if (old.name == ALL) continue
            if (host.groups.none { old in ancestors(it) }) hostRemoveGroup(host, old)
        }
    }

    // ------------------------------------------------------------------ reconcile_inventory

    fun reconcile() {
        source = 0
        val all = groups.getValue(ALL)
        val ungrouped = groups.getValue(UNGROUPED)
        for (group in groups.values.toList()) {
            if (group.name != ALL && ancestors(group).isEmpty()) addChildGroup(all, group, null)
        }
        for (host in hosts.values) {
            val mine = host.groups
            if (ungrouped in mine) {
                if (mine.any { it !== all && it !== ungrouped }) groupRemoveHost(ungrouped, host)
            } else if (mine.isEmpty() || (mine.size == 1 && all in mine)) {
                groupAddHost(ungrouped, host)
            }
        }
        for (name in groups.keys) {
            val host = hosts[name] ?: continue
            problems += InventoryProblem(
                ProblemSeverity.WARNING,
                "Found both group and host with same name: $name",
                host.definitions.firstOrNull() ?: InventoryLocation(0, null),
            )
        }
    }

    /** The inventory as it stands (call [reconcile] first, as `parse_sources` does after a parsed source). */
    fun build(): InventoryGraph = build(groups.values, hosts.values)

    /**
     * The inventory without [reconcile], as ansible-core shows it when no source parsed: `reconcile_inventory()`
     * never runs, so only what `all` reaches is visible (a failed source's groups without parents stay detached).
     */
    fun buildReachable(): InventoryGraph {
        val reachable = selfAndDescendants(groups.getValue(ALL)).toSet()
        val reachableHosts = LinkedHashSet<MHost>()
        for (group in groups.values) if (group in reachable) reachableHosts += group.hosts
        return build(groups.values.filter { it in reachable }, hosts.values.filter { it in reachableHosts }, reachable)
    }

    private fun build(groupList: Collection<MGroup>, hostList: Collection<MHost>, visible: Set<MGroup>? = null): InventoryGraph {
        fun shown(g: MGroup) = visible == null || g in visible
        return InventoryGraph(
            groups = groupList.associateTo(LinkedHashMap()) { g ->
                g.name to InventoryGraph.Group(
                    name = g.name,
                    parents = g.parentGroups.filter(::shown).map { it.name },
                    children = g.childGroups.map { it.name },
                    hosts = g.hosts.map { it.name },
                    varSections = g.sections.toList(),
                    depth = g.depth,
                    priority = g.priority,
                    definitions = g.definitions.toList(),
                )
            },
            hosts = hostList.associateTo(LinkedHashMap()) { h ->
                h.name to InventoryGraph.Host(
                    name = h.name,
                    groups = h.groups.filter(::shown).map { it.name },
                    varSections = h.sections.toList(),
                    definitions = h.definitions.toList(),
                )
            },
            problems = problems.toList(),
        )
    }

    private companion object {
        /** `C.INVALID_VARIABLE_NAMES`, which `to_safe_group_name` searches. */
        val INVALID_VARIABLE_NAME: Pattern = Pattern.compile("""^[\d\W]|[^\w]""", Pattern.UNICODE_CHARACTER_CLASS)
    }
}
