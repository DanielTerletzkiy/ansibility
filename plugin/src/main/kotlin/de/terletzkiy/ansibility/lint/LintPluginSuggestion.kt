package de.terletzkiy.ansibility.lint

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import de.terletzkiy.ansibility.coexist.CoexistenceSettings
import de.terletzkiy.ansibility.coexist.ConflictDetector
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Once Ansibility has linted a file (plan amendment R28), suggests disabling the "Ansible Lint" plugin while it is
 * loaded, so findings are not doubled: [Open Plugins Settings] [Keep Both]. Asked once per IDE session until answered;
 * the answer is remembered per user. We never disable it ourselves (that API is internal), and nothing is shown while
 * the coexistence notifications are off.
 */
object LintPluginSuggestion {
    const val PLUGIN_ID: String = "de.achimonline.ansible_lint"
    const val ANSWER_KEY: String = "ansibility.coexist.ansibleLint.answer"

    private val shownThisSession = AtomicBoolean()

    fun suggestOnce(project: Project) {
        val properties = PropertiesComponent.getInstance()
        if (!CoexistenceSettings.getInstance().conflictNotifications() || properties.getValue(ANSWER_KEY) != null) return
        if (!PluginManagerCore.isLoaded(PluginId.getId(PLUGIN_ID)) || !shownThisSession.compareAndSet(false, true)) return
        NotificationGroupManager.getInstance().getNotificationGroup(LintService.NOTIFICATION_GROUP)
            .createNotification(message("notification.disable.other.title"), message("notification.disable.other.text"), NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring(message("notification.disable.other.action")) {
                properties.setValue(ANSWER_KEY, "settings")
                ConflictDetector.openPluginsSettings(project)
            })
            .addAction(NotificationAction.createSimpleExpiring(message("notification.disable.other.keep")) {
                properties.setValue(ANSWER_KEY, "keep-both")
            })
            .notify(project)
    }
}
