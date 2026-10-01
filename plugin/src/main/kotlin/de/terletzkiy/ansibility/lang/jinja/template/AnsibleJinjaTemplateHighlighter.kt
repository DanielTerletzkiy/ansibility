package de.terletzkiy.ansibility.lang.jinja.template

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.util.LayerDescriptor
import com.intellij.openapi.editor.ex.util.LayeredLexerEditorHighlighter
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.fileTypes.EditorHighlighterProvider
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainSyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.templateLanguages.TemplateDataHighlighterWrapper
import com.intellij.psi.tree.IElementType
import de.terletzkiy.ansibility.lang.jinja.highlighting.AnsibleJinjaSyntaxHighlighter
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes

/**
 * The editor highlighter of Ansible Jinja template files (plan F2.1): the Jinja layer of [AnsibleJinjaSyntaxHighlighter]
 * on the standard template-language background, with the outer text highlighted by the outer language's own
 * highlighter (as a layer over the `TEXT` tokens, so the outer lexer sees the outer text joined).
 */
class AnsibleJinjaTemplateHighlighter(project: Project?, file: VirtualFile?, colors: EditorColorsScheme) :
    LayeredLexerEditorHighlighter(TemplateBackgroundHighlighter(AnsibleJinjaSyntaxHighlighter()), colors) {
    init {
        val outer = file?.let { AnsibleJinjaOuterLanguages.templateDataLanguage(project, it) }
        val outerHighlighter = outer?.let { SyntaxHighlighterFactory.getSyntaxHighlighter(it, project, file) } ?: PlainSyntaxHighlighter()
        registerLayer(AnsibleJinjaTokenTypes.TEXT, LayerDescriptor(TemplateDataHighlighterWrapper(outerHighlighter), ""))
    }

    /** Adds the template-language background to every Jinja token (outer text and raw bodies excepted). */
    private class TemplateBackgroundHighlighter(private val delegate: SyntaxHighlighter) : SyntaxHighlighter {
        override fun getHighlightingLexer(): Lexer = delegate.highlightingLexer

        override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> {
            val keys = delegate.getTokenHighlights(tokenType)
            if (tokenType == null || tokenType in AnsibleJinjaTokenTypes.OUTER_TEXT) return keys
            return arrayOf(DefaultLanguageHighlighterColors.TEMPLATE_LANGUAGE_COLOR, *keys)
        }
    }
}

/** `editorHighlighterProvider` for the `AnsibleJinja` file type. */
class AnsibleJinjaEditorHighlighterProvider : EditorHighlighterProvider {
    override fun getEditorHighlighter(
        project: Project?,
        fileType: FileType,
        virtualFile: VirtualFile?,
        colors: EditorColorsScheme,
    ): EditorHighlighter = AnsibleJinjaTemplateHighlighter(project, virtualFile, colors)
}
