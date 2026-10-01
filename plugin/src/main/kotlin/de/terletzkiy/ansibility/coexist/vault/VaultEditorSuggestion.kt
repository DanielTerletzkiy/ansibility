package de.terletzkiy.ansibility.coexist.vault

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.text.HtmlChunk
import de.terletzkiy.ansibility.coexist.CoexistenceSettings
import de.terletzkiy.ansibility.coexist.ConflictDetector
import de.terletzkiy.ansibility.context.AnsibleOnboarding
import de.terletzkiy.ansibility.inspections.vault.AnsibilityVaultChecksBundle
import java.util.concurrent.atomic.AtomicBoolean

/**
 * F7.12 item 3: suggests disabling Ansible Vault Editor, once, while it is loaded: "Ansibility now includes vault
 * support with vault ids, folding and inventory awareness. Ansible Vault Editor overlaps (gutter icons, intentions)."
 * with [Open Plugins Settings] [Keep both] [Don't show again].
 *
 * The suggestion appears at most once per IDE session, on the first project opened, until it is answered; any answer
 * is remembered per user (application [PropertiesComponent] key [ANSWER_KEY]) and ends it. Nothing is shown while the
 * X03 setting [CoexistenceSettings.conflictNotifications] is off. We never disable Vault Editor and never change its
 * settings (`disablePlugin` is internal API anyway).
 */
object VaultEditorSuggestion {
    /** The remembered answer: one of [ANSWER_SETTINGS], [ANSWER_KEEP_BOTH], [ANSWER_NEVER]. */
    const val ANSWER_KEY: String = "ansibility.coexist.vaultEditor.answer"

    const val ANSWER_SETTINGS: String = "settings"
    const val ANSWER_KEEP_BOTH: String = "keep-both"
    const val ANSWER_NEVER: String = "never"

    private val shownThisSession = AtomicBoolean()

    /**
     * Shows the suggestion when Vault Editor is [loaded], the X03 setting allows it, it has not been answered and it
     * was not shown in this IDE session. Returns the notification shown, or null.
     */
    fun suggestOnce(
        project: Project,
        loaded: () -> Boolean = { VaultEditorCoexistence.getInstance().isVaultEditorLoaded() },
        settings: CoexistenceSettings = CoexistenceSettings.getInstance(),
        properties: PropertiesComponent = PropertiesComponent.getInstance(),
        session: AtomicBoolean = shownThisSession,
    ): Notification? {
        if (!settings.conflictNotifications() || properties.getValue(ANSWER_KEY) != null || !loaded()) return null
        if (!session.compareAndSet(false, true)) return null
        val notification = createNotification(project, properties)
        notification.notify(project)
        return notification
    }

    /** The suggestion with its three answers; each one is remembered in [properties] and expires the notification. */
    fun createNotification(project: Project, properties: PropertiesComponent = PropertiesComponent.getInstance()): Notification {
        val content = HtmlChunk.text(AnsibilityVaultChecksBundle.message("coexist.suggestion.content")).toString()
        return NotificationGroupManager.getInstance()
            .getNotificationGroup(AnsibleOnboarding.NOTIFICATION_GROUP)
            .createNotification(AnsibilityVaultChecksBundle.message("coexist.suggestion.title"), content, NotificationType.INFORMATION)
            .addAction(
                NotificationAction.createSimpleExpiring(AnsibilityVaultChecksBundle.message("coexist.suggestion.open.plugins")) {
                    properties.setValue(ANSWER_KEY, ANSWER_SETTINGS)
                    ConflictDetector.openPluginsSettings(project)
                },
            )
            .addAction(
                NotificationAction.createSimpleExpiring(AnsibilityVaultChecksBundle.message("coexist.suggestion.keep.both")) {
                    properties.setValue(ANSWER_KEY, ANSWER_KEEP_BOTH)
                },
            )
            .addAction(
                NotificationAction.createSimpleExpiring(AnsibilityVaultChecksBundle.message("coexist.suggestion.never")) {
                    properties.setValue(ANSWER_KEY, ANSWER_NEVER)
                },
            )
    }
}

/** Runs [VaultEditorSuggestion] after a project opened; registered in `ansibility-vault.xml`, so only with Vault Editor. */
class VaultEditorSuggestionActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        VaultEditorSuggestion.suggestOnce(project)
    }
}
