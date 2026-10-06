package de.terletzkiy.ansibility.semantics.inventory

import de.terletzkiy.ansibility.semantics.yaml.SourceRange
import de.terletzkiy.ansibility.semantics.yaml.YEmpty
import de.terletzkiy.ansibility.semantics.yaml.YEntry
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YValue

/**
 * Port of ansible-core's YAML inventory plugin (`plugins/inventory/yaml.py`), writing into the shared
 * [InventoryBuilder] (`InventoryData`, `Group`, `Host` and the final `reconcile_inventory()`):
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
 * Where ansible-core fails the whole source, the parser records an [ProblemSeverity.ERROR] and continues
 * ([parseAll]); [InventorySources] instead stops the source there, as ansible-core does.
 */
object YamlInventoryParser {
    /** Parses one inventory document (the loaded YAML of a `hosts.yml`). */
    fun parse(document: YValue?): InventoryGraph = parseAll(listOf(document))

    /** Parses several inventory sources into one inventory, in `-i` order (like `InventoryManager.parse_sources`). */
    fun parseAll(documents: List<YValue?>): InventoryGraph {
        val builder = InventoryBuilder()
        documents.forEachIndexed { index, document -> parseInto(builder, document, index) }
        builder.reconcile()
        return builder.build()
    }

    /** `InventoryModule.parse` for one loaded [document] as source [index] of [builder]. */
    internal fun parseInto(builder: InventoryBuilder, document: YValue?, index: Int) {
        builder.source = index
        YamlSource(builder).parse(document)
    }

    private val SECTIONS = listOf("vars", "children", "hosts")

    private class YamlSource(private val builder: InventoryBuilder) {
        private fun problem(severity: ProblemSeverity, message: String, range: SourceRange?) =
            builder.problem(severity, message, range)

        // ------------------------------------------------------------------ yaml.py

        fun parse(document: YValue?) {
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
            builder.addGroup(name, key.range)
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
                        builder.addChild(name, subgroup, child.key.range)
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
                if (!builder.addHost(host, group, expansion.port, key.range)) continue
                if (variables.entries.isNotEmpty()) setVariables(host, variables)
            }
        }

        /** `set_variable` for every key of [vars], in Python dict order (the last of a repeated key wins). */
        private fun setVariables(entity: String, vars: YMap) {
            val entries = Py.dict(vars).map { (key, entry) -> InventoryBuilder.VarEntry(key, entry.value, entry.key.range) }
            builder.setVariables(entity, entries, vars.range)
        }
    }
}
