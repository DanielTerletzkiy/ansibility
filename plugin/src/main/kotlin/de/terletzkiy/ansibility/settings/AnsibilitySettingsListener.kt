package de.terletzkiy.ansibility.settings

import com.intellij.util.messages.Topic

/**
 * Notified on the application message bus after [AnsibilityAppSettings] changed (through the settings page,
 * [AnsibilityAppSettings.update] or a settings-sync reload). Called on the thread that made the change, usually the
 * EDT; implementations only schedule their own refresh.
 *
 * The topic broadcasts to direct children, so a listener connected to a project's message bus (and disposed with
 * the project) receives it too.
 */
fun interface AnsibilityAppSettingsListener {
    fun appSettingsChanged(old: AppSettings, new: AppSettings)

    companion object {
        @Topic.AppLevel
        val TOPIC: Topic<AnsibilityAppSettingsListener> =
            Topic(AnsibilityAppSettingsListener::class.java, Topic.BroadcastDirection.TO_DIRECT_CHILDREN)
    }
}

/**
 * Notified on the project message bus after the project's Ansibility settings or workspace state changed. Called on
 * the thread that made the change, usually the EDT; implementations only schedule their own refresh and must not
 * block. The snapshots are immutable; there is no need to read the services again.
 */
interface AnsibilitySettingsListener {
    /** [AnsibilityProjectSettings] changed: per-root strictness or runtime overrides, or the path rules. */
    fun projectSettingsChanged(old: ProjectSettings, new: ProjectSettings) {}

    /** [AnsibilityWorkspaceState] changed: the active environment, host or play of some root. */
    fun workspaceStateChanged(old: WorkspaceState, new: WorkspaceState) {}

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<AnsibilitySettingsListener> =
            Topic(AnsibilitySettingsListener::class.java, Topic.BroadcastDirection.NONE)
    }
}
