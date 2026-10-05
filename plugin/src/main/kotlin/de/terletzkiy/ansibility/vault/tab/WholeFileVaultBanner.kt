package de.terletzkiy.ansibility.vault.tab

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VaultLockState
import de.terletzkiy.ansibility.api.VaultStatusService
import de.terletzkiy.ansibility.vault.envelope.VaultEnvelopes
import org.jetbrains.annotations.Nls
import java.util.function.Function
import javax.swing.JComponent

/**
 * The banner of a whole-file vault (F7.7) and of its decrypted tab (F7.8).
 *
 * On the vault: `🔒 Ansible Vault · AES256 · 1.2 · id new · decrypts with: default` from the header facts and the
 * verification cache ([VaultStatusService]; "decrypts with" only once something verified it), [Unlock…] while its ids
 * are locked, [Open decrypted], and the file operations of V6b when they are registered. Nothing here decrypts: the
 * first 14 bytes decide whether the file is a vault at all.
 *
 * On the tab: which file and id it saves to, and why the last save did not reach the real file, with [Retry] or, when
 * the file changed on disk, [Overwrite].
 */
class WholeFileVaultBanner : EditorNotificationProvider, DumbAware {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        if (file is DecryptedVaultFile) return tabBanner(project, file)
        val banner = vaultBanner(project, file) ?: return null
        return Function { editor -> panel(project, file, editor, banner) }
    }

    /** What the banner of a whole-file vault shows. Not secret. */
    internal class VaultBanner(@Nls val text: String, val unlock: Boolean, val open: Boolean, @Nls val notice: String?)

    private fun panel(project: Project, file: VirtualFile, editor: FileEditor, banner: VaultBanner): JComponent {
        val panel = EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info)
        panel.text = banner.notice?.let { "${banner.text} \u00B7 $it" } ?: banner.text
        val tabs = DecryptedVaultTabs.getInstance(project)
        if (banner.unlock) panel.createActionLabel(TabTexts.message("banner.unlock")) { tabs.unlock(file) }
        if (banner.open) panel.createActionLabel(TabTexts.message("banner.open")) { tabs.open(file) }
        val actions = ActionManager.getInstance()
        for ((id, key) in FILE_ACTIONS) {
            if (actions.getAction(id) != null) panel.createActionLabel(TabTexts.message(key), id)
        }
        return panel
    }

    private fun tabBanner(project: Project, file: DecryptedVaultFile): Function<in FileEditor, out JComponent?>? {
        val session = file.session?.takeUnless { it.isDisposed } ?: return null
        val text = tabText(file) ?: return null
        val problem = session.problem
        return Function { editor ->
            val status = if (problem == null) EditorNotificationPanel.Status.Warning else EditorNotificationPanel.Status.Error
            val panel = EditorNotificationPanel(editor, status)
            panel.text = text
            val tabs = DecryptedVaultTabs.getInstance(project)
            when (problem) {
                null -> Unit
                TabProblem.ChangedOnDisk -> panel.createActionLabel(TabTexts.message("banner.tab.overwrite")) { tabs.overwriteDiskVersion(file) }
                else -> panel.createActionLabel(TabTexts.message("banner.tab.retry")) { tabs.retrySave(file) }
            }
            panel
        }
    }

    internal companion object {
        /** The whole-file actions of V6b, by action id; each label shows only when its action is registered. */
        private val FILE_ACTIONS = listOf(
            "Ansibility.Vault.RekeyFile" to "banner.rekey",
            "Ansibility.Vault.ChangeFileId" to "banner.change.id",
            "Ansibility.Vault.DecryptFileInPlace" to "banner.decrypt.in.place",
        )

        /** The banner of the whole-file vault [file], or null for any other file. Read action. */
        internal fun vaultBanner(project: Project, file: VirtualFile): VaultBanner? {
            if (!VaultEnvelopes.isWholeFileVault(file)) return null
            val status = VaultStatusService.getInstance(project).status(SourceLocation(file, 0)) ?: return null
            val root = AnsibleWorkspace.getInstance(project).rootFor(file)
            val defaultIdentity = root?.let { VaultStatusService.getInstance(project).config(it).defaultIdentity }
            val header = status.header
            val parts = mutableListOf(TabTexts.message("banner.title"), header.cipher, header.version)
            parts += TabTexts.message("banner.id", defaultIdentity?.let(header::labelOrDefault) ?: header.labelOrDefault())
            when {
                status.plaintextLength == null -> parts += TabTexts.message("banner.malformed")
                status.decryptsWith != null -> parts += TabTexts.message("banner.decrypts.with", status.decryptsWith)
                status.verified -> parts += TabTexts.message("banner.decrypts.none")
            }
            val decryptable = status.plaintextLength != null && root != null && status.lockState != VaultLockState.NO_IDENTITY
            val binary = file.getUserData(DecryptedVaultTabs.BINARY_PLAINTEXT) == true
            return VaultBanner(
                text = parts.joinToString(" \u00B7 "),
                unlock = decryptable && status.lockState == VaultLockState.LOCKED,
                open = decryptable && !binary,
                notice = if (binary) TabTexts.message("banner.binary") else null,
            )
        }

        /** The text of the tab banner of [file], or null once its session ended. */
        internal fun tabText(file: DecryptedVaultFile): String? {
            val session = file.session?.takeUnless { it.isDisposed } ?: return null
            val saves = TabTexts.message("banner.tab", session.original.name, session.identity)
            val problem = session.problem ?: return saves
            return "$saves \u00B7 ${TabTexts.problem(problem, session.original.name)}"
        }
    }
}
