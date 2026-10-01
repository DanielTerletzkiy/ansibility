package de.terletzkiy.ansibility.semantics.coerce

import de.terletzkiy.ansibility.semantics.value.IndeterminateValueException
import de.terletzkiy.ansibility.semantics.value.PyException
import de.terletzkiy.ansibility.semantics.value.PyRepr
import de.terletzkiy.ansibility.semantics.value.PyValue
import java.math.BigInteger

/** Python's `json.loads` (strict, `NaN`/`Infinity` allowed) and `json.dumps` with default settings. */
internal object PyJson {
    /** `json.loads(text)`; raises `ValueError` (`JSONDecodeError`) on malformed input. */
    fun loads(text: String): PyValue = Decoder(text).document()

    private class Decoder(private val s: String) {
        private var i = 0

        fun document(): PyValue {
            skipWhitespace()
            val value = value()
            skipWhitespace()
            if (i != s.length) throw error("Extra data")
            return value
        }

        private fun error(message: String) = PyException("JSONDecodeError", "$message: char $i")

        private fun skipWhitespace() {
            while (i < s.length && s[i] in " \t\n\r") i++
        }

        private fun value(): PyValue {
            if (i >= s.length) throw error("Expecting value")
            return when (s[i]) {
                '"' -> PyValue.Str(string())
                '{' -> obj()
                '[' -> array()
                'n' -> literal("null", PyValue.None)
                't' -> literal("true", PyValue.Bool(true))
                'f' -> literal("false", PyValue.Bool(false))
                'N' -> literal("NaN", PyValue.Float(Double.NaN))
                'I' -> literal("Infinity", PyValue.Float(Double.POSITIVE_INFINITY))
                '-' -> if (s.startsWith("-Infinity", i)) literal("-Infinity", PyValue.Float(Double.NEGATIVE_INFINITY)) else number()
                else -> number()
            }
        }

        private fun literal(word: String, value: PyValue): PyValue {
            if (!s.startsWith(word, i)) throw error("Expecting value")
            i += word.length
            return value
        }

        private val numberPattern = Regex("(-?(?:0|[1-9][0-9]*))(\\.[0-9]+)?([eE][-+]?[0-9]+)?")

        private fun number(): PyValue {
            val match = numberPattern.matchAt(s, i) ?: throw error("Expecting value")
            i = match.range.last + 1
            val (integer, fraction, exponent) = match.destructured
            if (fraction.isEmpty() && exponent.isEmpty()) {
                val digits = integer.removePrefix("-").length
                if (digits > PyValue.INT_MAX_STR_DIGITS) throw PyException.valueError("Exceeds the limit for integer string conversion")
                return PyValue.Int(BigInteger(integer))
            }
            return PyValue.Float((integer + fraction + exponent).toDouble())
        }

        private fun obj(): PyValue {
            i++ // {
            val pairs = mutableListOf<Pair<PyValue, PyValue>>()
            skipWhitespace()
            if (i < s.length && s[i] == '}') {
                i++
                return PyValue.Dict.EMPTY
            }
            while (true) {
                if (i >= s.length || s[i] != '"') throw error("Expecting property name enclosed in double quotes")
                val key = string()
                skipWhitespace()
                if (i >= s.length || s[i] != ':') throw error("Expecting ':' delimiter")
                i++
                skipWhitespace()
                pairs += PyValue.Str(key) to value()
                skipWhitespace()
                if (i >= s.length) throw error("Expecting ',' delimiter")
                when (s[i++]) {
                    '}' -> return PyValue.Dict.of(pairs)
                    ',' -> skipWhitespace()
                    else -> throw error("Expecting ',' delimiter")
                }
            }
        }

        private fun array(): PyValue {
            i++ // [
            val items = mutableListOf<PyValue>()
            skipWhitespace()
            if (i < s.length && s[i] == ']') {
                i++
                return PyValue.List(items)
            }
            while (true) {
                items += value()
                skipWhitespace()
                if (i >= s.length) throw error("Expecting ',' delimiter")
                when (s[i++]) {
                    ']' -> return PyValue.List(items)
                    ',' -> skipWhitespace()
                    else -> throw error("Expecting ',' delimiter")
                }
            }
        }

        /** A JSON string starting at the opening quote (strict: no raw control characters). */
        private fun string(): String {
            i++ // "
            val out = StringBuilder()
            while (true) {
                if (i >= s.length) throw error("Unterminated string starting at")
                val c = s[i++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (i >= s.length) throw error("Unterminated string starting at")
                        when (val e = s[i++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                var cp = hex4()
                                if (cp in 0xD800..0xDBFF && s.startsWith("\\u", i)) {
                                    val save = i
                                    i += 2
                                    val low = hex4()
                                    if (low in 0xDC00..0xDFFF) cp = 0x10000 + ((cp - 0xD800) shl 10) + (low - 0xDC00) else i = save
                                }
                                out.appendCodePoint(cp)
                            }
                            else -> throw error("Invalid \\escape: ${PyRepr.strRepr(e.toString())}")
                        }
                    }
                    c.code < 0x20 -> throw error("Invalid control character at")
                    else -> out.append(c)
                }
            }
        }

        private fun hex4(): Int {
            if (i + 4 > s.length) throw error("Invalid \\uXXXX escape")
            val text = s.substring(i, i + 4)
            if (!text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) throw error("Invalid \\uXXXX escape")
            i += 4
            return text.toInt(16)
        }
    }

    /**
     * `json.dumps(value)` with the default separators, `ensure_ascii=True` and `allow_nan=True`.
     * [encodeDate] handles `datetime.date`/`datetime` values (the `default=` hook); it raises `TypeError` when
     * the encoder in use cannot serialise them.
     */
    fun dumps(value: PyValue, encodeDate: (PyValue.Date) -> String): String {
        val out = StringBuilder()
        encode(value, out, encodeDate)
        return out.toString()
    }

    private fun encode(value: PyValue, out: StringBuilder, encodeDate: (PyValue.Date) -> String) {
        when (value) {
            PyValue.None -> out.append("null")
            is PyValue.Bool -> out.append(if (value.value) "true" else "false")
            is PyValue.Int -> out.append(PyRepr.intText(value.value))
            is PyValue.Float -> out.append(floatText(value.value))
            is PyValue.Str -> quote(value.value, out)
            is PyValue.List -> {
                out.append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) out.append(", ")
                    encode(item, out, encodeDate)
                }
                out.append(']')
            }
            is PyValue.Dict -> {
                out.append('{')
                value.entries.forEachIndexed { index, (key, item) ->
                    if (index > 0) out.append(", ")
                    quote(keyText(key), out)
                    out.append(": ")
                    encode(item, out, encodeDate)
                }
                out.append('}')
            }
            is PyValue.Date -> quote(encodeDate(value), out)
            PyValue.Vault, PyValue.Unloadable, is PyValue.Templated -> throw IndeterminateValueException(value)
        }
    }

    /** JSON object keys: str as-is, float/int/bool/None converted, anything else is a `TypeError`. */
    private fun keyText(key: PyValue): String = when (key) {
        is PyValue.Str -> key.value
        is PyValue.Float -> floatText(key.value)
        is PyValue.Bool -> if (key.value) "true" else "false"
        is PyValue.Int -> PyRepr.intText(key.value)
        PyValue.None -> "null"
        PyValue.Vault, is PyValue.Templated -> throw IndeterminateValueException(key)
        else -> throw PyException.typeError("keys must be str, int, float, bool or None, not ${key.typeName}")
    }

    private fun floatText(value: Double): String = when {
        value.isNaN() -> "NaN"
        value == Double.POSITIVE_INFINITY -> "Infinity"
        value == Double.NEGATIVE_INFINITY -> "-Infinity"
        else -> PyRepr.floatRepr(value)
    }

    /** `py_encode_basestring_ascii`: everything outside printable ASCII becomes `\uXXXX` (surrogate pairs above BMP). */
    private fun quote(text: String, out: StringBuilder) {
        out.append('"')
        for (c in text) {
            when (c) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                in ' '..'~' -> out.append(c)
                else -> out.append("\\u").append(Integer.toHexString(c.code).padStart(4, '0'))
            }
        }
        out.append('"')
    }
}
