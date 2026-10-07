package de.terletzkiy.ansibility.run.become

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.ui.NamedColorUtil
import de.terletzkiy.ansibility.run.AnsibilityRunBundle.message
import de.terletzkiy.ansibility.vault.secrets.RememberChoice
import javax.swing.JComponent

/** A become password prompt for one run of a root, for [environment] when the root has environments. */
class BecomePasswordRequest(
    val rootDisplayName: String,
    val environment: String?,
    /** Why the configured source gave nothing, or null. */
    val error: String?,
    val passwordSafeMemoryOnly: Boolean,
) {
    override fun toString(): String = "BecomePasswordRequest($rootDisplayName, $environment)"
}

/**
 * A typed become password and how to remember it: [RememberChoice] like a vault password, for [onlyEnvironment] or
 * every environment of the root. [take] hands the characters over once; [close] zeroes them if nobody took them.
 */
class BecomePasswordAnswer(password: CharArray, val remember: RememberChoice, val onlyEnvironment: Boolean) : AutoCloseable {
    private var password: CharArray? = password

    fun take(): CharArray = checkNotNull(password) { "the password was taken" }.also { password = null }

    override fun close() {
        password?.fill('\u0000')
        password = null
    }

    override fun toString(): String = "BecomePasswordAnswer(***, $remember, onlyEnvironment=$onlyEnvironment)"
}

/** Asks for become passwords (application service, replaced in tests). EDT only. */
interface BecomePrompter {
    fun ask(project: Project, request: BecomePasswordRequest): BecomePasswordAnswer?

    companion object {
        fun getInstance(): BecomePrompter = service()
    }
}

class BecomeDialogPrompter : BecomePrompter {
    override fun ask(project: Project, request: BecomePasswordRequest): BecomePasswordAnswer? {
        ThreadingAssertions.assertEventDispatchThread()
        val dialog = BecomePasswordDialog(project, request)
        dialog.show()
        return dialog.answer()
    }
}

/** The become (sudo) password of one run, remembered as chosen. */
internal class BecomePasswordDialog(project: Project, private val request: BecomePasswordRequest) : DialogWrapper(project, true) {
    private val field = JBPasswordField()
    private val keychain = JBRadioButton(message(if (request.passwordSafeMemoryOnly) "become.remember.keychain.memory" else "become.remember.keychain"))
    private val session = JBRadioButton(message("become.remember.session"), true)
    private val none = JBRadioButton(message("become.remember.none"))
    private val onlyEnvironment = JBCheckBox(message("become.remember.environment", request.environment.orEmpty()))

    init {
        title = message("become.title")
        none.addChangeListener { onlyEnvironment.isEnabled = !none.isSelected }
        init()
    }

    override fun createCenterPanel(): JComponent = centerPanel()

    /** The dialog's content (tests build it: a headless dialog shows none). */
    internal fun centerPanel(): DialogPanel = panel {
        row {
            label(
                if (request.environment == null) message("become.prompt", request.rootDisplayName)
                else message("become.prompt.environment", request.rootDisplayName, request.environment),
            )
        }
        row { cell(field).align(AlignX.FILL) }
        request.error?.let { error -> row { label(error).applyToComponent { foreground = NamedColorUtil.getErrorForeground() } } }
        // The UI DSL groups radio buttons itself (262 refuses one outside a buttons group).
        buttonsGroup {
            row { cell(keychain) }
            row { cell(session) }
            row { cell(none) }
        }
        if (request.environment != null) row { cell(onlyEnvironment) }
        row { comment(message("become.comment")) }
    }

    override fun getPreferredFocusedComponent(): JComponent = field

    override fun doValidate(): ValidationInfo? {
        val typed = field.password
        val empty = typed.isEmpty()
        typed.fill('\u0000')
        return if (empty) ValidationInfo(message("become.empty"), field) else null
    }

    /** The answer after the dialog closed (null when cancelled); the field is cleared either way. */
    fun answer(): BecomePasswordAnswer? {
        val typed = field.password
        field.text = ""
        if (!isOK) {
            typed.fill('\u0000')
            return null
        }
        val remember = when {
            keychain.isSelected -> RememberChoice.KEYCHAIN
            none.isSelected -> RememberChoice.NONE
            else -> RememberChoice.SESSION
        }
        return BecomePasswordAnswer(typed, remember, onlyEnvironment.isSelected && request.environment != null)
    }
}
