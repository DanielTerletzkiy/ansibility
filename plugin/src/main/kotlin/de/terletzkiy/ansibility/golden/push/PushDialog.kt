package de.terletzkiy.ansibility.golden.push

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBLoadingPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.terletzkiy.ansibility.golden.AnsibilityGoldenBundle.message
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * The Push dialog (plan amendment R24, D188):
 * ```
 * Push web from golden to:                               [Select differing] [Select none]
 * ☐ falcon   ≈ molecule only   1 changed                         Compare
 * ☐ heron    Δ tasks           3 changed · 2 added · 1 deleted   Compare   ⚠ uncommitted changes in this role
 * Outside scope (2) …   Roots without web (1)   ☐ thrush   creates roles/web, 12 files
 * ☑ Delete files the source does not have   ☐ Include key and vault files
 * Writes files only; nothing is staged, committed or pushed in git.
 * ```
 * Rows load in the background (a loading state until then); nothing is ticked at first; OK reads "Push to N Repos"
 * and is off while nothing is ticked. With key and vault files included, whole-file vaults whose header names another
 * vault id than the source's are listed as a warning (header lines only, never decrypted).
 */
class PushDialog(private val project: Project, private val model: PushModel) : DialogWrapper(project, true) {
    private var rows: List<PushRow> = emptyList()
    private val ticked = LinkedHashSet<PushRow>()

    /** The current options. */
    internal var options: PushOptions = PushOptions()
        private set

    /** The checkbox of each row (tests click them). */
    internal val checkBoxes: MutableMap<PushRow, JBCheckBox> = LinkedHashMap()

    /** The counts label of each row (they follow the options). */
    internal val countLabels: MutableMap<PushRow, JLabel> = LinkedHashMap()

    /** The Compare link of each row (off for a root without the role). */
    internal val compareLinks: MutableMap<PushRow, JComponent> = LinkedHashMap()

    /** The "uncommitted changes in this role" warning of each row that has one. */
    internal val uncommittedLabels: MutableMap<PushRow, JLabel> = LinkedHashMap()

    /** The "linked to …" note of each row that is (or lies below) a symbolic link; such rows cannot be ticked. */
    internal val linkedLabels: MutableMap<PushRow, JLabel> = LinkedHashMap()

    internal lateinit var deleteBox: JBCheckBox
        private set
    internal lateinit var sensitiveBox: JBCheckBox
        private set

    /** The different-vault-id warning (visible with key and vault files included, when a ticked row has one). */
    internal val vaultWarning: JBLabel = JBLabel().apply {
        icon = AllIcons.General.BalloonWarning
        isVisible = false
    }

    private lateinit var selectDifferingButton: JButton
    private lateinit var selectNoneButton: JButton
    private val loading = JBLoadingPanel(BorderLayout(), disposable).apply {
        setLoadingText(message("push.dialog.loading", model.roleName))
    }

    /**
     * The rows (or the loading state): at least [MIN_ROWS_HEIGHT] tall even while they load, as tall as they need up to
     * [MAX_ROWS_HEIGHT], then it scrolls. A JBLoadingPanel reports its content's size, so its own preferred size never
     * counted and the dialog opened at its smallest, hiding the choices (user report 2026-10-09).
     */
    internal val rowsArea: JPanel = object : JPanel(BorderLayout()) {
        override fun getPreferredSize(): Dimension {
            val content = super.getPreferredSize()
            return Dimension(maxOf(content.width, JBUI.scale(PREFERRED_WIDTH)), content.height.coerceIn(JBUI.scale(MIN_ROWS_HEIGHT), JBUI.scale(MAX_ROWS_HEIGHT)))
        }
    }.apply { add(loading, BorderLayout.CENTER) }

    init {
        title = message("push.dialog.title")
        init()
        updateState()
        val ready = model.rowsIfReady()
        if (ready != null || model.rows.isCompleted) {
            fill(ready)
        } else {
            loading.startLoading()
            model.rows.invokeOnCompletion {
                // Swing only; the dialog is modal, so run in any modality and check that it is still open.
                ApplicationManager.getApplication().invokeLater({ if (!isDisposed) fill(model.rowsIfReady()) }, ModalityState.any())
            }
        }
    }

    /** The ticked rows (dialog order) and the options. */
    fun choice(): PushChoice = PushChoice(tickedRows(), options)

    /** The ticked rows, in dialog order (never a linked one). */
    internal fun tickedRows(): List<PushRow> = rows.filter { it in ticked && it.pushable }

    /** "Select differing": exactly the copies a push with the current options would change. */
    internal fun selectDiffering() = setTicked(rows.filter { it.differs(options) })

    /** "Select none". */
    internal fun selectNone() = setTicked(emptyList())

    /** The OK button's text ("Push to 2 Repos"). */
    internal val okText: String get() = okAction.getValue(Action.NAME) as String

    override fun createCenterPanel(): JComponent = panel {
        row {
            label(message("push.dialog.header", model.roleName, model.sourceName)).bold().resizableColumn()
            selectDifferingButton = button(message("push.dialog.selectDiffering")) { selectDiffering() }.align(AlignX.RIGHT).component
            selectNoneButton = button(message("push.dialog.selectNone")) { selectNone() }.align(AlignX.RIGHT).component
        }
        row { cell(rowsArea).align(Align.FILL) }.resizableRow()
        row {
            deleteBox = checkBox(message("push.dialog.option.delete")).applyToComponent { isSelected = options.deleteExtra }
                .onChanged { options = options.copy(deleteExtra = it.isSelected); updateState() }.component
        }
        row {
            sensitiveBox = checkBox(message("push.dialog.option.sensitive")).applyToComponent { isSelected = options.includeSensitive }
                .onChanged { options = options.copy(includeSensitive = it.isSelected); updateState() }.component
        }
        row { cell(vaultWarning) }
        row { comment(message("push.dialog.footer")) }
    }

    /** Builds the rows once they are known ([computed] null: they could not be computed). */
    private fun fill(computed: List<PushRow>?) {
        loading.stopLoading()
        rows = computed.orEmpty()
        val content = panel {
            when {
                computed == null -> row { label(message("push.dialog.failed")).applyToComponent { foreground = JBColor.RED } }
                rows.isEmpty() -> row { label(message("push.dialog.none", model.roleName)) }
            }
            for (section in PushSection.entries) {
                val list = rows.filter { it.section == section }
                if (list.isEmpty()) continue
                val heading = when (section) {
                    PushSection.IN_SCOPE -> null
                    PushSection.OUTSIDE_SCOPE -> message("push.dialog.section.outsideScope", list.size)
                    PushSection.WITHOUT_ROLE -> message("push.dialog.section.withoutRole", model.roleName, list.size)
                }
                if (heading != null) {
                    separator()
                    row { label(heading).applyToComponent { foreground = UIUtil.getContextHelpForeground() } }
                }
                for (row in list) rowOf(row)
            }
        }
        loading.add(JBScrollPane(content).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        loading.revalidate()
        loading.repaint()
        updateState()
        growToFit()
    }

    /** Once the rows are in, makes an open dialog tall enough to show them (up to [MAX_ROWS_HEIGHT]); never shrinks it. */
    private fun growToFit() {
        val window = window ?: return
        if (!window.isDisplayable) return
        val wanted = window.preferredSize
        if (wanted.width <= window.width && wanted.height <= window.height) return
        window.size = Dimension(maxOf(wanted.width, window.width), maxOf(wanted.height, window.height))
        window.validate()
    }

    /** Remembers the size you give the dialog. */
    override fun getDimensionServiceKey(): String = "Ansibility.Golden.PushRoleToRepos"

    private fun Panel.rowOf(pushRow: PushRow) {
        row {
            checkBoxes[pushRow] = checkBox(pushRow.name).applyToComponent {
                isSelected = pushRow in ticked
                // D191: never written through a symbolic link, so a linked copy cannot be ticked.
                isEnabled = pushRow.pushable
            }
                .onChanged { box ->
                    if (box.isSelected) ticked += pushRow else ticked -= pushRow
                    updateState()
                }.component
            label(pushRow.badge.orEmpty()).applyToComponent { foreground = UIUtil.getContextHelpForeground() }
            val linkedTo = pushRow.linkedTo
            if (linkedTo != null) {
                linkedLabels[pushRow] = label(message("push.dialog.linked", linkedTo)).applyToComponent {
                    foreground = UIUtil.getContextHelpForeground()
                    toolTipText = message("push.dialog.linked.tooltip")
                }.component
                return@row
            }
            countLabels[pushRow] = label(pushRow.countsText(options)).component
            compareLinks[pushRow] = link(message("push.dialog.compare")) {
                PushService.getInstance(project).compare(model.source, pushRow, ModalityState.stateForComponent(loading))
            }.enabled(pushRow.copy != null).component
            if (pushRow.uncommitted) {
                uncommittedLabels[pushRow] = label(message("push.dialog.uncommitted")).applyToComponent {
                    icon = AllIcons.General.BalloonWarning
                    toolTipText = message("push.dialog.uncommitted.tooltip")
                }.component
            }
        }
    }

    private fun setTicked(selection: List<PushRow>) {
        ticked.clear()
        ticked += selection.filter { it.pushable }
        for ((row, box) in checkBoxes) box.isSelected = row in ticked
        updateState()
    }

    /** The counts, the OK button, the selection buttons and the vault warning after any change. */
    private fun updateState() {
        for ((row, label) in countLabels) label.text = row.countsText(options)
        val count = ticked.size
        setOKButtonText(message("push.dialog.ok", count))
        isOKActionEnabled = count > 0
        if (::selectDifferingButton.isInitialized) selectDifferingButton.isEnabled = rows.any { it.differs(options) }
        if (::selectNoneButton.isInitialized) selectNoneButton.isEnabled = count > 0
        val mismatches = if (options.includeSensitive) tickedRows().flatMap { row -> row.vaultIdMismatches.map { row to it } } else emptyList()
        vaultWarning.isVisible = mismatches.isNotEmpty()
        vaultWarning.text = if (mismatches.isEmpty()) "" else message(
            "push.dialog.vaultIds",
            mismatches.joinToString(", ") { (row, m) -> message("push.dialog.vaultIds.item", row.name, m.relPath, m.sourceId, m.targetId) },
        )
    }

    private companion object {
        const val PREFERRED_WIDTH = 720
        const val MIN_ROWS_HEIGHT = 200
        const val MAX_ROWS_HEIGHT = 480
    }
}
