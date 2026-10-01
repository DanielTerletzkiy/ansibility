package de.terletzkiy.ansibility.vault.identity

/**
 * The key-only reader of the common `.env.local` convention (F7.9 step 3): a provisioning wrapper script runs
 * `source ./ansible/.env.local` and passes `./ansible/$ANSIBLE_LOCAL_VAULT_PASSWORD_FILE` to Ansible.
 *
 * The file is never sourced and never decoded as a whole: lines are matched as bytes, and only the line that assigns
 * one of [KEYS] is decoded, so no other line (SSH users, hosts, other secrets) ever becomes a `String` or reaches a
 * log. The caller owns the content array and zeroes it.
 *
 * The value is read with the shell's word rules for an assignment: `KEY=value`, an optional `export `, single and
 * double quotes, backslash escapes, the value ending at unquoted whitespace (so a trailing `# comment` is ignored); the
 * last assignment wins. `$NAME` and `${NAME}` expand from [environment] where known and stay as written otherwise.
 */
object EnvLocalFile {
    /** The repository's key: the password file, relative to the root. */
    const val LOCAL_PASSWORD_FILE_KEY: String = "ANSIBLE_LOCAL_VAULT_PASSWORD_FILE"

    /** Ansible's own variable, also honoured when `.env.local` sets it. */
    const val PASSWORD_FILE_KEY: String = "ANSIBLE_VAULT_PASSWORD_FILE"

    /** The keys read, in priority order. */
    val KEYS: List<String> = listOf(LOCAL_PASSWORD_FILE_KEY, PASSWORD_FILE_KEY)

    /** The value of the first of [KEYS] that [content] assigns, or null. */
    fun passwordFile(content: ByteArray, environment: Map<String, String> = emptyMap()): String? {
        val values = HashMap<String, String>()
        var start = 0
        while (start <= content.size) {
            var end = start
            while (end < content.size && content[end] != LF) end++
            var lineEnd = end
            if (lineEnd > start && content[lineEnd - 1] == CR) lineEnd--
            assignment(content, start, lineEnd)?.let { (key, valueStart) ->
                values[key] = word(String(content, valueStart, lineEnd - valueStart, Charsets.UTF_8), environment)
            }
            start = end + 1
        }
        return KEYS.firstNotNullOfOrNull { values[it] }?.takeIf { it.isNotEmpty() }
    }

    /** The key of [KEYS] assigned by the line `[from, to)` and the offset of its value, or null for any other line. */
    private fun assignment(content: ByteArray, from: Int, to: Int): Pair<String, Int>? {
        var i = skipBlanks(content, from, to)
        if (matches(content, i, to, EXPORT) && i + EXPORT.size < to && isBlank(content[i + EXPORT.size])) {
            i = skipBlanks(content, i + EXPORT.size, to)
        }
        for (key in KEYS) {
            val bytes = key.toByteArray(Charsets.US_ASCII)
            if (matches(content, i, to, bytes) && i + bytes.size < to && content[i + bytes.size] == EQUALS) {
                return key to i + bytes.size + 1
            }
        }
        return null
    }

    /** The first shell word of [text] with quotes and escapes removed and known variables expanded. */
    internal fun word(text: String, environment: Map<String, String>): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == ' ' || c == '\t' -> break
                c == '\'' -> {
                    val close = text.indexOf('\'', i + 1).let { if (it < 0) text.length else it }
                    out.append(text, i + 1, close)
                    i = close + 1
                    continue
                }
                c == '"' -> {
                    i++
                    while (i < text.length && text[i] != '"') {
                        val d = text[i]
                        if (d == '\\' && i + 1 < text.length && text[i + 1] in DOUBLE_QUOTE_ESCAPES) {
                            out.append(text[i + 1])
                            i += 2
                        } else if (d == '$') {
                            i = expand(text, i, environment, out)
                        } else {
                            out.append(d)
                            i++
                        }
                    }
                    i++
                    continue
                }
                c == '\\' && i + 1 < text.length -> {
                    out.append(text[i + 1])
                    i += 2
                    continue
                }
                c == '$' -> {
                    i = expand(text, i, environment, out)
                    continue
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /** Expands `$NAME` or `${NAME}` at [at] into [out]; returns the index after it. Unknown names stay as written. */
    private fun expand(text: String, at: Int, environment: Map<String, String>, out: StringBuilder): Int {
        val braced = at + 1 < text.length && text[at + 1] == '{'
        val nameStart = if (braced) at + 2 else at + 1
        var nameEnd = nameStart
        while (nameEnd < text.length && (text[nameEnd].isLetterOrDigit() || text[nameEnd] == '_')) nameEnd++
        if (nameEnd == nameStart || (braced && (nameEnd >= text.length || text[nameEnd] != '}'))) {
            out.append('$')
            return at + 1
        }
        val end = if (braced) nameEnd + 1 else nameEnd
        out.append(environment[text.substring(nameStart, nameEnd)] ?: text.substring(at, end))
        return end
    }

    private fun skipBlanks(content: ByteArray, from: Int, to: Int): Int {
        var i = from
        while (i < to && isBlank(content[i])) i++
        return i
    }

    private fun isBlank(b: Byte): Boolean = b == SPACE || b == TAB

    private fun matches(content: ByteArray, at: Int, to: Int, expected: ByteArray): Boolean =
        at + expected.size <= to && expected.indices.all { content[at + it] == expected[it] }

    private const val LF: Byte = 0x0A
    private const val CR: Byte = 0x0D
    private const val SPACE: Byte = 0x20
    private const val TAB: Byte = 0x09
    private const val EQUALS: Byte = 0x3D
    private val EXPORT = "export".toByteArray(Charsets.US_ASCII)
    private val DOUBLE_QUOTE_ESCAPES = setOf('"', '\\', '$', '`')
}
