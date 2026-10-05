package de.terletzkiy.ansibility.semantics.typeflow

import de.terletzkiy.ansibility.semantics.typeflow.TypeflowTestSupport.CORE_218
import de.terletzkiy.ansibility.semantics.typeflow.TypeflowTestSupport.CORE_221
import de.terletzkiy.ansibility.semantics.typeflow.TypeflowTestSupport.describe
import de.terletzkiy.ansibility.semantics.typeflow.TypeflowTestSupport.evaluator
import de.terletzkiy.ansibility.semantics.value.PyValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Logical and runtime types of every supported template shape and filter. The runtime columns are the result types
 * `Templar.template` produced for the same variables, measured with ansible-core 2.18.8 (the target repo's lint
 * image, also with `ANSIBLE_JINJA2_NATIVE`) and 2.21.4 (templates marked with `trust_as_template`). Where the
 * evaluator cannot know the text of a string, 2.18's column lists every type `literal_eval` could make of it
 * (`bool|str|list|dict|object`).
 */
class JinjaTypeEvaluatorTest {
    private val vars = """
        i: 5
        f: 3.5
        b: true
        s: abc
        n: ~
        l: [1, a]
        d: {a: 1}
        dt: 2024-01-01
        ldt: [2024-01-01]
        linf: [.inf]
        items: [a, b]
        a1: '{{ i }}'
        a2: '{{ a1 }}'
        al: '{{ l }}'
        ad: '{{ d }}'
        ab: '{{ b }}'
        af: '{{ f }}'
        as: '{{ s }}'
        st: 'True'
        sl: '[1, 2]'
        lt: ['{{ i }}', x]
        lu: ['{{ nowhere }}', x]
        cyc: '{{ cyc }}'
    """

    private val classic = evaluator(vars, CORE_218)
    private val native = evaluator(vars, CORE_221)

    @ParameterizedTest(name = "[{index}] {0}: logical {1}, 2.18 {2}, 2.21 {3}")
    @CsvSource(
        delimiter = ';',
        textBlock = """
        '{{ i }}' ; int ; int ; int
        '{{ a1 }}' ; int ; str ; int
        '{{ a2 }}' ; int ; str ; int
        '{{ al }}' ; list ; list ; list
        '{{ ad }}' ; dict ; dict ; dict
        '{{ ab }}' ; bool ; bool ; bool
        '{{ af }}' ; float ; str ; float
        '{{ as }}' ; str ; str ; str
        '{{ f }}' ; float ; float ; float
        '{{ b }}' ; bool ; bool ; bool
        '{{ s }}' ; str ; str ; str
        '{{ n }}' ; NoneType ; NoneType ; NoneType
        '{{ l }}' ; list ; list ; list
        '{{ d }}' ; dict ; dict ; dict
        '{{ dt }}' ; date ; str ; date
        '{{ ldt }}' ; list ; str ; list
        '{{ linf }}' ; list ; str ; list
        '{{ st }}' ; str ; bool ; str
        '{{ sl }}' ; str ; bool|str|list|dict|object ; str
        '{{ lt }}' ; list ; list ; list
        '{{ lu }}' ; list ; str|list ; list
        '{{ i | int }}' ; int ; str ; int
        '{{ s | int }}' ; int ; str ; int
        '{{ i | string }}' ; str ; str ; str
        '{{ i | float }}' ; float ; str ; float
        '{{ i | bool }}' ; bool ; bool ; bool
        '{{ items | join(",") }}' ; str ; bool|str|list|dict|object ; str
        '{{ items | length }}' ; int ; str ; int
        '{{ items | count }}' ; int ; str ; int
        '{{ items | list }}' ; list ; str|list ; list
        '{{ d | dict2items }}' ; list ; str|list ; list
        '{{ d | dict2items | items2dict }}' ; dict ; str|dict ; dict
        '{{ d | to_json }}' ; str ; str ; str
        '{{ d | to_nice_json }}' ; str ; str ; str
        '{{ d | to_yaml }}' ; str ; str ; str
        '{{ d | ansible.builtin.to_nice_yaml }}' ; str ; str ; str
        '{{ s | b64encode }}' ; str ; str ; str
        '{{ "YWJj" | b64decode }}' ; str ; bool|str|list|dict|object ; str
        '{{ s | upper }}' ; str ; bool|str|list|dict|object ; str
        '{{ s | lower }}' ; str ; bool|str|list|dict|object ; str
        '{{ s | trim }}' ; str ; bool|str|list|dict|object ; str
        '{{ s | community.general.foo }}' ; unknown ; unknown ; unknown
        '{{ undefined_x | default(3) }}' ; unknown ; unknown ; unknown
        '{{ i | default("a") }}' ; int|str ; str ; int|str
        '{{ i | d(2.5) }}' ; int|float ; str ; int|float
        '{{ i if b else s }}' ; int|str ; str ; int|str
        '{{ i if b }}' ; unknown ; unknown ; unknown
        '{{ "a" ~ i }}' ; str ; str ; str
        '{{ i ~ "" }}' ; str ; str ; str
        '{{ "[" ~ i }}' ; str ; bool|str|list|dict|object ; str
        '{{ i }}{{ i }}' ; str ; str ; str
        '{{ s }}{{ s }}' ; str ; bool|str|list|dict|object ; str
        'x-{{ i }}' ; str ; str ; str
        '[{{ i }}]' ; str ; bool|str|list|dict|object ; str
        '{{ "[" }}{{ i }}]' ; str ; bool|str|list|dict|object ; str
        '{{ "Tr" }}ue' ; str ; bool|str|list|dict|object ; str
        '{{ "[1,2]" }}' ; str ; bool|str|list|dict|object ; str
        '{{ "True" }}' ; str ; bool ; str
        '{{ "true" }}' ; str ; str ; str
        '{% if b %}1{% endif %}' ; unknown ; unknown ; unknown
        '{% if b %}{{ i }}{% endif %}' ; unknown ; unknown ; unknown
        '{{ d.a }}' ; int ; str ; int
        '{{ d["a"] }}' ; int ; str ; int
        '{{ l[0] }}' ; int ; str ; int
        '{{ l.1 }}' ; str ; str ; str
        '{{ l[-1] }}' ; str ; str ; str
        '{{ d.items }}' ; unknown ; unknown ; unknown
        '{{ d.b }}' ; unknown ; unknown ; unknown
        '{{ 5 }}' ; int ; str ; int
        '{{ "x" }}' ; str ; str ; str
        '{{ [1,2] }}' ; list ; list ; list
        '{{ [i, s] }}' ; list ; list ; list
        '{{ {"k": i} }}' ; dict ; dict ; dict
        '{{ (1, 2) }}' ; object ; str|object ; list
        '{{ i + 1 }}' ; unknown ; unknown ; unknown
        '{{ -i }}' ; unknown ; unknown ; unknown
        '{{ lookup("env", "HOME") }}' ; unknown ; unknown ; unknown
        '{{ items | join(",") | int }}' ; int ; str ; int
        '{{ i is defined }}' ; bool ; bool ; bool
        '{{ i > 1 }}' ; bool ; bool ; bool
        '{{ i in l }}' ; bool ; bool ; bool
        '{{ not b }}' ; bool ; bool ; bool
        '{{ b and i }}' ; bool|int ; bool|str ; bool|int
        '{{ cyc }}' ; unknown ; unknown ; unknown
        '{{ nowhere }}' ; unknown ; unknown ; unknown
        '{{ vault_x }}' ; vault ; vault ; vault
        '{# c #}abc' ; str ; str ; str
        '{# c #}[1]' ; str ; bool|str|list|dict|object ; str
        '{{ i' ; unknown ; unknown ; unknown""",
    )
    fun `template types per core`(template: String, logical: String, runtime218: String, runtime221: String) {
        val classicTypes = classic.evaluate(template)
        val nativeTypes = native.evaluate(template)
        assertEquals(logical, describe(classicTypes.logical), "logical type of $template")
        assertEquals(logical, describe(nativeTypes.logical), "logical type of $template (2.21)")
        assertEquals(runtime218, describe(classicTypes.runtime), "2.18.8 runtime type of $template")
        assertEquals(runtime221, describe(nativeTypes.runtime), "2.21.4 runtime type of $template")
    }

    @Test
    fun `a single trailing newline keeps the shortcut and the native value`() {
        assertEquals("int", describe(classic.evaluate("{{ i }}\n").runtime))
        assertEquals("str", describe(classic.evaluate("{{ i | int }}\n").runtime))
        assertEquals("int", describe(native.evaluate("{{ i | int }}\n").runtime))
        assertEquals("str", describe(native.evaluate("{{ i }}\n\n").runtime))
    }

    @Test
    fun `known values are kept along a literal chain`() {
        val types = classic.evaluate("{{ i }}")
        assertEquals(PyValue.Int(5), types.logical.known)
        assertEquals(PyValue.Int(5), types.runtime.known)
        assertEquals(PyValue.Str("5"), classic.evaluate("{{ a1 }}").runtime.known)
        assertEquals(PyValue.Bool(true), classic.evaluate("{{ st }}").runtime.known)
        assertEquals(PyValue.Str("1"), classic.evaluate("{{ d.a }}").runtime.known)
    }

    @Test
    fun `jinja2_native on 2_18 keeps natives and makes rendered strings unknown`() {
        val legacy = evaluator(vars, CORE_218, jinja2Native = true)
        assertEquals("int", describe(legacy.evaluate("{{ i }}").runtime))
        assertEquals("int", describe(legacy.evaluate("{{ a1 }}").runtime))
        assertEquals("int", describe(legacy.evaluate("{{ i | int }}").runtime))
        assertEquals("list", describe(legacy.evaluate("{{ d | dict2items }}").runtime))
        assertEquals("unknown", describe(legacy.evaluate("{{ i | string }}").runtime))
        assertEquals("unknown", describe(legacy.evaluate("{{ s }}").runtime))
        assertEquals("unknown", describe(legacy.evaluate("{{ i }}{{ i }}").runtime))
        assertEquals("int", describe(legacy.evaluate("{{ i | int }}").logical))
    }

    @Test
    fun `every definition counts and one unknown definition makes the chain unknown`() {
        val twice = evaluator("x: 5", more = listOf("x: 7"))
        assertEquals("int", describe(twice.evaluate("{{ x }}").logical))
        assertNull(twice.evaluate("{{ x }}").logical.known, "two values: none is shown")
        val mixed = evaluator("x: 5", more = listOf("x: five"))
        assertEquals("int|str", describe(mixed.evaluate("{{ x }}").logical))
        val withRegister = evaluator("x: 5\nr: 1", more = listOf("x: 1"), runtimeOnly = setOf("x"))
        assertEquals("unknown", describe(withRegister.evaluate("{{ x }}").logical))
    }

    @Test
    fun `chains follow at most the configured depth and stop at cycles`() {
        val chain = (1..10).joinToString("\n") { "v$it: '{{ v${it + 1} }}'" } + "\nv11: 5"
        assertEquals("int", describe(evaluator(chain, maxDepth = 11).evaluate("{{ v1 }}").logical))
        assertEquals("unknown", describe(evaluator(chain, maxDepth = 8).evaluate("{{ v1 }}").logical))
        assertEquals("int", describe(evaluator(chain, maxDepth = 8).evaluate("{{ v4 }}").logical))
        val cycle = evaluator("a: '{{ b }}'\nb: '{{ a }}'\nc: '{{ a | default(1) }}'")
        assertEquals("unknown", describe(cycle.evaluate("{{ a }}").logical))
        assertEquals("unknown", describe(cycle.evaluate("{{ c }}").logical))
    }

    @Test
    fun `vault values and secret definitions never expose a value`() {
        val vault = evaluator("token: !vault |\n  \$ANSIBLE_VAULT;1.1;AES256\n  6162\nport: 5", secrets = setOf("port"))
        assertTrue(vault.evaluate("{{ token }}").logical.vault)
        assertTrue(vault.evaluate("{{ vault_anything }}").runtime.vault)
        val port = vault.evaluate("{{ port }}")
        assertEquals("int", describe(port.logical))
        assertNull(port.logical.known, "a secret definition's value is not kept")
        val trace = (port.origin as TypeOrigin.Chain).chain.definitions.single()
        assertNull(trace.shown)
    }

    @Test
    fun `the chain trace names every hop`() {
        val types = classic.evaluate("{{ a2 }}")
        val chain = assertInstanceOf(TypeOrigin.Chain::class.java, types.origin).chain
        assertEquals("a2", chain.name)
        val a1 = chain.definitions.single().next!!
        assertEquals("a1", a1.name)
        val i = a1.definitions.single().next!!
        assertEquals("i", i.name)
        assertEquals("vars.yml:1", i.definitions.single().label)
        assertEquals("5", i.definitions.single().shown)
        assertEquals(setOf(BaseType.INT), i.definitions.single().types)
        val attribute = assertInstanceOf(TypeOrigin.Chain::class.java, classic.evaluate("{{ d.a }}").origin)
        assertEquals(listOf("a"), attribute.accessors)
        assertEquals(TypeOrigin.Filter("int"), classic.evaluate("{{ i | int }}").origin)
        assertEquals(TypeOrigin.MultiNode, classic.evaluate("x-{{ i }}").origin)
    }

    @Test
    fun `members of runtime-only values take the resolver's member types`() {
        val asked = ArrayList<List<String>>()
        val resolver = object : VariableResolver {
            override fun definitions(name: String): List<VariableDefinition>? =
                listOf(VariableDefinition("tasks/main.yml:3", null)).takeIf { name == "r" || name == "vault_r" }

            override fun memberType(name: String, path: List<String>): AValue? {
                asked += listOf(name) + path
                if (name != "r") return null
                return when (path) {
                    listOf("rc"), listOf("results", "-1", "rc") -> AValue.of(BaseType.INT)
                    listOf("stdout"), listOf("results", "0", "stdout") -> AValue.of(BaseType.STR)
                    listOf("items") -> AValue.of(BaseType.LIST)
                    else -> null
                }
            }
        }
        val classic = JinjaTypeEvaluator(TemplatingRules(CORE_218, false), TestJinjaTokenizer, resolver)
        val native = JinjaTypeEvaluator(TemplatingRules(CORE_221, false), TestJinjaTokenizer, resolver)

        val rc = classic.evaluate("{{ r.rc }}")
        assertEquals("int", describe(rc.logical))
        assertEquals("str", describe(rc.runtime), "2.18 renders the int to a string")
        assertEquals("int", describe(native.evaluate("{{ r.rc }}").runtime))
        assertEquals(listOf("rc"), assertInstanceOf(TypeOrigin.Chain::class.java, rc.origin).accessors)
        assertEquals("str", describe(classic.evaluate("{{ r['stdout'] }}").logical))
        assertEquals("str", describe(classic.evaluate("{{ r.results[0].stdout }}").logical))
        assertEquals("str", describe(native.evaluate("{{ r['results'][0]['stdout'] }}").runtime))
        assertEquals("int", describe(classic.evaluate("{{ r.results[-1].rc }}").logical))
        assertEquals("int", describe(classic.evaluate("{{ r.rc | default(0) }}").logical))

        assertEquals("unknown", describe(classic.evaluate("{{ r.nope }}").logical), "undocumented")
        assertEquals("unknown", describe(classic.evaluate("{{ r }}").logical), "the whole result")
        asked.clear()
        assertEquals("unknown", describe(classic.evaluate("{{ r.items }}").logical), "the dict method")
        assertEquals("unknown", describe(classic.evaluate("{{ r[k].rc }}").logical), "a computed key")
        assertEquals("unknown", describe(classic.evaluate("{{ (r | default({})).rc }}").logical), "not a chain")
        assertEquals("unknown", describe(classic.evaluate("{{ vault_r.rc }}").logical), "never typed through a vault name")
        assertEquals(emptyList<List<String>>(), asked, "the resolver is not asked for these")
        assertNull(VariableResolver { null }.memberType("r", listOf("rc")), "the default knows no members")
    }
}
