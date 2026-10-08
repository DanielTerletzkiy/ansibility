package de.terletzkiy.ansibility.vault.actions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.NlsContexts
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.concurrency.ThreadingAssertions
import de.terletzkiy.ansibility.api.VaultLockState
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.vault.ui.AnsibilityVaultUiBundle
import javax.swing.JComponent

/** One vault id offered by a chooser: its label, the header it writes and whether its secret is loaded. Not secret. */
class VaultIdentityChoice(val label: String, val lockState: VaultLockState) {
    /** `1.1` for `default`, else `1.2` (the header the id writes). */
    val version: String get() = VaultEnvelope.versionFor(label)

    override fun toString(): String = "VaultIdentityChoice($label, $lockState)"
}

/** "Which vault id?" for Rekey to id… and Change id… (F7.5, F7.6). */
class VaultIdentityRequest(
    @NlsContexts.DialogTitle val title: String,
    @NlsContexts.Label val message: String,
    val choices: List<VaultIdentityChoice>,
    val preselected: String?,
)

/** The Encrypt value confirmation (F7.3): warnings about the value (never the value itself) and the id choice. */
class VaultEncryptRequest(
    val keyName: String,
    val warnings: List<@NlsContexts.Label String>,
    val choices: List<VaultIdentityChoice>,
    val preselected: String?,
)

/** The Decrypt to plain value confirmation (F7.4). */
class VaultDecryptRequest(val keyName: String?, val fileName: String)

/**
 * The questions the vault value actions ask (on the EDT). Tests replace the service; the default implementation
 * uses IDE dialogs whose texts never contain a password or a plaintext.
 */
interface VaultActionPrompts {
    /** The id to rekey or relabel to, or null when cancelled. */
    fun chooseIdentity(project: Project, request: VaultIdentityRequest): String?

    /** Confirms Encrypt value, returning the chosen id (one of [VaultEncryptRequest.choices]) or null when cancelled. */
    fun confirmEncrypt(project: Project, request: VaultEncryptRequest): String?

    /** Confirms Decrypt to plain value: true only on an explicit yes; Cancel is the default button. */
    fun confirmDecryptToPlain(project: Project, request: VaultDecryptRequest): Boolean

    /** Change id… found that [target]'s secret does not decrypt the value ([decryptsWith] does): encrypt it with [target]? */
    fun confirmRekeyForChangeId(project: Project, target: String, decryptsWith: String): Boolean

    /** The edit popup has unsaved changes: discard them? */
    fun confirmDiscard(project: Project): Boolean

    /**
     * The file actions (V6b) are about to [operation] the files [names]: go ahead? Encrypt File asks when there are
     * several, Decrypt File in Place always (Cancel is the default button there). True only on an explicit yes.
     */
    fun confirmFiles(project: Project, operation: FileOperation, names: List<String>): Boolean

    /**
     * Change Id… of the file actions found vaults of the root [root] (its display name) that [target]'s secret does not
     * decrypt ([decryptsWith] does): encrypt them with [target]? Asked once per root and id; true only on an explicit yes.
     */
    fun confirmRekeyFilesForChangeId(project: Project, root: String, target: String, decryptsWith: String): Boolean

    /**
     * New › Ansibility Vault File: the name of the new file in [directory] (as shown to you), checked while you type by
     * [validate] (an error text, or null for a valid name); null when cancelled.
     */
    fun askVaultFileName(project: Project, directory: String, validate: (String) -> String?): String?

    companion object {
        fun getInstance(): VaultActionPrompts = ApplicationManager.getApplication().service()
    }
}

/** [VaultActionPrompts] with IDE dialogs. */
class VaultDialogPrompts : VaultActionPrompts {
    override fun chooseIdentity(project: Project, request: VaultIdentityRequest): String? {
        ThreadingAssertions.assertEventDispatchThread()
        val dialog = IdentityDialog(project, request.title, request.message, emptyList(), request.choices, request.preselected, null)
        return if (dialog.showAndGet()) dialog.selected else null
    }

    override fun confirmEncrypt(project: Project, request: VaultEncryptRequest): String? {
        ThreadingAssertions.assertEventDispatchThread()
        val dialog = IdentityDialog(
            project, message("encrypt.title"), message("encrypt.message", request.keyName), request.warnings, request.choices,
            request.preselected, message("encrypt.ok"),
        )
        return if (dialog.showAndGet()) dialog.selected else null
    }

    override fun confirmDecryptToPlain(project: Project, request: VaultDecryptRequest): Boolean {
        ThreadingAssertions.assertEventDispatchThread()
        val options = arrayOf(message("decrypt.ok"), message("decrypt.cancel"))
        val answer = Messages.showDialog(
            project, message("decrypt.message", request.keyName ?: "-", request.fileName), message("decrypt.title"), options,
            1, Messages.getWarningIcon(),
        )
        return answer == 0
    }

    override fun confirmRekeyForChangeId(project: Project, target: String, decryptsWith: String): Boolean {
        ThreadingAssertions.assertEventDispatchThread()
        val options = arrayOf(message("change.id.rekey.ok", target), message("decrypt.cancel"))
        val answer = Messages.showDialog(
            project, message("change.id.rekey.message", target, decryptsWith), message("change.id.rekey.title"), options, 1,
            Messages.getQuestionIcon(),
        )
        return answer == 0
    }

    override fun confirmDiscard(project: Project): Boolean {
        ThreadingAssertions.assertEventDispatchThread()
        val options = arrayOf(message("edit.discard.yes"), message("edit.discard.no"))
        return Messages.showDialog(project, message("edit.discard.message"), message("edit.discard.title"), options, 1, Messages.getQuestionIcon()) == 0
    }

    override fun confirmFiles(project: Project, operation: FileOperation, names: List<String>): Boolean {
        ThreadingAssertions.assertEventDispatchThread()
        val key = operation.confirmKey
        val text = message("$key.message", names.size, fileList(names))
        val options = arrayOf(message("$key.ok"), message("decrypt.cancel"))
        // Writing plaintext to disk is the one answer that is not the default.
        val cancelByDefault = operation == FileOperation.DECRYPT
        val icon = if (cancelByDefault) Messages.getWarningIcon() else Messages.getQuestionIcon()
        return Messages.showDialog(project, text, operation.confirmTitle, options, if (cancelByDefault) 1 else 0, icon) == 0
    }

    override fun confirmRekeyFilesForChangeId(project: Project, root: String, target: String, decryptsWith: String): Boolean {
        ThreadingAssertions.assertEventDispatchThread()
        val options = arrayOf(message("change.id.rekey.ok", target), message("decrypt.cancel"))
        val answer = Messages.showDialog(
            project, message("file.change.id.rekey.message", target, decryptsWith, root), message("change.id.rekey.title"), options, 1,
            Messages.getQuestionIcon(),
        )
        return answer == 0
    }

    override fun askVaultFileName(project: Project, directory: String, validate: (String) -> String?): String? {
        ThreadingAssertions.assertEventDispatchThread()
        val validator = object : InputValidatorEx {
            override fun getErrorText(inputString: String): String? = validate(inputString.trim())

            override fun checkInput(inputString: String): Boolean = getErrorText(inputString) == null

            override fun canClose(inputString: String): Boolean = checkInput(inputString)
        }
        return Messages.showInputDialog(project, message("file.new.prompt", directory), message("file.new.title"), null, "", validator)?.trim()
    }

    /** At most [LISTED] names, one per line, then how many more. */
    private fun fileList(names: List<String>): String {
        val shown = names.take(LISTED).joinToString("\n") { "  $it" }
        return if (names.size > LISTED) shown + "\n" + message("file.confirm.more", names.size - LISTED) else shown
    }

    /** A message, optional warnings and a vault-id combo box. */
    private class IdentityDialog(
        project: Project,
        @NlsContexts.DialogTitle title: String,
        @NlsContexts.Label private val text: String,
        private val warnings: List<String>,
        private val choices: List<VaultIdentityChoice>,
        preselected: String?,
        okText: String?,
    ) : DialogWrapper(project) {
        private val combo = ComboBox(choices.toTypedArray()).apply {
            renderer = textListCellRenderer { choice -> choice?.let { message("identity.item", it.label, stateText(it)) } }
            selectedItem = choices.firstOrNull { it.label == preselected } ?: choices.firstOrNull()
        }

        val selected: String? get() = (combo.selectedItem as? VaultIdentityChoice)?.label

        init {
            this.title = title
            okText?.let { setOKButtonText(it) }
            init()
        }

        override fun createCenterPanel(): JComponent = panel {
            row { label(text) }
            for (warning in warnings) row { label("⚠ $warning") }
            row(message("encrypt.identity")) { cell(combo).align(AlignX.FILL) }
        }

        override fun getPreferredFocusedComponent(): JComponent = combo

        private fun stateText(choice: VaultIdentityChoice): String = when (choice.lockState) {
            VaultLockState.UNLOCKED -> message("identity.state.unlocked")
            VaultLockState.LOCKED -> message("identity.state.locked")
            VaultLockState.NO_IDENTITY -> message("identity.state.none")
        }
    }
}

private fun message(key: String, vararg params: Any): String = AnsibilityVaultUiBundle.message(key, *params)

/** The names a file confirmation lists before "… and N more". */
private const val LISTED = 10

/** The title of [FileOperation]'s confirmation: the menu item's words ("Decrypt File in Place", "Change Vault Id"). */
internal val FileOperation.confirmTitle: String
    @NlsContexts.DialogTitle get() = message("$confirmKey.title")

/** The bundle prefix of [FileOperation]'s confirmation: `.title`, `.message`, `.ok`. */
private val FileOperation.confirmKey: String
    get() = when (this) {
        FileOperation.ENCRYPT -> "file.confirm.encrypt"
        FileOperation.DECRYPT -> "file.confirm.decrypt"
        FileOperation.REKEY -> "file.confirm.rekey"
        FileOperation.CHANGE_ID -> "file.confirm.change.id"
    }
