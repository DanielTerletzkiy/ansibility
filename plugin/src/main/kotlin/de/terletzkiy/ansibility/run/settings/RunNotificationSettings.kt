package de.terletzkiy.ansibility.run.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * When a run's end is told in a notification (plan amendment R19, D147): personal and the same for every root and
 * project, so stored at application level in `ansibility.xml`. Edited in the "Notifications (all roots)" group of
 * Settings › Ansibility › Runner.
 */
@Service(Service.Level.APP)
@State(name = "AnsibilityRunNotifications", storages = [Storage("ansibility.xml")], category = SettingsCategory.PLUGINS)
class RunNotificationSettings : SimplePersistentStateComponent<RunNotificationSettings.Options>(Options()) {
    /** When a notification shows: unless the user watches the run's tab (the default), always, or never. */
    enum class When { NOT_IN_VIEW, ALWAYS, NEVER }

    class Options : BaseState() {
        var whenToNotify by enum(When.NOT_IN_VIEW)

        /** Also for runs that passed (failed, stopped and not started runs always notify unless [whenToNotify] is never). */
        var notifyPassed by property(true)
    }

    var whenToNotify: When
        get() = state.whenToNotify
        set(value) {
            state.whenToNotify = value
        }

    var notifyPassed: Boolean
        get() = state.notifyPassed
        set(value) {
            state.notifyPassed = value
        }

    companion object {
        fun getInstance(): RunNotificationSettings = service()
    }
}
