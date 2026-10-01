package de.terletzkiy.ansibility.typeflow

import com.intellij.psi.tree.IElementType
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaLexer
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes as T
import de.terletzkiy.ansibility.lang.jinja.lexer.JinjaLexMode
import de.terletzkiy.ansibility.semantics.typeflow.JinjaToken
import de.terletzkiy.ansibility.semantics.typeflow.JinjaTokenKind
import de.terletzkiy.ansibility.semantics.typeflow.JinjaTokenizer

/**
 * Feeds :semantics' PSI-free template model ([de.terletzkiy.ansibility.semantics.typeflow.JinjaTemplate]) from the
 * plugin's Jinja lexer in TEMPLATE mode (the one lexer, checked token-for-token against jinja2 3.1.6). Whitespace
 * inside tags is dropped; filter and test names are plain names; every keyword keeps its text.
 */
object LexerJinjaTokenizer : JinjaTokenizer {
    private val PUNCTUATION = setOf(
        T.DOT, T.COMMA, T.COLON, T.SEMICOLON, T.LPAREN, T.RPAREN, T.LBRACKET, T.RBRACKET, T.LBRACE, T.RBRACE,
    )

    override fun tokenize(text: String): List<JinjaToken> {
        val lexer = AnsibleJinjaLexer(JinjaLexMode.TEMPLATE)
        lexer.start(text)
        val tokens = ArrayList<JinjaToken>()
        while (true) {
            val type = lexer.tokenType ?: break
            kindOf(type)?.let { tokens += JinjaToken(it, text.substring(lexer.tokenStart, lexer.tokenEnd), lexer.tokenStart) }
            lexer.advance()
        }
        return tokens
    }

    /** The token kind of a lexer token (or of a Jinja PSI leaf, see [PsiTemplateTypes]); null for whitespace inside tags. */
    internal fun kindOf(type: IElementType): JinjaTokenKind? = when {
        type == T.WHITE_SPACE -> null
        type == T.TEXT -> JinjaTokenKind.TEXT
        type == T.RAW_TEXT -> JinjaTokenKind.RAW_TEXT
        type == T.COMMENT -> JinjaTokenKind.COMMENT
        type == T.VAR_START -> JinjaTokenKind.VAR_START
        type == T.VAR_END -> JinjaTokenKind.VAR_END
        type == T.BLOCK_START -> JinjaTokenKind.BLOCK_START
        type == T.BLOCK_END -> JinjaTokenKind.BLOCK_END
        type == T.IDENTIFIER || type == T.FILTER_NAME || type == T.TEST_NAME -> JinjaTokenKind.NAME
        type == T.STRING -> JinjaTokenKind.STRING
        type == T.INTEGER -> JinjaTokenKind.INTEGER
        type == T.FLOAT -> JinjaTokenKind.FLOAT
        T.KEYWORDS.contains(type) -> JinjaTokenKind.KEYWORD
        T.OPERATORS.contains(type) || type in PUNCTUATION -> JinjaTokenKind.OPERATOR
        else -> JinjaTokenKind.BAD
    }
}
