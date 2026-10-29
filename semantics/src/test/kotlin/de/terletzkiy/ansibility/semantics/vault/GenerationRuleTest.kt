package de.terletzkiy.ansibility.semantics.vault

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Plan amendment R29, D218 rule 1: shell-style globs on the last mapping key, first match wins, `*` as a default. */
class GenerationRuleTest {
    private val cookie = GenerationRule("vault_*_cookie_secret", GeneratorKind.HEX, 32)
    private val vrrp = GenerationRule("vault_vrrp_password", GeneratorKind.ALPHANUMERIC, 8)
    private val hmac = GenerationRule("vault_proxy_hmac_secre?", GeneratorKind.BASE64, 32)
    private val default = GenerationRule(GenerationRule.ANY, GeneratorKind.ALPHANUMERIC, 32)

    @Test
    fun `globs match whole keys with star and question mark`() {
        assertTrue(cookie.matches("vault_app_cookie_secret"))
        assertTrue(cookie.matches("vault__cookie_secret"))
        assertFalse(cookie.matches("vault_app_cookie_secret_old"))
        assertFalse(cookie.matches("app_cookie_secret"))
        assertTrue(hmac.matches("vault_proxy_hmac_secret"))
        assertFalse(hmac.matches("vault_proxy_hmac_secre"))
        assertTrue(GenerationRule.globMatches("*", ""))
        assertTrue(GenerationRule.globMatches("a*b*c", "aXXbYYc"))
        assertFalse(GenerationRule.globMatches("a*b*c", "aXXbYY"))
        assertTrue(GenerationRule.globMatches("**x", "abx"))
        assertFalse(GenerationRule.globMatches("VAULT_*", "vault_x"), "case-sensitive")
    }

    @Test
    fun `the first matching rule wins and star is a default`() {
        val rules = listOf(cookie, vrrp, hmac, default)
        assertEquals(cookie, GenerationRule.firstMatch(rules, "vault_app_cookie_secret"))
        assertEquals(vrrp, GenerationRule.firstMatch(rules, "vault_vrrp_password"))
        assertEquals(default, GenerationRule.firstMatch(rules, "vault_db_password"))
        assertEquals(default, GenerationRule.firstMatch(listOf(default, cookie), "vault_app_cookie_secret"))
        assertNull(GenerationRule.firstMatch(listOf(cookie, vrrp), "vault_db_password"))
        assertEquals(default, GenerationRule.firstMatch(listOf(cookie, default), null), "a sequence item has no key: only a default matches")
    }

    @Test
    fun `a rule's generator follows its kind and length`() {
        assertEquals(SecretGenerator(GeneratorKind.HEX, 32), cookie.generator)
        assertEquals(SecretGenerator(GeneratorKind.BASE64, 32), hmac.generator)
        assertEquals(512, GenerationRule("*", GeneratorKind.ALPHANUMERIC, 9999).generator.length, "an out-of-range length is clamped")
    }
}
