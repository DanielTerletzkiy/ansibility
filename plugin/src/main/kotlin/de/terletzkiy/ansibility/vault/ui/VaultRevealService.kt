package de.terletzkiy.ansibility.vault.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VaultFailure
import de.terletzkiy.ansibility.api.VaultPlaintext
import de.terletzkiy.ansibility.api.VaultStatusListener
import de.terletzkiy.ansibility.api.VaultStatusService
import de.terletzkiy.ansibility.vault.VaultRevealPresenter
import de.terletzkiy.ansibility.vault.actions.VaultValueActions
import de.terletzkiy.ansibility.vault.actions.VaultValueText
import de.terletzkiy.ansibility.vault.envelope.VaultEnvelopes
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The registered [VaultRevealPresenter] (F7.1): hands every revealed value to [VaultRevealService], which shows the
 * timed, masked popup and owns the plaintext from then on.
 */
class VaultPopupRevealPresenter : VaultRevealPresenter {
    override fun present(project: Project, location: SourceLocation, editor: Editor?, identity: String, plaintext: VaultPlaintext) =
        VaultRevealService.getInstance(project).present(location, editor, identity, plaintext)

    /** Reports [failure] next to the caret; a prompt you cancelled yourself needs no report. */
    override fun failed(project: Project, location: SourceLocation, editor: Editor?, failure: VaultFailure) {
        if (failure != VaultFailure.CANCELLED) VaultUiFeedback.failure(project, editor, failure)
    }
}

/** Why a Reveal popup closed. */
enum class RevealCloseReason { TIMEOUT, POPUP_CLOSED, EDITOR_SWITCH, LOCKED, PROJECT_CLOSED, REPLACED, EDITING }

/**
 * The Reveal popup of F7.1 (D24): a small non-modal popup next to the value with a **masked** field, an eye toggle,
 * the line `id default · 1.1 · 32 chars · no trailing newline`, and [Copy] [Edit…].
 *
 * The service owns the revealed [VaultPlaintext] and closes it, zeroing the bytes, when the popup closes: after
 * [VaultUiTimings.REVEAL_MILLIS], on Esc or an outside click, when another editor is selected, when the value's root
 * locks (no id of it unlocked any more), when another value is revealed, and on project close. At most one popup is
 * open per project. The masked field never holds the value: the text exists only while the eye shows it or Copy
 * runs, and the text components are emptied on close. Plain Swing only: no Document, PSI or editor ever sees it.
 */
@Service(Service.Level.PROJECT)
class VaultRevealService(private val project: Project) : Disposable {
    private var current: RevealSession? = null

    /** Shows [plaintext] (decrypted by [identity]) for the value at [location]. Called on the EDT; takes ownership. */
    fun present(location: SourceLocation, editor: Editor?, identity: String, plaintext: VaultPlaintext) {
        try {
            ThreadingAssertions.assertEventDispatchThread()
            current?.close(RevealCloseReason.REPLACED)
            val facts = RevealFacts.of(project, location, identity, plaintext)
            val session = RevealSession(project, location, plaintext, facts, rootOf(location))
            Disposer.register(this, session)
            current = session
            session.open(editor)
        } catch (e: Throwable) {
            plaintext.close()
            throw e
        }
    }

    /** The open popup's session, or null. */
    @get:TestOnly
    internal val session: RevealSession? get() = current?.takeUnless { it.isClosed }

    /** Project close: the open popup closes and its plaintext is zeroed. */
    override fun dispose() {
        current?.close(RevealCloseReason.PROJECT_CLOSED)
        current = null
    }

    private fun rootOf(location: SourceLocation): AnsibleRoot? = AnsibleWorkspace.getInstance(project).rootFor(location.file)

    companion object {
        fun getInstance(project: Project): VaultRevealService = project.service()
    }
}

/** What the Reveal popup says about a value without showing it: `id default · 1.1 · 32 chars · no trailing newline`. */
internal class RevealFacts(
    val keyName: String?,
    val identity: String,
    /** The envelope's header version as written (`1.1`, `1.2`), or null when it cannot be read. */
    val version: String?,
    /** True when the plaintext is UTF-8 text; a binary value is never shown or copied as text. */
    val isText: Boolean,
    /** Characters for text, bytes for binary values. */
    val length: Int,
    val endsWithNewline: Boolean,
) {
    /** The info line under the value. */
    val line: String
        get() = listOfNotNull(
            AnsibilityVaultUiBundle.message("card.identity", identity),
            version,
            if (isText) AnsibilityVaultUiBundle.message("reveal.facts.chars", length) else AnsibilityVaultUiBundle.message("reveal.facts.bytes", length),
            if (!isText) null else if (endsWithNewline) AnsibilityVaultUiBundle.message("reveal.facts.newline") else AnsibilityVaultUiBundle.message("reveal.facts.no.newline"),
        ).joinToString(" · ")

    override fun toString(): String = "RevealFacts($line)"

    companion object {
        fun of(project: Project, location: SourceLocation, identity: String, plaintext: VaultPlaintext): RevealFacts {
            val located = readAction { VaultEnvelopes.at(project, location) }
            return plaintext.read { bytes ->
                val text = VaultValueText.decode(bytes)
                RevealFacts(
                    located?.keyName, identity, located?.header?.version, text != null,
                    text?.codePointCount(0, text.length) ?: bytes.size, bytes.lastOrNull() == '\n'.code.toByte(),
                )
            }
        }

        private fun <T> readAction(action: () -> T): T =
            if (ApplicationManager.getApplication().isReadAccessAllowed) action() else runReadActionBlocking(action)
    }
}

/**
 * One open Reveal popup and the plaintext it owns. Every way of closing ends in [close], which empties the text
 * components, zeroes the plaintext and cancels the popup; it is idempotent.
 */
internal class RevealSession(
    private val project: Project,
    val location: SourceLocation,
    private val plaintext: VaultPlaintext,
    val facts: RevealFacts,
    private val root: AnsibleRoot?,
) : Disposable {
    /** Why the session closed, or null while it is open. */
    var closeReason: RevealCloseReason? = null
        private set

    val isClosed: Boolean get() = closeReason != null

    /** True while the eye shows the value. */
    var isRevealed: Boolean = false
        private set

    /** The popup, once shown. */
    internal var popup: JBPopup? = null
        private set

    private val masked = JBPasswordField().apply {
        isEditable = false
        // A stand-in of the value's length: the field never holds the value itself.
        text = "\u2022".repeat(facts.length.coerceIn(MIN_MASK, MAX_MASK))
        columns = COLUMNS
    }
    private val revealed = JBTextArea().apply {
        isEditable = false
        columns = COLUMNS
    }
    private val valueCards = JPanel(CardLayout()).apply {
        add(masked, MASKED)
        add(JBScrollPane(revealed), REVEALED)
    }
    private val eye = JButton(AllIcons.Actions.Show).apply {
        toolTipText = message("reveal.show")
        isEnabled = facts.isText
        addActionListener { toggle() }
    }
    private val status = JBLabel(
        if (facts.isText) message("reveal.closes", VaultUiTimings.seconds(VaultUiTimings.REVEAL_MILLIS)) else message("reveal.binary"),
    ).apply { foreground = UIUtil.getContextHelpForeground() }
    private var editor: Editor? = null

    /** Shows the popup and starts the close triggers. */
    fun open(editor: Editor?) {
        this.editor = editor
        val connection = project.messageBus.connect(this)
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = close(RevealCloseReason.EDITOR_SWITCH)
        })
        connection.subscribe(VaultStatusListener.TOPIC, VaultStatusListener {
            ApplicationManager.getApplication().invokeLater({ if (!isClosed && !rootUnlocked()) close(RevealCloseReason.LOCKED) }, ModalityState.any())
        })
        VaultUiClock.getInstance().scheduler.schedule(VaultUiTimings.REVEAL_MILLIS, this) { close(RevealCloseReason.TIMEOUT) }
        val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel(), eye)
            .setTitle(facts.keyName?.let { message("reveal.title", it) } ?: message("reveal.title.unnamed"))
            .setRequestFocus(true)
            .setFocusable(true)
            .setMovable(true)
            .setResizable(true)
            .setCancelKeyEnabled(true)
            .setCancelOnClickOutside(true)
            .setCancelOnWindowDeactivation(false)
            .addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) = close(RevealCloseReason.POPUP_CLOSED)
            })
            .createPopup()
        this.popup = popup
        if (!isClosed) VaultPopups.show(project, popup, editor, location)
    }

    /** The eye: shows or hides the value. */
    fun toggle() {
        if (isClosed || !facts.isText) return
        isRevealed = !isRevealed
        if (isRevealed) {
            val text = text() ?: return
            revealed.text = text
            revealed.rows = text.lines().size.coerceIn(1, MAX_ROWS)
            (valueCards.layout as CardLayout).show(valueCards, REVEALED)
            eye.icon = AllIcons.Actions.ToggleVisibility
            eye.toolTipText = message("reveal.hide")
        } else {
            revealed.text = ""
            (valueCards.layout as CardLayout).show(valueCards, MASKED)
            eye.icon = AllIcons.Actions.Show
            eye.toolTipText = message("reveal.show")
        }
        popup?.takeUnless { it.isDisposed }?.pack(true, true)
    }

    /** True when the eye currently shows exactly [candidate] (for tests: the value is visible only on request). */
    @TestOnly
    internal fun shows(candidate: String): Boolean = isRevealed && revealed.text == candidate

    /** The size of the owned plaintext: 0 once it is zeroed. */
    @get:TestOnly
    internal val plaintextSize: Int get() = plaintext.size

    /** The masked field's content, for tests: never the value. */
    @get:TestOnly
    internal val maskedText: String get() = String(masked.password)

    /** Copy (F7.1): the value goes to the clipboard, which is cleared after 30 s. */
    fun copy() {
        val text = text() ?: return
        VaultClipboard.getInstance().copy(text)
        status.text = message("reveal.copied", VaultUiTimings.seconds(VaultUiTimings.CLIPBOARD_MILLIS))
    }

    /** Edit… (F7.2): closes this popup and opens the edit popup for the same value. */
    fun edit() {
        if (isClosed) return
        val editor = editor
        close(RevealCloseReason.EDITING)
        VaultValueActions.getInstance(project).editAt(location, editor)
    }

    /** Closes the popup and zeroes the plaintext; idempotent. */
    fun close(reason: RevealCloseReason) {
        if (isClosed) return
        closeReason = reason
        revealed.text = ""
        isRevealed = false
        plaintext.close()
        popup?.takeUnless { it.isDisposed }?.cancel()
        Disposer.dispose(this)
    }

    override fun dispose() = close(RevealCloseReason.PROJECT_CLOSED)

    override fun toString(): String = "RevealSession(${location.file.name}, $closeReason)"

    private fun text(): String? = if (isClosed || !facts.isText) null else plaintext.read { VaultValueText.decode(it) }

    private fun rootUnlocked(): Boolean {
        val root = root ?: return false
        return runReadActionBlocking { VaultStatusService.getInstance(project).config(root).anyUnlocked }
    }

    private fun panel(): JComponent {
        val value = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            add(valueCards, BorderLayout.CENTER)
            add(eye, BorderLayout.EAST)
        }
        val facts = JBLabel(facts.line).apply { foreground = UIUtil.getContextHelpForeground() }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            add(JButton(message("reveal.copy")).apply {
                isEnabled = this@RevealSession.facts.isText
                addActionListener { copy() }
            })
            add(JButton(message("reveal.edit")).apply {
                isEnabled = this@RevealSession.facts.isText
                addActionListener { edit() }
            })
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
            for (component in listOf(value, facts, status, buttons)) {
                (component as JComponent).alignmentX = JComponent.LEFT_ALIGNMENT
                add(component)
            }
        }
    }

    private companion object {
        const val MASKED = "masked"
        const val REVEALED = "revealed"
        const val COLUMNS = 32
        const val MAX_ROWS = 10
        const val MIN_MASK = 8
        const val MAX_MASK = 32

        fun message(key: String, vararg params: Any): String = AnsibilityVaultUiBundle.message(key, *params)
    }
}
