package de.terletzkiy.ansibility.run.view

import com.intellij.execution.console.ConsoleViewWrapperBase
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.execution.ui.RunnerLayoutUi
import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import de.terletzkiy.ansibility.run.AnsibilityRunBundle.message
import de.terletzkiy.ansibility.run.events.HostRun
import de.terletzkiy.ansibility.run.events.ItemRun
import de.terletzkiy.ansibility.run.events.PlayRun
import de.terletzkiy.ansibility.run.events.StageRun
import de.terletzkiy.ansibility.run.events.TaskRun
import de.terletzkiy.ansibility.run.events.UnitRun
import de.terletzkiy.ansibility.toolwindow.AnsibilityToolWindowIcons
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.BoundedRangeModel
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/**
 * The "Plays" tab of a playbook run: the plays in a strip (status, name, counts), the run as a tree (plays → tasks →
 * hosts → items, then the recap) with filters, and the details of the selection. A Molecule run shows its stages in
 * the strip and at the top of the tree. It follows the [RunEventCollector] live; a run without events (an old
 * Ansible, another callback setup) says so and leaves the console to show it.
 */
class AnsibleRunView(
    private val project: Project,
    private val collector: RunEventCollector,
    private val actions: RunViewActions,
    hostPath: (String) -> Path?,
) : Disposable {
    private val model get() = collector.model
    val treeModel = RunTreeModel(collector.model)
    val tree = Tree(treeModel.treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = RunTreeRenderer({ collector.model.lastEvent }, { collector.model.finished })
        emptyText.text = message("run.view.waiting")
    }
    val details = RunDetails(project, collector.model, actions, hostPath)
    private val detailsHolder = JPanel(BorderLayout())
    /** The plays, or a Molecule run's stages, or a batch's units. */
    private val stripModel = DefaultListModel<Any>()
    val strip = JBList(stripModel).apply {
        layoutOrientation = JList.HORIZONTAL_WRAP
        visibleRowCount = 1
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = object : ColoredListCellRenderer<Any>() {
            override fun customizeCellRenderer(list: JList<out Any>, value: Any?, index: Int, selected: Boolean, hasFocus: Boolean) {
                val detail = when (value) {
                    is UnitRun -> {
                        icon = RunViewTexts.unitIcon(value, collector.model.finished)
                        append(value.name)
                        if (value.started == null || (value.exitCode ?: 0) != 0) RunViewTexts.unitStatus(value, collector.model.finished) else RunViewTexts.tally(value.counts)
                    }
                    is StageRun -> {
                        icon = RunViewTexts.icon(value.status)
                        append(message("run.view.stage", value.scenario, value.action))
                        value.result?.takeUnless { it.startsWith("Successful", ignoreCase = true) } ?: RunViewTexts.tally(value.counts)
                    }
                    is PlayRun -> {
                        icon = RunViewTexts.icon(value.status)
                        append(value.name.ifEmpty { message("run.view.play.unnamed") })
                        RunViewTexts.tally(value.counts)
                    }
                    else -> return
                }
                if (detail.isNotEmpty()) append("  $detail", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                ipad = JBUI.insets(2, 6)
            }
        }
        emptyText.text = ""
    }

    private val treeScroll = ScrollPaneFactory.createScrollPane(tree, true)

    /**
     * The strip scrolls sideways; its scroll bar gets a row of its own below the chips instead of covering them (or,
     * without the room for it, cutting them): the height has that row whenever the chips are wider than the view.
     */
    private val stripScroll: JBScrollPane = object : JBScrollPane(strip) {
        override fun getPreferredSize(): Dimension {
            val size = super.getPreferredSize()
            val insets = insets
            val available = (width.takeIf { it > 0 } ?: parent?.width ?: 0) - insets.left - insets.right
            val chips = strip.preferredSize
            val bar = if (available in 1 until chips.width) horizontalScrollBar.preferredSize.height else 0
            size.height = chips.height + bar + insets.top + insets.bottom
            return size
        }
    }.apply {
        border = JBUI.Borders.empty()
        isOverlappingScrollBar = false
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
    }

    /**
     * "Scroll to the End" (remembered for every run): while the tree shows its last row, new rows keep it at the end,
     * like the console; scrolling up stops it until the end shows again.
     */
    var scrollToEnd: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(SCROLL_TO_END_KEY, true)
        set(value) = PropertiesComponent.getInstance().setValue(SCROLL_TO_END_KEY, value, true)

    /** Called once when the run ended without any event: the console tab takes over. */
    var onNoEvents: () -> Unit = {}

    private var shownFor: Any? = this
    private var shownState: Any? = null
    private var expandedFailures = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<TaskRun, Boolean>())
    private val collapsedUnits = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<UnitRun, Boolean>())
    private var noEventsReported = false

    val component: JComponent = JPanel(BorderLayout()).apply {
        val toolbar = ActionManager.getInstance().createActionToolbar("AnsibilityRunView", toolbarActions(), true)
        toolbar.targetComponent = tree
        val left = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(treeScroll, BorderLayout.CENTER)
        }
        val splitter = OnePixelSplitter(false, 0.5f).apply {
            firstComponent = left
            secondComponent = ScrollPaneFactory.createScrollPane(detailsHolder, true)
        }
        add(stripScroll, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)
    }

    val preferredFocus: JComponent get() = tree

    init {
        collector.addListener(::refresh)
        tree.addTreeSelectionListener { updateDetails(force = true) }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val task = selectedNode()?.userObject as? TaskRun ?: return false
                return task.path?.let(details::navigate) == true
            }
        }.installOn(tree)
        TreeSpeedSearch.installOn(tree, false) { path -> text(path.lastPathComponent as? DefaultMutableTreeNode) }
        strip.addListSelectionListener { event ->
            if (event.valueIsAdjusting) return@addListSelectionListener
            val chosen = strip.selectedValue ?: return@addListSelectionListener
            treeModel.node(chosen)?.let { TreeUtil.selectNode(tree, it) }
        }
        refresh()
    }

    /** Brings the strip, the tree and the details up to the model (after each batch of events). */
    fun refresh() {
        val follow = scrollToEnd && atEnd(treeScroll.verticalScrollBar.model)
        val created = treeModel.sync()
        for (node in created) {
            val value = node.userObject
            if (value is StageRun || value is PlayRun || (value is HostRun && value.items.isNotEmpty() && value.status.isFailure)) tree.expandPath(TreePath(node.path).parentPath ?: continue)
            if (value is PlayRun || value is StageRun) tree.expandPath(TreePath(node.path))
        }
        for (play in model.plays) {
            for (task in play.tasks) {
                if (task.status.isFailure && expandedFailures.add(task)) treeModel.node(task)?.let { tree.expandPath(TreePath(it.path)) }
            }
        }
        // A batch: a unit opens while it runs; once it passed it closes (failed ones stay open), so long runs stay short.
        for (unit in model.units) {
            if (unit.ended != null && !unit.status.isFailure && collapsedUnits.add(unit)) treeModel.node(unit)?.let { tree.collapsePath(TreePath(it.path)) }
        }
        val chips: List<Any> = model.units.ifEmpty { model.stages.ifEmpty { model.plays } }
        val selected = strip.selectedValue
        if (stripModel.size() != chips.size || (0 until stripModel.size()).any { stripModel[it] !== chips[it] }) {
            stripModel.clear()
            chips.forEach(stripModel::addElement)
            selected?.let { strip.setSelectedValue(it, false) }
        }
        strip.repaint()
        if (follow && created.isNotEmpty()) showEnd()
        if (model.finished && !model.hasEvents) {
            tree.emptyText.text = message("run.view.no.events")
            if (!noEventsReported) {
                noEventsReported = true
                onNoEvents()
            }
        }
        updateDetails(force = false)
    }

    private fun selectedNode(): DefaultMutableTreeNode? = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode

    private fun updateDetails(force: Boolean) {
        val node = selectedNode()
        val value = node?.userObject
        val state: Any? = when (value) {
            is HostRun -> listOf(value.status, value.retries, value.polls, value.items.size)
            is ItemRun -> value.status
            else -> model.modificationCount
        }
        if (!force && value === shownFor && state == shownState) return
        shownFor = value
        shownState = state
        detailsHolder.removeAll()
        val content = if (node == null) {
            details.component(null, null, null, null)
        } else {
            details.component(value, treeModel.taskOf(node), treeModel.playOf(node), treeModel.hostOf(node))
        }
        detailsHolder.add(content, BorderLayout.CENTER)
        detailsHolder.revalidate()
        detailsHolder.repaint()
    }

    private fun text(node: DefaultMutableTreeNode?): String = when (val value = node?.userObject) {
        is UnitRun -> "${value.name} ${value.label}"
        is StageRun -> "${value.scenario} ${value.action}"
        is PlayRun -> value.name
        is TaskRun -> value.name
        is HostRun -> value.host
        is ItemRun -> value.label
        else -> ""
    }

    private fun toolbarActions() = DefaultActionGroup(
        object : DumbAwareToggleAction(message("run.view.action.show.ok"), null, AllIcons.RunConfigurations.TestPassed) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = treeModel.showOk
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                treeModel.showOk = state
                refresh()
            }
        },
        object : DumbAwareToggleAction(message("run.view.action.show.skipped"), null, AllIcons.RunConfigurations.TestSkipped) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = treeModel.showSkipped
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                treeModel.showSkipped = state
                refresh()
            }
        },
        Separator.getInstance(),
        object : DumbAwareAction(message("run.view.action.expand"), null, AllIcons.Actions.Expandall) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) = TreeUtil.expandAll(tree)
        },
        object : DumbAwareAction(message("run.view.action.collapse"), null, AllIcons.Actions.Collapseall) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) = TreeUtil.collapseAll(tree, 1)
        },
        object : DumbAwareToggleAction(message("run.view.action.scroll.end"), message("run.view.action.scroll.end.description"), AllIcons.RunConfigurations.Scroll_down) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = scrollToEnd
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                scrollToEnd = state
                if (state) showEnd()
            }
        },
        Separator.getInstance(),
        object : DumbAwareAction(message("run.view.action.source"), null, AllIcons.Actions.EditSource) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = selectedTask()?.path != null
            }
            override fun actionPerformed(e: AnActionEvent) {
                selectedTask()?.path?.let(details::navigate)
            }
        },
        object : DumbAwareAction(message("run.view.action.start.at"), null, AllIcons.Actions.RunToCursor) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabledAndVisible = actions.playbookActions
                e.presentation.isEnabled = actions.playbookActions && model.finished && selectedTask()?.handler == false
            }
            override fun actionPerformed(e: AnActionEvent) {
                selectedTask()?.let { actions.startAt(it.name) }
            }
        },
        object : DumbAwareAction(message("run.view.action.rerun.failed"), null, AllIcons.RunConfigurations.RerunFailedTests) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabledAndVisible = actions.playbookActions
                e.presentation.isEnabled = actions.playbookActions && model.finished && model.failedHosts().isNotEmpty()
            }
            override fun actionPerformed(e: AnActionEvent) = actions.rerunHosts(model.failedHosts())
        },
        object : DumbAwareAction(message("run.view.action.rerun.failed.units"), null, AllIcons.RunConfigurations.RerunFailedTests) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabledAndVisible = actions.unitActions
                e.presentation.isEnabled = actions.unitActions && model.finished && model.units.any { it.status.isFailure && !it.stopped }
            }
            override fun actionPerformed(e: AnActionEvent) = actions.runUnits(model.units.filter { it.status.isFailure && !it.stopped }.map { it.key })
        },
    )

    /** Shows the last row of the tree and the last chip of the strip, once both laid out their new rows. */
    private fun showEnd() = ApplicationManager.getApplication().invokeLater({
        if (tree.rowCount > 0) tree.scrollRowToVisible(tree.rowCount - 1)
        if (stripModel.size() > 0) strip.ensureIndexIsVisible(stripModel.size() - 1)
    }, ModalityState.any())

    /** The task of the selection (a task, or the task of a host or item). */
    fun selectedTask(): TaskRun? = selectedNode()?.let(treeModel::taskOf)

    override fun dispose() = Unit

    @TestOnly
    fun detailsComponentForTests(): JComponent = detailsHolder

    @TestOnly
    fun stripScrollForTests(): JBScrollPane = stripScroll

    companion object {
        internal const val SCROLL_TO_END_KEY = "ansibility.runView.scrollToEnd"

        /** Whether [bar] shows its end (a few pixels short count): new rows then keep it there. */
        internal fun atEnd(bar: BoundedRangeModel): Boolean = bar.value + bar.extent >= bar.maximum - JBUI.scale(END_SLACK)

        private const val END_SLACK = 8
    }
}

/**
 * The console of a playbook run with the "Plays" tab ([view]) in front of it: the Run tool window shows the view and
 * the console as two tabs, and the console keeps its own actions. A [banner] (a Molecule run's destroy countdown)
 * shows above both tabs; without a view the console alone gets it.
 */
class AnsibleRunConsole(console: ConsoleView, private val view: AnsibleRunView?, private val banner: RunBanner? = null) :
    ConsoleViewWrapperBase(console) {
    init {
        view?.let { Disposer.register(this, it) }
    }

    /** The console's tab content: the platform builds the Console tab from [getComponent]. */
    private val consoleComponent: JComponent by lazy { withBanner(delegate.component) }

    override fun getComponent(): JComponent = consoleComponent

    override fun buildUi(ui: RunnerLayoutUi) {
        val view = view ?: return super.buildUi(ui)
        val plays = ui.createContent(PLAYS_ID, withBanner(view.component), message("run.view.tab"), AnsibilityToolWindowIcons.Root, view.preferredFocus)
        plays.isCloseable = false
        ui.addContent(plays)
        super.buildUi(ui)
        ui.selectAndFocus(plays, false, false)
        view.onNoEvents = { ui.findContent(ExecutionConsole.CONSOLE_CONTENT_ID)?.let { ui.selectAndFocus(it, false, false) } }
    }

    private fun withBanner(content: JComponent): JComponent {
        val banner = banner ?: return content
        return JPanel(BorderLayout()).apply {
            add(banner.component(), BorderLayout.NORTH)
            add(content, BorderLayout.CENTER)
        }
    }

    override fun getExecutionConsoleId(): String = "AnsibilityRun"

    companion object {
        const val PLAYS_ID = "AnsibilityPlays"
    }
}
