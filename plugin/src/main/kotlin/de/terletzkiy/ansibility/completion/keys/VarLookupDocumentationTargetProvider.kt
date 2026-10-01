package de.terletzkiy.ansibility.completion.keys

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.openapi.project.DumbService
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.LookupElementDocumentationTargetProvider
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.vars.VarDocumentationTarget
import de.terletzkiy.ansibility.vars.VarSubject

/**
 * Ctrl+Q on a key or value item of this package shows the variable card of the M2 hover (plan F1.2, F4.3): the card of
 * the variable, or of the nested option, the item completes, seen from the file being edited.
 *
 * `LookupElementDocumentationTargetProvider` is `@ApiStatus.Experimental` (and `@OverrideOnly`, which an
 * implementation satisfies) in 262; it is the platform's documented way to document lookup items whose object is not
 * a PSI element. Items keep a light [VarLookupDoc] instead of PSI, so building a popup of thousands of items resolves
 * nothing until an item is documented.
 */
class VarLookupDocumentationTargetProvider : LookupElementDocumentationTargetProvider {
    override fun documentationTarget(psiFile: PsiFile, element: LookupElement, offset: Int): DocumentationTarget? {
        val doc = element.`object` as? VarLookupDoc ?: return null
        val project = psiFile.project
        if (DumbService.isDumb(project) || !doc.rootDir.isValid || !doc.file.isValid) return null
        val root = AnsibleWorkspace.getInstance(project).roots().firstOrNull { it.dir == doc.rootDir } ?: return null
        return VarDocumentationTarget(project, VarSubject.referenceTo(root, doc.name, doc.path, doc.file, doc.offset))
    }
}
