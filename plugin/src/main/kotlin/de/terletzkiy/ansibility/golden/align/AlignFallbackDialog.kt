package de.terletzkiy.ansibility.golden.align

import com.intellij.CommonBundle
import com.intellij.diff.DiffManagerEx
import com.intellij.diff.DiffRequestFactory
import com.intellij.diff.InvalidDiffRequestException
import com.intellij.diff.merge.MergeRequest
import com.intellij.diff.merge.MergeResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.Consumer
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.ui.JBUI
import de.terletzkiy.ansibility.golden.AnsibilityGoldenBundle.message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

/**
 * The merge workspace without the VCS module (plan amendment R24, D185): what [AlignFallbackDialog] does, without UI.
 *
 * - **Take Source** and **Keep Target** accept the selected rows whole ([AlignSession.accept]).
 * - **Merge…** (a changed text file) opens the platform's three-way merge viewer, built in: the target's document is
 *   the output, the sides are the target (also the base, D186) and the source; the result is saved when the viewer
 *   closes, and the row is resolved by how it closed.
 *
 * EDT throughout; failures go to [AlignUi.error] and leave the row open.
 */
class AlignFallback internal constructor(val project: Project, val session: AlignSession) {
    /** The rows still open. */
    val rows: List<AlignRow> get() = session.openRows

    /** Takes the source's files for [rows]. */
    @RequiresEdt
    fun takeSource(rows: List<AlignRow>) {
        try {
            session.accept(rows, AlignSide.SOURCE)
        } catch (e: AlignException) {
            error(e.message.orEmpty())
            return
        }
        session.resolved(rows, AlignResolution.TAKEN_FROM_SOURCE)
    }

    /** Keeps the target's files for [rows] (nothing is written). */
    @RequiresEdt
    fun keepTarget(rows: List<AlignRow>) {
        session.resolved(rows, AlignResolution.KEPT)
    }

    /** Opens the merge viewer for [row] (only when [AlignRow.canMerge]). */
    @RequiresEdt
    fun merge(row: AlignRow) {
        if (!row.canMerge) return
        val revisions = try {
            runWithModalProgressBlocking(project, message("align.progress.reading", row.relPath)) {
                withContext(Dispatchers.Default) { session.revisions(row) }
            }
        } catch (e: AlignException) {
            error(e.message.orEmpty())
            return
        }
        val texts = session.texts
        val request = try {
            DiffRequestFactory.getInstance().createMergeRequest(
                project,
                row.file,
                listOf(revisions.target, revisions.base, revisions.source),
                revisions.conflictType,
                texts.mergeWindowTitle(row.relPath),
                listOf(texts.targetPanel, texts.resultPanel, texts.sourcePanel),
                Consumer { result -> merged(row, result) },
            )
        } catch (e: InvalidDiffRequestException) {
            error(e.message.orEmpty())
            return
        }
        val show = showMergeForTests
        if (show != null) show(request) else DiffManagerEx.getInstance().showMergeBuiltin(project, request)
    }

    /** The viewer closed with [result]: its output is saved, and the row resolved accordingly (nothing on Cancel). */
    private fun merged(row: AlignRow, result: MergeResult) {
        val resolution = when (result) {
            MergeResult.CANCEL -> return
            MergeResult.LEFT -> AlignResolution.KEPT
            MergeResult.RIGHT -> AlignResolution.TAKEN_FROM_SOURCE
            MergeResult.RESOLVED -> AlignResolution.MERGED
        }
        val documents = FileDocumentManager.getInstance()
        documents.getCachedDocument(row.file)?.let { document -> ApplicationManager.getApplication().runWriteAction { documents.saveDocument(document) } }
        session.resolved(listOf(row), resolution)
        onChange?.invoke()
    }

    /** Called when a merge viewer resolved a row after [merge] returned (the dialog reloads its list). */
    var onChange: (() -> Unit)? = null

    private fun error(text: String) {
        AlignUi.getInstance().error(project, message("align.error.title", session.target.name), text)
    }

    companion object {
        /** Replaces the merge viewer (tests: apply a result to the request instead of showing it). */
        @TestOnly
        @Volatile
        var showMergeForTests: ((MergeRequest) -> Unit)? = null
    }
}

/**
 * The fallback merge workspace (plan amendment R24, D185) for an IDE without the VCS module: the differing files with
 * their state on each side, and Take Source, Keep Target and Merge… for the selection; it closes by itself when
 * every file is resolved. A plain [DialogWrapper] over [AlignFallback].
 */
class AlignFallbackDialog private constructor(private val fallback: AlignFallback) : DialogWrapper(fallback.project) {
    private val tableModel = Model()
    private val table = JBTable(tableModel).apply {
        setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        emptyText.text = message("align.fallback.empty")
    }
    private val take = JButton(message("align.fallback.take"))
    private val keep = JButton(message("align.fallback.keep"))
    private val merge = JButton(message("align.fallback.merge"))

    init {
        title = fallback.session.texts.dialogTitle
        setCancelButtonText(CommonBundle.getCloseButtonText())
        take.addActionListener { act { fallback.takeSource(it) } }
        keep.addActionListener { act { fallback.keepTarget(it) } }
        merge.addActionListener { selected().singleOrNull()?.let { row -> act { fallback.merge(row) } } }
        table.selectionModel.addListSelectionListener { updateButtons() }
        fallback.onChange = { reloaded() }
        init()
        if (tableModel.rowCount > 0) table.setRowSelectionInterval(0, 0)
        updateButtons()
    }

    override fun createActions() = arrayOf(cancelAction)

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
        add(JBLabel(fallback.session.texts.description), BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(take)
            add(keep)
            add(merge)
        }, BorderLayout.SOUTH)
        preferredSize = JBUI.size(640, 360)
    }

    private fun selected(): List<AlignRow> = table.selectedRows.toList().mapNotNull { index -> tableModel.rows.getOrNull(index) }

    private fun act(action: (List<AlignRow>) -> Unit) {
        val rows = selected()
        if (rows.isEmpty()) return
        action(rows)
        reloaded()
    }

    private fun reloaded() {
        tableModel.reload()
        if (tableModel.rowCount == 0 && isShowing) close(OK_EXIT_CODE) else updateButtons()
    }

    private fun updateButtons() {
        val rows = selected()
        take.isEnabled = rows.isNotEmpty()
        keep.isEnabled = rows.isNotEmpty()
        merge.isEnabled = rows.singleOrNull()?.canMerge == true
    }

    private inner class Model : AbstractTableModel() {
        var rows: List<AlignRow> = fallback.rows
            private set

        fun reload() {
            rows = fallback.rows
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = 3

        override fun getColumnName(column: Int): String = when (column) {
            0 -> message("align.fallback.column.file")
            1 -> fallback.session.texts.targetColumn
            else -> fallback.session.texts.sourceColumn
        }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val row = rows[rowIndex]
            val texts = fallback.session.texts
            return when (columnIndex) {
                0 -> row.relPath
                1 -> texts.targetStatus(row)
                else -> texts.sourceStatus(row)
            }
        }
    }

    companion object {
        /** Shows the fallback workspace for [session], modal; returns once it is closed. */
        @RequiresEdt
        fun show(project: Project, session: AlignSession) {
            val fallback = AlignFallback(project, session)
            val replaced = showForTests
            if (replaced != null) {
                replaced(fallback)
                return
            }
            AlignFallbackDialog(fallback).show()
        }

        /** Replaces the dialog (tests drive [AlignFallback] instead). */
        @TestOnly
        @Volatile
        var showForTests: ((AlignFallback) -> Unit)? = null
    }
}
