package de.terletzkiy.ansibility.vault.ui.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import com.intellij.util.ui.UIUtil
import de.terletzkiy.ansibility.api.VaultSourceKind
import de.terletzkiy.ansibility.vault.identity.MasterPasswordPrompt
import de.terletzkiy.ansibility.vault.identity.PasswordManager
import de.terletzkiy.ansibility.vault.identity.PasswordManagers
import de.terletzkiy.ansibility.vault.identity.VaultPaths
import de.terletzkiy.ansibility.vault.secrets.MasterPasswordRequest
import de.terletzkiy.ansibility.vault.secrets.VaultPrompter
import de.terletzkiy.ansibility.vault.ui.AnsibilityVaultUiBundle.message
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JList

/**
 * The fields that say where one secret comes from: the source (the IDE password store, 1Password, Bitwarden,
 * KeePassXC, Proton Pass, a password file, an environment variable, a prompt), its reference, file or variable, the
 * password to store, and a Test that reads a password manager's reference once and reports only whether it worked.
 * Shared by the vault id dialog and the runner settings' become password; [onLayout] runs when fields appear or go.
 */
internal class SecretSourceFields(
    private val project: Project,
    kinds: List<VaultSourceKind>,
    private val promptHint: String,
    private val onLayout: () -> Unit = {},
) {
    /** The root's directory, against which a relative KeePassXC database or password file resolves. */
    var base: Path? = null

    /** Whether the IDE password store already holds this secret (the password field may stay empty then). */
    var stored: Boolean = false
        set(value) {
            field = value
            update()
        }

    val kind = ComboBox(kinds.toTypedArray()).apply {
        // A classic renderer: the DSL text renderer kept the width of the first value shown after a change of source.
        renderer = object : ColoredListCellRenderer<VaultSourceKind>() {
            override fun customizeCellRenderer(list: JList<out VaultSourceKind>, value: VaultSourceKind?, index: Int, selected: Boolean, hasFocus: Boolean) {
                value?.let { append(VaultConfigurable.kindText(it)) }
            }
        }
        addActionListener { update() }
    }
    val location = JBTextField(36)
    private val locationLabel = JBLabel()
    val password = JBPasswordField().apply { columns = 24 }
    private val passwordLabel = JBLabel(message("settings.vault.dialog.password"))
    private val hint = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val test = JButton(message("settings.vault.dialog.test")).apply { addActionListener { testManager() } }
    private val testResult = JBLabel()
    private var testedManager: PasswordManager? = null

    init {
        kind.selectedItem = kinds.first()
        update()
    }

    /** Adds the fields to [panel], one row each. */
    fun install(panel: Panel) = with(panel) {
        row(message("settings.vault.dialog.source")) { cell(kind) }
        row(locationLabel) { cell(location).align(Align.FILL) }
        row(passwordLabel) { cell(password) }
        row { cell(hint) }
        row {
            cell(test)
            cell(testResult)
        }
    }

    fun selected(): VaultSourceKind = kind.selectedItem as VaultSourceKind

    fun reset(kind: VaultSourceKind, location: String, stored: Boolean) {
        this.kind.selectedItem = kind
        this.location.text = location
        password.text = ""
        testResult.text = ""
        testedManager = null
        this.stored = stored
    }

    /** The reference, file or variable of the selected source; empty for the password store and the prompt. */
    fun locationValue(): String = when (selected()) {
        VaultSourceKind.PASSWORD_SAFE, VaultSourceKind.PROMPT -> ""
        else -> location.text.trim()
    }

    fun hasTypedPassword(): Boolean = password.password.let { typed -> typed.isNotEmpty().also { typed.fill('\u0000') } }

    /** The typed password when the source is the password store (the field is cleared either way); null when none. */
    fun takePassword(): CharArray? {
        val typed = password.password
        password.text = ""
        if (typed.isEmpty() || selected() != VaultSourceKind.PASSWORD_SAFE) {
            typed.fill('\u0000')
            return null
        }
        return typed
    }

    /** Why the fields cannot be stored, or null; [pendingPassword] when a password was typed earlier and not stored yet. */
    fun validate(pendingPassword: Boolean = false): ValidationInfo? {
        val value = location.text.trim()
        val manager = PasswordManager.of(selected())
        return when {
            manager != null -> if (PasswordManagers.isReference(manager, value)) null
            else ValidationInfo(message("settings.vault.invalid.reference.${manager.name.lowercase()}"), location)
            selected() == VaultSourceKind.PASSWORD_FILE -> if (value.isEmpty()) ValidationInfo(message("settings.vault.invalid.file"), location) else null
            selected() == VaultSourceKind.ENVIRONMENT -> if (!VARIABLE.matches(value)) ValidationInfo(message("settings.vault.invalid.variable"), location) else null
            selected() == VaultSourceKind.PASSWORD_SAFE ->
                if (!stored && !pendingPassword && !hasTypedPassword()) ValidationInfo(message("settings.vault.invalid.password"), password) else null
            else -> null
        }
    }

    private fun update() {
        val kind = selected()
        val manager = PasswordManager.of(kind)
        val usesLocation = manager != null || kind == VaultSourceKind.PASSWORD_FILE || kind == VaultSourceKind.ENVIRONMENT
        location.isVisible = usesLocation
        locationLabel.isVisible = usesLocation
        locationLabel.text = when {
            manager != null -> message("settings.vault.dialog.reference.${manager.name.lowercase()}")
            kind == VaultSourceKind.ENVIRONMENT -> message("settings.vault.dialog.variable")
            else -> message("settings.vault.dialog.file")
        }
        location.emptyText.text = when {
            manager != null -> manager.example
            kind == VaultSourceKind.ENVIRONMENT -> "VAULT_PROD_PASSWORD"
            else -> "~/.vault-pass-prod"
        }
        val safe = kind == VaultSourceKind.PASSWORD_SAFE
        password.isVisible = safe
        passwordLabel.isVisible = safe
        hint.text = when {
            safe -> message(if (stored) "settings.vault.dialog.password.keep" else "settings.vault.dialog.password.hint")
            manager != null -> message("settings.vault.dialog.hint.${manager.name.lowercase()}")
            kind == VaultSourceKind.PROMPT -> promptHint
            else -> message("settings.vault.dialog.file.hint")
        }
        test.isVisible = manager != null
        testResult.isVisible = manager != null
        if (manager != null && testedManager != manager) testResult.text = ""
        onLayout()
    }

    /** Reads the reference once and reports only whether it worked; the value is zeroed at once. */
    private fun testManager() {
        val manager = PasswordManager.of(selected()) ?: return
        val reference = resolved(location.text.trim())
        testedManager = manager
        if (!PasswordManagers.isReference(manager, reference)) {
            testResult.text = message("settings.vault.invalid.reference.${manager.name.lowercase()}")
            return
        }
        testResult.text = message("settings.vault.dialog.test.running", manager.displayName)
        test.isEnabled = false
        val prompt = MasterPasswordPrompt { m, target, retry ->
            var answer: CharArray? = null
            ApplicationManager.getApplication().invokeAndWait({
                answer = VaultPrompter.getInstance().askMasterPassword(project, MasterPasswordRequest(m.displayName, target, retry))
            }, ModalityState.any())
            answer
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val bytes = PasswordManagers.read(manager, reference, prompt)
            val ok = bytes != null
            bytes?.fill(0)
            ApplicationManager.getApplication().invokeLater({
                test.isEnabled = true
                testResult.text = when {
                    ok -> message("settings.vault.dialog.test.ok", manager.displayName)
                    PasswordManagers.executable(manager) == null -> message("settings.vault.manager.missing", manager.displayName, manager.command)
                    else -> message("settings.vault.dialog.test.failed.${manager.name.lowercase()}")
                }
            }, ModalityState.any())
        }
    }

    /** A KeePassXC reference with its database resolved against [base] and `~`; others unchanged. */
    private fun resolved(reference: String): String {
        if (selected() != VaultSourceKind.KEEPASSXC) return reference
        val (database, entry) = PasswordManagers.keePass(reference) ?: return reference
        val path = VaultPaths.resolve(database, base, System.getProperty("user.home")) ?: return reference
        return "$path#$entry"
    }

    private companion object {
        val VARIABLE = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}
