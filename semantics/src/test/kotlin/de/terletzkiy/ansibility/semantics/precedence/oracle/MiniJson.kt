package de.terletzkiy.ansibility.semantics.precedence.oracle

import java.math.BigInteger

/**
 * Test-only JSON reader for the oracle files: objects → LinkedHashMap, arrays → List, integers → BigInteger,
 * other numbers → Double, plus Python's `NaN`/`Infinity` extensions that `json.dumps` may emit.
 */
object MiniJson {
    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.value()
        reader.skipWhitespace()
        require(reader.pos == text.length) { "trailing content at ${reader.pos}" }
        return value
    }

    private class Reader(val s: String) {
        var pos = 0

        fun skipWhitespace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(): Any? {
            skipWhitespace()
            require(pos < s.length) { "unexpected end" }
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                'N' -> literal("NaN", Double.NaN)
                'I' -> literal("Infinity", Double.POSITIVE_INFINITY)
                else -> if (c == '-' && s.startsWith("-Infinity", pos)) literal("-Infinity", Double.NEGATIVE_INFINITY) else number()
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            require(s.startsWith(word, pos)) { "bad literal at $pos" }
            pos += word.length
            return value
        }

        private fun obj(): Map<String, Any?> {
            pos++
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (s[pos] == '}') return out.also { pos++ }
            while (true) {
                skipWhitespace()
                val key = string()
                skipWhitespace()
                require(s[pos] == ':') { "expected ':' at $pos" }
                pos++
                out[key] = value()
                skipWhitespace()
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> error("expected ',' or '}' at ${pos - 1}")
                }
            }
        }

        private fun array(): List<Any?> {
            pos++
            val out = ArrayList<Any?>()
            skipWhitespace()
            if (s[pos] == ']') return out.also { pos++ }
            while (true) {
                out += value()
                skipWhitespace()
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> error("expected ',' or ']' at ${pos - 1}")
                }
            }
        }

        private fun string(): String {
            require(s[pos] == '"') { "expected string at $pos" }
            pos++
            val sb = StringBuilder()
            while (true) {
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                            pos += 4
                        }
                        else -> error("bad escape \\$e")
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun number(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && s[pos].isDigit()) pos++
            var isFloat = false
            if (pos < s.length && s[pos] == '.') {
                isFloat = true
                pos++
                while (pos < s.length && s[pos].isDigit()) pos++
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                isFloat = true
                pos++
                if (s[pos] == '+' || s[pos] == '-') pos++
                while (pos < s.length && s[pos].isDigit()) pos++
            }
            val text = s.substring(start, pos)
            require(text.isNotEmpty() && text != "-") { "bad number at $start" }
            return if (isFloat) text.toDouble() else BigInteger(text)
        }
    }
}
