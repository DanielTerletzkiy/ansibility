package de.terletzkiy.ansibility.vault.secrets

import de.terletzkiy.ansibility.semantics.vault.SecretBytes
import de.terletzkiy.ansibility.semantics.vault.VaultAes256
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The content fingerprint of a consent ([ConsentRecord]): `pbkdf2-hmac-sha256:<salt hex>:<sha256 hex>`, the SHA-256 of
 * the 80 bytes PBKDF2-HMAC-SHA256 derives from the content with a random 16-byte salt and 10,000 iterations (the
 * vault KDF, [VaultAes256.deriveKeys]).
 *
 * A bare SHA-256 of a password file would be an offline oracle for the password in the IDE configuration directory;
 * the salted KDF costs a guesser exactly what every vault value of the repository already costs, so the stored
 * consent reveals nothing a ciphertext does not. An empty file has the fingerprint `pbkdf2-hmac-sha256:empty`.
 */
internal object ConsentFingerprint {
    private const val SCHEME = "pbkdf2-hmac-sha256"
    private const val EMPTY = "$SCHEME:empty"
    private const val SALT_LENGTH = 16
    private val random = SecureRandom()

    /** A new fingerprint of [content] with a fresh salt. */
    fun of(content: ByteArray): String {
        if (content.isEmpty()) return EMPTY
        val salt = ByteArray(SALT_LENGTH).also(random::nextBytes)
        return "$SCHEME:${hex(salt)}:${hex(digest(content, salt))}"
    }

    /** Whether [content] has [fingerprint]; false for a malformed or foreign fingerprint. */
    fun matches(content: ByteArray, fingerprint: String): Boolean {
        if (fingerprint == EMPTY) return content.isEmpty()
        if (content.isEmpty()) return false
        val parts = fingerprint.split(':')
        if (parts.size != 3 || parts[0] != SCHEME) return false
        val salt = unhex(parts[1]) ?: return false
        val expected = unhex(parts[2]) ?: return false
        return MessageDigest.isEqual(digest(content, salt), expected)
    }

    private fun digest(content: ByteArray, salt: ByteArray): ByteArray =
        SecretBytes.of(content).use { secret ->
            VaultAes256.deriveKeys(secret, salt).use { keys ->
                val sha = MessageDigest.getInstance("SHA-256")
                for (part in listOf(keys.cipherKey(), keys.hmacKey(), keys.iv())) {
                    sha.update(part)
                    part.fill(0)
                }
                sha.digest()
            }
        }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(text: String): ByteArray? {
        if (text.length % 2 != 0 || text.isEmpty()) return null
        return ByteArray(text.length / 2) { i ->
            val high = Character.digit(text[2 * i], 16)
            val low = Character.digit(text[2 * i + 1], 16)
            if (high < 0 || low < 0) return null
            ((high shl 4) or low).toByte()
        }
    }
}
