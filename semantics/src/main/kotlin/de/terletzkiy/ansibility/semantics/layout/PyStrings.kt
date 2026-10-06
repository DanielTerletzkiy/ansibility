package de.terletzkiy.ansibility.semantics.layout

/** The Python string rules that `configparser` and ansible-core's configuration types apply to cfg text. */
internal object PyStrings {
    /** `str.isspace()` characters: what `str.strip()` removes and what `\s` matches in a `str` pattern. */
    private val WHITESPACE: Set<Char> = buildSet {
        addAll(listOf('\t', '\n', '\u000B', '\u000C', '\r', '\u001C', '\u001D', '\u001E', '\u001F', ' ', '\u0085',
            ' ', ' ', ' ', ' ', ' ', ' ', '　'))
        for (c in ' '..' ') add(c)
    }

    fun isSpace(c: Char): Boolean = c in WHITESPACE

    /** `str.strip()`. */
    fun strip(text: String): String = text.trim(::isSpace)

    /** `str.rstrip()`. */
    fun rstrip(text: String): String = text.trimEnd(::isSpace)

    /** `ansible.parsing.quoting.unquote`: drops one pair of matching surrounding quotes unless the last one is escaped. */
    fun unquote(text: String): String {
        val quoted = text.length > 1 && text.first() == text.last() && (text.first() == '"' || text.first() == '\'') &&
            text[text.length - 2] != '\\'
        return if (quoted) text.substring(1, text.length - 1) else text
    }
}
