package de.terletzkiy.ansibility.semantics.vault

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Ansible's strip rules per secret source, and the secret holder's hygiene. */
class SecretBytesTest {
    private fun loaded(load: SecretLoad): ByteArray = (load as SecretLoad.Loaded).secret.read { it.copyOf() }

    @Test
    fun `a password file loses all surrounding ASCII whitespace`() {
        assertArrayEquals("spaced pass 4".toByteArray(), loaded(SecretBytes.fromFile("  spaced pass 4 \r\n".toByteArray()) { null }))
        assertArrayEquals("ws pass".toByteArray(), loaded(SecretBytes.fromFile("\t\u000Bws pass\u000C\n\n".toByteArray()) { null }))
        assertArrayEquals("in side".toByteArray(), loaded(SecretBytes.fromFile("in side".toByteArray()) { null }))
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 'p'.code.toByte(), 0xE9.toByte()),
            loaded(SecretBytes.fromFile(byteArrayOf(0x20, 0xFF.toByte(), 'p'.code.toByte(), 0xE9.toByte(), 0x0A)) { null }))
        // Non-ASCII whitespace (NBSP in UTF-8) is not stripped: Python strips ASCII whitespace only from bytes.
        assertArrayEquals("\u00A0pw".toByteArray(), loaded(SecretBytes.fromFile("\u00A0pw\n".toByteArray()) { null }))
    }

    @Test
    fun `the synthetic password sources strip to their registered secrets`() {
        for ((id, password) in VaultTestData.passwords) {
            val load = when (password.source) {
                "file" -> SecretBytes.fromFile(password.sourceBytes) { null }
                "script" -> SecretBytes.fromScript(password.sourceBytes)
                "prompt" -> SecretBytes.fromPrompt(String(password.sourceBytes, Charsets.UTF_8).toCharArray())
                "vaulted file" -> {
                    // Stored vaulted with pw1; decrypted, only CR/LF are stripped.
                    val vaulted = VaultTestData.secret("pw1").use { VaultAes256.encrypt(password.sourceBytes, it, null) }
                    SecretBytes.fromFile(vaulted.formatBytes(), VaultMatcher.passwordFileDecryptor(
                        listOf(VaultTestData.labelled("default", "pw1")), idMatch = false))
                }
                else -> error("unknown source ${password.source}")
            }
            assertEquals(hex(password.bytes), hex(loaded(load)), id)
        }
    }

    @Test
    fun `a vaulted password file keeps inner spaces and needs a decryptor`() {
        val vaulted = VaultTestData.secret("pw1").use { VaultAes256.encrypt("  inner pass \r\n".toByteArray(), it, null) }
        var seen: VaultEnvelope? = null
        val plaintextSeen = mutableListOf<ByteArray>()
        val load = SecretBytes.fromFile("\n".toByteArray() + vaulted.formatBytes() + "  \n".toByteArray()) { envelope ->
            seen = envelope
            VaultTestData.secret("pw1").use { VaultAes256.decrypt(envelope, it) }!!.also { plaintextSeen += it }
        }
        assertArrayEquals("  inner pass ".toByteArray(), loaded(load))
        assertEquals(vaulted, seen)
        assertTrue(plaintextSeen.single().all { it == 0.toByte() }, "the decrypted file content is zeroed")
        assertEquals(SecretLoad.Failed(SecretLoadFailure.VAULTED_NOT_DECRYPTED), SecretBytes.fromFile(vaulted.formatBytes()) { null })
        assertEquals(SecretLoad.Failed(SecretLoadFailure.VAULTED_MALFORMED),
            SecretBytes.fromFile("\$ANSIBLE_VAULT;1.1;AES256\nzz\n".toByteArray()) { error("never called") })
        assertEquals(SecretLoad.Failed(SecretLoadFailure.EMPTY),
            SecretBytes.fromFile(vaulted.formatBytes()) { "\r\n".toByteArray() })
    }

    @Test
    fun `a script keeps everything but surrounding CR and LF`() {
        assertArrayEquals(" script pass ".toByteArray(), loaded(SecretBytes.fromScript(" script pass \r\n".toByteArray())))
        assertArrayEquals("\tx\t".toByteArray(), loaded(SecretBytes.fromScript("\r\n\tx\t\n\n".toByteArray())))
        assertEquals(SecretLoad.Failed(SecretLoadFailure.EMPTY), SecretBytes.fromScript("\r\n\n".toByteArray()))
    }

    @Test
    fun `a prompt is UTF-8 stripped of ASCII whitespace`() {
        assertArrayEquals("pässwörd-✓".toByteArray(), loaded(SecretBytes.fromPrompt("  pässwörd-✓ \t".toCharArray())))
        assertArrayEquals("a b".toByteArray(), loaded(SecretBytes.fromPrompt("a b\n".toCharArray())))
        assertEquals(SecretLoad.Failed(SecretLoadFailure.EMPTY), SecretBytes.fromPrompt("  \t".toCharArray()))
        assertEquals(SecretLoad.Failed(SecretLoadFailure.EMPTY), SecretBytes.fromPrompt(CharArray(0)))
        assertEquals(SecretLoad.Failed(SecretLoadFailure.ENCODING), SecretBytes.fromPrompt(charArrayOf('a', '\uD800')))
        val input = " x ".toCharArray()
        SecretBytes.fromPrompt(input)
        assertArrayEquals(" x ".toCharArray(), input, "the caller's buffer is not touched")
    }

    @Test
    fun `the holder never shows its bytes and can be zeroed`() {
        val raw = "test-pass-1".toByteArray()
        val secret = SecretBytes.of(raw)
        raw.fill(0)
        assertEquals("***", secret.toString())
        assertEquals("default=***", LabelledSecret("default", secret).toString())
        assertEquals("Loaded(***)", SecretLoad.Loaded(secret).toString())
        assertEquals(11, secret.size)
        assertArrayEquals("test-pass-1".toByteArray(), secret.read { it.copyOf() }, "of() copies its input")
        val copy = secret.copy()
        assertNotEquals(secret, copy, "equality is identity, never content")
        assertEquals(secret, secret)
        var leaked: ByteArray? = null
        secret.read { leaked = it }
        secret.zero()
        assertTrue(secret.isZeroed)
        assertEquals(0, secret.size)
        assertTrue(leaked!!.all { it == 0.toByte() }, "zero() overwrites the bytes")
        assertThrows(IllegalStateException::class.java) { secret.read { } }
        assertThrows(IllegalStateException::class.java) { secret.copy() }
        secret.close()
        assertFalse(copy.isZeroed, "a copy has its own lifetime")
        copy.read { assertArrayEquals("test-pass-1".toByteArray(), it) }
        assertThrows(IllegalArgumentException::class.java) { SecretBytes.of(ByteArray(0)) }
    }

    @Test
    fun `failures carry no data`() {
        val failed = SecretLoad.Failed(SecretLoadFailure.SCRIPT_FAILED, 3)
        assertEquals("Failed(reason=SCRIPT_FAILED, exitCode=3)", failed.toString())
        assertSame(SecretLoadFailure.EMPTY, (SecretBytes.fromScript(ByteArray(0)) as SecretLoad.Failed).reason)
    }
}
