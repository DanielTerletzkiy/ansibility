package de.terletzkiy.ansibility.semantics.json

import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.semantics.yaml.YEmpty
import de.terletzkiy.ansibility.semantics.yaml.YEntry
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.semantics.yaml.YVault
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Conversions between the YAML value model ([YValue]) and plain JSON values (see [Json]).
 *
 * [toJson] produces what ansible-core's JSON encoder would print for the loaded YAML value (so `ansible-doc -j`
 * output can be compared with a parsed spec); [fromJson] builds a [YValue] whose scalars resolve back to the
 * same Python values (strings are quoted, so `"yes"` stays a string).
 */
object YValueJson {
    /**
     * The JSON value of [value] after YAML 1.1 implicit typing:
     * - null, bool, int, float and str scalars become `null`, [Boolean], [Long]/[BigInteger], [Double] and [String];
     * - timestamps and the unloadable `=` scalar keep their text;
     * - `!vault` values become `{"__ansible_vault": ""}` (the shape of AnsibleJSONEncoder, without the ciphertext);
     * - mapping keys are rendered the way Python's `json` module renders non-string keys (`true`, `1`, `null`).
     */
    fun toJson(value: YValue?): Any? = when (value) {
        null, is YEmpty -> null
        is YScalar -> scalarToJson(value.resolved)
        is YSeq -> value.items.map { toJson(it) }
        is YMap -> LinkedHashMap<String, Any?>().apply {
            for (entry in value.entries) put(keyText(entry.key), toJson(entry.value))
        }
        is YVault -> mapOf("__ansible_vault" to "")
    }

    /** Builds a [YValue] from a JSON value produced by [Json.parse]. */
    fun fromJson(value: Any?): YValue = when (value) {
        null -> YScalar("null", ScalarStyle.PLAIN)
        is Boolean -> YScalar(if (value) "true" else "false", ScalarStyle.PLAIN)
        is String -> YScalar(value, ScalarStyle.DOUBLE_QUOTED)
        is Double -> YScalar(yamlFloat(value), ScalarStyle.PLAIN)
        is Float -> YScalar(yamlFloat(value.toDouble()), ScalarStyle.PLAIN)
        is Number -> YScalar(value.toString(), ScalarStyle.PLAIN)
        is Map<*, *> -> YMap(value.entries.map { (k, v) -> YEntry(YScalar(k.toString(), ScalarStyle.DOUBLE_QUOTED), fromJson(v)) })
        is List<*> -> YSeq(value.map { fromJson(it) })
        else -> YScalar(value.toString(), ScalarStyle.DOUBLE_QUOTED)
    }

    private fun scalarToJson(resolved: Resolved): Any? = when (resolved) {
        Resolved.Null -> null
        is Resolved.Bool -> resolved.value
        is Resolved.Int -> resolved.value.toLongOrBig()
        is Resolved.Float -> resolved.value
        is Resolved.Str -> resolved.value
        is Resolved.Timestamp -> resolved.text
        Resolved.Unloadable -> "="
    }

    private fun keyText(key: YScalar): String = when (val r = key.resolved) {
        Resolved.Null -> "null"
        is Resolved.Bool -> if (r.value) "true" else "false"
        is Resolved.Int -> r.value.toString()
        is Resolved.Float -> pythonFloatRepr(r.value)
        is Resolved.Str -> r.value
        is Resolved.Timestamp -> r.text
        Resolved.Unloadable -> "="
    }

    private fun BigInteger.toLongOrBig(): Any = if (bitLength() < 64) toLong() else this

    /** PyYAML's `represent_float`: Python's repr, with `.0` inserted before a bare exponent (`1.0e+20`). */
    internal fun yamlFloat(d: Double): String = when {
        d.isNaN() -> ".nan"
        d == Double.POSITIVE_INFINITY -> ".inf"
        d == Double.NEGATIVE_INFINITY -> "-.inf"
        else -> pythonFloatRepr(d).let { if ('.' !in it && 'e' in it) it.replaceFirst("e", ".0e") else it }
    }

    /**
     * Python's `repr(float)`: the shortest round-tripping digits, in fixed notation when the decimal exponent is
     * in -4 < e <= 16 and in `d.ddde+XX` notation otherwise.
     */
    internal fun pythonFloatRepr(d: Double): String {
        if (d.isNaN()) return "NaN"
        if (d.isInfinite()) return if (d > 0) "Infinity" else "-Infinity"
        if (d == 0.0) return if (1.0 / d < 0) "-0.0" else "0.0"
        val sign = if (d < 0) "-" else ""
        // Double.toString yields the shortest round-tripping digits (JDK 19+), like Python's repr.
        val exact = BigDecimal(Math.abs(d).toString()).stripTrailingZeros()
        val digits = exact.unscaledValue().toString()
        val point = digits.length - exact.scale() // value = 0.<digits> * 10^point
        val body = if (point > -4 && point <= 16) {
            when {
                point <= 0 -> "0." + "0".repeat(-point) + digits
                point >= digits.length -> digits + "0".repeat(point - digits.length) + ".0"
                else -> digits.substring(0, point) + "." + digits.substring(point)
            }
        } else {
            val mantissa = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
            val exponent = point - 1
            mantissa + "e" + (if (exponent < 0) "-" else "+") + Math.abs(exponent).toString().padStart(2, '0')
        }
        return sign + body
    }
}
