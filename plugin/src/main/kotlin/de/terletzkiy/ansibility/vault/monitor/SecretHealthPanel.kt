package de.terletzkiy.ansibility.vault.monitor

import com.intellij.icons.AllIcons
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.DefaultTreeExpander
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.pom.Navigatable
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import de.terletzkiy.ansibility.semantics.diagnostics.Level
import de.terletzkiy.ansibility.toolwindow.AnsibilityToolWindowIcons
import de.terletzkiy.ansibility.vault.ui.AnsibilityVaultUiBundle.message
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/** A row of the Vault tab: a repository, a category of one repository, or one file. */
sealed class SecretNode {
    /** The findings this row stands for (an action on the row acts on all of them). */
    abstract val findings: List<SecretFinding>

    /** What identifies the row across snapshots (its selection and its collapsed state are kept by it). */
    abstract val rowKey: String

    /** The repository ("falcon"). */
    class Group(val group: SecretGroup, override val findings: List<SecretFinding>) : SecretNode() {
        override val rowKey: String get() = "group|${group.id}"

        override fun toString(): String = group.title
    }

    /** A category of one repository ("Plaintext private keys"). */
    class Category(val group: SecretGroup, val category: SecretCategory, override val findings: List<SecretFinding>) : SecretNode() {
        override val rowKey: String get() = "category|${group.id}|${category.name}"

        override fun toString(): String = SecretTexts.category(category)
    }

    /** One file of one category. */
    class File(val finding: SecretFinding) : SecretNode() {
        override val findings: List<SecretFinding> get() = listOf(finding)

        override val rowKey: String get() = "file|${finding.key}"

        override fun toString(): String = finding.path
    }
}

/**
 * The "Vault" tab of the Ansibility tool window (plan amendment R21, D165): every vault and secret finding of the
 * workspace ([SecretHealthService]), repository › category (Broken vault files, Broken vault values, Plaintext private
 * keys, Key-like files not vaulted) › file, with its path below the repository, what was found and its VCS status. Live:
 * each new snapshot replaces the tree, keeping the selection; "No vault or secret problems in N roots" when clean.
 *
 * Double-click or Enter opens a text file at the finding (F4 too); binary files are never opened, and nothing opens a
 * file by itself (no autoscroll). The toolbar and the context menu act on the findings under the selected rows:
 * Convert to Whole-File Vault, Encrypt Files…, Show in Project View, Exclude Path… ([SecretTabActions]).
 * Rows carry kinds, names and statuses only, never content.
 */
class SecretHealthPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {
    private val model = DefaultTreeModel(DefaultMutableTreeNode())
    private val shownCount = AtomicLong()

    /** The tree; public for tests. */
    val tree: Tree = Tree(model)

    /** The snapshot the tree shows. */
    var shown: SecretSnapshot = SecretSnapshot.NOT_COMPUTED
        private set

    /** How many snapshots were shown (tests wait on it). */
    val showCount: Long get() = shownCount.get()

    @Volatile
    var isDisposed: Boolean = false
        private set

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = Renderer()
        tree.selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
        tree.toggleClickCount = 0
        TreeSpeedSearch.installOn(tree)
        TreeUtil.installActions(tree)
        val actions = SecretTabActions.actions()
        PopupHandler.installPopupMenu(tree, DefaultActionGroup(actions), POPUP_PLACE)
        installNavigation()
        setContent(ScrollPaneFactory.createScrollPane(tree, true))
        setToolbar(toolbar(actions))
        val service = SecretHealthService.getInstance(project)
        project.messageBus.connect(this).subscribe(
            SecretHealthListener.TOPIC,
            SecretHealthListener { _, new ->
                ApplicationManager.getApplication().invokeLater({ if (!isDisposed) show(new) }, ModalityState.any()) { isDisposed }
            },
        )
        show(service.snapshot)
        service.requestRefresh()
    }

    /**
     * Shows [snapshot]: rebuilds the tree, keeps the selected rows selected and the rows you collapsed collapsed (new
     * rows are expanded). EDT.
     */
    fun show(snapshot: SecretSnapshot) {
        val selected = tree.selectionPaths.orEmpty().mapNotNull { nodeOf(it)?.rowKey }.toSet()
        val collapsed = rows().filter { (path, node) -> node !is SecretNode.File && !tree.isExpanded(path) }.mapTo(HashSet()) { it.second.rowKey }
        val root = DefaultMutableTreeNode()
        for (group in snapshot.groups) {
            val ofGroup = snapshot.findingsOf(group)
            val groupNode = DefaultMutableTreeNode(SecretNode.Group(group, ofGroup))
            for (category in SecretCategory.entries) {
                val ofCategory = ofGroup.filter { it.category == category }
                if (ofCategory.isEmpty()) continue
                val categoryNode = DefaultMutableTreeNode(SecretNode.Category(group, category, ofCategory))
                ofCategory.forEach { categoryNode.add(DefaultMutableTreeNode(SecretNode.File(it))) }
                groupNode.add(categoryNode)
            }
            root.add(groupNode)
        }
        model.setRoot(root)
        TreeUtil.expandAll(tree)
        rows().filter { it.second.rowKey in collapsed }.asReversed().forEach { tree.collapsePath(it.first) }
        tree.emptyText.text = when {
            !snapshot.computed -> message("monitor.loading")
            snapshot.rootCount == 0 -> message("monitor.empty.no.roots")
            !snapshot.statusesKnown -> message("monitor.empty.waiting.vcs")
            else -> message("monitor.empty", snapshot.rootCount)
        }
        shown = snapshot
        val reselected = rows().filter { it.second.rowKey in selected }.map { it.first }
        if (reselected.isNotEmpty()) tree.selectionPaths = reselected.toTypedArray()
        shownCount.incrementAndGet()
    }

    /** Selects the file rows of the findings with [keys] (expanding their parents) and scrolls to the first. EDT. */
    fun select(keys: Collection<String>) {
        val paths = rows().filter { (_, node) -> node is SecretNode.File && node.finding.key in keys }.map { it.first }
        if (paths.isEmpty()) return
        paths.forEach { tree.expandPath(it.parentPath) }
        tree.selectionPaths = paths.toTypedArray()
        tree.scrollPathToVisible(paths.first())
    }

    /** The visible text of every row, depth-first, indented by two spaces per level (tests). */
    fun rowTexts(): List<String> {
        val texts = ArrayList<String>()
        fun visit(node: DefaultMutableTreeNode, depth: Int) {
            for (child in node.children()) {
                child as DefaultMutableTreeNode
                texts += "  ".repeat(depth) + textOf(child.userObject as SecretNode)
                visit(child, depth + 1)
            }
        }
        visit(model.root as DefaultMutableTreeNode, 0)
        return texts
    }

    /** The findings under the selected rows (every file of a selected repository or category), in tree order. */
    fun selectedFindings(): List<SecretFinding> =
        selectedNodes().flatMap { it.findings }.distinctBy { it.key }

    /** The file rows that are selected themselves. */
    fun selectedFiles(): List<SecretFinding> = selectedNodes().filterIsInstance<SecretNode.File>().map { it.finding }

    private fun selectedNodes(): List<SecretNode> =
        tree.selectionPaths.orEmpty().sortedBy { tree.getRowForPath(it) }.mapNotNull(::nodeOf)

    private fun nodeOf(path: TreePath?): SecretNode? = (path?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? SecretNode

    /** Every node of the model with its path, depth-first. */
    private fun rows(): List<Pair<TreePath, SecretNode>> {
        val rows = ArrayList<Pair<TreePath, SecretNode>>()
        for (node in (model.root as DefaultMutableTreeNode).preorderEnumeration()) {
            val treeNode = node as? DefaultMutableTreeNode ?: continue
            val secretNode = treeNode.userObject as? SecretNode ?: continue
            rows += TreePath(treeNode.path) to secretNode
        }
        return rows
    }

    /**
     * Opens [finding]'s file at its line (or offset), when it is a valid text file; false otherwise (a binary keystore
     * is never opened). EDT.
     */
    fun open(finding: SecretFinding): Boolean {
        val descriptor = descriptorOf(finding) ?: return false
        descriptor.navigate(true)
        return true
    }

    private fun descriptorOf(finding: SecretFinding): OpenFileDescriptor? {
        val file = finding.file
        if (!file.isValid || file.isDirectory || file.fileType.isBinary) return null
        return when {
            finding.line >= 0 -> OpenFileDescriptor(project, file, finding.line, 0)
            finding.offset >= 0 -> OpenFileDescriptor(project, file, finding.offset)
            else -> OpenFileDescriptor(project, file)
        }
    }

    override fun uiDataSnapshot(sink: DataSink) {
        super.uiDataSnapshot(sink)
        sink[SELECTED_FINDINGS] = selectedFindings()
        val navigatables = selectedFiles().mapNotNull(::descriptorOf)
        if (navigatables.isNotEmpty()) sink[CommonDataKeys.NAVIGATABLE_ARRAY] = Array<Navigatable>(navigatables.size) { navigatables[it] }
    }

    override fun dispose() {
        isDisposed = true
    }

    /** Opens the file of a file row, else expands or collapses [path]. */
    private fun activate(path: TreePath): Boolean {
        val node = nodeOf(path) ?: return false
        if (node is SecretNode.File) return open(node.finding)
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
    }

    private fun toolbar(actions: List<AnAction>): javax.swing.JComponent {
        val expander = DefaultTreeExpander(tree)
        val common = CommonActionsManager.getInstance()
        val group = DefaultActionGroup().apply {
            add(SecretTabActions.Refresh())
            add(common.createExpandAllAction(expander, tree))
            add(common.createCollapseAllAction(expander, tree))
            addSeparator()
            addAll(actions)
        }
        val toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, group, true)
        toolbar.targetComponent = tree
        return toolbar.component
    }

    /** The text of a row as the renderer shows it (main text, then the grey part). */
    private fun textOf(node: SecretNode): String = when (node) {
        is SecretNode.Group -> "${node.group.title}  ${groupSummary(node)}"
        is SecretNode.Category -> "${SecretTexts.category(node.category)}  ${node.findings.size}"
        is SecretNode.File -> "${node.finding.path}  ${fileSummary(node.finding)}"
    }

    private fun groupSummary(node: SecretNode.Group): String {
        val errors = node.findings.count { it.isError }
        return SecretTexts.counts(errors, node.findings.size - errors)
    }

    private fun fileSummary(finding: SecretFinding): String =
        (finding.details + listOfNotNull(SecretTexts.status(finding.status))).joinToString(" · ")

    private inner class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            val node = (value as? DefaultMutableTreeNode)?.userObject as? SecretNode ?: return
            when (node) {
                is SecretNode.Group -> {
                    icon = if (node.group.isRoot) AnsibilityToolWindowIcons.Root else AllIcons.Nodes.Folder
                    append(node.group.title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  " + groupSummary(node), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is SecretNode.Category -> {
                    icon = levelIcon(node.findings.any { it.isError })
                    append(SecretTexts.category(node.category))
                    append("  " + node.findings.size, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is SecretNode.File -> {
                    icon = levelIcon(node.finding.level == Level.ERROR)
                    append(node.finding.path)
                    append("  " + fileSummary(node.finding), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    toolTipText = node.finding.file.presentableUrl
                }
            }
        }

        private fun levelIcon(error: Boolean) = if (error) AllIcons.General.Error else AllIcons.General.Warning
    }

    companion object {
        /** The findings under the selected rows, for [SecretTabActions]. */
        val SELECTED_FINDINGS: DataKey<List<SecretFinding>> = DataKey.create("Ansibility.Vault.SelectedFindings")

        const val TOOLBAR_PLACE: String = "AnsibilityVaultTab"
        const val POPUP_PLACE: String = "AnsibilityVaultTabPopup"
    }
}
