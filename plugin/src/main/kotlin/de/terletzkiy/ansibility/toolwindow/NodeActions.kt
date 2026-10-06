package de.terletzkiy.ansibility.toolwindow

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.context.switching.UseAsAnsibleContextAction
import de.terletzkiy.ansibility.toolwindow.model.AnsibleTreeNode
import java.awt.datatransfer.StringSelection

/** The selected node of the Ansibility tool window that holds the event's component, read on the EDT. */
private fun AnActionEvent.selectedNode(action: DumbAwareAction): AnsibleTreeNode? {
    val component = getData(PlatformCoreDataKeys.CONTEXT_COMPONENT) ?: return null
    return updateSession.compute(action, "Ansibility tool window selection", ActionUpdateThread.EDT) {
        UseAsAnsibleContextAction.toolWindowPanelOf(component)?.selectedNode()
    }
}

/** "Copy Name" on a tool-window node (plan X82): the row's name, never a value. */
class CopyNodeNameAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.selectedNode(this) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val node = e.selectedNode(this) ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(node.presentation().name))
    }
}

/** "Show in Project View" on a tool-window node with a file or directory (plan X82). */
class ShowNodeInProjectAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && fileOf(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = fileOf(e) ?: return
        ProjectView.getInstance(project).select(null, file, true)
    }

    private fun fileOf(e: AnActionEvent): VirtualFile? = e.selectedNode(this)?.target?.file?.takeIf { it.isValid }
}
