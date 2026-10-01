package de.terletzkiy.ansibility.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.DefaultTreeExpander
import com.intellij.ide.util.treeView.NodeRenderer
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.pom.Navigatable
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.tree.AsyncTreeModel
import com.intellij.ui.tree.StructureTreeModel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeModelAdapter
import com.intellij.util.ui.tree.TreeUtil
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.toolwindow.model.AnsibleTreeNode
import de.terletzkiy.ansibility.toolwindow.model.NavigationTarget
import de.terletzkiy.ansibility.toolwindow.model.TreeContext
import de.terletzkiy.ansibility.toolwindow.model.WorkspaceSnapshot
import java.awt.event.MouseEvent
import java.util.concurrent.CompletableFuture
import javax.swing.JComponent
import javax.swing.ToolTipManager
import javax.swing.event.TreeModelEvent
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * The content of the "Ansibility" tool window (plan F6.1–F6.4): a toolbar, the tree on top and the details pane below.
 *
 * - The tree is a [StructureTreeModel] over [AnsibleTreeStructure] wrapped in an [AsyncTreeModel] and shown by a
 *   [Tree] with a [NodeRenderer] and speed search ([TreeSpeedSearch.installOn]).
 * - Double-click and Enter open a node's target (a group key or host entry in `hosts.yml`, a var file,
 *   `ansible.cfg`, a playbook or play) and expand container nodes; F4 (Jump to Source) always opens the target.
 *   Navigation is `OpenFileDescriptor(project, vf, offset).navigate(true)`, also offered to the platform through
 *   [CommonDataKeys.NAVIGATABLE_ARRAY].
 * - [AnsibleTreeRefresher] rebuilds the snapshot in a background read action; [applySnapshot] swaps it in on the EDT
 *   and invalidates the model, which keeps expansion and selection.
 */
class AnsibleToolWindowPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {
    private val structure = AnsibleTreeStructure(project, TreeContext { PlayGraph.getInstance(project).playsOf(it) })
    private val structureModel = StructureTreeModel(structure, this)
    private val asyncModel = AsyncTreeModel(structureModel, this)

    /** The tree; public for tests. */
    val tree: Tree = Tree(asyncModel)

    /** The details pane; public for tests. */
    val detailsView: AnsibleDetailsView = AnsibleDetailsView(::navigate)

    private val refresher: AnsibleTreeRefresher
    private var expandedOnce = false
    private var detailsUpdateScheduled = false

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = NodeRenderer()
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.emptyText.text = AnsibilityToolWindowBundle.message("toolwindow.loading")
        // Double-click is handled below: navigable nodes open their target, containers toggle.
        tree.toggleClickCount = 0
        ToolTipManager.sharedInstance().registerComponent(tree)
        TreeSpeedSearch.installOn(tree)
        TreeUtil.installActions(tree)
        // Right-click menu: "Use as Ansible context" (HA3) and other areas' node actions join this group.
        PopupHandler.installPopupMenu(tree, POPUP_GROUP, POPUP_PLACE)
        installNavigation()
        tree.addTreeSelectionListener { updateDetails() }
        // A refresh keeps the selected row but gives it a new element: re-render the details once the model settled.
        asyncModel.addTreeModelListener(object : TreeModelAdapter() {
            override fun process(event: TreeModelEvent, type: TreeModelAdapter.EventType) = scheduleDetailsUpdate()
        })

        val splitter = OnePixelSplitter(true, SPLITTER_PROPORTION_KEY, DEFAULT_PROPORTION).apply {
            firstComponent = ScrollPaneFactory.createScrollPane(tree, true)
            secondComponent = detailsView.component
        }
        setContent(splitter)
        setToolbar(createToolbar())

        refresher = AnsibleTreeRefresher.start(project, this, ::applySnapshot)
    }

    /** The snapshot the tree shows now. */
    val snapshot: WorkspaceSnapshot get() = structure.snapshot

    /** How many snapshots were applied (tests wait for a refresh with it). */
    val refreshCount: Long get() = refresher.appliedCount

    /** Asks for a rebuild of the snapshot (the Refresh action). */
    fun requestRefresh() = refresher.request()

    /**
     * Shows [snapshot]: invalidates the model (expansion and selection are kept, the presentation is new), expands the
     * roots on the first load and refreshes the details of the selection. Must be called on the EDT.
     */
    fun applySnapshot(snapshot: WorkspaceSnapshot): CompletableFuture<*> {
        structure.snapshot = snapshot
        if (snapshot.isEmpty) {
            tree.emptyText.setText(AnsibilityToolWindowBundle.message("toolwindow.empty"))
                .appendLine(AnsibilityToolWindowBundle.message("toolwindow.empty.hint"))
        } else {
            tree.emptyText.clear()
        }
        val done = structureModel.invalidateAsync()
        if (!expandedOnce && snapshot.roots.isNotEmpty()) {
            expandedOnce = true
            done.thenRun {
                ApplicationManager.getApplication().invokeLater(
                    { TreeUtil.promiseExpand(tree, INITIAL_EXPAND_DEPTH) },
                    ModalityState.any(),
                ) { isDisposed }
            }
        }
        return done
    }

    /** The node of the selected row, or null. */
    fun selectedNode(): AnsibleTreeNode? = nodeOf(TreeUtil.getSelectedPathIfOne(tree))

    /** Opens [node]'s target; false when it has none or it is gone. */
    fun navigate(node: AnsibleTreeNode): Boolean = node.target?.let(::navigate) == true

    /** Opens [target] with `OpenFileDescriptor(project, vf, offset).navigate(true)`; false when it is gone. */
    fun navigate(target: NavigationTarget): Boolean {
        if (!target.file.isValid) return false
        val descriptor = target.descriptor(project)
        if (!descriptor.canNavigate()) return false
        descriptor.navigate(true)
        return true
    }

    override fun uiDataSnapshot(sink: DataSink) {
        super.uiDataSnapshot(sink)
        val target = selectedNode()?.target?.takeIf { it.file.isValid } ?: return
        sink[CommonDataKeys.NAVIGATABLE_ARRAY] = arrayOf<Navigatable>(target.descriptor(project))
        sink[CommonDataKeys.VIRTUAL_FILE] = target.file
    }

    /** True once the tool window content was closed. */
    @Volatile
    var isDisposed: Boolean = false
        private set

    override fun dispose() {
        isDisposed = true
    }

    private fun nodeOf(path: TreePath?): AnsibleTreeNode? =
        path?.let { TreeUtil.getLastUserObject(AnsibleNodeDescriptor::class.java, it) }?.node

    private fun updateDetails() {
        detailsView.show(selectedNode()?.details())
    }

    private fun scheduleDetailsUpdate() {
        if (detailsUpdateScheduled) return
        detailsUpdateScheduled = true
        ApplicationManager.getApplication().invokeLater({
            detailsUpdateScheduled = false
            updateDetails()
        }, ModalityState.any()) { isDisposed }
    }

    /** Opens the target of a navigable node, else expands or collapses [path]. */
    private fun activate(path: TreePath): Boolean {
        val node = nodeOf(path) ?: return false
        if (node.navigatesOnDoubleClick && navigate(node)) return true
        if (tree.isExpanded(path)) tree.collapsePath(path) else tree.expandPath(path)
        return true
    }

    private fun installNavigation() {
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val path = tree.getPathForLocation(event.x, event.y) ?: return false
                return activate(path)
            }
        }.installOn(tree)
        DumbAwareAction.create { TreeUtil.getSelectedPathIfOne(tree)?.let(::activate) }
            .registerCustomShortcutSet(CommonShortcuts.ENTER, tree, this)
        DumbAwareAction.create { selectedNode()?.let(::navigate) }
            .registerCustomShortcutSet(CommonShortcuts.getEditSource(), tree, this)
    }

    private fun createToolbar(): JComponent {
        val expander = DefaultTreeExpander(tree)
        val actions = CommonActionsManager.getInstance()
        val group = DefaultActionGroup(
            RefreshAction(),
            actions.createExpandAllAction(expander, tree),
            actions.createCollapseAllAction(expander, tree),
        )
        // Context (HA3) and workspace-scope (WS1) actions join from their own fragments (`add-to-group`).
        (ActionManager.getInstance().getAction(TOOLBAR_EXTRA_GROUP) as? ActionGroup)?.let {
            group.addSeparator()
            group.add(it)
        }
        val toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, group, true)
        toolbar.setTargetComponent(tree)
        return toolbar.component
    }

    private inner class RefreshAction : DumbAwareAction(
        AnsibilityToolWindowBundle.message("action.refresh.text"),
        AnsibilityToolWindowBundle.message("action.refresh.description"),
        AllIcons.Actions.Refresh,
    ) {
        override fun actionPerformed(e: AnActionEvent) = requestRefresh()

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    }

    companion object {
        const val TOOLBAR_PLACE: String = "AnsibilityToolWindow"
        /** The action group other areas add tool-window toolbar actions to (registered in ansibility-core.xml). */
        const val TOOLBAR_EXTRA_GROUP = "Ansibility.ToolWindow.Toolbar.Extra"
        /** The tree's popup menu group (declared in ansibility-host.xml) and its action place. */
        const val POPUP_GROUP = "Ansibility.ToolWindow.Popup"
        const val POPUP_PLACE = "AnsibilityToolWindowPopup"
        private const val SPLITTER_PROPORTION_KEY = "ansibility.toolwindow.splitter"
        private const val DEFAULT_PROPORTION = 0.6f

        /** On the first load the roots are expanded, so their Shared vars, Environments and Playbooks show. */
        private const val INITIAL_EXPAND_DEPTH = 2

        /** Creates a panel owned by [parent]. */
        fun create(project: Project, parent: Disposable): AnsibleToolWindowPanel =
            AnsibleToolWindowPanel(project).also { Disposer.register(parent, it) }
    }
}
