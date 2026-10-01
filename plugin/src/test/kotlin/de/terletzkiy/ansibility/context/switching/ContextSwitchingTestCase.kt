package de.terletzkiy.ansibility.context.switching

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import de.terletzkiy.ansibility.context.AnsibilityCoreBundle
import de.terletzkiy.ansibility.context.host.HostContextTestCase
import de.terletzkiy.ansibility.context.host.PlayKeys
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.settings.AnsibilityWorkspaceState
import de.terletzkiy.ansibility.settings.SettingsTestSupport

/**
 * Base of the context-switching tests: the host-context fixture (falcon, platform, pelican with its danger zone, golden's
 * postfix role) plus helpers that drive the popup actions the way the platform does (update, then perform) and
 * inspect what a popup would list.
 */
abstract class ContextSwitchingTestCase : HostContextTestCase() {
    /** A data context with the project and, when given, [file]. */
    protected fun dataContext(file: VirtualFile? = null): DataContext =
        SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.VIRTUAL_FILE, file).build()

    protected fun event(action: AnAction, context: DataContext = dataContext()): AnActionEvent =
        TestActionEvent.createTestEvent(action, context)

    /** [action] updated as the platform would before showing it. */
    protected fun updated(action: AnAction, context: DataContext = dataContext()): AnActionEvent {
        val event = event(action, context)
        runReadActionBlocking { action.update(event) }
        return event
    }

    /** Performs [action] after an update, on the EDT, and drains the event queue. */
    protected fun perform(action: AnAction, context: DataContext = dataContext()) {
        val event = updated(action, context)
        assertTrue("${action.templateText} must be enabled", event.presentation.isEnabled)
        action.actionPerformed(event)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    /** The children of [group] (in a read action, as the background update computes them). */
    protected fun children(group: ActionGroup, context: DataContext = dataContext()): List<AnAction> =
        runReadActionBlocking { group.getChildren(event(group, context)).toList() }

    /** What a popup shows for [actions]: separators as `-- text`, actions as their updated text (`[x] ` when checked, `(grey)`). */
    protected fun texts(actions: List<AnAction>, context: DataContext = dataContext()): List<String> = actions.map { action ->
        when (action) {
            is Separator -> "-- ${action.text.orEmpty()}".trimEnd()
            else -> {
                val presentation = updated(action, context).presentation
                val checked = if (Toggleable.isSelected(presentation)) "[x] " else ""
                val grey = if (!presentation.isEnabled) " (grey)" else ""
                val secondary = presentation.getClientProperty(ActionUtil.SECONDARY_TEXT)?.let { " | $it" }.orEmpty()
                "$checked${presentation.text}$secondary$grey"
            }
        }
    }

    /** The child of [group] whose updated text is [text]. */
    protected fun child(group: ActionGroup, text: String, context: DataContext = dataContext()): AnAction {
        val children = children(group, context)
        return children.firstOrNull { it !is Separator && updated(it, context).presentation.text == text }
            ?: error("no '$text' in ${texts(children, context)}")
    }

    /**
     * The built-in status-bar text of [root] followed by [rest]: the prefix comes from the core bundle's `status.text`
     * (`Ansibility: falcon`), so the assertions follow the product's branding wherever that key is changed.
     */
    protected fun status(root: String, rest: String): String = AnsibilityCoreBundle.message("status.text", root) + rest

    /** The stored key of play [name] in [playbook]. */
    protected fun playKey(rootPath: String, playbook: String, name: String): String {
        val play = PlayGraph.getInstance(project).playsOf(vf(playbook)).single { it.ref.name == name }.ref
        return PlayKeys.of(root(rootPath), play)
    }

    /** The persisted workspace state as XML, and the state it loads back. */
    protected fun persistedXml(): String {
        val (xml, _) = SettingsTestSupport.xmlRoundTrip(AnsibilityWorkspaceState.getInstance(project).state, AnsibilityWorkspaceState.StateBean())
        return xml
    }

    /** Reloads the workspace state from its persisted XML, as after an IDE restart. */
    protected fun restart() {
        val (_, bean) = SettingsTestSupport.xmlRoundTrip(AnsibilityWorkspaceState.getInstance(project).state, AnsibilityWorkspaceState.StateBean())
        AnsibilityWorkspaceState.getInstance(project).loadState(AnsibilityWorkspaceState.StateBean())
        AnsibilityWorkspaceState.getInstance(project).loadState(bean)
    }

    protected val prod1Vars: String = "repos/falcon/ansible/environments/prod/host_vars/prod-prod1/vars.yml"
    protected val falconProdHosts: String = "repos/falcon/ansible/environments/prod/hosts.yml"
    protected val postfixTasks: String = "repos/falcon/ansible/roles/postfix/tasks/main.yml"
    protected val postfixTemplate: String = "repos/falcon/ansible/roles/postfix/templates/main.cf.j2"
    protected val systemPlaybook: String = "repos/falcon/ansible/playbook-setup-system.yml"
    protected val cloneToReplisync: String = "repos/pelican/ansible/danger_zone/database/playbook-clone-to-replisync.yml"
}
