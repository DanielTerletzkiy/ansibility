package de.terletzkiy.ansibility.semantics.vault

import de.terletzkiy.ansibility.semantics.vault.VaultTestData.vector
import de.terletzkiy.ansibility.semantics.vault.VaultTestData.vectors
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * M4.5 acceptance 1: the 16 synthetic vectors from ansible-vault 2.21.4 (cross-checked with 2.18.8) decrypt,
 * re-encrypt with their own salt to byte-identical envelopes and files, and match the derived keys step by step.
 */
class VaultVectorsTest {
    @Test
    fun `the index lists the 16 vectors and every password is registered`() {
        assertEquals((1..16).map { "v%02d".format(it) }, vectors.map { it.id }.sorted())
        for (v in vectors) {
            val password = VaultTestData.passwords.getValue(v.passwordId)
            assertEquals(v.passwordHex, hex(password.bytes), v.id)
            assertEquals(v.passwordSourceHex, hex(password.sourceBytes), v.id)
        }
    }

    @Test
    fun `both ansible-core versions decrypted every vector and the deterministic salt`() {
        for (version in VaultTestData.VERSIONS) {
            val tol = VaultTestData.tolerance(version)
            assertEquals(vectors.associate { it.id to "OK" }, tol.obj("vectors"), version)
            assertEquals(true, tol["deterministicV10"], version)
        }
    }

    @TestFactory
    fun `every vector parses and decrypts`(): List<DynamicTest> = vectors.map { v ->
        DynamicTest.dynamicTest(v.id) {
            val envelope = VaultTestData.envelope(v.envelope)
            assertEquals(v.formatVersion, envelope.version)
            assertEquals(VaultEnvelope.CIPHER, envelope.cipher)
            assertEquals(v.label, envelope.label)
            assertEquals(v.labelAsParsedByAnsible, envelope.labelOrDefault())
            assertArrayEquals(v.salt, envelope.salt)
            assertArrayEquals(v.hmac, envelope.hmac)
            assertArrayEquals(v.ciphertext, envelope.ciphertext)
            assertTrue(v.plaintext.size in envelope.plaintextLength, "plaintext length within the padding range")
            VaultTestData.secret(v.passwordId).use { secret ->
                assertArrayEquals(v.plaintext, VaultAes256.decrypt(envelope, secret))
            }
        }
    }

    @TestFactory
    fun `every raw ansible-vault file parses to the same envelope`(): List<DynamicTest> = vectors.map { v ->
        DynamicTest.dynamicTest(v.id) {
            val raw = VaultTestData.bytes(v.rawFile)
            if (v.kind == "file") {
                assertTrue(VaultEnvelope.isEncryptedFile(raw.copyOf(14)))
                assertEquals(VaultTestData.envelope(v.envelope), (VaultEnvelope.parse(raw) as EnvelopeParse.Ok).envelope)
            } else {
                assertEquals(v.envelope, VaultTestData.vaultScalar(String(raw, Charsets.UTF_8), v.yamlKey!!))
            }
        }
    }

    @TestFactory
    fun `re-encrypting with the vector's own salt is byte-identical`(): List<DynamicTest> = vectors.map { v ->
        DynamicTest.dynamicTest(v.id) {
            val envelope = VaultTestData.secret(v.passwordId).use { secret ->
                VaultAes256.encrypt(v.plaintext, secret, v.label, v.salt)
            }
            assertEquals(v.envelope, envelope.format())
            val raw = VaultTestData.bytes(v.rawFile)
            if (v.kind == "file") {
                assertArrayEquals(raw, envelope.formatBytes(), "whole-file vault bytes")
            } else {
                assertEquals(String(raw, Charsets.UTF_8), VaultLayout.inlineBlock(v.yamlKey!!, 0, VaultLayout.ENCRYPT_STRING_INDENT, envelope),
                    "encrypt_string output")
            }
        }
    }

    @TestFactory
    fun `the derived keys match step by step`(): List<DynamicTest> = vectors.map { v ->
        DynamicTest.dynamicTest(v.id) {
            VaultTestData.secret(v.passwordId).use { secret ->
                VaultAes256.deriveKeys(secret, v.salt).use { keys ->
                    assertEquals(v.derived["cipherKeyHex"], hex(keys.cipherKey()))
                    assertEquals(v.derived["hmacKeyHex"], hex(keys.hmacKey()))
                    assertEquals(v.derived["ivHex"], hex(keys.iv()))
                }
            }
        }
    }

    @TestFactory
    fun `a flipped bit fails the HMAC`(): List<DynamicTest> = vectors.flatMap { v ->
        val envelope = VaultTestData.envelope(v.envelope)
        listOf(
            "ciphertext" to VaultEnvelope.of(v.label, v.salt, v.hmac, flip(v.ciphertext)),
            "hmac" to VaultEnvelope.of(v.label, v.salt, flip(v.hmac), v.ciphertext),
            "salt" to VaultEnvelope.of(v.label, flip(v.salt), v.hmac, v.ciphertext),
        ).map { (part, tampered) ->
            DynamicTest.dynamicTest("${v.id} $part") {
                assertNotEquals(envelope, tampered)
                VaultTestData.secret(v.passwordId).use { assertNull(VaultAes256.decrypt(tampered, it)) }
                VaultTestData.secret(v.passwordId).use { secret ->
                    assertEquals(DecryptOutcome.NoSecretWorked(listOf("default")),
                        VaultMatcher.decrypt(tampered, listOf(LabelledSecret("default", secret)), idMatch = false))
                }
            }
        }
    }

    @Test
    fun `v04 is an empty plaintext in one padding block and v11 sixteen bytes in two`() {
        val v04 = VaultTestData.envelope(vector("v04").envelope)
        assertEquals(16, v04.ciphertextLength)
        assertEquals(0..15, v04.plaintextLength)
        assertEquals(0, vector("v04").plaintext.size)
        val v11 = VaultTestData.envelope(vector("v11").envelope)
        assertEquals(32, v11.ciphertextLength)
        assertEquals(16, vector("v11").plaintext.size)
        assertEquals(256 + 4, vector("v07").plaintext.size)
        assertEquals(272, VaultTestData.envelope(vector("v07").envelope).ciphertextLength)
    }

    @Test
    fun `v10 is deterministic with VAULT_ENCRYPT_SALT and its 20-byte salt is the UTF-8 of the setting`() {
        val v10 = vector("v10")
        assertEquals("fixed-salt-for-tests", v10.encryptSalt)
        assertArrayEquals(v10.encryptSalt!!.toByteArray(), v10.salt)
        assertEquals(20, v10.salt.size)
        assertArrayEquals(VaultTestData.bytes("raw/v10.yml"), VaultTestData.bytes("raw/v10b.yml"))
        val config = VaultConfig.resolve(mapOf("vault_encrypt_salt" to "fixed-salt-for-tests"), "/r", emptyMap(), "/r")
        val again = VaultTestData.secret("pw1").use { VaultAes256.encrypt(v10.plaintext, it, null, config.encryptSaltBytes()!!) }
        assertEquals(v10.envelope, again.format())
    }

    @Test
    fun `v12 needs Ansible's password file strip rules`() {
        val v12 = vector("v12")
        val loaded = SecretBytes.fromFile(unhex(v12.passwordSourceHex)) { null } as SecretLoad.Loaded
        loaded.secret.use { secret ->
            assertArrayEquals(v12.plaintext, VaultAes256.decrypt(VaultTestData.envelope(v12.envelope), secret))
        }
        val unstripped = SecretBytes.of(unhex(v12.passwordSourceHex))
        assertNull(VaultAes256.decrypt(VaultTestData.envelope(v12.envelope), unstripped))
    }

    @Test
    fun `v15 needs PBKDF2 over raw bytes, which PBEKeySpec cannot reproduce`() {
        val v15 = vector("v15")
        val raw = VaultTestData.passwords.getValue("nonutf8").bytes
        assertThrows(CharacterCodingException::class.java) { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(raw)) }
        val asChars = String(raw, Charsets.UTF_8).toCharArray() // lossy: the invalid bytes become U+FFFD
        val pbe = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(asChars, v15.salt, VaultAes256.ITERATIONS, 80 * 8)).encoded
        assertNotEquals(v15.derived["cipherKeyHex"], hex(pbe.copyOf(32)))
        VaultTestData.secret("nonutf8").use { secret ->
            assertArrayEquals(v15.plaintext, VaultAes256.decrypt(VaultTestData.envelope(v15.envelope), secret))
        }
    }

    @Test
    fun `v13 and v14 decrypt only with a secret their label does not name`() {
        for ((id, labels) in listOf("v13" to listOf("dev", "prod"), "v14" to listOf("default", "prod"))) {
            val secrets = labels.map { VaultTestData.labelled(it, if (it == "default") "pw1" else it) }
            val outcome = VaultMatcher.decrypt(VaultTestData.envelope(vector(id).envelope), secrets, idMatch = false)
            outcome as DecryptOutcome.Decrypted
            assertEquals("prod", outcome.label, id)
            assertEquals(false, outcome.labelMatches, id)
            outcome.read { assertArrayEquals(vector(id).plaintext, it) }
            outcome.close()
        }
    }

    private fun flip(bytes: ByteArray): ByteArray = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x01).toByte() }
}
