package de.terletzkiy.ansibility.semantics.vault

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * `VaultConfig` against `config-2.18.json` / `config-2.21.json`: for every probe row, the `DEFAULT_VAULT_*` values
 * ansible-core computed, and the secrets `setup_vault_secrets` loaded (label, kind, path, client id, bytes, order),
 * reproduced from the same configuration and the same synthetic files. Covers path resolution (cfg dir vs cwd,
 * `{{CWD}}`, `~`, `$VAR`), the `vault_id_match` quirk, quoting, strip rules, vaulted password files, client and plain
 * scripts, and prompts.
 */
class VaultConfigTest {
    @TestFactory
    fun `configuration rows`(): List<DynamicContainer> = VaultTestData.VERSIONS.map { version ->
        val table = VaultTestData.config(version)
        DynamicContainer.dynamicContainer(version, table.rows("config").map { row ->
            DynamicTest.dynamicTest(row.str("id")) { checkRow(OracleTree(table), row) }
        })
    }

    private fun checkRow(tree: OracleTree, row: Map<String, Any?>) {
        val config = tree.config(row)
        val constants = row.obj("constants")
        assertEquals(constants.strings("DEFAULT_VAULT_IDENTITY_LIST").map { it.inTree() }, config.identityList, "identity list")
        assertEquals(constants.strOrNull("DEFAULT_VAULT_PASSWORD_FILE")?.inTree(), config.passwordFile, "password file")
        assertEquals(constants.str("DEFAULT_VAULT_IDENTITY"), config.defaultIdentity, "vault_identity")
        when (val match = constants["DEFAULT_VAULT_ID_MATCH"]) {
            false -> assertNull(config.idMatchRaw, "vault_id_match unset")
            else -> assertEquals(match, config.idMatchRaw, "vault_id_match as Ansible holds it")
        }
        assertEquals(constants.strOrNull("DEFAULT_VAULT_ENCRYPT_IDENTITY"), config.encryptIdentity, "vault_encrypt_identity")
        assertEquals(constants.strOrNull("VAULT_ENCRYPT_SALT"), config.encryptSalt, "vault_encrypt_salt")

        val result = tree.load(config, row.strOrNull("promptHex"))
        val expected = row.rows("secrets")
        if (row.str("id") == "prompt-whitespace-only") {
            // The one deliberate difference: Ansible loads whitespace-only prompt input as an empty secret that never
            // decrypts anything; SecretBytes refuses it.
            assertEquals(listOf(""), expected.map { it.str("bytesHex") })
            assertEquals(SecretLoad.Failed(SecretLoadFailure.EMPTY), result.steps.single().load)
            return
        }
        val loadedSteps = result.steps.filter { it.load is SecretLoad.Loaded }
        assertEquals(expected.map { it.str("label") }, result.secrets.map { it.label }, "labels in order")
        for ((exp, step) in expected.zip(loadedSteps)) {
            val kind = tree.kindOf(step.slot)!!
            assertEquals(exp.str("type"), OracleTree.ansibleType(kind), "kind of ${step.slot}")
            assertEquals(exp.strOrNull("filename")?.inTree(), step.slot.path, "path")
            assertEquals(exp.strOrNull("clientVaultId"),
                if (kind == PasswordSourceKind.CLIENT_SCRIPT) step.slot.clientVaultId else null, "client --vault-id")
            (step.load as SecretLoad.Loaded).secret.read { assertEquals(exp.str("bytesHex"), hex(it), "secret bytes") }
        }
        assertEquals(row["error"] != null, result.failedEntirely, "Ansible fails only when no slot gave a secret")
        assertEquals(row.strings("scriptArgs"), tree.scriptRuns, "script arguments")
        result.secrets.forEach { it.secret.zero() }
    }

    @TestFactory
    fun `the order of tries for a configuration, as -vvvvv printed it`(): List<DynamicContainer> = VaultTestData.VERSIONS.map { version ->
        val table = VaultTestData.config(version)
        DynamicContainer.dynamicContainer(version, table.rows("tries").map { row ->
            DynamicTest.dynamicTest(row.str("id")) {
                val tree = OracleTree(table)
                val config = tree.config(row)
                val result = tree.load(config, null)
                val envelope = VaultTestData.envelope(VaultTestData.vector(row.str("envelope")).envelope)
                val outcome = VaultMatcher.decrypt(envelope, result.secrets, config.idMatch, config.defaultIdentity)
                when (outcome) {
                    is DecryptOutcome.Decrypted -> outcome.use {
                        assertEquals("OK", row["result"])
                        assertEquals(row["via"], it.label)
                        assertEquals(row.strings("tried"), it.tried)
                    }
                    is DecryptOutcome.NoSecretWorked -> {
                        assertEquals("FAILED", row["result"])
                        assertEquals(row.strings("tried"), outcome.tried)
                    }
                    is DecryptOutcome.FormatError -> error("unexpected $outcome")
                }
                result.secrets.forEach { it.secret.zero() }
            }
        })
    }

    @Test
    fun `vault_id_match is on for any non-empty value, false included`() {
        fun match(value: String?, fromEnv: Boolean = false): VaultConfig {
            val cfg = if (value != null && !fromEnv) mapOf("vault_id_match" to value) else emptyMap()
            val env = if (value != null && fromEnv) mapOf("ANSIBLE_VAULT_ID_MATCH" to value) else emptyMap()
            return VaultConfig.resolve(cfg, "/r", env, "/r")
        }
        assertFalse(match(null).idMatch)
        for (value in listOf("false", "False", "0", "no", "off", "true", "yes", "anything")) {
            assertTrue(match(value).idMatch, value)
            assertTrue(match(value, fromEnv = true).idMatch, "env $value")
        }
        assertTrue(match("false").idMatchLooksFalse)
        assertTrue(match("No").idMatchLooksFalse)
        assertFalse(match("true").idMatchLooksFalse)
        assertFalse(match(null).idMatchLooksFalse)
        assertFalse(match("").idMatch)
        assertFalse(match("\"\"").idMatch, "an ini value is unquoted")
        assertTrue(match("\"\"", fromEnv = true).idMatch, "an environment value is not")
        assertEquals(ConfigOrigin.ANSIBLE_CFG, match("false").originOf(VaultSetting.ID_MATCH))
        assertEquals(ConfigOrigin.ENVIRONMENT, match("false", fromEnv = true).originOf(VaultSetting.ID_MATCH))
        assertEquals(ConfigOrigin.DEFAULT, match(null).originOf(VaultSetting.ID_MATCH))
    }

    @Test
    fun `slots follow Ansible's order and labels`() {
        val config = VaultConfig.resolve(
            mapOf("vault_identity_list" to "prod@pw-prod.txt, dev@prompt, pw.txt, @x-client.sh, , team@prompt_ask_vault_pass",
                "vault_password_file" to "pw1.txt", "vault_identity" to "base"),
            "/repo/cfg", emptyMap(), "/repo/root",
        )
        assertEquals(listOf("prod", "dev", "base", "base", "team", "base"), config.slots.map { it.label })
        assertEquals(listOf(SlotSource.IDENTITY_LIST, SlotSource.IDENTITY_LIST, SlotSource.IDENTITY_LIST,
            SlotSource.IDENTITY_LIST, SlotSource.IDENTITY_LIST, SlotSource.PASSWORD_FILE), config.slots.map { it.source })
        assertEquals(listOf("/repo/root/pw-prod.txt", null, "/repo/root/pw.txt", "/repo/root/x-client.sh", null,
            "/repo/cfg/pw1.txt"), config.slots.map { it.path })
        assertEquals(listOf("prod", "dev", null, null, "team", "base"), config.slots.map { it.clientVaultId })
        assertEquals(listOf(false, true, false, false, true, false), config.slots.map { it.isPrompt })
        assertEquals("", config.identityList[4], "empty entries stay in the list but load nothing")
    }

    @Test
    fun `an empty or missing configuration has no slot`() {
        val config = VaultConfig.resolve(emptyMap(), null, emptyMap(), "/r")
        assertEquals(emptyList<VaultSecretSlot>(), config.slots)
        assertEquals("default", config.defaultIdentity)
        assertNull(config.passwordFile)
        assertNull(config.encryptSaltBytes())
        val empty = VaultConfig.resolve(mapOf("vault_password_file" to ""), "/c", emptyMap(), "/r")
        assertEquals(emptyList<VaultSecretSlot>(), empty.slots)
        assertEquals("salt", String(VaultConfig.resolve(emptyMap(), null, mapOf("ANSIBLE_VAULT_ENCRYPT_SALT" to "salt"), "/r")
            .encryptSaltBytes()!!))
    }

    @Test
    fun `Python path rules`() {
        val paths = PyPaths(mapOf("A" to "/a", "E" to "", "_x1" to "y"), home = "/home/u/")
        assertEquals("/a/b", paths.expandVars("\$A/b"))
        assertEquals("/a/b", paths.expandVars("\${A}/b"))
        assertEquals("\$NOPE/b", paths.expandVars("\$NOPE/b"))
        assertEquals("/b", paths.expandVars("\$E/b"))
        assertEquals("y-y", paths.expandVars("\$_x1-\${_x1}"))
        assertEquals("\${}x", paths.expandVars("\${}x"))
        assertEquals("\$", paths.expandVars("\$"))
        assertEquals("/home/u/p", paths.expandUser("~/p"))
        assertEquals("/home/u", paths.expandUser("~"))
        assertEquals("~bob/p", paths.expandUser("~bob/p"))
        assertEquals("~/p", PyPaths(emptyMap(), null).expandUser("~/p"))
        assertEquals("/", PyPaths(emptyMap(), "/").expandUser("~"))
        assertEquals("/a/c", PyPaths.normPath("/a/./b/../c/"))
        assertEquals("//a", PyPaths.normPath("//a"))
        assertEquals("/a", PyPaths.normPath("///a"))
        assertEquals("/a", PyPaths.normPath("/../a"))
        assertEquals("../b", PyPaths.normPath("a/../../b"))
        assertEquals(".", PyPaths.normPath(""))
        assertEquals(".", PyPaths.normPath("a/.."))
        assertEquals("/r/x", paths.unfrack("x", "/r"))
        assertEquals("/a/x", paths.unfrack("\$A/x", "/r"))
        assertEquals("/home/u/x", paths.unfrack("~/x", "/r"))
        assertEquals("/abs", paths.unfrack("/abs/./", "/r"))
    }

    @Test
    fun `Python quoting and stripping rules`() {
        assertEquals("x", PyText.unquote("\"x\""))
        assertEquals("x", PyText.unquote("'x'"))
        assertEquals("\"x'", PyText.unquote("\"x'"))
        assertEquals("\"x\\\"", PyText.unquote("\"x\\\""), "an escaped closing quote is kept")
        assertEquals("\"", PyText.unquote("\""))
        assertEquals("", PyText.unquote("''"))
        assertEquals("a b", PyText.strip("\u00A0\t a b \u3000\u2003\n"))
        assertEquals("x", PyText.strip("\u001Cx\u0085"))
    }

    @Test
    fun `slugs split at the first at sign`() {
        assertEquals(VaultIdSlug(null, "file"), VaultIdSlug.parse("file"))
        assertEquals(VaultIdSlug("dev", "a@b"), VaultIdSlug.parse("dev@a@b"))
        assertEquals(VaultIdSlug("", "file"), VaultIdSlug.parse("@file"))
        assertEquals("default", VaultIdSlug.parse("@file").label("default"))
        assertEquals("dev@a@b", VaultIdSlug.parse("dev@a@b").toString())
        assertTrue(VaultIdSlug.parse("x@prompt").isPrompt)
        assertTrue(VaultIdSlug.parse("prompt_ask_vault_pass").isPrompt)
        assertFalse(VaultIdSlug.parse("x@prompt.txt").isPrompt)
    }
}
