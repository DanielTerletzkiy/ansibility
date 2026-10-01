package de.terletzkiy.ansibility.semantics.vault

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * A vault secret: the raw password bytes Ansible derives the key from. Secrets are bytes, not text: a password file
 * may hold bytes that are not UTF-8 (vector v15).
 *
 * Never a `data class`: [toString] is `***`, [equals] is identity, and the bytes are reachable only inside [read].
 * [zero] (or [close], so Kotlin's `use {}` works) overwrites them; afterwards [read] throws. The factories implement Ansible's strip rules per
 * source and never mutate their input, so callers zero their own buffers.
 *
 * Thread-safe: [read] and [zero] are serialised, so a lock never zeroes bytes that a key derivation is reading.
 */
class SecretBytes private constructor(bytes: ByteArray) : AutoCloseable {
    private var bytes: ByteArray? = bytes

    /** The secret length in bytes; 0 after [zero]. */
    val size: Int @Synchronized get() = bytes?.size ?: 0

    /** True after [zero]. */
    val isZeroed: Boolean @Synchronized get() = bytes == null

    /**
     * Runs [block] on the secret bytes. The array must not be kept or modified.
     *
     * @throws IllegalStateException after [zero]
     */
    @Synchronized
    fun <T> read(block: (ByteArray) -> T): T = block(checkNotNull(bytes) { "the secret was zeroed" })

    /** An independent copy (for a second owner with its own lifetime). */
    @Synchronized
    fun copy(): SecretBytes = SecretBytes(checkNotNull(bytes) { "the secret was zeroed" }.copyOf())

    /** Overwrites the bytes with zeros and drops them; idempotent. */
    @Synchronized
    fun zero() {
        bytes?.fill(0)
        bytes = null
    }

    override fun close() = zero()

    override fun toString(): String = "***"

    companion object {
        /**
         * Wraps a copy of [raw] as it is, without any strip rule: a PasswordSafe entry or a value the caller has
         * already normalised.
         *
         * @throws IllegalArgumentException when [raw] is empty (Ansible refuses empty secrets)
         */
        fun of(raw: ByteArray): SecretBytes {
            require(raw.isNotEmpty()) { "a vault secret must not be empty" }
            return SecretBytes(raw.copyOf())
        }

        /**
         * A password file (`FileVaultSecret`): the content is stripped of all ASCII whitespace at both ends; if what
         * remains is itself vault data it is decrypted with [decryptVaulted] (Ansible uses the secrets loaded before
         * this one, under the same matching rules: [VaultMatcher.passwordFileDecryptor]); the result is then stripped
         * of CR and LF only, so spaces inside a vaulted password survive.
         *
         * [decryptVaulted] returns the plaintext (zeroed here after use) or null when no earlier secret decrypts it.
         */
        fun fromFile(raw: ByteArray, decryptVaulted: (VaultEnvelope) -> ByteArray?): SecretLoad {
            val stripped = raw.stripBytes { it in PY_WHITESPACE_BYTES }
            try {
                if (!VaultEnvelope.isEncrypted(stripped)) return loaded(stripped.stripBytes(::isCrOrLf))
                val envelope = when (val parsed = VaultEnvelope.parse(stripped)) {
                    is EnvelopeParse.Ok -> parsed.envelope
                    else -> return SecretLoad.Failed(SecretLoadFailure.VAULTED_MALFORMED)
                }
                val plaintext = decryptVaulted(envelope) ?: return SecretLoad.Failed(SecretLoadFailure.VAULTED_NOT_DECRYPTED)
                try {
                    return loaded(plaintext.stripBytes(::isCrOrLf))
                } finally {
                    plaintext.fill(0)
                }
            } finally {
                stripped.fill(0)
            }
        }

        /** A plain or `-client` password script's stdout (`ScriptVaultSecret`): only CR and LF are stripped. */
        fun fromScript(stdout: ByteArray): SecretLoad = loaded(stdout.stripBytes(::isCrOrLf))

        /**
         * Prompt input (`PromptVaultSecret`): encoded as UTF-8, then stripped of all ASCII whitespace.
         *
         * Stricter than Ansible in one case: Ansible checks for emptiness before stripping, so whitespace-only input
         * becomes an empty secret that never decrypts anything; here it is refused as [SecretLoadFailure.EMPTY].
         */
        fun fromPrompt(chars: CharArray): SecretLoad {
            val encoder = Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val buffer: ByteBuffer = try {
                encoder.encode(CharBuffer.wrap(chars))
            } catch (_: CharacterCodingException) {
                return SecretLoad.Failed(SecretLoadFailure.ENCODING)
            }
            val encoded = ByteArray(buffer.remaining()).also { buffer.get(it) }
            if (buffer.hasArray()) buffer.array().fill(0)
            try {
                return loaded(encoded.stripBytes { it in PY_WHITESPACE_BYTES })
            } finally {
                encoded.fill(0)
            }
        }

        private val PY_WHITESPACE_BYTES: Set<Byte> = PY_WHITESPACE.map { it.code.toByte() }.toSet()

        private fun isCrOrLf(b: Byte): Boolean = b == 0x0D.toByte() || b == 0x0A.toByte()

        /** Takes ownership of [bytes]: wraps them, or zeroes them and reports an empty secret. */
        private fun loaded(bytes: ByteArray): SecretLoad =
            if (bytes.isEmpty()) SecretLoad.Failed(SecretLoadFailure.EMPTY) else SecretLoad.Loaded(SecretBytes(bytes))

        /** A new array without the leading and trailing bytes matching [strip] (Python's `bytes.strip(chars)`). */
        private inline fun ByteArray.stripBytes(strip: (Byte) -> Boolean): ByteArray {
            var start = 0
            var end = size
            while (start < end && strip(this[start])) start++
            while (end > start && strip(this[end - 1])) end--
            return copyOfRange(start, end)
        }
    }
}

/** A vault secret together with its id label, in the order Ansible tries the secrets. */
class LabelledSecret(
    /** The id label (`default`, `dev`, …). */
    val label: String,
    val secret: SecretBytes,
) {
    override fun toString(): String = "$label=***"
}

/** The result of reading a secret from a source. */
sealed interface SecretLoad {
    /** The caller owns [secret] and zeroes it when the id is locked. */
    class Loaded(val secret: SecretBytes) : SecretLoad {
        override fun toString(): String = "Loaded(***)"
    }

    /** No secret; [exitCode] is set for a script that exited with a non-zero code other than the unknown-id code. */
    data class Failed(val reason: SecretLoadFailure, val exitCode: Int? = null) : SecretLoad
}

/** Why a source gave no secret. Carries no data, so it can be logged and shown as it is. */
enum class SecretLoadFailure {
    /** Nothing left after the strip rules ("Invalid vault password was provided"). */
    EMPTY,

    /** A vaulted password file that no secret loaded before it decrypts. */
    VAULTED_NOT_DECRYPTED,

    /** A password file that starts like vault data but is malformed. */
    VAULTED_MALFORMED,

    /** Prompt input that cannot be encoded as UTF-8 (an unpaired surrogate). */
    ENCODING,

    /** A `-client` script exited with code 2: it has no secret for the requested vault id. */
    UNKNOWN_VAULT_ID,

    /** A script exited with another non-zero code. */
    SCRIPT_FAILED,
}
