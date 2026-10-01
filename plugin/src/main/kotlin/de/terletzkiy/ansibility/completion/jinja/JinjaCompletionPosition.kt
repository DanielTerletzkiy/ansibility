package de.terletzkiy.ansibility.completion.jinja

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.tree.IElementType
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaLexer
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes
import de.terletzkiy.ansibility.lang.jinja.lexer.JinjaLexMode

/** One accessor of a member chain: a constant attribute or key (`floating`, `'os_family'`, `0`) or a dynamic subscript. */
sealed interface Accessor {
    data class Const(val name: String) : Accessor

    /** `hostvars[inventory_hostname]`: a subscript whose key is an expression. */
    data object Dynamic : Accessor
}

/**
 * What the caret completes inside Jinja text (the analysed text of `vars.JinjaTextSites`), decided from the Jinja
 * lexer's tokens before the caret:
 * - [Name]: a variable name (possibly empty) where an expression can start: after `{{`, an operator, `(`, `,`, `[`,
 *   `in`, `if`, `not` …; [Name.bracketOwner] is set right after `chain[` so quoted keys of `chain` can be offered too;
 * - [Member]: an attribute after `root.path.`;
 * - [Key]: a quoted key after `root.path['` (either quote style).
 *
 * There is no position in outer template text, comments, `{% raw %}` bodies, string literals that are not subscript
 * keys, numbers, filter or test names, statement tag names, names being bound (`{% for x`, `{% set x`,
 * `{% macro m(a`, `import … as x`), or right after a complete operand (`{{ x i|` types an operator).
 */
sealed interface JinjaCompletionPosition {
    /** The text between the start of the completed word and the caret. */
    val prefix: String

    data class Name(override val prefix: String, val bracketOwner: Chain? = null) : JinjaCompletionPosition

    data class Member(val chain: Chain, override val prefix: String) : JinjaCompletionPosition

    data class Key(
        val chain: Chain,
        override val prefix: String,
        /** The quote character that opens the key. */
        val quote: Char,
        /** True when the closing quote is already there. */
        val closed: Boolean,
    ) : JinjaCompletionPosition

    /** A member chain: a root name and its accessors (`hostvars`, `[Dynamic]`). */
    data class Chain(val root: String, val path: List<Accessor>) {
        /** The constant accessors, or null when one of them is dynamic. */
        val constPath: List<String>? get() = path.map { (it as? Accessor.Const)?.name ?: return null }
    }

    companion object {
        /** The position at [caret] of [text], lexed in [mode]; null when the caret completes no variable. */
        fun at(text: CharSequence, caret: Int, mode: JinjaLexMode): JinjaCompletionPosition? = PositionParser(text, caret, mode).parse()
    }
}

/** A token of the analysed text. */
private class Token(val type: IElementType, val start: Int, val end: Int)

private class PositionParser(private val text: CharSequence, private val caret: Int, private val mode: JinjaLexMode) {
    /** The significant (non-whitespace) tokens of the tag (or expression) around the caret, up to the caret. */
    private val tokens = ArrayList<Token>()

    /** The token with `start < caret <= end`, whitespace included; null when the caret is at the start. */
    private var atCaret: Token? = null

    fun parse(): JinjaCompletionPosition? {
        if (caret < 0 || caret > text.length || !lex()) return null
        val current = atCaret ?: return positionAfter("")
        return when {
            current.type == AnsibleJinjaTokenTypes.STRING -> keyPosition(current)
            isWord(current.type) -> {
                if (current.end > caret && current.type != AnsibleJinjaTokenTypes.IDENTIFIER) return null
                tokens.removeAt(tokens.lastIndex)
                positionAfter(text.subSequence(current.start, caret).toString())
            }
            current.type == AnsibleJinjaTokenTypes.WHITE_SPACE -> positionAfter("")
            current.end == caret && current.type in PUNCTUATION_BEFORE_NAME -> positionAfter("")
            else -> null
        }
    }

    /** Lexes up to the caret; false when the caret is outside Jinja code (outer text, a comment, a raw body, a delimiter). */
    private fun lex(): Boolean {
        val lexer = AnsibleJinjaLexer(mode)
        lexer.start(text, 0, text.length, 0)
        var inCode = mode == JinjaLexMode.EXPRESSION
        while (true) {
            ProgressManager.checkCanceled()
            val type = lexer.tokenType ?: break
            val start = lexer.tokenStart
            val end = lexer.tokenEnd
            if (start >= caret) break
            val token = Token(type, start, end)
            when (type) {
                AnsibleJinjaTokenTypes.VAR_START, AnsibleJinjaTokenTypes.BLOCK_START -> {
                    tokens.clear()
                    tokens += token
                    inCode = true
                    if (end > caret) return false
                }
                AnsibleJinjaTokenTypes.VAR_END, AnsibleJinjaTokenTypes.BLOCK_END,
                AnsibleJinjaTokenTypes.TEXT, AnsibleJinjaTokenTypes.RAW_TEXT, AnsibleJinjaTokenTypes.COMMENT,
                -> {
                    tokens.clear()
                    inCode = false
                }
                AnsibleJinjaTokenTypes.WHITE_SPACE -> Unit
                else -> tokens += token
            }
            if (end >= caret) {
                atCaret = token
                break
            }
            lexer.advance()
        }
        return inCode
    }

    /** The position of a word [prefix] after the collected tokens. */
    private fun positionAfter(prefix: String): JinjaCompletionPosition? {
        val previous = tokens.lastOrNull() ?: return if (mode == JinjaLexMode.EXPRESSION) JinjaCompletionPosition.Name(prefix) else null
        return when (previous.type) {
            AnsibleJinjaTokenTypes.DOT -> {
                tokens.removeAt(tokens.lastIndex)
                chainBefore()?.let { JinjaCompletionPosition.Member(it, prefix) }
            }
            AnsibleJinjaTokenTypes.LBRACKET -> {
                if (!startsExpression(previous)) return null
                tokens.removeAt(tokens.lastIndex)
                JinjaCompletionPosition.Name(prefix, chainBefore())
            }
            // `x is not |` names a test
            AnsibleJinjaTokenTypes.NOT_KEYWORD ->
                if (tokens.getOrNull(tokens.lastIndex - 1)?.type != AnsibleJinjaTokenTypes.IS_KEYWORD && startsExpression(previous)) {
                    JinjaCompletionPosition.Name(prefix)
                } else {
                    null
                }
            else -> if (startsExpression(previous)) JinjaCompletionPosition.Name(prefix) else null
        }
    }

    /** A quoted subscript key: the caret inside a string literal right after `[`. */
    private fun keyPosition(string: Token): JinjaCompletionPosition? {
        val quote = text[string.start]
        val length = string.end - string.start
        val closed = length >= 2 && text[string.end - 1] == quote && text[string.end - 2] != '\\'
        if (closed && caret >= string.end) return null
        val prefix = text.subSequence(string.start + 1, caret).toString()
        if (prefix.any { !(it.isLetterOrDigit() || it == '_' || it == '-' || it == '.') }) return null
        tokens.removeAt(tokens.lastIndex)
        if (tokens.lastOrNull()?.type != AnsibleJinjaTokenTypes.LBRACKET) return null
        tokens.removeAt(tokens.lastIndex)
        val chain = chainBefore() ?: return null
        return JinjaCompletionPosition.Key(chain, prefix, quote, closed)
    }

    /**
     * The member chain that ends at the last collected token (`item.floating.ssl`, `hostvars[host]`,
     * `ansible_facts['default_ipv4']`), consuming its tokens. Null when the chain does not start at a plain name
     * (a call, a literal, a parenthesised expression, a filter result).
     */
    private fun chainBefore(): JinjaCompletionPosition.Chain? {
        val accessors = ArrayList<Accessor>()
        while (true) {
            val last = tokens.removeLastOrNull() ?: return null
            when (last.type) {
                AnsibleJinjaTokenTypes.IDENTIFIER, AnsibleJinjaTokenTypes.INTEGER -> {
                    val name = text.subSequence(last.start, last.end).toString()
                    if (tokens.lastOrNull()?.type == AnsibleJinjaTokenTypes.DOT) {
                        tokens.removeAt(tokens.lastIndex)
                        accessors += Accessor.Const(name)
                        continue
                    }
                    if (last.type == AnsibleJinjaTokenTypes.INTEGER) return null
                    return JinjaCompletionPosition.Chain(name, accessors.asReversed().toList())
                }
                AnsibleJinjaTokenTypes.RBRACKET -> accessors += subscript() ?: return null
                else -> return null
            }
        }
    }

    /** The accessor of a `[…]` whose `]` was just consumed; consumes the tokens up to its `[`. */
    private fun subscript(): Accessor? {
        var depth = 0
        val inner = ArrayList<Token>()
        while (true) {
            val token = tokens.removeLastOrNull() ?: return null
            if (token.type == AnsibleJinjaTokenTypes.RBRACKET) depth++
            if (token.type == AnsibleJinjaTokenTypes.LBRACKET) {
                if (depth == 0) break
                depth--
            }
            inner += token
        }
        val single = inner.singleOrNull() ?: return Accessor.Dynamic
        val literal = text.subSequence(single.start, single.end).toString()
        return when (single.type) {
            AnsibleJinjaTokenTypes.STRING -> Accessor.Const(unquote(literal))
            AnsibleJinjaTokenTypes.INTEGER -> Accessor.Const(literal)
            else -> Accessor.Dynamic
        }
    }

    /** True when a variable name may start right after [token]. */
    private fun startsExpression(token: Token): Boolean = when (token.type) {
        AnsibleJinjaTokenTypes.VAR_START, AnsibleJinjaTokenTypes.LPAREN, AnsibleJinjaTokenTypes.LBRACKET,
        AnsibleJinjaTokenTypes.LBRACE, AnsibleJinjaTokenTypes.COMMA, AnsibleJinjaTokenTypes.COLON,
        AnsibleJinjaTokenTypes.AND_KEYWORD, AnsibleJinjaTokenTypes.OR_KEYWORD, AnsibleJinjaTokenTypes.NOT_KEYWORD,
        AnsibleJinjaTokenTypes.IN_KEYWORD, AnsibleJinjaTokenTypes.IF_KEYWORD, AnsibleJinjaTokenTypes.ELSE_KEYWORD,
        AnsibleJinjaTokenTypes.ELIF_KEYWORD, AnsibleJinjaTokenTypes.INCLUDE_KEYWORD, AnsibleJinjaTokenTypes.EXTENDS_KEYWORD,
        AnsibleJinjaTokenTypes.IMPORT_KEYWORD, AnsibleJinjaTokenTypes.DO_KEYWORD, AnsibleJinjaTokenTypes.CALL_KEYWORD,
        -> !bindsName()
        AnsibleJinjaTokenTypes.PIPE -> false
        else -> AnsibleJinjaTokenTypes.OPERATORS.contains(token.type) && !bindsName()
    }

    /**
     * True inside a statement part that binds names rather than reading them: a `for` target list before `in`, a
     * `set` target before `=`, the parameter list of a `macro` (outside defaults), an `import … as`/`from … import`
     * name list.
     */
    private fun bindsName(): Boolean {
        val first = tokens.firstOrNull() ?: return false
        if (first.type != AnsibleJinjaTokenTypes.BLOCK_START) return false
        val statement = tokens.getOrNull(1) ?: return false
        val last = tokens.last()
        return when (statement.type) {
            AnsibleJinjaTokenTypes.FOR_KEYWORD -> tokens.none { it.type == AnsibleJinjaTokenTypes.IN_KEYWORD }
            AnsibleJinjaTokenTypes.SET_KEYWORD -> tokens.none { it.type == AnsibleJinjaTokenTypes.ASSIGN }
            AnsibleJinjaTokenTypes.MACRO_KEYWORD -> last.type != AnsibleJinjaTokenTypes.ASSIGN
            AnsibleJinjaTokenTypes.IMPORT_KEYWORD, AnsibleJinjaTokenTypes.FROM_KEYWORD ->
                tokens.drop(2).any { it.type == AnsibleJinjaTokenTypes.AS_KEYWORD || it.type == AnsibleJinjaTokenTypes.IMPORT_KEYWORD }
            else -> false
        }
    }

    private fun isWord(type: IElementType): Boolean =
        type == AnsibleJinjaTokenTypes.IDENTIFIER || type in AnsibleJinjaTokenTypes.EXPRESSION_KEYWORDS.values

    private fun unquote(literal: String): String {
        if (literal.length < 2) return literal
        val quote = literal.first()
        return if ((quote == '\'' || quote == '"') && literal.last() == quote) literal.substring(1, literal.length - 1) else literal
    }

    private companion object {
        /** Tokens right after which (caret at their end) a name or member may follow. */
        val PUNCTUATION_BEFORE_NAME: Set<IElementType> = setOf(
            AnsibleJinjaTokenTypes.VAR_START, AnsibleJinjaTokenTypes.BLOCK_START, AnsibleJinjaTokenTypes.DOT,
            AnsibleJinjaTokenTypes.LBRACKET, AnsibleJinjaTokenTypes.LPAREN, AnsibleJinjaTokenTypes.COMMA,
            AnsibleJinjaTokenTypes.COLON, AnsibleJinjaTokenTypes.LBRACE, AnsibleJinjaTokenTypes.PLUS,
            AnsibleJinjaTokenTypes.MINUS, AnsibleJinjaTokenTypes.MUL, AnsibleJinjaTokenTypes.DIV,
            AnsibleJinjaTokenTypes.FLOORDIV, AnsibleJinjaTokenTypes.MOD, AnsibleJinjaTokenTypes.POW,
            AnsibleJinjaTokenTypes.TILDE, AnsibleJinjaTokenTypes.EQEQ, AnsibleJinjaTokenTypes.NE,
            AnsibleJinjaTokenTypes.LT, AnsibleJinjaTokenTypes.GT, AnsibleJinjaTokenTypes.LE, AnsibleJinjaTokenTypes.GE,
            AnsibleJinjaTokenTypes.ASSIGN,
        )
    }
}
