package de.terletzkiy.ansibility.dispatch

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * The one Go to Declaration entry point of the plugin (`gotoDeclarationHandler order="first"`, plan A.4).
 *
 * The platform tries handlers in order and the first non-empty result wins, before references are resolved, so
 * this handler also answers on YAML keys (Ctrl+B on a vars-file key, which would otherwise show usages of the key
 * itself) and anywhere inside scalars. It classifies the offset once and returns the first non-empty target list
 * of the `siteNavigation` extensions; several targets open the platform chooser, a [WebDocTarget] opens the
 * browser. The platform calls handlers for an injected fragment before its host; the offset is mapped to the
 * host file, so both calls classify the same position. Returns null outside Ansible roots.
 *
 * While indexing every extension is still asked: the platform runs Go to Declaration handlers with access to
 * reliable index data (`DumbModeAccessType.RELIABLE_DATA_ONLY`).
 */
class AnsibleGotoDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        val file = sourceElement?.containingFile ?: editor?.let(::fileOf) ?: return null
        val position = SiteDispatch.hostPosition(file, offset)
        if (SiteDispatch.contextOf(position.file) == null) return null
        val classified = SiteDispatch.classify(position) ?: return null
        val targets = SiteDispatch.navigationTargets(classified.site, position.file)
        return if (targets.isEmpty()) null else targets.toTypedArray()
    }

    private fun fileOf(editor: Editor): PsiFile? {
        val project = editor.project ?: return null
        return PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
    }
}
