package de.terletzkiy.ansibility.lang.jinja.lexer

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.ASSIGN
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.BAD_CHARACTER
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.BLOCK_END
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.BLOCK_START
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.CLAUSE_KEYWORDS
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.COLON
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.COMMA
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.COMMENT
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.DIV
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.DOT
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.EQEQ
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.EXPRESSION_KEYWORDS
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.FILTER_KEYWORD
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.FILTER_NAME
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.FLOAT
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.FLOORDIV
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.GE
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.GT
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.IDENTIFIER
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.INTEGER
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.IS_KEYWORD
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.LBRACE
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.LBRACKET
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.LE
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.LPAREN
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.LT
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.MINUS
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.MOD
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.MUL
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.NE
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.NOT_KEYWORD
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.PIPE
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.PLUS
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.POW
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.RAW_KEYWORD
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.RAW_TEXT
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.RBRACE
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.RBRACKET
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.RPAREN
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.SEMICOLON
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.STATEMENT_KEYWORDS
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.STRING
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.TEST_NAME
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.TEXT
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.TILDE
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.VAR_END
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.VAR_START
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes.WHITE_SPACE

/**
 * A hand-written, restartable lexer for Ansible's Jinja2 (plan A.5). It follows `jinja2/lexer.py` 3.1 with the
 * default delimiters and is tolerant: every input lexes, and [BAD_CHARACTER] appears only for characters Jinja itself
 * rejects.
 *
 * In [JinjaLexMode.TEMPLATE] mode it produces [TEXT] outside tags and lexes
 * - `{{ … }}` and `{% … %}` including the whitespace markers `{{-`, `-}}`, `{%-`, `-%}`, `{%+`, `+%}`;
 * - `{# … #}` (also `{#- … -#}`) as one [COMMENT] token;
 * - the body of `{% raw %}…{% endraw %}` as one [RAW_TEXT] token;
 * - loose spacing such as `{% endfor%}` and `{%   if %}`.
 *
 * As in Jinja, `}}` closes an output tag only when no `{` is open, so `{{ {'a': {'b': 1}} }}` works, and `%}` always
 * closes a statement. In [JinjaLexMode.EXPRESSION] mode the whole input is one expression.
 *
 * **State.** [getState] is the state at the start of the current token, and restarting with
 * `start(buffer, tokenStart, end, state)` reproduces the remaining tokens exactly. Editor highlighters restart
 * incrementally only where the state is 0, so state 0 marks exactly the positions that no earlier token depends on:
 * outer [TEXT] and the tags that follow it, except the `{% endraw %}` tag, and in expression mode only the start. The
 * state packs:
 * - bits 0–1: where the lexer is (outer text or bare expression, `{{ }}`, `{% %}`, raw body);
 * - bits 2–5: the token context, which decides whether the next name is a filter, test, attribute, tag name or
 *   ordinary name;
 * - bit 6: the tag so far is `{% raw`;
 * - bit 7: right after a raw body;
 * - bits 8–15: the number of open `{` inside `{{ }}` (capped at 255);
 * - bit 16: inside an expression-mode input, after its first token.
 */
class AnsibleJinjaLexer @JvmOverloads constructor(
    /** Whether the input is a template or a single bare expression. */
    val mode: JinjaLexMode = JinjaLexMode.TEMPLATE,
) : LexerBase() {
    private var buffer: CharSequence = ""
    private var bufferEnd = 0
    private var tokenType: IElementType? = null
    private var tokenStart = 0
    private var tokenEnd = 0

    /** The state in effect at [tokenStart], returned by [getState]. */
    private var tokenState = 0

    /** The state in effect at [tokenEnd], i.e. for the next token. */
    private var nextState = 0

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.bufferEnd = endOffset
        this.tokenEnd = startOffset
        this.nextState = initialState
        locateToken()
    }

    override fun getState(): Int = tokenState

    override fun getTokenType(): IElementType? = tokenType

    override fun getTokenStart(): Int = tokenStart

    override fun getTokenEnd(): Int = tokenEnd

    override fun advance() {
        locateToken()
    }

    override fun getBufferSequence(): CharSequence = buffer

    override fun getBufferEnd(): Int = bufferEnd

    private fun locateToken() {
        tokenStart = tokenEnd
        tokenState = nextState
        if (tokenStart >= bufferEnd) {
            tokenType = null
            return
        }
        when (nextState and SUB_MASK) {
            SUB_TOP -> if (mode == JinjaLexMode.EXPRESSION) lexCode(SUB_TOP) else lexText()
            SUB_VAR -> lexCode(SUB_VAR)
            SUB_BLOCK -> lexCode(SUB_BLOCK)
            else -> lexRaw()
        }
    }

    private fun emit(type: IElementType, end: Int, state: Int) {
        tokenType = type
        tokenEnd = end
        // in expression mode only the very start is a restart point: strings may look ahead past later tokens
        nextState = if (mode == JinjaLexMode.EXPRESSION) state or IN_EXPRESSION else state
    }

    // ------------------------------------------------------------------------------------------------ outer text

    private fun lexText() {
        val p = tokenStart
        if (buffer[p] == '{') {
            when (at(p + 1)) {
                '{' -> return emit(VAR_START, p + 2 + markerLength(p + 2), SUB_VAR)
                '%' -> return emit(BLOCK_START, p + 2 + markerLength(p + 2), SUB_BLOCK or (CTX_TAG_NAME shl CTX_SHIFT))
                '#' -> return emit(COMMENT, commentEnd(p + 2), SUB_TOP)
            }
        }
        var i = p + 1
        while (i < bufferEnd) {
            if (buffer[i] == '{') {
                val next = at(i + 1)
                if (next == '{' || next == '%' || next == '#') break
            }
            i++
        }
        emit(TEXT, i, SUB_TOP)
    }

    private fun markerLength(offset: Int): Int = if (at(offset) == '-' || at(offset) == '+') 1 else 0

    private fun commentEnd(from: Int): Int {
        var i = from
        while (i + 1 < bufferEnd) {
            if (buffer[i] == '#' && buffer[i + 1] == '}') return i + 2
            i++
        }
        return bufferEnd
    }

    /**
     * The body of `{% raw %}`: everything up to the next complete `{% endraw %}` tag, which is then lexed normally.
     * The body's end depends on the text of that tag, so its `{%` gets the non-zero state [AFTER_RAW]: an editor
     * highlighter must not restart there, but before the raw block.
     */
    private fun lexRaw() {
        val close = endRawTagStart(tokenStart)
        if (close == tokenStart) {
            tokenState = SUB_TOP or AFTER_RAW
            nextState = tokenState
            lexText()
            return
        }
        emit(RAW_TEXT, close, if (close < bufferEnd) SUB_TOP or AFTER_RAW else SUB_RAW)
    }

    private fun endRawTagStart(from: Int): Int {
        var i = from
        while (i + 1 < bufferEnd) {
            if (buffer[i] == '{' && buffer[i + 1] == '%' && isEndRawTag(i + 2)) return i
            i++
        }
        return bufferEnd
    }

    /** Whether `[-+]? \s* endraw \s* [-+]? %}` starts at [from] (the text after `{%`). */
    private fun isEndRawTag(from: Int): Boolean {
        var i = from + markerLength(from)
        while (i < bufferEnd && isWhitespace(buffer[i])) i++
        if (!regionMatches(i, "endraw")) return false
        i += "endraw".length
        while (i < bufferEnd && isWhitespace(buffer[i])) i++
        i += markerLength(i)
        return at(i) == '%' && at(i + 1) == '}'
    }

    // ------------------------------------------------------------------------------------------------ code

    /** Lexes one token inside `{{ }}` ([SUB_VAR]), `{% %}` ([SUB_BLOCK]) or a bare expression ([SUB_TOP]). */
    private fun lexCode(sub: Int) {
        val p = tokenStart
        val c = buffer[p]
        val state = nextState
        if (isWhitespace(c)) {
            var i = p + 1
            while (i < bufferEnd && isWhitespace(buffer[i])) i++
            return emit(WHITE_SPACE, i, state)
        }
        val depth = if (sub == SUB_VAR) (state ushr DEPTH_SHIFT) and MAX_DEPTH else 0
        if (sub == SUB_VAR && depth == 0) {
            if (c == '}' && at(p + 1) == '}') return emit(VAR_END, p + 2, SUB_TOP)
            if (c == '-' && at(p + 1) == '}' && at(p + 2) == '}') return emit(VAR_END, p + 3, SUB_TOP)
        }
        if (sub == SUB_BLOCK) {
            val after = if (state and RAW_PENDING != 0) SUB_RAW else SUB_TOP
            if (c == '%' && at(p + 1) == '}') return emit(BLOCK_END, p + 2, after)
            if ((c == '-' || c == '+') && at(p + 1) == '%' && at(p + 2) == '}') return emit(BLOCK_END, p + 3, after)
        }
        val context = (state ushr CTX_SHIFT) and CTX_BITS
        when {
            isNameStart(c) -> lexName(sub, context, depth)
            c in '0'..'9' -> {
                val end = numberEnd(p, integerOnly = context == CTX_AFTER_DOT)
                emit(numberType(p, end), end, compose(sub, CTX_NONE, false, depth))
            }
            c == '\'' || c == '"' -> emit(STRING, stringEnd(p, sub), compose(sub, CTX_NONE, false, depth))
            else -> lexOperator(sub, context, depth)
        }
    }

    private fun compose(sub: Int, context: Int, rawPending: Boolean, depth: Int): Int {
        var state = sub or (context shl CTX_SHIFT)
        if (rawPending) state = state or RAW_PENDING
        if (sub == SUB_VAR) state = state or (depth shl DEPTH_SHIFT)
        return state
    }

    private fun lexName(sub: Int, context: Int, depth: Int) {
        val p = tokenStart
        var end = p + 1
        while (end < bufferEnd && isNamePart(buffer[end])) end++
        var nextContext = CTX_NONE
        val type: IElementType = when (context) {
            CTX_AFTER_PIPE, CTX_FILTER_DOT -> FILTER_NAME.also { nextContext = CTX_AFTER_FILTER }
            CTX_AFTER_IS ->
                if (end - p == 3 && regionMatches(p, "not")) NOT_KEYWORD.also { nextContext = CTX_AFTER_IS_NOT }
                else TEST_NAME.also { nextContext = CTX_AFTER_TEST }
            CTX_AFTER_IS_NOT, CTX_TEST_DOT -> TEST_NAME.also { nextContext = CTX_AFTER_TEST }
            CTX_AFTER_DOT -> IDENTIFIER
            else -> {
                val keyword = keywordAt(p, end, sub, context)
                when (keyword) {
                    null -> IDENTIFIER
                    IS_KEYWORD -> keyword.also { nextContext = CTX_AFTER_IS }
                    // `{% filter upper %}`: the tag's argument is a filter name
                    FILTER_KEYWORD -> keyword.also { nextContext = CTX_AFTER_PIPE }
                    else -> keyword
                }
            }
        }
        val rawPending = type == RAW_KEYWORD && context == CTX_TAG_NAME
        emit(type, end, compose(sub, nextContext, rawPending, depth))
    }

    private fun keywordAt(start: Int, end: Int, sub: Int, context: Int): IElementType? {
        // `recursive=True`, `set block = …`, `namespace(found=false)`: a name being assigned is never a keyword
        if (followedByAssignment(end)) return null
        val word = buffer.subSequence(start, end).toString()
        return when {
            sub == SUB_BLOCK && context == CTX_TAG_NAME -> STATEMENT_KEYWORDS[word] ?: EXPRESSION_KEYWORDS[word]
            sub == SUB_BLOCK -> CLAUSE_KEYWORDS[word] ?: EXPRESSION_KEYWORDS[word]
            else -> EXPRESSION_KEYWORDS[word]
        }
    }

    private fun followedByAssignment(from: Int): Boolean {
        var i = from
        while (i < bufferEnd && isWhitespace(buffer[i])) i++
        return at(i) == '=' && at(i + 1) != '='
    }

    private fun lexOperator(sub: Int, context: Int, depth: Int) {
        val p = tokenStart
        val next = at(p + 1)
        var nextContext = CTX_NONE
        var nextDepth = depth
        var length = 1
        val type: IElementType = when (buffer[p]) {
            '+' -> PLUS
            '-' -> MINUS
            '~' -> TILDE
            ',' -> COMMA
            ':' -> COLON
            ';' -> SEMICOLON
            '%' -> MOD
            '(' -> LPAREN
            ')' -> RPAREN
            '[' -> LBRACKET
            ']' -> RBRACKET
            '*' -> if (next == '*') POW.also { length = 2 } else MUL
            '/' -> if (next == '/') FLOORDIV.also { length = 2 } else DIV
            '=' -> if (next == '=') EQEQ.also { length = 2 } else ASSIGN
            '<' -> if (next == '=') LE.also { length = 2 } else LT
            '>' -> if (next == '=') GE.also { length = 2 } else GT
            '!' -> if (next == '=') NE.also { length = 2 } else BAD_CHARACTER
            '|' -> PIPE.also { nextContext = CTX_AFTER_PIPE }
            '.' -> DOT.also {
                nextContext = when (context) {
                    CTX_AFTER_FILTER -> CTX_FILTER_DOT
                    CTX_AFTER_TEST -> CTX_TEST_DOT
                    else -> CTX_AFTER_DOT
                }
            }
            '{' -> LBRACE.also { nextDepth = minOf(depth + 1, MAX_DEPTH) }
            '}' -> RBRACE.also { nextDepth = maxOf(depth - 1, 0) }
            else -> BAD_CHARACTER.also {
                length = Character.charCount(Character.codePointAt(buffer, p)).coerceAtMost(bufferEnd - p)
            }
        }
        emit(type, p + length, compose(sub, nextContext, false, nextDepth))
    }

    // ------------------------------------------------------------------------------------------------ literals

    /**
     * Integers (`42`, `1_000`, `0x1F`, `0o17`, `0b101`) and floats (`1.5`, `2e10`, `1.5e-3`) as in Jinja 3. After a
     * dot only an integer is read, so `item.0.name` is `item` `.` `0` `.` `name`, as in Jinja.
     */
    private fun numberEnd(start: Int, integerOnly: Boolean): Int {
        if (buffer[start] == '0') {
            val radix = when (at(start + 1)) {
                'x', 'X' -> 16
                'o', 'O' -> 8
                'b', 'B' -> 2
                else -> 0
            }
            if (radix != 0) {
                val end = digitsEnd(start + 2, radix, leadingUnderscore = true)
                if (end > start + 2) return end
            }
        }
        var i = digitsEnd(start, 10, leadingUnderscore = false)
        if (integerOnly) return i
        if (at(i) == '.' && isDigit(at(i + 1), 10)) i = digitsEnd(i + 1, 10, leadingUnderscore = false)
        if (at(i) == 'e' || at(i) == 'E') {
            var j = i + 1
            if (at(j) == '+' || at(j) == '-') j++
            if (isDigit(at(j), 10)) i = digitsEnd(j, 10, leadingUnderscore = false)
        }
        return i
    }

    /** [FLOAT] for a decimal number with a fraction or an exponent, [INTEGER] otherwise (`0x1E` is an integer). */
    private fun numberType(start: Int, end: Int): IElementType {
        val prefixed = end - start > 2 && buffer[start] == '0' && buffer[start + 1].lowercaseChar() in "xob"
        if (prefixed) return INTEGER
        for (i in start until end) {
            val c = buffer[i]
            if (c == '.' || c == 'e' || c == 'E') return FLOAT
        }
        return INTEGER
    }

    /** Digits of [radix] with single underscores between them; returns [from] when there is no digit. */
    private fun digitsEnd(from: Int, radix: Int, leadingUnderscore: Boolean): Int {
        var i = from
        if (leadingUnderscore && at(i) == '_' && isDigit(at(i + 1), radix)) i++
        if (!isDigit(at(i), radix)) return from
        i++
        while (true) {
            i = when {
                isDigit(at(i), radix) -> i + 1
                at(i) == '_' && isDigit(at(i + 1), radix) -> i + 2
                else -> return i
            }
        }
    }

    /**
     * The end of a string literal starting at [start]; backslash escapes are skipped. At a line break the string goes
     * on only if its closing quote comes before the next tag end (`}}` or `%}`), which keeps every multi-line string
     * Jinja accepts. Otherwise it is unterminated and ends at the line break, so a missing quote costs at most the rest
     * of its line and the tag around it.
     *
     * The decision never reads past the end of the tag the string is in (a tag end found by the scan either ends the
     * tag later or lies inside it), so incremental relexing stays exact: text after the tag cannot change the string.
     */
    private fun stringEnd(start: Int, sub: Int): Int {
        val quote = buffer[start]
        var i = start + 1
        while (i < bufferEnd) {
            when (buffer[i]) {
                '\\' -> i += 2
                quote -> return i + 1
                '\n' -> {
                    val closing = closingQuoteBeforeTagEnd(i, quote, sub)
                    return if (closing < 0) i else closing + 1
                }
                else -> i++
            }
        }
        return bufferEnd
    }

    /** The closing [quote] after [from] if no tag end of [sub] comes first; -1 otherwise. */
    private fun closingQuoteBeforeTagEnd(from: Int, quote: Char, sub: Int): Int {
        // `}}` ends `{{ }}`, `%}` ends `{% %}`; a bare expression has no tag end
        val marker: Char? = when (sub) {
            SUB_VAR -> '}'
            SUB_BLOCK -> '%'
            else -> null
        }
        var i = from
        while (i < bufferEnd) {
            val ch = buffer[i]
            when {
                ch == '\\' -> i++
                ch == quote -> return i
                ch == marker && at(i + 1) == '}' -> return -1
            }
            i++
        }
        return -1
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private fun at(offset: Int): Char = if (offset < bufferEnd) buffer[offset] else NO_CHAR

    private fun regionMatches(offset: Int, text: String): Boolean {
        if (offset + text.length > bufferEnd) return false
        for (k in text.indices) if (buffer[offset + k] != text[k]) return false
        return true
    }

    private companion object {
        const val NO_CHAR = '\u0000'

        const val SUB_MASK = 0x3

        /** Outer text (template mode) or the bare expression (expression mode). Always state 0 for outer text. */
        const val SUB_TOP = 0
        const val SUB_VAR = 1
        const val SUB_BLOCK = 2
        const val SUB_RAW = 3

        const val CTX_SHIFT = 2
        const val CTX_BITS = 0xF
        const val CTX_NONE = 0

        /** Directly after `{%`: the next name is the tag name. */
        const val CTX_TAG_NAME = 1
        const val CTX_AFTER_DOT = 2
        const val CTX_AFTER_PIPE = 3
        const val CTX_AFTER_FILTER = 4
        const val CTX_FILTER_DOT = 5
        const val CTX_AFTER_IS = 6
        const val CTX_AFTER_IS_NOT = 7
        const val CTX_AFTER_TEST = 8
        const val CTX_TEST_DOT = 9

        const val RAW_PENDING = 1 shl 6

        /** Outer text right after a raw body, i.e. at its `{% endraw %}`: not a restart point. */
        const val AFTER_RAW = 1 shl 7

        /** Set on every state after the first token in expression mode, so the start is the only restart point. */
        const val IN_EXPRESSION = 1 shl 16

        const val DEPTH_SHIFT = 8
        const val MAX_DEPTH = 0xFF

        fun isWhitespace(c: Char): Boolean = Character.isWhitespace(c) || Character.isSpaceChar(c)

        fun isNameStart(c: Char): Boolean = c == '_' || Character.isLetter(c)

        fun isNamePart(c: Char): Boolean = c == '_' || Character.isLetterOrDigit(c)

        fun isDigit(c: Char, radix: Int): Boolean = when (radix) {
            2 -> c == '0' || c == '1'
            8 -> c in '0'..'7'
            10 -> c in '0'..'9'
            else -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
        }
    }
}
