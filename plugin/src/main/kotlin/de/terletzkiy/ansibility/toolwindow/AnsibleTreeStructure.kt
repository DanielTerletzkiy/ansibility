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
import de.terletzkiy.ansibility.toolwindow.model.NodeStyle
import de.terletzkiy.ansibility.toolwindow.model.TreeContext
import de.terletzkiy.ansibility.toolwindow.model.TreeView
import de.terletzkiy.ansibility.toolwindow.model.WorkspaceNode
import de.terletzkiy.ansibility.toolwindow.model.WorkspaceSnapshot

/**
 * The [AbstractTreeStructure] behind the tool window's `StructureTreeModel`. Elements are [AnsibleTreeNode]s over
 * the current [snapshot]; the model calls this class on its background invoker inside a read action, and children
 * are computed from the in-memory snapshot (plays of an expanded playbook come from [context]).
 */
class AnsibleTreeStructure(
    private val project: Project,
    private val context: TreeContext,
    private val view: TreeView = TreeView.REPOS,
) : AbstractTreeStructure() {
    /** The snapshot the tree shows; replaced on the EDT before `StructureTreeModel.invalidateAsync()`. */
    @Volatile
    var snapshot: WorkspaceSnapshot = WorkspaceSnapshot.EMPTY

    override fun getRootElement(): Any = WorkspaceNode(snapshot, view)

    override fun getChildElements(element: Any): Array<Any> {
        // The root element is equal across snapshots; always expand the current one.
        val node = if (element is WorkspaceNode) WorkspaceNode(snapshot, view) else element as? AnsibleTreeNode ?: return emptyArray()
        return node.children(context).toTypedArray()
    }

    override fun getParentElement(element: Any): Any? = (element as? AnsibleTreeNode)?.parent

    override fun createDescriptor(element: Any, parentDescriptor: NodeDescriptor<*>?): NodeDescriptor<*> =
        AnsibleNodeDescriptor(project, parentDescriptor, element as AnsibleTreeNode)

    override fun isAlwaysLeaf(element: Any): Boolean = (element as? AnsibleTreeNode)?.isLeaf == true

    /**
     * [LeafState.NEVER] for nodes whose children are loaded only when they are expanded ([AnsibleTreeNode.childrenOnDemand]:
     * a host's Effective vars and Targeted by compute that host's models). The tree then never loads their children to
     * find out whether they have any, so listing hosts computes none of them. Every other node is a leaf exactly when it
     * has no children ([LeafState.DEFAULT]).
     */
    override fun getLeafState(element: Any): LeafState = when {
        isAlwaysLeaf(element) -> LeafState.ALWAYS
        (element as? AnsibleTreeNode)?.childrenOnDemand == true -> LeafState.NEVER
        else -> LeafState.DEFAULT
    }

    override fun commit() = Unit

    override fun hasSomethingToCommit(): Boolean = false
}

/**
 * Renders one [AnsibleTreeNode]: icon, name (bold or struck through by [NodeStyle]), the badge, the grey extra text and
 * an HTML tooltip. Double-click expands only container nodes; nodes with a navigation target open it instead (plan
 * F6.3). [update] runs on the tree's background thread; [toString] (speed search) returns the name it computed, so the
 * EDT never computes a presentation.
 */
class AnsibleNodeDescriptor(
    project: Project,
    parent: NodeDescriptor<*>?,
    val node: AnsibleTreeNode,
) : PresentableNodeDescriptor<AnsibleTreeNode>(project, parent) {
    override fun getElement(): AnsibleTreeNode = node

    @Volatile
    private var name: String = ""

    override fun update(presentation: PresentationData) {
        val model = node.presentation()
        name = model.name
        presentation.setIcon(AnsibilityToolWindowIcons.of(model.icon))
        presentation.presentableText = model.name
        presentation.addText(model.name, nameAttributes(model.style))
        model.badge?.takeIf { it.isNotEmpty() }?.let { presentation.addText("  $it", SimpleTextAttributes.REGULAR_ATTRIBUTES) }
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

    override fun toString(): String = name

    private companion object {
        val STRUCK: SimpleTextAttributes = SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, null)

        fun nameAttributes(style: NodeStyle): SimpleTextAttributes = when (style) {
            NodeStyle.NORMAL -> SimpleTextAttributes.REGULAR_ATTRIBUTES
            NodeStyle.EMPHASIZED -> SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
            NodeStyle.STRUCK -> STRUCK
        }
    }
}
