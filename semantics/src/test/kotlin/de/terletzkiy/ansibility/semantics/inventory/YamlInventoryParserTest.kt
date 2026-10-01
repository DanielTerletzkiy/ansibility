package de.terletzkiy.ansibility.semantics.inventory

import de.terletzkiy.ansibility.semantics.testutil.YamlText
import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigInteger

class YamlInventoryParserTest {
    private fun parse(text: String) = YamlInventoryParser.parse(YamlText.parse(text.trimIndent()))

    private fun InventoryGraph.problemMessages(severity: ProblemSeverity) = problems.filter { it.severity == severity }.map { it.message }

    @Test
    fun `top-level keys are groups next to all and hosts listed with null values join them`() {
        val graph = parse(
            """
            all:
              vars: {ansible_user: provisioner, ansible_port: 2222}
              hosts:
                prod-prod1: {ansible_host: 10.0.0.1}
                prod-prod2: {ansible_host: 10.0.0.2}
            database:
              hosts:
                prod-prod1:   # a comment
            app_mono:
              hosts: {prod-prod1: , prod-prod2: }
              vars:
                structure: {ha: {floating_ip: "{{ system_ip_floating }}"}}
            """,
        )
        assertEquals(listOf("all", "ungrouped", "database", "app_mono"), graph.groups.keys.toList())
        assertEquals(listOf("ungrouped", "database", "app_mono"), graph.group("all")!!.children)
        assertEquals(listOf("prod-prod1", "prod-prod2"), graph.group("all")!!.hosts)
        assertEquals(listOf("prod-prod1"), graph.group("database")!!.hosts)
        assertEquals(listOf("all"), graph.group("database")!!.parents)
        assertEquals(setOf("all", "database", "app_mono"), graph.host("prod-prod1")!!.groups.toSet())
        assertEquals(listOf("app_mono"), graph.host("prod-prod2")!!.groupNames)
        assertTrue(graph.group("ungrouped")!!.hosts.isEmpty(), "hosts in other groups are not ungrouped")
        assertTrue(graph.problems.isEmpty(), graph.problems.toString())

        val allVars = graph.group("all")!!.vars
        assertEquals(Resolved.Int(BigInteger.valueOf(2222)), (allVars["ansible_port"] as YScalar).resolved)
        val structure = graph.group("app_mono")!!.vars["structure"] as YMap
        assertNotNull(structure.range)
        assertEquals("10.0.0.1", (graph.host("prod-prod1")!!.ansibleHost as YScalar).text)
    }

    @Test
    fun `templated ansible_host is kept as written with its range`() {
        val text = """
            all:
              hosts:
                prod-platform1:
                  ansible_host: "{{ host_ips['prod-platform1'] }}"
        """.trimIndent()
        val graph = YamlInventoryParser.parse(YamlText.parse(text))
        val host = graph.host("prod-platform1")!!
        val value = host.ansibleHost as YScalar
        assertEquals("{{ host_ips['prod-platform1'] }}", value.text)
        val range = value.range!!
        assertEquals("\"{{ host_ips['prod-platform1'] }}\"", text.substring(range.start, range.end))
        val keyRange = host.varSections.single().keyRanges.getValue("ansible_host")
        assertEquals("ansible_host", text.substring(keyRange.start, keyRange.end))
        assertEquals(1, host.definitions.size)
    }

    @Test
    fun `null and commented-out host lists and empty sections are tolerated`() {
        val graph = parse(
            """
            contracting:
              children:
                analytics:
                  hosts:
                    prod-mlflow1:
              hosts:
                # prod-a:
                # prod-b:
            empty:
            vars_only:
              vars:
            """,
        )
        assertEquals(listOf("analytics"), graph.group("contracting")!!.children)
        assertTrue(graph.group("contracting")!!.hosts.isEmpty())
        assertNotNull(graph.group("empty"))
        assertNotNull(graph.group("vars_only"))
        assertEquals(listOf("prod-mlflow1"), graph.hostsOf("contracting"))
        assertTrue(graph.problems.isEmpty(), graph.problems.toString())
    }

    @Test
    fun `children nest at any depth and may be defined elsewhere`() {
        val graph = parse(
            """
            jenkins_node:
              children:
                jenkins_controller:
            jenkins_controller:
              hosts:
                build-build1:
            a:
              children:
                b:
                  children:
                    c:
                      hosts:
                        deep1:
            """,
        )
        assertEquals(listOf("jenkins_node"), graph.group("jenkins_controller")!!.parents)
        assertEquals(setOf("all", "jenkins_node", "jenkins_controller"), graph.host("build-build1")!!.groups.toSet())
        assertEquals(listOf("jenkins_node", "a"), graph.group("all")!!.children - "ungrouped")
        assertEquals(1, graph.group("a")!!.depth)
        assertEquals(2, graph.group("b")!!.depth)
        assertEquals(3, graph.group("c")!!.depth)
        assertEquals(setOf("all", "a", "b", "c"), graph.host("deep1")!!.groups.toSet())
        assertEquals(listOf("deep1"), graph.hostsOf("a"))
        assertEquals(setOf("b", "a", "all"), graph.ancestorsOf("c"))
        assertEquals(2, graph.group("jenkins_controller")!!.definitions.size)
    }

    @Test
    fun `string sections are shorthand for one-key mappings`() {
        val graph = parse(
            """
            web:
              hosts: web1
              children: api
              vars: flag
            """,
        )
        assertEquals(listOf("web1"), graph.group("web")!!.hosts)
        assertEquals(listOf("api"), graph.group("web")!!.children)
        assertTrue("flag" in graph.group("web")!!.vars)
    }

    @Test
    fun `host ranges and ports are expanded and the port becomes ansible_port`() {
        val graph = parse(
            """
            web:
              hosts:
                web[01:03]:
                  role: web
                "db:2222":
                "edge-[a:b]":
            """,
        )
        assertEquals(listOf("web01", "web02", "web03", "db", "edge-a", "edge-b"), graph.group("web")!!.hosts)
        assertEquals("web", (graph.host("web02")!!.vars["role"] as YScalar).text)
        assertEquals(Resolved.Int(BigInteger.valueOf(2222)), (graph.host("db")!!.vars["ansible_port"] as YScalar).resolved)
        assertNull(graph.host("web01")!!.vars["ansible_port"])
    }

    @Test
    fun `hosts without a group are ungrouped and removed from ungrouped once they have one`() {
        val graph = parse(
            """
            all:
              hosts:
                lonely:
                both:
            ungrouped:
              hosts:
                both:
            web:
              hosts:
                both:
            """,
        )
        assertEquals(listOf("lonely"), graph.group("ungrouped")!!.hosts)
        assertEquals(setOf("all", "ungrouped"), graph.host("lonely")!!.groups.toSet())
        assertEquals(setOf("all", "web"), graph.host("both")!!.groups.toSet())
        assertFalse(graph.group("ungrouped")!!.isImplicit)
        assertTrue(graph.group("all")!!.definitions.isNotEmpty())
    }

    @Test
    fun `a group defined twice keeps both variable blocks in document order`() {
        val graph = parse(
            """
            a:
              vars: {x: 1, shared: first}
            b:
              children:
                a:
                  vars: {shared: second}
            """,
        )
        val a = graph.group("a")!!
        assertEquals(2, a.varSections.size)
        assertEquals("second", (a.vars["shared"] as YScalar).text)
        assertEquals(setOf("b"), a.parents.toSet())
        assertEquals(2, a.definitions.size)
    }

    @Test
    fun `host vars from two groups accumulate on the host`() {
        val graph = parse(
            """
            a:
              hosts:
                h1: {x: 1, y: a}
            b:
              hosts:
                h1: {y: b}
            """,
        )
        val h1 = graph.host("h1")!!
        assertEquals(2, h1.varSections.size)
        assertEquals("b", (h1.vars["y"] as YScalar).text)
        assertEquals("1", (h1.vars["x"] as YScalar).text)
    }

    @Test
    fun `ansible_group_priority sets the priority and is not a variable`() {
        val graph = parse(
            """
            web:
              vars:
                ansible_group_priority: 10
                other: 1
            db:
              vars:
                ansible_group_priority: "3"
            bad:
              vars:
                ansible_group_priority: high
            """,
        )
        assertEquals(10, graph.group("web")!!.priority)
        assertFalse("ansible_group_priority" in graph.group("web")!!.vars)
        assertEquals(3, graph.group("db")!!.priority)
        assertEquals(1, graph.group("bad")!!.priority)
        assertEquals(1, graph.problemMessages(ProblemSeverity.ERROR).size)
    }

    @Test
    fun `invalid definitions are reported like ansible-core and the rest still parses`() {
        val graph = parse(
            """
            listy: [a, b]
            web:
              hosts:
                ok1:
                123:
                bad_value: some string
              extra: {x: 1}
              scalar_key: 5
            broken_vars:
              vars: [1, 2]
            """,
        )
        val warnings = graph.problemMessages(ProblemSeverity.WARNING)
        val errors = graph.problemMessages(ProblemSeverity.ERROR)
        assertTrue(warnings.any { it.startsWith("Skipping 'listy' as this is not a valid group definition") }, warnings.toString())
        assertTrue(warnings.any { it.startsWith("Skipping unexpected key (extra) in group (web)") }, warnings.toString())
        assertTrue(warnings.any { it.startsWith("Skipping key (scalar_key) in group (web) as it is not a mapping") }, warnings.toString())
        assertTrue(errors.any { it.startsWith("Host pattern 123 must be a string") }, errors.toString())
        assertTrue(errors.any { it.startsWith("Invalid data from file, expected dictionary") }, errors.toString())
        assertTrue(errors.any { it.startsWith("Invalid \"vars\" entry for \"broken_vars\" group") }, errors.toString())
        assertNull(graph.group("listy"))
        assertEquals(listOf("ok1"), graph.group("web")!!.hosts)
        assertNull(graph.host("bad_value"))
        val location = graph.problems.first { it.message.startsWith("Host pattern 123") }.location
        assertNotNull(location.range)
    }

    @Test
    fun `recursive children, unknown children and self references are errors`() {
        val graph = parse(
            """
            a:
              children:
                b:
                  children:
                    a:
            c:
              children:
                c:
            d:
              children:
                ghost: [not, a, group]
            """,
        )
        val errors = graph.problemMessages(ProblemSeverity.ERROR)
        assertTrue(errors.any { it.contains("creates a recursive dependency loop") }, errors.toString())
        assertTrue(errors.any { it.startsWith("can't add group to itself") }, errors.toString())
        assertTrue(errors.any { it == "ghost is not a known host nor group" }, errors.toString())
        // As in ansible-core, the inner edge b -> a is added first; the outer a -> b then closes the loop.
        assertEquals(listOf("a"), graph.group("b")!!.children)
        assertTrue(graph.group("a")!!.children.isEmpty())
    }

    @Test
    fun `document level errors`() {
        assertEquals(
            listOf("Parsed empty YAML file"),
            YamlInventoryParser.parse(YamlText.parse("")).problems.map { it.message },
        )
        assertTrue(
            YamlInventoryParser.parse(YamlText.parse("- a\n- b\n")).problems.single().message
                .startsWith("YAML inventory has invalid structure"),
        )
        val badNames = YamlInventoryParser.parse(YamlText.parse("~: {}\n1: {}\n")).problems.map { it.message }
        assertEquals(
            listOf(
                "Unable to add group ~: Invalid empty/false group name provided",
                "Unable to add group 1: Invalid group name supplied, expected a string but got int",
            ),
            badNames,
        )
        assertEquals(
            "Plugin configuration YAML file, not YAML inventory",
            YamlInventoryParser.parse(YamlText.parse("plugin: aws_ec2\n")).problems.single().message,
        )
        val empty = InventoryGraph.empty()
        assertEquals(listOf("all", "ungrouped"), empty.groups.keys.toList())
        assertTrue(empty.group("all")!!.isImplicit)
        assertEquals(1, empty.group("ungrouped")!!.depth)
    }

    @Test
    fun `group names with invalid characters and group-host name clashes are warned about`() {
        val graph = parse(
            """
            app-mono:
              hosts:
                web:
            web:
              hosts:
                other:
            """,
        )
        assertTrue(graph.problemMessages(ProblemSeverity.WEAK_WARNING).single().contains("'app-mono'"))
        assertEquals(listOf("Found both group and host with same name: web"), graph.problemMessages(ProblemSeverity.WARNING))
    }

    @Test
    fun `several sources merge into one inventory with per-source locations`() {
        val first = YamlText.parse("web:\n  hosts:\n    h1: {x: 1}\n")
        val second = YamlText.parse("web:\n  hosts:\n    h1: {x: 2}\n    h2:\n")
        val graph = YamlInventoryParser.parseAll(listOf(first, second))
        assertEquals(listOf("h1", "h2"), graph.group("web")!!.hosts)
        assertEquals(listOf(0, 1), graph.host("h1")!!.varSections.map { it.location.sourceIndex })
        assertEquals("2", (graph.host("h1")!!.vars["x"] as YScalar).text)
        assertEquals(listOf(0, 1), graph.group("web")!!.definitions.map { it.sourceIndex })
    }
}
