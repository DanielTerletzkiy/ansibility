package de.terletzkiy.ansibility.vault.secrets

import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.ui.NamedColorUtil
import de.terletzkiy.ansibility.vault.AnsibilityVaultBundle.message
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.ButtonGroup
import javax.swing.JComponent

/**
 * The production [VaultPrompter]: modal dialogs built with the Kotlin UI DSL v2. Plain Swing only (no `Document`, no
 * editor), so nothing typed reaches PSI, completion or an AI context. Must be called on the EDT.
 */
class VaultDialogPrompter : VaultPrompter {
    override fun askConsent(project: Project, request: VaultConsentRequest): VaultConsentDecision {
        ThreadingAssertions.assertEventDispatchThread()
        val dialog = VaultConsentDialog(project, request)
        dialog.show()
        return dialog.decision()
    }

    override fun askPassword(project: Project, request: VaultPasswordRequest): VaultPasswordAnswer? {
        ThreadingAssertions.assertEventDispatchThread()
        val dialog = VaultPasswordDialog(project, request)
        dialog.show()
        return dialog.answer()
    }

    override fun askMasterPassword(project: Project, request: MasterPasswordRequest): CharArray? {
        ThreadingAssertions.assertEventDispatchThread()
        val dialog = MasterPasswordDialog(project, request)
        dialog.show()
        return dialog.password()
    }
}

/** A password manager's master password, for this session only (it is never stored). */
internal class MasterPasswordDialog(project: Project, private val request: MasterPasswordRequest) : DialogWrapper(project, true) {
    private val field = JBPasswordField()

    init {
        title = message("master.title", request.managerName)
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            label(
                if (request.managerName == "KeePassXC") message("master.prompt.database", request.target)
                else message("master.prompt", request.managerName, request.target),
            )
        }
        row { cell(field).align(AlignX.FILL) }
        if (request.retry) row { label(message("master.retry")).applyToComponent { foreground = NamedColorUtil.getErrorForeground() } }
        row { comment(message("master.comment")) }
    }

    override fun getPreferredFocusedComponent(): JComponent = field

    override fun doValidate(): ValidationInfo? {
        val typed = field.password
        val empty = typed.isEmpty()
        typed.fill('\u0000')
        return if (empty) ValidationInfo(message("password.empty"), field) else null
    }

    /** The typed password (null when cancelled); the field is cleared either way. */
    fun password(): CharArray? {
        val typed = field.password
        field.text = ""
        if (!isOK) {
            typed.fill('\u0000')
            return null
        }
        return typed
    }
}

/**
 * The D25 dialog: every root with discovered sources nobody consented to, and exactly what will be read for each.
 * [Use for all listed] [Choose…] [Not now]; "Choose…" enables a checkbox per root.
 */
internal class VaultConsentDialog(project: Project, private val request: VaultConsentRequest) : DialogWrapper(project, true) {
    private val choosing = AtomicBooleanProperty(false)
    private val checkBoxes = LinkedHashMap<String, JBCheckBox>()

    private val chooseAction: Action = object : DialogWrapperAction(message("consent.choose")) {
        override fun doAction(e: ActionEvent?) {
            choosing.set(true)
            isEnabled = false
            setOKButtonText(message("consent.use.selected"))
        }
    }

    init {
        title = message("consent.title")
        setOKButtonText(message("consent.use.all"))
        setCancelButtonText(message("consent.not.now"))
        init()
    }

    override fun createActions(): Array<Action> =
        if (request.roots.size > 1) arrayOf(okAction, chooseAction, cancelAction) else arrayOf(okAction, cancelAction)

    override fun createCenterPanel(): JComponent = panel {
        row { label(intro()) }
        for (root in request.roots) {
            row {
                val box = checkBox(root.displayName).enabledIf(choosing).component
                box.isSelected = true
                checkBoxes[root.rootKey] = box
            }
            indent {
                for (item in root.items) row { label(itemText(item)) }
            }
        }
        row { comment(message("consent.footer")) }
    }

    private fun intro(): String = when (request.reason) {
        ConsentReason.FIRST_USE -> message("consent.intro")
        ConsentReason.NAMED_BY_ENV_LOCAL -> message("consent.intro.named")
        ConsentReason.CHANGED -> message("consent.intro.changed")
    }

    /** The decision after the dialog closed. */
    fun decision(): VaultConsentDecision = when {
        !isOK -> VaultConsentDecision.NotNow
        choosing.get() -> VaultConsentDecision.Choose(checkBoxes.filterValues { it.isSelected }.keys.toSet())
        else -> VaultConsentDecision.UseAll
    }

    companion object {
        fun itemText(item: ConsentItem): String {
            val text = when (item.kind) {
                ConsentItem.Kind.ENV_LOCAL_KEY ->
                    if (item.expected != null) message("consent.item.env.local.expected", item.label, item.display, item.expected)
                    else message("consent.item.env.local", item.label, item.display)
                ConsentItem.Kind.PASSWORD_FILE -> message("consent.item.file", item.label, item.display)
                ConsentItem.Kind.ENVIRONMENT -> message("consent.item.environment", item.label, item.display)
            }
            return if (item.changed) message("consent.item.changed", text) else text
        }
    }
}

/** The D26 prompt: a masked field and the remember choice (password safe, this session only, don't remember). */
internal class VaultPasswordDialog(project: Project, private val request: VaultPasswordRequest) : DialogWrapper(project, true) {
    private val field = JBPasswordField()
    private val keychain = JBRadioButton(
        message(if (request.passwordSafeMemoryOnly) "password.remember.keychain.memory" else "password.remember.keychain"), true,
    )
    private val session = JBRadioButton(message("password.remember.session"))
    private val none = JBRadioButton(message("password.remember.none"))

    init {
        title = message("password.title")
        ButtonGroup().apply {
            add(keychain)
            add(session)
            add(none)
        }
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { label(message("password.prompt", request.label, request.rootDisplayName)) }
        row { cell(field).align(AlignX.FILL) }
        request.error?.let { error -> row { label(error).applyToComponent { foreground = NamedColorUtil.getErrorForeground() } } }
        row { cell(keychain) }
        row { cell(session) }
        row { cell(none) }
    }

    override fun getPreferredFocusedComponent(): JComponent = field

    override fun doValidate(): ValidationInfo? {
        val typed = field.password
        val empty = typed.all { it.isWhitespace() }
        typed.fill('\u0000')
        return if (empty) ValidationInfo(message("password.empty"), field) else null
    }

    /** The answer after the dialog closed (null when cancelled); the field is cleared either way. */
    fun answer(): VaultPasswordAnswer? {
        val typed = field.password
        field.text = ""
        if (!isOK) {
            typed.fill('\u0000')
            return null
        }
        val remember = when {
            session.isSelected -> RememberChoice.SESSION
            none.isSelected -> RememberChoice.NONE
            else -> RememberChoice.KEYCHAIN
        }
        return VaultPasswordAnswer(typed, remember)
    }
}
