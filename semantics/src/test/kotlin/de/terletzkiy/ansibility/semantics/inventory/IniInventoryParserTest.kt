package de.terletzkiy.ansibility.semantics.inventory

import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigInteger

class IniInventoryParserTest {
    private fun parse(text: String, core: CoreVersion? = null) = IniInventoryParser.parse(text, core)

    private fun errors(graph: InventoryGraph) = graph.problems.filter { it.severity == ProblemSeverity.ERROR }

    /** The one ERROR of a failed file with its 1-based line. */
    private fun failure(text: String): Pair<String, Int> {
        val graph = parse(text)
        val error = errors(graph).single()
        assertEquals(setOf("all", "ungrouped"), graph.groups.keys, "a failed source alone shows nothing")
        return error.message to IniInventoryParser.lineOf(text, error.location.range!!.start)
    }

    /** The resolved Python value of [value] (scalars) or its shape. */
    private fun py(value: YValue?): Any? = when (value) {
        is YScalar -> when (val r = value.resolved) {
            Resolved.Null -> null
            is Resolved.Bool -> r.value
            is Resolved.Int -> r.value
            is Resolved.Float -> r.value
            is Resolved.Str -> r.value
            else -> r
        }
        is YSeq -> value.items.map(::py)
        is YMap -> value.entries.associate { py(it.key) to py(it.value) }
        else -> value
    }

    private fun hostVar(text: String, host: String, name: String, core: CoreVersion? = null) =
        py(parse(text, core).host(host)!!.vars[name])

    private fun groupVar(text: String, group: String, name: String, core: CoreVersion? = null) =
        py(parse(text, core).group(group)!!.vars[name])

    @Nested
    inner class Structure {
        @Test
        fun `hosts before any section are ungrouped and sections declare groups`() {
            val graph = parse(
                """
                loose1
                loose2 x=1
                [web]
                web1
                [db:hosts]
                db1
                [dc1:children]
                web
                db
                [dc1:vars]
                region=eu
                """.trimIndent(),
            )
            assertEquals(listOf("loose1", "loose2"), graph.group("ungrouped")!!.hosts)
            assertEquals(listOf("ungrouped", "dc1"), graph.group("all")!!.children)
            assertEquals(listOf("web", "db"), graph.group("dc1")!!.children)
            assertEquals(listOf("db1"), graph.group("db")!!.hosts)
            assertEquals(setOf("all", "dc1", "web"), graph.host("web1")!!.groups.toSet())
            assertEquals("eu", py(graph.group("dc1")!!.vars["region"]))
            assertTrue(errors(graph).isEmpty())
        }

        @Test
        fun `sections may be used before they are declared`() {
            val graph = parse("[parent:children]\nlate\n[pre:vars]\ndeclared_later=yes\n[late]\nlate1\n[pre]\npre1\n[empty]\n")
            assertEquals(listOf("ungrouped", "parent", "pre", "empty"), graph.group("all")!!.children)
            assertEquals(listOf("late"), graph.group("parent")!!.children)
            assertEquals("yes", py(graph.group("pre")!!.vars["declared_later"]))
            assertTrue(graph.group("empty")!!.hosts.isEmpty())
        }

        @Test
        fun `a vars section before the group's hosts section and a child reference is valid`() {
            val graph = parse("[g:vars]\nk=1\n[x:children]\ng\n[g]\nh1\n")
            assertEquals(listOf("g"), graph.group("x")!!.children)
            assertTrue(errors(graph).isEmpty())
        }

        @Test
        fun `all hosts land in ungrouped after the explicit ones`() {
            val graph = parse("[all]\nlisted-under-all\n[ungrouped]\nexplicit-ungrouped\n[ungrouped:vars]\nu=1\n[all:children]\nweb\n[web]\nh1\n")
            assertEquals(listOf("explicit-ungrouped", "listed-under-all"), graph.group("ungrouped")!!.hosts)
            assertEquals(listOf("ungrouped", "web"), graph.group("all")!!.children)
            assertEquals(BigInteger.ONE, py(graph.group("ungrouped")!!.vars["u"]))
        }

        @Test
        fun `indentation is ignored and group names are case-sensitive`() {
            val graph = parse("  [web]\n    h1   x=1\n  [web:vars]\n    k = v\n[Case]\na\n[case]\nb\n")
            assertEquals(listOf("h1"), graph.group("web")!!.hosts)
            assertEquals("v", py(graph.group("web")!!.vars["k"]))
            assertEquals(listOf("a"), graph.group("Case")!!.hosts)
            assertEquals(listOf("b"), graph.group("case")!!.hosts)
        }

        @Test
        fun `dashes and dots in group names are kept with a warning`() {
            val graph = parse("[dash-group]\nh1\n[group.dot]\nh2\n")
            assertTrue(graph.group("dash-group") != null && graph.group("group.dot") != null)
            assertEquals(2, graph.problems.count { it.severity == ProblemSeverity.WEAK_WARNING && "Invalid characters" in it.message })
        }

        @Test
        fun `a group named like a host is reported and its inline vars go to the group`() {
            val graph = parse("[web]\nweb role=front\n")
            assertEquals(listOf("web"), graph.group("web")!!.hosts)
            assertEquals("front", py(graph.group("web")!!.vars["role"]))
            assertTrue(graph.host("web")!!.vars.isEmpty())
            assertTrue(graph.problems.any { it.message == "Found both group and host with same name: web" })
        }

        @Test
        fun `a repeated section merges and a host may be a member and a child's host`() {
            val graph = parse("[web]\nh1 x=1\n[db]\nh1\n[web:children]\ndb\n[web:vars]\na=1\n[web:vars]\nb=2\n")
            assertEquals(listOf("h1"), graph.group("web")!!.hosts)
            assertEquals(listOf("db"), graph.group("web")!!.children)
            assertEquals(mapOf("a" to BigInteger.ONE, "b" to BigInteger.TWO), graph.group("web")!!.vars.mapValues { py(it.value) })
        }

        @Test
        fun `ansible_group_priority in a vars section sets the priority`() {
            val graph = parse("[a]\nh1\n[a:vars]\nansible_group_priority=10\n")
            assertEquals(10, graph.group("a")!!.priority)
            assertFalse("ansible_group_priority" in graph.group("a")!!.vars)
        }

        @Test
        fun `empty and comment-only files are empty inventories`() {
            assertTrue(errors(parse("")).isEmpty())
            val graph = parse("# hash\n; semicolon\n\n")
            assertEquals(setOf("all", "ungrouped"), graph.groups.keys)
            assertTrue(errors(graph).isEmpty())
        }
    }

    @Nested
    inner class Comments {
        @Test
        fun `full-line comments and a hash after a section header`() {
            val graph = parse("# c\n; c\n[web] # comment\nh1\n[web:vars] # also\nk=v\n[p:children]   # here\nweb # after a child\n")
            assertEquals(listOf("h1"), graph.group("web")!!.hosts)
            assertEquals(listOf("web"), graph.group("p")!!.children)
            assertTrue(errors(graph).isEmpty())
        }

        @Test
        fun `a semicolon after a section header makes it a host line`() {
            val (message, line) = failure("[web]\nh1\n[web:children] ; only '#' comments may follow a section header\n")
            assertTrue("invalid literal for int() with base 10: 'web'" in message, message)
            assertEquals(3, line)
        }

        @Test
        fun `an unquoted hash starts a comment even inside a token`() {
            val text = "[web]\nh1 x=a#b y=1\nh2 x=a #b y=2\nh3 x=\"a b\"#c y=3\nh4 x=\"a#b\" y=4\n"
            val graph = parse(text)
            assertEquals(mapOf("x" to "a"), graph.host("h1")!!.vars.mapValues { py(it.value) })
            assertEquals(mapOf("x" to "a"), graph.host("h2")!!.vars.mapValues { py(it.value) })
            assertEquals(mapOf("x" to "a b"), graph.host("h3")!!.vars.mapValues { py(it.value) })
            assertEquals(mapOf("x" to "a#b", "y" to BigInteger.valueOf(4)), graph.host("h4")!!.vars.mapValues { py(it.value) })
        }

        @Test
        fun `a semicolon on a host line or a child line fails the source`() {
            assertEquals("Expected key=value host variable assignment, got: ;" to 2, failure("[web]\nh1 ; comment\n"))
            assertEquals("Expected group name, got: web ; comment" to 4, failure("[web]\nh1\n[p:children]\nweb ; comment\n"))
            assertEquals("Expected group name, got: web x=1" to 4, failure("[web]\nh1\n[p:children]\nweb x=1\n"))
        }

        @Test
        fun `vars values keep hash and semicolon text`() {
            val text = "[g]\nh\n[g:vars]\na=abc # not a comment\nb=5 # a python comment\nc=abc ; not a comment\n"
            assertEquals("abc # not a comment", groupVar(text, "g", "a"))
            assertEquals(BigInteger.valueOf(5), groupVar(text, "g", "b"))
            assertEquals("abc ; not a comment", groupVar(text, "g", "c"))
        }
    }

    @Nested
    inner class Failures {
        @Test
        fun `every whole-source failure is reported at ansible-core's line`() {
            val cases = listOf(
                "[a:children]\nb\n[b:children]\na\n" to ("Adding group 'a' as child to 'b' creates a recursive dependency loop." to 4),
                "[parent:children]\nnosuchgroup\n" to ("Section [parent:children] includes undefined group 'nosuchgroup'." to 2),
                "[nosuchgroup:vars]\nx=1\n" to ("Section [nosuchgroup:vars] not valid for undefined group 'nosuchgroup'." to 1),
                "[web]\nh1\n[web:vars]\njustaword\n" to ("Expected key=value, got: justaword" to 4),
                "[web]\nh1 x\n" to ("Expected key=value host variable assignment, got: x" to 2),
                "[web:foo]\nh1\n" to ("Section [web:foo] has unknown type: foo" to 1),
                "[web]\nh1 x=\"open\n" to ("Error parsing host definition 'h1 x=\"open': No closing quotation" to 2),
            )
            for ((text, expected) in cases) assertEquals(expected, failure(text), text)
        }

        @Test
        fun `spaces in a section header and YAML content are refused`() {
            assertTrue(failure("[ web ]\nh1\n").first.startsWith("Invalid section entry: '[ web ]'"))
            assertEquals(1, failure("[ web ]\nh1\n").second)
            assertTrue("ending in ':' is not allowed" in failure("all:\n  hosts:\n    h1:\n").first)
            assertTrue("ending in ':' is not allowed" in failure("[web]\nh1:\n").first)
            assertTrue("'---' is normally a sign this is a YAML file" in failure("---\nall:\n").first)
        }

        @Test
        fun `the first undefined child is the one reported, under its last parent`() {
            val (message, line) = failure("[p1:children]\nx\n[p2:children]\nx\ny\n")
            assertEquals("Section [p2:children] includes undefined group 'x'.", message)
            assertEquals(2, line)
        }

        @Test
        fun `an empty host name and an unhashable literal fail the source`() {
            assertEquals("Invalid empty host name provided" to 2, failure("[web]\n\"\" x=1\n"))
            assertTrue("unhashable type: 'list'" in failure("[g]\nh1\n[g:vars]\nx={[1]}\n").first)
            assertTrue("unhashable type: 'dict'" in failure("[g]\nh1 x={{}:1}\n").first)
        }

        @Test
        fun `a byte order mark is part of the first line, as ansible-core reads it`() {
            assertTrue("host range must be begin:end or begin:end:step" in failure("﻿[web]\nh1\n").first)
        }

        @Test
        fun `parsing stops at the first error and keeps what it added`() {
            val builder = InventoryBuilder(failFast = true)
            val text = "[early]\nearly1 kept=yes\n[bad section]\n[late]\nlate1\n"
            val failure = runCatching { IniInventoryParser.parseInto(builder, text, 0, null) }.exceptionOrNull()
            assertTrue(failure is InventoryBuilder.SourceFailure)
            assertTrue("early" in builder.groups && "early1" in builder.hosts)
            assertFalse("late" in builder.groups)
        }
    }

    @Nested
    inner class Hosts {
        @Test
        fun `ranges, strides, padding from the start bound and backwards ranges`() {
            val graph = parse(
                "[web]\nweb[01:03].example.test\nweb-[a:c]\nweb[8:10:2]\nweb[001:002]\nweb[1:02]\nbackwards[3:1]\n",
            )
            assertEquals(
                listOf(
                    "web01.example.test", "web02.example.test", "web03.example.test", "web-a", "web-b", "web-c", "web8", "web10",
                    "web001", "web002", "web1", "web2",
                ),
                graph.group("web")!!.hosts,
            )
            assertTrue(errors(graph).isEmpty())
        }

        @Test
        fun `a hex range fails and inline vars go to every expanded host`() {
            assertTrue("invalid literal for int()" in failure("[web]\nweb[0x1:0x3]\n").first)
            val graph = parse("[web]\nweb[1:2] role=frontend\n")
            assertEquals(listOf("frontend", "frontend"), listOf("web1", "web2").map { py(graph.host(it)!!.vars["role"]) })
        }

        @Test
        fun `ports, IPv6 addresses and the port applied only on creation`() {
            val graph = parse("[a]\ndb1:2222\n[2001:db8::1]:2201\n2001:db8::2\nh1:2201 v=a\n[b]\nh1:2202 v=b\n[c]\nh1 v=c\n")
            assertEquals(BigInteger.valueOf(2222), py(graph.host("db1")!!.vars["ansible_port"]))
            assertEquals(BigInteger.valueOf(2201), py(graph.host("2001:db8::1")!!.vars["ansible_port"]))
            assertNull(graph.host("2001:db8::2")!!.vars["ansible_port"])
            assertEquals(BigInteger.valueOf(2201), py(graph.host("h1")!!.vars["ansible_port"]))
            assertEquals("c", py(graph.host("h1")!!.vars["v"]))
            assertEquals(listOf("a", "b", "c", "all"), graph.host("h1")!!.groups) // reconcile adds `all` last
        }

        @Test
        fun `the later line wins per variable and a repeated key on one line takes the last value`() {
            val graph = parse("[web]\nh1 ansible_host=192.0.2.1 ansible_host=192.0.2.2 a=1\n[db]\nh1 a=2\n[web:vars]\nk=1\nk=2\nKey=3\n")
            assertEquals("192.0.2.2", py(graph.host("h1")!!.vars["ansible_host"]))
            assertEquals(BigInteger.TWO, py(graph.host("h1")!!.vars["a"]))
            assertEquals(BigInteger.TWO, py(graph.group("web")!!.vars["k"]))
            assertEquals(BigInteger.valueOf(3), py(graph.group("web")!!.vars["Key"]))
            assertEquals(2, graph.host("h1")!!.varSections.size)
        }
    }

    @Nested
    inner class Typing {
        private val host = "typed"

        private fun typed(value: String) = hostVar("$host v=$value\n", host, "v")

        private fun vars(value: String) = groupVar("[g]\nh\n[g:vars]\nv=$value\n", "g", "v")

        @Test
        fun `host-line values are shlex-split, then literal_eval'd`() {
            assertEquals(BigInteger.valueOf(5), typed("5"))
            assertEquals(BigInteger.valueOf(5), typed("\"5\""))
            assertEquals(BigInteger.valueOf(5), typed("'5'"))
            assertEquals("5", typed("\"'5'\""))
            assertEquals(true, typed("True"))
            assertNull(typed("None"))
            assertEquals("yes", typed("yes"))
            assertEquals("true", typed("true"))
            assertEquals("none", typed("none"))
            assertEquals(1.5, typed("1.5"))
            assertEquals(1000.0, typed("1e3"))
            assertEquals(BigInteger.valueOf(16), typed("0x10"))
            assertEquals("010", typed("010"))
            assertEquals(BigInteger.valueOf(-3), typed("-3"))
            assertEquals(BigInteger.valueOf(1000), typed("1_000"))
            assertEquals(listOf(BigInteger.ONE, BigInteger.TWO), typed("[1,2]"))
            assertEquals(listOf(BigInteger.ONE, BigInteger.TWO), typed("(1,2)"))
            assertEquals(listOf(BigInteger.ONE, BigInteger.TWO), typed("1,2"))
            assertEquals("{a:1}", typed("{'a':1}"))
            assertEquals(mapOf("a" to BigInteger.ONE), typed("\"{'a': 1}\""))
            assertEquals(listOf("a", "b"), typed("\"['a', 'b']\""))
            assertEquals("babc", typed("b'abc'"))
            assertEquals("abc", typed("\"b'abc'\""))
            assertEquals("hello world", typed("\"hello world\""))
            assertEquals(" padded ", typed("\" padded \""))
            assertEquals("{{ x }}", typed("\"{{ x }}\""))
            assertEquals("", typed(""))
            assertEquals("a=b", typed("a=b"))
            assertEquals("a:b", typed("a:b"))
            assertEquals("a;b", typed("a;b"))
            assertEquals("ab", typed("a\\b"))
            assertEquals("[True,false]", typed("[True,false]"))
        }

        @Test
        fun `vars values keep their quotes, then literal_eval`() {
            assertEquals(BigInteger.valueOf(5), vars("5"))
            assertEquals("5", vars("\"5\""))
            assertEquals("5", vars("'5'"))
            assertEquals("yes", vars("yes"))
            assertEquals(true, vars("True"))
            assertEquals("FALSE", vars("FALSE"))
            assertNull(vars("None"))
            assertEquals(1.5, vars("1.5"))
            assertEquals(listOf(BigInteger.ONE, BigInteger.TWO), vars("[1, 2]"))
            assertEquals(mapOf("a" to BigInteger.ONE), vars("{'a': 1}"))
            assertEquals("hello world", vars("hello world"))
            assertEquals("hello world", vars("\"hello world\""))
            assertEquals("spaced", groupVar("[g]\nh\n[g:vars]\nv_spaced = spaced\n", "g", "v_spaced"))
            assertEquals("a=b", vars("a=b"))
            assertEquals("", vars(""))
            assertEquals("{{ x }}", vars("{{ x }}"))
            assertEquals("010", vars("010"))
        }

        @Test
        fun `strings are quoted scalars whose source text is the INI spelling`() {
            val text = "[g]\nh x=\"a b\" n=0x10\n[g:vars]\ns=abc # c\n"
            val graph = parse(text)
            val x = graph.host("h")!!.vars["x"] as YScalar
            assertEquals(ScalarStyle.DOUBLE_QUOTED, x.style)
            assertEquals("\"a b\"", x.sourceText)
            assertEquals("\"a b\"", text.substring(x.range!!.start, x.range!!.end))
            val n = graph.host("h")!!.vars["n"] as YScalar
            assertEquals("16", n.text)
            assertEquals("0x10", n.sourceText)
            val s = graph.group("g")!!.vars["s"] as YScalar
            assertEquals("abc # c", s.sourceText)
        }

        @Test
        fun `version-dependent literals follow the target core`() {
            val text = "h1 s=\"{1, 2, 1.0}\" c=1j cc=1+2j neg=-1j e=... n=\"{1: 2, True: 3, 1.0: 4}\" nb=\"[b'x', (1, 2)]\"\n"
            val modern = parse(text, CoreVersion(2, 21, 4))
            val legacy = parse(text, CoreVersion(2, 18, 8))
            for (graph in listOf(modern, legacy)) {
                val vars = graph.host("h1")!!.vars.mapValues { py(it.value) }
                assertEquals(listOf(BigInteger.ONE, BigInteger.TWO), vars["s"])
                assertEquals("1j", vars["c"])
                assertEquals("(1+2j)", vars["cc"])
                assertEquals("(-0-1j)", vars["neg"])
                assertEquals("...", vars["e"])
                assertEquals(mapOf(BigInteger.ONE to BigInteger.valueOf(4)), vars["n"])
                assertEquals(listOf("x", listOf(BigInteger.ONE, BigInteger.TWO)), vars["nb"])
            }
            assertTrue(modern.problems.none { it.severity == ProblemSeverity.WARNING })
            val warnings = legacy.problems.filter { it.severity == ProblemSeverity.WARNING }.map { it.message }
            assertEquals(6, warnings.size, warnings.toString()) // s, c, cc, neg, e, nb
            assertTrue(warnings.all { "before 2.19" in it && "cannot print" in it })
            assertTrue(warnings.any { "set" in it } && warnings.any { "complex" in it } && warnings.any { "Ellipsis" in it } && warnings.any { "bytes" in it })
        }

        @Test
        fun `floats keep their value through the YAML spelling`() {
            assertEquals(1e16, typed("1e16"))
            assertEquals(1e-5, typed("1e-5"))
            assertEquals(Double.POSITIVE_INFINITY, typed("1e999"))
            assertEquals(-0.0, typed("-0.0"))
        }
    }

    @Nested
    inner class Locations {
        @Test
        fun `definitions, key ranges and line numbers point into the file`() {
            val text = "[web]\nweb1 http_port=8080\n[web:vars]\ntier = frontend\n[dc1:children]\nweb\n"
            val graph = parse(text)
            fun at(location: InventoryLocation) = text.substring(location.range!!.start, location.range!!.end)
            val web = graph.group("web")!!
            assertEquals(listOf("web", "web", "web"), web.definitions.map(::at))
            assertEquals(listOf(1, 3, 6), web.definitions.map { IniInventoryParser.lineOf(text, it.range!!.start) })
            val host = graph.host("web1")!!
            assertEquals("web1", at(host.definitions.single()))
            val hostSection = host.varSections.single()
            assertEquals("http_port", text.substring(hostSection.keyRanges.getValue("http_port").start, hostSection.keyRanges.getValue("http_port").end))
            assertEquals("http_port=8080", at(hostSection.location))
            val groupSection = web.varSections.single()
            assertEquals("tier", text.substring(groupSection.keyRanges.getValue("tier").start, groupSection.keyRanges.getValue("tier").end))
            assertEquals("tier = frontend", at(groupSection.location))
        }

        @Test
        fun `line numbers count every line break Python's splitlines knows`() {
            val text = "a\r\nb\rc\u000Bd e\n"
            assertEquals(listOf(1, 2, 3, 4, 5), listOf('a', 'b', 'c', 'd', 'e').map { IniInventoryParser.lineOf(text, text.indexOf(it)) })
        }
    }

    @Nested
    inner class Shlex {
        private fun split(text: String) = PyShlex.split(text).map { it.text }

        @Test
        fun `posix shlex with comments`() {
            assertEquals(listOf("a", "b c", "d"), split("a \"b c\" d"))
            assertEquals(listOf("x=a"), split("x=a#b y=1"))
            assertEquals(listOf("x=a#b", "y=4"), split("x=\"a#b\" y=4"))
            assertEquals(listOf("", "x"), split("\"\" x"))
            assertEquals(listOf("a\\b", "a\"b", "ab", "a\\nb"), split("'a\\b' \"a\\\"b\" a\\b \"a\\nb\""))
            assertEquals(listOf("a"), split("a # rest"))
            assertEquals(emptyList<String>(), split("# all comment"))
        }

        @Test
        fun `errors and offsets`() {
            assertEquals("No closing quotation", runCatching { PyShlex.split("a \"b") }.exceptionOrNull()?.message)
            assertEquals("No escaped character", runCatching { PyShlex.split("a \\") }.exceptionOrNull()?.message)
            val token = PyShlex.split("  k=\"v w\" next").first()
            assertEquals(2, token.start)
            assertEquals(9, token.end)
            assertEquals(3, token.offsetOf(1)) // `=`
            assertEquals(5, token.offsetOf(2)) // `v`, after the opening quote
        }
    }
}
