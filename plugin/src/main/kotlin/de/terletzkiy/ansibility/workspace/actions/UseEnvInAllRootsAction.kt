package de.terletzkiy.ansibility.workspace.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import de.terletzkiy.ansibility.api.WorkspaceScopeService
import de.terletzkiy.ansibility.context.switching.ContextSwitcher
import de.terletzkiy.ansibility.context.switching.ContextTarget
import de.terletzkiy.ansibility.context.switching.UseAsAnsibleContextAction
import de.terletzkiy.ansibility.toolwindow.model.EnvironmentNameNode
import de.terletzkiy.ansibility.workspace.AnsibilityScopeBundle.message

/**
 * "Use prod as Ansible context in all 8 roots of scope" on an environment row of the Environments tab (plan amendment
 * R9, WS9): writes the environment as each in-scope root's context. Hosts are never shared: same-named hosts in two
 * repos are different machines.
 */
class UseEnvInAllRootsAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val targets = project?.let { targetsOf(e, it) }.orEmpty()
        e.presentation.isEnabledAndVisible = targets.isNotEmpty()
        if (targets.isNotEmpty()) e.presentation.setText(message("use.env.all.text", targets.first().environment.orEmpty(), targets.size), false)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        targetsOf(e, project).forEach { ContextSwitcher.use(project, it) }
    }

    private fun targetsOf(e: AnActionEvent, project: Project): List<ContextTarget> {
        val component = e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT) ?: return emptyList()
        val node = e.updateSession.compute(this, "Ansibility environment row", ActionUpdateThread.EDT) {
            UseAsAnsibleContextAction.toolWindowPanelOf(component)?.selectedNode()
        } as? EnvironmentNameNode ?: return emptyList()
        return targets(project, node)
    }

    companion object {
        /** One target per root of [node] that the workspace scope covers. */
        fun targets(project: Project, node: EnvironmentNameNode): List<ContextTarget> {
            val inScope = WorkspaceScopeService.getInstance(project).current().roots.toSet()
            return node.environments.map { it.root.root }.filter { it in inScope }.map { ContextTarget(it, node.name) }
        }
    }
}
