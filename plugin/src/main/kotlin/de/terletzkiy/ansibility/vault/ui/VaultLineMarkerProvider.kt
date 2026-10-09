package de.terletzkiy.ansibility.vault.ui

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiEditorUtil
import com.intellij.psi.util.elementType
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.VaultLockState
import de.terletzkiy.ansibility.api.VaultStatusService
import de.terletzkiy.ansibility.coexist.vault.VaultEditorCoexistence
import de.terletzkiy.ansibility.vault.actions.VaultValueActions
import de.terletzkiy.ansibility.vault.actions.VaultValuePsi
import de.terletzkiy.ansibility.vault.actions.VaultValueRef
import javax.swing.Icon
import org.jetbrains.yaml.YAMLTokenTypes
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * 🟣 X93: a lock icon in the gutter of every inline vault value inside an Ansible root: closed while no id that may
 * decrypt it is unlocked, open once one is. A click reveals the value (F7.1); the right-click menu offers the other
 * value actions.
 *
 * Anchored on the key (or, for a sequence item, on the `!vault` tag). The state comes from [VaultStatusService] only,
 * which never decrypts and never reads a secret; [VaultGutterRefresher] re-runs the markers when a lock state changes.
 * While Ansible Vault Editor is loaded its own icon marks literal-block values, so ours stays away from those
 * (F7.12) and only marks what it misses, such as a quoted `!vault "…"`.
 */
class VaultLineMarkerProvider : LineMarkerProviderDescriptor(), DumbAware {
    override fun getName(): String = AnsibilityVaultUiBundle.message("gutter.name")

    override fun getIcon(): Icon = AllIcons.Ide.Readonly

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        val scalar = scalarFor(element) ?: return null
        if ((scalar.parent as? YAMLKeyValue)?.let(VaultEditorCoexistence.getInstance()::vaultEditorMarksValue) == true) return null
        val project = element.project
        val file = element.containingFile?.originalFile?.viewProvider?.virtualFile ?: return null
        if (AnsibleWorkspace.getInstance(project).rootFor(file) == null) return null
        val status = VaultStatusService.getInstance(project).status(scalar) ?: return null
        val label = status.identity?.label ?: status.header.labelOrDefault()
        val (icon, tooltip) = when (status.lockState) {
            VaultLockState.UNLOCKED -> AllIcons.Ide.Readwrite to AnsibilityVaultUiBundle.message("gutter.tooltip.unlocked", label)
            VaultLockState.LOCKED -> AllIcons.Ide.Readonly to AnsibilityVaultUiBundle.message("gutter.tooltip.locked", label)
            VaultLockState.NO_IDENTITY -> AllIcons.Ide.Readonly to AnsibilityVaultUiBundle.message("gutter.tooltip.none")
        }
        return VaultLineMarkerInfo(element, icon, tooltip)
    }

    /**
     * The marker, with the X93 action menu on right-click: Reveal, Copy, Edit…, Rekey to id…, Change id…, Decrypt to
     * plain value…, and Unlock vault ids… while the value's ids are locked.
     */
    private class VaultLineMarkerInfo(element: PsiElement, icon: Icon, tooltip: String) : LineMarkerInfo<PsiElement>(
        element, element.textRange, icon, { tooltip }, REVEAL, GutterIconRenderer.Alignment.LEFT,
        { AnsibilityVaultUiBundle.message("gutter.accessible") },
    ) {
        override fun createGutterRenderer(): GutterIconRenderer = object : LineMarkerGutterIconRenderer<PsiElement>(this) {
            override fun getPopupMenuActions(): ActionGroup = DefaultActionGroup(
                ValueAction("intention.reveal") { project, ref, editor -> VaultValueActions.getInstance(project).reveal(ref, editor) },
                ValueAction("intention.copy") { project, ref, editor -> VaultValueActions.getInstance(project).copy(ref, editor) },
                ValueAction("intention.edit") { project, ref, editor -> VaultValueActions.getInstance(project).edit(ref, editor) },
                Separator.getInstance(),
                ValueAction("intention.rekey") { project, ref, editor -> VaultValueActions.getInstance(project).rekey(ref, editor) },
                ValueAction("intention.change.id") { project, ref, editor -> VaultValueActions.getInstance(project).changeId(ref, editor) },
                ValueAction("intention.decrypt") { project, ref, editor -> VaultValueActions.getInstance(project).decryptToPlain(ref, editor) },
                Separator.getInstance(),
                ValueAction("gutter.unlock", shownFor = { project, scalar -> isLocked(project, scalar) }) { project, ref, editor -> VaultValueActions.getInstance(project).unlock(ref, editor) },
            )
        }

        /** One menu entry: runs [perform] for the marker's value; shown while [shownFor] holds for it (status only, never decrypting). */
        private inner class ValueAction(
            key: String,
            private val shownFor: (Project, YAMLScalar) -> Boolean = { _, _ -> true },
            private val perform: (Project, VaultValueRef, Editor?) -> Unit,
        ) : DumbAwareAction(AnsibilityVaultUiBundle.message(key)) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

            override fun update(event: AnActionEvent) {
                val element = this@VaultLineMarkerInfo.element
                event.presentation.isEnabledAndVisible = element != null && runReadActionBlocking {
                    element.isValid && scalarFor(element)?.let { shownFor(element.project, it) } == true
                }
            }

            override fun actionPerformed(event: AnActionEvent) {
                val element = this@VaultLineMarkerInfo.element ?: return
                val ref = runReadActionBlocking { scalarFor(element)?.let(VaultValueRef::of) } ?: return
                perform(element.project, ref, PsiEditorUtil.findEditor(element))
            }
        }
    }

    private companion object {
        /** Click: Reveal the value next to the editor's caret. */
        val REVEAL = GutterIconNavigationHandler<PsiElement> { _, element ->
            val scalar = scalarFor(element) ?: return@GutterIconNavigationHandler
            val ref = VaultValueRef.of(scalar) ?: return@GutterIconNavigationHandler
            VaultValueActions.getInstance(element.project).reveal(ref, PsiEditorUtil.findEditor(element))
        }

        /** True while every id that may decrypt [scalar] is locked (from [VaultStatusService]: nothing is decrypted). */
        fun isLocked(project: Project, scalar: YAMLScalar): Boolean =
            VaultStatusService.getInstance(project).status(scalar)?.lockState == VaultLockState.LOCKED

        /** The vault value [element] anchors a marker for: a key leaf of a `!vault` value, or the tag of a vault sequence item. */
        fun scalarFor(element: PsiElement): YAMLScalar? = when (element.elementType) {
            YAMLTokenTypes.SCALAR_KEY -> (element.parent as? YAMLKeyValue)?.takeIf { it.key == element }?.let { it.value as? YAMLScalar }?.takeIf(VaultValuePsi::isVault)
            YAMLTokenTypes.TAG -> (element.parent as? YAMLScalar)?.takeIf { it.parent is YAMLSequenceItem && VaultValuePsi.isVault(it) }
            else -> null
        }
    }
}
