package de.terletzkiy.ansibility.toolwindow

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.util.treeView.AbstractTreeStructure
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.ide.util.treeView.PresentableNodeDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.tree.LeafState
import de.terletzkiy.ansibility.toolwindow.model.AnsibleTreeNode
import de.terletzkiy.ansibility.toolwindow.model.TreeContext
import de.terletzkiy.ansibility.toolwindow.model.WorkspaceNode
import de.terletzkiy.ansibility.toolwindow.model.WorkspaceSnapshot

/**
 * The [AbstractTreeStructure] behind the tool window's `StructureTreeModel`. Elements are [AnsibleTreeNode]s over
 * the current [snapshot]; the model calls this class on its background invoker inside a read action, and children
 * are computed from the in-memory snapshot (plays of an expanded playbook come from [context]).
 */
class AnsibleTreeStructure(private val project: Project, private val context: TreeContext) : AbstractTreeStructure() {
    /** The snapshot the tree shows; replaced on the EDT before `StructureTreeModel.invalidateAsync()`. */
    @Volatile
    var snapshot: WorkspaceSnapshot = WorkspaceSnapshot.EMPTY

    override fun getRootElement(): Any = WorkspaceNode(snapshot)

    override fun getChildElements(element: Any): Array<Any> {
        // The root element is equal across snapshots; always expand the current one.
        val node = if (element is WorkspaceNode) WorkspaceNode(snapshot) else element as? AnsibleTreeNode ?: return emptyArray()
        return node.children(context).toTypedArray()
    }

    override fun getParentElement(element: Any): Any? = (element as? AnsibleTreeNode)?.parent

    override fun createDescriptor(element: Any, parentDescriptor: NodeDescriptor<*>?): NodeDescriptor<*> =
        AnsibleNodeDescriptor(project, parentDescriptor, element as AnsibleTreeNode)

    override fun isAlwaysLeaf(element: Any): Boolean = (element as? AnsibleTreeNode)?.isLeaf == true

    override fun getLeafState(element: Any): LeafState = if (isAlwaysLeaf(element)) LeafState.ALWAYS else LeafState.DEFAULT

    override fun commit() = Unit

    override fun hasSomethingToCommit(): Boolean = false
}

/**
 * Renders one [AnsibleTreeNode]: icon, name, the grey extra text and an HTML tooltip. Double-click expands only
 * container nodes; nodes with a navigation target open it instead (plan F6.3).
 */
class AnsibleNodeDescriptor(
    project: Project,
    parent: NodeDescriptor<*>?,
    val node: AnsibleTreeNode,
) : PresentableNodeDescriptor<AnsibleTreeNode>(project, parent) {
    override fun getElement(): AnsibleTreeNode = node

    override fun update(presentation: PresentationData) {
        val model = node.presentation()
        presentation.setIcon(AnsibilityToolWindowIcons.of(model.icon))
        presentation.presentableText = model.name
        presentation.addText(model.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        model.extra?.takeIf { it.isNotEmpty() }?.let { presentation.addText("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        if (model.tooltip.isNotEmpty()) {
            val html = HtmlBuilder()
            model.tooltip.forEachIndexed { index, line ->
                if (index > 0) html.br()
                html.append(line)
            }
            presentation.tooltip = html.wrapWithHtmlBody().toString()
        }
    }

    override fun expandOnDoubleClick(): Boolean = !node.navigatesOnDoubleClick

    override fun toString(): String = node.presentation().name
}
