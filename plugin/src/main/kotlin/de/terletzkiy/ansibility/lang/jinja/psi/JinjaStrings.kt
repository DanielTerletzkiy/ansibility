package de.terletzkiy.ansibility.lang.jinja.psi

/**
 * Decoding of Jinja string literals. Jinja's lexer decodes the body of a string token with Python's
 * `unicode-escape` codec after normalising newlines; this is the same decoding.
 */
object JinjaStrings {
    /**
     * The value of the string token [text] (quotes included): escapes decoded, `\r\n` and `\r` normalised to `\n`.
     * An unterminated string (no closing quote) yields the text after the opening quote.
     */
    fun unquote(text: String): String {
        if (text.isEmpty()) return text
        val quote = text[0]
        if (quote != '\'' && quote != '"') return text
        val end = if (text.length >= 2 && text.last() == quote && !endsWithEscape(text)) text.length - 1 else text.length
        return decode(text.substring(1, end).replace("\r\n", "\n").replace('\r', '\n'))
    }

    /** Whether the last quote of [text] is escaped by an odd number of backslashes before it. */
    private fun endsWithEscape(text: String): Boolean {
        var backslashes = 0
        var i = text.length - 2
        while (i >= 1 && text[i] == '\\') {
            backslashes++
            i--
        }
        return backslashes % 2 == 1
    }

    /** Python `unicode-escape` decoding; an escape Python does not know keeps its backslash. */
    private fun decode(body: String): String {
        if ('\\' !in body) return body
        val out = StringBuilder(body.length)
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c != '\\' || i + 1 >= body.length) {
                out.append(c)
                i++
                continue
            }
            val e = body[i + 1]
            i += 2
            when (e) {
                '\n' -> Unit
                '\\' -> out.append('\\')
                '\'' -> out.append('\'')
                '"' -> out.append('"')
                'a' -> out.append('\u0007')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'v' -> out.append('\u000B')
                in '0'..'7' -> {
                    var value = e - '0'
                    var digits = 1
                    while (digits < 3 && i < body.length && body[i] in '0'..'7') {
                        value = value * 8 + (body[i] - '0')
                        i++
                        digits++
                    }
                    out.append(value.toChar())
                }
                'x' -> i = hex(body, i, 2, out, "\\x")
                'u' -> i = hex(body, i, 4, out, "\\u")
                'U' -> i = hex(body, i, 8, out, "\\U")
                else -> out.append('\\').append(e)
            }
        }
        return out.toString()
    }

    /** Appends the code point of [count] hex digits at [from]; keeps the escape text when the digits are incomplete. */
    private fun hex(body: String, from: Int, count: Int, out: StringBuilder, escape: String): Int {
        val end = from + count
        if (end > body.length) {
            out.append(escape).append(body, from, body.length)
            return body.length
        }
        val code = body.substring(from, end).toIntOrNull(16)
        if (code == null || !Character.isValidCodePoint(code)) {
            out.append(escape).append(body, from, end)
        } else {
            out.appendCodePoint(code)
        }
        return end
    }
}
