package de.terletzkiy.ansibility.semantics.vault

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ansible-core's `VaultAES256`, byte for byte, on JDK crypto only:
 * - the key material is PBKDF2-HMAC-SHA256 with 10,000 iterations and 80 output bytes over the **raw** password bytes,
 *   split into a 32-byte AES key, a 32-byte HMAC key and a 16-byte initial CTR counter block. PBKDF2 is written out
 *   here because `PBEKeySpec` takes a `char[]` and UTF-8-encodes it, which cannot reproduce a non-UTF-8 password file;
 * - the plaintext is PKCS7-padded to 16 bytes (always: an empty plaintext gives one full block) and encrypted with
 *   AES-256-CTR (the whole 128-bit counter increments big-endian, as in `cryptography`);
 * - the HMAC-SHA256 covers the raw ciphertext only and is compared in constant time.
 *
 * Every intermediate buffer (derived keys, padded plaintext) is zeroed after use. The copies the JDK's `Mac` and
 * `Cipher` keep internally cannot be cleared from outside (`SecretKeySpec` has no public `destroy`).
 */
object VaultAes256 {
    const val ITERATIONS: Int = 10_000
    const val KEY_LENGTH: Int = 32
    const val IV_LENGTH: Int = 16

    /** The length of a salt ansible-vault picks itself. */
    const val SALT_LENGTH: Int = 32

    private const val DERIVED_LENGTH = 2 * KEY_LENGTH + IV_LENGTH
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val CIPHER_TRANSFORMATION = "AES/CTR/NoPadding"

    private val random = SecureRandom()

    /** 32 random bytes from `SecureRandom`, as `os.urandom(32)`. */
    fun randomSalt(): ByteArray = ByteArray(SALT_LENGTH).also(random::nextBytes)

    /** The keys ansible-vault derives from [secret] and [salt]; the caller closes them. */
    fun deriveKeys(secret: SecretBytes, salt: ByteArray): DerivedKeys =
        DerivedKeys(secret.read { pbkdf2HmacSha256(it, salt, ITERATIONS, DERIVED_LENGTH) })

    /**
     * Decrypts [envelope] with [secret]. Returns the plaintext (the caller owns and zeroes it), or null when the HMAC
     * does not match: the wrong secret, and Ansible then tries the next one.
     *
     * @throws VaultFormatException with [FormatReason.PADDING] when the HMAC matches but the padding is invalid,
     *   which only a crafted envelope can produce; Ansible fails outright, so no further secret should be tried
     */
    fun decrypt(envelope: VaultEnvelope, secret: SecretBytes): ByteArray? =
        deriveKeys(secret, envelope.saltView()).use { keys -> decrypt(envelope, keys) }

    /** [decrypt] with keys derived earlier for this envelope's salt (re-encrypting on save may reuse them). */
    fun decrypt(envelope: VaultEnvelope, keys: DerivedKeys): ByteArray? {
        val ciphertext = envelope.ciphertextView()
        val tag = keys.withBytes { dk ->
            Mac.getInstance(HMAC_ALGORITHM).run {
                init(SecretKeySpec(dk, KEY_LENGTH, KEY_LENGTH, HMAC_ALGORITHM))
                doFinal(ciphertext)
            }
        }
        if (!MessageDigest.isEqual(tag, envelope.hmacView())) return null
        val padded = keys.withBytes { dk -> ctr(dk, Cipher.DECRYPT_MODE).doFinal(ciphertext) }
        try {
            val pad = if (padded.isEmpty()) 0 else padded.last().toInt() and 0xFF
            val valid = padded.size % VaultEnvelope.BLOCK_SIZE == 0 && pad in 1..VaultEnvelope.BLOCK_SIZE &&
                (padded.size - pad until padded.size).all { (padded[it].toInt() and 0xFF) == pad }
            if (!valid) throw VaultFormatException(FormatReason.PADDING)
            return padded.copyOf(padded.size - pad)
        } finally {
            padded.fill(0)
        }
    }

    /**
     * Encrypts [plaintext] the way ansible-vault does, under [label] (`default`, empty or null write a `1.1` header,
     * any other label `1.2;AES256;<label>`). [salt] defaults to 32 random bytes; pass the UTF-8 bytes of
     * `VAULT_ENCRYPT_SALT` ([VaultConfig.encryptSaltBytes]) to reproduce ansible-vault's deterministic output.
     *
     * @throws IllegalArgumentException when [plaintext] is already vault data ("input is already encrypted") or
     *   [salt] is empty, as ansible-vault refuses both
     */
    fun encrypt(plaintext: ByteArray, secret: SecretBytes, label: String?, salt: ByteArray = randomSalt()): VaultEnvelope {
        require(!VaultEnvelope.isEncrypted(plaintext)) { "input is already encrypted" }
        require(salt.isNotEmpty()) { "empty salt" }
        return deriveKeys(secret, salt).use { keys -> encrypt(plaintext, keys, label, salt) }
    }

    /** [encrypt] with keys derived earlier for [salt]. */
    fun encrypt(plaintext: ByteArray, keys: DerivedKeys, label: String?, salt: ByteArray): VaultEnvelope {
        require(!VaultEnvelope.isEncrypted(plaintext)) { "input is already encrypted" }
        require(salt.isNotEmpty()) { "empty salt" }
        val pad = VaultEnvelope.BLOCK_SIZE - plaintext.size % VaultEnvelope.BLOCK_SIZE
        val padded = plaintext.copyOf(plaintext.size + pad)
        padded.fill(pad.toByte(), plaintext.size, padded.size)
        try {
            val ciphertext = keys.withBytes { dk -> ctr(dk, Cipher.ENCRYPT_MODE).doFinal(padded) }
            val tag = keys.withBytes { dk ->
                Mac.getInstance(HMAC_ALGORITHM).run {
                    init(SecretKeySpec(dk, KEY_LENGTH, KEY_LENGTH, HMAC_ALGORITHM))
                    doFinal(ciphertext)
                }
            }
            return VaultEnvelope.of(label, salt.copyOf(), tag, ciphertext)
        } finally {
            padded.fill(0)
        }
    }

    private fun ctr(dk: ByteArray, mode: Int): Cipher = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
        init(mode, SecretKeySpec(dk, 0, KEY_LENGTH, "AES"), IvParameterSpec(dk, 2 * KEY_LENGTH, IV_LENGTH))
    }

    /**
     * PBKDF2 (RFC 8018) with HMAC-SHA256 over the raw [password] bytes. [password] must not be empty: the JDK's
     * HMAC refuses an empty key, and every Ansible secret source refuses empty secrets.
     */
    internal fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        require(password.isNotEmpty()) { "a vault secret must not be empty" }
        require(iterations >= 1 && length >= 1)
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(password, HMAC_ALGORITHM))
        val hLen = mac.macLength
        val out = ByteArray(length)
        val u = ByteArray(hLen)
        val t = ByteArray(hLen)
        val index = ByteArray(4)
        var block = 1
        var offset = 0
        try {
            while (offset < length) {
                index[0] = (block ushr 24).toByte()
                index[1] = (block ushr 16).toByte()
                index[2] = (block ushr 8).toByte()
                index[3] = block.toByte()
                mac.update(salt)
                mac.update(index)
                mac.doFinal(u, 0)
                u.copyInto(t)
                for (iteration in 1 until iterations) {
                    mac.update(u)
                    mac.doFinal(u, 0)
                    for (k in 0 until hLen) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
                }
                val n = minOf(hLen, length - offset)
                t.copyInto(out, offset, 0, n)
                offset += n
                block++
            }
            return out
        } finally {
            u.fill(0)
            t.fill(0)
        }
    }
}

/**
 * The 80 bytes PBKDF2 derives for one (secret, salt): the AES key, the HMAC key and the initial CTR block. Secret:
 * never a `data class`, [toString] is `***`, and [close] zeroes the bytes.
 */
class DerivedKeys internal constructor(bytes: ByteArray) : AutoCloseable {
    private var bytes: ByteArray? = bytes

    /** A copy of the 32-byte AES-256 key (for step-by-step tests; zero it). */
    fun cipherKey(): ByteArray = withBytes { it.copyOfRange(0, VaultAes256.KEY_LENGTH) }

    /** A copy of the 32-byte HMAC-SHA256 key. */
    fun hmacKey(): ByteArray = withBytes { it.copyOfRange(VaultAes256.KEY_LENGTH, 2 * VaultAes256.KEY_LENGTH) }

    /** A copy of the 16-byte initial counter block. */
    fun iv(): ByteArray = withBytes { it.copyOfRange(2 * VaultAes256.KEY_LENGTH, 2 * VaultAes256.KEY_LENGTH + VaultAes256.IV_LENGTH) }

    @Synchronized
    internal fun <T> withBytes(block: (ByteArray) -> T): T = block(checkNotNull(bytes) { "the keys were zeroed" })

    @Synchronized
    override fun close() {
        bytes?.fill(0)
        bytes = null
    }

    override fun toString(): String = "***"
}

/** A malformed envelope found while decrypting; the message carries the reason only, never data. */
class VaultFormatException(val reason: FormatReason) : RuntimeException("Vault format error: $reason")
