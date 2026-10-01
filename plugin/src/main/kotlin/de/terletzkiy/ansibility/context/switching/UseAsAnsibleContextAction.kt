package de.terletzkiy.ansibility.context.switching

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ComponentUtil
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.HostScopeOrigin
import de.terletzkiy.ansibility.toolwindow.AnsibleToolWindowPanel
import de.terletzkiy.ansibility.toolwindow.model.AnsibleTreeNode
import de.terletzkiy.ansibility.toolwindow.model.EnvironmentNode
import de.terletzkiy.ansibility.toolwindow.model.GroupNode
import de.terletzkiy.ansibility.toolwindow.model.HostNode
import java.awt.Component

/**
 * "Ansibility: Use as Ansible Context" (plan amendment R7/R8, F8.1): makes an environment or host the Ansible context of its root.
 *
 * - **Tool window:** on an environment node (its environment), a group node (the group's environment; the context has
 *   no group dimension) and a host node (its environment and host). The node comes from the tool window that holds
 *   the focused component, so the action works from the tree's popup menu, the toolbar and Find Action alike.
 * - **Inventory files** (editor popup menu): files below `host_vars/<host>` use their host,
 *   `environments/<env>/group_vars` files their environment, and in `hosts.yml` the caret decides (a host entry its
 *   host, a group block or elsewhere the environment). Playbook-level `group_vars` apply to every environment and
 *   offer nothing.
 *
 * The text names the target (`Ansibility: Use prod › prod-prod1 as Ansible Context`); the action is hidden where it has none.
 * The play is kept while it still hits the new host or environment.
 */
class UseAsAnsibleContextAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val target = targetOf(e)
        e.presentation.isEnabledAndVisible = target != null
        if (target != null) e.presentation.setText(ContextTexts.message("use.as.text", target.label), false)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        targetOf(e)?.let { ContextSwitcher.use(project, it) }
    }

    private fun targetOf(e: AnActionEvent): ContextTarget? {
        val project = e.project ?: return null
        val component = e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT)
        val panel = component?.let { onEdt(e) { toolWindowPanelOf(it) } }
        if (panel != null) return onEdt(e) { panel.selectedNode() }?.let(::targetOf)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        val offset = e.getData(CommonDataKeys.EDITOR)?.takeIf { it.virtualFile == file }?.caretModel?.offset ?: -1
        return fileTarget(project, file, offset)
    }

    /** Swing state is read on the EDT, also from the background update ([com.intellij.openapi.actionSystem.UpdateSession.compute]). */
    private fun <T> onEdt(e: AnActionEvent, compute: () -> T): T =
        e.updateSession.compute(this, "Ansibility tool window selection", ActionUpdateThread.EDT, compute)

    companion object {
        /** The Ansibility tool window panel that holds [component], or null. */
        fun toolWindowPanelOf(component: Component): AnsibleToolWindowPanel? =
            component as? AnsibleToolWindowPanel ?: ComponentUtil.getParentOfType(AnsibleToolWindowPanel::class.java, component)

        /** The context an environment, group or host node stands for, or null for other nodes. */
        fun targetOf(node: AnsibleTreeNode): ContextTarget? = when (node) {
            is EnvironmentNode -> ContextTarget(node.env.root.root, node.env.name)
            is GroupNode -> ContextTarget(node.env.root.root, node.env.name)
            is HostNode -> ContextTarget(node.env.root.root, node.env.name, node.host.name)
            else -> null
        }

        /**
         * The context an inventory file stands for at [offset] (-1: the file as a whole), or null for other files,
         * outside every root and while indexing. Needs a read lock (it takes one when the caller holds none).
         */
        fun fileTarget(project: Project, file: VirtualFile, offset: Int): ContextTarget? = readLocked {
            if (DumbService.isDumb(project)) return@readLocked null
            val context = AnsibleWorkspace.getInstance(project).contextOf(file) ?: return@readLocked null
            if (context.kind !in INVENTORY_KINDS) return@readLocked null
            val scope = AnsibleContextService.getInstance(project).hostScope(file, offset)
            when (val origin = scope.origin) {
                is HostScopeOrigin.HostVars -> ContextTarget(scope.root, origin.host.environment, origin.host.host)
                is HostScopeOrigin.GroupVars -> origin.environment?.let { ContextTarget(scope.root, it) }
                is HostScopeOrigin.InventoryEntry -> ContextTarget(scope.root, origin.environment, origin.host)
                else -> null
            }
        }

        private val INVENTORY_KINDS = setOf(FileKind.INVENTORY, FileKind.GROUP_VARS, FileKind.HOST_VARS)
    }
}
