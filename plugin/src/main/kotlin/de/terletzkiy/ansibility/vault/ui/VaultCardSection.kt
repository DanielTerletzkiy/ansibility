package de.terletzkiy.ansibility.vault.ui

import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.vfs.VfsUtilCore
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.CardContext
import de.terletzkiy.ansibility.api.CardPlacement
import de.terletzkiy.ansibility.api.CardSection
import de.terletzkiy.ansibility.api.CardSubject
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.ValueShape
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.api.VaultIdentity
import de.terletzkiy.ansibility.api.VaultLockState
import de.terletzkiy.ansibility.api.VaultSecretSource
import de.terletzkiy.ansibility.api.VaultSourceKind
import de.terletzkiy.ansibility.api.VaultSourceOrigin
import de.terletzkiy.ansibility.api.VaultStatus
import de.terletzkiy.ansibility.api.VaultStatusService
import de.terletzkiy.ansibility.vault.identity.PasswordManager

/**
 * F7.14: the card's **Vault** row (`cardSection` id `ansibilityVault`, SECTION, after `ansibilityHostDefinition`).
 *
 * For a definition card on a `!vault` value it describes that value; for a reference (`{{ vault_x }}`) it describes
 * every inline vault definition of the variable in the card's root (at most [MAX_ENTRIES], then "N more"). Each
 * entry reads, for example, `AES256 · 1.1 · id default (from .vault-pass via .env.local) · 🔒 locked · plaintext
 * 16–31 bytes · Unlock and reveal`, or `🔓 decrypts with id default` once an explicit action verified it.
 *
 * Everything comes from [VaultStatusService] (header facts, ids and their lock state, the verification cache): the
 * card never decrypts, never reads a secret, and holds neither the value nor the ciphertext. The Reveal link
 * ([VaultCardLinks]) is the only way from the card to the value, and it opens the timed popup, never the card.
 */
class VaultCardSection : CardSection {
    override val placement: CardPlacement get() = CardPlacement.SECTION

    override fun section(subject: CardSubject, context: CardContext): HtmlChunk? {
        val variable = subject as? CardSubject.Variable ?: return null
        if (variable.local) return null
        val status = VaultStatusService.getInstance(context.project)
        val definition = variable.definition
        var more = 0
        val entries: List<Entry> = if (definition != null) {
            listOfNotNull(status.status(definition)?.let { Entry(definition, it) })
        } else {
            if (variable.path.isNotEmpty()) return null
            val definitions = VarService.getInstance(context.project).symbol(variable.root, variable.name).definitions
            val found = ArrayList<Entry>()
            for (candidate in definitions) {
                ProgressManager.checkCanceled()
                if (candidate.valueShape != ValueShape.VAULT) continue
                // Only the shown entries read their envelope; the rest are counted from the index's value shape.
                if (found.size == MAX_ENTRIES) more++ else status.status(candidate.location)?.let { found += Entry(candidate.location, it) }
            }
            found
        }
        if (entries.isEmpty()) return null
        val withLocation = definition == null
        val defaultIdentity = status.config(variable.root).defaultIdentity
        val lines = entries.map { line(it, variable.root, defaultIdentity, withLocation, hint = !withLocation) }
        val rest = more.takeIf { it > 0 }?.let { HtmlChunk.text(message("card.more", it)) }
        return CardSection.row(message("card.section"), joined(lines + listOfNotNull(rest), HtmlChunk.br()))
    }

    /** One vault value and its status. */
    private class Entry(val location: SourceLocation, val status: VaultStatus)

    private fun line(entry: Entry, root: AnsibleRoot, defaultIdentity: String, withLocation: Boolean, hint: Boolean): HtmlChunk {
        val status = entry.status
        val parts = ArrayList<HtmlChunk>()
        if (withLocation) parts += HtmlChunk.text(label(entry.location, root))
        val header = status.header
        parts += HtmlChunk.text(listOfNotNull(header.cipher, header.version, header.label?.let { message("card.label", it) }).joinToString(SEPARATOR))
        status.identity?.let { parts += HtmlChunk.text(identityText(it)) }
        parts += HtmlChunk.text(stateText(status, defaultIdentity))
        status.plaintextLength?.let { range ->
            parts += HtmlChunk.text(
                if (range.first == range.last) message("card.plaintext.length.exact", range.first) else message("card.plaintext.length", range.first, range.last),
            )
        }
        val revealable = status.plaintextLength != null && status.lockState != VaultLockState.NO_IDENTITY
        if (revealable) {
            val locked = status.lockState == VaultLockState.LOCKED
            parts += HtmlChunk.link(VaultCardLinks.reveal(entry.location), message(if (locked) "card.link.unlock" else "card.link.reveal"))
            if (hint) parts += HtmlChunk.text(message(if (locked) "card.hint.unlock" else "card.hint.reveal")).wrapWith(DocumentationMarkup.GRAYED_ELEMENT)
        }
        return joined(parts, HtmlChunk.text(SEPARATOR))
    }

    /** [chunks] with [separator] between them. */
    private fun joined(chunks: List<HtmlChunk>, separator: HtmlChunk): HtmlChunk =
        HtmlChunk.fragment(*chunks.flatMapIndexed { index, chunk -> if (index == 0) listOf(chunk) else listOf(separator, chunk) }.toTypedArray())

    private fun stateText(status: VaultStatus, defaultIdentity: String): String {
        val decryptsWith = status.decryptsWith
        val label = status.header.labelOrDefault(defaultIdentity)
        return when {
            status.plaintextLength == null -> message("card.malformed")
            status.verified && decryptsWith == null -> message("card.decrypts.none")
            decryptsWith != null && status.lockState != VaultLockState.UNLOCKED -> message("card.decrypts.with.locked", decryptsWith)
            decryptsWith != null && decryptsWith != label -> message("card.decrypts.with.mislabelled", decryptsWith, label)
            decryptsWith != null -> message("card.decrypts.with", decryptsWith)
            status.lockState == VaultLockState.UNLOCKED -> message("card.unlocked")
            status.lockState == VaultLockState.LOCKED -> message("card.locked")
            else -> message("card.no.identity")
        }
    }

    private fun identityText(identity: VaultIdentity): String =
        sourceText(identity.source)?.let { message("card.identity.source", identity.label, it) } ?: message("card.identity", identity.label)

    /** `from .vault-pass via .env.local`, `client script ~/bin/vault-client`, `password safe`, …; never content. */
    private fun sourceText(source: VaultSecretSource): String? {
        val location = source.location
        return when (source.kind) {
            VaultSourceKind.PASSWORD_FILE -> location?.let { file ->
                originText(source.origin)?.let { message("source.file.via", file, it) } ?: message("source.file", file)
            }
            VaultSourceKind.SCRIPT -> location?.let { message("source.script", it) }
            VaultSourceKind.CLIENT_SCRIPT -> location?.let { message("source.client.script", it) }
            VaultSourceKind.PASSWORD_SAFE -> message("source.password.safe")
            VaultSourceKind.PROMPT -> message("source.prompt")
            VaultSourceKind.ENVIRONMENT -> location?.let { message("source.environment", it) }
            VaultSourceKind.ONE_PASSWORD, VaultSourceKind.BITWARDEN, VaultSourceKind.KEEPASSXC, VaultSourceKind.PROTON_PASS ->
                PasswordManager.of(source.kind)?.let { manager -> location?.let { message("source.manager", manager.displayName, it) } }
        }
    }

    private fun originText(origin: VaultSourceOrigin): String? = when (origin) {
        VaultSourceOrigin.SETTINGS -> message("origin.settings")
        VaultSourceOrigin.ANSIBLE_CFG -> message("origin.ansible.cfg")
        VaultSourceOrigin.ENVIRONMENT -> message("origin.environment")
        VaultSourceOrigin.ENV_LOCAL -> message("origin.env.local")
        VaultSourceOrigin.ENV_LOCAL_SKEL -> message("origin.env.local.skel")
        VaultSourceOrigin.CONVENTIONAL_NAME, VaultSourceOrigin.PASSWORD_SAFE, VaultSourceOrigin.PROMPT -> null
    }

    /** `environments/prod/group_vars/all/vault.yml:4`, relative to the root. */
    private fun label(location: SourceLocation, root: AnsibleRoot): String {
        val path = VfsUtilCore.getRelativePath(location.file, root.dir) ?: location.file.name
        val line = FileDocumentManager.getInstance().getDocument(location.file)?.takeIf { location.offset <= it.textLength }?.getLineNumber(location.offset)
        return if (line == null) path else "$path:${line + 1}"
    }

    private companion object {
        const val MAX_ENTRIES = 6
        const val SEPARATOR = " · "

        fun message(key: String, vararg params: Any): String = AnsibilityVaultUiBundle.message(key, *params)
    }
}
