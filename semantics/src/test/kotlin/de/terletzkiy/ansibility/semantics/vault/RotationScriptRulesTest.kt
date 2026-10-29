package de.terletzkiy.ansibility.semantics.vault

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Plan amendment R29, X131: the rules of a rotation script, read from its `case` arms without running it. The scripts
 * are synthetic and shaped like a typical rotation script: a dispatcher, a length default, an alphanumeric helper, three
 * special rules and a default.
 */
class RotationScriptRulesTest {
    private val script = """
        #!/usr/bin/env bash
        set -euo pipefail
        # Rotates the vault_* values of vault.yml (synthetic example).
        PASSWORD_LENGTH="${'$'}{PASSWORD_LENGTH:-32}"

        generate_password() {
          local length="${'$'}{1:-${'$'}PASSWORD_LENGTH}"
          LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c "${'$'}length"
        }

        value_for() {
          local key="${'$'}1"
          case "${'$'}key" in
            vault_*_cookie_secret)
              openssl rand -hex 16
              ;;
            vault_vrrp_password)
              # VRRP allows 8 characters only
              generate_password 8
              ;;
            vault_proxy_hmac_secret|"vault_edge_hmac_secret")
              openssl rand -base64 32
              ;;
            vault_legacy_*)
              echo "keep"
              ;;
            *)
              generate_password "${'$'}PASSWORD_LENGTH"
              ;;
          esac
        }

        case "${'$'}{1:-}" in
          generate) for key in ${'$'}(keys); do value_for "${'$'}key"; done ;;
          encrypt) encrypt_all ;;
          *) echo "usage: ${'$'}0 generate|encrypt" >&2; exit 1 ;;
        esac
    """.trimIndent()

    @Test
    fun `the arms become rules in order, the default last`() {
        val parsed = RotationScriptRules.parse(script)
        assertEquals(
            listOf(
                GenerationRule("vault_*_cookie_secret", GeneratorKind.HEX, 32),
                GenerationRule("vault_vrrp_password", GeneratorKind.ALPHANUMERIC, 8),
                GenerationRule("vault_proxy_hmac_secret", GeneratorKind.BASE64, 32),
                GenerationRule("vault_edge_hmac_secret", GeneratorKind.BASE64, 32),
                GenerationRule("*", GeneratorKind.ALPHANUMERIC, 32),
            ),
            parsed.rules,
        )
        assertEquals(listOf("vault_legacy_*"), parsed.skipped, "an arm it does not understand; the dispatcher is no rule table")
    }

    @Test
    fun `the length default comes from the script, else 32`() {
        val longer = script.replace("PASSWORD_LENGTH:-32", "PASSWORD_LENGTH:-48")
        assertEquals(GenerationRule("*", GeneratorKind.ALPHANUMERIC, 48), RotationScriptRules.parse(longer).rules.last())
        val unset = script.lines().filterNot { it.startsWith("PASSWORD_LENGTH=") }.joinToString("\n")
        assertEquals(GenerationRule("*", GeneratorKind.ALPHANUMERIC, 32), RotationScriptRules.parse(unset).rules.last())
    }

    @Test
    fun `a call without an argument takes the function's own default`() {
        val text = """
            pw() {
              tr -dc "[:alnum:]" < /dev/urandom | head -c "${'$'}{1:-20}"
            }
            case ${'$'}name in
              (vault_a) pw ;;
              vault_b) pw 12 ;;
              vault_c) tr -dc 'A-Za-z0-9' </dev/urandom | head -c 24 ;;
            esac
        """.trimIndent()
        assertEquals(
            listOf(
                GenerationRule("vault_a", GeneratorKind.ALPHANUMERIC, 20),
                GenerationRule("vault_b", GeneratorKind.ALPHANUMERIC, 12),
                GenerationRule("vault_c", GeneratorKind.ALPHANUMERIC, 24),
            ),
            RotationScriptRules.parse(text).rules,
        )
    }

    @Test
    fun `a script without a rule table imports nothing`() {
        val parsed = RotationScriptRules.parse("#!/bin/sh\necho \"in case of trouble\"\ncase \"${'$'}1\" in\n  start) run ;;\nesac\n")
        assertTrue(parsed.rules.isEmpty())
        assertTrue(parsed.skipped.isEmpty())
        assertTrue(RotationScriptRules.parse("").rules.isEmpty())
    }
}
