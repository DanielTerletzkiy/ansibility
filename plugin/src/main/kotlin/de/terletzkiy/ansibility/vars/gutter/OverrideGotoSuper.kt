package de.terletzkiy.ansibility.vars.gutter

import com.intellij.codeInsight.navigation.PsiTargetNavigator
import com.intellij.lang.LanguageCodeInsightActionHandler
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.vars.AnsibilityVarsBundle.message
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * Go to Super (Ctrl+U, plan X15) on a variable definition: the lower-precedence definitions it overrides, e.g. from
 * a `group_vars` key to the role default and the spec option. Ctrl+B on the same key shows its usages.
 */
class OverrideGotoSuper : LanguageCodeInsightActionHandler {
    override fun isValidFor(editor: Editor, file: PsiFile): Boolean = !DumbService.isDumb(file.project) && keyAt(editor, file) != null

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val targets = ReadAction.compute<List<PsiElement>, RuntimeException> { targets(editor, file) }
        when (targets.size) {
            0 -> return
            1 -> (targets.single() as? Navigatable)?.navigate(true)
            else -> PsiTargetNavigator(targets).navigate(editor, message("gotosuper.title"))
        }
    }

    override fun startInWriteAction(): Boolean = false

    companion object {
        fun targets(editor: Editor, file: PsiFile): List<PsiElement> {
            val keyValue = keyAt(editor, file) ?: return emptyList()
            val overrides = OverrideLineMarkers.overridesOf(keyValue) ?: return emptyList()
            return overrides.lower.asReversed().mapNotNull { OverrideLineMarkers.target(file.project, overrides.root, it) }
        }

        private fun keyAt(editor: Editor, file: PsiFile): YAMLKeyValue? {
            val element = file.findElementAt(editor.caretModel.offset) ?: return null
            val keyValue = PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java, false) ?: return null
            return keyValue.takeIf { key -> key.key?.textRange?.containsOffset(editor.caretModel.offset) == true }
        }
    }
}
