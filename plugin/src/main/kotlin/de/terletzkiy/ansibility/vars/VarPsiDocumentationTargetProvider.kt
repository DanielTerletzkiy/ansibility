package de.terletzkiy.ansibility.vars

import com.intellij.openapi.project.DumbService
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.PsiDocumentationTargetProvider
import com.intellij.psi.PsiElement
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * The variable card for PSI elements (`platform.backend.documentation.psiTargetProvider`, plan A4 "Ctrl-hover").
 *
 * Ctrl-hover does not ask offset-based documentation providers: it resolves Go to Declaration targets and, for a
 * single target, shows the `computeDocumentationHint()` of that element's PSI documentation target. So this
 * provider answers for
 * - the [VarTargetElement]s our Go to Declaration returns (spec options, `defaults/` and `vars/` keys, inventory
 *   keys, `register:` names …), and
 * - [YAMLKeyValue]s (or their key) that are variable keys inside an Ansible root ([VarKeySites]): spec options,
 *   defaults, vars and inventory keys, play/task vars, `set_fact` keys.
 *
 * With several Go to Declaration targets the platform shows no hint at all (a platform limitation, not ours); the
 * chooser then lists each target's role, file and line. While indexing it answers nothing.
 */
class VarPsiDocumentationTargetProvider : PsiDocumentationTargetProvider {
    override fun documentationTarget(element: PsiElement, originalElement: PsiElement?): DocumentationTarget? {
        val project = element.project
        if (DumbService.isDumb(project)) return null
        if (element is VarTargetElement) {
            return element.takeIf { it.isValid }?.let { VarDocumentationTarget(project, it.subject) }
        }
        val keyValue = element as? YAMLKeyValue ?: (element.parent as? YAMLKeyValue)?.takeIf { it.key == element } ?: return null
        val file = keyValue.containingFile?.originalFile?.viewProvider?.virtualFile ?: return null
        val context = AnsibleWorkspace.getInstance(project).contextOf(file) ?: return null
        val site = VarKeySites.of(keyValue, context, file) ?: return null
        return VarSubject.key(site)?.let { VarDocumentationTarget(project, it) }
    }
}
