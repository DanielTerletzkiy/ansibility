package de.terletzkiy.ansibility.index.vault

import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VaultEnvelopeInfo
import de.terletzkiy.ansibility.api.VaultEnvelopeKind
import de.terletzkiy.ansibility.api.VaultHeaderInfo
import de.terletzkiy.ansibility.semantics.vault.EnvelopeParse
import de.terletzkiy.ansibility.semantics.vault.FormatHint
import de.terletzkiy.ansibility.semantics.vault.FormatReason
import de.terletzkiy.ansibility.semantics.vault.NotVaultReason
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle

/**
 * Why an envelope does not parse, in the codec's vocabulary (`semantics.vault.EnvelopeParse`): the "well formed" flag
 * and reason of an `ansible.vault` entry. Each value has a stable [code] because it is stored in the index.
 */
enum class VaultEnvelopeProblem(val code: Int) {
    /** A `!vault` tag on an empty value: "Input is not vault encrypted data". */
    EMPTY(1),

    /** The value does not start with `$ANSIBLE_VAULT`. */
    NO_MAGIC(2),

    /** Whitespace or a blank line before `$ANSIBLE_VAULT`. */
    LEADING_WHITESPACE(3),

    /** A byte order mark before `$ANSIBLE_VAULT`. */
    BYTE_ORDER_MARK(4),

    /** A non-ASCII character or byte. */
    NON_ASCII(5),

    /** Fewer than three header fields. */
    HEADER_FIELDS(6),

    /** A cipher other than `AES256` (case-sensitive), also what a folded block looks like. */
    UNKNOWN_CIPHER(7),

    /** An odd number of hex digits (trailing whitespace or a TAB on a payload line). */
    ODD_LENGTH(8),

    /** A character that is not a hex digit. */
    NON_HEX_DIGIT(9),

    /** The inner payload lacks the salt and HMAC separators. */
    PAYLOAD_FIELDS(10),

    /** Invalid padding behind a valid HMAC (only a crafted envelope; never seen without a secret). */
    PADDING(11),
    ;

    companion object {
        private val BY_CODE = entries.associateBy { it.code }

        /** The problem stored as [code], or null (well formed or unknown). */
        fun ofCode(code: Int): VaultEnvelopeProblem? = BY_CODE[code]

        /** The problem of [parse], or null when the envelope is well formed. */
        fun of(parse: EnvelopeParse): VaultEnvelopeProblem? = when (parse) {
            is EnvelopeParse.Ok -> null
            is EnvelopeParse.UnknownCipher -> UNKNOWN_CIPHER
            is EnvelopeParse.NotVault -> when (parse.reason) {
                NotVaultReason.EMPTY -> EMPTY
                NotVaultReason.NO_MAGIC -> NO_MAGIC
                NotVaultReason.LEADING_WHITESPACE -> LEADING_WHITESPACE
                NotVaultReason.BYTE_ORDER_MARK -> BYTE_ORDER_MARK
                NotVaultReason.NON_ASCII -> NON_ASCII
            }
            is EnvelopeParse.Format -> when (parse.reason) {
                FormatReason.HEADER_FIELDS -> HEADER_FIELDS
                FormatReason.ODD_LENGTH -> ODD_LENGTH
                FormatReason.NON_HEX_DIGIT -> NON_HEX_DIGIT
                FormatReason.PAYLOAD_FIELDS -> PAYLOAD_FIELDS
                FormatReason.PADDING -> PADDING
            }
        }

        /** The codec's hint for [parse] (folded block, trailing whitespace, …), or null. */
        fun hintOf(parse: EnvelopeParse): FormatHint? = when (parse) {
            is EnvelopeParse.Format -> parse.hint
            is EnvelopeParse.UnknownCipher -> parse.hint
            else -> null
        }
    }
}

/**
 * One envelope in an `ansible.vault` value: an inline `!vault` scalar or a whole-file vault, with its header facts as
 * written and whether it parses. Never plaintext and never the payload: only its length and line count.
 */
data class VaultIndexEntry(
    val kind: VaultEnvelopeKind,
    /** The start offset of the `!vault` scalar (its tag included), or 0 for a whole-file vault. */
    val offset: Int,
    /** For inline values: the key path from the document root (`["users", "0", "password"]`); empty for files. */
    val keyPath: List<String>,
    /** For inline values: the YAML key that holds the value, or null (a sequence item, a whole file). */
    val keyName: String?,
    /** The header's version field as written, or empty when the value has no `$ANSIBLE_VAULT` header. */
    val version: String,
    /** The header's cipher field as written, or empty when the value has no header. */
    val cipher: String,
    /** The raw label: the fourth header field whenever there is one, else null. Not mapped to `default` (A.7). */
    val label: String?,
    /** The ciphertext length in bytes when the envelope parses, else null. */
    val ciphertextLength: Int?,
    /** The number of non-blank payload lines after the header line (0 for a folded or flattened value). */
    val hexLines: Int,
    /** Why the envelope does not parse, or null when it is well formed. */
    val problem: VaultEnvelopeProblem?,
    /** The codec's hint for [problem] (folded block, trailing whitespace, …), or null. */
    val hint: FormatHint?,
    /** For inline values: the YAML style of the scalar (literal `|`, folded `>`, plain, quoted); null for files. */
    val style: ScalarStyle?,
    /** The indexed file's size in bytes. */
    val fileSize: Long,
    /** The indexed file's VFS timestamp (milliseconds) when it was indexed. */
    val timestamp: Long,
) {
    /** True when the envelope parses (Ansible would try secrets on it). */
    val wellFormed: Boolean get() = problem == null

    /** The header as written; empty version and cipher when the value has no header at all. */
    val header: VaultHeaderInfo get() = VaultHeaderInfo(version, cipher, label)

    /**
     * The label Ansible matches this envelope against: the raw label, or [defaultIdentity] (the root's
     * `vault_identity`, `default` unless renamed) for an unlabelled envelope. Resolved at query time (A.7).
     */
    fun labelOrDefault(defaultIdentity: String = VaultHeaderInfo.DEFAULT_IDENTITY): String = label ?: defaultIdentity

    /** The API view of this entry in [file]. */
    fun toInfo(file: VirtualFile): VaultEnvelopeInfo =
        VaultEnvelopeInfo(kind, SourceLocation(file, offset), header, ciphertextLength, keyName)

    override fun toString(): String =
        "VaultIndexEntry($kind@$offset ${keyPath.joinToString(".")}, $version;$cipher${label?.let { ";$it" }.orEmpty()}, " +
            "${problem ?: "ok"})"
}
