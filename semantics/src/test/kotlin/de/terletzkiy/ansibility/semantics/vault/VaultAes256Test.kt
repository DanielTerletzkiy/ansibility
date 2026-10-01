package de.terletzkiy.ansibility.semantics.vault

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** The cipher: hand-written PBKDF2, padding, CTR, HMAC and the encrypt rules. */
class VaultAes256Test {
    @Test
    fun `PBKDF2-HMAC-SHA256 matches the RFC 7914 test vectors`() {
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
                "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            hex(VaultAes256.pbkdf2HmacSha256("passwd".toByteArray(), "salt".toByteArray(), 1, 64)),
        )
        assertEquals(
            "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56" +
                "a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d",
            hex(VaultAes256.pbkdf2HmacSha256("Password".toByteArray(), "NaCl".toByteArray(), 80000, 64)),
        )
    }

    @Test
    fun `PBKDF2 equals the JDK's for UTF-8 passwords, also for long passwords and odd lengths`() {
        for (password in listOf("test-pass-1", "pässwörd-✓", "x".repeat(200))) {
            for (length in listOf(1, 31, 32, 33, 80)) {
                val jdk = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(PBEKeySpec(password.toCharArray(), "salt".toByteArray(), 1000, length * 8)).encoded
                assertEquals(hex(jdk), hex(VaultAes256.pbkdf2HmacSha256(password.toByteArray(), "salt".toByteArray(), 1000, length)),
                    "$password/$length")
            }
        }
        assertThrows(IllegalArgumentException::class.java) { VaultAes256.pbkdf2HmacSha256(ByteArray(0), ByteArray(1), 1, 1) }
    }

    @Test
    fun `round trips for every padding length`() {
        SecretBytes.of("round-trip".toByteArray()).use { secret ->
            for (size in 0..49) {
                val plain = ByteArray(size) { (it * 7).toByte() }
                val envelope = VaultAes256.encrypt(plain, secret, null)
                assertEquals(16 * (size / 16 + 1), envelope.ciphertextLength, "PKCS7 always pads ($size)")
                assertEquals(VaultAes256.SALT_LENGTH, envelope.salt.size)
                assertArrayEquals(plain, VaultAes256.decrypt(envelope, secret))
                assertArrayEquals(plain, VaultAes256.decrypt(VaultTestData.envelope(envelope.format()), secret), "through text")
            }
        }
    }

    @Test
    fun `random salts differ, an explicit salt is deterministic`() {
        SecretBytes.of("pw".toByteArray()).use { secret ->
            val plain = "same".toByteArray()
            assertNotEquals(VaultAes256.encrypt(plain, secret, null).format(), VaultAes256.encrypt(plain, secret, null).format())
            val salt = "any length".toByteArray()
            assertEquals(VaultAes256.encrypt(plain, secret, "dev", salt), VaultAes256.encrypt(plain, secret, "dev", salt))
        }
    }

    @Test
    fun `the label decides the header`() {
        SecretBytes.of("pw".toByteArray()).use { secret ->
            assertEquals("\$ANSIBLE_VAULT;1.1;AES256", VaultAes256.encrypt(ByteArray(1), secret, null).headerLine())
            assertEquals("\$ANSIBLE_VAULT;1.1;AES256", VaultAes256.encrypt(ByteArray(1), secret, "").headerLine())
            assertEquals("\$ANSIBLE_VAULT;1.1;AES256", VaultAes256.encrypt(ByteArray(1), secret, "default").headerLine())
            assertEquals("\$ANSIBLE_VAULT;1.2;AES256;team", VaultAes256.encrypt(ByteArray(1), secret, "team").headerLine())
            assertThrows(IllegalArgumentException::class.java) { VaultAes256.encrypt(ByteArray(1), secret, "a;b") }
        }
    }

    @Test
    fun `ansible-vault's refusals`() {
        SecretBytes.of("pw".toByteArray()).use { secret ->
            val v01 = VaultTestData.vector("v01").envelope.toByteArray()
            assertThrows(IllegalArgumentException::class.java) { VaultAes256.encrypt(v01, secret, null) }
            assertThrows(IllegalArgumentException::class.java) { VaultAes256.encrypt(ByteArray(1), secret, null, ByteArray(0)) }
            // Not ASCII, so not "already encrypted": a vault envelope followed by a non-ASCII byte is plaintext.
            VaultAes256.encrypt(v01 + 0xFF.toByte(), secret, null)
        }
    }

    @Test
    fun `a wrong secret gives null and keys can be reused`() {
        val v01 = VaultTestData.vector("v01")
        val envelope = VaultTestData.envelope(v01.envelope)
        SecretBytes.of("wrong".toByteArray()).use { assertNull(VaultAes256.decrypt(envelope, it)) }
        val keys = VaultTestData.secret("pw1").use { VaultAes256.deriveKeys(it, v01.salt) }
        assertArrayEquals(v01.plaintext, VaultAes256.decrypt(envelope, keys))
        val again = VaultAes256.encrypt(v01.plaintext, keys, null, v01.salt)
        assertEquals(v01.envelope, again.format())
        assertEquals("***", keys.toString())
        keys.close()
        assertThrows(IllegalStateException::class.java) { keys.cipherKey() }
        assertThrows(IllegalStateException::class.java) { VaultAes256.decrypt(envelope, keys) }
    }

    @Test
    fun `the format exception carries no data`() {
        assertEquals("Vault format error: PADDING", VaultFormatException(FormatReason.PADDING).message)
    }
}
