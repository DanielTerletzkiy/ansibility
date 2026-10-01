package de.terletzkiy.ansibility.semantics.value

import java.math.BigDecimal
import java.math.BigInteger

/**
 * Python's `repr()` and `str()` for [PyValue], character for character (CPython 3.13/3.14): the canonical printer
 * the golden tables are compared with, and what `check_type_str` produces when it stringifies a value.
 */
object PyRepr {
    /** `repr(value)`. Throws [IndeterminateValueException] for vault, templated and unloadable values. */
    fun repr(value: PyValue): String = when (value) {
        PyValue.None -> "None"
        is PyValue.Bool -> if (value.value) "True" else "False"
        is PyValue.Int -> intText(value.value)
        is PyValue.Float -> floatRepr(value.value)
        is PyValue.Str -> strRepr(value.value)
        is PyValue.Date -> value.timestamp.pyRepr()
        is PyValue.List -> value.items.joinToString(", ", "[", "]") { repr(it) }
        is PyValue.Dict -> value.entries.joinToString(", ", "{", "}") { (k, v) -> repr(k) + ": " + repr(v) }
        PyValue.Vault, PyValue.Unloadable, is PyValue.Templated -> throw IndeterminateValueException(value)
    }

    /** `str(value)`: the text itself for `str`, `isoformat(' ')` for dates, otherwise [repr]. */
    fun str(value: PyValue): String = when (value) {
        is PyValue.Str -> value.value
        is PyValue.Date -> value.timestamp.pyStr()
        else -> repr(value)
    }

    /** A short, never-failing rendering for diagnostics (opaque values get placeholders). */
    fun display(value: PyValue, limit: Int = 60): String {
        val text = when (value) {
            PyValue.Vault -> "<vault>"
            PyValue.Unloadable -> "<unloadable>"
            is PyValue.Templated -> value.text
            else -> try {
                repr(value)
            } catch (e: IndeterminateValueException) {
                "<${value.typeName}>"
            } catch (e: PyException) {
                "<${value.typeName}>"
            }
        }
        return if (text.length <= limit) text else text.take(limit - 1) + "…"
    }

    /** `str(int)`; Python refuses decimal conversions longer than [PyValue.INT_MAX_STR_DIGITS] digits. */
    internal fun intText(value: BigInteger): String {
        val text = value.toString()
        val digits = if (text.startsWith("-")) text.length - 1 else text.length
        if (digits > PyValue.INT_MAX_STR_DIGITS) {
            throw PyException(
                "ValueError",
                "Exceeds the limit (${PyValue.INT_MAX_STR_DIGITS} digits) for integer string conversion; " +
                    "use sys.set_int_max_str_digits() to increase the limit",
            )
        }
        return text
    }

    /**
     * `repr(float)`: the shortest decimal that round-trips (David Gay's mode 0, like CPython), in fixed notation
     * when the decimal exponent is in [-4, 16), else in exponent notation (`1e+16`, `1e-05`).
     */
    fun floatRepr(value: Double): String {
        if (value.isNaN()) return "nan"
        if (value == Double.POSITIVE_INFINITY) return "inf"
        if (value == Double.NEGATIVE_INFINITY) return "-inf"
        if (value == 0.0) return if (1.0 / value < 0) "-0.0" else "0.0"
        val (digits, decpt) = shortestDigits(kotlin.math.abs(value))
        val sign = if (value < 0) "-" else ""
        val body = if (decpt > -4 && decpt <= 16) {
            when {
                decpt <= 0 -> "0." + "0".repeat(-decpt) + digits
                decpt >= digits.length -> digits + "0".repeat(decpt - digits.length) + ".0"
                else -> digits.substring(0, decpt) + "." + digits.substring(decpt)
            }
        } else {
            val mantissa = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
            val exponent = decpt - 1
            val expSign = if (exponent < 0) "-" else "+"
            mantissa + "e" + expSign + kotlin.math.abs(exponent).toString().padStart(2, '0')
        }
        return sign + body
    }

    /**
     * The shortest round-tripping digit string of a positive finite double and its decimal point position
     * (`value = 0.d1d2… × 10^decpt`). Java's `Double.toString` is shortest for two or more digits but may return
     * two digits where one suffices (`4.9E-324` vs Python's `5e-324`), so one-digit candidates are tried first.
     */
    private fun shortestDigits(value: Double): Pair<String, Int> {
        val java = BigDecimal(java.lang.Double.toString(value)).stripTrailingZeros()
        var digits = java.unscaledValue().toString()
        var decpt = digits.length - java.scale()
        if (digits.length == 2) {
            val oneDigit = oneDigitCandidate(value, digits, decpt)
            if (oneDigit != null) {
                digits = oneDigit.first
                decpt = oneDigit.second
            }
        }
        return digits to decpt
    }

    private fun oneDigitCandidate(value: Double, digits: String, decpt: Int): Pair<String, Int>? {
        val exact = BigDecimal(value)
        val low = digits[0].digitToInt()
        val candidates = listOf(low, low + 1).mapNotNull { d ->
            if (d == 0) return@mapNotNull null
            // d × 10^(decpt - 1); a 10 carries into the next decade.
            val (text, point) = if (d == 10) "1" to decpt + 1 else d.toString() to decpt
            val decimal = BigDecimal(BigInteger(text), -(point - 1))
            if (decimal.toDouble() == value) Triple(text, point, decimal) else null
        }
        if (candidates.isEmpty()) return null
        val best = candidates.minWithOrNull(
            compareBy<Triple<String, Int, BigDecimal>> { it.third.subtract(exact).abs() }
                .thenBy { it.first.last().digitToInt() % 2 },
        )!!
        return best.first to best.second
    }

    /**
     * `repr(str)`: single quotes unless the text contains `'` and no `"`; backslash, the quote, `\t`, `\n`, `\r`
     * escaped; other control and non-printable characters as `\xNN`, `\uNNNN` or `\UNNNNNNNN`.
     */
    fun strRepr(text: String): String {
        val quote = if ('\'' in text && '"' !in text) '"' else '\''
        val out = StringBuilder(text.length + 2).append(quote)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == quote.code || cp == '\\'.code -> out.append('\\').appendCodePoint(cp)
                cp == '\t'.code -> out.append("\\t")
                cp == '\n'.code -> out.append("\\n")
                cp == '\r'.code -> out.append("\\r")
                cp < 0x20 || cp == 0x7F -> out.append("\\x").append(hex(cp, 2))
                cp < 0x7F -> out.appendCodePoint(cp)
                isPrintable(cp) -> out.appendCodePoint(cp)
                cp <= 0xFF -> out.append("\\x").append(hex(cp, 2))
                cp <= 0xFFFF -> out.append("\\u").append(hex(cp, 4))
                else -> out.append("\\U").append(hex(cp, 8))
            }
        }
        return out.append(quote).toString()
    }

    private fun hex(value: Int, width: Int): String = Integer.toHexString(value).padStart(width, '0')

    /** CPython's `Py_UNICODE_ISPRINTABLE`: not in categories Cc, Cf, Cs, Co, Cn, Zl, Zp, Zs (space excepted). */
    internal fun isPrintable(cp: Int): Boolean {
        if (cp == ' '.code) return true
        return when (Character.getType(cp).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
            Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SPACE_SEPARATOR,
            -> false
            else -> true
        }
    }
}

/** A Python exception raised by the ported code; [pyClass] is the Python class name (`TypeError`, `OverflowError` …). */
internal class PyException(val pyClass: String, message: String) : RuntimeException(message) {
    companion object {
        fun typeError(message: String) = PyException("TypeError", message)

        fun valueError(message: String) = PyException("ValueError", message)
    }
}

/**
 * Thrown when a result depends on something this layer cannot see: vault content, a template result, an
 * unloadable file, or a Python value outside [PyValue] (a tuple, set, bytes or complex from `literal_eval`).
 */
internal class IndeterminateValueException(val reason: String) : RuntimeException(reason) {
    constructor(value: PyValue) : this("depends on the content of a ${describeOpaque(value)}")

    companion object {
        fun describeOpaque(value: PyValue): String = when (value) {
            PyValue.Vault -> "vault-encrypted value"
            is PyValue.Templated -> "templated value"
            PyValue.Unloadable -> "value PyYAML cannot load"
            else -> value.typeName
        }
    }
}
