package de.terletzkiy.ansibility.semantics.typeflow

/** One node of a template's output, after Jinja's own lexing rules (see [JinjaTemplate]). */
sealed interface TemplateNode {
    /** Outer text (raw blocks included) after whitespace control; never empty. */
    data class Text(val text: String, val start: Int) : TemplateNode

    /**
     * One `{{ … }}` tag. [expression] is null when the expression is outside the supported grammar.
     *
     * @property start the offset of `{{`
     * @property end the offset after `}}`
     * @property closeStart the offset of the closing `}}` (or `-}}`) token
     */
    data class Output(val expression: JinjaExpr?, val start: Int, val end: Int, val closeStart: Int) : TemplateNode

    /** One `{% … %}` tag other than `raw`/`endraw`; [keyword] is its tag name (`if`, `for`, `set` …). */
    data class Statement(val keyword: String?, val start: Int, val end: Int) : TemplateNode
}

/** How a template renders, which decides its result type (plan A.5). */
enum class TemplateShape {
    /** The tags do not close, or an expression is outside the supported grammar: nothing is known. */
    MALFORMED,

    /** No expression and no statement (text and comments only): a plain string. */
    TEXT_ONLY,

    /** Exactly one `{{ … }}` and nothing else that renders (comments, stripped whitespace and one trailing newline do not). */
    SINGLE_EXPRESSION,

    /** Expressions plus text, or several expressions, without statements: always rendered as a string on 2.19+. */
    MULTI_NODE,

    /** At least one `{% … %}` statement; 2.19+ may still return a native value (`{% if %}{{ i }}{% endif %}` is an int). */
    STATEMENTS,
}

/**
 * A template as Jinja sees it before rendering: the text of one Jinja-bearing YAML scalar, cut into [nodes].
 *
 * Jinja's lexer rules applied here (Ansible's environment uses the defaults for them):
 * - `keep_trailing_newline=False`: one trailing line break of the source is dropped;
 * - whitespace control: `{{-`, `{%-` and `{#-` strip the whitespace before the tag, `-}}`, `-%}` and `-#}` the
 *   whitespace after it;
 * - comments render nothing; `{% raw %}…{% endraw %}` renders its body as text.
 *
 * @property source the template text (the YAML scalar's loaded value)
 * @property bareName ansible-core ≤ 2.18's `Templar.template` shortcut (`SINGLE_VAR`, `^{{\s*(\w*)\s*}}$`, where
 *   Python's `$` admits one trailing newline): the variable name of a template that is exactly `{{ name }}`, which
 *   keeps a variable's bool, int or float value without rendering
 */
class JinjaTemplate private constructor(
    val source: String,
    val nodes: List<TemplateNode>,
    val shape: TemplateShape,
    val bareName: String?,
) {
    /** The output tags, in order. */
    val outputs: List<TemplateNode.Output> get() = nodes.filterIsInstance<TemplateNode.Output>()

    /** The expression of a [TemplateShape.SINGLE_EXPRESSION] template, else null. */
    val singleExpression: JinjaExpr?
        get() = if (shape == TemplateShape.SINGLE_EXPRESSION) outputs.single().expression else null

    override fun toString(): String = "JinjaTemplate($shape, ${nodes.size} nodes, bare=$bareName)"

    companion object {
        private val SINGLE_VAR = Regex("""(?U)\{\{\s*(\w*)\s*}}\n?""")
        private val IDENTIFIER = Regex("""(?U)[^\W\d]\w*""")
        private val CONSTANT_NAMES = setOf("true", "false", "none", "True", "False", "None")

        /** Parses [source] from its [tokens] (as the plugin's Jinja lexer produces them in TEMPLATE mode). */
        fun parse(source: String, tokens: List<JinjaToken>): JinjaTemplate = Builder(source, tokens).build()

        /** Parses [source] with [tokenizer]. */
        fun parse(source: String, tokenizer: JinjaTokenizer): JinjaTemplate = parse(source, tokenizer.tokenize(source))

        /** The `SINGLE_VAR` name of [source], if the whole text is `{{ name }}`. */
        internal fun bareNameOf(source: String): String? {
            val name = SINGLE_VAR.matchEntire(source)?.groupValues?.get(1) ?: return null
            return name.takeIf { IDENTIFIER.matches(it) && it !in CONSTANT_NAMES }
        }
    }

    private class Builder(private val source: String, private val tokens: List<JinjaToken>) {
        private val nodes = ArrayList<TemplateNode>()

        /** Text of the node being collected, with its start; flushed when a tag starts. */
        private val text = StringBuilder()
        private var textStart = -1

        /** The previous tag ended with `-`: strip the whitespace at the start of the following text. */
        private var stripNext = false
        private var malformed = false
        private var statements = false

        fun build(): JinjaTemplate {
            var i = 0
            while (i < tokens.size) {
                val token = tokens[i]
                when (token.kind) {
                    JinjaTokenKind.TEXT, JinjaTokenKind.RAW_TEXT -> appendText(token.text, token.start)
                    JinjaTokenKind.COMMENT -> tag(token.text.startsWith("{#-"), token.text.endsWith("-#}"))
                    JinjaTokenKind.VAR_START -> i = output(i)
                    JinjaTokenKind.BLOCK_START -> i = statement(i)
                    else -> malformed = true
                }
                i++
            }
            flushText(last = true)
            val shape = when {
                malformed -> TemplateShape.MALFORMED
                statements -> TemplateShape.STATEMENTS
                nodes.none { it is TemplateNode.Output } -> TemplateShape.TEXT_ONLY
                nodes.size == 1 -> TemplateShape.SINGLE_EXPRESSION
                else -> TemplateShape.MULTI_NODE
            }
            val bare = bareNameOf(source)?.takeIf {
                shape == TemplateShape.SINGLE_EXPRESSION && (nodes.single() as TemplateNode.Output).expression is JinjaExpr.Name
            }
            return JinjaTemplate(source, nodes.toList(), shape, bare)
        }

        private fun appendText(value: String, start: Int) {
            if (text.isEmpty()) textStart = start
            text.append(if (stripNext && text.isEmpty()) value.trimStart() else value)
            if (stripNext && text.isNotEmpty()) stripNext = false
        }

        /** A tag starts: [stripBefore] applies `-` to the text before it; [stripAfter] to the text after it. */
        private fun tag(stripBefore: Boolean, stripAfter: Boolean) {
            if (stripBefore) {
                val trimmed = text.trimEnd()
                text.setLength(0)
                text.append(trimmed)
            }
            flushText(last = false)
            stripNext = stripAfter
        }

        private fun flushText(last: Boolean) {
            if (last) dropTrailingNewline()
            if (text.isNotEmpty()) nodes += TemplateNode.Text(text.toString(), textStart)
            text.setLength(0)
            textStart = -1
        }

        /** `keep_trailing_newline=False`: Jinja drops one line break at the very end of the source. */
        private fun dropTrailingNewline() {
            if (!source.endsWith('\n') && !source.endsWith('\r')) return
            val lastToken = tokens.lastOrNull() ?: return
            if (lastToken.kind != JinjaTokenKind.TEXT && lastToken.kind != JinjaTokenKind.RAW_TEXT) return
            when {
                text.endsWith("\r\n") -> text.setLength(text.length - 2)
                text.endsWith("\n") || text.endsWith("\r") -> text.setLength(text.length - 1)
            }
        }

        /** `{{ … }}` starting at token [open]; returns the index of its closing token. */
        private fun output(open: Int): Int {
            val start = tokens[open]
            val close = (open + 1 until tokens.size).firstOrNull { tokens[it].kind == JinjaTokenKind.VAR_END }
            if (close == null || (open + 1 until close).any { tokens[it].kind.isTemplateLevel() }) {
                malformed = true
                return tokens.size
            }
            val end = tokens[close]
            tag(start.text.endsWith("-"), end.text.startsWith("-"))
            val expression = JinjaExprParser.parse(tokens.subList(open + 1, close))
            if (expression == null) malformed = true
            nodes += TemplateNode.Output(expression, start.start, end.end, end.start)
            return close
        }

        /** `{% … %}` starting at token [open]; returns the index of its last token. */
        private fun statement(open: Int): Int {
            val start = tokens[open]
            val close = (open + 1 until tokens.size).firstOrNull { tokens[it].kind == JinjaTokenKind.BLOCK_END }
            if (close == null || (open + 1 until close).any { tokens[it].kind.isTemplateLevel() }) {
                malformed = true
                return tokens.size
            }
            val end = tokens[close]
            val keyword = tokens.getOrNull(open + 1)?.takeIf { open + 1 < close }?.text
            tag(start.text.endsWith("-"), end.text.startsWith("-"))
            when (keyword) {
                // `{% raw %}` and `{% endraw %}` only delimit text that is copied verbatim.
                "raw", "endraw" -> Unit
                else -> {
                    statements = true
                    nodes += TemplateNode.Statement(keyword, start.start, end.end)
                }
            }
            return close
        }

        private fun JinjaTokenKind.isTemplateLevel(): Boolean =
            this == JinjaTokenKind.TEXT || this == JinjaTokenKind.RAW_TEXT || this == JinjaTokenKind.COMMENT ||
                this == JinjaTokenKind.VAR_START || this == JinjaTokenKind.BLOCK_START
    }
}
