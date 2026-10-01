package de.terletzkiy.ansibility.yaml

import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.semantics.yaml.YEmpty
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.semantics.yaml.YVault

/**
 * Renders a [YValue] in the golden format written by `src/test/testData/yaml/golden/generate.py` from PyYAML
 * (see that script for the format). Ranges and source spellings are left out; mapping entries are sorted by key and
 * collapsed to the value PyYAML keeps. A copy lives in the :semantics tests for `YamlText`; keep the two in sync.
 */
object YValueDump {
    fun render(value: YValue?): String {
        val out = StringBuilder()
        if (value != null) dump(value, 0, out)
        return out.toString()
    }

    private fun dump(value: YValue, indent: Int, out: StringBuilder) {
        val pad = " ".repeat(indent)
        when (value) {
            is YEmpty -> out.append(pad).append("empty\n")
            is YVault -> out.append(pad).append("vault\n")
            is YScalar -> {
                out.append(pad).append("scalar ").append(style(value.style)).append(' ')
                value.tag?.let { out.append(it).append(' ') }
                out.append(escape(value.text)).append(" => ").append(resolved(value.resolved)).append('\n')
            }
            is YSeq -> {
                out.append(pad).append("seq\n")
                value.items.forEach { dump(it, indent + 2, out) }
            }
            is YMap -> {
                out.append(pad).append("map\n")
                val effective = LinkedHashMap<String, Pair<YScalar, YValue>>()
                value.entries.forEach { effective[it.key.text] = it.key to it.value }
                for ((text, entry) in effective.toSortedMap()) {
                    out.append(pad).append("  key ").append(style(entry.first.style)).append(' ')
                    out.append(escape(text)).append('\n')
                    dump(entry.second, indent + 4, out)
                }
            }
        }
    }

    private fun style(style: ScalarStyle): String = when (style) {
        ScalarStyle.PLAIN -> "plain"
        ScalarStyle.SINGLE_QUOTED -> "single"
        ScalarStyle.DOUBLE_QUOTED -> "double"
        ScalarStyle.LITERAL -> "literal"
        ScalarStyle.FOLDED -> "folded"
    }

    private fun resolved(value: Resolved): String = when (value) {
        Resolved.Null -> "null"
        is Resolved.Bool -> "bool ${value.value}"
        is Resolved.Int -> "int ${value.value}"
        is Resolved.Float -> "float " + when {
            value.value.isNaN() -> "nan"
            value.value == Double.POSITIVE_INFINITY -> "inf"
            value.value == Double.NEGATIVE_INFINITY -> "-inf"
            // Matches Python's repr() between 1e-3 and 1e7, the range the golden corpus keeps to.
            else -> value.value.toString()
        }
        is Resolved.Str -> "str"
        is Resolved.Timestamp -> "timestamp"
        Resolved.Unloadable -> "unloadable"
    }

    /** Code points escaped besides the C0 and C1 controls (the same set as `generate.py`). */
    private val SPECIAL = setOf(0x2028, 0x2029, 0xFEFF)

    private fun escape(text: String): String {
        val out = StringBuilder("\"")
        for (ch in text) {
            when {
                ch == '\\' -> out.append("\\\\")
                ch == '"' -> out.append("\\\"")
                ch == '\n' -> out.append("\\n")
                ch == '\t' -> out.append("\\t")
                ch == '\r' -> out.append("\\r")
                ch.code < 0x20 || ch.code in 0x7F..0x9F || ch.code in SPECIAL -> out.append("\\u%04x".format(ch.code))
                else -> out.append(ch)
            }
        }
        return out.append('"').toString()
    }
}
