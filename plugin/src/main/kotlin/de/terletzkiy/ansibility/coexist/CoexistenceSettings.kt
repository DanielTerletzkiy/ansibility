package de.terletzkiy.ansibility.coexist

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * The coexistence switches that `dispatch` and `coexist` read (plan, Coexistence & settings):
 * - X85 "Hide other Ansible plugins' completions" (application level, off: foreign items are only de-duplicated);
 * - "SchemaStore task/playbook exclusion" (project level, on);
 * - X03 conflict notifications (application level, on).
 *
 * Application service with exactly one implementation, `settings.AnsibilityCoexistenceSettings`, which reads the
 * persistent Ansibility settings. Callers must not cache the answers, so toggles apply immediately.
 */
interface CoexistenceSettings {
    /** When true, completion in Ansible files stops after our items instead of passing de-duplicated foreign items through. */
    fun hideOtherAnsibleCompletions(): Boolean

    /** When true, SchemaStore catalog schemas are not applied to task, handler and playbook files inside Ansible roots of [project]. */
    fun schemaStoreExclusion(project: Project): Boolean

    /**
     * When true, the one-time X03 notification about conflicting Ansible plugins may be shown. Implementations that do
     * not override it keep the notification on, the default of the setting.
     */
    fun conflictNotifications(): Boolean = true

    companion object {
        fun getInstance(): CoexistenceSettings = ApplicationManager.getApplication().service()
    }
}
