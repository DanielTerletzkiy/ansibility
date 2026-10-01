package de.terletzkiy.ansibility.vault.actions

import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.VaultFailure
import de.terletzkiy.ansibility.api.VaultHeaderInfo
import de.terletzkiy.ansibility.api.VaultStatusService
import de.terletzkiy.ansibility.coexist.vault.VaultEditorCoexistence
import de.terletzkiy.ansibility.coexist.vault.VaultIntentionKind
import de.terletzkiy.ansibility.semantics.vault.EncryptIdentity
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.vault.AnsibilityVaultBundle
import de.terletzkiy.ansibility.vault.ui.AnsibilityVaultUiBundle
import de.terletzkiy.ansibility.vault.ui.VaultUiTimings
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Base of the vault value intentions (plan amendment R7/R8, A.13): classic intentions, not ModCommand. A ModCommand
 * runs in a background read action, so it could neither prompt for a password nor stay out of the preview, whose
 * default runs the action on a copy and would decrypt into it.
 *
 * - [isAvailable] is PSI only: it never decrypts, never reads a secret and never asks a vault service anything
 *   that could (the passive-path rule);
 * - [startInWriteAction] is false: [invoke] starts on the EDT, the crypto runs in the background
 *   ([VaultValueActions]) and the write happens in one command on the EDT;
 * - [generatePreview] is an HTML description that never contains the value.
 */
abstract class VaultIntentionBase : PsiElementBaseIntentionAction(), PriorityAction {
    override fun getFamilyName(): String = message("intention.family")

    override fun startInWriteAction(): Boolean = false

    /** Whether the action writes the file (else it is offered in read-only files too). */
    protected abstract val writes: Boolean

    override fun checkFile(file: PsiFile?): Boolean = if (writes) super.checkFile(file) else file != null

    override fun getPriority(): PriorityAction.Priority = PriorityAction.Priority.NORMAL

    /** The HTML preview for [scalar] (in the preview's copy of the file); never contains the value or the ciphertext. */
    protected abstract fun preview(scalar: YAMLScalar): HtmlChunk

    /** The element the intention works on at the caret: the platform tries the caret and the character before it. */
    protected abstract fun scalarAt(element: PsiElement): YAMLScalar?

    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo {
        val offset = editor.caretModel.offset
        val element = file.findElementAt(offset)?.let(::scalarAt) ?: file.findElementAt(offset - 1)?.let(::scalarAt)
        return element?.let { IntentionPreviewInfo.Html(preview(it)) } ?: IntentionPreviewInfo.EMPTY
    }

    protected companion object {
        fun message(key: String, vararg params: Any): String = AnsibilityVaultUiBundle.message(key, *params)

        /** The key holding [scalar] (`vault_db_password`), or `-` for a sequence item. */
        fun keyChunk(scalar: YAMLScalar): String = VaultValuePsi.keyName(scalar) ?: "-"

        /** An HTML preview made of [paragraphs] (plain texts, escaped). */
        fun html(vararg paragraphs: String): HtmlChunk = HtmlChunk.fragment(*paragraphs.map { HtmlChunk.p().addText(it) }.toTypedArray())
    }
}

/** Base of the intentions on an existing `!vault` value (caret on the value, its tag or its key). */
abstract class VaultValueIntention : VaultIntentionBase() {
    /** Extra availability rule of one intention (coexistence, ids); PSI and settings only. */
    protected open fun isAvailableFor(project: Project, scalar: YAMLScalar): Boolean = true

    /** Runs the action for [ref] (on the EDT). */
    internal abstract fun perform(project: Project, editor: Editor?, ref: VaultValueRef)

    override fun scalarAt(element: PsiElement): YAMLScalar? = VaultValuePsi.vaultScalarAt(element)

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        val scalar = scalarAt(element) ?: return false
        val file = element.containingFile?.originalFile?.viewProvider?.virtualFile ?: return false
        if (AnsibleWorkspace.getInstance(project).rootFor(file) == null) return false
        return isAvailableFor(project, scalar)
    }

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val ref = scalarAt(element)?.let(VaultValueRef::of) ?: return
        perform(project, editor, ref)
    }

    /** The header written in [scalar], for previews. */
    protected fun headerOf(scalar: YAMLScalar): VaultHeaderInfo? = VaultValuePsi.header(scalar)
}

/** F7.1 Reveal vault value: the timed, masked popup. */
class VaultRevealIntention : VaultValueIntention() {
    override val writes: Boolean get() = false

    override fun getText(): String = message("intention.reveal")

    override fun getPriority(): PriorityAction.Priority = PriorityAction.Priority.HIGH

    override fun perform(project: Project, editor: Editor?, ref: VaultValueRef) = VaultValueActions.getInstance(project).reveal(ref, editor)

    override fun preview(scalar: YAMLScalar): HtmlChunk = html(message("intention.reveal.preview", VaultUiTimings.seconds(VaultUiTimings.REVEAL_MILLIS)))
}

/** F7.1 Copy decrypted vault value: cleared from the clipboard and the paste history after 30 s. */
class VaultCopyIntention : VaultValueIntention() {
    override val writes: Boolean get() = false

    override fun getText(): String = message("intention.copy")

    override fun perform(project: Project, editor: Editor?, ref: VaultValueRef) = VaultValueActions.getInstance(project).copy(ref, editor)

    override fun preview(scalar: YAMLScalar): HtmlChunk = html(message("intention.copy.preview", VaultUiTimings.seconds(VaultUiTimings.CLIPBOARD_MILLIS)))
}

/** F7.2 Edit vault value…: hidden while Ansible Vault Editor offers its own edit (F7.12). */
class VaultEditIntention : VaultValueIntention() {
    override val writes: Boolean get() = true

    override fun getText(): String = message("intention.edit")

    override fun getPriority(): PriorityAction.Priority = PriorityAction.Priority.HIGH

    /** Hidden only on the values Vault Editor's own "Modify vault value" handles, while that intention is enabled (F7.12). */
    override fun isAvailableFor(project: Project, scalar: YAMLScalar): Boolean {
        val coexistence = VaultEditorCoexistence.getInstance()
        if (coexistence.ourIntentionVisible(VaultIntentionKind.EDIT_VALUE)) return true
        return (scalar.parent as? YAMLKeyValue)?.let(coexistence::vaultEditorMarksValue) != true
    }

    override fun perform(project: Project, editor: Editor?, ref: VaultValueRef) = VaultValueActions.getInstance(project).edit(ref, editor)

    override fun preview(scalar: YAMLScalar): HtmlChunk {
        val header = headerOf(scalar)
        val written = header?.let { h -> h.label?.let { "${h.version};$it" } ?: h.version } ?: VaultEnvelope.VERSION_1_1
        return html(message("intention.edit.preview", written))
    }
}

/** F7.4 Decrypt to plain value…: writes the secret in plaintext after a confirmation that defaults to Cancel. */
class VaultDecryptToPlainIntention : VaultValueIntention() {
    override val writes: Boolean get() = true

    override fun getText(): String = message("intention.decrypt")

    override fun getPriority(): PriorityAction.Priority = PriorityAction.Priority.LOW

    override fun perform(project: Project, editor: Editor?, ref: VaultValueRef) = VaultValueActions.getInstance(project).decryptToPlain(ref, editor)

    override fun preview(scalar: YAMLScalar): HtmlChunk = html(message("intention.decrypt.preview", keyChunk(scalar)))
}

/** F7.5 Rekey vault value to id… (single value). */
class VaultRekeyIntention : VaultValueIntention() {
    override val writes: Boolean get() = true

    override fun getText(): String = message("intention.rekey")

    override fun getPriority(): PriorityAction.Priority = PriorityAction.Priority.LOW

    override fun perform(project: Project, editor: Editor?, ref: VaultValueRef) = VaultValueActions.getInstance(project).rekey(ref, editor)

    override fun preview(scalar: YAMLScalar): HtmlChunk = html(message("intention.rekey.preview", keyChunk(scalar)))
}

/** F7.6 Change vault id… (single value): header-only once verified, else a rekey after asking. */
class VaultChangeIdIntention : VaultValueIntention() {
    override val writes: Boolean get() = true

    override fun getText(): String = message("intention.change.id")

    override fun getPriority(): PriorityAction.Priority = PriorityAction.Priority.LOW

    override fun perform(project: Project, editor: Editor?, ref: VaultValueRef) = VaultValueActions.getInstance(project).changeId(ref, editor)

    override fun preview(scalar: YAMLScalar): HtmlChunk = html(message("intention.change.id.preview", keyChunk(scalar)))
}

/**
 * F7.3 Encrypt value with Ansible Vault: on a plain or quoted value (never a key, an anchor or an argument spec) in
 * a file kind Ansible loads `!vault` from; also on the key of a plaintext `vault_*` variable (ANS-X001's case).
 * Hidden while Ansible Vault Editor offers its own encrypt intention (F7.12).
 */
class VaultEncryptIntention : VaultIntentionBase() {
    override val writes: Boolean get() = true

    override fun getText(): String = message("intention.encrypt")

    override fun getPriority(): PriorityAction.Priority = PriorityAction.Priority.LOW

    override fun scalarAt(element: PsiElement): YAMLScalar? {
        PsiTreeUtil.getParentOfType(element, YAMLScalar::class.java, false)?.let { scalar ->
            return scalar.takeIf { (it.parent as? YAMLKeyValue)?.value == it }
        }
        val keyValue = PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java, false) ?: return null
        val key = keyValue.key ?: return null
        if (!key.textRange.contains(element.textRange) || !keyValue.keyText.startsWith(VAULT_PREFIX)) return null
        return keyValue.value as? YAMLScalar
    }

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        if (!VaultEditorCoexistence.getInstance().ourIntentionVisible(VaultIntentionKind.ENCRYPT_VALUE)) return false
        val scalar = scalarAt(element) ?: return false
        return PlainValueRef.accepts(scalar)
    }

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val ref = scalarAt(element)?.let(PlainValueRef::of) ?: return
        VaultValueActions.getInstance(project).encrypt(ref, editor)
    }

    override fun preview(scalar: YAMLScalar): HtmlChunk {
        val key = keyChunk(scalar)
        val context = PlainValueRef.fileContext(scalar) ?: return html(message("intention.encrypt.preview.history"))
        val file = scalar.containingFile.originalFile.viewProvider.virtualFile
        val project = scalar.project
        val config = VaultStatusService.getInstance(project).config(context.root)
        val labels = config.identities.map { it.label }.distinct()
        val first = when (val choice = VaultValueActions.getInstance(project).encryptChoice(context.root, context.environment, file, config, labels)) {
            is EncryptIdentity.Choice.Chosen -> message("intention.encrypt.preview", key, "${choice.label} (${VaultEnvelope.versionFor(choice.label)})")
            is EncryptIdentity.Choice.Ambiguous -> message("intention.encrypt.preview.choose", key, choice.candidates.joinToString(", "))
            is EncryptIdentity.Choice.NotFound, EncryptIdentity.Choice.NoIdentity -> AnsibilityVaultBundle.failure(VaultFailure.NO_IDENTITY)
        }
        return html(first, message("intention.encrypt.preview.history"))
    }

    private companion object {
        const val VAULT_PREFIX = "vault_"
    }
}
