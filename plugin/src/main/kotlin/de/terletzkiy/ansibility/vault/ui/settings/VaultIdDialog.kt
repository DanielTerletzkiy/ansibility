package de.terletzkiy.ansibility.vault.ui.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import de.terletzkiy.ansibility.api.VaultSourceKind
import de.terletzkiy.ansibility.vault.ui.AnsibilityVaultUiBundle.message
import javax.swing.JComponent

/** Adds or edits one vault id of the Vault settings page: its label, where the secret comes from, and the password itself. */
internal class VaultIdDialog(
    project: Project,
    private val row: VaultConfigurable.IdRow?,
    private val takenLabels: Set<String>,
    /** The root's directory, against which a relative KeePassXC database path resolves. */
    base: java.nio.file.Path?,
) : DialogWrapper(project) {
    private val label = JBTextField(row?.label ?: "", 20)
    private var ready = false
    private val source = SecretSourceFields(project, VaultConfigurable.KINDS, message("settings.vault.prompt.hint")) { if (ready) pack() }

    init {
        title = message(if (row == null) "settings.vault.dialog.add" else "settings.vault.dialog.edit")
        source.base = base
        source.reset(row?.kind ?: VaultSourceKind.PASSWORD_SAFE, row?.location.orEmpty(), row?.stored == true)
        init()
        ready = true
    }

    override fun createCenterPanel(): JComponent = panel {
        row(message("settings.vault.dialog.id")) { cell(label).comment(message("settings.vault.dialog.id.comment")) }
        source.install(this)
    }

    override fun getPreferredFocusedComponent(): JComponent = if (row == null) label else source.password

    override fun doValidate(): ValidationInfo? {
        val text = label.text.trim()
        if (text.isEmpty() || !LABEL.matches(text)) return ValidationInfo(message("settings.vault.invalid.label"), label)
        if (text in takenLabels) return ValidationInfo(message("settings.vault.invalid.taken", text), label)
        return source.validate(pendingPassword = row?.password != null)
    }

    /** The edited row; a typed password is handed over (and the field cleared). */
    fun result(): VaultConfigurable.IdRow {
        val kind = source.selected()
        val typed = source.takePassword()
        return VaultConfigurable.IdRow(
            label = label.text.trim(),
            kind = kind,
            location = when (kind) {
                VaultSourceKind.PASSWORD_SAFE -> row?.location?.takeIf { row.kind == VaultSourceKind.PASSWORD_SAFE } ?: ""
                else -> source.locationValue()
            },
            password = typed ?: row?.password?.takeIf { kind == VaultSourceKind.PASSWORD_SAFE }?.copyOf(),
        )
    }

    private companion object {
        /** Ansible accepts any label without `@`; the page keeps to what is safe in a service name and a header. */
        val LABEL = Regex("[A-Za-z0-9_.-]+")
    }
}
