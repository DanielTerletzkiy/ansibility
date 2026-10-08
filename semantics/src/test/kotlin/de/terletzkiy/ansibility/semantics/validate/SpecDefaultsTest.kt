package de.terletzkiy.ansibility.semantics.validate

import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.coerce.CoreSemantics
import de.terletzkiy.ansibility.semantics.diagnostics.Level
import de.terletzkiy.ansibility.semantics.testutil.YamlText
import de.terletzkiy.ansibility.semantics.validate.SpecDefaults.MismatchReason
import de.terletzkiy.ansibility.semantics.validate.SpecDefaults.UnknownReason
import de.terletzkiy.ansibility.semantics.validate.SpecDefaults.Verdict
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.semantics.yaml.YVault
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The documented-default comparator (plan amendment R23, D170): ansible-test's `doc-default-does-not-match-spec` rule
 * (both values through the option type's checker, then Python's `==`), with the null, Jinja, secret and cap rules.
 */
class SpecDefaultsTest {
    private val defaults = SpecDefaults()

    /** The verdict for an option written as a flow mapping (`{type: str, default: 1.5}`) and a role default written as YAML. */
    private fun verdict(option: String, roleDefault: String, comparator: SpecDefaults = defaults, secret: Boolean = false): Verdict =
        comparator.compare(SpecText.option("version", option), value(roleDefault), secret)

    private fun value(yaml: String): YValue = (YamlText.parse("v: $yaml") as YMap)["v"]!!

    private fun mismatch(cap: Level, reason: MismatchReason = MismatchReason.VALUE) = Verdict.Mismatch(cap, reason)

    @Test
    fun `the user's case - defaults 1_2 against a documented 1_5 is an error`() {
        assertEquals(mismatch(Level.ERROR), verdict("{default: 1.5}", "1.2"))
        assertEquals(mismatch(Level.ERROR), verdict("{type: str, default: \"1.5\"}", "1.2"))
        assertEquals(Verdict.Equal, verdict("{default: 1.2}", "1.2"))
    }

    @Test
    fun `ansible-test cases`() {
        // type | documented | role default | expected (EQUAL or the mismatch's cap)
        val table = listOf(
            // type-only differences are equal after the type's conversion
            listOf("str", "\"1.2\"", "1.2", "EQUAL"),
            listOf("str", "1.2", "\"1.2\"", "EQUAL"),
            listOf("str", "\"3.2\"", "3.2", "EQUAL"),
            listOf("bool", "yes", "true", "EQUAL"),
            listOf("bool", "\"yes\"", "true", "EQUAL"),
            listOf("bool", "True", "true", "EQUAL"),
            listOf("bool", "false", "no", "EQUAL"),
            listOf("int", "\"8080\"", "8080", "EQUAL"),
            listOf("int", "8080", "8081", "ERROR"),
            listOf("float", "2", "2.0", "EQUAL"),
            listOf("float", "\"3.10\"", "3.10", "EQUAL"),
            listOf("list", "[a, b]", "\"a,b\"", "EQUAL"),
            listOf("list", "[a, b]", "[b, a]", "ERROR"),
            listOf("list", "[1, 2]", "[1, 2, 3]", "ERROR"),
            listOf("dict", "{a: 1, b: 2}", "{b: 2, a: 1}", "EQUAL"),
            listOf("dict", "{}", "{k: 1}", "ERROR"),
            listOf("path", "~/x", "~/x", "EQUAL"),
            listOf("path", "~/x", "~/y", "ERROR"),
            listOf("raw", "1", "true", "ERROR"),
            listOf("raw", "1", "1.0", "ERROR"),
            listOf("raw", "[1, a]", "[1, a]", "EQUAL"),
            // a documented value that fails its own type is compared raw (S001 reports the type)
            listOf("int", "notanint", "5", "ERROR"),
            listOf("int", "notanint", "notanint", "EQUAL"),
            // same text, other YAML type: a typing hazard, WARNING
            listOf("str", "\"1.20\"", "1.20", "WARNING"),
            listOf("str", "\"3.10\"", "3.10", "WARNING"),
            listOf("str", "\"yes\"", "yes", "WARNING"),
        )
        for ((type, documented, roleDefault, expected) in table) {
            val verdict = verdict("{type: $type, default: $documented}", roleDefault)
            val outcome = when (verdict) {
                Verdict.Equal -> "EQUAL"
                is Verdict.Mismatch -> verdict.cap.name
                else -> verdict.toString()
            }
            assertEquals(expected, outcome, "$type documented $documented vs role $roleDefault: $verdict")
        }
    }

    @Test
    fun `same text mismatches name the YAML typing`() {
        assertEquals(mismatch(Level.WARNING, MismatchReason.YAML_TYPING), verdict("{type: str, default: \"1.20\"}", "1.20"))
    }

    @Test
    fun `elements and sub-options convert the keys that are present only`() {
        assertEquals(Verdict.Equal, verdict("{type: list, elements: int, default: ['1', '2']}", "[1, 2]"))
        assertEquals(mismatch(Level.ERROR), verdict("{type: list, elements: int, default: ['1', '3']}", "[1, 2]"))
        val dict = "{type: dict, options: {port: {type: int, default: 5432}, host: {type: str}}, default: {port: '80'}}"
        assertEquals(Verdict.Equal, verdict(dict, "{port: 80}"))
        assertEquals(mismatch(Level.ERROR), verdict(dict, "{port: 80, host: db}"), "sub-option defaults are never filled in")
        val items = "{type: list, elements: dict, options: {port: {type: int}}, default: [{port: '80'}]}"
        assertEquals(Verdict.Equal, verdict(items, "[{port: 80}]"))
        assertEquals(mismatch(Level.ERROR), verdict(items, "[{port: 81}]"))
    }

    @Test
    fun `no documented default makes no claim`() {
        assertEquals(Verdict.NoClaim, verdict("{type: str}", "1.2"))
        assertEquals(Verdict.NoClaim, verdict("{type: str, default: null}", "1.2"))
        assertEquals(Verdict.NoClaim, verdict("{type: str, default: ~}", "1.2"))
        assertEquals(Verdict.NoClaim, verdict("{type: str, default: }", "1.2"))
        assertNull(SpecDefaults.documentedDefault(SpecText.option("x", "{default: null}")))
    }

    @Test
    fun `spec errors make no claim`() {
        assertEquals(Verdict.NoClaim, verdict("{type: str, required: true, default: 1.5}", "1.2"), "required with a default is ANS-S001")
        assertEquals(Verdict.NoClaim, verdict("{type: string, default: 1.5}", "1.2"))
        assertEquals(Verdict.NoClaim, verdict("{type: list, elements: string, default: [a]}", "[b]"))
        assertEquals(Verdict.NoClaim, verdict("{type: str, default: 1.5}", "="), "an unloadable value")
    }

    @Test
    fun `null role defaults`() {
        assertEquals(mismatch(Level.ERROR, MismatchReason.NULL_ROLE_DEFAULT), verdict("{type: str, default: x}", "null"))
        assertEquals(mismatch(Level.ERROR, MismatchReason.NULL_ROLE_DEFAULT), verdict("{type: str, default: x}", ""))
        for (empty in listOf("''", "[]", "{}")) {
            assertEquals(mismatch(Level.WEAK_WARNING, MismatchReason.EMPTY_VS_NULL), verdict("{type: raw, default: $empty}", "~"), empty)
        }
        assertEquals(
            mismatch(Level.WEAK_WARNING, MismatchReason.EMPTY_VS_NULL),
            verdict("{type: str, default: ''}", "null", SpecDefaults(CoreSemantics(CoreVersion(2, 21, 4)))),
            "check_type_str(None) is '' since 2.19.1, but None is never coerced",
        )
    }

    @Test
    fun `secrets are never compared`() {
        val secret = Verdict.Unknown(UnknownReason.SECRET)
        assertEquals(secret, verdict("{type: str, default: a}", "b", secret = true))
        assertEquals(secret, verdict("{type: str, no_log: true, default: a}", "b"))
        assertEquals(secret, defaults.compare(SpecText.option("version", "{type: str, default: a}"), YVault()))
        assertEquals(secret, verdict("{type: dict, default: {a: 1}}", "{a: !vault x}"))
    }

    @Test
    fun `a no_log sub-option keeps its whole dict secret`() {
        val secret = Verdict.Unknown(UnknownReason.SECRET)
        val option = "{type: dict, options: {user: {type: str}, password: {type: str, no_log: true}}, default: {user: app, password: ''}}"
        assertEquals(secret, verdict(option, "{user: app, password: changeme}"))
        val deep = "{type: list, elements: dict, options: {auth: {type: dict, options: {token: {no_log: true}}}}, default: [{auth: {token: a}}]}"
        assertEquals(secret, verdict(deep, "[{auth: {token: b}}]"))
        assertEquals(mismatch(Level.ERROR), verdict("{type: dict, options: {user: {type: str}}, default: {user: app}}", "{user: web}"))
    }

    @Test
    fun `dicts that differ only in key order are equal whatever the type`() {
        // A missing type is str: str() of the two dicts differs by key order, but the task sees an equal dict.
        assertEquals(Verdict.Equal, verdict("{default: {LANG: C, TZ: UTC}}", "{TZ: UTC, LANG: C}"))
        assertEquals(Verdict.Equal, verdict("{default: [{a: 1, b: 2}]}", "[{b: 2, a: 1}]"))
        assertEquals(mismatch(Level.ERROR), verdict("{default: {LANG: C, TZ: UTC}}", "{TZ: CET, LANG: C}"))
        assertEquals(mismatch(Level.ERROR), verdict("{default: [a, b]}", "[b, a]"), "lists keep their order")
    }

    @Test
    fun `templates`() {
        assertEquals(Verdict.Equal, verdict("{default: '{{ base }}-x'}", "'{{base}}-x'"), "identical text, whitespace normalised")
        assertEquals(Verdict.Equal, verdict("{type: list, default: ['{{ a }}', b]}", "['{{a }}', b]"))
        assertEquals(Verdict.Unknown(UnknownReason.TEMPLATED), verdict("{default: '{{ a }}'}", "'{{ b }}'"))
        assertEquals(Verdict.Unknown(UnknownReason.TEMPLATED), verdict("{default: 1.5}", "'{{ base }}-x'"))
        assertEquals(Verdict.Unknown(UnknownReason.TEMPLATED), verdict("{default: '{{ x }}'}", "1.2"), "a templated documented default")
        assertEquals(mismatch(Level.ERROR), verdict("{default: 1.5}", "!unsafe '{{ x }}'"), "!unsafe is never templated")
    }

    @Test
    fun `a bare reference is followed to a literal and capped at warning`() {
        val values = mapOf("base" to value("1.2"), "alias" to value("'{{ base }}'"), "loop" to value("'{{ loop }}'"), "jinja" to value("'{{ a }}-b'"))
        val chained = SpecDefaults(resolve = values::get)
        assertEquals(Verdict.Mismatch(Level.WARNING, MismatchReason.CHAIN, "base", values["base"]), verdict("{default: 1.5}", "'{{ base }}'", chained))
        assertEquals(Verdict.Mismatch(Level.WARNING, MismatchReason.CHAIN, "base", values["base"]), verdict("{default: 1.5}", "'{{ alias }}'", chained))
        assertEquals(Verdict.Equal, verdict("{default: 1.2}", "'{{- base -}}'", chained))
        assertEquals(Verdict.Unknown(UnknownReason.TEMPLATED), verdict("{default: 1.5}", "'{{ loop }}'", chained), "a cycle")
        assertEquals(Verdict.Unknown(UnknownReason.TEMPLATED), verdict("{default: 1.5}", "'{{ jinja }}'", chained), "a template at the end")
        assertEquals(Verdict.Unknown(UnknownReason.TEMPLATED), verdict("{default: 1.5}", "'{{ other }}'", chained), "unknown name (or a secret the caller hides)")
        assertEquals(Verdict.Unknown(UnknownReason.TEMPLATED), verdict("{default: 1.5}", "'{{ base }}'"))
    }

    @Test
    fun `the target version decides conversions that changed`() {
        // `jsonarg` serialises dates since 2.19; 2.18 rejects them, so the raw values are compared.
        val option = "{type: jsonarg, default: '[\"2020-01-01\"]'}"
        assertEquals(Verdict.Equal, verdict(option, "[2020-01-01]", SpecDefaults(CoreSemantics(CoreVersion(2, 21, 4)))))
        assertEquals(mismatch(Level.ERROR), verdict(option, "[2020-01-01]", SpecDefaults(CoreSemantics(CoreVersion(2, 18, 8)))))
    }

    @Test
    fun `helpers`() {
        assertEquals(SpecDefaults.bareReference(YScalar("{{- x }}", ScalarStyle.DOUBLE_QUOTED)), "x")
        assertNull(SpecDefaults.bareReference(YScalar("{{ x }}-y", ScalarStyle.DOUBLE_QUOTED)))
        assertNull(SpecDefaults.bareReference(YScalar("{{ x }}", ScalarStyle.DOUBLE_QUOTED, tag = "!unsafe")))
        assertEquals(SpecDefaults.normalizeTemplate("{{a  |  b}} c {%-if x-%}"), "{{ a | b }} c {%- if x -%}")
    }
}
