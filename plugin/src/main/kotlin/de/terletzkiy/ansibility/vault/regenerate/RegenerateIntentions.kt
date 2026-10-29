package de.terletzkiy.ansibility.vault.regenerate

import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.vault.actions.PlainValueRef
import de.terletzkiy.ansibility.vault.actions.VaultIntentionBase
import de.terletzkiy.ansibility.vault.actions.VaultValueIntention
import de.terletzkiy.ansibility.vault.actions.VaultValuePsi
import de.terletzkiy.ansibility.vault.actions.VaultValueRef
import de.terletzkiy.ansibility.vault.ui.AnsibilityVaultUiBundle
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * X97 "Regenerate secret" (plan amendment R29, D215): a new random value for one `!vault` value, encrypted with the id
 * that decrypts it now and its old header (D220); a small chooser (generator and length, preset from the root's rule or
 * the value's shape) instead of the selection tree. One undoable command; neither value is ever shown.
 *
 * A classic intention like the value intentions of `vault.actions` (PSI-only availability, `startInWriteAction` false,
 * an HTML preview without the value), in the family "Ansibility Vault secrets" next to their "Ansibility Vault".
 */
class VaultRegenerateIntention : VaultValueIntention() {
    override val writes: Boolean get() = true

    override fun getFamilyName(): String = AnsibilityVaultUiBundle.message("intention.family.secrets")

    override fun getText(): String = AnsibilityVaultUiBundle.message("intention.regenerate")

    override fun getPriority(): PriorityAction.Priority = PriorityAction.Priority.LOW

    override fun perform(project: Project, editor: Editor?, ref: VaultValueRef) = RegenerateService.getInstance(project).regenerate(ref, editor)

    override fun preview(scalar: YAMLScalar): HtmlChunk =
        html(AnsibilityVaultUiBundle.message("intention.regenerate.preview", VaultValuePsi.keyName(scalar) ?: "-"))
}

/**
 * "Generate and encrypt" (plan amendment R29, D215, ANS-X001's case): on the key or the value of a plaintext `vault_*`
 * value, a new random value in a `!vault |` block with the id Ansible's rule picks (asked when ambiguous, D220). The
 * placeholder is replaced, never encrypted; it may be empty or anchored ([PlainValueRef.placeholder]).
 */
class VaultGenerateAndEncryptIntention : VaultIntentionBase() {
    override val writes: Boolean get() = true

    override fun getFamilyName(): String = AnsibilityVaultUiBundle.message("intention.family.secrets")

    override fun getText(): String = AnsibilityVaultUiBundle.message("intention.generate")

    override fun getPriority(): PriorityAction.Priority = PriorityAction.Priority.LOW

    override fun scalarAt(element: PsiElement): YAMLScalar? {
        val keyValue = PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java, false) ?: return null
        if (!keyValue.keyText.startsWith(PlainValueRef.VAULT_PREFIX)) return null
        val scalar = keyValue.value as? YAMLScalar ?: return null
        val onKey = keyValue.key?.textRange?.contains(element.textRange) == true
        return scalar.takeIf { onKey || PsiTreeUtil.isAncestor(it, element, false) }
    }

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean =
        scalarAt(element)?.let(PlainValueRef::acceptsPlaceholder) == true

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val ref = scalarAt(element)?.let(PlainValueRef::placeholder) ?: return
        RegenerateService.getInstance(project).generateAndEncrypt(ref, editor)
    }

    override fun preview(scalar: YAMLScalar): HtmlChunk = html(
        AnsibilityVaultUiBundle.message("intention.generate.preview", VaultValuePsi.keyName(scalar) ?: "-"),
        AnsibilityVaultUiBundle.message("intention.generate.preview.rule"),
    )
}
