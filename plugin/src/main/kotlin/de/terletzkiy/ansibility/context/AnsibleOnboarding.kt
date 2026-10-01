package de.terletzkiy.ansibility.context

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.text.HtmlChunk

/**
 * X01 onboarding: once per project, a balloon summarising the detected roots
 * (`8 project roots · golden · 2 danger-zone · 1 detached worktree`). When detached worktrees exist it offers
 * "Exclude worktree from project", which runs [WorktreeExcluder] only on click.
 */
object AnsibleOnboarding {
    /** Project-level [PropertiesComponent] flag set once the balloon was shown. */
    const val SHOWN_KEY: String = "ansibility.onboarding.shown"
    const val NOTIFICATION_GROUP: String = "Ansibility"

    /** The current roots of [project] as a summary, computed in a background read action. */
    suspend fun summaryOf(project: Project): RootsSummary? {
        val workspace = AnsibleWorkspaceImpl.getInstance(project) ?: return null
        return readAction { RootsSummary.of(workspace.roots(), workspace.detachedWorktrees()) }
    }

    /**
     * Shows the balloon for [summary] unless it was shown before or the project has no Ansible roots (then it
     * stays pending until roots appear on a later start). Returns the notification shown, or null.
     */
    fun notifyIfNeeded(project: Project, summary: RootsSummary): Notification? {
        val properties = PropertiesComponent.getInstance(project)
        if (properties.isTrueValue(SHOWN_KEY) || summary.isEmpty) return null
        val notification = createNotification(project, summary)
        properties.setValue(SHOWN_KEY, true)
        notification.notify(project)
        return notification
    }

    /** The balloon for [summary]; public for tests. */
    fun createNotification(project: Project, summary: RootsSummary): Notification {
        val content = buildString {
            append(HtmlChunk.text(summary.text()))
            if (summary.detachedWorktrees.isNotEmpty()) {
                append(HtmlChunk.br())
                append(HtmlChunk.text(AnsibilityCoreBundle.message("onboarding.worktree.explanation", summary.detachedWorktrees.size)))
            }
        }
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(AnsibilityCoreBundle.message("onboarding.title"), content, NotificationType.INFORMATION)
        if (summary.detachedWorktrees.isNotEmpty()) {
            notification.addAction(
                NotificationAction.createSimpleExpiring(
                    AnsibilityCoreBundle.message("onboarding.action.exclude", summary.detachedWorktrees.size),
                ) { excludeAll(project, summary.detachedWorktrees) },
            )
        }
        return notification
    }

    private fun excludeAll(project: Project, worktrees: List<DetachedWorktree>) {
        val excluded = worktrees.filter { it.dir.isValid && WorktreeExcluder.exclude(project, it.dir) }
        val (key, type) = if (excluded.size == worktrees.size) {
            "onboarding.excluded" to NotificationType.INFORMATION
        } else {
            "onboarding.exclude.failed" to NotificationType.WARNING
        }
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(AnsibilityCoreBundle.message(key, excluded.joinToString(", ") { it.name }, excluded.size), type)
            .notify(project)
    }
}

/** Runs [AnsibleOnboarding] after the project opened (`postStartupActivity`). */
class AnsibleOnboardingActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        if (PropertiesComponent.getInstance(project).isTrueValue(AnsibleOnboarding.SHOWN_KEY)) return
        val summary = AnsibleOnboarding.summaryOf(project) ?: return
        AnsibleOnboarding.notifyIfNeeded(project, summary)
    }
}
