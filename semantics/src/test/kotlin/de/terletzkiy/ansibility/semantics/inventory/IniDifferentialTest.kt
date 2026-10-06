package de.terletzkiy.ansibility.semantics.inventory

import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.precedence.oracle.MiniJson
import de.terletzkiy.ansibility.semantics.precedence.oracle.OracleCompare
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * [IniInventoryParser] against the real `ansible-inventory -i <case> --list --export` for the INI edge cases under
 * `ini-differential/` (written by `tools/docgen/layout-oracle/ini_differential.py`): whether the file is rejected,
 * and the groups, their hosts, children, priorities and variables, and every host's variables with their types.
 */
class IniDifferentialTest {
    @Suppress("UNCHECKED_CAST")
    private val expected: Map<String, Any?> by lazy {
        MiniJson.parse(resource("expected.json")) as Map<String, Any?>
    }

    private val core: CoreVersion by lazy { requireNotNull(CoreVersion.parse(expected["ansible_core"] as String)) }

    @Suppress("UNCHECKED_CAST")
    private val cases: Map<String, Map<String, Any?>> by lazy { expected["cases"] as Map<String, Map<String, Any?>> }

    private fun resource(name: String): String =
        requireNotNull(javaClass.getResource("/ini-differential/$name")) { "ini-differential/$name missing" }.readText()

    @Test
    fun `the cases were recorded on a core with the modern value handling`() {
        assertEquals(67, cases.size)
        assertTrue(core >= IniInventoryParser.TYPED_VALUES_SINCE, core.toString())
    }

    @TestFactory
    fun `every case parses like ini py`(): List<DynamicTest> = cases.map { (name, record) ->
        DynamicTest.dynamicTest(name) {
            val mismatches = mismatches(IniInventoryParser.parse(resource("cases/$name.ini"), core), record)
            assertTrue(mismatches.isEmpty(), "$name:\n" + mismatches.joinToString("\n"))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun mismatches(graph: InventoryGraph, record: Map<String, Any?>): List<String> {
        val out = ArrayList<String>()
        val failed = graph.problems.any { it.severity == ProblemSeverity.ERROR }
        if (failed != record["failed"]) out += "failed: expected ${record["failed"]}, got $failed ${graph.problems}"

        val json = record["stdout"] as Map<String, Any?>
        val hostvars = (json["_meta"] as Map<String, Any?>)["hostvars"] as Map<String, Any?>
        val groupsJson = json.filterKeys { it != "_meta" }.mapValues { it.value as Map<String, Any?> }
        val expectedGroups = LinkedHashSet(groupsJson.keys)
        groupsJson.values.forEach { g -> (g["children"] as List<String>?)?.let { expectedGroups += it } }
        if (expectedGroups != graph.groups.keys) out += "groups: expected $expectedGroups, got ${graph.groups.keys}"
        for (name in expectedGroups) {
            val group = graph.group(name) ?: continue
            val g = groupsJson[name].orEmpty()
            val children = g["children"] as List<String>? ?: emptyList()
            if (children != group.children) out += "$name children: expected $children, got ${group.children}"
            val hosts = g["hosts"] as List<String>? ?: emptyList()
            if (name != InventoryGraph.ALL && hosts != group.hosts) out += "$name hosts: expected $hosts, got ${group.hosts}"
            val vars = (g["vars"] as Map<String, Any?>?).orEmpty().toMutableMap()
            val priority = vars.remove("ansible_group_priority")
            if (priority != null && priority.toString() != group.priority.toString()) {
                out += "$name priority: expected $priority, got ${group.priority}"
            }
            if (vars.keys != group.vars.keys) out += "$name vars: expected ${vars.keys}, got ${group.vars.keys}"
            for (key in vars.keys.intersect(group.vars.keys)) OracleCompare.diff("$name.$key", vars[key], group.vars.getValue(key), out)
        }

        // `--export` lists a host only under its groups or in hostvars: one left in `all` alone is not printed.
        val listed = graph.hosts.values.filter { host ->
            host.vars.isNotEmpty() || graph.groups.values.any { it.name != InventoryGraph.ALL && host.name in it.hosts }
        }.map { it.name }.toSet()
        val expectedHosts = groupsJson.values.flatMap { (it["hosts"] as List<String>?).orEmpty() }.toSet() + hostvars.keys
        if (expectedHosts != listed) out += "hosts: expected $expectedHosts, got $listed"
        for (name in listed) {
            val e = (hostvars[name] as Map<String, Any?>?).orEmpty()
            val actual = graph.host(name)!!.vars
            if (e.keys != actual.keys) out += "$name vars: expected ${e.keys}, got ${actual.keys}"
            for (key in e.keys.intersect(actual.keys)) OracleCompare.diff("$name.$key", e[key], actual.getValue(key), out)
        }
        return out
    }
}
