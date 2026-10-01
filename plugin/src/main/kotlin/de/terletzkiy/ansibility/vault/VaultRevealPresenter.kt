package de.terletzkiy.ansibility.vault

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VaultFailure
import de.terletzkiy.ansibility.api.VaultPlaintext

/**
 * The surface that shows a revealed inline value (F7.1: the timed, masked popup of WU V3). Registered under
 * `de.terletzkiy.ansibility.vaultRevealPresenter` (the vault UI registers `vault.ui.VaultPopupRevealPresenter` in `ansibility-vault-ui.xml`); [de.terletzkiy.ansibility.api.VaultOperations.reveal]
 * unlocks lazily, decrypts off the EDT and hands the result to the first registered presenter. Without a presenter,
 * reveal does nothing and decrypts nothing.
 */
interface VaultRevealPresenter {
    /**
     * Shows [plaintext] (decrypted by [identity]) for the value at [location], next to [editor]'s caret when given and
     * not disposed. Called on the EDT. Takes ownership of [plaintext] and closes it when the popup closes (timeout,
     * Esc, outside click, editor switch, lock).
     */
    fun present(project: Project, location: SourceLocation, editor: Editor?, identity: String, plaintext: VaultPlaintext)

    /** Tells the user why the value at [location] could not be revealed. Called on the EDT. */
    fun failed(project: Project, location: SourceLocation, editor: Editor?, failure: VaultFailure)

    companion object {
        val EP_NAME: ExtensionPointName<VaultRevealPresenter> = ExtensionPointName("de.terletzkiy.ansibility.vaultRevealPresenter")
    }
}
