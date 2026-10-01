package de.terletzkiy.ansibility.semantics.inventory

import de.terletzkiy.ansibility.semantics.inventory.InventoryGraph.Companion.ALL
import de.terletzkiy.ansibility.semantics.inventory.InventoryGraph.Companion.UNGROUPED
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.semantics.yaml.SourceRange
import de.terletzkiy.ansibility.semantics.yaml.YEmpty
import de.terletzkiy.ansibility.semantics.yaml.YEntry
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YValue
import java.math.BigInteger
import java.util.regex.Pattern

/**
 * Port of ansible-core's YAML inventory plugin (`plugins/inventory/yaml.py`) together with the `InventoryData`,
 * `Group` and `Host` mutations it drives and the final `reconcile_inventory()`:
 *
 * - every top-level key is a group (groups are siblings of `all:`, not only under `all.children`);
 * - a group may have `hosts` (host pattern → vars or null), `vars` and `children` (nested groups at any depth,
 *   defined inline or elsewhere); a null or entirely commented-out section is skipped; a plain string section
 *   is shorthand for a one-key mapping;
 * - host keys are expanded like `_expand_hostpattern` (ranges, `:port`);
 * - `all` and `ungrouped` always exist; groups without parents become children of `all`, hosts without groups
 *   join `ungrouped`;
 * - `ansible_group_priority` in a group's `vars` sets the priority instead of a variable;
 * - variable values are kept as [YValue] with their ranges (a templated `ansible_host` stays a template).
 *
 * Where ansible-core fails the whole source, the parser records an [ProblemSeverity.ERROR] and continues.
 */
object YamlInventoryParser {
    /** Parses one inventory document (the loaded YAML of a `hosts.yml`). */
    fun parse(document: YValue?): InventoryGraph = parseAll(listOf(document))

    /** Parses several inventory sources into one inventory, in `-i` order (like `InventoryManager.parse_sources`). */
    fun parseAll(documents: List<YValue?>): InventoryGraph {
        val builder = Builder()
        documents.forEachIndexed { index, document -> builder.parseSource(document, index) }
        builder.reconcile()
        return builder.build()
    }

    private val INVALID_VARIABLE_NAME: Pattern = Pattern.compile("""^[\d\W]|[^\w]""", Pattern.UNICODE_CHARACTER_CLASS)
    private val SECTIONS = listOf("vars", "children", "hosts")

    private class MGroup(val name: String) {
        var depth = 0
        var priority = 1
        val hosts = ArrayList<MHost>()
        val childGroups = ArrayList<MGroup>()
        val parentGroups = ArrayList<MGroup>()
        val sections = ArrayList<InlineVars>()
        val definitions = ArrayList<InventoryLocation>()
    }

    private class MHost(val name: String) {
        val groups = ArrayList<MGroup>()
        val sections = ArrayList<InlineVars>()
        val definitions = ArrayList<InventoryLocation>()
    }

    private class Builder {
        val groups = LinkedHashMap<String, MGroup>()
        val hosts = LinkedHashMap<String, MHost>()
        val problems = ArrayList<InventoryProblem>()
        var source = 0

        init {
            addGroup(ALL, null)
            addGroup(UNGROUPED, null)
            addChild(ALL, UNGROUPED, null)
        }

        private fun at(range: SourceRange?) = InventoryLocation(source, range)

        private fun problem(severity: ProblemSeverity, message: String, range: SourceRange?) {
            problems += InventoryProblem(severity, message, at(range))
        }

        // ------------------------------------------------------------------ yaml.py

        fun parseSource(document: YValue?, index: Int) {
            source = index
            when {
                document == null || !Py.isTruthy(document) ->
                    problem(ProblemSeverity.ERROR, "Parsed empty YAML file", document?.range)
                document !is YMap -> problem(
                    ProblemSeverity.ERROR,
                    "YAML inventory has invalid structure, it should be a dictionary, got: ${Py.typeName(document)}",
                    document.range,
                )
                Py.isTruthy(Py.dict(document)["plugin"]?.value) ->
                    problem(ProblemSeverity.ERROR, "Plugin configuration YAML file, not YAML inventory", document.range)
                else -> for (entry in Py.dict(document).values) parseGroup(entry.key, entry.value)
            }
        }

        /** `_parse_group`; returns the group name (also when the definition was skipped, as ansible-core does). */
        private fun parseGroup(key: YScalar, data: YValue): String? {
            val name = groupName(key) ?: return null
            if (data !is YMap && !Py.isNone(data)) {
                problem(ProblemSeverity.WARNING, "Skipping '$name' as this is not a valid group definition", key.range)
                return name
            }
            addGroup(name, key.range)
            if (data !is YMap) return name

            val entries = Py.dict(data)
            for (section in SECTIONS) {
                val entry = entries[section] ?: continue
                val value = entry.value
                if (Py.isStr(value)) {
                    // "convert strings to dicts as these are allowed"
                    val scalar = value as YScalar
                    entries[section] = YEntry(entry.key, YMap(listOf(YEntry(scalar, YEmpty(scalar.range))), scalar.range))
                } else if (value !is YMap && !Py.isNone(value)) {
                    problem(
                        ProblemSeverity.ERROR,
                        "Invalid \"$section\" entry for \"$name\" group, requires a dictionary, found \"${Py.typeName(value)}\" instead.",
                        entry.key.range,
                    )
                    entries.remove(section)
                }
            }

            for ((keyText, entry) in entries) {
                val value = entry.value
                if (value !is YMap && !Py.isNone(value)) {
                    problem(
                        ProblemSeverity.WARNING,
                        "Skipping key ($keyText) in group ($name) as it is not a mapping, it is a ${Py.typeName(value)}",
                        entry.key.range,
                    )
                    continue
                }
                if (value !is YMap) continue // "Skipping empty key"
                when (keyText) {
                    "vars" -> setVariables(name, value)
                    "children" -> for (child in Py.dict(value).values) {
                        val subgroup = parseGroup(child.key, child.value) ?: continue
                        addChild(name, subgroup, child.key.range)
                    }
                    "hosts" -> for (hostEntry in Py.dict(value).values) parseHost(name, hostEntry)
                    else -> problem(
                        ProblemSeverity.WARNING,
                        "Skipping unexpected key ($keyText) in group ($name), only \"vars\", \"children\" and \"hosts\" are valid",
                        entry.key.range,
                    )
                }
            }
            return name
        }

        /** `InventoryData.add_group`'s checks: a falsy name, then a non-string name, are errors. */
        private fun groupName(key: YScalar): String? {
            if (!Py.isTruthy(key)) {
                problem(ProblemSeverity.ERROR, "Unable to add group ${key.text}: Invalid empty/false group name provided", key.range)
                return null
            }
            if (!Py.isStr(key)) {
                problem(
                    ProblemSeverity.ERROR,
                    "Unable to add group ${key.text}: Invalid group name supplied, expected a string but got ${Py.typeName(key)}",
                    key.range,
                )
                return null
            }
            return key.text
        }

        /** `_parse_host` + `_populate_host_vars`. */
        private fun parseHost(group: String, entry: YEntry) {
            val key = entry.key
            if (!Py.isStr(key)) {
                problem(
                    ProblemSeverity.ERROR,
                    "Host pattern ${key.text} must be a string. Enclose integers/floats in quotation marks.",
                    key.range,
                )
                return
            }
            val expansion = try {
                HostRanges.expandHostPattern(key.text)
            } catch (e: HostRangeException) {
                problem(ProblemSeverity.ERROR, "Invalid host pattern '${key.text}': ${e.message}", key.range)
                return
            }
            val value = entry.value
            val variables: YMap = when {
                value is YMap -> value
                !Py.isTruthy(value) -> YMap(emptyList(), value.range) // `group_data[key][host_pattern] or {}`
                else -> {
                    problem(
                        ProblemSeverity.ERROR,
                        "Invalid data from file, expected dictionary and got: ${Py.typeName(value)}",
                        value.range ?: key.range,
                    )
                    return
                }
            }
            for (host in expansion.hosts) {
                if (!addHost(host, group, expansion.port, key)) continue
                if (variables.entries.isNotEmpty()) setVariables(host, variables)
            }
        }

        // ------------------------------------------------------------------ InventoryData

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

        /** `InventoryData.add_host`; returns false when the host could not be added. */
        private fun addHost(name: String, group: String?, port: BigInteger?, key: YScalar): Boolean {
            if (name.isEmpty()) {
                problem(ProblemSeverity.ERROR, "Invalid empty host name provided", key.range)
                return false
            }
            val g = group?.let { groups[it] }
            var host = hosts[name]
            if (host == null) {
                host = MHost(name)
                hosts[name] = host
                if (port != null && port.signum() != 0) {
                    // Host(name, port) sets ansible_port = int(port) when the host is first created (`if port:`).
                    val scalar = YScalar(port.toString(), ScalarStyle.PLAIN, range = key.range)
                    host.sections += InlineVars(
                        mapOf("ansible_port" to scalar),
                        key.range?.let { mapOf("ansible_port" to it) }.orEmpty(),
                        at(key.range),
                    )
                }
            }
            host.definitions += at(key.range)
            if (g != null) groupAddHost(g, host)
            return true
        }

        /**
         * `InventoryData.set_variable` for every key of [vars]: the entity is looked up as a group first, then as
         * a host. `ansible_group_priority` on a group sets its priority.
         */
        private fun setVariables(entity: String, vars: YMap) {
            val group = groups[entity]
            val host = if (group == null) hosts[entity] else null
            val entries = LinkedHashMap<String, YValue>()
            val keyRanges = LinkedHashMap<String, SourceRange>()
            for ((key, entry) in Py.dict(vars)) {
                if (group != null && key == "ansible_group_priority") {
                    setPriority(group, entry)
                    continue
                }
                entries[key] = entry.value
                entry.key.range?.let { keyRanges[key] = it }
            }
            if (entries.isEmpty()) return
            val section = InlineVars(entries, keyRanges, at(vars.range))
            when {
                group != null -> group.sections += section
                host != null -> host.sections += section
            }
        }

        private fun setPriority(group: MGroup, entry: YEntry) {
            val value = Py.toInt(entry.value)
            if (value == null || value.bitLength() > 31) {
                problem(
                    ProblemSeverity.ERROR,
                    "Invalid priority value '${(entry.value as? YScalar)?.text ?: Py.typeName(entry.value)}' for group '${group.name}'",
                    entry.value.range ?: entry.key.range,
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

        fun build(): InventoryGraph = InventoryGraph(
            groups = groups.mapValuesTo(LinkedHashMap()) { (_, g) ->
                InventoryGraph.Group(
                    name = g.name,
                    parents = g.parentGroups.map { it.name },
                    children = g.childGroups.map { it.name },
                    hosts = g.hosts.map { it.name },
                    varSections = g.sections.toList(),
                    depth = g.depth,
                    priority = g.priority,
                    definitions = g.definitions.toList(),
                )
            },
            hosts = hosts.mapValuesTo(LinkedHashMap()) { (_, h) ->
                InventoryGraph.Host(
                    name = h.name,
                    groups = h.groups.map { it.name },
                    varSections = h.sections.toList(),
                    definitions = h.definitions.toList(),
                )
            },
            problems = problems.toList(),
        )
    }
}
