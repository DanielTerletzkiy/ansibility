package de.terletzkiy.ansibility.vault.monitor

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The monitoring in tests (plan amendment R21, D164–D166): starts [SecretHealthService] with a short debounce (the
 * activity does not start it in unit-test mode, and a light project outlives a test) and puts everything back when
 * [disposable] goes: the service stopped, the notifier and the application-level memory forgotten.
 */
class Monitor(private val project: Project, disposable: Disposable) {
    val service: SecretHealthService = SecretHealthService.getInstance(project)
    val notifier: SecretNotifier = SecretNotifier.getInstance(project)

    init {
        service.stopForTests()
        notifier.resetForTests()
        SecretNotificationMemory.getInstance().resetForTests()
        service.debounceMillis = 10
        service.readyTimeoutMillis = 5_000
        Disposer.register(disposable) {
            service.stopForTests()
            service.debounceMillis = SecretHealthService.DEBOUNCE_MS
            service.readyTimeoutMillis = SecretHealthService.READY_TIMEOUT_MS
            notifier.resetForTests()
            SecretNotificationMemory.getInstance().resetForTests()
        }
    }

    fun start(): Monitor = also { service.start() }

    /** Waits until a snapshot newer than the current one satisfies [condition] (events dispatched meanwhile). */
    fun await(message: String = "no matching snapshot", condition: (SecretSnapshot) -> Boolean): SecretSnapshot {
        PlatformTestUtil.waitWithEventsDispatching(
            { "$message; last: ${service.snapshot.findings}" },
            { service.snapshot.computed && condition(service.snapshot) },
            30,
        )
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        return service.snapshot
    }

    /** Asks for a new snapshot and waits until it was built and handed on. */
    fun refresh(): SecretSnapshot {
        val before = service.buildCount
        service.requestRefresh()
        PlatformTestUtil.waitWithEventsDispatching("no refresh", { service.buildCount > before }, 30)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        return service.snapshot
    }
}

/** Records the notifications a test causes (the platform publishes them at once on the project bus in tests). */
class VaultNotifications(private val project: Project, disposable: Disposable) {
    val all = CopyOnWriteArrayList<Notification>()

    init {
        project.messageBus.connect(disposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                all += notification
            }
        })
    }

    /** The vault notifications (both groups). */
    val vault: List<Notification>
        get() = all.filter { it.groupId == SecretNotifier.GROUP_ID || it.groupId == SecretNotifier.WARNINGS_GROUP_ID }

    /** Clicks the action [text] of [notification] as the balloon does (tests only: `Notification.fire` is internal API). */
    fun click(notification: Notification, text: String) {
        val action = notification.actions.firstOrNull { it.templateText == text } ?: error("no action $text in ${actions(notification)}")
        val context = SimpleDataContext.builder().add(Notification.KEY, notification).add(CommonDataKeys.PROJECT, project).build()
        Notification.fire(notification, action, context)
    }

    companion object {
        fun actions(notification: Notification): List<String> = notification.actions.map { it.templateText.orEmpty() }
    }
}

/** `ansible-vault encrypt_string` output (`!vault |`, then the indented envelope) saved as a file. */
fun pasted(envelope: VaultEnvelope, tag: String = "!vault |"): String = tag + "\n" + envelope.formatLines().joinToString("") { "  $it\n" }
