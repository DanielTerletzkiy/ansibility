package de.terletzkiy.ansibility.run.notify

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import de.terletzkiy.ansibility.run.settings.RunNotificationSettings
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Records the notifications a test causes (in tests the platform publishes them at once on the project's bus) and,
 * until [disposable] goes, the system notifications of runs; puts the notifier and its settings back afterwards.
 */
class RecordedNotifications(private val project: Project, disposable: Disposable) {
    val all = CopyOnWriteArrayList<Notification>()
    val system = CopyOnWriteArrayList<Pair<String, String>>()
    private val notifier = RunNotifier.getInstance(project)

    init {
        project.messageBus.connect(disposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                all += notification
            }
        })
        notifier.systemForTests = { title, text -> system += title to text }
        Disposer.register(disposable) {
            notifier.resetForTests()
            RunNotificationSettings.getInstance().loadState(RunNotificationSettings.Options())
        }
    }

    /** The run notifications ("Ansibility runs"). */
    val runs: List<Notification> get() = all.filter { it.groupId == RunNotifier.GROUP_ID }

    /** The countdown balloons of R15 (group "Ansibility", title "Molecule instances"). */
    val countdowns: List<Notification> get() = all.filter { it.groupId == "Ansibility" && it.title == "Molecule instances" }

    /** Clicks the action [text] of [notification], as the balloon does (tests only: `Notification.fire` is internal API). */
    fun click(notification: Notification, text: String) {
        val action = notification.actions.firstOrNull { it.templateText == text } ?: error("no action $text in ${notification.actions.map { it.templateText }}")
        val context = SimpleDataContext.builder().add(Notification.KEY, notification).add(CommonDataKeys.PROJECT, project).build()
        Notification.fire(notification, action, context)
    }

    companion object {
        fun texts(notification: Notification): List<String> = notification.actions.map { it.templateText.orEmpty() }
    }
}
