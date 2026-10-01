package de.terletzkiy.ansibility.lang.jinja.parser

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaLexer
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes
import de.terletzkiy.ansibility.lang.jinja.lexer.JinjaLexMode
import de.terletzkiy.ansibility.lang.jinja.psi.AnsibleJinjaElementType
import de.terletzkiy.ansibility.lang.jinja.psi.AnsibleJinjaElementTypes
import de.terletzkiy.ansibility.lang.jinja.psi.AnsibleJinjaFile

/**
 * The parser definition of `AnsibleJinja` for template files (the base tree of their template view provider) and for
 * fragments injected into YAML. Both lex in template mode: expression-mode values are injected with the prefix
 * `{{ ` and the suffix ` }}` (plan A.5).
 */
class AnsibleJinjaParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = AnsibleJinjaLexer(JinjaLexMode.TEMPLATE)

    override fun createParser(project: Project?): PsiParser = AnsibleJinjaParser()

    override fun getFileNodeType(): IFileElementType = AnsibleJinjaElementTypes.FILE

    override fun getWhitespaceTokens(): TokenSet = AnsibleJinjaTokenTypes.WHITESPACES

    override fun getCommentTokens(): TokenSet = AnsibleJinjaTokenTypes.COMMENTS

    override fun getStringLiteralElements(): TokenSet = AnsibleJinjaTokenTypes.STRINGS

    override fun createElement(node: ASTNode): PsiElement =
        (node.elementType as? AnsibleJinjaElementType)?.createPsi(node) ?: ASTWrapperPsiElement(node)

    override fun createFile(viewProvider: FileViewProvider): PsiFile = AnsibleJinjaFile(viewProvider)
}
