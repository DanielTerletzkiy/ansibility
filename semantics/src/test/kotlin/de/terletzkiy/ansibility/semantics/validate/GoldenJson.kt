package de.terletzkiy.ansibility.semantics.validate

/**
 * Minimal JSON reader for the golden tables (objects → LinkedHashMap, arrays → List, numbers → Long/Double,
 * strings with all JSON escapes). Test-only; the tables are written by `json.dumps` with `ensure_ascii`.
 */
object GoldenJson {
    fun parse(text: String): Any? = Reader(text).run {
        val value = value()
        skipWhitespace()
        require(pos == text.length) { "trailing data at $pos" }
        value
    }

    private class Reader(private val s: String) {
        var pos = 0

        fun skipWhitespace() {
            while (pos < s.length && s[pos] in " \t\r\n") pos++
        }

        fun value(): Any? {
            skipWhitespace()
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else error("unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            require(s.startsWith(word, pos)) { "expected $word at $pos" }
            pos += word.length
            return value
        }

        private fun number(): Any {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] in "+-.eE")) pos++
            val text = s.substring(start, pos)
            return if (text.any { it in ".eE" }) text.toDouble() else text.toLong()
        }

        private fun obj(): Map<String, Any?> {
            pos++
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (s[pos] == '}') {
                pos++
                return result
            }
            while (true) {
                skipWhitespace()
                val key = string()
                skipWhitespace()
                require(s[pos] == ':') { "expected ':' at $pos" }
                pos++
                result[key] = value()
                skipWhitespace()
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return result
                    else -> error("expected ',' or '}' at ${pos - 1}")
                }
            }
        }

        private fun array(): List<Any?> {
            pos++
            val result = mutableListOf<Any?>()
            skipWhitespace()
            if (s[pos] == ']') {
                pos++
                return result
            }
            while (true) {
                result += value()
                skipWhitespace()
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return result
                    else -> error("expected ',' or ']' at ${pos - 1}")
                }
            }
        }

        private fun string(): String {
            require(s[pos] == '"') { "expected string at $pos" }
            pos++
            val out = StringBuilder()
            while (true) {
                val c = s[pos++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> when (val e = s[pos++]) {
                        '"', '\\', '/' -> out.append(e)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            out.append(s.substring(pos, pos + 4).toInt(16).toChar())
                            pos += 4
                        }
                        else -> error("bad escape \\$e at $pos")
                    }
                    else -> out.append(c)
                }
            }
        }
    }
}
