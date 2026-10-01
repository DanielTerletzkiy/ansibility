package de.terletzkiy.ansibility.completion.jinja

import com.intellij.codeInsight.completion.CompletionConfidence
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ThreeState
import de.terletzkiy.ansibility.api.JinjaContainer
import de.terletzkiy.ansibility.dispatch.SiteDispatch
import de.terletzkiy.ansibility.lang.jinja.lexer.JinjaLexMode
import de.terletzkiy.ansibility.vars.JinjaTextSites

/**
 * Lets the completion popup open by itself while a variable is typed inside Jinja (plan F1.3). The platform skips
 * the auto-popup inside string literals, and every quoted YAML scalar is one, so `"{{ hap` would otherwise need
 * Ctrl+Space. Registered `order="first"` for every language; it answers [ThreeState.NO] ("do not skip") only where
 * [JinjaVarCompletionSource] completes ([JinjaCompletionPosition]) and [ThreeState.UNSURE] everywhere else, so other
 * confidences decide as before. Outside Ansible roots it returns after one cached context lookup.
 */
class JinjaCompletionConfidence : CompletionConfidence() {
    override fun shouldSkipAutopopup(editor: Editor, contextElement: PsiElement, psiFile: PsiFile, offset: Int): ThreeState {
        val host = SiteDispatch.hostPosition(psiFile, offset)
        if (!SiteDispatch.isCompletionTarget(SiteDispatch.contextOf(host.file))) return ThreeState.UNSURE
        // Variable completion needs the indexes; while indexing the popup would stay empty.
        if (DumbService.isDumb(psiFile.project)) return ThreeState.UNSURE
        val analysis = JinjaTextSites.analysisAt(host.file, host.offset) ?: return ThreeState.UNSURE
        val mode = if (analysis.container == JinjaContainer.YAML_EXPRESSION) JinjaLexMode.EXPRESSION else JinjaLexMode.TEMPLATE
        JinjaCompletionPosition.at(analysis.text, analysis.textOffset, mode) ?: return ThreeState.UNSURE
        return ThreeState.NO
    }
}
