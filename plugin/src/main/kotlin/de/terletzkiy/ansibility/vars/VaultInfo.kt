package de.terletzkiy.ansibility.vars

import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.VaultEnvelopeKind
import de.terletzkiy.ansibility.api.VaultHeaderInfo
import de.terletzkiy.ansibility.index.PathFacts
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.vault.envelope.VaultEnvelopes
import de.terletzkiy.ansibility.yaml.YamlPsi
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * Vault facts for display (plan F4.8, F7.14). Nothing is ever decrypted and no value of a vault file, a `vault_*` key or
 * a `!vault` scalar is ever shown; only the envelope header (`$ANSIBLE_VAULT;1.1;AES256`) is read, to say how a value
 * is encrypted.
 *
 * Header parsing is the codec's ([VaultEnvelopes], `semantics.vault.VaultEnvelope`): the header is the value's first
 * line exactly as Ansible reads it. A value with a blank line or whitespace before `$ANSIBLE_VAULT` has no header,
 * because Ansible refuses it ("Input is not vault encrypted data").
 */
internal object VaultInfo {
    private const val VAULT_PREFIX = "vault_"

    /** The envelope of a `!vault` value: format [version], [cipher] and the optional vault-id [label]. */
    data class Header(val version: String, val cipher: String, val label: String?) {
        /** `AES256, 1.1` (plus the label when there is one). */
        val summary: String get() = listOfNotNull(cipher, version, label).joinToString(", ")
    }

    /** True when values of variable [name] in [file] must never be shown. */
    fun isSecret(name: String, file: VirtualFile): Boolean = name.startsWith(VAULT_PREFIX) || PathFacts.of(file).isVaultFile

    /** True when [keyValue]'s value is tagged `!vault` (or the deprecated `!vault-encrypted`), on the value or the key. */
    fun isVaultValue(keyValue: YAMLKeyValue): Boolean {
        val value = keyValue.value ?: return false
        return YamlPsi.tagOf(value) in VaultEnvelopes.VAULT_TAGS || YamlPsi.tagOf(keyValue) in VaultEnvelopes.VAULT_TAGS
    }

    /**
     * The envelope header of [keyValue]'s `!vault` value, or null when there is none: not a vault value, no
     * `$ANSIBLE_VAULT` first line, or a header without version or cipher. A malformed body still has its header.
     */
    fun headerOf(keyValue: YAMLKeyValue): Header? {
        if (!isVaultValue(keyValue)) return null
        val located = VaultEnvelopes.at(keyValue)?.takeIf { it.kind == VaultEnvelopeKind.INLINE } ?: return null
        return located.header.toHeader()
    }

    /** The header of envelope text (`$ANSIBLE_VAULT;1.1;AES256`, `$ANSIBLE_VAULT;1.2;AES256;label`, with or without payload). */
    fun parse(text: String): Header? = VaultEnvelopes.headerOf(text, VaultEnvelope.parse(text))?.toHeader()

    /** The display header, or null when the version or cipher field is empty; an empty label counts as none. */
    private fun VaultHeaderInfo.toHeader(): Header? {
        if (version.isEmpty() || cipher.isEmpty()) return null
        return Header(version, cipher, label?.takeIf { it.isNotEmpty() })
    }
}
