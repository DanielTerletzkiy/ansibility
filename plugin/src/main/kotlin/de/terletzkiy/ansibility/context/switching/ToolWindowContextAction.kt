package de.terletzkiy.ansibility.context.switching

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.toolwindow.model.AnsibleTreeNode
import de.terletzkiy.ansibility.toolwindow.model.RootNode
import java.awt.Component
import javax.swing.JComponent

/**
 * The context button of the Ansibility tool window, `[falcon · prod › prod-prod1 ▾]` (plan amendment R7/R8, F8.1): one
 * button rather than three combos, next to R9's scope selector, joining the toolbar through
 * `Ansibility.ToolWindow.Toolbar.Extra`. It opens the same popup as the status-bar widget for the root of the
 * selected tree node (else of the selected editor's file, else the first root with an inventory), plus "Use … as
 * Ansible Context" for a selected environment, group or host node.
 */
class ToolWindowContextAction : ComboBoxAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val root = project?.let {
            val (node, file) = e.updateSession.compute(this, "Ansible context root", ActionUpdateThread.EDT) {
                selectedNode(e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT)) to editorFile(it)
            }
            rootOf(it, node, file)
        }
        e.presentation.isEnabledAndVisible = root != null
        if (project == null || root == null) return
        e.presentation.setText(ContextTexts.buttonText(root, AnsibleContextService.getInstance(project).selection(root)), false)
        e.presentation.description = ContextTexts.message("toolwindow.context.description", root.displayName)
    }

    override fun shouldShowDisabledActions(): Boolean = true

    override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup {
        val project = CommonDataKeys.PROJECT.getData(dataContext) ?: return DefaultActionGroup()
        return popupGroup(project, button)
    }

    companion object {
        /** The id of the registered "Ansibility: Use as Ansible Context" action (`ansibility-host.xml`). */
        const val USE_AS_CONTEXT_ACTION_ID: String = "Ansibility.UseAsAnsibleContext"

        /**
         * The popup of the button in the tool window holding [component]: the context popup of [rootOf]'s root with
         * "Use … as Ansible Context" for the selected node. Call on the EDT.
         */
        fun popupGroup(project: Project, component: Component?): DefaultActionGroup {
            val file = editorFile(project)
            val root = rootOf(project, selectedNode(component), file) ?: return DefaultActionGroup()
            val extra = listOfNotNull(ActionManager.getInstance().getAction(USE_AS_CONTEXT_ACTION_ID))
            return DefaultActionGroup(ContextPopupGroup(root, file?.takeIf { root.contains(project, it) }, extra = extra))
        }

        /** The selected node of the tool window holding [component]; call on the EDT. */
        fun selectedNode(component: Component?): AnsibleTreeNode? =
            component?.let(UseAsAnsibleContextAction::toolWindowPanelOf)?.selectedNode()

        /** The selected editor's file; call on the EDT. */
        private fun editorFile(project: Project): VirtualFile? = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()

        /** The root the button shows: the root of [node], else of [file], else the first root with an inventory. */
        fun rootOf(project: Project, node: AnsibleTreeNode?, file: VirtualFile?): AnsibleRoot? = readLocked {
            generateSequence(node) { it.parent }.filterIsInstance<RootNode>().firstOrNull()?.root?.root
                ?: file?.let { AnsibleWorkspace.getInstance(project).rootFor(it) }?.takeIf { !it.detached }
                ?: AnsibleWorkspace.getInstance(project).roots().firstOrNull { !it.detached && it.environmentsDir != null }
        }

        private fun AnsibleRoot.contains(project: Project, file: VirtualFile): Boolean =
            readLocked { AnsibleWorkspace.getInstance(project).rootFor(file) } == this
    }
}
