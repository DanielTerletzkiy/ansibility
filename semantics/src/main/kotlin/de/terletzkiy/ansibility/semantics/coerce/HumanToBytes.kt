package de.terletzkiy.ansibility.semantics.coerce

import de.terletzkiy.ansibility.semantics.value.PyException
import de.terletzkiy.ansibility.semantics.value.PyRepr
import de.terletzkiy.ansibility.semantics.value.PyValue
import java.math.BigInteger

/** Port of `module_utils/common/text/formatters.py` `human_to_bytes`, used by the `bytes` and `bits` types. */
object HumanToBytes {
    /** `SIZE_RANGES` as the doubles Python multiplies with (`num * (1 << 80)` converts the int exactly). */
    private val SIZE_RANGES: Map<Char, Double> = listOf('B', 'K', 'M', 'G', 'T', 'P', 'E', 'Z', 'Y')
        .withIndex().associate { (index, key) -> key to Math.scalb(1.0, 10 * index) }

    /** `VALID_UNITS[range_key]`: ((byte name, byte symbol), (bit name, bit symbol)). */
    private val VALID_UNITS: Map<Char, Pair<Pair<String, String>, Pair<String, String>>> = mapOf(
        'B' to (("byte" to "B") to ("bit" to "b")),
        'K' to (("kilobyte" to "KB") to ("kilobit" to "Kb")),
        'M' to (("megabyte" to "MB") to ("megabit" to "Mb")),
        'G' to (("gigabyte" to "GB") to ("gigabit" to "Gb")),
        'T' to (("terabyte" to "TB") to ("terabit" to "Tb")),
        'P' to (("petabyte" to "PB") to ("petabit" to "Pb")),
        'E' to (("exabyte" to "EB") to ("exabit" to "Eb")),
        'Z' to (("zetabyte" to "ZB") to ("zetabit" to "Zb")),
        'Y' to (("yottabyte" to "YB") to ("yottabit" to "Yb")),
    )

    /** CPython `re`'s `\s` for str patterns is `str.isspace`; spelled out for a Java character class. */
    private const val PY_SPACE = "\\x09-\\x0D\\x1C-\\x20\\x85\\xA0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000"

    /** `^([0-9]*\.?[0-9]+)(?:\s*([A-Za-z]+))?\s*$`; `$` also matches before a final `\n`, as in Python. */
    private val PATTERN = Regex(
        "^([0-9]*\\.?[0-9]+)(?:[$PY_SPACE]*([A-Za-z]+))?[$PY_SPACE]*$",
        setOf(RegexOption.UNIX_LINES),
    )

    /**
     * `human_to_bytes(number, default_unit, isbits)`: `'1K'` → 1024, `'1.5G'`, `'10'`, `1024`; bit suffixes
     * (`'1Mb'`) only with [isBits]. Rejections are `ValueError`s; an infinite size crashes with `OverflowError`
     * (Python's `int(round(inf))`).
     */
    fun convert(number: PyValue, defaultUnit: String? = null, isBits: Boolean = false): CheckResult =
        CheckResult.of(setOf("ValueError")) { PyValue.Int(parse(number, defaultUnit, isBits)) }

    internal fun parse(number: PyValue, defaultUnit: String?, isBits: Boolean): BigInteger {
        val text = PyRepr.str(number)
        val match = PATTERN.find(text) ?: throw PyException.valueError("human_to_bytes() can't interpret following string: $text")
        val num = match.groupValues[1].toDouble()
        val unit = match.groups[2]?.value ?: defaultUnit ?: return PyNumbers.roundHalfEven(num)
        if (unit.isEmpty()) throw PyException("IndexError", "string index out of range")
        val rangeKey = unit.substring(0, Character.charCount(unit.codePointAt(0))).uppercase()
        val limit = rangeKey.singleOrNull()?.let { SIZE_RANGES[it] } ?: throw PyException.valueError(
            "human_to_bytes() failed to convert $text (unit = $unit). The suffix must be one of Y, Z, E, P, T, G, M, K, B",
        )
        if (unit.length > 1) {
            val group = VALID_UNITS.getValue(rangeKey.single())
            val (name, symbol) = if (isBits) group.second else group.first
            if (unit.lowercase() != name && unit != symbol) {
                val unitClass = if (isBits) "b" else "B"
                val expect = if (rangeKey == "B") "expect $unitClass or ${if (isBits) "bit" else "byte"}" else
                    "expect $rangeKey$unitClass or $rangeKey"
                throw PyException.valueError("human_to_bytes() failed to convert $text. Value is not a valid string ($expect)")
            }
        }
        return PyNumbers.roundHalfEven(num * limit)
    }
}
