package de.terletzkiy.ansibility.lang.jinja.editor

import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler
import com.intellij.injected.editor.EditorWindow
import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes as T

/**
 * Brace matching for Ansible Jinja (plan F2.1): the tag delimiters `{{ }}` and `{% %}` (structural, so they are
 * highlighted and matched across the outer text of a template), and `( )`, `[ ]`, `{ }` inside expressions. Comment
 * delimiters `{# #}` and block keywords (`if` … `endif`) are matched by [AnsibleJinjaCodeBlockSupport], because a
 * comment is a single token.
 *
 * The platform never pairs the tag delimiters on its own ([isPairedBracesAllowedBeforeType]); typing `{{` and `{%`
 * is handled by [AnsibleJinjaTypedHandler], which respects the "auto-close" setting.
 */
class AnsibleJinjaBraceMatcher : PairedBraceMatcher {
    override fun getPairs(): Array<BracePair> = PAIRS

    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean {
        if (lbraceType == T.VAR_START || lbraceType == T.BLOCK_START) return false
        return contextType == null || contextType in ALLOWED_BEFORE
    }

    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int = openingBraceOffset

    private companion object {
        val PAIRS = arrayOf(
            BracePair(T.VAR_START, T.VAR_END, true),
            BracePair(T.BLOCK_START, T.BLOCK_END, true),
            BracePair(T.LPAREN, T.RPAREN, false),
            BracePair(T.LBRACKET, T.RBRACKET, false),
            BracePair(T.LBRACE, T.RBRACE, false),
        )

        /** Tokens before which an inserted closing bracket makes sense: whitespace, closers, separators, tag ends. */
        val ALLOWED_BEFORE = TokenSet.create(
            TokenType.WHITE_SPACE, T.TEXT, T.VAR_END, T.BLOCK_END, T.RPAREN, T.RBRACKET, T.RBRACE, T.COMMA, T.COLON,
            T.PIPE, T.SEMICOLON,
        )
    }
}

/**
 * Pairs the quotes of Jinja string literals (`default('|')`). In an injected fragment a `"` is left alone: it would
 * end the host's double-quoted YAML scalar.
 */
class AnsibleJinjaQuoteHandler : SimpleTokenSetQuoteHandler(T.STRING) {
    override fun hasNonClosedLiteral(editor: Editor, iterator: HighlighterIterator, offset: Int): Boolean {
        val text = editor.document.charsSequence
        if (editor is EditorWindow && offset < text.length && text[offset] == '"') return false
        return super.hasNonClosedLiteral(editor, iterator, offset)
    }
}
