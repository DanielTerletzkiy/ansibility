package de.terletzkiy.ansibility.toolwindow

import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TableUtil
import com.intellij.ui.TreeTableSpeedSearch
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.dualView.TreeTableView
import com.intellij.ui.treeStructure.treetable.ListTreeTableModelOnColumns
import com.intellij.ui.treeStructure.treetable.TreeColumnInfo
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.toolwindow.AnsibilityToolWindowBundle.message
import de.terletzkiy.ansibility.toolwindow.host.DefinitionCell
import de.terletzkiy.ansibility.toolwindow.host.EffectiveContext
import de.terletzkiy.ansibility.toolwindow.host.EffectiveRow
import de.terletzkiy.ansibility.toolwindow.host.EffectiveTexts
import de.terletzkiy.ansibility.toolwindow.host.EffectiveVarsContent
import de.terletzkiy.ansibility.toolwindow.host.MarkerCell
import de.terletzkiy.ansibility.toolwindow.host.PlayChoice
import de.terletzkiy.ansibility.toolwindow.host.PlayChoiceItem
import de.terletzkiy.ansibility.toolwindow.host.PlayOutcome
import de.terletzkiy.ansibility.toolwindow.model.NavigationTarget
import de.terletzkiy.ansibility.toolwindow.model.NodeIcon
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.table.TableCellRenderer
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/**
 * The Effective vars of one host in the details pane (plan amendment R7/R8 F8.7, WU HA7a): the play selector above a
 * [TreeTableView] over a [ListTreeTableModelOnColumns] with the columns Name | Value | Type | Layer | Source |
 * Shadowed (n). Expanding a variable shows the definitions that win in other plays (Auto over every play), the
 * definitions it shadows, struck through, and the tasks that may replace it at runtime. Vault values are masked in the
 * model already; nothing here reads a value.
 *
 * Double-click or Enter on a row opens its definition ([navigate]). Choosing a play calls [onChoice]; the panel stores it
 * and computes the new table in the background. Everything here runs on the EDT over precomputed data.
 */
class EffectiveVarsView(
    val content: EffectiveVarsContent,
    private val onChoice: (HostKey, PlayChoice) -> Unit,
    private val navigate: (NavigationTarget) -> Unit,
) {
    /** A variable, a definition it shadows or a runtime marker: one row of the tree table. */
    sealed class Row(val variable: String) : DefaultMutableTreeNode() {
        /** A variable and its winning definition. */
        class Variable(val row: EffectiveRow) : Row(row.name)

        /** Auto over every play: a definition that wins in other plays than the variable's winner. */
        class Outcome(variable: String, val outcome: PlayOutcome) : Row(variable)

        /** A definition the variable's winner shadows (or merges, under `hash_behaviour = merge`). */
        class Shadowed(variable: String, val cell: DefinitionCell) : Row(variable)

        /** A task that may replace the variable at runtime. */
        class Runtime(variable: String, val marker: MarkerCell) : Row(variable)

        /** Where double-click opens. */
        val target: NavigationTarget
            get() = when (this) {
                is Variable -> NavigationTarget(row.winner.location.file, row.winner.location.offset)
                is Outcome -> NavigationTarget(outcome.winner.location.file, outcome.winner.location.offset)
                is Shadowed -> NavigationTarget(cell.location.file, cell.location.offset)
                is Runtime -> NavigationTarget(marker.location.file, marker.location.offset)
            }

        /** Whether the row is drawn struck through (a shadowed definition that does not merge). */
        val struck: Boolean get() = this is Shadowed && !cell.merged

        override fun toString(): String = variable
    }

    private val root = DefaultMutableTreeNode()

    /** The tree table; public for tests. */
    val table: TreeTableView

    /** The play selector; public for tests. */
    val selector: ComboBox<PlayChoiceItem> = ComboBox(DefaultComboBoxModel(content.choices.toTypedArray()))

    /** The component to put into the details pane. */
    val component: JComponent

    init {
        for (row in content.table.rows) {
            val node = Row.Variable(row)
            row.others.forEach { node.add(Row.Outcome(row.name, it)) }
            row.shadowed.forEach { node.add(Row.Shadowed(row.name, it)) }
            row.markers.forEach { node.add(Row.Runtime(row.name, it)) }
            root.add(node)
        }
        val model = ListTreeTableModelOnColumns(root, columns())
        table = TreeTableView(model).apply {
            setRootVisible(false)
            tree.showsRootHandles = true
            setTreeCellRenderer(NameRenderer(content.table.context))
            emptyText.text = message("effective.table.empty")
        }
        installNavigation()
        // Type a variable name to find its row (the rows' text is the variable name).
        TreeTableSpeedSearch.installOn(table)

        selector.renderer = textListCellRenderer { item: PlayChoiceItem? -> item?.text.orEmpty() }
        selector.selectedItem = content.selected
        selector.toolTipText = message("effective.selector.tooltip")
        selector.addActionListener {
            val item = selector.selectedItem as? PlayChoiceItem ?: return@addActionListener
            // Compared with the stored choice, not the shown one: for a play that no longer runs on the host the selector
            // shows Auto, and choosing Auto must forget that play.
            if (item.choice != content.table.context.choice) onChoice(content.table.context.host, item.choice)
        }
        val header = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            add(JBLabel(message("effective.selector.label")))
            add(selector)
            border = JBUI.Borders.empty(4, 6)
        }
        // Other areas add row actions (HA9: a timed Reveal of masked vault cells) to POPUP_GROUP; they read the row's
        // definition from SELECTED_DEFINITION.
        PopupHandler.installPopupMenu(table, POPUP_GROUP, POPUP_PLACE)
        val scroll = ScrollPaneFactory.createScrollPane(table, true)
        component = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(UiDataProvider.wrapComponent(scroll) { sink -> selectedDefinition()?.let { sink[SELECTED_DEFINITION] = it } }, BorderLayout.CENTER)
        }
    }

    /** The definition of the selected row (a variable's winner, another play's winner or a shadowed definition), or null. */
    fun selectedDefinition(): DefinitionCell? = when (val row = table.selectedRow.takeIf { it >= 0 }?.let(::rowAt)) {
        is Row.Variable -> row.row.winner
        is Row.Outcome -> row.outcome.winner
        is Row.Shadowed -> row.cell
        else -> null
    }

    /** The variable rows in table order (tests). */
    val variableRows: List<Row.Variable> get() = root.children().toList().filterIsInstance<Row.Variable>()

    /** What a new table of the same host takes over from this one: the expanded variables and the selected row's variable. */
    class ViewState(val expanded: Set<String>, val selected: String?)

    /** The expanded variables and the variable of the selected row. */
    fun state(): ViewState = ViewState(
        expanded = variableRows.filter { table.tree.isExpanded(pathOf(it)) }.mapTo(HashSet()) { it.variable },
        selected = table.selectedRow.takeIf { it >= 0 }?.let(::rowAt)?.variable,
    )

    /**
     * Expands the variables of [state] this table has and selects the row of its selected variable; [scrollToSelection]
     * brings it into view once the table is laid out.
     */
    fun restore(state: ViewState) {
        for (row in variableRows) if (row.variable in state.expanded) table.tree.expandPath(pathOf(row))
        val selected = variableRows.firstOrNull { it.variable == state.selected } ?: return
        val index = table.tree.getRowForPath(pathOf(selected)).takeIf { it >= 0 } ?: return
        table.setRowSelectionInterval(index, index)
    }

    /** Scrolls the selected row into view. */
    fun scrollToSelection() = TableUtil.scrollSelectionToVisible(table)

    private fun pathOf(row: Row): TreePath = TreePath(arrayOf<Any>(root, row))

    /** The row at table row [index], or null. */
    fun rowAt(index: Int): Row? = table.tree.getPathForRow(index)?.lastPathComponent as? Row

    /** Opens the definition of the selected row; false without a selection. */
    fun openSelected(): Boolean {
        val row = table.selectedRow.takeIf { it >= 0 }?.let(::rowAt) ?: return false
        navigate(row.target)
        return true
    }

    private fun installNavigation() {
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = openSelected()
        }.installOn(table)
        table.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && openSelected()) e.consume()
            }
        })
    }

    /**
     * The Name column: the variable name (followed in grey by how the plays differ, Auto over every play), the plays of
     * another play's winner, the variable name struck through for a shadowed definition; an icon per row kind.
     */
    private class NameRenderer(private val context: EffectiveContext) : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            val node = value as? Row ?: return
            icon = AnsibilityToolWindowIcons.of(
                when (node) {
                    is Row.Variable, is Row.Outcome -> NodeIcon.VARIABLE
                    is Row.Shadowed -> NodeIcon.SHADOWED
                    is Row.Runtime -> NodeIcon.RUNTIME
                },
            )
            when (node) {
                is Row.Variable -> {
                    append(node.variable, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    EffectiveTexts.spreadText(context, node.row)?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                }
                is Row.Outcome -> append(outcomeText(node.outcome), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                else -> append(node.variable, if (node.struck) STRUCK else SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }

    /** A text column; shadowed definitions are struck through, runtime markers grey. */
    private class TextColumn(name: String, private val renderer: TableCellRenderer, private val text: (Row) -> String?) : ColumnInfo<Row, String>(name) {
        override fun valueOf(item: Row): String? = text(item)

        override fun getRenderer(item: Row): TableCellRenderer = renderer
    }

    /** Renders the text columns of one table. */
    private class TextRenderer : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            val text = value as? String ?: return
            val node = (table as? TreeTableView)?.tree?.getPathForRow(row)?.lastPathComponent as? Row
            val attributes = when {
                node?.struck == true -> STRUCK
                node is Row.Runtime -> SimpleTextAttributes.GRAYED_ATTRIBUTES
                else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
            }
            append(text, attributes)
        }
    }

    companion object {
        /** The table's popup menu group (declared in the ha7 block of `ansibility-inventory.xml`) and its action place. */
        const val POPUP_GROUP: String = "Ansibility.ToolWindow.EffectiveVars.Popup"
        const val POPUP_PLACE: String = "AnsibilityEffectiveVarsPopup"

        /** The definition of the selected row, for actions of [POPUP_GROUP]. Its value is masked; actions that reveal decrypt explicitly. */
        @JvmField
        val SELECTED_DEFINITION: DataKey<DefinitionCell> = DataKey.create("Ansibility.EffectiveVars.Definition")

        private val STRUCK = SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, UIUtil.getContextHelpForeground())

        /** `in 2 plays: System, Debug`, the Name cell of another play's winner. */
        fun outcomeText(outcome: PlayOutcome): String = message("effective.outcome.plays", outcome.plays.size, EffectiveTexts.playNames(outcome.plays))

        /** Name | Value | Type | Layer | Source | Shadowed (n), with one renderer per table. */
        private fun columns(): Array<ColumnInfo<*, *>> {
            val renderer = TextRenderer()
            fun column(name: String, text: (Row) -> String?) = TextColumn(name, renderer, text)
            return arrayOf(
                TreeColumnInfo(message("effective.column.name")),
                column(message("effective.column.value")) { row ->
                    when (row) {
                        is Row.Variable -> row.row.winner.value
                        is Row.Outcome -> row.outcome.winner.value
                        is Row.Shadowed -> row.cell.value
                        is Row.Runtime -> message("effective.marker.${row.marker.kind.name}")
                    }
                },
                column(message("effective.column.type")) { row ->
                    when (row) {
                        is Row.Variable -> message("effective.type.${row.row.winner.kind.name}")
                        is Row.Outcome -> message("effective.type.${row.outcome.winner.kind.name}")
                        is Row.Shadowed -> message("effective.type.${row.cell.kind.name}")
                        is Row.Runtime -> null
                    }
                },
                column(message("effective.column.layer")) { row ->
                    when (row) {
                        is Row.Variable -> row.row.winner.layerText
                        is Row.Outcome -> row.outcome.winner.layerText
                        is Row.Shadowed -> if (row.cell.merged) "${row.cell.layerText} · ${message("effective.merged")}" else row.cell.layerText
                        is Row.Runtime -> message("effective.layer.${row.marker.kind.layer.name}", "")
                    }
                },
                column(message("effective.column.source")) { row ->
                    when (row) {
                        is Row.Variable -> row.row.winner.source
                        is Row.Outcome -> row.outcome.winner.source
                        is Row.Shadowed -> row.cell.source
                        is Row.Runtime -> row.marker.source
                    }
                },
                column(message("effective.column.shadowed")) { row ->
                    (row as? Row.Variable)?.row?.shadowed?.size?.takeIf { it > 0 }?.toString()
                },
            )
        }
    }
}
