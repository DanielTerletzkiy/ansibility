package de.terletzkiy.ansibility.lang.jinja.editor

import com.intellij.codeInsight.highlighting.CodeBlockSupportHandler
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaBlockStatementElement
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaTag

/**
 * Matching block keywords and comment delimiters (plan F2.1 brace matching, for what is not a token pair):
 * - with the caret on a tag of a block statement (its name or delimiters), the names of all its tags are highlighted,
 *   e.g. `if`, `elif`, `else` and `endif`;
 * - inside a `{# … #}` comment, its `{#` and `#}`.
 *
 * The code block of an element (Move Caret to Code Block Start/End, Ctrl+[ and Ctrl+]) is its innermost block
 * statement, from the opening tag to the end tag.
 */
class AnsibleJinjaCodeBlockSupport : CodeBlockSupportHandler {
    override fun getCodeBlockMarkerRanges(elementAtCursor: PsiElement): List<TextRange> {
        if (elementAtCursor.elementType == AnsibleJinjaTokenTypes.COMMENT) return commentDelimiters(elementAtCursor)
        val tag = PsiTreeUtil.getParentOfType(elementAtCursor, JinjaTag::class.java, false) ?: return emptyList()
        if (!isTagPart(elementAtCursor, tag)) return emptyList()
        val statement = tag.parent as? JinjaBlockStatementElement ?: return emptyList()
        return PsiTreeUtil.getChildrenOfTypeAsList(statement, JinjaTag::class.java).mapNotNull { it.tagKeyword?.textRange }
    }

    override fun getCodeBlockRange(elementAtCursor: PsiElement): TextRange =
        PsiTreeUtil.getParentOfType(elementAtCursor, JinjaBlockStatementElement::class.java, false)?.textRange ?: TextRange.EMPTY_RANGE

    /** The tag's name or one of its delimiters: the caret is "on the tag", not in its expression. */
    private fun isTagPart(element: PsiElement, tag: JinjaTag): Boolean =
        element == tag.tagKeyword || element == tag.startDelimiter || element == tag.endDelimiter

    /** `{#` and `#}` of [comment], each with its whitespace marker (`{#-`, `-#}`). */
    private fun commentDelimiters(comment: PsiElement): List<TextRange> {
        val range = comment.textRange
        val text = comment.text
        if (text.length < 2) return emptyList()
        val openLength = if (text.length > 2 && text[2] in MARKERS) 3 else 2
        val open = TextRange(range.startOffset, range.startOffset + openLength)
        if (text.length < openLength + 2 || !text.endsWith("#}")) return listOf(open)
        val closeLength = if (text.length >= openLength + 3 && text[text.length - 3] in MARKERS) 3 else 2
        return listOf(open, TextRange(range.endOffset - closeLength, range.endOffset))
    }

    private companion object {
        val MARKERS = setOf('-', '+')
    }
}
