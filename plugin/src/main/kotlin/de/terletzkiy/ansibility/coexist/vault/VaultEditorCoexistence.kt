package de.terletzkiy.ansibility.coexist.vault

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalarList

/** Ansibility's vault intentions that overlap an Ansible Vault Editor intention (F7.12). */
enum class VaultIntentionKind(
    /** The Vault Editor intention class that does the same (its `plugin.xml`, version 1.22). */
    val vaultEditorIntention: String,
) {
    /** Edit an inline value (F7.2); theirs: "Modify vault value". */
    EDIT_VALUE("$VE_INTENTIONS.PropertyVaultModifyIntentionAction"),

    /** Open a whole-file vault decrypted for editing (F7.7); theirs: "Modify vault". */
    EDIT_FILE("$VE_INTENTIONS.FileVaultModifyIntentionAction"),

    /** Encrypt a plain value (F7.3); theirs: "Encrypt as Ansible vault". */
    ENCRYPT_VALUE("$VE_INTENTIONS.PropertyVaultCreateIntentionAction"),

    /** Re-encrypt an inline value with a new password of the same id; theirs: "Change vault value password". */
    CHANGE_PASSWORD_VALUE("$VE_INTENTIONS.PropertyVaultChangePasswordIntentionAction"),

    /** Re-encrypt a whole-file vault with a new password of the same id; theirs: "Change vault password". */
    CHANGE_PASSWORD_FILE("$VE_INTENTIONS.FileVaultChangePasswordIntentionAction"),
}

private const val VE_INTENTIONS = "ru.sadv1r.idea.plugin.ansible.vault.editor.intention"

/**
 * The coexistence gate with Ansible Vault Editor (F7.12, D27 "coexist"): while it is loaded, its gutter icons and its
 * overlapping intentions win and ours step aside; our features without an equivalent (Reveal, Copy, Rekey to id,
 * Change id, Decrypt to plain, banner, tab, manager, status, inspections, folding, host awareness) always stay.
 *
 * Other work units call it from intentions' `isAvailable` and line marker providers (read action, any thread):
 * - [ourIntentionVisible]: our Edit, Encrypt and Change-password intentions show only when the corresponding Vault
 *   Editor intention is disabled in Settings › Editor › Intentions, or Vault Editor is absent;
 * - [vaultEditorMarksValue] / [vaultEditorMarksFile]: our line markers return null where Vault Editor's sit.
 *
 * Vault Editor is detected through the [VaultEditorLookup] that the optional `ansibility-vault.xml` registers, which
 * the platform loads and unloads with Vault Editor, so the answer follows plugin state changes without a listener.
 * Nothing here reads Vault Editor's saved passwords or changes its settings.
 */
@Service(Service.Level.APP)
class VaultEditorCoexistence {
    /** True while Ansible Vault Editor is loaded. */
    fun isVaultEditorLoaded(): Boolean = lookup() != null

    /**
     * Whether our intention of [kind] may show: always without Vault Editor, else only while its intention for the
     * same job is disabled. An intention that this Vault Editor version does not have never hides ours.
     */
    fun ourIntentionVisible(kind: VaultIntentionKind): Boolean {
        val lookup = lookup() ?: return true
        val theirsEnabled = lookup.isIntentionEnabled(kind.vaultEditorIntention) ?: return true
        return !theirsEnabled
    }

    /** True when Vault Editor shows its gutter icon for [keyValue]'s value, so our line marker must not. */
    fun vaultEditorMarksValue(keyValue: YAMLKeyValue): Boolean = isVaultEditorLoaded() && VaultEditorMarkers.marksValue(keyValue)

    /** True when Vault Editor shows its gutter icon on [file] (a whole-file vault), so our line marker must not. */
    fun vaultEditorMarksFile(file: PsiFile): Boolean = isVaultEditorLoaded() && VaultEditorMarkers.marksFile(file)

    private fun lookup(): VaultEditorLookup? = VaultEditorLookup.EP_NAME.extensionList.firstOrNull { it.isLoaded() }

    companion object {
        /** Ansible Vault Editor's plugin id. */
        const val PLUGIN_ID_STRING: String = "ru.sadv1r.ansible-vault-editor-idea-plugin"

        val PLUGIN_ID: PluginId = PluginId.getId(PLUGIN_ID_STRING)

        fun getInstance(): VaultEditorCoexistence = service()
    }
}

/**
 * Where Vault Editor 1.22 puts its gutter icons (its `PropertyVaultLineMarkerProvider` and `FileVaultLineMarkerProvider`,
 * read from its bytecode): on the key of a key-value whose value is a literal block (`|`) starting with
 * `$ANSIBLE_VAULT`, whatever the tag, and on the first leaf of a file whose text starts with `$ANSIBLE_VAULT`. It does
 * not mark quoted or folded values, which our markers keep.
 */
object VaultEditorMarkers {
    /** True when Vault Editor marks [keyValue]: its value is a literal block whose text starts with `$ANSIBLE_VAULT`. */
    fun marksValue(keyValue: YAMLKeyValue): Boolean {
        val value = keyValue.value as? YAMLScalarList ?: return false
        return value.textValue.startsWith(VaultEnvelope.MAGIC)
    }

    /** True when Vault Editor marks [file]: its text starts with `$ANSIBLE_VAULT`. */
    fun marksFile(file: PsiFile): Boolean = file.viewProvider.contents.startsWith(VaultEnvelope.MAGIC)
}
