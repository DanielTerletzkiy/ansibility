package de.terletzkiy.ansibility.lang.jinja.refs

import com.intellij.openapi.util.TextRange
import com.intellij.psi.tree.IElementType
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaLexer
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes as T
import de.terletzkiy.ansibility.lang.jinja.lexer.JinjaLexMode

/**
 * The implementation of [JinjaRefs.analyzeBracedExpression]: masks the braced parts of an implicit expression and
 * analyses the rest as one bare expression.
 *
 * The braced parts are what the template lexer does not report as outer text: `{{ … }}` output tags, `{% … %}`
 * statement tags, `{# … #}` comments and `{% raw %}` bodies. Each part is replaced by text of the same length, so the
 * analyser's offsets are those of the original text:
 * - inside a quoted string of the expression (`'{{ item }}=' in out`): [PLACEHOLDER]s, so the string stays one string
 *   whose value is unknown;
 * - an output tag outside a string: a string literal (quotes around placeholders), standing for the rendered value as
 *   it takes part in the expression in the common case (`{{ a }} == b`);
 * - a statement tag or comment outside a string: spaces, since it renders nothing (the text between `{% if %}` and
 *   `{% endif %}` stays and is analysed);
 * - a `{% raw %}` body outside a string: spaces as well, so its text, which Jinja copies verbatim, is left out.
 *
 * The analyser is told the masked ranges, so a string with a masked part is a dynamic value (no constant accessor, no
 * member name). This object drops the names that touch a part (`prefix_{{ x }}` renders to one name assembled at run
 * time) together with the indirect reads starting from them, and ends a constant path before an attribute that touches
 * one (`x.a{{ b }}` reads `x`, with no constant path). Touching takes Jinja's whitespace control into account,
 * as Ansible renders with it: the whitespace that `{{-`/`-}}` (and the `{%`/`{#` forms) strip, and the one newline
 * after a `%}`/`#}` that `trim_blocks` removes unless the tag ends with `+%}`/`+#}`.
 */
internal object JinjaBracedExpressions {
    /** The filler for rendered text inside a string; not a name character, so no member name can be formed from it. */
    const val PLACEHOLDER: Char = '\uE000'

    /**
     * One braced part; [renders] is true for an output tag, whose rendered value takes part in the expression. [glue]
     * is the part together with the whitespace Jinja removes next to it, so a name ending or starting there is
     * rendered against the part.
     */
    class Part(val range: TextRange, val renders: Boolean, val glue: TextRange = range)

    fun analyze(text: CharSequence): JinjaRefsResult {
        val parts = bracedParts(text)
        if (parts.isEmpty()) return JinjaRefs.analyze(text, JinjaLexMode.EXPRESSION)
        val result = JinjaRefsAnalyzer(mask(text, parts), JinjaLexMode.EXPRESSION, parts.map { it.range }).analyze()
        fun touches(range: TextRange) = parts.any { it.glue.endOffset == range.startOffset || it.glue.startOffset == range.endOffset }

        /** The end of an access without its last `.segment` when that segment is glued to a part, else null. */
        fun trimmedEnd(path: List<String>, range: TextRange): Int? =
            if (path.isEmpty() || parts.none { it.glue.startOffset == range.endOffset }) null else lastSegmentStart(text, range)

        val dropped = result.references.filter { touches(it.nameRange) }.mapTo(HashSet()) { it.nameRange }
        val references = result.references.mapNotNull { ref ->
            if (ref.nameRange in dropped) return@mapNotNull null
            val end = trimmedEnd(ref.attrPath, ref.range) ?: return@mapNotNull ref
            ref.copy(attrPath = ref.attrPath.dropLast(1), range = TextRange(ref.range.startOffset, end))
        }
        val indirect = result.indirectReferences.mapNotNull { ref ->
            if (ref.rootRange in dropped || touches(ref.nameRange)) return@mapNotNull null
            val end = trimmedEnd(ref.attrPath, ref.range) ?: return@mapNotNull ref
            ref.copy(attrPath = ref.attrPath.dropLast(1), range = TextRange(ref.range.startOffset, end))
        }
        if (references == result.references && indirect == result.indirectReferences) return result
        return result.copy(references = references, indirectReferences = indirect)
    }

    /**
     * Where the last `.segment` of an access at [range] starts (its dot), or null when the access does not end with
     * one. Such a segment glued to a braced part (`x.a{{ b }}`) names an attribute assembled at run time, so the
     * constant path ends before it; a closing `]` cannot glue to a name.
     */
    private fun lastSegmentStart(text: CharSequence, range: TextRange): Int? {
        var k = range.endOffset
        while (k > range.startOffset && (text[k - 1].isLetterOrDigit() || text[k - 1] == '_')) k--
        if (k == range.endOffset) return null
        while (k > range.startOffset && text[k - 1].isWhitespace()) k--
        return (k - 1).takeIf { it > range.startOffset && text[it] == '.' }
    }

    /** The braced parts of [text], in order. An unterminated tag runs to the end of the text. */
    fun bracedParts(text: CharSequence): List<Part> {
        val parts = ArrayList<Part>()
        val lexer = AnsibleJinjaLexer(JinjaLexMode.TEMPLATE)
        lexer.start(text)
        var open = -1
        var renders = false
        while (true) {
            val type = lexer.tokenType ?: break
            when {
                type == T.VAR_START || type == T.BLOCK_START -> {
                    if (open < 0) open = lexer.tokenStart
                    renders = renders || type == T.VAR_START
                }
                type == T.VAR_END || type == T.BLOCK_END -> if (open >= 0) {
                    parts += tag(text, TextRange(open, lexer.tokenEnd), renders)
                    open = -1
                    renders = false
                }
                open < 0 && type != T.TEXT -> parts += bodyPart(text, TextRange(lexer.tokenStart, lexer.tokenEnd), type)
            }
            lexer.advance()
        }
        if (open >= 0) parts += Part(TextRange(open, text.length), renders, glue = TextRange(stripStart(text, open), text.length))
        return parts
    }

    /** A part outside any tag: a comment, whose markers control whitespace like a tag's, or a `{% raw %}` body. */
    private fun bodyPart(text: CharSequence, range: TextRange, type: IElementType): Part =
        if (type == T.COMMENT) tag(text, range, renders = false) else Part(range, renders = false)

    /**
     * A tag or comment at [range] with its [Part.glue]: before a `-` opening marker the whitespace up to it, after a
     * `-` closing marker the whitespace after it, and after a block or comment end without a `+` marker one newline
     * (`trim_blocks`).
     */
    private fun tag(text: CharSequence, range: TextRange, renders: Boolean): Part {
        val start = stripStart(text, range.startOffset)
        var end = range.endOffset
        if (range.length >= 4 && text[end - 1] == '}' && text[end - 2] in CLOSERS) {
            when (text[end - 3]) {
                '-' -> while (end < text.length && text[end].isWhitespace()) end++
                '+' -> Unit
                else -> if (text[end - 2] != '}') {
                    if (end + 1 < text.length && text[end] == '\r' && text[end + 1] == '\n') end += 2
                    else if (end < text.length && text[end] == '\n') end++
                }
            }
        }
        return Part(range, renders, TextRange(start, end))
    }

    /** Where a part starting at [start] glues on the left: before the whitespace a `{{-`/`{%-`/`{#-` opening strips. */
    private fun stripStart(text: CharSequence, start: Int): Int {
        if (start + 2 >= text.length || text[start] != '{' || text[start + 1] !in OPENERS || text[start + 2] != '-') return start
        var glue = start
        while (glue > 0 && text[glue - 1].isWhitespace()) glue--
        return glue
    }

    /** [text] with each of [parts] replaced by neutral text of the same length (see the class comment). */
    fun mask(text: CharSequence, parts: List<Part>): CharSequence {
        val masked = StringBuilder(text)
        var quote: Char? = null
        var next = 0
        var i = 0
        while (i < text.length) {
            val part = parts.getOrNull(next)
            if (part != null && i == part.range.startOffset) {
                val range = part.range
                val fill = if (quote != null || part.renders) PLACEHOLDER else ' '
                for (k in range.startOffset until range.endOffset) masked.setCharAt(k, fill)
                if (quote == null && part.renders && range.length >= 2) {
                    masked.setCharAt(range.startOffset, '\'')
                    masked.setCharAt(range.endOffset - 1, '\'')
                }
                i = range.endOffset
                next++
                continue
            }
            val c = text[i]
            when {
                quote == null -> if (c == '\'' || c == '"') quote = c
                c == '\\' -> if (part == null || i + 1 < part.range.startOffset) i++
                c == quote -> quote = null
            }
            i++
        }
        return masked
    }

    /** The second character of a tag or comment opening: `{{`, `{%`, `{#`. */
    private val OPENERS = setOf('{', '%', '#')

    /** The first character of a tag or comment closing: `}}`, `%}`, `#}`. */
    private val CLOSERS = setOf('}', '%', '#')
}
