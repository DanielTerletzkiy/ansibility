package de.terletzkiy.ansibility.semantics.typeflow

import de.terletzkiy.ansibility.semantics.schema.OptionType
import de.terletzkiy.ansibility.semantics.typeflow.TypeflowTestSupport.CORE_218
import de.terletzkiy.ansibility.semantics.typeflow.TypeflowTestSupport.CORE_221
import de.terletzkiy.ansibility.semantics.typeflow.TypeflowTestSupport.evaluator
import de.terletzkiy.ansibility.semantics.value.PyValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * The ANS-T020 must-rule (plan A.6) for both core versions: a templated value is a finding only when its logical type
 * and its runtime type are both certainly outside the documented type.
 */
class TemplatedTypeCheckTest {
    private val vars = """
        max_connections: 65535
        port: 8080
        name: web
        items: [a, b]
        servers: [{host: a}]
        started: 2024-01-01
        alias: '{{ max_connections }}'
        nothing: ~
        token: !vault |
          ${'$'}ANSIBLE_VAULT;1.1;AES256
          6162
        mixed: 5
    """
    private val more = listOf("mixed: '5'")

    private fun verdict(core: String, template: String, documented: OptionType): TemplatedMismatch? {
        val semantics = if (core == "2.18") CORE_218 else CORE_221
        val types = evaluator(vars, semantics, more = more).evaluate(template)
        return TemplatedTypeCheck(semantics).check(documented, types)
    }

    @ParameterizedTest(name = "[{index}] {0} as {1}: 2.18 {2}, 2.21 {3}")
    @CsvSource(
        delimiter = ';',
        textBlock = """
        '{{ max_connections }}' ; str ; red ; red
        '{{ max_connections }}' ; int ; silent ; silent
        '{{ max_connections }}' ; float ; silent ; silent
        '{{ max_connections }}' ; bool ; red ; red
        '{{ max_connections }}' ; list ; red ; red
        '{{ max_connections }}' ; raw ; silent ; silent
        '{{ max_connections | int }}' ; str ; silent ; red
        '{{ max_connections | int }}' ; int ; silent ; silent
        '{{ port | string }}' ; int ; red ; red
        '{{ port | string }}' ; str ; silent ; silent
        '{{ items | join(",") }}' ; int ; red ; red
        '{{ items | join(",") }}' ; str ; silent ; silent
        '{{ items | join(",") }}' ; bool ; silent ; red
        '{{ items | join(",") }}' ; list ; silent ; red
        '{{ items | length }}' ; int ; silent ; silent
        '{{ items | length }}' ; str ; silent ; red
        '{{ port | bool }}' ; str ; red ; red
        '{{ port | int }}' ; bool ; red ; red
        '{{ items }}' ; str ; red ; red
        '{{ items }}' ; list ; silent ; silent
        '{{ items }}' ; int ; red ; red
        '{{ servers }}' ; dict ; red ; red
        '{{ name }}' ; int ; red ; red
        '{{ name }}' ; str ; silent ; silent
        '{{ started }}' ; str ; silent ; red
        '{{ alias }}' ; str ; silent ; red
        '{{ alias }}' ; int ; silent ; silent
        'x-{{ port }}' ; int ; red ; red
        'x-{{ port }}' ; str ; silent ; silent
        '[{{ port }}]' ; list ; silent ; red
        '{{ nothing }}' ; str ; silent ; silent
        '{{ token }}' ; int ; silent ; silent
        '{{ vault_db_password }}' ; int ; silent ; silent
        '{{ mixed }}' ; int ; silent ; silent
        '{{ mixed }}' ; str ; silent ; silent
        '{{ mixed }}' ; bool ; red ; red
        '{{ undefined_thing }}' ; int ; silent ; silent
        '{{ port if port else name }}' ; int ; silent ; silent
        '{{ port if port else name }}' ; list ; red ; red
        '{% if port %}{{ port }}{% endif %}' ; str ; silent ; silent""",
    )
    fun `must-rule matrix`(template: String, type: String, at218: String, at221: String) {
        val documented = OptionType.parse(type)
        assertEquals(at218, if (verdict("2.18", template, documented) != null) "red" else "silent", "2.18.8: $template as $type")
        assertEquals(at221, if (verdict("2.21", template, documented) != null) "red" else "silent", "2.21.4: $template as $type")
    }

    @Test
    fun `the haproxy chain is coerced and named`() {
        val mismatch = requireNotNull(verdict("2.18", "{{ max_connections }}", OptionType.Str))
        assertEquals(RuntimeOutcome.Coerced(PyValue.Int(65535), PyValue.Str("65535")), mismatch.outcome)
        assertEquals(PyValue.Int(65535), mismatch.logical.known)
        assertFalse(mismatch.certainRejection)
        val chain = (mismatch.origin as TypeOrigin.Chain).chain
        assertEquals("max_connections", chain.name)
        assertEquals("vars.yml:1", chain.definitions.single().label)
    }

    @Test
    fun `rejections are certain`() {
        val list = verdict("2.18", "{{ items }}", OptionType.Int)!!
        assertTrue(list.outcome is RuntimeOutcome.Rejected, "${list.outcome}")
        assertTrue(list.certainRejection)
        val joined = verdict("2.21", "{{ items | join(\",\") }}", OptionType.Int)!!
        assertEquals(RuntimeOutcome.ByType(TypeEffect.CONVERT_OR_REJECT), joined.outcome)
        assertFalse(joined.certainRejection)
        val dict = verdict("2.21", "{{ items | dict2items }}", OptionType.Dict)!!
        assertEquals(RuntimeOutcome.ByType(TypeEffect.REJECT), dict.outcome)
        assertTrue(dict.certainRejection)
    }

    @Test
    fun `type effects follow check_type`() {
        assertEquals(TypeEffect.COERCE, TemplatedTypeCheck.effect(OptionType.Str, setOf(BaseType.INT, BaseType.LIST)))
        assertEquals(TypeEffect.COERCE, TemplatedTypeCheck.effect(OptionType.List, setOf(BaseType.STR)))
        assertEquals(TypeEffect.REJECT, TemplatedTypeCheck.effect(OptionType.Int, setOf(BaseType.LIST, BaseType.DICT, BaseType.OTHER)))
        assertEquals(TypeEffect.CONVERT_OR_REJECT, TemplatedTypeCheck.effect(OptionType.Bool, setOf(BaseType.STR)))
        assertNull(TemplatedTypeCheck.accepts(OptionType.Raw, BaseType.STR))
        assertEquals(true, TemplatedTypeCheck.accepts(OptionType.Float, BaseType.INT))
    }
}
