package de.terletzkiy.ansibility.semantics.vault

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The password-script protocol without running a process: kinds, command lines and exit codes. The command lines
 * for real configurations are also checked against the arguments the synthetic scripts received from ansible-core
 * (`VaultConfigTest`, `scriptArgs`).
 */
class VaultScriptProtocolTest {
    @Test
    fun `client scripts are recognised by name without extension`() {
        for (name in listOf("x-client", "x-client.sh", "/d/x-client.py", "dir.d/x-client", ".vault-client", "a/.b-client",
            "multi-client.sh", "x.y-client.rb")) {
            assertTrue(VaultScriptProtocol.isClientScriptName(name), name)
        }
        for (name in listOf("x-client.tar.gz", "x.client", "x-client/", "client", "x-clients.sh", "x-client-1.sh", "-client.d/x")) {
            assertFalse(VaultScriptProtocol.isClientScriptName(name), name)
        }
    }

    @Test
    fun `the executable bit decides between a file and a script`() {
        assertEquals(PasswordSourceKind.FILE, VaultScriptProtocol.kindOf("/r/x-client.sh", isExecutable = false))
        assertEquals(PasswordSourceKind.SCRIPT, VaultScriptProtocol.kindOf("/r/pw.sh", isExecutable = true))
        assertEquals(PasswordSourceKind.CLIENT_SCRIPT, VaultScriptProtocol.kindOf("/r/x-client.sh", isExecutable = true))
        val prompt = VaultConfig.resolve(mapOf("vault_identity_list" to "dev@prompt"), "/r", emptyMap(), "/r").slots.single()
        assertEquals(PasswordSourceKind.PROMPT, VaultScriptProtocol.kindOf(prompt, isExecutable = true))
    }

    @Test
    fun `command lines`() {
        assertEquals(listOf("/r/x-client.sh", "--vault-id", "prod"),
            VaultScriptProtocol.command("/r/x-client.sh", PasswordSourceKind.CLIENT_SCRIPT, "prod"))
        assertEquals(listOf("/r/x-client.sh"), VaultScriptProtocol.command("/r/x-client.sh", PasswordSourceKind.CLIENT_SCRIPT, null))
        assertEquals(listOf("/r/x-client.sh"), VaultScriptProtocol.command("/r/x-client.sh", PasswordSourceKind.CLIENT_SCRIPT, ""))
        assertEquals(listOf("/r/pw.sh"), VaultScriptProtocol.command("/r/pw.sh", PasswordSourceKind.SCRIPT, "prod"),
            "a plain script never gets --vault-id")
        assertThrows(IllegalArgumentException::class.java) { VaultScriptProtocol.command("/r/pw", PasswordSourceKind.FILE, null) }

        val config = VaultConfig.resolve(
            mapOf("vault_identity_list" to "prod@x-client.sh, x-client.sh, @x-client.sh", "vault_password_file" to "x-client.sh",
                "vault_identity" to "team"),
            "/cfg", emptyMap(), "/root",
        )
        assertEquals(
            listOf(listOf("/root/x-client.sh", "--vault-id", "prod"), listOf("/root/x-client.sh"), listOf("/root/x-client.sh"),
                listOf("/cfg/x-client.sh", "--vault-id", "team")),
            config.slots.map { VaultScriptProtocol.command(it, PasswordSourceKind.CLIENT_SCRIPT) },
        )
        assertTrue(VaultScriptProtocol.capturesStderr(PasswordSourceKind.CLIENT_SCRIPT))
        assertFalse(VaultScriptProtocol.capturesStderr(PasswordSourceKind.SCRIPT))
    }

    @Test
    fun `exit codes`() {
        val ok = VaultScriptProtocol.result(PasswordSourceKind.CLIENT_SCRIPT, 0, "prod-pass-3\n".toByteArray()) as SecretLoad.Loaded
        ok.secret.read { assertArrayEquals("prod-pass-3".toByteArray(), it) }
        assertEquals(SecretLoad.Failed(SecretLoadFailure.UNKNOWN_VAULT_ID, 2),
            VaultScriptProtocol.result(PasswordSourceKind.CLIENT_SCRIPT, 2, "unused".toByteArray()))
        assertEquals(SecretLoad.Failed(SecretLoadFailure.SCRIPT_FAILED, 2),
            VaultScriptProtocol.result(PasswordSourceKind.SCRIPT, 2, ByteArray(0)), "exit 2 is special for client scripts only")
        assertEquals(SecretLoad.Failed(SecretLoadFailure.SCRIPT_FAILED, 1),
            VaultScriptProtocol.result(PasswordSourceKind.CLIENT_SCRIPT, 1, "x".toByteArray()))
        assertEquals(SecretLoad.Failed(SecretLoadFailure.EMPTY), VaultScriptProtocol.result(PasswordSourceKind.SCRIPT, 0, "\r\n".toByteArray()))
        val spaced = VaultScriptProtocol.result(PasswordSourceKind.SCRIPT, 0, " script pass \r\n".toByteArray()) as SecretLoad.Loaded
        spaced.secret.read { assertArrayEquals(" script pass ".toByteArray(), it) }
    }
}
