package de.terletzkiy.ansibility.semantics.vault

/**
 * A parsed Ansible Vault envelope: the header `$ANSIBLE_VAULT;1.1;AES256` or `$ANSIBLE_VAULT;1.2;AES256;<label>`
 * and the payload `hex(hex(salt) LF hex(hmac) LF hex(ciphertext))`.
 *
 * Nothing here is secret, but the payload is still never logged: [toString] prints the header and the lengths only.
 * Instances are immutable; the byte accessors return copies.
 *
 * [parse] applies ansible-core's reader rules exactly (verified against 2.18.8 and 2.21.4 by the tolerance matrices
 * of `tools/vault/vectors.sh`); [format] writes byte for byte what `ansible-vault` writes.
 */
class VaultEnvelope internal constructor(
    /** The version field as written (stripped). Ansible never checks it: `9.9` decrypts. */
    val version: String,
    /** The cipher field as written (stripped); always [CIPHER] for an envelope that parsed. */
    val cipher: String,
    /**
     * The raw label: the fourth header field whenever there is one, whatever the version (`1.1;AES256;dev` has label
     * `dev`), else null. It can be empty (`1.2;AES256;`). It is not authenticated: only a hint about the secret.
     */
    val label: String?,
    private val saltBytes: ByteArray,
    private val hmacBytes: ByteArray,
    private val ciphertextBytes: ByteArray,
) {
    init {
        require(label == null || label.none { it == ';' || it == '\n' || it == '\r' }) {
            "a vault id label must not contain ';' or a line break"
        }
    }

    /** The PBKDF2 salt (32 random bytes when ansible-vault chose it; any length with `VAULT_ENCRYPT_SALT`). */
    val salt: ByteArray get() = saltBytes.copyOf()

    /** The HMAC-SHA256 tag over [ciphertext]. */
    val hmac: ByteArray get() = hmacBytes.copyOf()

    /** The AES-256-CTR ciphertext of the PKCS7-padded plaintext. */
    val ciphertext: ByteArray get() = ciphertextBytes.copyOf()

    /** The ciphertext length in bytes. */
    val ciphertextLength: Int get() = ciphertextBytes.size

    /**
     * The id Ansible matches this envelope against: the label, or [defaultIdentity] (`vault_identity`, `default`
     * unless renamed) when the header has fewer than four fields. An empty label stays empty: no secret matches it
     * under `vault_id_match`.
     */
    fun labelOrDefault(defaultIdentity: String = DEFAULT_IDENTITY): String = label ?: defaultIdentity

    /**
     * The plaintext lengths the ciphertext allows: PKCS7 always pads, so `[ct - 16, ct - 1]`. Empty when the
     * ciphertext is not a whole number of AES blocks (such an envelope never decrypts).
     */
    val plaintextLength: IntRange
        get() {
            val n = ciphertextBytes.size
            return if (n >= BLOCK_SIZE && n % BLOCK_SIZE == 0) (n - BLOCK_SIZE)..(n - 1) else IntRange.EMPTY
        }

    /**
     * The version [format] writes: `1.2` when [label] is non-empty and not `default`, else `1.1` (ansible-vault's
     * writer compares with the literal `default`, never with a renamed `vault_identity`).
     */
    val formatVersion: String get() = versionFor(label)

    /** The header line [format] writes. */
    fun headerLine(): String =
        if (formatVersion == VERSION_1_2) "$MAGIC;$VERSION_1_2;$cipher;$label" else "$MAGIC;$VERSION_1_1;$cipher"

    /** The lines [format] writes: the header, then the payload hex in lines of [LINE_WIDTH] lowercase digits. */
    fun formatLines(): List<String> {
        val inner = StringBuilder(2 * (saltBytes.size + hmacBytes.size + ciphertextBytes.size) + 2)
        Hex.appendLower(saltBytes, inner).append('\n')
        Hex.appendLower(hmacBytes, inner).append('\n')
        Hex.appendLower(ciphertextBytes, inner)
        val body = Hex.encodeLower(inner.toString().toByteArray(Charsets.US_ASCII))
        return listOf(headerLine()) + body.chunked(LINE_WIDTH)
    }

    /**
     * The envelope as ansible-vault writes it: [headerLine], the payload wrapped at 80 columns in lowercase hex, LF
     * line ends and exactly one trailing LF (the same text a literal `!vault |` block holds with clip chomping).
     */
    fun format(): String = formatLines().joinToString("\n", postfix = "\n")

    /** [format] as ASCII bytes, the content of a whole-file vault. */
    fun formatBytes(): ByteArray = format().toByteArray(Charsets.US_ASCII)

    /**
     * The same payload under another label: a header-only relabel. Labels are not covered by the HMAC, so this never
     * needs a secret; callers verify first that the new id's secret decrypts the payload (F7.6). `default`, empty or
     * null write a `1.1` header.
     */
    fun withLabel(newLabel: String?): VaultEnvelope =
        VaultEnvelope(versionFor(newLabel), cipher, newLabel?.takeIf { versionFor(it) == VERSION_1_2 },
            saltBytes, hmacBytes, ciphertextBytes)

    internal fun saltView(): ByteArray = saltBytes
    internal fun hmacView(): ByteArray = hmacBytes
    internal fun ciphertextView(): ByteArray = ciphertextBytes

    override fun equals(other: Any?): Boolean = other is VaultEnvelope && version == other.version &&
        cipher == other.cipher && label == other.label && saltBytes.contentEquals(other.saltBytes) &&
        hmacBytes.contentEquals(other.hmacBytes) && ciphertextBytes.contentEquals(other.ciphertextBytes)

    override fun hashCode(): Int =
        listOf(version, cipher, label).hashCode() * 31 + ciphertextBytes.contentHashCode()

    override fun toString(): String =
        "VaultEnvelope($version;$cipher${label?.let { ";$it" }.orEmpty()}, salt ${saltBytes.size} B, " +
            "ciphertext ${ciphertextBytes.size} B)"

    companion object {
        /** The magic that starts every envelope, and the first 14 bytes of every whole-file vault. */
        const val MAGIC: String = "\$ANSIBLE_VAULT"

        /** The only cipher ansible-core reads and writes (case-sensitive). */
        const val CIPHER: String = "AES256"

        /** Ansible's default vault id label (`DEFAULT_VAULT_IDENTITY`). */
        const val DEFAULT_IDENTITY: String = "default"

        const val VERSION_1_1: String = "1.1"
        const val VERSION_1_2: String = "1.2"

        /** The column width of the payload lines ansible-vault writes. */
        const val LINE_WIDTH: Int = 80

        internal const val BLOCK_SIZE = 16

        private val MAGIC_BYTES = MAGIC.toByteArray(Charsets.US_ASCII)
        private const val BOM = '\uFEFF'

        /** The version ansible-vault writes for [label]: `1.2` for any non-empty label except `default`. */
        fun versionFor(label: String?): String =
            if (!label.isNullOrEmpty() && label != DEFAULT_IDENTITY) VERSION_1_2 else VERSION_1_1

        /** ansible-core's `is_encrypted`: pure ASCII and starting with [MAGIC] (no leading whitespace, BOM or blank line). */
        fun isEncrypted(data: ByteArray): Boolean =
            data.size >= MAGIC_BYTES.size && data.all { it >= 0 } && startsWithMagic(data)

        /** [isEncrypted] for text: every character ASCII and the text starting with [MAGIC]. */
        fun isEncrypted(text: CharSequence): Boolean = text.startsWith(MAGIC) && text.all { it.code < 0x80 }

        /**
         * `is_encrypted_file`: whether a file is a whole-file vault, from its first bytes only. Pass at least the first
         * 14 bytes; anything after them is ignored, so a binary vault never needs to be decoded.
         */
        fun isEncryptedFile(head: ByteArray): Boolean = head.size >= MAGIC_BYTES.size && startsWithMagic(head)

        private fun startsWithMagic(data: ByteArray): Boolean = MAGIC_BYTES.indices.all { data[it] == MAGIC_BYTES[it] }

        /**
         * Parses raw bytes, as ansible-core reads a whole-file vault: any byte of 0x80 or above makes it "not vault
         * encrypted data", exactly like `is_encrypted`.
         */
        fun parse(raw: ByteArray): EnvelopeParse {
            if (raw.size >= 3 && raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte()) {
                return EnvelopeParse.NotVault(NotVaultReason.BYTE_ORDER_MARK)
            }
            // ISO-8859-1 maps every byte to one char, so bytes >= 0x80 stay visible to the ASCII check of parse(text).
            return parse(String(raw, Charsets.ISO_8859_1))
        }

        /**
         * Parses text: the value of a `!vault` scalar after YAML has removed the indentation and applied chomping (what
         * `YAMLScalar.textValue` returns), or a decoded whole file.
         *
         * The rules are ansible-core's (`_parse_vaulttext_envelope`, `_parse_vaulttext`):
         * - the text must be ASCII and start with [MAGIC];
         * - lines split at LF, CR and CRLF only; the header is the first line, stripped of ASCII whitespace and split
         *   at `;`, each field stripped; fewer than three fields is a format error; the version is never checked; the
         *   cipher must be `AES256` exactly; a fourth field is the label;
         * - the payload is every later line concatenated **without stripping**: blank lines, re-wrapping, uppercase
         *   hex and a blank line after the header are fine; a trailing or leading space or a TAB is an odd-length or
         *   non-hex error; then the inner payload must split into salt, HMAC and ciphertext at its first two LFs.
         */
        fun parse(text: CharSequence): EnvelopeParse {
            notVaultReason(text)?.let { return EnvelopeParse.NotVault(it) }
            val lines = splitLines(text)
            val fields = lines.first().pyStrip().split(';')
            if (fields.size < 3) {
                return EnvelopeParse.Format(FormatReason.HEADER_FIELDS, EnvelopePart.HEADER, hintFor(lines))
            }
            val version = fields[1].pyStrip()
            val cipher = fields[2].pyStrip()
            val label = fields.getOrNull(3)?.pyStrip()
            if (cipher != CIPHER) return EnvelopeParse.UnknownCipher(cipher, hintFor(lines))

            val body = StringBuilder(text.length)
            for (i in 1 until lines.size) body.append(lines[i])
            val inner = when (val decoded = Hex.decode(body)) {
                is Hex.Decoded.Bytes -> decoded.bytes
                is Hex.Decoded.Error -> return EnvelopeParse.Format(decoded.reason, EnvelopePart.BODY, hintFor(lines))
            }
            val first = inner.indexOf(LF)
            val second = if (first < 0) -1 else inner.indexOf(LF, first + 1)
            if (second < 0) return EnvelopeParse.Format(FormatReason.PAYLOAD_FIELDS, EnvelopePart.BODY, hintFor(lines))
            val salt = Hex.decode(inner, 0, first)
            if (salt is Hex.Decoded.Error) return EnvelopeParse.Format(salt.reason, EnvelopePart.SALT, null)
            val ciphertext = Hex.decode(inner, second + 1, inner.size)
            if (ciphertext is Hex.Decoded.Error) return EnvelopeParse.Format(ciphertext.reason, EnvelopePart.CIPHERTEXT, null)
            val hmac = Hex.decode(inner, first + 1, second)
            if (hmac is Hex.Decoded.Error) return EnvelopeParse.Format(hmac.reason, EnvelopePart.HMAC, null)
            return EnvelopeParse.Ok(
                VaultEnvelope(
                    version, cipher, label,
                    (salt as Hex.Decoded.Bytes).bytes, (hmac as Hex.Decoded.Bytes).bytes,
                    (ciphertext as Hex.Decoded.Bytes).bytes,
                ),
            )
        }

        /** Builds an envelope from its parts, for the cipher; the label follows [withLabel]'s rules. */
        internal fun of(label: String?, salt: ByteArray, hmac: ByteArray, ciphertext: ByteArray): VaultEnvelope =
            VaultEnvelope(versionFor(label), CIPHER, label?.takeIf { versionFor(it) == VERSION_1_2 }, salt, hmac, ciphertext)

        private const val LF: Byte = 0x0A

        private fun ByteArray.indexOf(byte: Byte, from: Int = 0): Int {
            for (i in from until size) if (this[i] == byte) return i
            return -1
        }

        private fun notVaultReason(text: CharSequence): NotVaultReason? {
            if (text.isEmpty()) return NotVaultReason.EMPTY
            if (text[0] == BOM) return NotVaultReason.BYTE_ORDER_MARK
            if (!text.startsWith(MAGIC)) {
                val firstContent = text.indexOfFirst { it !in PY_WHITESPACE }
                return if (firstContent > 0 && text.startsWith(MAGIC, firstContent)) {
                    NotVaultReason.LEADING_WHITESPACE
                } else {
                    NotVaultReason.NO_MAGIC
                }
            }
            return if (text.any { it.code >= 0x80 }) NotVaultReason.NON_ASCII else null
        }

        /** Python's `bytes.splitlines()`: breaks at LF, CR and CRLF only; no empty element after a final break. */
        internal fun splitLines(text: CharSequence): List<String> {
            val lines = ArrayList<String>()
            var start = 0
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\n' || c == '\r') {
                    lines += text.substring(start, i)
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    start = i + 1
                }
                i++
            }
            if (start < text.length) lines += text.substring(start)
            if (lines.isEmpty()) lines += ""
            return lines
        }

        private val HEX_RUN = Regex("""\s[0-9A-Fa-f]{16,}""")

        /** Why a payload line failed, for messages and fixes (ANS-V102, V103); null when nothing typical is seen. */
        private fun hintFor(lines: List<String>): FormatHint? {
            val content = lines.filter { it.isNotBlank() }
            if (content.size == 1 && HEX_RUN.containsMatchIn(content[0])) return FormatHint.FOLDED_BLOCK
            val body = lines.drop(1)
            return when {
                body.any { it.isNotEmpty() && (it.last() == ' ' || it.last() == '\t') } -> FormatHint.TRAILING_WHITESPACE
                body.any { it.isNotEmpty() && (it.first() == ' ' || it.first() == '\t') } -> FormatHint.LEADING_WHITESPACE
                body.any { '\t' in it } -> FormatHint.TAB
                else -> null
            }
        }
    }
}

/** The result of [VaultEnvelope.parse]. Format results are reported before any secret is tried (Ansible stops at once). */
sealed interface EnvelopeParse {
    /** A well-formed envelope. */
    class Ok(val envelope: VaultEnvelope) : EnvelopeParse {
        override fun toString(): String = "Ok($envelope)"
    }

    /** Not vault data at all: Ansible reports "Input is not vault encrypted data" (an inline value) or treats a file as plain. */
    data class NotVault(val reason: NotVaultReason) : EnvelopeParse

    /** Malformed: Ansible raises `AnsibleVaultFormatError` and tries no further secret. */
    data class Format(val reason: FormatReason, val part: EnvelopePart, val hint: FormatHint?) : EnvelopeParse

    /**
     * A cipher other than `AES256` (case-sensitive): "Cipher 'aes256' could not be found". A folded `>` block lands
     * here too, because the payload is folded into the header line ([hint] [FormatHint.FOLDED_BLOCK]).
     */
    class UnknownCipher(
        /** The cipher field as written; for a folded block it holds payload hex, so only [name] is shown. */
        val cipher: String,
        val hint: FormatHint?,
    ) : EnvelopeParse {
        /** The cipher field up to the first whitespace (`aes256`; `AES256` for a folded block). */
        val name: String get() = cipher.takeWhile { !it.isWhitespace() }

        override fun toString(): String = "UnknownCipher($name, hint=$hint)"
    }
}

/** Why a text is not vault data. */
enum class NotVaultReason {
    /** Nothing at all. */
    EMPTY,

    /** It does not start with `$ANSIBLE_VAULT`. */
    NO_MAGIC,

    /** Whitespace or a blank line before `$ANSIBLE_VAULT` (a quoted `" $ANSIBLE_VAULT…"`, a file starting with LF). */
    LEADING_WHITESPACE,

    /** A UTF-8 byte order mark before `$ANSIBLE_VAULT`. */
    BYTE_ORDER_MARK,

    /** It starts with `$ANSIBLE_VAULT` but contains a non-ASCII character or byte. */
    NON_ASCII,
}

/** What is malformed, in ansible-core's error vocabulary. */
enum class FormatReason {
    /** Fewer than three `;` fields in the header ("Vault envelope format error"). */
    HEADER_FIELDS,

    /** An odd number of hex digits ("Odd-length string"): typically trailing spaces or a TAB on a payload line. */
    ODD_LENGTH,

    /** A character that is not a hex digit ("Non-hexadecimal digit found"): typically leading spaces in a whole file. */
    NON_HEX_DIGIT,

    /** The inner payload lacks the two LF separators between salt, HMAC and ciphertext ("Vault vaulttext format error"). */
    PAYLOAD_FIELDS,

    /**
     * The HMAC verified but the decrypted padding is invalid. Only a crafted envelope can do this; ansible-core fails
     * with an unhandled error, so it is treated like a format error (no further secret is tried).
     */
    PADDING,
}

/** Which part of the envelope a [FormatReason] refers to. */
enum class EnvelopePart { HEADER, BODY, SALT, HMAC, CIPHERTEXT }

/** A recognisable cause of a format error, for messages and password-free fixes. */
enum class FormatHint {
    /** A folded `>` block: YAML joined the payload lines into the header line (ANS-V102). */
    FOLDED_BLOCK,

    /** Spaces or TABs at the end of a payload line (ANS-V103, "Odd-length string"). */
    TRAILING_WHITESPACE,

    /** Spaces or TABs at the start of a payload line (a whole file indented like an inline block). */
    LEADING_WHITESPACE,

    /** A TAB inside a payload line. */
    TAB,
}

/** Python's ASCII whitespace for `bytes.strip()`: space, TAB, LF, VT, FF, CR. */
internal val PY_WHITESPACE: Set<Char> = setOf(' ', '\t', '\n', '\u000B', '\u000C', '\r')

/** `bytes.strip()` on ASCII text. */
internal fun String.pyStrip(): String = trim { it in PY_WHITESPACE }
