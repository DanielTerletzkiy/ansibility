package de.terletzkiy.ansibility.vault.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.terletzkiy.ansibility.api.VaultPlaintext
import de.terletzkiy.ansibility.api.VaultStatusListener
import de.terletzkiy.ansibility.api.VaultStatusService
import de.terletzkiy.ansibility.vault.AnsibilityVaultBundle
import de.terletzkiy.ansibility.vault.actions.VaultActionPrompts
import de.terletzkiy.ansibility.vault.actions.VaultValueActions
import de.terletzkiy.ansibility.vault.actions.VaultValueRef
import de.terletzkiy.ansibility.vault.actions.VaultValueText
import de.terletzkiy.ansibility.vault.actions.VaultWriteOutcome
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/** Why an edit popup closed. */
enum class EditCloseReason { WRITTEN, UNCHANGED, CANCELLED, LOCKED, PROJECT_CLOSED, REPLACED }

/**
 * The F7.2 edit popup: the decrypted value in a plain Swing [JBTextArea], a "⏎ Ends with newline" toggle and
 * [Encrypt and Replace]. The text area is not an IDE editor: no Document, PSI, undo history, Local History, inline
 * completion or AI context ever sees the plaintext, and nothing is saved anywhere but the re-encrypted envelope.
 *
 * Encrypt and Replace re-encrypts with the id that decrypted the value and keeps the header label, the block
 * indentation and the `!vault |` line ([VaultValueActions.reencrypt]); an unchanged value writes nothing and an empty
 * one is refused, as `ansible-vault` does. Esc or an outside click with unsaved changes asks before discarding; the
 * popup closes without asking when the value's root locks and on project close. At most one edit popup per project.
 */
@Service(Service.Level.PROJECT)
class VaultEditService(private val project: Project) : Disposable {
    private var current: VaultEditSession? = null

    /**
     * Opens the popup for [ref] with [plaintext] (decrypted by [identity]), next to [editor]. Called on the EDT; takes
     * ownership of [plaintext]. A binary value cannot be edited as text: it is closed at once and reported.
     */
    internal fun open(ref: VaultValueRef, editor: Editor?, identity: String, plaintext: VaultPlaintext, headerVersion: String) {
        try {
            ThreadingAssertions.assertEventDispatchThread()
            val text = plaintext.read { VaultValueText.decode(it) }
            if (text == null) {
                plaintext.close()
                VaultUiFeedback.error(project, editor, AnsibilityVaultUiBundle.message("edit.binary"))
                return
            }
            current?.close(EditCloseReason.REPLACED)
            val session = VaultEditSession(project, ref, identity, plaintext, text, headerVersion)
            Disposer.register(this, session)
            current = session
            session.open(editor)
        } catch (e: Throwable) {
            plaintext.close()
            throw e
        }
    }

    /** The open edit popup's session, or null. */
    @get:TestOnly
    internal val session: VaultEditSession? get() = current?.takeUnless { it.isClosed }

    override fun dispose() {
        current?.close(EditCloseReason.PROJECT_CLOSED)
        current = null
    }

    companion object {
        fun getInstance(project: Project): VaultEditService = project.service()
    }
}

/** One open edit popup and the original plaintext it owns (zeroed on close). */
internal class VaultEditSession(
    private val project: Project,
    val ref: VaultValueRef,
    /** The id whose secret decrypted the value: the one that re-encrypts it. */
    val identity: String,
    private val original: VaultPlaintext,
    initialText: String,
    headerVersion: String,
) : Disposable {
    var closeReason: EditCloseReason? = null
        private set

    val isClosed: Boolean get() = closeReason != null

    /** True while Encrypt and Replace runs. */
    var isBusy: Boolean = false
        private set

    internal val textArea = JBTextArea(initialText.removeSuffix("\n")).apply {
        rows = initialText.lines().size.coerceIn(MIN_ROWS, MAX_ROWS)
        columns = COLUMNS
    }
    internal val endsWithNewline = JBCheckBox(message("edit.newline"), initialText.endsWith("\n"))
    private val error = JBLabel().apply { foreground = JBColor.RED }
    private val submit = JButton(message("edit.submit")).apply { addActionListener { submit() } }
    private val facts = JBLabel(message("edit.facts", identity, headerVersion)).apply { foreground = UIUtil.getContextHelpForeground() }
    private var popup: JBPopup? = null
    private var editor: Editor? = null

    /** The size of the owned original plaintext: 0 once it is zeroed. */
    @get:TestOnly
    internal val originalSize: Int get() = original.size

    /** The error line, for tests. */
    @get:TestOnly
    internal val errorText: String get() = error.text.orEmpty()

    fun open(editor: Editor?) {
        this.editor = editor
        project.messageBus.connect(this).subscribe(VaultStatusListener.TOPIC, VaultStatusListener {
            ApplicationManager.getApplication().invokeLater({ if (!isClosed && !rootUnlocked()) close(EditCloseReason.LOCKED) }, ModalityState.any())
        })
        val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel(), textArea)
            .setTitle(ref.keyName?.let { message("edit.title", it) } ?: message("edit.title.unnamed"))
            .setRequestFocus(true)
            .setFocusable(true)
            .setMovable(true)
            .setResizable(true)
            .setCancelKeyEnabled(true)
            .setCancelOnClickOutside(true)
            .setCancelOnWindowDeactivation(false)
            .setCancelOnOtherWindowOpen(false)
            .setCancelCallback { requestCancel() }
            .addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) = close(EditCloseReason.CANCELLED)
            })
            .createPopup()
        this.popup = popup
        VaultPopups.show(project, popup, editor, ref.location)
    }

    /** The bytes the edit would encrypt: the text, plus a line feed when the toggle is on. */
    private fun currentBytes(): ByteArray = (textArea.text + if (endsWithNewline.isSelected) "\n" else "").toByteArray(Charsets.UTF_8)

    /** True when the text differs from the decrypted value. */
    val isDirty: Boolean
        get() {
            if (isClosed) return false
            val bytes = currentBytes()
            return try {
                original.read { !it.contentEquals(bytes) }
            } finally {
                bytes.fill(0)
            }
        }

    /** Esc or an outside click: closing is fine unless there are unsaved changes and you keep editing. */
    fun requestCancel(): Boolean = isClosed || !isDirty || VaultActionPrompts.getInstance().confirmDiscard(project)

    /** Encrypt and Replace. */
    fun submit() {
        if (isClosed || isBusy) return
        val bytes = currentBytes()
        if (original.read { it.contentEquals(bytes) }) {
            bytes.fill(0)
            close(EditCloseReason.UNCHANGED)
            VaultUiFeedback.info(project, editor, message("action.unchanged"))
            return
        }
        if (bytes.isEmpty()) {
            error.text = message("edit.empty")
            return
        }
        isBusy = true
        submit.isEnabled = false
        error.text = ""
        VaultValueActions.getInstance(project).reencrypt(ref, identity, bytes) { outcome ->
            isBusy = false
            submit.isEnabled = true
            when (outcome) {
                is VaultWriteOutcome.Written -> {
                    close(EditCloseReason.WRITTEN)
                    VaultUiFeedback.info(project, editor, message("action.written.edit", outcome.identity))
                }
                is VaultWriteOutcome.Failed -> error.text = AnsibilityVaultBundle.failure(outcome.failure)
                VaultWriteOutcome.Changed -> error.text = message("action.changed")
                VaultWriteOutcome.Gone -> error.text = message("action.not.found")
            }
        }
    }

    /** Closes the popup, empties the text area and zeroes the original plaintext; idempotent. */
    fun close(reason: EditCloseReason) {
        if (isClosed) return
        closeReason = reason
        textArea.text = ""
        original.close()
        popup?.takeUnless { it.isDisposed }?.cancel()
        Disposer.dispose(this)
    }

    override fun dispose() = close(EditCloseReason.PROJECT_CLOSED)

    override fun toString(): String = "VaultEditSession(${ref.file.name}, $closeReason)"

    private fun rootUnlocked(): Boolean = runReadActionBlocking { VaultStatusService.getInstance(project).config(ref.root).anyUnlocked }

    private fun panel(): JComponent {
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            add(JButton(message("edit.cancel")).apply { addActionListener { popup?.cancel() } })
            add(submit)
        }
        val bottom = JPanel(BorderLayout()).apply {
            add(endsWithNewline, BorderLayout.WEST)
            add(buttons, BorderLayout.EAST)
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
            for (component in listOf(JBScrollPane(textArea), facts, error, bottom)) {
                component.alignmentX = JComponent.LEFT_ALIGNMENT
                add(component)
            }
        }
    }

    private companion object {
        const val COLUMNS = 48
        const val MIN_ROWS = 2
        const val MAX_ROWS = 12

        fun message(key: String, vararg params: Any): String = AnsibilityVaultUiBundle.message(key, *params)
    }
}
