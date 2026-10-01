package de.terletzkiy.ansibility.settings

import com.intellij.openapi.project.Project
import de.terletzkiy.ansibility.coexist.CoexistenceSettings

/**
 * The [CoexistenceSettings] of the plugin, read from the persistent settings on every call:
 * - X85 "Hide other Ansible plugins' completions" and X03 conflict notifications from [AnsibilityAppSettings]
 *   ([AppSettings.coexistence]; off and on by default);
 * - the SchemaStore task/playbook exclusion from the project's [AnsibilityProjectSettings] ([PathSettings.schemaStoreExclusion],
 *   on by default). A disposed project answers false, so a closing project never excludes anything.
 *
 * Registered as the only implementation of the application service in `ansibility-settings.xml`.
 */
class AnsibilityCoexistenceSettings : CoexistenceSettings {
    override fun hideOtherAnsibleCompletions(): Boolean = AnsibilityAppSettings.getInstance().settings.coexistence.hideOtherAnsibleCompletions

    override fun schemaStoreExclusion(project: Project): Boolean =
        !project.isDisposed && AnsibilityProjectSettings.getInstance(project).settings.paths.schemaStoreExclusion

    override fun conflictNotifications(): Boolean = AnsibilityAppSettings.getInstance().settings.coexistence.conflictNotifications
}
