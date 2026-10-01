package de.terletzkiy.ansibility.context.switching

import com.intellij.openapi.project.Project
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.settings.AnsibilityWorkspaceState
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext

/**
 * The one way the context UI changes the Ansible context (plan amendment R7/R8, F8.1). Every switch goes through
 * [AnsibleContextService.setSelection], which stores it per root in the per-user workspace state and bumps only the
 * selection tracker; presentation follows from there (`HostModeRestarts`, the widget, the banners). Nothing here
 * restarts the daemon, so inspections never re-run because of a switch (D32).
 */
object ContextSwitcher {
    /** Stores [context] as [root]'s selection (the service applies the selection rules). */
    fun select(project: Project, root: AnsibleRoot, context: RootContext) {
        AnsibleContextService.getInstance(project).setSelection(root, context)
    }

    /** Chooses [environment] (null: All) for [root], keeping the play while it still hits the environment. */
    fun selectEnvironment(project: Project, root: AnsibleRoot, environment: String?) {
        val current = AnsibleContextService.getInstance(project).selection(root)
        select(project, root, ContextChoices(project).withEnvironment(root, current, environment))
    }

    /** Chooses [host] of [environment] for [root], keeping the play while it still hits the host. */
    fun selectHost(project: Project, root: AnsibleRoot, environment: String, host: String) {
        val current = AnsibleContextService.getInstance(project).selection(root)
        select(project, root, ContextChoices(project).withHost(root, current, environment, host))
    }

    /** Chooses the play [key] (null: Auto) for [root]; env and host stay. */
    fun selectPlay(project: Project, root: AnsibleRoot, key: String?) {
        val current = AnsibleContextService.getInstance(project).selection(root)
        select(project, root, ContextChoices(project).withPlay(current, key))
    }

    /** Applies [target] ("Use as Ansible context", the banner's "Make … the Ansible context"). */
    fun use(project: Project, target: ContextTarget) {
        if (target.host != null) selectHost(project, target.root, target.environment ?: return, target.host)
        else selectEnvironment(project, target.root, target.environment)
    }

    /** Whether the context follows the selected editor's file (D33: on by default, narrowing only). */
    fun followsEditor(project: Project): Boolean = AnsibilityWorkspaceState.getInstance(project).snapshot.followEditor

    /** Turns Follow editor on or off for the whole project; the stored selections are unchanged. */
    fun setFollowEditor(project: Project, follow: Boolean) {
        AnsibilityWorkspaceState.getInstance(project).update { it.copy(followEditor = follow) }
    }
}

/**
 * An environment or host of [root] that can become its Ansible context: a tool-window node, an inventory file, the
 * banner's file scope. [environment] null with [host] null means All environments.
 */
data class ContextTarget(val root: AnsibleRoot, val environment: String?, val host: String? = null) {
    /** `prod › prod-prod1`, `prod`, `All environments`. */
    val label: String
        get() = when {
            environment == null -> ContextTexts.message("selection.all.environments")
            host == null -> environment
            else -> "$environment${ContextTexts.CHAIN}$host"
        }

    /** Whether [selection] already is this target. */
    fun isSelected(selection: RootContext): Boolean {
        val selected = (selection.environment as? EnvironmentChoice.Named)?.name
        return selected == environment && selection.host == host
    }
}
