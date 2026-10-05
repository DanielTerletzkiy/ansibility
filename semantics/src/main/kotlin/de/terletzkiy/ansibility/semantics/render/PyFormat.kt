package de.terletzkiy.ansibility.semantics.render

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * Python string formatting for the `%` operator, `str.format` and the `format` filter: conversions `s r d i f F e x X
 * o %`, flags `- + 0 space #`, width and precision, and the format-spec mini-language with fill, align, sign, `,` and
 * `_`. Anything else raises the error Python raises, or is reported as not emulated.
 */
internal object PyFormat {
    private val PERCENT = Regex("""%(?:\(([^)]*)\))?([-+ 0#]*)(\*|\d+)?(?:\.(\*|\d+))?[hlL]?([diouxXeEfFgGcrsa%])""")

    fun percent(format: String, args: RValue, tuples: Boolean): String {
        RValues.requireKnown(args)
        val positional = when (args) {
            is RValue.Tuple -> args.items
            else -> listOf(args)
        }
        val mapping = args as? RValue.Dict
        var index = 0
        val out = StringBuilder()
        var last = 0
        for (m in PERCENT.findAll(format)) {
            out.append(format, last, m.range.first)
            last = m.range.last + 1
            val conversion = m.groupValues[5][0]
            if (conversion == '%') {
                out.append('%')
                continue
            }
            val key = m.groups[1]?.value
            val value = if (key != null) {
                mapping?.get(key) ?: throw RenderError(if (mapping == null) "format requires a mapping" else RValues.repr(RValue.Str(key), tuples))
            } else {
                positional.getOrNull(index++) ?: throw RenderError("not enough arguments for format string")
            }
            val flags = m.groupValues[2]
            val width = m.groupValues[3].toIntOrNull()
            val precision = m.groupValues[4].toIntOrNull()
            out.append(convert(value, conversion, flags, width, precision, tuples))
        }
        out.append(format.substring(last))
        if (mapping == null && index < positional.size) throw RenderError("not all arguments converted during string formatting")
        return out.toString()
    }

    private fun convert(value: RValue, conversion: Char, flags: String, width: Int?, precision: Int?, tuples: Boolean): String {
        val body = when (conversion) {
            's' -> RValues.str(value, tuples).let { if (precision != null) it.take(precision) else it }
            'r', 'a' -> RValues.repr(value, tuples)
            'd', 'i', 'u' -> integerOf(value, "%$conversion").toString().let { sign(it, flags) }
            'x' -> (if ('#' in flags) "0x" else "") + integerOf(value, "%x").toString(16)
            'X' -> (if ('#' in flags) "0X" else "") + integerOf(value, "%X").toString(16).uppercase()
            'o' -> (if ('#' in flags) "0o" else "") + integerOf(value, "%o").toString(8)
            'f', 'F' -> sign(fixed(floatOf(value, "%f"), precision ?: 6), flags)
            'e', 'E' -> sign(exponent(floatOf(value, "%e"), precision ?: 6).let { if (conversion == 'E') it.uppercase() else it }, flags)
            'g', 'G' -> sign(general(floatOf(value, "%g"), precision ?: 6), flags)
            'c' -> when (value) {
                is RValue.Int -> String(Character.toChars(value.value.toInt()))
                is RValue.Str -> value.value
                else -> throw RenderError("%c requires int or char")
            }
            else -> throw RenderError("unsupported format character '$conversion'")
        }
        if (width == null || body.length >= width) return body
        return when {
            '-' in flags -> body.padEnd(width)
            '0' in flags && conversion !in "sr" -> {
                val signed = body.startsWith("-") || body.startsWith("+") || body.startsWith(" ")
                if (signed) body[0] + body.substring(1).padStart(width - 1, '0') else body.padStart(width, '0')
            }
            else -> body.padStart(width)
        }
    }

    private fun sign(text: String, flags: String): String = when {
        text.startsWith("-") -> text
        '+' in flags -> "+$text"
        ' ' in flags -> " $text"
        else -> text
    }

    private fun integerOf(value: RValue, spec: String): BigInteger = when (value) {
        is RValue.Bool -> if (value.value) BigInteger.ONE else BigInteger.ZERO
        is RValue.Int -> value.value
        is RValue.Float -> BigDecimal(value.value).toBigInteger()
        else -> throw RenderError("$spec format: a real number is required, not ${value.typeName}")
    }

    private fun floatOf(value: RValue, spec: String): Double = when (value) {
        is RValue.Bool -> if (value.value) 1.0 else 0.0
        is RValue.Int -> value.value.toDouble()
        is RValue.Float -> value.value
        else -> throw RenderError("must be real number, not ${value.typeName}")
    }

    fun fixed(value: Double, precision: Int): String {
        if (value.isNaN()) return "nan"
        if (value.isInfinite()) return if (value > 0) "inf" else "-inf"
        return BigDecimal(value).setScale(precision, RoundingMode.HALF_EVEN).toPlainString()
    }

    private fun exponent(value: Double, precision: Int): String {
        if (value.isNaN()) return "nan"
        if (value.isInfinite()) return if (value > 0) "inf" else "-inf"
        val text = String.format(java.util.Locale.ROOT, "%.${precision}e", value)
        // Java prints e+05 like Python; make sure the exponent has at least two digits
        return text.replace(Regex("e([+-])(\\d)$"), "e$10$2")
    }

    private fun general(value: Double, precision: Int): String {
        if (value == 0.0) return "0"
        val p = if (precision == 0) 1 else precision
        val exp = Math.floor(Math.log10(Math.abs(value))).toInt()
        return if (exp < -4 || exp >= p) {
            exponent(value, p - 1).replace(Regex("\\.?0+e"), "e")
        } else {
            fixed(value, maxOf(0, p - 1 - exp)).let { if ('.' in it) it.trimEnd('0').trimEnd('.') else it }
        }
    }

    /** `str.format(*args, **kwargs)`. */
    fun format(format: String, args: List<RValue>, kwargs: Map<String, RValue>, tuples: Boolean): String {
        RValues.requireKnown(*(args + kwargs.values).toTypedArray())
        val out = StringBuilder()
        var auto = 0
        var i = 0
        while (i < format.length) {
            val c = format[i]
            if (c == '{') {
                if (format.getOrNull(i + 1) == '{') {
                    out.append('{')
                    i += 2
                    continue
                }
                val close = format.indexOf('}', i)
                if (close < 0) throw RenderError("Single '{' encountered in format string")
                val field = format.substring(i + 1, close)
                val colon = field.indexOf(':')
                val ref = if (colon < 0) field else field.substring(0, colon)
                val spec = if (colon < 0) "" else field.substring(colon + 1)
                val (name, conversion) = ref.split('!', limit = 2).let { it[0] to it.getOrNull(1) }
                val base = name.takeWhile { it != '.' && it != '[' }
                var value = when {
                    base.isEmpty() -> args.getOrNull(auto++) ?: throw RenderError("Replacement index ${auto - 1} out of range for positional args tuple")
                    base.all { it.isDigit() } -> args.getOrNull(base.toInt()) ?: throw RenderError("Replacement index $base out of range for positional args tuple")
                    else -> kwargs[base] ?: throw RenderError(RValues.repr(RValue.Str(base), tuples))
                }
                for (part in Regex("""\.(\w+)|\[([^]]+)]""").findAll(name.substring(base.length))) {
                    value = part.groups[1]?.let { Access.attribute(value, it.value, RenderOptions(RenderMode.TEMPLATE_FILE)) }
                        ?: (value as? RValue.Dict)?.get(part.groupValues[2])
                        ?: RValues.sequence(value)?.getOrNull(part.groupValues[2].toIntOrNull() ?: -1)
                        ?: throw RenderError(part.value)
                }
                if (value is RValue.Undefined) throw RenderError(value.message)
                val converted = when (conversion) {
                    "r", "a" -> RValue.Str(RValues.repr(value, tuples))
                    "s" -> RValue.Str(RValues.str(value, tuples))
                    else -> value
                }
                out.append(spec(converted, spec, tuples))
                i = close + 1
                continue
            }
            if (c == '}') {
                if (format.getOrNull(i + 1) == '}') {
                    out.append('}')
                    i += 2
                    continue
                }
                throw RenderError("Single '}' encountered in format string")
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private val SPEC = Regex("""^(?:(.)?([<>=^]))?([-+ ])?(#)?(0)?(\d+)?([,_])?(?:\.(\d+))?([bcdeEfFgGnosxX%])?$""")

    /** `format(value, spec)`. */
    fun spec(value: RValue, spec: String, tuples: Boolean): String {
        if (spec.isEmpty()) return RValues.str(value, tuples)
        val m = SPEC.matchEntire(spec) ?: throw RenderError("Invalid format specifier '$spec'")
        val fill = m.groups[1]?.value?.first() ?: if (m.groups[5] != null) '0' else ' '
        val align = m.groups[2]?.value?.first()
        val signFlag = m.groups[3]?.value ?: ""
        val width = m.groups[6]?.value?.toInt()
        val grouping = m.groups[7]?.value
        val precision = m.groups[8]?.value?.toInt()
        val type = m.groups[9]?.value?.first()
        val numeric = value is RValue.Int || value is RValue.Float || value is RValue.Bool
        var body = when {
            type == null && !numeric -> RValues.str(value, tuples).let { if (precision != null) it.take(precision) else it }
            type == 's' -> RValues.str(value, tuples).let { if (precision != null) it.take(precision) else it }
            type == null && value is RValue.Float -> if (precision != null) general(value.value, precision) else RValues.str(value, tuples)
            type == null || type == 'd' || type == 'n' -> integerOf(value, "{:d}").let { group(it.toString(), grouping) }
            type == 'x' -> integerOf(value, "{:x}").toString(16)
            type == 'X' -> integerOf(value, "{:X}").toString(16).uppercase()
            type == 'o' -> integerOf(value, "{:o}").toString(8)
            type == 'b' -> integerOf(value, "{:b}").toString(2)
            type == 'f' || type == 'F' -> fixed(floatOf(value, "{:f}"), precision ?: 6).let { groupFloat(it, grouping) }
            type == 'e' || type == 'E' -> exponent(floatOf(value, "{:e}"), precision ?: 6).let { if (type == 'E') it.uppercase() else it }
            type == 'g' || type == 'G' -> general(floatOf(value, "{:g}"), precision ?: 6)
            type == '%' -> fixed(floatOf(value, "{:%}") * 100, precision ?: 6) + "%"
            type == 'c' -> String(Character.toChars(integerOf(value, "{:c}").toInt()))
            else -> throw RenderError("Unknown format code '$type' for object of type '${value.typeName}'")
        }
        if (numeric && type != 'c') body = sign(body, signFlag)
        if (width == null || body.length >= width) return body
        val pad = width - body.length
        return when (align ?: if (numeric) '>' else '<') {
            '<' -> body + fill.toString().repeat(pad)
            '^' -> fill.toString().repeat(pad / 2) + body + fill.toString().repeat(pad - pad / 2)
            '=' -> if (body.startsWith("-") || body.startsWith("+")) body[0] + fill.toString().repeat(pad) + body.substring(1) else fill.toString().repeat(pad) + body
            else -> fill.toString().repeat(pad) + body
        }
    }

    private fun group(digits: String, separator: String?): String {
        if (separator == null) return digits
        val negative = digits.startsWith("-")
        val raw = digits.removePrefix("-")
        val grouped = raw.reversed().chunked(3).joinToString(separator).reversed()
        return if (negative) "-$grouped" else grouped
    }

    private fun groupFloat(text: String, separator: String?): String {
        if (separator == null) return text
        val dot = text.indexOf('.')
        return if (dot < 0) group(text, separator) else group(text.substring(0, dot), separator) + text.substring(dot)
    }
}
