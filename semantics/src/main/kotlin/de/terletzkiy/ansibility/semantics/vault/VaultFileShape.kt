package de.terletzkiy.ansibility.semantics.vault

/**
 * How a file's text stands to Ansible's whole-file vault rule (plan amendment R21, D159: ANS-V107).
 *
 * ansible-core decrypts a file only when its first 14 bytes are exactly `$ANSIBLE_VAULT` (`is_encrypted_file`,
 * `is_encrypted`): no byte order mark, blank, blank line or YAML tag may come first. A file with anything before the
 * magic is used as it is: `copy`, `template`, `lookup('file')` and even `lookup('unvault')` deliver the envelope text
 * (verified with ansible-core 2.21.4 on synthetic files), and as a vars file it fails to load.
 */
enum class VaultShapeKind {
    /** A whole-file vault: `$ANSIBLE_VAULT` is the first byte (ANS-V101–V103 judge its envelope). */
    VAULT,

    /**
     * A `!vault |` line (also `!vault >`, `|-`, `!vault-encrypted`, `--- !vault |`), then the envelope: what
     * `ansible-vault encrypt_string` prints for a YAML value, saved as a file.
     */
    TAG_LINE,

    /**
     * A `key: !vault |` line, then the envelope (`encrypt_string --name` output), in a file Ansible reads as it is (in
     * any other file it may be a vars file with one variable, which Ansible loads).
     */
    YAML_VALUE,

    /** A YAML file Ansible loads (a vars file, a playbook) that is one top-level `!vault` value instead of a mapping. */
    VARS_DOCUMENT,

    /** Comments, blank lines, `---` or directives before the envelope. */
    PREAMBLE,

    /** The envelope indented, nothing else before it. */
    INDENTED,

    /**
     * A UTF-8 byte order mark, then the envelope (also before a tag line, a preamble or indentation: the mark is what
     * has to go first, by File | File Properties | Remove BOM; the file is judged again afterwards).
     */
    BYTE_ORDER_MARK,

    /** The envelope inside a quoted string (reported only in files Ansible reads as they are). */
    QUOTED,

    /** The envelope inside other text (reported only in files Ansible reads as they are). */
    MIXED,
    ;

    /**
     * True for the shapes that are one whole envelope behind something Ansible does not skip: dropping it gives the
     * whole-file vault the file was meant to be ([VaultFileShape.unwrapped]).
     */
    val isWrapped: Boolean get() = this != VAULT && this != QUOTED && this != MIXED
}

/**
 * What the caller knows about a file from its path and its first bytes. The classifier never reads configuration or
 * another file, so it stays pure for indexers (DEV.md rule 5).
 */
data class ShapeContext(
    /**
     * The file is YAML (a `.yml`/`.yaml` name, also a `.yml.j2` template, or a vars file without one): `key: !vault |`
     * values are the normal inline form, a top-level `!vault` value is [VaultShapeKind.VARS_DOCUMENT], and envelopes in
     * values are left to the inline checks (ANS-V101–V103, ANS-V114). Takes precedence over [readRaw].
     */
    val yamlInput: Boolean,
    /**
     * Ansible uses the file as it is: below a `files` or `templates` directory, a Jinja template, or a key-like name.
     * Only in such files, when they are not YAML, are [VaultShapeKind.QUOTED], [VaultShapeKind.MIXED] and
     * [VaultShapeKind.YAML_VALUE] reported.
     */
    val readRaw: Boolean,
    /** The file starts with a UTF-8 byte order mark that the text does not contain (an IDE document's text). */
    val byteOrderMark: Boolean = false,
    /**
     * A documentation file (Markdown, reStructuredText, AsciiDoc) that Ansible never loads or decrypts: an envelope in it
     * is an example, so nothing is reported unless [readRaw] says Ansible copies the file as it is.
     */
    val documentation: Boolean = false,
)

/**
 * The shape of one file's text ([classify]): its [kind], where the envelope header is, and the whole-file vault it
 * should be. Nothing here is secret (an envelope is ciphertext), but [toString] still prints positions only.
 */
class VaultFileShape internal constructor(
    val kind: VaultShapeKind,
    /** The 0-based line of the `$ANSIBLE_VAULT` header (or of its first mention for QUOTED and MIXED). */
    val headerLine: Int,
    /** The offset of the `$` of that header in the text. */
    val headerOffset: Int,
    /** The 0-based line of the `!vault` tag line, or -1. */
    val tagLine: Int,
    /** The lines before [headerLine]: the tag line, comments and blank lines. */
    val linesBefore: Int,
    /** The columns before the magic on its line (spaces and TABs; for QUOTED and MIXED the other text). */
    val indent: Int,
    /**
     * The whole-file vault the file should be, for the [VaultShapeKind.isWrapped] shapes: the header and the payload
     * lines without the tag or preamble lines, without the indentation of the header and without trailing blanks and
     * blank lines, in the text's own line separator, ending with exactly one; the payload digits are untouched. For
     * [VaultShapeKind.BYTE_ORDER_MARK] the text itself (the mark is not part of it) when the magic comes first after the
     * mark, null when more wraps it (the mark must go first). Null for the other shapes and when the classifier was asked
     * for the verdict only or got a head of the file.
     */
    val unwrapped: String?,
    /**
     * `VaultEnvelope.parse` of [unwrapped] (of the text for [VaultShapeKind.VAULT]): the envelope's own problems, with
     * ansible-core's reader rules. Null when [unwrapped] is null (except for VAULT).
     */
    val inner: EnvelopeParse?,
) {
    /** True when [unwrapped] is a well-formed envelope, so the file can become a whole-file vault without any password. */
    val unwrapsToVault: Boolean get() = kind.isWrapped && inner is EnvelopeParse.Ok

    override fun toString(): String =
        "VaultFileShape($kind, header line ${headerLine + 1}, tag line ${tagLine + 1}, $linesBefore before, indent $indent" +
            ", inner ${inner?.let { if (it is EnvelopeParse.Ok) "Ok" else it.toString() }})"

    companion object {
        /** The envelope must start within the first 4 KiB characters (a few lines of tag, comments or indentation). */
        const val HEAD_CHARS: Int = 4096

        /** ANS-V114: the fewest payload digits an untagged value needs before it counts as an envelope. */
        private const val MIN_UNTAGGED_PAYLOAD = 32

        private const val MAGIC = VaultEnvelope.MAGIC

        /**
         * What a header line starts with: the magic and its field separator. Text such as `$ANSIBLE_VAULT_PASSWORD_FILE`
         * (Ansible's own environment variables) is no envelope.
         */
        private const val HEADER_PREFIX = "$MAGIC;"

        /** A complete header (`$ANSIBLE_VAULT;1.1;AES256`): what a quoted or mixed-in mention must be to count. */
        private val MENTION = Regex(Regex.escape(HEADER_PREFIX) + """[0-9]+\.[0-9]+;[A-Za-z0-9_]+""")

        /**
         * The tag line of a wrapped envelope: an optional `---`, an optional key (plain or quoted, after sequence dashes)
         * or bare sequence dashes, the `!vault` or `!vault-encrypted` tag (`! vault` too), an optional block indicator
         * with chomping and indentation indicators, an optional comment.
         */
        private val TAG_LINE = Regex(
            """[ \t]*(?:---[ \t]+)?(?<key>(?:-[ \t]+)+|(?:-[ \t]+)*(?:"(?:[^"\\]|\\.)*"|'(?:[^']|'')*'|[^\s#'"!][^#]*?)[ \t]*:[ \t]+)?""" +
                """![ \t]*vault(?:-encrypted)?(?:[ \t]+[|>][-+0-9]*)?[ \t]*(?:#.*)?""",
        )
        private val DOCUMENT_START = Regex("""---[ \t]*(?:#.*)?""")
        private val WHITESPACE = Regex("""\s+""")

        /** The cheap gate: whether `$ANSIBLE_VAULT;` starts within [HEAD_CHARS], so [classify] can find anything. */
        fun mayHoldEnvelope(text: CharSequence): Boolean = indexOf(text, HEADER_PREFIX, 0, minOf(text.length, HEAD_CHARS)) >= 0

        /**
         * The shape of a file's raw [bytes] (a byte order mark is read from them): for the index and the commit check,
         * which read bytes. Bytes of 0x80 and above stay visible to the codec's ASCII rule (ISO-8859-1).
         */
        fun classify(bytes: ByteArray, context: ShapeContext, complete: Boolean = true, withEnvelope: Boolean = true): VaultFileShape? {
            val bom = bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
            val start = if (bom) 3 else 0
            val text = String(bytes, start, bytes.size - start, Charsets.ISO_8859_1)
            return classify(text, if (bom) context.copy(byteOrderMark = true) else context, complete, withEnvelope)
        }

        /**
         * The shape of [text], or null when it holds no envelope that ANS-V107 reports: no `$ANSIBLE_VAULT;` header within
         * [HEAD_CHARS], an envelope quoted or inside other text in a YAML file or in a file Ansible does not read as it
         * is (code), a `key: !vault |` value outside the files Ansible reads as they are (the normal inline form in YAML),
         * or a documentation file ([ShapeContext.documentation]). A quoted or mixed-in mention counts only as a complete
         * header (`$ANSIBLE_VAULT;1.1;AES256`), a header line only with the magic's `;`.
         *
         * The lines before the header may be blank lines, comments, `---`, directives and at most one tag line; every
         * line after it must be hex once its indentation is removed (trailing blanks allowed), or the envelope counts as
         * mixed into other text. With [complete] false [text] is a head of a longer file: its last, possibly cut line is
         * ignored and neither [VaultFileShape.unwrapped] nor [VaultFileShape.inner] is computed; with [withEnvelope]
         * false neither is either (the verdict only, for indexers).
         */
        fun classify(text: CharSequence, context: ShapeContext, complete: Boolean = true, withEnvelope: Boolean = true): VaultFileShape? {
            val envelope = withEnvelope && complete
            if (startsWith(text, MAGIC, 0) && !(context.byteOrderMark && !startsWith(text, HEADER_PREFIX, 0))) {
                val kind = if (context.byteOrderMark) VaultShapeKind.BYTE_ORDER_MARK else VaultShapeKind.VAULT
                return VaultFileShape(
                    kind, 0, 0, -1, 0, 0,
                    unwrapped = if (envelope && kind == VaultShapeKind.BYTE_ORDER_MARK) text.toString() else null,
                    inner = if (envelope) VaultEnvelope.parse(text) else null,
                )
            }
            if (context.documentation && !context.readRaw) return null
            if (!mayHoldEnvelope(text)) return null
            // Only complete lines count: a head of a longer file ends at its last line break.
            val limit = if (complete) text.length else lastLineEnd(text)
            // The lines before the header are read first; the body only when the verdict needs it.
            val before = ArrayList<Line>()
            var header: Line? = null
            var mention = -1
            var mentionAt = -1
            var start = 0
            while (start < limit && start < HEAD_CHARS) {
                val line = Line(start, nextBreak(text, start, limit))
                var at = indexOf(text, HEADER_PREFIX, line.start, line.end)
                if (at in 0 until HEAD_CHARS && skipBlanks(text, line.start, line.end) == at) {
                    header = line
                    break
                }
                while (at in 0 until HEAD_CHARS && mention < 0) {
                    if (MENTION.matchesAt(text, at)) {
                        mention = before.size
                        mentionAt = at
                    }
                    at = indexOf(text, HEADER_PREFIX, at + 1, line.end)
                }
                before += line
                start = nextStart(text, line.end, limit)
            }
            val reportsMixed = context.readRaw && !context.yamlInput
            if (header == null) {
                if (mention < 0 || !reportsMixed) return null
                val quote = text[mentionAt - 1]
                val kind = if (quote == '"' || quote == '\'') VaultShapeKind.QUOTED else VaultShapeKind.MIXED
                return VaultFileShape(kind, mention, mentionAt, -1, mention, mentionAt - before[mention].start, null, null)
            }
            val headerLine = before.size
            val magicAt = skipBlanks(text, header.start, header.end)
            val indent = magicAt - header.start
            var tagLine = -1
            var keyed = false
            var mixed = false
            for ((index, line) in before.withIndex()) {
                val content = text.subSequence(skipBlanks(text, line.start, line.end), trimEnd(text, line.start, line.end))
                if (content.isEmpty() || content[0] == '#' || content[0] == '%' || DOCUMENT_START.matches(content)) continue
                val match = TAG_LINE.matchEntire(content)
                if (match == null || tagLine >= 0) {
                    mixed = true
                    break
                }
                tagLine = index
                keyed = match.groups["key"] != null
            }
            // A `key: !vault |` value in YAML is the normal inline form (ANS-V101–V103 judge it); a file Ansible does not
            // read as it is may be a vars file of one variable (`vars_files` and `include_vars` take any name).
            if (tagLine >= 0 && keyed && (context.yamlInput || !context.readRaw)) return null
            if (mixed && !reportsMixed) return null
            val bodyStart = nextStart(text, header.end, limit)
            if (!mixed) mixed = !payloadOnly(text, bodyStart, limit)
            if (mixed) {
                if (!reportsMixed) return null
                return VaultFileShape(VaultShapeKind.MIXED, headerLine, magicAt, tagLine, headerLine, indent, null, null)
            }
            val kind = when {
                tagLine >= 0 && !keyed -> if (context.yamlInput) VaultShapeKind.VARS_DOCUMENT else VaultShapeKind.TAG_LINE
                tagLine >= 0 -> VaultShapeKind.YAML_VALUE
                headerLine > 0 -> VaultShapeKind.PREAMBLE
                indent > 0 -> VaultShapeKind.INDENTED
                else -> return null
            }
            // A byte order mark comes before everything: it has to go first, then the file is judged again.
            if (context.byteOrderMark) return VaultFileShape(VaultShapeKind.BYTE_ORDER_MARK, headerLine, magicAt, tagLine, headerLine, indent, null, null)
            val unwrapped = if (envelope) unwrap(text, header, indent, bodyStart) else null
            return VaultFileShape(kind, headerLine, magicAt, tagLine, headerLine, indent, unwrapped, unwrapped?.let(VaultEnvelope::parse))
        }

        /**
         * ANS-V114: whether a YAML value written without a vault tag is an envelope: its first word is a
         * `$ANSIBLE_VAULT;<version>;<cipher>` header and every further word is hex, at least 32 digits in all (a literal
         * block keeps the lines, a plain or folded value joins them with spaces). A one-line mention stays silent.
         */
        fun isUntaggedEnvelope(value: CharSequence): Boolean {
            val trimmed = value.trim()
            if (!startsWith(trimmed, MAGIC, 0)) return false
            val words = trimmed.split(WHITESPACE)
            if (words.size < 2 || !words[0].startsWith("$MAGIC;") || words[0].count { it == ';' } < 2) return false
            var digits = 0
            for (i in 1 until words.size) {
                val word = words[i]
                if (!isHex(word, 0, word.length)) return false
                digits += word.length
            }
            return digits >= MIN_UNTAGGED_PAYLOAD
        }

        // -------------------------------------------------------------------------------------------- text helpers

        /** One line of the text: [start] inclusive, [end] exclusive, without its line break. */
        private class Line(val start: Int, val end: Int)

        /** The offset of the next LF or CR in [from, limit), or [limit]. */
        private fun nextBreak(text: CharSequence, from: Int, limit: Int): Int {
            var i = from
            while (i < limit && text[i] != '\n' && text[i] != '\r') i++
            return i
        }

        /** The start of the line after the line break at [breakAt] (CRLF counts once). */
        private fun nextStart(text: CharSequence, breakAt: Int, limit: Int): Int =
            if (breakAt < limit && text[breakAt] == '\r' && breakAt + 1 < limit && text[breakAt + 1] == '\n') breakAt + 2 else breakAt + 1

        /** The end of the last line break of [text] (0 without any): where the complete lines of a head end. */
        private fun lastLineEnd(text: CharSequence): Int {
            for (i in text.length - 1 downTo 0) if (text[i] == '\n' || text[i] == '\r') return i + 1
            return 0
        }

        /** True when every non-blank line in [from, limit) is hex once its blanks are removed. */
        private fun payloadOnly(text: CharSequence, from: Int, limit: Int): Boolean {
            var start = from
            while (start < limit) {
                val end = nextBreak(text, start, limit)
                val content = skipBlanks(text, start, end)
                val trimmed = trimEnd(text, content, end)
                if (content < trimmed && !isHex(text, content, trimmed)) return false
                start = nextStart(text, end, limit)
            }
            return true
        }

        /** See [VaultFileShape.unwrapped]: [header] from its magic, then the payload lines from [bodyStart] on. */
        private fun unwrap(text: CharSequence, header: Line, indent: Int, bodyStart: Int): String {
            val separator = separatorOf(text)
            val result = StringBuilder(text.length)
            val magicAt = header.start + indent
            result.append(text, magicAt, trimEnd(text, magicAt, header.end)).append(separator)
            var start = bodyStart
            while (start < text.length) {
                val lineEnd = nextBreak(text, start, text.length)
                val end = trimEnd(text, start, lineEnd)
                var from = start
                while (from < end && from - start < indent && isBlank(text[from])) from++
                if (from < end) result.append(text, from, end).append(separator)
                start = nextStart(text, lineEnd, text.length)
            }
            return result.toString()
        }

        /** The text's first line separator (CRLF, LF or CR); LF when it has none. */
        private fun separatorOf(text: CharSequence): String {
            for (i in text.indices) {
                when (text[i]) {
                    '\n' -> return "\n"
                    '\r' -> return if (i + 1 < text.length && text[i + 1] == '\n') "\r\n" else "\r"
                }
            }
            return "\n"
        }

        private fun isBlank(c: Char): Boolean = c == ' ' || c == '\t'

        private fun skipBlanks(text: CharSequence, from: Int, to: Int): Int {
            var i = from
            while (i < to && isBlank(text[i])) i++
            return i
        }

        private fun trimEnd(text: CharSequence, from: Int, to: Int): Int {
            var e = to
            while (e > from && isBlank(text[e - 1])) e--
            return e
        }

        private fun isHex(text: CharSequence, from: Int, to: Int): Boolean {
            if (from >= to) return false
            for (i in from until to) {
                val c = text[i]
                if (!(c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F')) return false
            }
            return true
        }

        private fun startsWith(text: CharSequence, prefix: String, at: Int): Boolean {
            if (at + prefix.length > text.length) return false
            for (i in prefix.indices) if (text[at + i] != prefix[i]) return false
            return true
        }

        /** The first index of [what] in [text] that starts in [from, to), or -1. */
        private fun indexOf(text: CharSequence, what: String, from: Int, to: Int): Int {
            var i = from
            while (i < to) {
                if (text[i] == what[0] && startsWith(text, what, i)) return i
                i++
            }
            return -1
        }
    }
}
