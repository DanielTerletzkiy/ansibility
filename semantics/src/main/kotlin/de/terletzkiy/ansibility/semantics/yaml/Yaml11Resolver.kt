package de.terletzkiy.ansibility.semantics.yaml

import java.math.BigInteger
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime

/**
 * Port of PyYAML's implicit resolvers and scalar constructors (yaml/resolver.py, yaml/constructor.py),
 * which is what Ansible's `AnsibleLoader` uses. YAML 1.1 semantics: `yes` is a bool, `0644` is octal 420,
 * `1:20` is sexagesimal 80, `1e3` stays a string, `3.10` becomes the float 3.1.
 *
 * Where PyYAML's constructor raises (`!!bool maybe`, `0b_`, an unknown tag such as `!Ref`, a bare `=`), Ansible
 * cannot load the file, and the result is [Resolved.Unloadable].
 */
object Yaml11Resolver {
    private val BOOL = Regex("^(?:yes|Yes|YES|no|No|NO|true|True|TRUE|false|False|FALSE|on|On|ON|off|Off|OFF)$")
    private val FLOAT = Regex(
        "^(?:[-+]?(?:[0-9][0-9_]*)\\.[0-9_]*(?:[eE][-+][0-9]+)?" +
            "|\\.[0-9][0-9_]*(?:[eE][-+][0-9]+)?" +
            "|[-+]?[0-9][0-9_]*(?::[0-5]?[0-9])+\\.[0-9_]*" +
            "|[-+]?\\.(?:inf|Inf|INF)" +
            "|\\.(?:nan|NaN|NAN))$",
    )
    private val INT = Regex(
        "^(?:[-+]?0b[0-1_]+" +
            "|[-+]?0[0-7_]+" +
            "|[-+]?(?:0|[1-9][0-9_]*)" +
            "|[-+]?0x[0-9a-fA-F_]+" +
            "|[-+]?[1-9][0-9_]*(?::[0-5]?[0-9])+)$",
    )
    private val NULL = Regex("^(?:~|null|Null|NULL|)$")
    private val TIMESTAMP = Regex(
        "^(?:[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]" +
            "|[0-9][0-9][0-9][0-9]-[0-9][0-9]?-[0-9][0-9]?" +
            "(?:[Tt]|[ \\t]+)[0-9][0-9]?:[0-9][0-9]:[0-9][0-9]" +
            "(?:\\.[0-9]*)?(?:[ \\t]*(?:Z|[-+][0-9][0-9]?(?::[0-9][0-9])?))?)$",
    )
    private val VALUE = Regex("^(?:=)$")

    /** `SafeConstructor.timestamp_regexp`, which is looser than the resolver's (`2024-1-1` needs `!!timestamp`). */
    private val TIMESTAMP_PARTS = Regex(
        "^([0-9][0-9][0-9][0-9])-([0-9][0-9]?)-([0-9][0-9]?)" +
            "(?:(?:[Tt]|[ \\t]+)([0-9][0-9]?):([0-9][0-9]):([0-9][0-9])(?:\\.[0-9]*)?" +
            "(?:[ \\t]*(?:Z|[-+]([0-9][0-9]?)(?::([0-9][0-9]))?))?)?$",
    )

    /** Python's `float()` on a lower-cased string: decimal digits, an optional exponent, `inf`/`infinity`/`nan`. */
    private val PYTHON_FLOAT = Regex("[-+]?(?:(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:e[-+]?[0-9]+)?|inf|infinity|nan)")

    private const val YAML_ORG = "tag:yaml.org,2002:"
    private val SIXTY = BigInteger.valueOf(60)
    private val BOOL_VALUES =
        mapOf("yes" to true, "no" to false, "true" to true, "false" to false, "on" to true, "off" to false)

    /** Ansible's own scalar tags, which load as strings (`!vault` decrypts to one). */
    private val ANSIBLE_STRING_TAGS = setOf("!unsafe", "!vault", "!vault-encrypted")

    fun resolve(scalar: YScalar): Resolved {
        val tag = scalar.tag
        if (tag != null) return resolveTagged(tag, scalar.text)
        if (scalar.style != ScalarStyle.PLAIN) return Resolved.Str(scalar.text)
        return resolvePlain(scalar.text)
    }

    /** Implicit resolution of a plain scalar's text, in PyYAML's resolver order. */
    fun resolvePlain(text: String): Resolved = when {
        BOOL.matches(text) -> Resolved.Bool(BOOL_VALUES.getValue(text.lowercase()))
        FLOAT.matches(text) -> Resolved.Float(constructFloat(text))
        // The regex admits `0b_` and `0x_`, whose digits vanish with the underscores: int() raises.
        INT.matches(text) -> constructing { Resolved.Int(constructInt(text)) }
        NULL.matches(text) -> Resolved.Null
        TIMESTAMP.matches(text) -> timestamp(text)
        VALUE.matches(text) -> Resolved.Unloadable
        else -> Resolved.Str(text)
    }

    /**
     * An explicitly tagged scalar, as the matching `construct_yaml_*` builds it. `!` (the non-specific tag) resolves
     * like a plain scalar, whatever the quoting. `!!binary` is approximated as a string.
     */
    private fun resolveTagged(tag: String, text: String): Resolved {
        if (tag == "!") return resolvePlain(text)
        if (tag in ANSIBLE_STRING_TAGS) return Resolved.Str(text)
        return when (shortTag(tag)) {
            "!!str", "!!binary" -> Resolved.Str(text)
            "!!int" -> constructing { Resolved.Int(constructInt(text)) }
            "!!float" -> constructing { Resolved.Float(constructFloat(text)) }
            "!!bool" -> BOOL_VALUES[text.lowercase()]?.let { Resolved.Bool(it) } ?: Resolved.Unloadable
            "!!null" -> Resolved.Null
            "!!timestamp" -> timestamp(text)
            // No constructor (unknown local tags such as `!Ref`, `!!value`) or a collection tag on a scalar.
            else -> Resolved.Unloadable
        }
    }

    /**
     * PyYAML `construct_yaml_int`, including what Python's `int()` accepts on the way (surrounding whitespace,
     * a repeated base prefix such as `0o17` for octal).
     *
     * @throws NumberFormatException where PyYAML raises `ValueError`.
     */
    fun constructInt(raw: String): BigInteger {
        var value = raw.replace("_", "")
        if (value.isEmpty()) throw NumberFormatException("empty int")
        val negative = value[0] == '-'
        if (value[0] == '+' || value[0] == '-') value = value.substring(1)
        val magnitude = when {
            value == "0" -> BigInteger.ZERO
            value.startsWith("0b") -> pythonInt(value.substring(2), 2)
            value.startsWith("0x") -> pythonInt(value.substring(2), 16)
            value.startsWith("0") -> pythonInt(value, 8)
            ':' in value -> {
                var result = BigInteger.ZERO
                var base = BigInteger.ONE
                for (digit in value.split(':').map { pythonInt(it, 10) }.asReversed()) {
                    result += digit * base
                    base *= SIXTY
                }
                result
            }
            else -> pythonInt(value, 10)
        }
        return if (negative) magnitude.negate() else magnitude
    }

    /**
     * PyYAML `construct_yaml_float`, with Python's `float()` rules for the remaining text.
     *
     * @throws NumberFormatException where PyYAML raises `ValueError`.
     */
    fun constructFloat(raw: String): Double {
        var value = raw.replace("_", "").lowercase()
        if (value.isEmpty()) throw NumberFormatException("empty float")
        val sign = if (value[0] == '-') -1.0 else 1.0
        if (value[0] == '+' || value[0] == '-') value = value.substring(1)
        return when {
            value == ".inf" -> sign * Double.POSITIVE_INFINITY
            value == ".nan" -> Double.NaN
            ':' in value -> {
                var result = 0.0
                var base = 1.0
                for (digit in value.split(':').map(::pythonFloat).asReversed()) {
                    result += digit * base
                    base *= 60
                }
                sign * result
            }
            else -> sign * pythonFloat(value)
        }
    }

    /** Python's `int(text, base)`: surrounding whitespace, a sign and the base's own prefix are allowed. */
    private fun pythonInt(text: String, base: Int): BigInteger {
        var digits = text.trim()
        val negative = digits.startsWith("-")
        if (digits.startsWith("+") || digits.startsWith("-")) digits = digits.substring(1)
        val prefix = when (base) {
            2 -> "0b"
            8 -> "0o"
            16 -> "0x"
            else -> null
        }
        if (prefix != null && digits.startsWith(prefix, ignoreCase = true)) digits = digits.substring(2)
        if (digits.isEmpty() || digits.any { Character.digit(it, base) < 0 }) throw NumberFormatException(text)
        val magnitude = BigInteger(digits, base)
        return if (negative) magnitude.negate() else magnitude
    }

    /** Python's `float()` on a lower-cased string (Kotlin's parser would also take hex floats and `1d`). */
    private fun pythonFloat(text: String): Double {
        val value = text.trim()
        if (!PYTHON_FLOAT.matches(value)) throw NumberFormatException(text)
        val negative = value.startsWith("-")
        return when (value.trimStart('+', '-')) {
            "inf", "infinity" -> if (negative) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
            "nan" -> Double.NaN
            else -> value.toDouble()
        }
    }

    /** `construct_yaml_timestamp`: builds a `date`/`datetime`, which raises for an impossible date or time. */
    private fun timestamp(text: String): Resolved {
        val parts = TIMESTAMP_PARTS.matchEntire(text)?.groupValues ?: return Resolved.Unloadable
        // datetime.MINYEAR is 1; java.time would accept year 0.
        if (parts[1].toInt() < 1) return Resolved.Unloadable
        return try {
            LocalDate.of(parts[1].toInt(), parts[2].toInt(), parts[3].toInt())
            if (parts[4].isNotEmpty()) LocalTime.of(parts[4].toInt(), parts[5].toInt(), parts[6].toInt())
            // datetime.timezone() takes offsets strictly below 24 hours.
            if (parts[7].isNotEmpty() && parts[7].toInt() * 60 + (parts[8].toIntOrNull() ?: 0) >= 24 * 60) {
                return Resolved.Unloadable
            }
            Resolved.Timestamp(text)
        } catch (_: DateTimeException) {
            Resolved.Unloadable
        }
    }

    private fun shortTag(tag: String): String {
        val verbatim = if (tag.startsWith("!<") && tag.endsWith(">")) tag.substring(2, tag.length - 1) else tag
        return if (verbatim.startsWith(YAML_ORG)) "!!" + verbatim.removePrefix(YAML_ORG) else verbatim
    }

    private inline fun constructing(build: () -> Resolved): Resolved =
        try {
            build()
        } catch (_: NumberFormatException) {
            Resolved.Unloadable
        }
}
