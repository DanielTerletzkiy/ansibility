package de.terletzkiy.ansibility.inspections.references

import com.intellij.modcommand.ModPsiUpdater
import com.intellij.modcommand.PsiUpdateModCommandQuickFix
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import de.terletzkiy.ansibility.navigation.AnsibilityNavigationBundle

/**
 * ANS-R001 quick fix "Change to '<name>'": replaces the reference text ([rangeInElement] of the problem's element)
 * with the nearest existing name. The element's manipulator does the replacement, so a quoted YAML scalar keeps its
 * quotes and escaping, and a `k=v` word or list item inside a longer scalar changes alone. Offered only for elements
 * that have a manipulator (YAML scalars).
 */
class ChangeReferenceFix(private val replacement: String, private val rangeInElement: TextRange) : PsiUpdateModCommandQuickFix() {
    override fun getName(): String = AnsibilityNavigationBundle.message("fix.change.reference", replacement)

    override fun getFamilyName(): String = AnsibilityNavigationBundle.message("fix.change.reference.family")

    override fun applyFix(project: Project, element: PsiElement, updater: ModPsiUpdater) {
        val manipulator = ElementManipulators.getManipulator(element) ?: return
        if (rangeInElement.endOffset > element.textLength) return
        manipulator.handleContentChange(element, rangeInElement, replacement)
    }
}
