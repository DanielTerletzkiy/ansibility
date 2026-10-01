package de.terletzkiy.ansibility.lang.jinja.editor

import com.intellij.openapi.util.TextRange
import com.intellij.psi.tree.IElementType
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaLexer
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes as T
import de.terletzkiy.ansibility.lang.jinja.lexer.JinjaLexMode

/**
 * The Jinja block statements whose opening tag needs an end tag, with the keywords that open and close them (plan F2.7,
 * X35). `set` is a block only without `=` (`{% set x %}…{% endset %}`); `{% set x = 1 %}` is a single tag.
 */
enum class JinjaBlockKind(val openKeyword: IElementType, val closeKeyword: IElementType, val endTagName: String) {
    IF(T.IF_KEYWORD, T.ENDIF_KEYWORD, "endif"),
    FOR(T.FOR_KEYWORD, T.ENDFOR_KEYWORD, "endfor"),
    MACRO(T.MACRO_KEYWORD, T.ENDMACRO_KEYWORD, "endmacro"),
    CALL(T.CALL_KEYWORD, T.ENDCALL_KEYWORD, "endcall"),
    FILTER(T.FILTER_KEYWORD, T.ENDFILTER_KEYWORD, "endfilter"),
    WITH(T.WITH_KEYWORD, T.ENDWITH_KEYWORD, "endwith"),
    BLOCK(T.BLOCK_KEYWORD, T.ENDBLOCK_KEYWORD, "endblock"),
    RAW(T.RAW_KEYWORD, T.ENDRAW_KEYWORD, "endraw"),
    SET(T.SET_KEYWORD, T.ENDSET_KEYWORD, "endset"),
    ;

    companion object {
        private val BY_OPEN = entries.associateBy { it.openKeyword }
        private val BY_CLOSE = entries.associateBy { it.closeKeyword }

        /** The block [tag] opens, or null for middle, end and single tags. */
        fun openedBy(tag: JinjaScannedTag): JinjaBlockKind? {
            val kind = BY_OPEN[tag.keywordType] ?: return null
            return if (kind == SET && tag.hasAssignment) null else kind
        }

        /** The block [tag] closes, or null. */
        fun closedBy(tag: JinjaScannedTag): JinjaBlockKind? = BY_CLOSE[tag.keywordType]
    }
}

/**
 * One `{% … %}` tag found by [JinjaTagScanner], with the spelling details an inserted end tag mirrors.
 *
 * @property range from `{%` to after `%}` (to the end of the scanned text for a tag cut off by it).
 * @property keywordType the token type of the tag name (`IF_KEYWORD`, `ENDFOR_KEYWORD` …), null for `{% %}` or a
 *   tag that starts with something other than a name.
 * @property startDelimiter `{%`, `{%-` or `{%+` as written.
 * @property endDelimiter `%}`, `-%}` or `+%}` as written; null when the tag is not closed.
 * @property gapAfterStart the whitespace between the start delimiter and the tag name (`{%   if` keeps its three spaces).
 * @property gapBeforeEnd the whitespace before the end delimiter.
 * @property hasAssignment the tag contains a top-level `=` (an inline `set`, keyword arguments …).
 */
class JinjaScannedTag(
    val range: TextRange,
    val keywordType: IElementType?,
    val startDelimiter: String,
    val endDelimiter: String?,
    val gapAfterStart: String,
    val gapBeforeEnd: String,
    val hasAssignment: Boolean,
) {
    val isClosed: Boolean
        get() = endDelimiter != null

    /**
     * The end tag of [kind] spelled like this tag: the same delimiters (whitespace markers included) and the same
     * spacing, so `{%- if x -%}` gives `{%- endif -%}` and `{%   for … %}` gives `{%   endfor %}`. Spacing that
     * spans lines becomes one space.
     */
    fun endTagText(kind: JinjaBlockKind): String =
        startDelimiter + singleLine(gapAfterStart) + kind.endTagName + singleLine(gapBeforeEnd) + (endDelimiter ?: "%}")

    private fun singleLine(gap: String): String = if ('\n' in gap || '\r' in gap) " " else gap

    override fun toString(): String = "JinjaScannedTag($range, $keywordType)"
}

/** A token of the template lexer: its type and document range. */
data class JinjaScannedToken(val type: IElementType, val start: Int, val end: Int)

/**
 * Lexes a stretch of document text in Jinja template mode (a template file, or a YAML scalar of an Ansible file) for
 * the typing assistance of [AnsibleJinjaTypedHandler], [AnsibleJinjaBackspaceHandler] and [AnsibleJinjaEnterHandler].
 * It works on the raw document text, so it needs neither a committed document nor PSI, and it only reads.
 *
 * All offsets are document offsets.
 */
object JinjaTagScanner {
    /** The token covering [offset] (`start <= offset < end`) in `text[from, to)`, or null outside that range. */
    fun tokenAt(text: CharSequence, from: Int, to: Int, offset: Int): JinjaScannedToken? {
        if (offset < from || offset >= to) return null
        val lexer = AnsibleJinjaLexer(JinjaLexMode.TEMPLATE)
        lexer.start(text, from, to, 0)
        while (true) {
            val type = lexer.tokenType ?: return null
            if (lexer.tokenEnd > offset) return JinjaScannedToken(type, lexer.tokenStart, lexer.tokenEnd)
            lexer.advance()
        }
    }

    /** The `{% … %}` tags of `text[from, to)` in source order (raw bodies are skipped, as Jinja skips them). */
    fun tags(text: CharSequence, from: Int, to: Int): List<JinjaScannedTag> {
        val tags = ArrayList<JinjaScannedTag>()
        val lexer = AnsibleJinjaLexer(JinjaLexMode.TEMPLATE)
        lexer.start(text, from, to, 0)
        while (lexer.tokenType != null) {
            if (lexer.tokenType == T.BLOCK_START) tags += readTag(lexer, text, to) else lexer.advance()
        }
        return tags
    }

    /**
     * How many blocks of [kind] are still open after [tags]: each opener opens one, each closer closes the innermost
     * open one, and a closer with nothing open (a stray end tag above the opener) is ignored. Positive when an end tag
     * is missing, e.g. for a new `{% if %}` inside an `if` whose `{% endif %}` it would otherwise take over.
     */
    fun unclosed(tags: List<JinjaScannedTag>, kind: JinjaBlockKind): Int {
        var open = 0
        for (tag in tags) {
            if (JinjaBlockKind.openedBy(tag) == kind) open++ else if (JinjaBlockKind.closedBy(tag) == kind && open > 0) open--
        }
        return open
    }

    /** Reads one tag; [lexer] is at its `{%` and ends after it. */
    private fun readTag(lexer: AnsibleJinjaLexer, text: CharSequence, to: Int): JinjaScannedTag {
        val start = lexer.tokenStart
        val startDelimiter = text.subSequence(start, lexer.tokenEnd).toString()
        lexer.advance()
        var gapAfterStart = ""
        var gapBeforeEnd = ""
        var keywordType: IElementType? = null
        var endDelimiter: String? = null
        var hasAssignment = false
        var depth = 0
        var first = true
        var end = to
        while (true) {
            val type = lexer.tokenType ?: break
            val tokenText = text.subSequence(lexer.tokenStart, lexer.tokenEnd)
            when {
                type == T.BLOCK_END -> {
                    endDelimiter = tokenText.toString()
                    end = lexer.tokenEnd
                    lexer.advance()
                    break
                }
                // the tag is cut off (a missing `%}`): the next tag or the outer text starts after it
                type == T.BLOCK_START || type == T.VAR_START || type == T.TEXT || type == T.COMMENT -> {
                    end = lexer.tokenStart
                    break
                }
                type == T.WHITE_SPACE -> {
                    if (first) gapAfterStart = tokenText.toString()
                    gapBeforeEnd = tokenText.toString()
                }
                else -> {
                    if (first) keywordType = type
                    first = false
                    gapBeforeEnd = ""
                    when (type) {
                        T.LPAREN, T.LBRACKET, T.LBRACE -> depth++
                        T.RPAREN, T.RBRACKET, T.RBRACE -> depth = maxOf(0, depth - 1)
                        T.ASSIGN -> if (depth == 0) hasAssignment = true
                    }
                }
            }
            lexer.advance()
        }
        return JinjaScannedTag(
            TextRange(start, maxOf(start, end)),
            keywordType,
            startDelimiter,
            endDelimiter,
            gapAfterStart,
            if (endDelimiter == null) "" else gapBeforeEnd,
            hasAssignment,
        )
    }
}
