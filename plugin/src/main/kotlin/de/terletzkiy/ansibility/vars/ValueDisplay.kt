package de.terletzkiy.ansibility.vars

import de.terletzkiy.ansibility.semantics.value.PyRepr
import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.YEmpty
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.semantics.yaml.YVault
import de.terletzkiy.ansibility.semantics.yaml.Yaml11Resolver

/**
 * Loaded YAML values written back the way `ansible-doc` prints spec defaults and choices (a YAML dump): strings are
 * quoted only where YAML 1.1 would otherwise read another type (`'65535'`, `'3.2'`, `''`), booleans are `true`/`false`,
 * collections use flow style. The result is one line of at most [MAX_LENGTH] characters.
 */
internal object ValueDisplay {
    const val MAX_LENGTH: Int = 80
    private const val ELLIPSIS = "…"
    private val INDICATORS = "-?:,[]{}#&*!|>'\"%@`".toSet()

    fun dump(value: YValue): String {
        val out = StringBuilder()
        append(value, out)
        return if (out.length <= MAX_LENGTH) out.toString() else out.substring(0, MAX_LENGTH - 1) + ELLIPSIS
    }

    /** The YAML type name of a loaded value: `str`, `int`, `float`, `bool`, `null`, `date`, `list`, `dict`. */
    fun typeName(value: YValue): String = when (value) {
        is YEmpty -> "null"
        is YVault -> "str"
        is YSeq -> "list"
        is YMap -> "dict"
        is YScalar -> when (value.resolved) {
            Resolved.Null -> "null"
            is Resolved.Bool -> "bool"
            is Resolved.Int -> "int"
            is Resolved.Float -> "float"
            is Resolved.Timestamp -> "date"
            is Resolved.Str, Resolved.Unloadable -> "str"
        }
    }

    private fun append(value: YValue, out: StringBuilder) {
        if (out.length > MAX_LENGTH) return
        when (value) {
            is YEmpty -> out.append("null")
            is YVault -> out.append("!vault")
            is YScalar -> out.append(scalar(value))
            is YSeq -> {
                out.append('[')
                value.items.forEachIndexed { i, item ->
                    if (i > 0) out.append(", ")
                    append(item, out)
                }
                out.append(']')
            }
            is YMap -> {
                out.append('{')
                value.entries.forEachIndexed { i, entry ->
                    if (i > 0) out.append(", ")
                    out.append(string(entry.key.text)).append(": ")
                    append(entry.value, out)
                }
                out.append('}')
            }
        }
    }

    private fun scalar(value: YScalar): String = when (val resolved = value.resolved) {
        Resolved.Null -> "null"
        is Resolved.Bool -> if (resolved.value) "true" else "false"
        is Resolved.Int -> resolved.value.toString()
        is Resolved.Float -> PyRepr.floatRepr(resolved.value)
        is Resolved.Timestamp -> resolved.text
        is Resolved.Str -> string(resolved.value)
        Resolved.Unloadable -> value.sourceText
    }

    /** A string scalar, quoted when a plain spelling would load as something else or break the flow line. */
    private fun string(text: String): String = when {
        '\n' in text || text.any { it.isISOControl() } -> doubleQuoted(text)
        needsQuotes(text) -> "'" + text.replace("'", "''") + "'"
        else -> text
    }

    private fun needsQuotes(text: String): Boolean =
        text.isEmpty() ||
            text.first().isWhitespace() || text.last().isWhitespace() ||
            text.first() in INDICATORS ||
            ": " in text || " #" in text || text.endsWith(":") ||
            Yaml11Resolver.resolvePlain(text) !is Resolved.Str

    private fun doubleQuoted(text: String): String = buildString {
        append('"')
        for (c in text) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                else -> if (c.isISOControl()) append("\\x%02x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}
