package de.terletzkiy.ansibility.model.task

/**
 * Splits a module's string arguments the way ansible-core's `parse_kv` does (`module_utils/splitter.py`):
 * words are separated by whitespace outside quotes and Jinja blocks, a word with an unescaped `=` after its
 * first character is an option `k=v` (the value unquoted), and every other word is free-form text
 * (`_raw_params`). With [checkRaw] (the free-form modules `command`, `shell`, `raw`, `script`), only the
 * options those modules read from the string (`creates`, `chdir` …) are taken out; all else stays free-form.
 */
object KeyValueArgs {
    /** The options `command`/`shell` accept inside their free-form string. */
    val RAW_OPTIONS: Set<String> = setOf(
        "creates", "removes", "chdir", "executable", "warn", "stdin", "stdin_add_newline", "strip_empty_ends",
    )

    /** One `k=v` option; offsets are indices into the parsed text. */
    data class Option(val key: String, val value: String, val keyStart: Int, val keyEnd: Int, val valueStart: Int, val valueEnd: Int)

    /** The result: options in order, and the free-form words joined with single spaces (null when there are none). */
    data class Parsed(val options: List<Option>, val rawParams: String?)

    fun parse(text: String, checkRaw: Boolean): Parsed {
        val options = ArrayList<Option>()
        val raw = ArrayList<String>()
        for ((start, end) in words(text)) {
            val word = text.substring(start, end)
            val eq = splitPosition(word)
            if (eq < 0) {
                raw += word.replace("\\=", "=")
                continue
            }
            val key = word.substring(0, eq)
            if (checkRaw && key !in RAW_OPTIONS) {
                raw += word
                continue
            }
            val trimmedKey = key.trim()
            val (valueStart, valueEnd) = unquotedBounds(text, start + eq + 1, end)
            options += Option(
                key = trimmedKey,
                value = text.substring(valueStart, valueEnd),
                keyStart = start,
                keyEnd = start + key.length,
                valueStart = valueStart,
                valueEnd = valueEnd,
            )
        }
        return Parsed(options, raw.takeIf { it.isNotEmpty() }?.joinToString(" "))
    }

    /** Word boundaries: whitespace splits outside quotes and outside `{{ }}`, `{% %}` and `{# #}`. */
    fun words(text: String): List<Pair<Int, Int>> {
        val result = ArrayList<Pair<Int, Int>>()
        var start = -1
        var quote: Char? = null
        var jinjaDepth = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            when {
                quote != null -> if (c == quote && text.getOrNull(i - 1) != '\\') quote = null
                c == '{' && (next == '{' || next == '%' || next == '#') -> {
                    jinjaDepth++
                    if (start < 0) start = i
                    i += 2
                    continue
                }
                jinjaDepth > 0 && (c == '}' || c == '%' || c == '#') && next == '}' -> {
                    jinjaDepth--
                    i += 2
                    continue
                }
                jinjaDepth == 0 && (c == '"' || c == '\'') -> quote = c
                jinjaDepth == 0 && c.isWhitespace() -> {
                    if (start >= 0) result += start to i
                    start = -1
                    i++
                    continue
                }
            }
            if (start < 0) start = i
            i++
        }
        if (start >= 0) result += start to text.length
        return result
    }

    /** The first `=` after position 0 that is not escaped, or -1 (parse_kv's scan starts at index 1). */
    private fun splitPosition(word: String): Int {
        var pos = word.indexOf('=', 1)
        while (pos > 0) {
            if (word[pos - 1] != '\\') return pos
            pos = word.indexOf('=', pos + 1)
        }
        return -1
    }

    /** The bounds of `text[start, end)` without surrounding whitespace and one pair of matching quotes. */
    private fun unquotedBounds(text: String, start: Int, end: Int): Pair<Int, Int> {
        var s = start
        var e = end
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        if (e - s >= 2 && (text[s] == '"' || text[s] == '\'') && text[e - 1] == text[s]) {
            s++
            e--
        }
        return s to e
    }
}
