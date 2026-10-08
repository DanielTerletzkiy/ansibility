package de.terletzkiy.ansibility.toolwindow

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.toolwindow.model.TreeView
import de.terletzkiy.ansibility.vault.monitor.SecretTab

/**
 * The "Ansibility" tool window (plan F6.1, `<toolWindow id="Ansibility" anchor="left">` in `ansibility-inventory.xml`).
 *
 * It is offered only in projects with at least one non-detached Ansible root ([isApplicableAsync]). The content is an
 * [AnsibleToolWindowPanel] per tree view, then the Vault tab (plan amendment R21, D165: `vault.monitor.SecretTab`,
 * whose snapshot waits for the indexes). Every model the tree views read (roots, inventories, role registry, play
 * graph) is built from the VFS and PSI without indexes, so the window is [DumbAware] and stays usable while indexing.
 * A red badge marks the window's icon while the Vault tab lists an error ([init]).
 */
class AnsibleToolWindowFactory : ToolWindowFactory, DumbAware {
    override suspend fun isApplicableAsync(project: Project): Boolean =
        readAction { AnsibleWorkspace.getInstance(project).roots().any { !it.detached } }

    /** Installs the Vault badge on the icon as soon as the window is registered, before its content is created. */
    override fun init(toolWindow: ToolWindow) {
        SecretTab.installBadge(toolWindow.project, toolWindow)
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        for ((view, title) in TABS) {
            val panel = AnsibleToolWindowPanel(project, view)
            val content = ContentFactory.getInstance().createContent(panel, AnsibilityToolWindowBundle.message(title), false)
            content.isCloseable = false
            content.setDisposer(panel)
            content.preferredFocusableComponent = panel.tree
            toolWindow.contentManager.addContent(content)
        }
        SecretTab.addTo(project, toolWindow)
    }

    companion object {
        /**
         * The tool window id. Its stripe title is `toolwindow.stripe.Ansibility` of the plugin's resource bundle
         * (`messages/AnsibilityBundle.properties`), which the platform looks up by id.
         */
        const val ID: String = "Ansibility"

        private val TABS = listOf(
            TreeView.REPOS to "toolwindow.tab.workspace",
            TreeView.ROLES to "toolwindow.tab.roles",
            TreeView.ENVIRONMENTS to "toolwindow.tab.environments",
        )
    }
}
