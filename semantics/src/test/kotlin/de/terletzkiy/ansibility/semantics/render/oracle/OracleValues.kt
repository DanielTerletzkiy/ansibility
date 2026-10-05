package de.terletzkiy.ansibility.semantics.render.oracle

import de.terletzkiy.ansibility.semantics.json.Json
import java.math.BigDecimal
import java.math.BigInteger

/** Value comparisons of the oracle, with Python's JSON distinctions (`1` is not `1.0`, `True` is not `1`). */
internal object OracleValues {

    /**
     * Deep equality of JSON-shaped values: integers by value whatever their Kotlin type, floating-point numbers by
     * value (`NaN` equals `NaN`, `-0.0` is not `0.0`: Python prints them apart), an integer never equals a float,
     * booleans only booleans, maps regardless of key order (the json callback sorts keys), lists in order, any
     * [CharSequence] by its characters.
     */
    fun jsonEquals(a: Any?, b: Any?): Boolean = when {
        a == null || b == null -> a == null && b == null
        a is Boolean || b is Boolean -> a == b
        isInteger(a) && isInteger(b) -> integer(a) == integer(b)
        isFloat(a) && isFloat(b) -> (a as Number).toDouble().equals((b as Number).toDouble())
        a is Number || b is Number -> false
        a is CharSequence && b is CharSequence -> a.toString() == b.toString()
        a is Map<*, *> && b is Map<*, *> -> a.size == b.size && a.all { (k, v) -> b.containsKey(k) && jsonEquals(v, b[k]) }
        a is List<*> && b is List<*> -> a.size == b.size && a.indices.all { jsonEquals(a[it], b[it]) }
        else -> false
    }

    /**
     * Parses JSON as Python's `json` writes it: RFC 8259 plus the bare tokens `NaN`, `Infinity` and `-Infinity`,
     * which become the [Double] values (a YAML `.inf` stored by `set_fact`, case 04).
     */
    fun parsePythonJson(text: String): Any? {
        val out = StringBuilder(text.length)
        var i = 0
        var replaced = false
        while (i < text.length) {
            val c = text[i]
            if (c == '"') {
                val end = stringEnd(text, i)
                out.append(text, i, end)
                i = end
                continue
            }
            val token = NON_FINITE.keys.firstOrNull { text.startsWith(it, i) }
            if (token != null) {
                out.append('"').append("\\u0000python-json:").append(token).append('"')
                i += token.length
                replaced = true
                continue
            }
            out.append(c)
            i++
        }
        val parsed = Json.parse(out)
        return if (replaced) restoreNonFinite(parsed) else parsed
    }

    /** True when [a] and [b] hold the same members as multisets, compared with [jsonEquals]. */
    fun sameMembers(a: List<*>, b: List<*>): Boolean {
        if (a.size != b.size) return false
        val left = b.toMutableList()
        for (x in a) {
            val i = left.indexOfFirst { jsonEquals(x, it) }
            if (i < 0) return false
            left.removeAt(i)
        }
        return true
    }

    /**
     * Reads the members of a Python `repr` of a flat or nested list of scalars (`['b', 'a']`, `[30, 1, 20]`,
     * `[None, True, 1.5]`), as a renderer prints a set-operation result; null for anything else (dicts, tuples, …).
     */
    fun pythonListMembers(text: String): List<Any?>? = runCatching {
        val reader = ReprReader(text.trim())
        val value = reader.value()
        reader.skipSpaces()
        if (reader.atEnd() && value is List<*>) value else null
    }.getOrNull()

    private const val SENTINEL = "\u0000python-json:"

    private val NON_FINITE = linkedMapOf(
        "-Infinity" to Double.NEGATIVE_INFINITY,
        "Infinity" to Double.POSITIVE_INFINITY,
        "NaN" to Double.NaN,
    )

    /** The index after the JSON string that starts at [start]. */
    private fun stringEnd(text: String, start: Int): Int {
        var i = start + 1
        while (i < text.length && text[i] != '"') i += if (text[i] == '\\') 2 else 1
        return minOf(i + 1, text.length)
    }

    private fun restoreNonFinite(v: Any?): Any? = when (v) {
        is String -> if (v.startsWith(SENTINEL)) NON_FINITE.getValue(v.removePrefix(SENTINEL)) else v
        is List<*> -> v.map(::restoreNonFinite)
        is Map<*, *> -> v.entries.associate { (k, x) -> k to restoreNonFinite(x) }
        else -> v
    }

    private fun isInteger(v: Any): Boolean = v is Int || v is Long || v is Short || v is Byte || v is BigInteger

    private fun isFloat(v: Any): Boolean = v is Double || v is Float || v is BigDecimal

    private fun integer(v: Any): BigInteger = if (v is BigInteger) v else BigInteger.valueOf((v as Number).toLong())

    /** A reader for the subset of Python `repr` that [pythonListMembers] accepts. */
    private class ReprReader(private val s: String) {
        private var i = 0

        fun atEnd(): Boolean = i >= s.length

        fun skipSpaces() {
            while (i < s.length && s[i] == ' ') i++
        }

        fun value(): Any? {
            skipSpaces()
            require(i < s.length)
            return when (val c = s[i]) {
                '[' -> list()
                '\'', '"' -> string(c)
                else -> scalar()
            }
        }

        private fun list(): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            skipSpaces()
            if (s[i] == ']') {
                i++
                return out
            }
            while (true) {
                out += value()
                skipSpaces()
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> throw IllegalArgumentException("not a list repr")
                }
            }
        }

        private fun string(quote: Char): String {
            i++
            val b = StringBuilder()
            while (s[i] != quote) {
                val c = s[i++]
                if (c != '\\') {
                    b.append(c)
                    continue
                }
                when (val e = s[i++]) {
                    'n' -> b.append('\n')
                    't' -> b.append('\t')
                    'r' -> b.append('\r')
                    'x' -> b.append(s.substring(i, i + 2).toInt(16).toChar()).also { i += 2 }
                    'u' -> b.append(s.substring(i, i + 4).toInt(16).toChar()).also { i += 4 }
                    else -> b.append(e)
                }
            }
            i++
            return b.toString()
        }

        private fun scalar(): Any? {
            val start = i
            while (i < s.length && s[i] != ',' && s[i] != ']' && s[i] != ' ') i++
            return when (val word = s.substring(start, i)) {
                "None" -> null
                "True" -> true
                "False" -> false
                else -> word.toLongOrNull() ?: word.toBigIntegerOrNull() ?: word.toDouble()
            }
        }
    }
}
