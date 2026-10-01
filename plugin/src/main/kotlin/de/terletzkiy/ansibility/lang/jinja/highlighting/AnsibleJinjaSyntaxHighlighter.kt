package de.terletzkiy.ansibility.lang.jinja.highlighting

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaLexer
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes
import de.terletzkiy.ansibility.lang.jinja.lexer.JinjaLexMode

/**
 * Lexer-based highlighting of the Jinja layer: delimiters, keywords, names, filters, tests, literals, operators and
 * comments. Outer [AnsibleJinjaTokenTypes.TEXT] gets no attributes, so the outer language's highlighting shows through
 * in template files and the host's in injected YAML fragments.
 */
class AnsibleJinjaSyntaxHighlighter @JvmOverloads constructor(
    private val mode: JinjaLexMode = JinjaLexMode.TEMPLATE,
) : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = AnsibleJinjaLexer(mode)

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> =
        pack(tokenType?.let { KEYS[it] })

    private companion object {
        val KEYS: Map<IElementType, TextAttributesKey> = buildMap {
            SyntaxHighlighterBase.fillMap(this, AnsibleJinjaTokenTypes.DELIMITERS, AnsibleJinjaHighlighterColors.DELIMITER)
            SyntaxHighlighterBase.fillMap(this, AnsibleJinjaTokenTypes.KEYWORDS, AnsibleJinjaHighlighterColors.KEYWORD)
            SyntaxHighlighterBase.fillMap(this, AnsibleJinjaTokenTypes.OPERATORS, AnsibleJinjaHighlighterColors.OPERATOR)
            SyntaxHighlighterBase.fillMap(this, AnsibleJinjaTokenTypes.PARENTHESES, AnsibleJinjaHighlighterColors.PARENTHESES)
            SyntaxHighlighterBase.fillMap(this, AnsibleJinjaTokenTypes.BRACKETS, AnsibleJinjaHighlighterColors.BRACKETS)
            SyntaxHighlighterBase.fillMap(this, AnsibleJinjaTokenTypes.BRACES, AnsibleJinjaHighlighterColors.BRACES)
            SyntaxHighlighterBase.fillMap(this, AnsibleJinjaTokenTypes.STRINGS, AnsibleJinjaHighlighterColors.STRING)
            SyntaxHighlighterBase.fillMap(this, AnsibleJinjaTokenTypes.NUMBERS, AnsibleJinjaHighlighterColors.NUMBER)
            SyntaxHighlighterBase.fillMap(this, AnsibleJinjaTokenTypes.COMMENTS, AnsibleJinjaHighlighterColors.COMMENT)
            put(AnsibleJinjaTokenTypes.IDENTIFIER, AnsibleJinjaHighlighterColors.IDENTIFIER)
            put(AnsibleJinjaTokenTypes.FILTER_NAME, AnsibleJinjaHighlighterColors.FILTER)
            put(AnsibleJinjaTokenTypes.TEST_NAME, AnsibleJinjaHighlighterColors.TEST)
            put(AnsibleJinjaTokenTypes.DOT, AnsibleJinjaHighlighterColors.DOT)
            put(AnsibleJinjaTokenTypes.COMMA, AnsibleJinjaHighlighterColors.COMMA)
            put(AnsibleJinjaTokenTypes.COLON, AnsibleJinjaHighlighterColors.OPERATOR)
            put(AnsibleJinjaTokenTypes.SEMICOLON, AnsibleJinjaHighlighterColors.OPERATOR)
            put(AnsibleJinjaTokenTypes.RAW_TEXT, AnsibleJinjaHighlighterColors.RAW_TEXT)
            put(AnsibleJinjaTokenTypes.BAD_CHARACTER, AnsibleJinjaHighlighterColors.BAD_CHARACTER)
        }
    }
}

/** Registered as `lang.syntaxHighlighterFactory` for `AnsibleJinja`: template mode, the mode of files and injections. */
class AnsibleJinjaSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        AnsibleJinjaSyntaxHighlighter()
}
