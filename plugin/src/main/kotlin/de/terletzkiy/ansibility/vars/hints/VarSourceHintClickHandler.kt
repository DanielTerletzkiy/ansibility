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
import com.intellij.openapi.util.text.StringUtil
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
 *
 * The reference is found from the clicked inlay's offset now (R31 fixes, decision 9): an inlay moves with the text, so
 * an edit above it between the hint pass and the click never sends the click to another position ([referenceStart]).
 */
class VarSourceHintClickHandler : InlayActionHandler {
    override fun handleClick(e: EditorMouseEvent, payload: InlayActionPayload) {
        val (distance, name) = VarSourceHints.parsePayload((payload as? StringInlayActionPayload)?.text) ?: return
        val editor = e.editor
        val project = editor.project ?: return
        val inlay = editor.inlayModel.getElementAt(e.mouseEvent.point) ?: return
        val document = editor.document
        val offset = referenceStart(document.immutableCharSequence, inlay.offset, distance, name) ?: return
        PsiTargetNavigator(Supplier { targets(project, document, offset) })
            .navigate(e.mouseEvent, AnsibilityVarsBundle.message("hints.popup.title", name), project)
    }

    companion object {
        /**
         * The start of the reference of [name] whose hint sits at [hintOffset] now: [distance] before it while the text
         * there still starts with [name] (the accessors between the name and the hint unchanged), else the last [name]
         * before the hint on its line; null when the line holds none.
         */
        fun referenceStart(text: CharSequence, hintOffset: Int, distance: Int, name: String): Int? {
            if (hintOffset > text.length || name.isEmpty()) return null
            val start = hintOffset - distance
            if (start >= 0 && isNameAt(text, start, name)) return start
            val lineStart = StringUtil.lastIndexOf(text, '\n', 0, hintOffset) + 1
            var at = hintOffset - name.length
            while (at >= lineStart) {
                if (isNameAt(text, at, name)) return at
                at--
            }
            return null
        }

        private fun isNameAt(text: CharSequence, at: Int, name: String): Boolean =
            StringUtil.startsWith(text, at, name) && !isNamePart(text.getOrNull(at - 1)) && !isNamePart(text.getOrNull(at + name.length))

        private fun isNamePart(char: Char?): Boolean = char != null && (char.isLetterOrDigit() || char == '_')

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
