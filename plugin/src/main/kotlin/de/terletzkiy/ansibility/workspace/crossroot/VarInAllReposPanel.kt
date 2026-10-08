package de.terletzkiy.ansibility.workspace.crossroot

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.dispatch.SitePresentation
import de.terletzkiy.ansibility.toolwindow.AnsibleToolWindowScope
import de.terletzkiy.ansibility.workspace.AnsibilityScopeBundle.message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * The closable "This Variable in All Repos" tab (plan amendment R9, F9.8): every definition of one variable per root,
 * with an environment chooser for the effective column. It is a report: Refresh recomputes it, and the header says
 * "outdated" once PSI changed since. [origin] is the file the action started in: from a Molecule file the report lists
 * Molecule definitions whatever the setting (plan amendment R20, D154), otherwise the setting decides on each refresh.
 */
class VarInAllReposPanel(
    private val project: Project,
    private val name: String,
    val origin: VirtualFile? = null,
) : SimpleToolWindowPanel(true, true), Disposable {
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    val tree = Tree(model)
    private val header = JBLabel()
    private val environments = ComboBox<String>()
    private var stamp = -1L
    private var job: Job? = null
    private var filling = false

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = Renderer()
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = navigateSelected()
        }.installOn(tree)
        environments.addActionListener { if (!filling) refresh() }
        val north = JPanel(BorderLayout())
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
        bar.add(JBLabel(message("allrepos.env")))
        bar.add(environments)
        north.add(header, BorderLayout.CENTER)
        north.add(bar, BorderLayout.EAST)
        header.border = JBUI.Borders.empty(4, 6)
        val content = JPanel(BorderLayout())
        content.add(north, BorderLayout.NORTH)
        content.add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
        setContent(content)
        val group = DefaultActionGroup(RefreshAction())
        val toolbar = ActionManager.getInstance().createActionToolbar("AnsibilityVarInAllRepos", group, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)
        refresh()
    }

    private fun selectedEnvironment(): String? = (environments.selectedItem as? String)?.takeIf { environments.selectedIndex > 0 }

    fun refresh() {
        job?.cancel()
        header.text = message("allrepos.computing", name)
        val environment = selectedEnvironment()
        job = AnsibleToolWindowScope.getInstance(project).scope.launch(Dispatchers.Default) {
            val (report, modCount) = smartReadAction(project) {
                CrossRootVars.report(project, name, environment, origin) to PsiModificationTracker.getInstance(project).modificationCount
            }
            withContext(Dispatchers.EDT) { show(report, modCount) }
        }
    }

    private fun show(report: CrossRootReport, modCount: Long) {
        stamp = modCount
        filling = true
        val chosen = report.environment
        environments.removeAllItems()
        environments.addItem(message("allrepos.env.all"))
        report.environments.forEach(environments::addItem)
        environments.selectedItem = chosen ?: message("allrepos.env.all")
        filling = false
        header.text = message("allrepos.header", report.name, report.definitionCount, report.groups.size)
        root.removeAllChildren()
        val outside = report.groups.filter { !it.inScope }
        fun add(parent: DefaultMutableTreeNode, group: CrossRootGroup) {
            val node = DefaultMutableTreeNode(group)
            group.definitions.forEach { node.add(DefaultMutableTreeNode(DefinitionRow(group.root, it))) }
            parent.add(node)
        }
        report.groups.filter { it.inScope }.forEach { add(root, it) }
        if (outside.isNotEmpty()) {
            val folder = DefaultMutableTreeNode(OutsideScope(outside.size))
            outside.forEach { add(folder, it) }
            root.add(folder)
        }
        model.reload()
        TreeUtil.expand(tree, 2)
    }

    private fun navigateSelected(): Boolean {
        val definition = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? DefinitionRow ?: return false
        val location = definition.item.definition.location
        if (!location.file.isValid) return false
        OpenFileDescriptor(project, location.file, location.offset).navigate(true)
        return true
    }

    override fun dispose() {
        job?.cancel()
    }

    private class OutsideScope(val count: Int)

    private class DefinitionRow(val root: AnsibleRoot, val item: CrossRootDefinition)

    private inner class RefreshAction : DumbAwareAction(message("allrepos.refresh"), null, AllIcons.Actions.Refresh) {
        override fun actionPerformed(e: AnActionEvent) = refresh()

        override fun update(e: AnActionEvent) {
            val outdated = stamp >= 0 && PsiModificationTracker.getInstance(project).modificationCount != stamp
            e.presentation.text = if (outdated) message("allrepos.refresh.outdated") else message("allrepos.refresh")
            e.presentation.icon = if (outdated) AllIcons.General.Warning else AllIcons.Actions.Refresh
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
                is CrossRootGroup -> {
                    icon = AllIcons.Nodes.Folder
                    append(item.root.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  " + message("allrepos.count", item.definitions.size), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is OutsideScope -> {
                    icon = AllIcons.Nodes.Folder
                    append(message("allrepos.outside", item.count), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is DefinitionRow -> render(item)
            }
        }

        private fun render(row: DefinitionRow) {
            val item = row.item
            val definition = item.definition
            icon = AllIcons.Nodes.Variable
            val path = VfsUtilCore.getRelativePath(definition.location.file, row.root.dir, '/') ?: definition.location.file.name
            append(path + line(definition.location))
            definition.preview?.let { append(" = $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            append("   " + SitePresentation.definitionKindName(definition.kind), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            when (val mark = item.mark) {
                is DefinitionMark.Wins -> append("   " + message("allrepos.mark.wins", mark.hosts), SimpleTextAttributes.REGULAR_ATTRIBUTES)
                is DefinitionMark.Shadowed -> append("   " + message("allrepos.mark.shadowed", mark.hosts), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                is DefinitionMark.NotLoaded -> append("   " + message("allrepos.mark.unloaded"), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                DefinitionMark.None -> Unit
            }
        }

        private fun line(location: SourceLocation): String {
            val document = FileDocumentManager.getInstance().getDocument(location.file) ?: return ""
            return ":" + (document.getLineNumber(location.offset.coerceIn(0, document.textLength)) + 1)
        }
    }
}
