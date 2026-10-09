package de.terletzkiy.ansibility.toolwindow

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.context.switching.UseAsAnsibleContextAction
import de.terletzkiy.ansibility.model.drift.AnsibilityDriftBundle
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.GoldenRoot
import de.terletzkiy.ansibility.settings.RootKeys
import de.terletzkiy.ansibility.toolwindow.model.RootNode

/**
 * "Ansibility: Use as Golden Root" on a root row of the tool window (plan amendment R24, D177): makes that root the
 * golden root of role drift (`GoldenRoot.Root` with the root's key), as Settings › Ansibility › Role drift does. Hidden
 * on other rows and on the root that already is the chosen golden root; detached worktrees have no root rows.
 */
class UseAsGoldenRootAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val root = rootOf(e)
        val offered = project != null && root != null && !isGolden(project, root)
        e.presentation.isEnabledAndVisible = offered
        if (offered) e.presentation.setText(AnsibilityDriftBundle.message("drift.action.use.text", root.displayName), false)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        rootOf(e)?.let { use(project, it) }
    }

    private fun rootOf(e: AnActionEvent): AnsibleRoot? {
        val component = e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT) ?: return null
        val node = e.updateSession.compute(this, "Ansibility root row", ActionUpdateThread.EDT) {
            UseAsAnsibleContextAction.toolWindowPanelOf(component)?.selectedNode()
        }
        return (node as? RootNode)?.root?.root?.takeIf { !it.detached }
    }

    companion object {
        /** Whether [root] is the chosen golden root already. */
        fun isGolden(project: Project, root: AnsibleRoot): Boolean =
            AnsibilityProjectSettings.getInstance(project).settings.drift.golden == GoldenRoot.Root(RootKeys.keyOf(project, root.dir))

        /** Makes [root] the golden root (stored by its key, like every per-root setting). */
        fun use(project: Project, root: AnsibleRoot) {
            if (root.detached) return
            val key = RootKeys.keyOf(project, root.dir)
            AnsibilityProjectSettings.getInstance(project).update { it.copy(drift = it.drift.copy(golden = GoldenRoot.Root(key))) }
        }
    }
}
