package de.terletzkiy.ansibility.lang.jinja

import de.terletzkiy.ansibility.lang.jinja.lexer.JinjaLexMode
import de.terletzkiy.ansibility.lang.jinja.lexer.JinjaLexMode.EXPRESSION
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaLocalKind
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaNameSite
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaRefs
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaRefsResult
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaVarRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.readText

class JinjaRefsTest {
    private fun analyze(text: String, mode: JinjaLexMode = JinjaLexMode.TEMPLATE) = JinjaRefs.analyze(text, mode)

    /** `name.path` of each free reference, in order. */
    private fun JinjaRefsResult.free(): List<String> = references.map(::display)

    private fun JinjaRefsResult.local(): List<String> = localReferences.map(::display)

    private fun display(ref: JinjaVarRef) = (listOf(ref.name) + ref.attrPath).joinToString(".")

    private fun JinjaRefsResult.localNamed(name: String) = locals.single { it.name == name }

    private fun String.textOf(ref: JinjaVarRef) = substring(ref.range.startOffset, ref.range.endOffset)

    private fun String.textOf(site: JinjaNameSite) = substring(site.range.startOffset, site.range.endOffset)

    // ------------------------------------------------------------------------------------------------ paths

    @Test
    fun `attribute paths follow dots and constant subscripts`() {
        val text = "{{ item.listen.tls.cert }} {{ ansible_facts['os_family'] }} {{ ansible_facts[\"service_mgr\"] }}" +
            " {{ hostvars[groups['collectors'][0]].ansible_host }}"
        val result = analyze(text)
        assertEquals(
            listOf("item.listen.tls.cert", "ansible_facts.os_family", "ansible_facts.service_mgr", "hostvars", "groups.collectors.0"),
            result.free(),
        )
        val (item, factsSingle, factsDouble, hostvars, groups) = result.references
        assertEquals("item.listen.tls.cert", text.textOf(item))
        assertEquals("ansible_facts['os_family']", text.textOf(factsSingle))
        assertEquals("ansible_facts[\"service_mgr\"]", text.textOf(factsDouble))
        assertEquals("hostvars", text.textOf(hostvars))
        assertEquals("groups['collectors'][0]", text.textOf(groups))
        assertEquals("item", text.substring(item.nameRange.startOffset, item.nameRange.endOffset))
    }

    @Test
    fun `paths stop at method calls, dynamic subscripts and slices`() {
        val result = analyze("{{ item.0.name }} {{ item[1] }} {{ x.items() }} {{ y.get('a').b }} {{ z[1:] }} {{ w[k].v }}")
        assertEquals(listOf("item.0.name", "item.1", "x", "y", "z", "w", "k"), result.free())
        assertFalse(result.references.any { it.called })
    }

    @Test
    fun `attributes, filters, tests and keyword arguments are not references`() {
        val result = analyze("{{ a.b | combine(d, recursive=True) }}{{ x is sameas y }}{{ n | ansible.builtin.splitext }}{{ ns(k=v) }}")
        assertEquals(listOf("a.b", "d", "x", "y", "n", "ns", "v"), result.free())
    }

    @Test
    fun `calls are flagged on the root only`() {
        val result = analyze("{{ lookup('template', t, template_vars={'probe': a}) | trim }}{{ range(3) }}{{ x.items() }}")
        assertEquals(listOf("lookup", "t", "a", "range", "x"), result.free())
        assertEquals(listOf(true, false, false, true, false), result.references.map { it.called })
    }

    // ------------------------------------------------------------------------------------------------ guards

    @Test
    fun `guards directly after the access`() {
        val result = analyze(
            "{{ a | default('x') }}{{ b | d }}{{ c is defined }}{{ d.e is not defined }}{{ f is undefined }}" +
                "{{ g | lower | default('') }}{{ h is none }}{{ i|default(omit) }}{{ j['k'] | d(1) }}{{ l }}",
        )
        assertEquals(listOf("a", "b", "c", "d.e", "f", "g", "h", "i", "omit", "j.k", "l"), result.free())
        assertEquals(
            listOf(true, true, true, true, true, false, false, true, false, true, false),
            result.references.map { it.guarded },
        )
    }

    @Test
    fun `conditions in the same expression guard later terms`() {
        fun guardedByCondition(expression: String) = analyze(expression, EXPRESSION).references.map { it.guardedByCondition }
        assertEquals(listOf(false, true), guardedByCondition("x is defined and x.y | length > 0"))
        assertEquals(listOf(false, true), guardedByCondition("x is not defined or x | length == 0"))
        assertEquals(listOf(false, true), guardedByCondition("not (p is defined) or p.q"))
        assertEquals(listOf(false, false), guardedByCondition("r is defined or r.s"))
        assertEquals(listOf(false, false), guardedByCondition("u.v and u is defined"))
        assertEquals(listOf(true, false, false), guardedByCondition("a.b if a is defined else c"))
        assertEquals(listOf(false, false, true), guardedByCondition("b if a is not defined else a.x"))
        // `is` binds tighter than `+`: this is `x + (y is defined)`, which asserts nothing
        assertEquals(listOf(false, false, false), guardedByCondition("x + y is defined and y"))
    }

    @Test
    fun `if branches guard their bodies`() {
        val result = analyze(
            "{% if x is defined %}{{ x }}{% elif y is defined %}{{ x }}{{ y }}{% else %}{{ z }}{% endif %}{{ x }}" +
                "{% if w is not defined %}{{ w }}{% else %}{{ w.v }}{% endif %}" +
                "{% if a is defined and b is defined %}{{ a }}{{ b }}{% endif %}{% if c is defined or d %}{{ c }}{% endif %}",
        )
        assertEquals(
            listOf("x", "x", "y", "x", "y", "z", "x", "w", "w", "w.v", "a", "b", "a", "b", "c", "d", "c"),
            result.free(),
        )
        assertEquals(
            listOf(false, true, false, false, true, false, false, false, false, true, false, false, true, true, false, false, false),
            result.references.map { it.guardedByCondition },
        )
    }

    @Test
    fun `macro bodies do not inherit conditions around the definition`() {
        val result = analyze("{% if m is defined %}{% macro f() %}{{ m }}{% endmacro %}{{ m }}{% endif %}")
        assertEquals(listOf("m", "m", "m"), result.free())
        assertEquals(listOf(false, false, true), result.references.map { it.guardedByCondition })
    }

    // ------------------------------------------------------------------------------------------------ locals

    @Test
    fun `for targets, loop and the else branch`() {
        val text = "{% for k, v in d.items() if v.enabled %}{{ k }}={{ v }}{{ loop.index }}{% else %}{{ k }}{% endfor %}{{ k }}"
        val result = analyze(text)
        assertEquals(listOf("d", "k", "k"), result.free())
        assertEquals(listOf("v.enabled", "k", "v", "loop.index"), result.local())
        val k = result.localNamed("k")
        assertEquals(JinjaLocalKind.FOR_TARGET, k.kind)
        assertEquals("k", text.substring(k.definitionRange.startOffset, k.definitionRange.endOffset))
        // visible from the loop filter to the {% else %}
        assertEquals(text.indexOf("if v.enabled"), k.scope.startOffset)
        assertEquals(text.indexOf("{% else %}"), k.scope.endOffset)
        val loop = result.localNamed("loop")
        assertEquals(JinjaLocalKind.LOOP, loop.kind)
        assertEquals(text.indexOf("%}") + 2, loop.scope.startOffset)
    }

    @Test
    fun `a for target shadows item`() {
        val result = analyze("{{ item }}{% for item in items %}{{ item.x }}{% endfor %}{{ item }}")
        assertEquals(listOf("item", "items", "item"), result.free())
        assertEquals(listOf("item.x"), result.local())
    }

    @Test
    fun `set binds after the tag, inside for it does not leak, inside if it does`() {
        val result = analyze(
            "{% set x = x + 1 %}{{ x }}{% for i in l %}{% set y = i %}{{ y }}{% endfor %}{{ y }}" +
                "{% if c %}{% set z = 1 %}{% endif %}{{ z }}{% set a, b = 1, 2 %}{{ a ~ b }}",
        )
        assertEquals(listOf("x", "l", "y", "c"), result.free())
        assertEquals(listOf("x", "i", "y", "z", "a", "b"), result.local())
        assertEquals(JinjaLocalKind.SET, result.localNamed("z").kind)
    }

    @Test
    fun `block set binds after endset`() {
        val text = "{% set body | upper %}{{ inner }}{{ body }}{% endset %}{{ body }}"
        val result = analyze(text)
        assertEquals(listOf("inner", "body"), result.free())
        assertEquals(listOf("body"), result.local())
        val body = result.localNamed("body")
        assertEquals(JinjaLocalKind.BLOCK_SET, body.kind)
        assertEquals(text.length - "{{ body }}".length, body.scope.startOffset)
        assertEquals(listOf("upper"), result.filterNames.map { it.name })
    }

    @Test
    fun `namespace attributes are owned by the namespace and outlive loops`() {
        val text = "{% set ns = namespace(found=false, items=[]) %}{% for c in cs %}{% set ns.found = c %}{% endfor %}" +
            "{{ ns.found }}{{ found }}{% set other.x = 1 %}"
        val result = analyze(text)
        assertEquals(listOf("namespace", "cs", "found", "other.x"), result.free())
        assertEquals(listOf("ns.found", "c", "ns.found"), result.local())
        val attributes = result.locals.filter { it.kind == JinjaLocalKind.NAMESPACE_ATTRIBUTE }
        assertEquals(listOf("found", "items", "found", "x"), attributes.map { it.name })
        assertEquals(listOf("ns", "ns", "ns", "other"), attributes.map { it.owner })
        // the attribute set inside the loop is visible after it, like the namespace itself
        val setInLoop = attributes[2]
        assertEquals(text.length, setInLoop.scope.endOffset)
        assertEquals(result.localNamed("ns").scope.endOffset, setInLoop.scope.endOffset)
        assertNull("namespace attributes are not bare names", result.references.firstOrNull { it.name == "found" }?.local)
    }

    @Test
    fun `macro name, parameters, defaults and implicit locals`() {
        val text = "{% macro m(a, b=dflt) %}{{ a }}{{ b }}{{ caller() }}{{ varargs }}{{ m }}{{ c }}{% endmacro %}{{ m(1) }}{{ a }}"
        val result = analyze(text)
        assertEquals(listOf("dflt", "c", "a"), result.free())
        assertEquals(listOf("a", "b", "caller", "varargs", "m", "m"), result.local())
        assertEquals(
            listOf(
                "m" to JinjaLocalKind.MACRO, "a" to JinjaLocalKind.MACRO_PARAMETER, "b" to JinjaLocalKind.MACRO_PARAMETER,
                "varargs" to JinjaLocalKind.MACRO_IMPLICIT, "kwargs" to JinjaLocalKind.MACRO_IMPLICIT, "caller" to JinjaLocalKind.MACRO_IMPLICIT,
            ),
            result.locals.map { it.name to it.kind },
        )
        assertEquals(text.indexOf("{% endmacro %}"), result.localNamed("a").scope.endOffset)
        assertEquals(text.length, result.localNamed("m").scope.endOffset)
    }

    @Test
    fun `call, with, import and from`() {
        val result = analyze(
            "{% call(user) list_users(users) %}{{ user.name }}{% endcall %}{{ user }}" +
                "{% with a = 1, b = c %}{{ a }}{{ b }}{% endwith %}{{ a }}" +
                "{% from 'f' import m as mm, n with context %}{{ mm() }}{{ n }}{% import 'g' as g %}{{ g.x }}",
        )
        assertEquals(listOf("list_users", "users", "user", "c", "a"), result.free())
        assertEquals(listOf("user.name", "a", "b", "mm", "n", "g.x"), result.local())
        assertEquals(JinjaLocalKind.CALL_PARAMETER, result.localNamed("user").kind)
        assertEquals(JinjaLocalKind.WITH, result.localNamed("b").kind)
        assertEquals(listOf("mm", "n", "g"), result.locals.filter { it.kind == JinjaLocalKind.IMPORT }.map { it.name })
    }

    @Test
    fun `include clauses are not references`() {
        val result = analyze("{% include 'x' ignore missing without context %}{% include tpl with context %}{% extends base %}")
        assertEquals(listOf("tpl", "base"), result.free())
    }

    @Test
    fun `raw bodies and comments are skipped`() {
        val result = analyze("{% raw %}{{ nope }}{% endraw %}{# {{ nope2 }} #}{{ yes }}")
        assertEquals(listOf("yes"), result.free())
    }

    // ------------------------------------------------------------------------------------------------ names

    @Test
    fun `filter and test names, including names passed as strings`() {
        val text = "{{ xs | map('basename') | select('defined') | selectattr('state', 'equalto', 'up') | map(attribute='name')" +
            " | ansible.builtin.splitext | list }}{% if a is ansible.builtin.version('1', '>') %}{% endif %}{% filter upper %}{% endfilter %}"
        val result = analyze(text)
        assertEquals(
            listOf("map", "basename", "select", "selectattr", "map", "ansible.builtin.splitext", "list", "upper"),
            result.filterNames.map { it.name },
        )
        assertEquals(listOf(false, true, false, false, false, false, false, false), result.filterNames.map { it.viaString })
        assertEquals(listOf("defined", "equalto", "ansible.builtin.version"), result.testNames.map { it.name })
        assertEquals(listOf(true, true, false), result.testNames.map { it.viaString })
        assertEquals("basename", text.textOf(result.filterNames[1]))
        assertEquals("ansible.builtin.splitext", text.textOf(result.filterNames[5]))
        assertEquals("ansible.builtin.version", text.textOf(result.testNames[2]))
        assertEquals(listOf("xs", "a"), result.free())
    }

    // ------------------------------------------------------------------------------------------------ modes and APIs

    @Test
    fun `expression mode analyses the whole input`() {
        val result = analyze("'collectors' in group_names and not known_hosts_file.stat.exists", EXPRESSION)
        assertEquals(listOf("group_names", "known_hosts_file.stat.exists"), result.free())
        val folded = analyze(
            "(app_settings_file is defined and app_settings_file.changed)\n" +
                "or (app_proxy_file is defined and app_proxy_file.changed)\n",
            EXPRESSION,
        )
        assertEquals(listOf(true, false, true, false), folded.references.map { it.guarded })
        assertEquals(listOf(false, true, false, true), folded.references.map { it.guardedByCondition })
        assertEquals(listOf("splitext"), analyze("x | ansible.builtin.splitext", EXPRESSION).filterNames.map { it.name.substringAfterLast('.') })
    }

    @Test
    fun `reference and locals at an offset`() {
        val text = "{% for x in xs %}{{ x.a }}{% endfor %}{{ item.b }}"
        val result = analyze(text)
        val inLoop = text.indexOf("x.a") + 2
        assertEquals("x", result.referenceAt(inLoop)?.name)
        assertNotNull(result.referenceAt(inLoop)?.local)
        assertEquals(listOf("x", "loop"), result.localsVisibleAt(inLoop).map { it.name }.sorted().reversed())
        val itemEnd = text.indexOf("item.b") + "item.b".length
        assertEquals(listOf("b"), result.referenceAt(itemEnd)?.attrPath)
        assertTrue(result.localsVisibleAt(itemEnd).isEmpty())
        assertNull(result.referenceAt(0))
    }

    @Test
    fun `broken templates are tolerated`() {
        val inputs = listOf(
            "{% for x in y %}{{ x }}", "{% endfor %}{% endif %}{% endset %}{% else %}{{ z }}", "{{ a.", "{{ a[", "{{ a(",
            "{% set %}", "{% for %}", "{% macro %}", "{% macro m( %}", "{% call( %}", "{% with %}", "{% from %}",
            "{% import %}", "{{ (", "{{ ) }}", "{% if %}{% elif %}{% else %}{% endif %}", "{% set x | %}", "{{ x is }}",
            "{{ x | }}", "{{ 'abc }}", "{% raw %}", "{#", "{{", "{%", "{{ {'a': }}", "{{ a if }}", "{{ a if b else }}",
        )
        for (input in inputs) analyze(input)
        val unclosed = analyze("{% for x in y %}{{ x }}")
        assertEquals("{% for x in y %}{{ x }}".length, unclosed.localNamed("x").scope.endOffset)
        assertEquals(listOf("z"), analyze("{% endfor %}{% endif %}{{ z }}").free())
    }

    // ------------------------------------------------------------------------------------------------ synthetic templates

    @Test
    fun `namespace routes template`() {
        val result = analyze(jinjaTestData.resolve("templates/namespace-routes.yml.j2").readText())
        assertEquals(
            setOf("ansible_managed", "namespace", "beacon_channels", "beacon_fallback_channel", "beacon_defaults"),
            result.references.map { it.name }.toSet(),
        )
        val attributes = result.locals.filter { it.kind == JinjaLocalKind.NAMESPACE_ATTRIBUTE }
        assertEquals(
            listOf("active" to "ns", "channel" to "fallback_ns", "channel" to "fallback_ns", "has_rules" to "rules_ns", "has_rules" to "rules_ns"),
            attributes.map { it.name to it.owner },
        )
        assertTrue(result.localReferences.any { it.name == "fallback" && it.attrPath == listOf("rule") && it.guarded })
        assertTrue("default guards", result.references.single { it.name == "beacon_fallback_channel" }.guarded)
    }

    @Test
    fun `macro template defining and calling env`() {
        val result = analyze(jinjaTestData.resolve("templates/compose-macro.yml.j2").readText())
        assertEquals(JinjaLocalKind.MACRO, result.localNamed("env").kind)
        assertEquals(listOf("name", "text", "env", "env", "env"), result.local())
        assertEquals(
            listOf(
                "bakery_stage", "bakery_slot",
                "bakery_stage", "bakery_slot",
                "bakery_web_port", "bakery_slot_offset",
                "bakery_metrics_port", "bakery_slot_offset",
                "bakery_debug", "bakery_db_host",
            ),
            result.free(),
        )
    }

    @Test
    fun `orphan template with undefined variables`() {
        val result = analyze(jinjaTestData.resolve("templates/kiosk.default.j2").readText())
        assertEquals(listOf("ansible_managed", "kiosk_listen_address", "kiosk_upstream_url"), result.free())
        assertTrue(result.references.none { it.guarded || it.guardedByCondition })
    }

    @Test
    fun `molecule Dockerfile reads the platform item`() {
        val result = analyze(jinjaTestData.resolve("templates/Dockerfile.j2").readText())
        assertEquals(listOf("item.image", "item.command", "item.command"), result.free())
        assertEquals(listOf("none"), result.testNames.map { it.name })
    }

    @Test
    fun `all synthetic templates analyse without errors`() {
        val templates = jinjaTestData.resolve("templates").toFile().listFiles { f -> f.name.endsWith(".j2") }!!
        for (template in templates) {
            val result = analyze(template.readText())
            for (ref in result.references + result.localReferences) {
                assertTrue(template.name, ref.nameRange.startOffset == ref.range.startOffset && ref.range.endOffset >= ref.nameRange.endOffset)
            }
        }
    }
}
