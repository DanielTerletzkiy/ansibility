package de.terletzkiy.ansibility.vault.rotate

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.terletzkiy.ansibility.index.vault.VaultCounts
import de.terletzkiy.ansibility.vault.ui.AnsibilityVaultUiBundle.message
import de.terletzkiy.ansibility.vault.ui.VaultClipboard
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.security.SecureRandom
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.UIManager

/**
 * The questions of Rotate Vault Password… (plan amendment R29, D205, D209, D214): the rotation dialog and the review.
 * An application service; tests replace it, like `VaultActionPrompts`. No text shown here contains a password or a
 * value; the new password appears only masked in its own field (Show reveals it there), and Copy goes through
 * [VaultClipboard], which clears it after its timeout.
 */
interface RotationPrompts {
    /**
     * The rotation dialog (D205): edits [request] in place (the id, the checked groups, the password-file writes) and
     * answers with the new password, or null when cancelled. The caller owns and closes the answer. EDT.
     */
    fun chooseRotation(project: Project, request: RotationRequest): NewPassword?

    /** The review (D209): true on Rotate, false on Cancel; sets [RotationReview.alsoRegenerate] (D214). EDT. */
    fun review(project: Project, review: RotationReview): Boolean

    companion object {
        fun getInstance(): RotationPrompts = ApplicationManager.getApplication().service()
    }
}

/** [RotationPrompts] with IDE dialogs. */
class RotationDialogPrompts : RotationPrompts {
    override fun chooseRotation(project: Project, request: RotationRequest): NewPassword? {
        ThreadingAssertions.assertEventDispatchThread()
        val dialog = RotationDialog(project, request, SecureRandom())
        return if (dialog.showAndGet()) dialog.answer() else null.also { dialog.clear() }
    }

    override fun review(project: Project, review: RotationReview): Boolean {
        ThreadingAssertions.assertEventDispatchThread()
        return RotationReviewDialog(project, review).showAndGet()
    }
}

/** The plain texts of the comments of [component] (the UI DSL shows them in editor panes), for tests. */
@TestOnly
internal fun commentsOf(component: JComponent): List<String> =
    UIUtil.findComponentsOfType(component, JEditorPane::class.java).map { it.document.getText(0, it.document.length).trim() }

/** The texts of rotation rows and sources (bundle texts, never a value). */
internal object RotationTexts {
    /** "default: 130 values in 41 files, 4 vault files". */
    @Nls
    fun identity(identity: RotationIdentity): String = message("rotate.dialog.identity", identity.label, counts(identity.counts))

    /** "130 values in 41 files, 4 vault files". */
    @Nls
    fun counts(counts: VaultCounts): String = message("rotate.dialog.counts", counts.inline, counts.inlineFiles, counts.files)

    /** "falcon, heron". */
    @Nls
    fun roots(group: RotationGroup): String = group.roots.joinToString(", ") { it.displayName }

    /** What the source is: "password file ansible/.vault-pass". */
    @Nls
    fun source(source: RotationSource): String = message("rotate.dialog.source", kind(source), source.display)

    @Nls
    fun kind(source: RotationSource): String = message("settings.vault.kind.${source.kind.name.lowercase()}")

    /**
     * A password file as the rotation names it before writing it: its display (with where a link points) and, outside
     * the root's repository, an explicit note (plan amendment R29 review, decision 12).
     */
    @Nls
    fun file(source: RotationSource): String = if (source.outside) message("rotate.source.outside", source.display) else source.display

    /** D211 in words: where the new password goes for [group]. */
    @Nls
    fun destination(group: RotationGroup): String = when (group.source.destination) {
        Destination.PASSWORD_FILE ->
            if (group.writable) message("rotate.dialog.write.file", file(group.source))
            else message("rotate.dialog.write.unresolved", group.source.display, group.unresolved.mapNotNull { it.source.unresolved }.distinct().joinToString(", "))
        Destination.PASSWORD_SAFE -> message("rotate.dialog.write.safe")
        Destination.PROMPT -> message("rotate.dialog.write.prompt")
        Destination.YOURSELF -> message("rotate.dialog.write.yourself", group.source.display)
    }
}

/**
 * Shows the generated new password (plan amendment R29 review, decision 16): painted from its character array with
 * `Graphics.drawChars`, masked unless Show is on, so it never becomes a `String` or the content of a text component
 * (whose buffer clearing does not zero). [set] takes ownership of the array and zeroes the one before.
 */
internal class GeneratedPasswordView : JComponent() {
    private var chars: CharArray = CharArray(0)

    /** Show: the characters themselves instead of bullets. */
    var revealed: Boolean = false
        set(value) {
            field = value
            repaint()
        }

    init {
        border = UIManager.getBorder("TextField.border") ?: JBUI.Borders.empty(3, 5)
        font = UIManager.getFont("PasswordField.font") ?: UIManager.getFont("TextField.font")
        isOpaque = true
    }

    /** Shows [password], which this view owns from now on. */
    fun set(password: CharArray) {
        chars.fill('\u0000')
        chars = password
        revalidate()
        repaint()
    }

    /** A copy of the password shown (the caller zeroes it). */
    fun copy(): CharArray = chars.copyOf()

    fun clear() = set(CharArray(0))

    override fun getPreferredSize(): Dimension {
        val metrics = getFontMetrics(font)
        val insets = insets
        return Dimension(metrics.charWidth('W') * COLUMNS + insets.left + insets.right, metrics.height + insets.top + insets.bottom)
    }

    override fun getMinimumSize(): Dimension = Dimension(JBUI.scale(MIN_WIDTH), preferredSize.height)

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.color = if (isEnabled) UIUtil.getTextFieldBackground() else UIUtil.getPanelBackground()
            g2.fillRect(0, 0, width, height)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.font = font
            g2.color = if (isEnabled) UIUtil.getTextFieldForeground() else UIUtil.getLabelDisabledForeground()
            val metrics = g2.fontMetrics
            val insets = insets
            val y = insets.top + (height - insets.top - insets.bottom - metrics.height) / 2 + metrics.ascent
            if (revealed) {
                g2.drawChars(chars, 0, chars.size, insets.left, y)
            } else {
                val masked = CharArray(chars.size) { BULLET }
                g2.drawChars(masked, 0, masked.size, insets.left, y)
            }
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        const val COLUMNS = 40
        const val MIN_WIDTH = 120
        const val BULLET = '•'
    }
}

/**
 * The rotation dialog (D205): the vault id with its counts; the roots that hold vaults of it, one row per password
 * source (roots sharing a source are checked together, D206) with where the new password goes (D211); and the new
 * password: generated (64 alphanumeric, `SecureRandom`) or typed twice, masked, with Show and Copy. The password lives in
 * character arrays only (the generated one in a [GeneratedPasswordView], never a text component) and is cleared when
 * the dialog closes.
 */
internal class RotationDialog(project: Project, private val request: RotationRequest, private val random: SecureRandom) : DialogWrapper(project) {
    private val identities = ComboBox(request.identities.toTypedArray()).apply {
        renderer = textListCellRenderer { it?.let(RotationTexts::identity) }
        selectedItem = request.identity
    }

    /** Holds the rows of the chosen id's groups; swapped when the id changes (no experimental UI DSL placeholder). */
    private val rootsHolder = JPanel(BorderLayout()).apply { isOpaque = false }
    private val generateButton = JBRadioButton(message("rotate.dialog.generate"), true)
    private val typeButton = JBRadioButton(message("rotate.dialog.type"))
    private val generated = GeneratedPasswordView()
    private val typed = JBPasswordField().apply { columns = 40 }
    private val repeated = JBPasswordField().apply { columns = 40 }
    private val show = JBCheckBox(message("rotate.dialog.show"))
    private val copy = JButton(message("rotate.dialog.copy"))
    private val regenerate = JButton(message("rotate.dialog.regenerate"))
    private val echo = typed.echoChar
    private var center: JComponent? = null

    init {
        title = message("rotate.dialog.title")
        setOKButtonText(message("rotate.dialog.ok"))
        fill(NewPassword.generate(random))
        identities.addActionListener {
            (identities.selectedItem as? RotationIdentity)?.let { request.identity = it }
            showRoots()
            initValidation()
        }
        generateButton.addActionListener { modeChanged() }
        typeButton.addActionListener { modeChanged() }
        show.addActionListener { showChanged() }
        copy.addActionListener { copyPassword() }
        regenerate.addActionListener { fill(NewPassword.generate(random)) }
        init()
        modeChanged()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(message("rotate.dialog.identity.label")) { cell(identities).align(AlignX.FILL) }
        group(message("rotate.dialog.roots")) {
            row { cell(rootsHolder).align(AlignX.FILL) }
            row { comment(message("rotate.dialog.roots.comment")) }
        }
        group(message("rotate.dialog.password")) {
            buttonsGroup {
                row { cell(generateButton) }
                indent {
                    row {
                        cell(generated).align(AlignX.FILL)
                        cell(regenerate)
                    }
                }
                row { cell(typeButton) }
                indent {
                    row(message("rotate.dialog.password.first")) { cell(typed).align(AlignX.FILL) }
                    row(message("rotate.dialog.password.second")) { cell(repeated).align(AlignX.FILL) }
                }
            }
            row {
                cell(show)
                cell(copy)
            }
            row { comment(message("rotate.dialog.password.comment")) }
        }
    }.also { showRoots() }.apply { preferredSize = JBUI.size(640, 480) }.also { center = it }

    override fun getDimensionServiceKey(): String = "Ansibility.Vault.RotatePassword"

    override fun getPreferredFocusedComponent(): JComponent = identities

    /** Puts the rows of the chosen id into [rootsHolder]. */
    private fun showRoots() {
        rootsHolder.removeAll()
        rootsHolder.add(rootsPanel(), BorderLayout.CENTER)
        rootsHolder.revalidate()
        rootsHolder.repaint()
    }

    /** The groups of the chosen id: a checkbox per source (its roots), what the source is, and where the new password goes. */
    private fun rootsPanel(): JComponent = panel {
        for (group in request.identity.groups) groupRows(group)
        if (request.identity.groups.isEmpty()) row { label(message("rotate.dialog.roots.none")) }
    }

    private fun Panel.groupRows(group: RotationGroup) {
        row {
            checkBox(RotationTexts.roots(group)).applyToComponent {
                isSelected = group.checked
                addActionListener {
                    group.checked = isSelected
                    initValidation()
                }
            }
        }
        indent {
            row { comment(RotationTexts.source(group.source)) }
            row {
                if (group.writable) {
                    checkBox(RotationTexts.destination(group)).applyToComponent {
                        isSelected = group.write
                        addActionListener { group.write = isSelected }
                    }
                } else {
                    label(RotationTexts.destination(group))
                }
            }
        }
    }

    /** Shows [password] (generated) in [generated]; its characters never become a `String` (review, decision 16). */
    private fun fill(password: NewPassword) {
        password.use { generated.set(it.chars()) }
    }

    private fun modeChanged() {
        val generating = generateButton.isSelected
        generated.isEnabled = generating
        regenerate.isEnabled = generating
        typed.isEnabled = !generating
        repeated.isEnabled = !generating
        initValidation()
    }

    private fun showChanged() {
        val char = if (show.isSelected) 0.toChar() else echo
        for (field in listOf(typed, repeated)) field.echoChar = char
        generated.revealed = show.isSelected
    }

    /** Copy (D205): the password in the active fields through [VaultClipboard]. */
    private fun copyPassword() {
        val chars = current()
        try {
            if (chars.isNotEmpty()) VaultClipboard.getInstance().copy(String(chars))
        } finally {
            chars.fill('\u0000')
        }
    }

    /** The password the active fields hold (the caller zeroes it). */
    private fun current(): CharArray = if (generateButton.isSelected) generated.copy() else typed.password

    override fun doValidate(): ValidationInfo? {
        if (request.chosen.isEmpty()) return ValidationInfo(message("rotate.dialog.roots.empty"), identities)
        if (generateButton.isSelected) return null
        val first = typed.password
        val second = repeated.password
        try {
            NewPassword.problem(first, second)?.let { return ValidationInfo(message(it), if (it == "rotate.dialog.password.mismatch") repeated else typed) }
            if (first.size < NewPassword.SHORT) return ValidationInfo(message("rotate.dialog.password.short", NewPassword.SHORT), typed).asWarning().withOKEnabled()
            return null
        } finally {
            first.fill('\u0000')
            second.fill('\u0000')
        }
    }

    /** The answer after OK: the new password (generated or typed); the fields are cleared. */
    fun answer(): NewPassword = NewPassword(current(), generated = generateButton.isSelected).also { clear() }

    /** Clears every password field (after the dialog closed either way). */
    fun clear() {
        for (field in listOf(typed, repeated)) field.text = ""
        generated.clear()
    }

    override fun dispose() {
        clear()
        super.dispose()
    }

    /** For tests: the texts the dialog shows (labels, comments, checkboxes, the id combo), never a field's content. */
    @TestOnly
    fun texts(): List<String> {
        val component = center ?: return emptyList()
        val texts = ArrayList<String>()
        texts += request.identities.map(RotationTexts::identity)
        for (child in UIUtil.findComponentsOfType(component, JLabel::class.java)) child.text?.let(texts::add)
        for (child in UIUtil.findComponentsOfType(component, JBCheckBox::class.java)) child.text?.let(texts::add)
        texts += commentsOf(component)
        return texts
    }

    /** For tests: what every text component of the dialog holds (decision 16: the generated password is in none). */
    @TestOnly
    fun textComponentContents(): List<String> =
        center?.let { UIUtil.findComponentsOfType(it, javax.swing.text.JTextComponent::class.java).map { field -> field.text } }.orEmpty()

    /** For tests: types [first] and [second] into the two fields of "type it twice". */
    @TestOnly
    fun type(first: String, second: String) {
        typeButton.isSelected = true
        modeChanged()
        typed.text = first
        repeated.text = second
    }

    /** For tests: what the dialog's validation says now. */
    @TestOnly
    fun validation(): ValidationInfo? = doValidate()

    /** For tests: the components a test clicks. */
    @TestOnly
    fun component(name: String): Component? = when (name) {
        "copy" -> copy
        "show" -> show
        "generate" -> generateButton
        else -> null
    }
}

/**
 * The review after the preflight (D209): per root what will be rewritten (counts and files), what is left alone and
 * why, ids of other roots with the same password, where the new password goes; "Also regenerate secrets…" (D214,
 * unchecked). [Rotate] / [Cancel]; Cancel changes nothing and stores nothing.
 */
internal class RotationReviewDialog(project: Project, private val review: RotationReview) : DialogWrapper(project) {
    private val regenerate = JBCheckBox(message("rotate.review.regenerate"), review.alsoRegenerate)
    private var center: JComponent? = null

    init {
        title = message("rotate.review.title", review.identity)
        setOKButtonText(message("rotate.dialog.ok"))
        regenerate.isEnabled = review.canRegenerate
        regenerate.addActionListener { review.alsoRegenerate = regenerate.isSelected }
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { label(message("rotate.review.intro", review.identity)) }
        for (root in review.roots) {
            row { label(message("rotate.review.root", root.name, root.values, root.vaultFiles, root.files.size)) }
            if (root.files.isNotEmpty()) indent { row { comment(root.files.take(MAX_FILES).joinToString("<br>") + more(root.files.size)) } }
        }
        if (review.leftAlone.isNotEmpty()) {
            group(message("rotate.review.left.alone")) {
                for (line in review.leftAlone.take(MAX_FILES)) row { label(line) }
                if (review.leftAlone.size > MAX_FILES) row { comment(message("rotate.review.more", review.leftAlone.size - MAX_FILES)) }
            }
        }
        if (review.equalPasswords.isNotEmpty()) {
            group(message("rotate.review.equal")) { for (line in review.equalPasswords) row { label(line) } }
        }
        group(message("rotate.review.destinations")) { for (line in review.destinations) row { label(line) } }
        row { cell(regenerate) }
        row { comment(message("rotate.review.regenerate.comment")) }
        row { comment(message("rotate.review.comment")) }
    }.also { center = it }

    private fun more(size: Int): String = if (size > MAX_FILES) "<br>" + message("rotate.review.more", size - MAX_FILES) else ""

    override fun getDimensionServiceKey(): String = "Ansibility.Vault.RotatePassword.Review"

    /** For tests: the texts the review shows. */
    @TestOnly
    fun texts(): List<String> {
        val component = center ?: return emptyList()
        val texts = ArrayList<String>()
        for (child in UIUtil.findComponentsOfType(component, JLabel::class.java)) child.text?.let(texts::add)
        for (child in UIUtil.findComponentsOfType(component, JBCheckBox::class.java)) child.text?.let(texts::add)
        texts += commentsOf(component)
        return texts
    }

    companion object {
        private const val MAX_FILES = 20
    }
}
