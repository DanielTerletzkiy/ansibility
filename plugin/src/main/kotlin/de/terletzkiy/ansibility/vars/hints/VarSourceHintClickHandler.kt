package de.terletzkiy.ansibility.vars.hints

import com.intellij.codeInsight.hints.declarative.InlayActionHandler
import com.intellij.codeInsight.hints.declarative.InlayActionPayload
import com.intellij.codeInsight.hints.declarative.StringInlayActionPayload
import com.intellij.codeInsight.navigation.PsiTargetNavigator
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import de.terletzkiy.ansibility.dispatch.AnsibleGotoDeclarationHandler
import de.terletzkiy.ansibility.vars.AnsibilityVarsBundle
import java.util.function.Supplier

/**
 * A click on a variable source hint (plan amendment R31, D235): the Ctrl+B list of its reference ([VarNavigation]'s
 * rule 5, through the plugin's one Go to Declaration entry point) as a popup at the hint, a jump when there is one
 * target. The targets are computed in a background read action while the popup shows a progress icon
 * ([PsiTargetNavigator] with a supplier), never on the EDT.
 */
class VarSourceHintClickHandler : InlayActionHandler {
    override fun handleClick(e: EditorMouseEvent, payload: InlayActionPayload) {
        val (offset, name) = VarSourceHints.parsePayload((payload as? StringInlayActionPayload)?.text) ?: return
        val editor = e.editor
        val project = editor.project ?: return
        val document = editor.document
        PsiTargetNavigator(Supplier { targets(project, document, offset) })
            .navigate(e.mouseEvent, AnsibilityVarsBundle.message("hints.popup.title", name), project)
    }

    companion object {
        /** The Ctrl+B targets of the reference at [offset] of [document]'s file; call in a read action. */
        fun targets(project: Project, document: Document, offset: Int): List<PsiElement> {
            if (project.isDisposed) return emptyList()
            val file = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return emptyList()
            if (offset < 0 || offset > file.textLength) return emptyList()
            return try {
                AnsibleGotoDeclarationHandler().getGotoDeclarationTargets(file.findElementAt(offset), offset, null)?.toList().orEmpty()
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (_: IndexNotReadyException) {
                emptyList()
            }
        }
    }
}
