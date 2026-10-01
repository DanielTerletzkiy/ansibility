package de.terletzkiy.ansibility.semantics.typeflow

/**
 * Test-only Jinja tokenizer (TEMPLATE mode) producing what the plugin's `AnsibleJinjaLexer` hands to :semantics:
 * text, comments, tag delimiters with their whitespace markers, and expression tokens without whitespace. It follows
 * jinja2 3.1's lexer closely enough for the evaluator tests (bracket balancing inside tags, `{% raw %}` bodies);
 * the plugin cross-checks its real lexer against the same templates.
 */
object TestJinjaTokenizer : JinjaTokenizer {
    private val EXPRESSION_KEYWORDS = setOf("and", "or", "not", "in", "is", "if", "else", "true", "True", "false", "False", "none", "None")
    private val STATEMENT_KEYWORDS = setOf(
        "if", "elif", "else", "endif", "for", "endfor", "set", "endset", "macro", "endmacro", "call", "endcall", "filter",
        "endfilter", "with", "endwith", "include", "import", "from", "extends", "block", "endblock", "raw", "endraw", "do",
        "break", "continue",
    )
    private val OPERATORS = listOf("**", "//", "==", "!=", "<=", ">=", "+", "-", "*", "/", "%", "~", "<", ">", "=", "|", ".", ",", ":", ";", "(", ")", "[", "]", "{", "}")
    private val ENDRAW = Regex("""\{%[-+]?\s*endraw\s*[-+]?%}""")

    override fun tokenize(text: String): List<JinjaToken> {
        val out = ArrayList<JinjaToken>()
        var i = 0
        fun flushText(start: Int, end: Int) {
            if (end > start) out += JinjaToken(JinjaTokenKind.TEXT, text.substring(start, end), start)
        }
        var segment = 0
        while (i < text.length) {
            val opener = listOf("{{", "{%", "{#").map { text.indexOf(it, i) }.filter { it >= 0 }.minOrNull()
            if (opener == null) {
                flushText(segment, text.length)
                return out
            }
            flushText(segment, opener)
            i = when (text.substring(opener, opener + 2)) {
                "{#" -> {
                    val close = text.indexOf("#}", opener + 2)
                    val end = if (close < 0) text.length else close + 2
                    out += JinjaToken(JinjaTokenKind.COMMENT, text.substring(opener, end), opener)
                    end
                }
                "{{" -> tag(text, opener, JinjaTokenKind.VAR_START, JinjaTokenKind.VAR_END, "}}", out)
                else -> {
                    val end = tag(text, opener, JinjaTokenKind.BLOCK_START, JinjaTokenKind.BLOCK_END, "%}", out)
                    val isRaw = out.size >= 3 && out[out.size - 1].kind == JinjaTokenKind.BLOCK_END &&
                        out[out.size - 2].text == "raw" && out[out.size - 3].kind == JinjaTokenKind.BLOCK_START
                    if (isRaw) {
                        val match = ENDRAW.find(text, end)
                        val rawEnd = match?.range?.first ?: text.length
                        if (rawEnd > end) out += JinjaToken(JinjaTokenKind.RAW_TEXT, text.substring(end, rawEnd), end)
                        if (match == null) rawEnd else tag(text, rawEnd, JinjaTokenKind.BLOCK_START, JinjaTokenKind.BLOCK_END, "%}", out)
                    } else {
                        end
                    }
                }
            }
            segment = i
        }
        flushText(segment, text.length)
        return out
    }

    /** One tag from [open]; returns the offset after it. */
    private fun tag(text: String, open: Int, startKind: JinjaTokenKind, endKind: JinjaTokenKind, close: String, out: MutableList<JinjaToken>): Int {
        var i = open + 2
        if (i < text.length && (text[i] == '-' || text[i] == '+')) i++
        out += JinjaToken(startKind, text.substring(open, i), open)
        var depth = 0
        var first = true
        while (i < text.length) {
            val c = text[i]
            if (c.isWhitespace()) {
                i++
                continue
            }
            if (depth == 0) {
                for (marker in listOf("-$close", "+$close", close)) {
                    if (text.startsWith(marker, i) && !(marker.startsWith("+") && close == "}}")) {
                        out += JinjaToken(endKind, marker, i)
                        return i + marker.length
                    }
                }
            }
            when {
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                    val word = text.substring(start, i)
                    val keyword = if (startKind == JinjaTokenKind.BLOCK_START && first) word in STATEMENT_KEYWORDS else word in EXPRESSION_KEYWORDS
                    out += JinjaToken(if (keyword) JinjaTokenKind.KEYWORD else JinjaTokenKind.NAME, word, start)
                }
                c.isDigit() -> i = number(text, i, out)
                c == '\'' || c == '"' -> {
                    val start = i++
                    while (i < text.length && text[i] != c) {
                        if (text[i] == '\\') i++
                        i++
                    }
                    i = minOf(i + 1, text.length)
                    out += JinjaToken(JinjaTokenKind.STRING, text.substring(start, i), start)
                }
                else -> {
                    val operator = OPERATORS.firstOrNull { text.startsWith(it, i) }
                    if (operator == null) {
                        out += JinjaToken(JinjaTokenKind.BAD, c.toString(), i)
                        i++
                    } else {
                        if (operator in setOf("(", "[", "{")) depth++
                        if (operator in setOf(")", "]", "}")) depth--
                        out += JinjaToken(JinjaTokenKind.OPERATOR, operator, i)
                        i += operator.length
                    }
                }
            }
            first = false
        }
        return i
    }

    private fun number(text: String, from: Int, out: MutableList<JinjaToken>): Int {
        val prefixed = Regex("""0[xX][_0-9a-fA-F]+|0[oO][_0-7]+|0[bB][_01]+""").matchAt(text, from)
        if (prefixed != null) {
            out += JinjaToken(JinjaTokenKind.INTEGER, prefixed.value, from)
            return from + prefixed.value.length
        }
        val float = Regex("""\d[\d_]*(\.\d[\d_]*([eE][+-]?\d[\d_]*)?|[eE][+-]?\d[\d_]*)""").matchAt(text, from)
        if (float != null) {
            out += JinjaToken(JinjaTokenKind.FLOAT, float.value, from)
            return from + float.value.length
        }
        val int = Regex("""\d[\d_]*""").matchAt(text, from)!!
        out += JinjaToken(JinjaTokenKind.INTEGER, int.value, from)
        return from + int.value.length
    }
}
