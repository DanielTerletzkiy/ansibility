package de.terletzkiy.ansibility.coexist

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.text.HtmlChunk
import de.terletzkiy.ansibility.context.AnsibilityCoreBundle
import de.terletzkiy.ansibility.context.AnsibleOnboarding

/** An installed plugin whose Ansible features overlap ours (plan, Coexistence & settings). */
data class ConflictingPlugin(val id: String, val name: String)

/**
 * X03: detects enabled Ansible plugins whose completion, documentation, navigation, file types or Jinja support
 * duplicate ours, and suggests disabling them once per plugin (application-wide) with a button that opens
 * Settings › Plugins. Nothing is shown while [CoexistenceSettings.conflictNotifications] is off.
 *
 * Complementary plugins are deliberately absent: `ru.sadv1r.ansible-vault-editor-idea-plugin` (we integrate with
 * it), `de.achimonline.ansible_lint` (left alone) and the vault-only plugins.
 */
object ConflictDetector {
    /** Marketplace ids verified against plugins.jetbrains.com (2026-09). */
    val KNOWN: List<ConflictingPlugin> = listOf(
        ConflictingPlugin("ir.msdehghan.plugins.ansible", "Ansible (msdehghan)"),
        ConflictingPlugin("YAML/Ansible support", "YAML/Ansible support"),
        ConflictingPlugin("com.taff.plugin.orchide", "OrchidE - Ansible Language Support"),
        ConflictingPlugin("dev.meanmail.plugin.ansible", "Ansible Pro"),
        ConflictingPlugin("dev.vetro.ansible", "Ansible Sense"),
        ConflictingPlugin("com.github.valbendan.ansible", "Typed Ansible"),
        ConflictingPlugin("me.vennen.ansible-lsp", "Ansible LSP"),
        ConflictingPlugin("dev.yamlix.ansible", "Yamlix for Ansible"),
        ConflictingPlugin("net.sjrx.intellij.plugins.ansiblesupport", "Ansible Support"),
        ConflictingPlugin("com.vorih.jinja2plugin", "Jinja2 (for Ansible)"),
        ConflictingPlugin("com.callsiq.playbooknavigator", "Playbook Navigator"),
    )

    /** Application-level [PropertiesComponent] list of plugin ids already reported. */
    const val NOTIFIED_KEY: String = "ansibility.coexist.notified"

    private const val PLUGINS_CONFIGURABLE_ID = "preferences.pluginManager"

    /**
     * The startup check: notifies about new conflicts ([notifyIfNeeded]) unless the X03 setting is off, in which case
     * nothing is shown or remembered. Returns the notification shown, or null.
     */
    fun notifyOnStartup(
        project: Project,
        settings: CoexistenceSettings = CoexistenceSettings.getInstance(),
        conflicts: () -> List<ConflictingPlugin> = { enabledConflicts() },
        properties: PropertiesComponent = PropertiesComponent.getInstance(),
    ): Notification? {
        if (!settings.conflictNotifications()) return null
        return notifyIfNeeded(project, conflicts(), properties)
    }

    /** The [KNOWN] plugins that [isEnabled] reports as loaded. */
    fun enabledConflicts(isEnabled: (String) -> Boolean = ::isLoaded): List<ConflictingPlugin> = KNOWN.filter { isEnabled(it.id) }

    /**
     * Notifies about enabled conflicting plugins not reported before, and remembers them. Returns the
     * notification shown, or null when there was nothing new.
     */
    fun notifyIfNeeded(
        project: Project,
        conflicts: List<ConflictingPlugin> = enabledConflicts(),
        properties: PropertiesComponent = PropertiesComponent.getInstance(),
    ): Notification? {
        val notified = properties.getList(NOTIFIED_KEY).orEmpty().toSet()
        val fresh = conflicts.filter { it.id !in notified }
        if (fresh.isEmpty()) return null
        properties.setList(NOTIFIED_KEY, (notified + fresh.map { it.id }).sorted())
        val notification = createNotification(project, fresh)
        notification.notify(project)
        return notification
    }

    /** The one-time suggestion for [conflicts]; public for tests. */
    fun createNotification(project: Project, conflicts: List<ConflictingPlugin>): Notification {
        val names = conflicts.joinToString(", ") { it.name }
        val content = HtmlChunk.text(AnsibilityCoreBundle.message("coexist.content", names, conflicts.size)).toString()
        return NotificationGroupManager.getInstance()
            .getNotificationGroup(AnsibleOnboarding.NOTIFICATION_GROUP)
            .createNotification(AnsibilityCoreBundle.message("coexist.title", conflicts.size), content, NotificationType.WARNING)
            .addAction(
                NotificationAction.createSimpleExpiring(AnsibilityCoreBundle.message("coexist.action.open.plugins")) {
                    openPluginsSettings(project)
                },
            )
    }

    /** Opens Settings › Plugins. The configurable class is internal API, so it is selected by its public id. */
    fun openPluginsSettings(project: Project) {
        ShowSettingsUtil.getInstance().showSettingsDialog(
            project,
            { configurable -> (configurable as? SearchableConfigurable)?.id == PLUGINS_CONFIGURABLE_ID },
            null,
        )
    }

    private fun isLoaded(id: String): Boolean = PluginManagerCore.isLoaded(PluginId.getId(id))
}

/** Runs [ConflictDetector] after the project opened (`postStartupActivity`); returns early when X03 notifications are off. */
class ConflictDetectorActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        ConflictDetector.notifyOnStartup(project)
    }
}
