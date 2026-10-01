package de.terletzkiy.ansibility.settings.ui

import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import de.terletzkiy.ansibility.settings.AnsibilitySettingsBundle.message
import de.terletzkiy.ansibility.settings.OuterLanguageRule
import javax.swing.JComponent
import javax.swing.ListSelectionModel

/** The editable, ordered table of outer-language rules (pattern → language ID) on the settings page. */
internal class OuterLanguageRulesTable {
    /** A mutable table row. */
    class Row(var pattern: String, var languageId: String)

    private val columns: Array<ColumnInfo<Row, *>> = arrayOf(
        object : ColumnInfo<Row, String>(message("jinja.rules.column.pattern")) {
            override fun valueOf(item: Row): String = item.pattern

            override fun isCellEditable(item: Row): Boolean = true

            override fun setValue(item: Row, value: String?) {
                item.pattern = value.orEmpty().trim()
            }
        },
        object : ColumnInfo<Row, String>(message("jinja.rules.column.language")) {
            override fun valueOf(item: Row): String = item.languageId

            override fun isCellEditable(item: Row): Boolean = true

            override fun setValue(item: Row, value: String?) {
                item.languageId = value.orEmpty().trim()
            }
        },
    )

    val model: ListTableModel<Row> = ListTableModel(columns, mutableListOf())

    val table: JBTable = JBTable(model).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        visibleRowCount = VISIBLE_ROWS
    }

    val component: JComponent = ToolbarDecorator.createDecorator(table)
        .setAddAction {
            if (table.isEditing) table.cellEditor?.stopCellEditing()
            model.addRow(Row("", OuterLanguageRule.PLAIN_TEXT))
            val row = model.rowCount - 1
            table.selectionModel.setSelectionInterval(row, row)
            table.editCellAt(row, 0)
        }
        .createPanel()

    /** The rules as shown, skipping rows without a pattern or language. */
    fun rules(): List<OuterLanguageRule> =
        model.items.filter { it.pattern.isNotBlank() && it.languageId.isNotBlank() }.map { OuterLanguageRule(it.pattern.trim(), it.languageId.trim()) }

    /** Shows [rules]. */
    fun reset(rules: List<OuterLanguageRule>) {
        if (table.isEditing) table.cellEditor?.cancelCellEditing()
        model.items = rules.mapTo(ArrayList()) { Row(it.pattern, it.languageId) }
    }

    /** Commits a cell that is still being edited. */
    fun stopEditing() {
        if (table.isEditing) table.cellEditor?.stopCellEditing()
    }

    private companion object {
        const val VISIBLE_ROWS = 8
    }
}
