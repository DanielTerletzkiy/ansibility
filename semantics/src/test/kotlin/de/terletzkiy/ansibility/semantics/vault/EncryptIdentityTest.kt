package de.terletzkiy.ansibility.semantics.vault

import de.terletzkiy.ansibility.semantics.vault.EncryptIdentity.Choice
import de.terletzkiy.ansibility.semantics.vault.EncryptIdentity.Rule
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * The encrypt-id choice and the header it writes, against `encrypt_string` and `edit` runs of ansible-core 2.18.8 and
 * 2.21.4 (`config-*.json`): two ids and none chosen are refused, `default` writes `1.1`, `vault_identity = team` writes
 * `1.2;AES256;team`, an unknown `vault_encrypt_identity` is refused, and edit keeps the header label (re-deriving the
 * version) and the secret that decrypted.
 */
class EncryptIdentityTest {
    @TestFactory
    fun `encrypt_string rows`(): List<DynamicContainer> = VaultTestData.VERSIONS.map { version ->
        val table = VaultTestData.config(version)
        DynamicContainer.dynamicContainer(version, table.rows("encrypt").map { row ->
            DynamicTest.dynamicTest(row.str("id")) {
                val tree = OracleTree(table)
                val config = tree.config(row)
                val loaded = tree.load(config, null)
                val labels = loaded.secrets.map { it.label }
                when (val choice = EncryptIdentity.ansible(config.encryptIdentity, labels)) {
                    is Choice.Chosen -> {
                        assertEquals("OK", row["result"])
                        val secret = loaded.secrets.first { it.label == choice.label }
                        val envelope = VaultAes256.encrypt("synthetic value".toByteArray(), secret.secret, choice.label)
                        assertEquals(row.str("header"), envelope.headerLine())
                        val decrypts = VaultTestData.passwords.entries.first { (_, p) ->
                            SecretBytes.of(p.bytes).use { VaultAes256.decrypt(envelope, it) } != null
                        }.key
                        assertEquals(row.str("decryptsWith"), decrypts)
                    }
                    is Choice.Ambiguous -> assertEquals("REFUSED" to "AMBIGUOUS", row["result"] to row["refusal"])
                    is Choice.NotFound -> assertEquals("REFUSED" to "NOT_FOUND", row["result"] to row["refusal"])
                    Choice.NoIdentity -> error("every row loads at least one secret")
                }
                loaded.secrets.forEach { it.secret.zero() }
            }
        })
    }

    @TestFactory
    fun `edit rows`(): List<DynamicContainer> = VaultTestData.VERSIONS.map { version ->
        val table = VaultTestData.config(version)
        DynamicContainer.dynamicContainer(version, table.rows("edit").map { row ->
            DynamicTest.dynamicTest(row.str("id")) {
                val tree = OracleTree(table)
                val config = tree.config(row)
                val loaded = tree.load(config, null)
                val original = VaultTestData.envelope(String(unhex(row.str("originalHex")), Charsets.US_ASCII))
                val outcome = VaultMatcher.decrypt(original, loaded.secrets, config.idMatch, config.defaultIdentity)
                    as DecryptOutcome.Decrypted
                val decrypting = loaded.secrets[outcome.index]
                assertEquals(row.str("originalPassword"), VaultTestData.passwords.entries.first { (_, p) ->
                    decrypting.secret.read { it.contentEquals(p.bytes) }
                }.key, "edit re-encrypts with the secret that decrypted")
                val edited = outcome.use { d ->
                    d.read { plain ->
                        val changed = if (row["unchanged"] == true) plain else plain + "edited\n".toByteArray()
                        VaultAes256.encrypt(changed, decrypting.secret, EncryptIdentity.editLabel(original, config.defaultIdentity))
                    }
                }
                assertEquals(row.str("header"), edited.headerLine())
                assertEquals(row.str("decryptsWith"), row.str("originalPassword"))
                VaultTestData.secret(row.str("decryptsWith")).use { secret ->
                    assertArrayEquals(unhex(row.str("plaintextHex")), VaultAes256.decrypt(edited, secret))
                }
                loaded.secrets.forEach { it.secret.zero() }
            }
        })
    }

    @Test
    fun `ansible-vault's own rule`() {
        assertEquals(Choice.NoIdentity, EncryptIdentity.ansible(null, emptyList()))
        assertEquals(Choice.Chosen("dev", Rule.ONLY_IDENTITY), EncryptIdentity.ansible(null, listOf("dev")))
        assertEquals(Choice.Chosen("dev", Rule.ONLY_IDENTITY), EncryptIdentity.ansible("", listOf("dev")))
        assertEquals(Choice.Ambiguous(listOf("default", "default"), null), EncryptIdentity.ansible(null, listOf("default", "default")))
        assertEquals(Choice.Chosen("prod", Rule.ENCRYPT_IDENTITY), EncryptIdentity.ansible("prod", listOf("dev", "prod")))
        assertEquals(Choice.NotFound("qa", listOf("dev")), EncryptIdentity.ansible("qa", listOf("dev")))
    }

    @Test
    fun `the IDE's rule adds the environment mapping, distinct ids and a pre-selection`() {
        assertEquals(Choice.Chosen("prod", Rule.ENCRYPT_IDENTITY), EncryptIdentity.choose("prod", "dev", listOf("dev", "prod")))
        assertEquals(Choice.Chosen("prod", Rule.ENVIRONMENT_MAPPING), EncryptIdentity.choose(null, "prod", listOf("dev", "prod")))
        assertEquals(Choice.NotFound("qa", listOf("dev", "prod")), EncryptIdentity.choose(null, "qa", listOf("dev", "prod")))
        assertEquals(Choice.Chosen("default", Rule.ONLY_IDENTITY), EncryptIdentity.choose(null, null, listOf("default", "default")))
        assertEquals(Choice.Ambiguous(listOf("dev", "prod"), "prod"),
            EncryptIdentity.choose(null, null, listOf("dev", "prod"), neighbours = listOf("prod", "dev", "prod", "ops", "ops", "ops")))
        assertEquals(Choice.Ambiguous(listOf("dev", "prod"), "dev"),
            EncryptIdentity.choose(null, null, listOf("dev", "prod"), neighbours = listOf("dev", "prod")), "a tie keeps the first")
        assertEquals(Choice.Ambiguous(listOf("dev", "prod"), null), EncryptIdentity.choose(null, null, listOf("dev", "prod")))
        assertEquals(Choice.NoIdentity, EncryptIdentity.choose(null, "prod", emptyList()))
    }

    @Test
    fun `the label decides the header version`() {
        assertEquals("1.1", VaultEnvelope.versionFor(null))
        assertEquals("1.1", VaultEnvelope.versionFor(""))
        assertEquals("1.1", VaultEnvelope.versionFor("default"))
        assertEquals("1.2", VaultEnvelope.versionFor("team"))
        assertEquals("1.2", VaultEnvelope.versionFor("Default"))
        val v01 = VaultTestData.envelope(VaultTestData.vector("v01").envelope)
        assertEquals("team", EncryptIdentity.editLabel(v01, "team"))
        assertEquals("default", EncryptIdentity.editLabel(v01))
        assertEquals("dev", EncryptIdentity.editLabel(VaultTestData.envelope(VaultTestData.vector("v13").envelope), "team"))
    }
}
