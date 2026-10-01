package de.terletzkiy.ansibility.semantics.inventory

import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.YEmpty
import de.terletzkiy.ansibility.semantics.yaml.YEntry
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.semantics.yaml.YVault
import java.math.BigInteger

/**
 * Small ports of the Python built-ins that ansible-core's inventory and variable code relies on
 * (dict construction from YAML, truthiness, `int()`, `str.zfill`, `os.path.splitext`, `str` ordering).
 */
internal object Py {
    /**
     * The Python dict PyYAML builds from [map]: one entry per key text, at the position of the first occurrence,
     * holding the last occurrence (Ansible warns about duplicate keys and uses the last value).
     */
    fun dict(map: YMap): LinkedHashMap<String, YEntry> {
        val out = LinkedHashMap<String, YEntry>()
        for (entry in map.entries) out[entry.key.text] = entry
        return out
    }

    /** `value is None` after loading: an empty node or a plain `~`/`null` scalar. */
    fun isNone(value: YValue?): Boolean =
        value == null || value is YEmpty || (value is YScalar && value.resolved == Resolved.Null)

    /** Python truthiness of the loaded value. A vault value is a non-empty string. */
    fun isTruthy(value: YValue?): Boolean = when (value) {
        null, is YEmpty -> false
        is YVault -> true
        is YSeq -> value.items.isNotEmpty()
        is YMap -> value.entries.isNotEmpty()
        is YScalar -> when (val r = value.resolved) {
            Resolved.Null -> false
            is Resolved.Bool -> r.value
            is Resolved.Int -> r.value.signum() != 0
            is Resolved.Float -> r.value != 0.0
            is Resolved.Str -> r.value.isNotEmpty()
            is Resolved.Timestamp, Resolved.Unloadable -> true
        }
    }

    /** `isinstance(value, str)` after loading (`!unsafe` strings included, vault values excluded). */
    fun isStr(value: YValue?): Boolean = value is YScalar && value.resolved is Resolved.Str

    /** The Python type name of the loaded value, as used in Ansible's messages. */
    fun typeName(value: YValue?): String = when (value) {
        null, is YEmpty -> "NoneType"
        is YVault -> "AnsibleVaultEncryptedUnicode"
        is YSeq -> "list"
        is YMap -> "dict"
        is YScalar -> when (value.resolved) {
            Resolved.Null -> "NoneType"
            is Resolved.Bool -> "bool"
            is Resolved.Int -> "int"
            is Resolved.Float -> "float"
            is Resolved.Str -> "str"
            is Resolved.Timestamp -> "date"
            Resolved.Unloadable -> "unloadable"
        }
    }

    /**
     * Python `int(text)` for base 10: surrounding whitespace, an optional sign, digits with single underscores
     * between them. Returns null where Python raises ValueError.
     */
    fun parseInt(text: String): BigInteger? {
        var s = text.trim()
        var negative = false
        if (s.startsWith("+") || s.startsWith("-")) {
            negative = s[0] == '-'
            s = s.substring(1)
        }
        if (s.isEmpty() || !s[0].isAsciiDigit() || !s[s.length - 1].isAsciiDigit()) return null
        for (i in s.indices) {
            val c = s[i]
            if (c == '_') {
                if (s[i - 1] == '_') return null
            } else if (!c.isAsciiDigit()) {
                return null
            }
        }
        val value = BigInteger(s.replace("_", ""))
        return if (negative) value.negate() else value
    }

    /** Python `int(value)` applied to a loaded YAML value (bool → 0/1, float truncated, str parsed). */
    fun toInt(value: YValue?): BigInteger? = when (value) {
        is YScalar -> when (val r = value.resolved) {
            is Resolved.Int -> r.value
            is Resolved.Bool -> if (r.value) BigInteger.ONE else BigInteger.ZERO
            is Resolved.Float -> if (r.value.isFinite()) java.math.BigDecimal(r.value).toBigInteger() else null
            is Resolved.Str -> parseInt(r.value)
            else -> null
        }
        else -> null
    }

    /** Python `str.zfill(width)`: pads with zeros after an optional sign. */
    fun zfill(text: String, width: Int): String {
        if (text.length >= width) return text
        val pad = "0".repeat(width - text.length)
        return if (text.startsWith("+") || text.startsWith("-")) text[0] + pad + text.substring(1) else pad + text
    }

    /** The extension part of Python `os.path.splitext(name)` for a single path component ("" when there is none). */
    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return ""
        for (i in 0 until dot) if (name[i] != '.') return name.substring(dot)
        return ""
    }

    /** Python `str` ordering (by code point), used by `sorted()` and tuple comparison of names. */
    val STRING_ORDER: Comparator<String> = Comparator { a, b ->
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a.codePointAt(i)
            val cb = b.codePointAt(j)
            if (ca != cb) return@Comparator ca.compareTo(cb)
            i += Character.charCount(ca)
            j += Character.charCount(cb)
        }
        (a.length - i).compareTo(b.length - j)
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'
}
