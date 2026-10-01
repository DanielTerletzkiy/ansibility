package de.terletzkiy.ansibility.inspections.types

import com.intellij.modcommand.ModPsiUpdater
import com.intellij.modcommand.PsiUpdateModCommandQuickFix
import com.intellij.openapi.project.Project
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import de.terletzkiy.ansibility.types.AnsibilityTypesBundle
import org.jetbrains.yaml.YAMLElementGenerator
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLQuotedText
import org.jetbrains.yaml.psi.YAMLScalar

/*
 * In-file quick fixes of the type inspections (🟣 CLAUDE X80). Each one edits the problem's own element through PSI,
 * so the platform shows the change as the preview. They are offered only for scalars without an anchor or tag
 * (TypeFixes checks), so replacing the whole scalar loses nothing.
 */

/** Replaces [scalar] with a scalar written as [text] (plain, or quoted when [text] carries quotes). */
private fun replaceScalar(project: Project, scalar: YAMLScalar, text: String) {
    val replacement = YAMLElementGenerator.getInstance(project).createYamlKeyValue("key", text).value ?: return
    scalar.replace(replacement)
}

/** "Quote value" (ANS-T011): `3.2` → `"3.2"`, keeping the spelling the author wrote (YAML 1.1 would read `3.10` as 3.1). */
class QuoteValueFix : PsiUpdateModCommandQuickFix() {
    override fun getFamilyName(): String = AnsibilityTypesBundle.message("fix.quote")

    override fun applyFix(project: Project, element: PsiElement, updater: ModPsiUpdater) {
        val scalar = element as? YAMLScalar ?: return
        replaceScalar(project, scalar, SpecEdits.quoted(scalar.text.trim()))
    }
}

/** "Unquote value" (ANS-T016): `"444"` → `444` for an `int` option, offered when the plain spelling loads as the coerced value. */
class UnquoteValueFix : PsiUpdateModCommandQuickFix() {
    override fun getFamilyName(): String = AnsibilityTypesBundle.message("fix.unquote")

    override fun applyFix(project: Project, element: PsiElement, updater: ModPsiUpdater) {
        val scalar = element as? YAMLQuotedText ?: return
        replaceScalar(project, scalar, scalar.textValue)
    }
}

/**
 * Replaces a scalar with [replacement]: "Replace with 'true'" for ANS-T013 (the literal ansible-core coerces to), and
 * "Replace with nearest choice 'x'" for ANS-T004. [text] is the YAML text to write; a quoted scalar keeps its quotes
 * when [keepQuotes] is set (its manipulator escapes the content).
 */
class ReplaceValueFix(
    private val replacement: String,
    private val text: String,
    private val nearestChoice: Boolean,
    private val keepQuotes: Boolean,
) : PsiUpdateModCommandQuickFix() {
    override fun getName(): String =
        AnsibilityTypesBundle.message(if (nearestChoice) "fix.nearest.choice" else "fix.replace", replacement)

    override fun getFamilyName(): String =
        AnsibilityTypesBundle.message(if (nearestChoice) "fix.nearest.choice.family" else "fix.replace.family")

    override fun applyFix(project: Project, element: PsiElement, updater: ModPsiUpdater) {
        val scalar = element as? YAMLScalar ?: return
        val manipulator = ElementManipulators.getManipulator(scalar)
        if (keepQuotes && scalar is YAMLQuotedText && manipulator != null) {
            manipulator.handleContentChange(scalar, ElementManipulators.getValueTextRange(scalar), replacement)
        } else {
            replaceScalar(project, scalar, text)
        }
    }
}

/** "Remove unsupported key 'k'" (ANS-T002): deletes the key-value from its mapping. */
class RemoveKeyFix(private val key: String) : PsiUpdateModCommandQuickFix() {
    override fun getName(): String = AnsibilityTypesBundle.message("fix.remove.key", key)

    override fun getFamilyName(): String = AnsibilityTypesBundle.message("fix.remove.key.family")

    override fun applyFix(project: Project, element: PsiElement, updater: ModPsiUpdater) {
        val keyValue = element as? YAMLKeyValue ?: return
        val mapping = keyValue.parentMapping ?: return
        mapping.deleteKeyValue(keyValue)
    }
}
