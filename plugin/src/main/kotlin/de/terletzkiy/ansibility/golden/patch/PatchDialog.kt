package de.terletzkiy.ansibility.golden.patch

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import de.terletzkiy.ansibility.golden.AnsibilityGoldenBundle.message
import org.jetbrains.annotations.Nls
import java.awt.Dimension
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JList

/**
 * The dialog of "Copy as Patch for Golden…" (plan amendment R25, X126):
 * ```
 * Patch for golden: web from falcon
 *   tasks/main.yml            changed
 *   templates/extra.j2        new file
 *   handlers/old.yml          deleted
 *   files/logo.png            binary: noted, not in the patch
 *   files/ssl/web.key         key or vault file: left out
 * ☐ Include key and vault files (1)   as they are, never decrypted
 * 3 files in the patch · 1 binary file noted · 1 key or vault file left out
 * Paths are relative to golden (…/golden): git apply works there. Nothing is written; git is not run.
 *                                        [Cancel] [Save as .patch…] [Copy to Clipboard]
 * ```
 * Ticking "Include key and vault files" is the explicit confirmation (as in Push). Both outputs are off while the
 * patch would hold no file.
 */
class PatchDialog(project: Project, private val model: PatchModel) : DialogWrapper(project, true) {
    /** Whether key and vault files go in ("Include key and vault files"). */
    internal var includeSensitive: Boolean = false
        private set

    private var output: PatchOutput? = null

    internal val copyAction: Action = object : DialogWrapperAction(message("patch.dialog.copy")) {
        override fun doAction(e: ActionEvent?) = finish(PatchOutput.CLIPBOARD)
    }.apply { putValue(DEFAULT_ACTION, true) }

    internal val saveAction: Action = object : DialogWrapperAction(message("patch.dialog.save")) {
        override fun doAction(e: ActionEvent?) = finish(PatchOutput.FILE)
    }

    /** The files with how each changes golden. */
    internal val fileList: JBList<PatchRow> = JBList(model.rows).apply {
        cellRenderer = object : ColoredListCellRenderer<PatchRow>() {
            override fun customizeCellRenderer(list: JList<out PatchRow>, value: PatchRow?, index: Int, selected: Boolean, hasFocus: Boolean) {
                if (value == null) return
                append(value.relPath, if (value.inPatch(includeSensitive)) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append("  ")
                append(noteOf(value), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        visibleRowCount = minOf(this@PatchDialog.model.rows.size, MAX_VISIBLE_ROWS).coerceAtLeast(1)
    }

    /** "Include key and vault files (n)", only when such files differ. */
    internal var sensitiveBox: JBCheckBox? = null
        private set

    /** What goes in and what does not, after the option. */
    internal val summary: JBLabel = JBLabel()

    init {
        title = message("patch.dialog.title")
        init()
        updateState()
    }

    /** The choice once a button was pressed, else null. */
    fun choice(): PatchChoice? = output?.let { PatchChoice(it, includeSensitive) }

    /** Sets "Include key and vault files" (the checkbox's action; tests call it). */
    internal fun setIncludeSensitive(include: Boolean) {
        includeSensitive = include
        sensitiveBox?.let { if (it.isSelected != include) it.isSelected = include }
        updateState()
    }

    override fun createActions(): Array<Action> = arrayOf(cancelAction, saveAction, copyAction)

    override fun createCenterPanel(): JComponent = panel {
        row { label(message("patch.dialog.header", model.goldenName, model.roleName, model.copyName)).bold() }
        row {
            cell(JBScrollPane(fileList).apply { preferredSize = Dimension(JBUI.scale(PREFERRED_WIDTH), preferredSize.height) }).align(Align.FILL)
        }.resizableRow()
        if (model.sensitiveCount > 0) {
            row {
                sensitiveBox = checkBox(message("patch.dialog.sensitive", model.sensitiveCount))
                    .comment(message("patch.dialog.sensitive.comment"))
                    .onChanged { setIncludeSensitive(it.isSelected) }
                    .component
            }
        }
        row { cell(summary) }
        row { comment(message("patch.dialog.footer", model.base.location)) }
    }

    override fun getPreferredFocusedComponent(): JComponent = fileList

    /** Remembers the size you give the dialog. */
    override fun getDimensionServiceKey(): String = "Ansibility.Golden.CopyAsPatch"

    private fun finish(output: PatchOutput) {
        if (model.diffCount(includeSensitive) == 0) return
        this.output = output
        close(OK_EXIT_CODE)
    }

    /** The summary, the list's notes and the two outputs after the option changed. */
    private fun updateState() {
        val files = model.diffCount(includeSensitive)
        val binaries = model.rows.count { it.binary && (!it.sensitive || includeSensitive) }
        val leftOut = if (includeSensitive) 0 else model.sensitiveCount
        summary.text = listOfNotNull(
            message("patch.dialog.summary.files", files),
            binaries.takeIf { it > 0 }?.let { message("patch.dialog.summary.binary", it) },
            leftOut.takeIf { it > 0 }?.let { message("patch.dialog.summary.leftOut", it) },
        ).joinToString(message("patch.note.separator"))
        copyAction.isEnabled = files > 0
        saveAction.isEnabled = files > 0
        fileList.repaint()
    }

    @Nls
    private fun noteOf(row: PatchRow): String = when {
        row.sensitive && !includeSensitive -> message("patch.dialog.row.leftOut")
        row.binary -> message("patch.dialog.row.binary")
        row.sensitive -> message("patch.dialog.row.sensitive", changeText(row.change))
        else -> changeText(row.change)
    }

    @Nls
    private fun changeText(change: PatchChange): String = when (change) {
        PatchChange.CHANGED -> message("patch.dialog.row.changed")
        PatchChange.ADDED -> message("patch.dialog.row.added")
        PatchChange.DELETED -> message("patch.dialog.row.deleted")
    }

    /** The note of [row] in the list (tests). */
    internal fun noteForTests(row: PatchRow): String = noteOf(row)

    private companion object {
        const val PREFERRED_WIDTH = 560
        const val MAX_VISIBLE_ROWS = 14
    }
}
