package de.terletzkiy.ansibility.semantics.coerce

import de.terletzkiy.ansibility.semantics.value.PyException
import de.terletzkiy.ansibility.semantics.value.PyRepr
import de.terletzkiy.ansibility.semantics.value.PyValue
import java.math.BigDecimal
import java.math.BigInteger

/** CPython text and number primitives the checks rely on (`str.strip`, `decimal.Decimal`, `float`). */
internal object PyNumbers {
    /** The largest integer (in decimal digits) the port materialises; beyond it the check reports a crash. */
    const val MAX_INT_DIGITS: Int = 1_000_000

    /** CPython's `Py_UNICODE_ISSPACE` (`str.isspace`, `str.strip()`, the `\s` of `re` on str). */
    fun isSpace(cp: Int): Boolean = when (cp) {
        in 0x09..0x0D, in 0x1C..0x20, 0x85, 0xA0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
        else -> false
    }

    /** `str.strip()` without arguments. */
    fun strip(text: String): String {
        var start = 0
        var end = text.length
        while (start < end && isSpace(text.codePointAt(start))) start += Character.charCount(text.codePointAt(start))
        while (end > start && isSpace(text.codePointBefore(end))) end -= Character.charCount(text.codePointBefore(end))
        return text.substring(start, end)
    }

    /** `Py_UNICODE_TODECIMAL`: the digit value of a Unicode decimal digit (category Nd), else -1. */
    private fun toDecimal(cp: Int): Int =
        if (Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER.toInt()) Character.digit(cp, 10) else -1

    /** A `decimal.Decimal` value. */
    sealed interface PyDecimal {
        data class Finite(val value: BigDecimal) : PyDecimal
        data class Infinite(val negative: Boolean) : PyDecimal
        data object NaN : PyDecimal
    }

    /**
     * `decimal.Decimal(value)` for the values `check_type_int` may pass (everything except int and bool).
     * Raises `TypeError` for unsupported types, `ValueError` for a malformed tuple form and
     * `InvalidOperation` for a malformed string.
     */
    fun decimal(value: PyValue): PyDecimal = when (value) {
        is PyValue.Str -> decimalFromString(value.value)
        is PyValue.Float -> when {
            value.value.isNaN() -> PyDecimal.NaN
            value.value.isInfinite() -> PyDecimal.Infinite(value.value < 0)
            else -> PyDecimal.Finite(BigDecimal(value.value))
        }
        is PyValue.List -> decimalFromTuple(value.items)
        is PyValue.Int -> PyDecimal.Finite(BigDecimal(value.value))
        is PyValue.Bool -> PyDecimal.Finite(if (value.value) BigDecimal.ONE else BigDecimal.ZERO)
        else -> throw PyException.typeError("conversion from ${value.typeName} to Decimal is not supported")
    }

    private val DECIMAL_SYNTAX = Regex(
        "^([+-]?)(?:(inf|infinity)|(s?nan)([0-9]*)|([0-9]+)(?:\\.([0-9]*))?(?:e([+-]?[0-9]+))?|\\.([0-9]+)(?:e([+-]?[0-9]+))?)$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The C `decimal` module's string conversion: surrounding Unicode whitespace is stripped, `_` is ignored
     * anywhere, other Unicode whitespace becomes a space (and so fails), Unicode digits become ASCII digits.
     */
    private fun decimalFromString(text: String): PyDecimal {
        val stripped = strip(text)
        val ascii = StringBuilder(stripped.length)
        var i = 0
        while (i < stripped.length) {
            val cp = stripped.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == '_'.code -> Unit
                cp in 1..127 -> ascii.appendCodePoint(cp)
                isSpace(cp) -> ascii.append(' ')
                toDecimal(cp) >= 0 -> ascii.append('0' + toDecimal(cp))
                else -> throw invalidOperation()
            }
        }
        val match = DECIMAL_SYNTAX.matchEntire(ascii) ?: throw invalidOperation()
        val g = match.groupValues
        val negative = g[1] == "-"
        return when {
            g[2].isNotEmpty() -> PyDecimal.Infinite(negative)
            g[3].isNotEmpty() -> PyDecimal.NaN
            g[5].isNotEmpty() -> finite(negative, g[5], g[6], g[7])
            else -> finite(negative, "", g[8], g[9])
        }
    }

    private fun finite(negative: Boolean, intPart: String, fraction: String, exponent: String): PyDecimal {
        val coefficient = BigInteger(intPart + fraction)
        if (coefficient.signum() == 0) return PyDecimal.Finite(BigDecimal.ZERO)
        val exp = if (exponent.isEmpty()) BigInteger.ZERO else BigInteger(exponent)
        val scale = BigInteger.valueOf(fraction.length.toLong()) - exp
        if (scale.bitLength() > 31) {
            // Far outside what an int check can materialise: a huge integer or a tiny non-integral fraction.
            return if (scale.signum() < 0) PyDecimal.Finite(BigDecimal(coefficient, Int.MIN_VALUE + 1)) else
                PyDecimal.Finite(BigDecimal(coefficient, Int.MAX_VALUE))
        }
        val value = BigDecimal(coefficient, scale.toInt())
        return PyDecimal.Finite(if (negative) value.negate() else value)
    }

    /** `Decimal((sign, digits, exponent))`, which also accepts lists; checks in CPython's order. */
    private fun decimalFromTuple(items: List<PyValue>): PyDecimal {
        if (items.size != 3) throw PyException.valueError("argument must be a sequence of length 3")
        val sign = integerOf(items[0])
        if (sign == null || (sign != BigInteger.ZERO && sign != BigInteger.ONE)) {
            throw PyException.valueError("sign must be an integer with the value 0 or 1")
        }
        val third = items[2]
        var special: PyDecimal? = null
        var exponent = BigInteger.ZERO
        if (third is PyValue.Str) {
            special = when (third.value) {
                "F" -> PyDecimal.Infinite(sign == BigInteger.ONE)
                "n", "N" -> PyDecimal.NaN
                else -> throw PyException.valueError("string argument in the third position must be 'F', 'n' or 'N'")
            }
        } else {
            exponent = integerOf(third) ?: throw PyException.valueError("exponent must be an integer")
            if (exponent.bitLength() > 63) throw PyException("OverflowError", "Python int too large to convert to C ssize_t")
        }
        val digits = (items[1] as? PyValue.List)?.items
            ?: throw PyException.valueError("coefficient must be a tuple of digits")
        val text = StringBuilder()
        for (digit in digits) {
            val d = integerOf(digit)
            if (d == null || d.signum() < 0 || d > BigInteger.valueOf(9)) {
                throw PyException.valueError("coefficient must be a tuple of digits")
            }
            text.append(d.toInt())
        }
        if (special != null) return special
        val coefficient = if (text.isEmpty()) BigInteger.ZERO else BigInteger(text.toString())
        if (coefficient.signum() == 0) return PyDecimal.Finite(BigDecimal.ZERO)
        if (exponent.negate().bitLength() > 31) {
            return PyDecimal.Finite(BigDecimal(coefficient, if (exponent.signum() > 0) Int.MIN_VALUE + 1 else Int.MAX_VALUE))
        }
        val value = BigDecimal(coefficient, exponent.negate().toInt())
        return PyDecimal.Finite(if (sign == BigInteger.ONE) value.negate() else value)
    }

    /** The integer value of a Python int (bool included, as `PyLong_Check` sees it), else null. */
    private fun integerOf(value: PyValue): BigInteger? = when (value) {
        is PyValue.Int -> value.value
        is PyValue.Bool -> if (value.value) BigInteger.ONE else BigInteger.ZERO
        else -> null
    }

    private fun invalidOperation() = PyException("InvalidOperation", "[<class 'decimal.ConversionSyntax'>]")

    /**
     * `int(Decimal)` when it equals the Decimal (`check_type_int`'s test), or null when a fractional part remains.
     * Infinity raises `OverflowError`, NaN raises `ValueError`, as CPython does.
     */
    fun integralValue(decimal: PyDecimal): BigInteger? = when (decimal) {
        is PyDecimal.Infinite -> throw PyException("OverflowError", "cannot convert Infinity to integer")
        PyDecimal.NaN -> throw PyException.valueError("cannot convert NaN to integer")
        is PyDecimal.Finite -> {
            val value = decimal.value
            if (value.signum() == 0) {
                BigInteger.ZERO
            } else if (value.scale() > 0 && value.stripTrailingZeros().scale() > 0) {
                null
            } else {
                if (value.precision().toLong() - value.scale().toLong() > MAX_INT_DIGITS) {
                    throw PyException("MemoryError", "integer too large to materialise")
                }
                value.toBigIntegerExact()
            }
        }
    }

    private val FLOAT_SYNTAX = Regex("^[+-]?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?$")
    private val FLOAT_SPECIAL = Regex("^([+-]?)(inf|infinity|nan)$", RegexOption.IGNORE_CASE)

    /**
     * `float(text)`: Unicode digits and whitespace are normalised first, `_` is allowed only between digits,
     * surrounding ASCII whitespace is stripped, then CPython's decimal/`inf`/`nan` grammar applies.
     * Raises `ValueError` when the text is not a float literal.
     */
    fun floatFromString(text: String): Double {
        val ascii = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp < 127 -> ascii.appendCodePoint(cp)
                isSpace(cp) -> ascii.append(' ')
                toDecimal(cp) >= 0 -> ascii.append('0' + toDecimal(cp))
                else -> ascii.append('?')
            }
        }
        val fail = { PyException.valueError("could not convert string to float: ${PyRepr.strRepr(text)}") }
        val withoutUnderscores = if ('_' in ascii) removeUnderscores(ascii.toString()) ?: throw fail() else ascii.toString()
        val stripped = withoutUnderscores.trim { it == ' ' || it in '\t'..'\r' }
        FLOAT_SPECIAL.matchEntire(stripped)?.let { m ->
            val negative = m.groupValues[1] == "-"
            return if (m.groupValues[2].equals("nan", ignoreCase = true)) Double.NaN else if (negative) {
                Double.NEGATIVE_INFINITY
            } else {
                Double.POSITIVE_INFINITY
            }
        }
        if (!FLOAT_SYNTAX.matches(stripped)) throw fail()
        return stripped.toDouble()
    }

    /** `_Py_string_to_number_with_underscores`: each `_` must sit between two ASCII digits. */
    private fun removeUnderscores(text: String): String? {
        val out = StringBuilder(text.length)
        var prev = '\u0000'
        for (c in text) {
            if (c == '_') {
                if (prev !in '0'..'9') return null
            } else {
                out.append(c)
                if (prev == '_' && c !in '0'..'9') return null
            }
            prev = c
        }
        return if (prev == '_') null else out.toString()
    }

    /** `float(int)`: correctly rounded; too large for a double raises `OverflowError`. */
    fun floatFromInt(value: BigInteger): Double {
        val result = value.toDouble()
        if (result.isInfinite()) throw PyException("OverflowError", "int too large to convert to float")
        return result
    }

    /** `round(x)` for a float: half to even, as an int; infinity raises `OverflowError`, NaN `ValueError`. */
    fun roundHalfEven(value: Double): BigInteger {
        if (value.isNaN()) throw PyException.valueError("cannot convert float NaN to integer")
        if (value.isInfinite()) throw PyException("OverflowError", "cannot convert float infinity to integer")
        return BigDecimal(value).setScale(0, java.math.RoundingMode.HALF_EVEN).toBigIntegerExact()
    }
}
