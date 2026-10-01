package de.terletzkiy.ansibility.completion.jinja

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.api.JinjaContainer
import de.terletzkiy.ansibility.dispatch.SiteDispatch
import de.terletzkiy.ansibility.lang.jinja.lexer.JinjaLexMode
import de.terletzkiy.ansibility.vars.JinjaTextSites

/**
 * Opens the completion popup after the characters that start a member or key in Jinja (plan F1.3 member completion):
 * `.` (`item.`), a quote after `[` (`ansible_facts['`) and `[` itself (`hostvars[`). Identifier characters open it
 * through the platform's own auto-popup ([JinjaCompletionConfidence] keeps it from being skipped in strings).
 *
 * The popup is only scheduled; whether it opens is decided on the committed file after the character was inserted,
 * by [opensPopup]. Other files and positions are left alone (the handler always continues).
 */
class JinjaAutoPopupTypedHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (charTyped !in TRIGGERS) return Result.CONTINUE
        if (!SiteDispatch.isCompletionTarget(SiteDispatch.contextOf(file))) return Result.CONTINUE
        AutoPopupController.getInstance(project).scheduleAutoPopup(editor, CompletionType.BASIC) { committed ->
            opensPopup(committed, editor.caretModel.offset)
        }
        return Result.CONTINUE
    }

    companion object {
        private val TRIGGERS = setOf('.', '\'', '"', '[')

        /** True when completion at [offset] of [file] is a member, a quoted key, or right after `chain[`. */
        fun opensPopup(file: PsiFile, offset: Int): Boolean {
            if (DumbService.isDumb(file.project)) return false
            val host = SiteDispatch.hostPosition(file, offset)
            val analysis = JinjaTextSites.analysisAt(host.file, host.offset) ?: return false
            val mode = if (analysis.container == JinjaContainer.YAML_EXPRESSION) JinjaLexMode.EXPRESSION else JinjaLexMode.TEMPLATE
            return when (val position = JinjaCompletionPosition.at(analysis.text, analysis.textOffset, mode)) {
                is JinjaCompletionPosition.Member, is JinjaCompletionPosition.Key -> true
                is JinjaCompletionPosition.Name -> position.bracketOwner != null
                null -> false
            }
        }
    }
}
