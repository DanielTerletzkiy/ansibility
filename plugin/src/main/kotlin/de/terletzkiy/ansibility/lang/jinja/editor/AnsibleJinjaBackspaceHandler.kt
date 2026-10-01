package de.terletzkiy.ansibility.lang.jinja.editor

import com.intellij.codeInsight.editorActions.BackspaceHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes

/**
 * Undoes an auto-closed empty tag with Backspace (plan X35), as the platform does for `(|)`: in `{{ | }}`, `{% | %}`
 * or `{# | #}` Backspace leaves the first `{`, i.e. the text before the tag start was typed. Only with "auto-close
 * `{{ }}` and `{% %}`" on and where [JinjaTypingContext] applies.
 */
class AnsibleJinjaBackspaceHandler : BackspaceHandlerDelegate() {
    override fun beforeCharDeleted(c: Char, file: PsiFile, editor: Editor) = Unit

    override fun charDeleted(c: Char, file: PsiFile, editor: Editor): Boolean {
        if (c != ' ' || !JinjaTypingContext.settings().autoCloseDelimiters) return false
        val host = InjectedLanguageEditorUtil.getTopLevelEditor(editor)
        val offset = host.caretModel.offset
        val text = host.document.charsSequence
        // after the deletion: `{{| }}` (the space before the caret is gone)
        if (offset < 2 || offset + 3 > text.length || text[offset - 2] != '{') return false
        val closer = CLOSERS[text[offset - 1]] ?: return false
        if (!text.subSequence(offset, offset + 3).contentEquals(" $closer")) return false
        if (offset >= 3 && text[offset - 3] == '{') return false
        val project = file.project
        val context = JinjaTypingContext.find(project, host, file, offset - 2) ?: return false
        if (!isEmptyTagStart(context, offset - 2)) return false
        host.document.deleteString(offset - 1, offset + 3)
        host.caretModel.moveToOffset(offset - 1)
        return true
    }

    /** Whether an empty tag (no whitespace marker) starts at [start]. */
    private fun isEmptyTagStart(context: JinjaTypingContext, start: Int): Boolean {
        val token = context.tokenAt(start) ?: return false
        return when (token.type) {
            AnsibleJinjaTokenTypes.VAR_START, AnsibleJinjaTokenTypes.BLOCK_START -> token.start == start && token.end == start + 2
            AnsibleJinjaTokenTypes.COMMENT -> token.start == start && token.end == start + 5
            else -> false
        }
    }

    private companion object {
        val CLOSERS = mapOf('{' to "}}", '%' to "%}", '#' to "#}")
    }
}
