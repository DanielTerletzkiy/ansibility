package de.terletzkiy.ansibility.lang.jinja

import de.terletzkiy.ansibility.lang.jinja.lexer.JinjaLexMode
import de.terletzkiy.ansibility.lang.jinja.lexer.JinjaLexMode.EXPRESSION
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaIndirectRef
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaIndirection
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaIndirection.HOSTVARS
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaIndirection.VARS
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaRefs
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaRefsResult
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaVarRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU2 (plan amendment FU, F1.11): members read by name through `hostvars` and `vars` ([JinjaRefsResult.indirectReferences])
 * and the outside names of braced implicit expressions ([JinjaRefs.analyzeBracedExpression]). The `hostvars` cases are
 * the shapes of the fixture and the real repo (research `find-usages-inventory.md`, U14/U14b).
 */
class JinjaIndirectRefsTest {
    private fun analyze(text: String, mode: JinjaLexMode = JinjaLexMode.TEMPLATE) = JinjaRefs.analyze(text, mode)

    /** `via:name.path` of each indirect read, in order. */
    private fun JinjaRefsResult.indirect(): List<String> = indirectReferences.map { "${it.via}:" + (listOf(it.name) + it.attrPath).joinToString(".") }

    private fun JinjaRefsResult.free(): List<String> = references.map(::display)

    private fun display(ref: JinjaVarRef) = (listOf(ref.name) + ref.attrPath).joinToString(".")

    private fun String.at(ref: JinjaIndirectRef) = substring(ref.nameRange.startOffset, ref.nameRange.endOffset)

    private fun String.range(ref: JinjaIndirectRef) = substring(ref.range.startOffset, ref.range.endOffset)

    private fun String.root(ref: JinjaIndirectRef) = substring(ref.rootRange.startOffset, ref.rootRange.endOffset)

    // ------------------------------------------------------------------------------------------------ hostvars

    @Test
    fun `hostvars members after a dynamic host`() {
        val text = "{{ hostvars[percona_donor_host].ansible_host }}:3306 " +
            "{{ hostvars[groups['monitoring_client'][0]].loki_version }} " +
            "{{ hostvars[groups['keepalived_backup'][0]]['system_ip_floating'] }} " +
            "{% for _h in groups['preview_hosts'] | sort %}{{ hostvars[_h].preview_slug }}{% endfor %}"
        val result = analyze(text)
        assertEquals(
            listOf("HOSTVARS:ansible_host", "HOSTVARS:loki_version", "HOSTVARS:system_ip_floating", "HOSTVARS:preview_slug"),
            result.indirect(),
        )
        assertEquals(listOf("ansible_host", "loki_version", "system_ip_floating", "preview_slug"), result.indirectReferences.map { text.at(it) })
        assertEquals(listOf("hostvars", "hostvars", "hostvars", "hostvars"), result.indirectReferences.map { text.root(it) })
        // the direct references stay what they were: the member is no reference of its own
        assertEquals(
            listOf("hostvars", "percona_donor_host", "hostvars", "groups.monitoring_client.0", "hostvars", "groups.keepalived_backup.0", "groups.preview_hosts", "hostvars"),
            result.free(),
        )
        assertTrue(result.indirectReferences.none { it.guarded || it.guardedByCondition })
    }

    @Test
    fun `constant hosts and accessors after the member`() {
        val text = "{{ hostvars['web1'].ansible_host }} {{ hostvars.web1.ansible_port }} {{ hostvars[h].net.ipv4['address'][0] }}" +
            " {{ hostvars[h][\"double\"] }}"
        val result = analyze(text)
        assertEquals(
            listOf("HOSTVARS:ansible_host", "HOSTVARS:ansible_port", "HOSTVARS:net.ipv4.address.0", "HOSTVARS:double"),
            result.indirect(),
        )
        val net = result.indirectReferences[2]
        assertEquals("net", text.at(net))
        assertEquals("net.ipv4['address'][0]", text.range(net))
        assertEquals("double", text.at(result.indirectReferences[3]))
        // the existing reference keeps its constant path through the host
        assertEquals("hostvars.web1.ansible_host", result.free().first())
    }

    @Test
    fun `dynamic and non-member hostvars accesses are left out`() {
        val result = analyze(
            "{{ hostvars[h][name] }}{{ hostvars[h]['ansible-host'] }}{{ hostvars[h].keys() }}{{ hostvars[h] }}" +
                "{{ hostvars | length }}{{ hostvars[h][prefix ~ 'x'] }}{{ hostvars[h].get('x') }}",
        )
        assertEquals(emptyList<String>(), result.indirect())
    }

    @Test
    fun `extract members through map and the filter itself`() {
        val text = "{{ groups['app_mono'] | map('extract', hostvars, 'app_thrush_mono_s3_bucket') | reject('undefined') | list }}" +
            "{{ groups.db | map('ansible.builtin.extract', hostvars, ['ansible_default_ipv4', 'address']) | list }}" +
            "{{ 'web1' | extract(hostvars, 'ansible_host') }}{{ h | ansible.builtin.extract(hostvars, ['facts', k]) }}"
        val result = analyze(text)
        assertEquals(
            listOf("HOSTVARS:app_thrush_mono_s3_bucket", "HOSTVARS:ansible_default_ipv4.address", "HOSTVARS:ansible_host", "HOSTVARS:facts"),
            result.indirect(),
        )
        assertEquals("app_thrush_mono_s3_bucket", text.at(result.indirectReferences[0]))
        assertEquals("ansible_default_ipv4', 'address'", text.range(result.indirectReferences[1]))
        assertTrue("a guard after the map applies to the list", result.indirectReferences.none { it.guarded })
    }

    @Test
    fun `extract of other containers or dynamic keys is left out`() {
        val result = analyze(
            "{{ hosts | map('extract', other, 'x') }}{{ hosts | map('extract', hostvars) }}{{ hosts | map('extract', hostvars, name) }}" +
                "{{ hosts | map('attribute', hostvars, 'x') }}{{ hosts | select('extract', hostvars, 'x') }}",
        )
        assertEquals(emptyList<String>(), result.indirect())
    }

    // ------------------------------------------------------------------------------------------------ vars

    @Test
    fun `vars members and vars lookups`() {
        val text = "{{ vars['x'] }}{{ vars.y.z }}{{ vars[\"w\"] }}{{ lookup('vars', 'a') }}" +
            "{{ lookup('ansible.builtin.vars', 'b', 'c', default='') }}{{ query('vars', 'd') }}{{ q('vars', 'e') | first }}"
        val result = analyze(text)
        assertEquals(listOf("VARS:x", "VARS:y.z", "VARS:w", "VARS:a", "VARS:b", "VARS:c", "VARS:d", "VARS:e"), result.indirect())
        assertEquals(listOf("x", "y", "w", "a", "b", "c", "d", "e"), result.indirectReferences.map { text.at(it) })
        assertEquals(listOf("vars", "vars", "vars", "lookup", "lookup", "lookup", "query", "q"), result.indirectReferences.map { text.root(it) })
        assertEquals("only the default= argument guards a lookup", listOf(false, false, false, false, true, true, false, false), result.indirectReferences.map { it.guarded })
        assertEquals(listOf("vars.x", "vars.y.z", "vars.w", "lookup", "lookup", "query", "q"), result.free())
    }

    @Test
    fun `dynamic names and other lookups are left out`() {
        val result = analyze(
            "{{ vars[n] }}{{ vars.items() }}{{ vars }}{{ lookup('vars', 'p_' ~ x) }}{{ lookup('vars', item) }}{{ lookup('env', 'HOME') }}" +
                "{{ lookup(plugin, 'x') }}{{ lookup('vars') }}{{ query('ansible.builtin.varnames', '^a') }}",
        )
        assertEquals(emptyList<String>(), result.indirect())
    }

    @Test
    fun `template locals named like the magic variables are not followed`() {
        val result = analyze(
            "{% for vars in groups %}{{ vars.a }}{% endfor %}{% macro m(hostvars) %}{{ hostvars[h].b }}{% endmacro %}" +
                "{% set lookup = x %}{{ lookup('vars', 'c') }}{% with q = y %}{{ q('vars', 'd') }}{% endwith %}{{ vars.e }}",
        )
        assertEquals(listOf("VARS:e"), result.indirect())
    }

    // ------------------------------------------------------------------------------------------------ guards

    @Test
    fun `guards on members`() {
        val result = analyze(
            "{{ hostvars[h].a | default('') }}{{ vars['b'] is defined }}" +
                "{% if hostvars[h].c is defined %}{{ hostvars[other].c }}{{ hostvars[ h ]['c'] }}{{ hostvars[h].d }}{% endif %}" +
                "{% if e is defined %}{{ vars['e'] }}{{ hostvars[h].e }}{% endif %}{% if vars.f is defined %}{{ lookup('vars', 'f') }}{{ f }}{% endif %}",
        )
        assertEquals(
            listOf("HOSTVARS:a", "VARS:b", "HOSTVARS:c", "HOSTVARS:c", "HOSTVARS:c", "HOSTVARS:d", "VARS:e", "HOSTVARS:e", "VARS:f", "VARS:f"),
            result.indirect(),
        )
        assertEquals(listOf(true, true, true, false, false, false, false, false, true, false), result.indirectReferences.map { it.guarded })
        assertEquals(
            "a tested member guards the same member on the same host only; a tested name guards its vars member but not hostvars'",
            listOf(false, false, false, false, true, false, true, false, false, true),
            result.indirectReferences.map { it.guardedByCondition },
        )
        val extract = analyze("{% if hostvars[h].x is defined %}{{ hosts | map('extract', hostvars, 'x') }}{% endif %}")
        assertEquals("extract reads every host of the list", listOf(false, false), extract.indirectReferences.map { it.guardedByCondition })
        // direct references are unaffected by member tests: `vars.f is defined` does not mark the plain `f`
        assertFalse(result.references.single { it.name == "f" }.guardedByCondition)
    }

    @Test
    fun `member tests guard in expression mode too`() {
        val and = analyze("hostvars[h].x is defined and hostvars[h].x | length > 0", EXPRESSION)
        assertEquals(listOf("HOSTVARS:x", "HOSTVARS:x"), and.indirect())
        assertEquals(listOf(false, true), and.indirectReferences.map { it.guardedByCondition })
        val or = analyze("vars.y is not defined or vars.y | length == 0", EXPRESSION)
        assertEquals(listOf("VARS:y", "VARS:y"), or.indirect())
        assertEquals(listOf(false, true), or.indirectReferences.map { it.guardedByCondition })
    }

    @Test
    fun `indirect read at an offset`() {
        val text = "{{ hostvars[h].ansible_host }}"
        val result = analyze(text)
        assertEquals("ansible_host", result.indirectReferenceAt(text.indexOf("ansible_host") + 3)?.name)
        assertEquals("ansible_host", result.indirectReferenceAt(text.indexOf("ansible_host") + "ansible_host".length)?.name)
        assertNull(result.indirectReferenceAt(text.indexOf("hostvars")))
    }

    // ------------------------------------------------------------------------------------------------ braced expressions

    @Test
    fun `braced implicit expressions of the fixture`() {
        val coolify = "'{{ item }}=' in coolify_env_content"
        assertEquals(listOf("coolify_env_content"), JinjaRefs.analyzeBracedExpression(coolify).free())
        val keycloak = "'quay.io/keycloak/keycloak:{{ keycloak_version }}' in (compose_content.content | b64decode)"
        val result = JinjaRefs.analyzeBracedExpression(keycloak)
        assertEquals(listOf("compose_content.content"), result.free())
        val ref = result.references.single()
        assertEquals("compose_content", keycloak.substring(ref.nameRange.startOffset, ref.nameRange.endOffset))
        assertEquals(listOf("b64decode"), result.filterNames.map { it.name })
    }

    @Test
    fun `names outside the braces keep their offsets, flags and members`() {
        val text = "{{ a }} == b and \"{{ c }}\" in d.e and {% if f %}yes{% endif %} == g or {# note #} h is defined and h.i" +
            " and hostvars[k].m == '{{ n }}'"
        val result = JinjaRefs.analyzeBracedExpression(text)
        assertEquals(listOf("b", "d.e", "g", "h", "h.i", "hostvars", "k"), result.free())
        for (ref in result.references) assertEquals(ref.name, text.substring(ref.nameRange.startOffset, ref.nameRange.endOffset))
        assertTrue(result.references.single { it.attrPath == listOf("i") }.guardedByCondition)
        assertEquals(listOf("HOSTVARS:m"), result.indirect())
        assertEquals("m", text.at(result.indirectReferences.single()))
    }

    @Test
    fun `names assembled with a braced part are dynamic`() {
        assertEquals(emptyList<String>(), JinjaRefs.analyzeBracedExpression("prefix_{{ x }} is defined").free())
        assertEquals(emptyList<String>(), JinjaRefs.analyzeBracedExpression("{{ x }}_suffix | bool").free())
        assertEquals(emptyList<String>(), JinjaRefs.analyzeBracedExpression("{{ x }}").free())
        val members = JinjaRefs.analyzeBracedExpression("vars['{{ n }}'] and hostvars[h]['{{ m }}'] and {{ p }}vars.x and vars.x{{ q }}")
        assertEquals(emptyList<String>(), members.indirect())
        val subscript = JinjaRefs.analyzeBracedExpression("x['{{ k }}'].y and z[{{ k }}]")
        assertEquals("a braced subscript ends the constant path", listOf("x", "z"), subscript.free())
    }

    @Test
    fun `an attribute glued to a braced part ends the constant path`() {
        val text = "x.y.a{{ b }} == 1 and w.a {{- b }} and vars.v.a{{ b }} and z['k']{{ b }} and u.y {{ b }}"
        val result = JinjaRefs.analyzeBracedExpression(text)
        assertEquals("`a` is assembled at run time; `['k']` and a separated `.y` stay", listOf("x.y", "w", "vars.v", "z.k", "u.y"), result.free())
        assertEquals(listOf("x.y", "w", "vars.v", "z['k']", "u.y"), result.references.map { text.substring(it.range.startOffset, it.range.endOffset) })
        assertEquals(listOf("VARS:v"), result.indirect())
        assertEquals("v", text.range(result.indirectReferences.single()))
    }

    @Test
    fun `whitespace control and trim_blocks glue names to a braced part`() {
        // Jinja strips the whitespace next to a `-` marker, so `prefix_ {{- x }}` renders as one name `prefix_<x>`
        assertEquals(emptyList<String>(), JinjaRefs.analyzeBracedExpression("prefix_ {{- x }} is defined").free())
        assertEquals(emptyList<String>(), JinjaRefs.analyzeBracedExpression("{{ x -}}\n  _suffix | bool").free())
        assertEquals(emptyList<String>(), JinjaRefs.analyzeBracedExpression("a {#- note -#} b").free())
        assertEquals(emptyList<String>(), JinjaRefs.analyzeBracedExpression("vars.x {%- if c %}{% endif %} and v").indirect())
        // Ansible renders with trim_blocks: the newline after `%}` or `#}` goes, unless the tag ends with `+%}`
        assertEquals(listOf("v"), JinjaRefs.analyzeBracedExpression("{% if c %}\nb == 1 and v").free())
        assertEquals(listOf("b", "v"), JinjaRefs.analyzeBracedExpression("{% if c +%}\nb == 1 and v").free())
        assertEquals(listOf("v"), JinjaRefs.analyzeBracedExpression("{# c #}\nb == 1 and v").free())
        // without a marker the whitespace stays and separates the name from the part
        assertEquals(listOf("prefix_", "suffix"), JinjaRefs.analyzeBracedExpression("prefix_ {{ x }} suffix").free())
        assertEquals(listOf("a", "b"), JinjaRefs.analyzeBracedExpression("a {# note #} b").free())
        assertEquals("trim_blocks leaves output tags alone", listOf("b", "v"), JinjaRefs.analyzeBracedExpression("{{ x }}\nb == 1 and v").free())
    }

    @Test
    fun `strings are constant whatever characters they hold`() {
        // the masked parts of a braced expression are known by range: a private-use character in real text is just text
        val key = "\uE000"
        assertEquals(listOf("x.$key.y"), analyze("{{ x['$key'].y }}").free())
        assertEquals(listOf("x.$key"), JinjaRefs.analyzeBracedExpression("x['$key'] == '{{ y }}'").free())
        assertEquals(listOf("VARS:a"), analyze("{{ lookup('vars', 'a', default='$key') }}").indirect())
    }

    @Test
    fun `without braces it is a plain expression`() {
        val text = "a is defined and a.b | length > 0"
        assertEquals(analyze(text, EXPRESSION), JinjaRefs.analyzeBracedExpression(text))
    }

    @Test
    fun `the template analysis of a braced expression is unchanged`() {
        val text = "'{{ item }}=' in coolify_env_content"
        assertEquals(listOf("item"), analyze(text).free())
        assertEquals(emptyList<JinjaIndirection>(), analyze(text).indirectReferences.map { it.via })
    }

    @Test
    fun `via names the two kinds`() {
        assertEquals(listOf(HOSTVARS, VARS), analyze("{{ hostvars[h].a }}{{ vars.b }}").indirectReferences.map { it.via })
    }
}
