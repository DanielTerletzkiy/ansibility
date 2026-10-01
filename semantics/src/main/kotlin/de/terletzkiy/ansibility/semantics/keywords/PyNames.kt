package de.terletzkiy.ansibility.semantics.keywords

import de.terletzkiy.ansibility.semantics.value.PyValue
import java.math.BigInteger

/** The Python name and integer rules keyword validation depends on. */
internal object PyNames {
    /** `keyword.kwlist` of CPython 3.13/3.14 (`True`, `False`, `None` included). */
    private val PYTHON_KEYWORDS = setOf(
        "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del",
        "elif", "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda",
        "nonlocal", "not", "or", "pass", "raise", "return", "try", "while", "with", "yield",
    )

    /** `_jinja_bits.JINJA_KEYWORDS` of 2.21: names Jinja resolves specially. */
    private val JINJA_KEYWORDS = setOf("true", "false", "none", "True", "False", "None", "not")

    /** `str.isidentifier()` restricted to ASCII (both versions require `isascii()` first). */
    fun isAsciiIdentifier(text: String): Boolean {
        if (text.isEmpty() || text.any { it.code > 127 }) return false
        val first = text[0]
        if (!(first == '_' || first in 'a'..'z' || first in 'A'..'Z')) return false
        return text.all { it == '_' || it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
    }

    /**
     * Whether ansible-core rejects [name] as a variable name: 2.18's `isidentifier` (not a string, not ASCII, not an
     * identifier, or a Python keyword) and 2.21's `validate_variable_name` (not a string, not ASCII, not an
     * identifier, or a Jinja keyword). Versions between them reject what both rules reject.
     */
    fun rejectsVariableName(name: PyValue, semantics: KeywordSemantics): Boolean {
        val text = (name as? PyValue.Str)?.value ?: return true
        if (!isAsciiIdentifier(text)) return true
        val python = text in PYTHON_KEYWORDS
        val jinja = text in JINJA_KEYWORDS
        return when {
            semantics.rejectsPythonKeywords -> python
            semantics.rejectsJinjaKeywords -> jinja
            else -> python && jinja
        }
    }

    /**
     * Python's `int(text)` for a `str`: surrounding whitespace is ignored, an optional sign, then decimal digits with
     * single underscores between them (Unicode decimal digits count as digits). Null when Python raises `ValueError`.
     */
    fun parseInt(text: String): BigInteger? {
        val stripped = text.trim { it.isWhitespace() }
        if (stripped.isEmpty()) return null
        var index = 0
        val negative = stripped[0] == '-'
        if (stripped[0] == '+' || stripped[0] == '-') index++
        if (index >= stripped.length) return null
        val digits = StringBuilder()
        var previousUnderscore = true // a leading underscore is invalid
        while (index < stripped.length) {
            val c = stripped[index]
            when {
                c == '_' -> {
                    if (previousUnderscore) return null
                    previousUnderscore = true
                }
                Character.isDigit(c) -> {
                    digits.append(Character.digit(c, 10))
                    previousUnderscore = false
                }
                else -> return null
            }
            index++
        }
        if (previousUnderscore || digits.isEmpty()) return null
        val value = BigInteger(digits.toString())
        return if (negative) value.negate() else value
    }
}
