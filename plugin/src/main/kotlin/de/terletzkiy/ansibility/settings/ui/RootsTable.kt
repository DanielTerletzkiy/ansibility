package de.terletzkiy.ansibility.settings.ui

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.context.TargetVersion
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.diagnostics.Preset
import de.terletzkiy.ansibility.settings.AnsibilitySettingsBundle.message
import de.terletzkiy.ansibility.settings.CollectionsSource
import de.terletzkiy.ansibility.settings.RootSettings
import de.terletzkiy.ansibility.settings.nonBlank
import javax.swing.DefaultCellEditor
import javax.swing.ListSelectionModel
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

/** One detected root in the roots table: its settings key, the root, and its auto-detected target version. */
internal data class RootRow(val key: String, val root: AnsibleRoot, val detected: TargetVersion)

/**
 * The roots table of the settings page: the detected roots (read-only) with editable target ansible-core,
 * strictness preset and collections source. Values are read from and written to the page's working copy through
 * [settingsOf] and [update], so the table itself holds no settings.
 */
internal class RootsTable(
    private val settingsOf: (String) -> RootSettings,
    private val update: (String, (RootSettings) -> RootSettings) -> Unit,
) {
    private val columns: Array<ColumnInfo<RootRow, *>> = arrayOf(RootColumn(), KindColumn(), TargetColumn(), PresetColumn(), CollectionsColumn())

    val model: ListTableModel<RootRow> = ListTableModel(columns, mutableListOf())

    val table: JBTable = JBTable(model).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        setShowGrid(false)
        emptyText.text = message("roots.loading")
        visibleRowCount = VISIBLE_ROWS
    }

    /** The selected row, if any. */
    val selectedRow: RootRow?
        get() = table.selectedRow.takeIf { it >= 0 }?.let { model.getRowValue(table.convertRowIndexToModel(it)) }

    /** Shows [rows], keeping the selected root when it is still there (else the first row is selected). */
    fun setRows(rows: List<RootRow>) {
        val selectedKey = selectedRow?.key
        model.items = rows
        table.emptyText.text = message("roots.empty")
        val index = rows.indexOfFirst { it.key == selectedKey }.takeIf { it >= 0 } ?: if (rows.isEmpty()) -1 else 0
        if (index >= 0) table.selectionModel.setSelectionInterval(index, index) else table.clearSelection()
    }

    /** Repaints after the working copy changed outside the table. */
    fun refresh() {
        if (table.isEditing) table.cellEditor?.cancelCellEditing()
        table.repaint()
    }

    private inner class RootColumn : ColumnInfo<RootRow, String>(message("roots.column.root")) {
        override fun valueOf(item: RootRow): String = item.root.displayName

        override fun getTooltipText(): String = message("roots.column.root.tooltip")
    }

    private inner class KindColumn : ColumnInfo<RootRow, String>(message("roots.column.kind")) {
        override fun valueOf(item: RootRow): String = SettingsLabels.kind(item.root)
    }

    /** Target ansible-core: an editable combo with "Auto" and the common core lines; Auto shows what was detected. */
    private inner class TargetColumn : ColumnInfo<RootRow, String>(message("roots.column.target")) {
        override fun valueOf(item: RootRow): String = settingsOf(item.key).targetCore ?: autoText(item.detected)

        override fun isCellEditable(item: RootRow): Boolean = true

        override fun setValue(item: RootRow, value: String?) {
            val target = parseTarget(value) ?: return
            update(item.key) { it.copy(targetCore = target.value) }
        }

        override fun getEditor(item: RootRow): TableCellEditor {
            val choices = (listOf(message("target.auto")) + listOfNotNull(item.detected.version?.toString()) + KNOWN_CORE_LINES).distinct()
            return DefaultCellEditor(ComboBox(choices.toTypedArray()).apply { isEditable = true })
        }
    }

    private inner class PresetColumn : ColumnInfo<RootRow, Preset>(message("roots.column.preset")) {
        override fun valueOf(item: RootRow): Preset = settingsOf(item.key).preset

        override fun isCellEditable(item: RootRow): Boolean = true

        override fun setValue(item: RootRow, value: Preset?) {
            if (value != null) update(item.key) { it.copy(preset = value) }
        }

        override fun getRenderer(item: RootRow): TableCellRenderer = SettingsLabels.tableRenderer(SettingsLabels::preset)

        override fun getEditor(item: RootRow): TableCellEditor = DefaultCellEditor(
            ComboBox(Preset.entries.toTypedArray()).apply { renderer = SettingsLabels.listRenderer(SettingsLabels::preset) },
        )
    }

    private inner class CollectionsColumn : ColumnInfo<RootRow, CollectionsSource>(message("roots.column.collections")) {
        override fun valueOf(item: RootRow): CollectionsSource = settingsOf(item.key).collectionsSource

        override fun isCellEditable(item: RootRow): Boolean = true

        override fun setValue(item: RootRow, value: CollectionsSource?) {
            if (value != null) update(item.key) { it.copy(collectionsSource = value) }
        }

        override fun getRenderer(item: RootRow): TableCellRenderer = SettingsLabels.tableRenderer(SettingsLabels::collections)

        override fun getEditor(item: RootRow): TableCellEditor = DefaultCellEditor(
            ComboBox(CollectionsSource.entries.toTypedArray()).apply { renderer = SettingsLabels.listRenderer(SettingsLabels::collections) },
        )
    }

    /** A parsed target cell: [value] null means Auto. */
    data class Target(val value: String?)

    companion object {
        private const val VISIBLE_ROWS = 8

        /** Core lines offered in the target editor besides Auto and the detected version. */
        val KNOWN_CORE_LINES: List<String> = listOf("2.18", "2.19", "2.20", "2.21")

        /** How a root on Auto shows its detected version. */
        fun autoText(detected: TargetVersion): String {
            val version = detected.version ?: return message("target.auto.unknown")
            return if (detected.guessed) message("target.auto.guessed", version.toString()) else message("target.auto.detected", version.toString())
        }

        /**
         * Parses a typed target: blank or anything starting with "Auto" is Auto, a version is kept as typed
         * (trimmed), and anything else is rejected (null), leaving the cell unchanged.
         */
        fun parseTarget(text: String?): Target? {
            val trimmed = text.nonBlank() ?: return Target(null)
            if (trimmed.startsWith(message("target.auto"))) return Target(null)
            return if (CoreVersion.parse(trimmed) != null && trimmed.all { it.isDigit() || it == '.' }) Target(trimmed) else null
        }
    }
}
