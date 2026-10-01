package de.terletzkiy.ansibility.lang.jinja.lexer

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaLanguage

/** A token of [AnsibleJinjaLanguage]. [toString] is the debug name, which token dumps in tests print. */
class AnsibleJinjaTokenType(debugName: String) : IElementType(debugName, AnsibleJinjaLanguage)

/**
 * Token types produced by [AnsibleJinjaLexer], one element type per token kind.
 *
 * Keywords are contextual, as in Jinja itself, where every keyword is lexed as a name and only the parser gives it
 * meaning:
 * - [EXPRESSION_KEYWORDS] (`and`, `or`, `not`, `in`, `is`, `if`, `else` and the constants) are keywords wherever an
 *   expression can stand;
 * - [STATEMENT_KEYWORDS] (`for`, `endfor`, `set`, `macro`, `raw` …) only as the tag name, the first token after `{%`;
 * - [CLAUSE_KEYWORDS] (`as`, `import`, `recursive`, `with`) only later inside a `{% … %}` tag.
 *
 * Everywhere else those words are [IDENTIFIER]s, so `{{ raw }}`, `{{ item.block }}` and `combine(recursive=True)`
 * name variables, attributes and keyword arguments. A name directly after `|` (and each dotted segment of an FQCN
 * filter such as `ansible.builtin.splitext`) is a [FILTER_NAME]; a name after `is` or `is not` is a [TEST_NAME].
 */
object AnsibleJinjaTokenTypes {
    // ---------------------------------------------------------------- template level

    /** Text outside any Jinja tag: the outer language (nginx, YAML, shell …). */
    @JvmField val TEXT = AnsibleJinjaTokenType("TEXT")

    /** Text between `{% raw %}` and `{% endraw %}`, copied to the output verbatim (often Go templates). */
    @JvmField val RAW_TEXT = AnsibleJinjaTokenType("RAW_TEXT")

    /** A whole `{# … #}` comment including its delimiters and whitespace markers (`{#- … -#}`). */
    @JvmField val COMMENT = AnsibleJinjaTokenType("COMMENT")

    /** `{{`, `{{-` or `{{+`. */
    @JvmField val VAR_START = AnsibleJinjaTokenType("VAR_START")

    /** `}}` or `-}}`. */
    @JvmField val VAR_END = AnsibleJinjaTokenType("VAR_END")

    /** `{%`, `{%-` or `{%+`. */
    @JvmField val BLOCK_START = AnsibleJinjaTokenType("BLOCK_START")

    /** `%}`, `-%}` or `+%}`. */
    @JvmField val BLOCK_END = AnsibleJinjaTokenType("BLOCK_END")

    // ---------------------------------------------------------------- names and literals

    /** A variable, attribute, macro, parameter or keyword-argument name. */
    @JvmField val IDENTIFIER = AnsibleJinjaTokenType("IDENTIFIER")

    /** A filter name after `|`, or one dotted segment of an FQCN filter name. */
    @JvmField val FILTER_NAME = AnsibleJinjaTokenType("FILTER_NAME")

    /** A test name after `is` / `is not`, or one dotted segment of an FQCN test name. */
    @JvmField val TEST_NAME = AnsibleJinjaTokenType("TEST_NAME")

    /** A single- or double-quoted string with backslash escapes; an unterminated string ends at the line end. */
    @JvmField val STRING = AnsibleJinjaTokenType("STRING")

    /** An integer: `42`, `1_000`, `0x1F`, `0o17`, `0b101`. */
    @JvmField val INTEGER = AnsibleJinjaTokenType("INTEGER")

    /** A float: `1.5`, `2e10`, `1_000.25e-3`. */
    @JvmField val FLOAT = AnsibleJinjaTokenType("FLOAT")

    // ---------------------------------------------------------------- expression keywords

    @JvmField val AND_KEYWORD = AnsibleJinjaTokenType("AND_KEYWORD")
    @JvmField val OR_KEYWORD = AnsibleJinjaTokenType("OR_KEYWORD")
    @JvmField val NOT_KEYWORD = AnsibleJinjaTokenType("NOT_KEYWORD")
    @JvmField val IN_KEYWORD = AnsibleJinjaTokenType("IN_KEYWORD")
    @JvmField val IS_KEYWORD = AnsibleJinjaTokenType("IS_KEYWORD")
    @JvmField val IF_KEYWORD = AnsibleJinjaTokenType("IF_KEYWORD")
    @JvmField val ELSE_KEYWORD = AnsibleJinjaTokenType("ELSE_KEYWORD")

    /** `true` or `True`. */
    @JvmField val TRUE_KEYWORD = AnsibleJinjaTokenType("TRUE_KEYWORD")

    /** `false` or `False`. */
    @JvmField val FALSE_KEYWORD = AnsibleJinjaTokenType("FALSE_KEYWORD")

    /** `none` or `None`. */
    @JvmField val NONE_KEYWORD = AnsibleJinjaTokenType("NONE_KEYWORD")

    // ---------------------------------------------------------------- statement keywords (tag names)

    @JvmField val ELIF_KEYWORD = AnsibleJinjaTokenType("ELIF_KEYWORD")
    @JvmField val ENDIF_KEYWORD = AnsibleJinjaTokenType("ENDIF_KEYWORD")
    @JvmField val FOR_KEYWORD = AnsibleJinjaTokenType("FOR_KEYWORD")
    @JvmField val ENDFOR_KEYWORD = AnsibleJinjaTokenType("ENDFOR_KEYWORD")
    @JvmField val SET_KEYWORD = AnsibleJinjaTokenType("SET_KEYWORD")
    @JvmField val ENDSET_KEYWORD = AnsibleJinjaTokenType("ENDSET_KEYWORD")
    @JvmField val MACRO_KEYWORD = AnsibleJinjaTokenType("MACRO_KEYWORD")
    @JvmField val ENDMACRO_KEYWORD = AnsibleJinjaTokenType("ENDMACRO_KEYWORD")
    @JvmField val CALL_KEYWORD = AnsibleJinjaTokenType("CALL_KEYWORD")
    @JvmField val ENDCALL_KEYWORD = AnsibleJinjaTokenType("ENDCALL_KEYWORD")
    @JvmField val FILTER_KEYWORD = AnsibleJinjaTokenType("FILTER_KEYWORD")
    @JvmField val ENDFILTER_KEYWORD = AnsibleJinjaTokenType("ENDFILTER_KEYWORD")
    @JvmField val WITH_KEYWORD = AnsibleJinjaTokenType("WITH_KEYWORD")
    @JvmField val ENDWITH_KEYWORD = AnsibleJinjaTokenType("ENDWITH_KEYWORD")
    @JvmField val INCLUDE_KEYWORD = AnsibleJinjaTokenType("INCLUDE_KEYWORD")
    @JvmField val IMPORT_KEYWORD = AnsibleJinjaTokenType("IMPORT_KEYWORD")
    @JvmField val FROM_KEYWORD = AnsibleJinjaTokenType("FROM_KEYWORD")
    @JvmField val EXTENDS_KEYWORD = AnsibleJinjaTokenType("EXTENDS_KEYWORD")
    @JvmField val BLOCK_KEYWORD = AnsibleJinjaTokenType("BLOCK_KEYWORD")
    @JvmField val ENDBLOCK_KEYWORD = AnsibleJinjaTokenType("ENDBLOCK_KEYWORD")
    @JvmField val RAW_KEYWORD = AnsibleJinjaTokenType("RAW_KEYWORD")
    @JvmField val ENDRAW_KEYWORD = AnsibleJinjaTokenType("ENDRAW_KEYWORD")
    @JvmField val DO_KEYWORD = AnsibleJinjaTokenType("DO_KEYWORD")
    @JvmField val BREAK_KEYWORD = AnsibleJinjaTokenType("BREAK_KEYWORD")
    @JvmField val CONTINUE_KEYWORD = AnsibleJinjaTokenType("CONTINUE_KEYWORD")

    // ---------------------------------------------------------------- clause keywords (inside a statement)

    @JvmField val AS_KEYWORD = AnsibleJinjaTokenType("AS_KEYWORD")
    @JvmField val RECURSIVE_KEYWORD = AnsibleJinjaTokenType("RECURSIVE_KEYWORD")

    // ---------------------------------------------------------------- operators

    @JvmField val PLUS = AnsibleJinjaTokenType("PLUS")
    @JvmField val MINUS = AnsibleJinjaTokenType("MINUS")
    @JvmField val MUL = AnsibleJinjaTokenType("MUL")
    @JvmField val DIV = AnsibleJinjaTokenType("DIV")
    @JvmField val FLOORDIV = AnsibleJinjaTokenType("FLOORDIV")
    @JvmField val MOD = AnsibleJinjaTokenType("MOD")
    @JvmField val POW = AnsibleJinjaTokenType("POW")

    /** `~`, string concatenation. */
    @JvmField val TILDE = AnsibleJinjaTokenType("TILDE")
    @JvmField val EQEQ = AnsibleJinjaTokenType("EQEQ")
    @JvmField val NE = AnsibleJinjaTokenType("NE")
    @JvmField val LT = AnsibleJinjaTokenType("LT")
    @JvmField val GT = AnsibleJinjaTokenType("GT")
    @JvmField val LE = AnsibleJinjaTokenType("LE")
    @JvmField val GE = AnsibleJinjaTokenType("GE")

    /** `=` in `set`, `with`, keyword arguments and macro parameter defaults. */
    @JvmField val ASSIGN = AnsibleJinjaTokenType("ASSIGN")

    /** `|`, the filter pipe. */
    @JvmField val PIPE = AnsibleJinjaTokenType("PIPE")

    // ---------------------------------------------------------------- punctuation

    @JvmField val DOT = AnsibleJinjaTokenType("DOT")
    @JvmField val COMMA = AnsibleJinjaTokenType("COMMA")
    @JvmField val COLON = AnsibleJinjaTokenType("COLON")

    /** `;`: lexically valid in Jinja, although no statement uses it. */
    @JvmField val SEMICOLON = AnsibleJinjaTokenType("SEMICOLON")
    @JvmField val LPAREN = AnsibleJinjaTokenType("LPAREN")
    @JvmField val RPAREN = AnsibleJinjaTokenType("RPAREN")
    @JvmField val LBRACKET = AnsibleJinjaTokenType("LBRACKET")
    @JvmField val RBRACKET = AnsibleJinjaTokenType("RBRACKET")
    @JvmField val LBRACE = AnsibleJinjaTokenType("LBRACE")
    @JvmField val RBRACE = AnsibleJinjaTokenType("RBRACE")

    // ---------------------------------------------------------------- platform tokens

    /** Whitespace inside a tag or an expression (outer text is always [TEXT]). */
    @JvmField val WHITE_SPACE: IElementType = TokenType.WHITE_SPACE

    /** A character Jinja's own lexer rejects, e.g. `$`, `?`, `#` or a lone `!` inside a tag. */
    @JvmField val BAD_CHARACTER: IElementType = TokenType.BAD_CHARACTER

    // ---------------------------------------------------------------- keyword tables

    /** Keywords wherever an expression can stand. */
    @JvmField
    val EXPRESSION_KEYWORDS: Map<String, IElementType> = mapOf(
        "and" to AND_KEYWORD, "or" to OR_KEYWORD, "not" to NOT_KEYWORD, "in" to IN_KEYWORD, "is" to IS_KEYWORD,
        "if" to IF_KEYWORD, "else" to ELSE_KEYWORD,
        "true" to TRUE_KEYWORD, "True" to TRUE_KEYWORD, "false" to FALSE_KEYWORD, "False" to FALSE_KEYWORD,
        "none" to NONE_KEYWORD, "None" to NONE_KEYWORD,
    )

    /** Keywords as the tag name of a `{% … %}` statement. */
    @JvmField
    val STATEMENT_KEYWORDS: Map<String, IElementType> = mapOf(
        "if" to IF_KEYWORD, "elif" to ELIF_KEYWORD, "else" to ELSE_KEYWORD, "endif" to ENDIF_KEYWORD,
        "for" to FOR_KEYWORD, "endfor" to ENDFOR_KEYWORD,
        "set" to SET_KEYWORD, "endset" to ENDSET_KEYWORD,
        "macro" to MACRO_KEYWORD, "endmacro" to ENDMACRO_KEYWORD,
        "call" to CALL_KEYWORD, "endcall" to ENDCALL_KEYWORD,
        "filter" to FILTER_KEYWORD, "endfilter" to ENDFILTER_KEYWORD,
        "with" to WITH_KEYWORD, "endwith" to ENDWITH_KEYWORD,
        "include" to INCLUDE_KEYWORD, "import" to IMPORT_KEYWORD, "from" to FROM_KEYWORD,
        "extends" to EXTENDS_KEYWORD, "block" to BLOCK_KEYWORD, "endblock" to ENDBLOCK_KEYWORD,
        "raw" to RAW_KEYWORD, "endraw" to ENDRAW_KEYWORD,
        "do" to DO_KEYWORD, "break" to BREAK_KEYWORD, "continue" to CONTINUE_KEYWORD,
    )

    /** Keywords after the tag name inside a `{% … %}` statement: `import … as`, `from … import`, `recursive`, `with context`. */
    @JvmField
    val CLAUSE_KEYWORDS: Map<String, IElementType> = mapOf(
        "as" to AS_KEYWORD, "import" to IMPORT_KEYWORD, "recursive" to RECURSIVE_KEYWORD, "with" to WITH_KEYWORD,
    )

    // ---------------------------------------------------------------- token sets

    @JvmField
    val KEYWORDS: TokenSet = TokenSet.create(
        *(EXPRESSION_KEYWORDS.values + STATEMENT_KEYWORDS.values + CLAUSE_KEYWORDS.values).distinct().toTypedArray(),
    )

    /** `true`/`True`, `false`/`False`, `none`/`None`. */
    @JvmField val CONSTANTS: TokenSet = TokenSet.create(TRUE_KEYWORD, FALSE_KEYWORD, NONE_KEYWORD)

    /** Tag delimiters, whitespace markers included. */
    @JvmField val DELIMITERS: TokenSet = TokenSet.create(VAR_START, VAR_END, BLOCK_START, BLOCK_END)

    @JvmField
    val OPERATORS: TokenSet = TokenSet.create(
        PLUS, MINUS, MUL, DIV, FLOORDIV, MOD, POW, TILDE, EQEQ, NE, LT, GT, LE, GE, ASSIGN, PIPE,
    )

    @JvmField val PARENTHESES: TokenSet = TokenSet.create(LPAREN, RPAREN)
    @JvmField val BRACKETS: TokenSet = TokenSet.create(LBRACKET, RBRACKET)
    @JvmField val BRACES: TokenSet = TokenSet.create(LBRACE, RBRACE)
    @JvmField val STRINGS: TokenSet = TokenSet.create(STRING)
    @JvmField val NUMBERS: TokenSet = TokenSet.create(INTEGER, FLOAT)
    @JvmField val COMMENTS: TokenSet = TokenSet.create(COMMENT)
    @JvmField val WHITESPACES: TokenSet = TokenSet.create(WHITE_SPACE)

    /** Text Jinja copies to the output unchanged: [TEXT] and [RAW_TEXT]. */
    @JvmField val OUTER_TEXT: TokenSet = TokenSet.create(TEXT, RAW_TEXT)
}
