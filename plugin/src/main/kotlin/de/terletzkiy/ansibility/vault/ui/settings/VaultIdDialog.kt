package de.terletzkiy.ansibility.vault.ui.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import de.terletzkiy.ansibility.api.VaultSourceKind
import de.terletzkiy.ansibility.vault.identity.MasterPasswordPrompt
import de.terletzkiy.ansibility.vault.identity.PasswordManager
import de.terletzkiy.ansibility.vault.identity.PasswordManagers
import de.terletzkiy.ansibility.vault.secrets.MasterPasswordRequest
import de.terletzkiy.ansibility.vault.secrets.VaultPrompter
import de.terletzkiy.ansibility.vault.ui.AnsibilityVaultUiBundle.message
import javax.swing.JButton
import javax.swing.JComponent

/** Adds or edits one vault id of the Vault settings page: its label, where the secret comes from, and the password itself. */
internal class VaultIdDialog(
    private val project: Project,
    private val row: VaultConfigurable.IdRow?,
    private val takenLabels: Set<String>,
    /** The root's directory, against which a relative KeePassXC database path resolves. */
    private val base: java.nio.file.Path?,
) : DialogWrapper(project) {
    private val label = JBTextField(row?.label ?: "", 20)
    private val kind = ComboBox(VaultConfigurable.KINDS.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { VaultConfigurable.kindText(it) }
        selectedItem = row?.kind ?: VaultSourceKind.PASSWORD_SAFE
        addActionListener { update() }
    }
    private val location = JBTextField(row?.location ?: "", 36)
    private val locationLabel = JBLabel()
    private val password = JBPasswordField().apply { columns = 24 }
    private val passwordLabel = JBLabel(message("settings.vault.dialog.password"))
    private val passwordHint = JBLabel()
    private val test = JButton(message("settings.vault.dialog.test")).apply { addActionListener { testManager() } }
    private val testResult = JBLabel()

    init {
        title = message(if (row == null) "settings.vault.dialog.add" else "settings.vault.dialog.edit")
        init()
        update()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(message("settings.vault.dialog.id")) { cell(label).comment(message("settings.vault.dialog.id.comment")) }
        row(message("settings.vault.dialog.source")) { cell(kind) }
        row(locationLabel) { cell(location).align(Align.FILL) }
        row(passwordLabel) { cell(password) }
        row { cell(passwordHint) }
        row { cell(test); cell(testResult) }
    }

    override fun getPreferredFocusedComponent(): JComponent = if (row == null) label else password

    private fun selected(): VaultSourceKind = kind.selectedItem as VaultSourceKind

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
        passwordHint.isVisible = safe || manager != null || kind == VaultSourceKind.PROMPT || kind == VaultSourceKind.PASSWORD_FILE
        passwordHint.text = when {
            safe -> message(if (row?.stored == true) "settings.vault.dialog.password.keep" else "settings.vault.dialog.password.hint")
            manager != null -> message("settings.vault.dialog.hint.${manager.name.lowercase()}")
            kind == VaultSourceKind.PROMPT -> message("settings.vault.prompt.hint")
            else -> message("settings.vault.dialog.file.hint")
        }
        test.isVisible = manager != null
        testResult.isVisible = manager != null
        if (manager != null && testedManager != manager) testResult.text = ""
        pack()
    }

    private var testedManager: PasswordManager? = null

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
        val path = de.terletzkiy.ansibility.vault.identity.VaultPaths.resolve(database, base, System.getProperty("user.home")) ?: return reference
        return "$path#$entry"
    }

    override fun doValidate(): ValidationInfo? {
        val text = label.text.trim()
        if (text.isEmpty() || !LABEL.matches(text)) return ValidationInfo(message("settings.vault.invalid.label"), label)
        if (text in takenLabels) return ValidationInfo(message("settings.vault.invalid.taken", text), label)
        val value = location.text.trim()
        return when (selected()) {
            VaultSourceKind.ONE_PASSWORD, VaultSourceKind.BITWARDEN, VaultSourceKind.KEEPASSXC, VaultSourceKind.PROTON_PASS -> {
                val manager = PasswordManager.of(selected())
                if (manager != null && !PasswordManagers.isReference(manager, value)) {
                    ValidationInfo(message("settings.vault.invalid.reference.${manager.name.lowercase()}"), location)
                } else {
                    null
                }
            }
            VaultSourceKind.PASSWORD_FILE -> if (value.isEmpty()) ValidationInfo(message("settings.vault.invalid.file"), location) else null
            VaultSourceKind.ENVIRONMENT -> if (!VARIABLE.matches(value)) ValidationInfo(message("settings.vault.invalid.variable"), location) else null
            VaultSourceKind.PASSWORD_SAFE -> if (row?.stored != true && row?.password == null && password.password.isEmpty()) {
                ValidationInfo(message("settings.vault.invalid.password"), password)
            } else {
                null
            }
            else -> null
        }
    }

    /** The edited row; a typed password is handed over (and the field cleared). */
    fun result(): VaultConfigurable.IdRow {
        val kind = selected()
        val typed = password.password.takeIf { it.isNotEmpty() && kind == VaultSourceKind.PASSWORD_SAFE }
        password.text = ""
        return VaultConfigurable.IdRow(
            label = label.text.trim(),
            kind = kind,
            location = when (kind) {
                VaultSourceKind.PASSWORD_SAFE -> row?.location?.takeIf { row.kind == VaultSourceKind.PASSWORD_SAFE } ?: ""
                VaultSourceKind.PROMPT -> ""
                else -> location.text.trim()
            },
            password = typed ?: row?.password?.takeIf { kind == VaultSourceKind.PASSWORD_SAFE }?.copyOf(),
        )
    }

    private companion object {
        /** Ansible accepts any label without `@`; the page keeps to what is safe in a service name and a header. */
        val LABEL = Regex("[A-Za-z0-9_.-]+")
        val VARIABLE = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}
