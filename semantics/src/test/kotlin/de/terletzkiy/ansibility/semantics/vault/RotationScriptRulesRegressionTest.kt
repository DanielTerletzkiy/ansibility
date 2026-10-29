package de.terletzkiy.ansibility.semantics.vault

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression tests of the review of plan amendment R29 step A (findings 5 and 6, X131 and D218): the script's default
 * arm is never imported as a catch-all rule, and `case` patterns with bracket expressions match after the import, or
 * are skipped when a rule cannot read them. The first two tests are the review's own.
 */
class RotationScriptRulesRegressionTest {
    /** A synthetic rotation script with the three special arms the plan names and the script's default arm. */
    private val script = """
        #!/usr/bin/env bash
        PASSWORD_LENGTH="${'$'}{PASSWORD_LENGTH:-32}"
        generate_password() {
          LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c "${'$'}{1:-${'$'}PASSWORD_LENGTH}"
        }
        value_for() {
          case "${'$'}1" in
            vault_*_cookie_secret) openssl rand -hex 16 ;;
            vault_vrrp_password) generate_password 8 ;;
            vault_proxy_hmac_secret) openssl rand -base64 32 ;;
            *) generate_password ;;
          esac
        }
    """.trimIndent()

    /**
     * D218: "a rule of the root ... Your three script rules become three rows", then "same as now", then 32 alphanumeric.
     * The import must not turn the script's default arm into a fourth rule `*`, which would match every key first, so
     * "same as now" (D218 rule 2) would never apply again in that root.
     */
    @Test
    fun `the script's default arm is not imported as a catch-all rule`() {
        val rules = RotationScriptRules.parse(script).rules
        assertEquals(3, rules.size, "three script rules become three rows, got $rules")
        val catchAll = GenerationRule.firstMatch(rules, "vault_api_token")
        assertEquals(null, catchAll, "a key the script has no special arm for keeps D218's 'same as now'; matched by $catchAll")
    }

    /** A shell `case` pattern with a bracket expression is imported verbatim, and the rule must match what the arm matched. */
    @Test
    fun `a bracket expression of a case pattern still matches after the import`() {
        val bracketed = script.replace("vault_*_cookie_secret)", "vault_*_[Cc]ookie_secret)")
        val rules = RotationScriptRules.parse(bracketed).rules
        val rule = rules.first { it.kind == GeneratorKind.HEX }
        assertTrue(rule.matches("vault_app_cookie_secret"), "the script's arm matches vault_app_cookie_secret; the imported rule ${rule.pattern} does not")
    }

    @Test
    fun `the import reports the default arm and what it makes`() {
        assertEquals(SecretGenerator(GeneratorKind.ALPHANUMERIC, 32), RotationScriptRules.parse(script).defaultArm?.generator)
        val longer = RotationScriptRules.parse(script.replace("PASSWORD_LENGTH:-32", "PASSWORD_LENGTH:-48"))
        assertEquals(SecretGenerator(GeneratorKind.ALPHANUMERIC, 48), longer.defaultArm?.generator, "a default other than 32 is reported")
        assertFalse(longer.rules.any { GenerationRule.isCatchAll(it.pattern) })
        // A default arm the import does not understand is still left out, and not listed as skipped.
        val failing = RotationScriptRules.parse(script.replace("*) generate_password ;;", "*) echo \"unknown key\" >&2; exit 1 ;;"))
        assertNotNull(failing.defaultArm)
        assertNull(failing.defaultArm?.generator)
        assertTrue(failing.skipped.isEmpty(), "skipped: ${failing.skipped}")
        // A script without a default arm has none.
        assertNull(RotationScriptRules.parse(script.replace("    *) generate_password ;;\n", "")).defaultArm)
    }

    @Test
    fun `patterns a rule cannot read are skipped, the rest of their arm is imported`() {
        val text = script
            .replace("vault_vrrp_password)", "vault_vrrp_password|vault_[ab_password)")
            .replace("vault_proxy_hmac_secret)", "@(vault_proxy_hmac_secret|vault_edge_hmac_secret))")
        val parsed = RotationScriptRules.parse(text)
        assertEquals(listOf("vault_*_cookie_secret", "vault_vrrp_password"), parsed.rules.map { it.pattern })
        assertTrue("vault_[ab_password" in parsed.skipped, "skipped: ${parsed.skipped}")
        assertTrue(parsed.skipped.any { it.startsWith("@(") }, "an extended pattern is skipped whole: ${parsed.skipped}")
    }

    @Test
    fun `globs read bracket expressions as a shell does`() {
        val cookie = GenerationRule("vault_*_[Cc]ookie_secret", GeneratorKind.HEX, 32)
        assertTrue(cookie.matches("vault_app_Cookie_secret"))
        assertFalse(cookie.matches("vault_app_kookie_secret"))
        assertTrue(GenerationRule.globMatches("vault_[!a]*", "vault_b"))
        assertFalse(GenerationRule.globMatches("vault_[!a]*", "vault_a"))
        assertTrue(GenerationRule.globMatches("vault_[^a]*", "vault_b"))
        assertTrue(GenerationRule.globMatches("v[a-c]ult", "vbult"))
        assertFalse(GenerationRule.globMatches("v[a-c]ult", "vdult"))
        assertTrue(GenerationRule.globMatches("vault_[[:digit:]]", "vault_7"))
        assertFalse(GenerationRule.globMatches("vault_[[:digit:]]", "vault_x"))
        assertTrue(GenerationRule.globMatches("a[]]b", "a]b"), "a ']' first in the set is a member")
        assertTrue(GenerationRule.globMatches("a[-x]b", "a-b"), "a '-' first in the set is a member")
        assertTrue(GenerationRule.globMatches("a\\*b", "a*b"))
        assertFalse(GenerationRule.globMatches("a\\*b", "aXb"))
        assertTrue(GenerationRule.globMatches("*_[0-9][0-9]", "vault_db_12"))
    }

    @Test
    fun `a pattern that cannot be read matches nothing`() {
        for (bad in listOf("vault_[ab", "vault_\\", "vault_[[:nope:]]", "@(a|b)", "a|b", "(a)")) {
            assertFalse(GenerationRule.isValidPattern(bad), bad)
            assertFalse(GenerationRule(bad, GeneratorKind.HEX, 32).matches("vault_a"), bad)
            assertFalse(GenerationRule(bad, GeneratorKind.HEX, 32).isValidPattern, bad)
        }
        for (good in listOf("*", "vault_*", "vault_?", "vault_[ab]", "a[]]b", "x\\[y")) assertTrue(GenerationRule.isValidPattern(good), good)
        assertTrue(GenerationRule.isCatchAll("*") && GenerationRule.isCatchAll("**"))
        assertFalse(GenerationRule.isCatchAll("*_password") || GenerationRule.isCatchAll(""))
    }
}
