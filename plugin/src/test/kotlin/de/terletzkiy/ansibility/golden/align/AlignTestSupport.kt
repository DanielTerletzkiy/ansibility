package de.terletzkiy.ansibility.golden.align

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.replaceService
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Helpers of the Align tests (plan amendment R24, D184–D187): a scripted merge window registered as the only
 * [RoleMergeDialog] (the window is an extension, so tests drive Accept and Merge… through the session), a recording
 * [AlignUi], and the notifications a test causes.
 */
object AlignTestSupport {
    /** Makes [dialog] the only merge window until [parent] is disposed; an empty list leaves the fallback. */
    fun install(dialogs: List<RoleMergeDialog>, parent: Disposable) {
        ExtensionTestUtil.maskExtensions(RoleMergeDialog.EP_NAME, dialogs, parent)
    }

    /** Installs [ui] as the [AlignUi] until [parent] is disposed. */
    fun install(ui: AlignUi, parent: Disposable) {
        ApplicationManager.getApplication().replaceService(AlignUi::class.java, ui, parent)
    }

    /** Records the notifications published on [project]'s bus until [parent] is disposed. */
    fun notifications(project: Project, parent: Disposable): MutableList<Notification> {
        val all = CopyOnWriteArrayList<Notification>()
        project.messageBus.connect(parent).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                all += notification
            }
        })
        return all
    }

    /** The texts of [notification]'s buttons. */
    fun buttons(notification: Notification): List<String> = notification.actions.map { it.templateText.orEmpty() }

    /** Clicks the button [text] of [notification], as the balloon does (tests only: `Notification.fire` is internal API). */
    fun click(project: Project, notification: Notification, text: String) {
        val action = notification.actions.firstOrNull { it.templateText == text } ?: error("no button $text in ${buttons(notification)}")
        val context = SimpleDataContext.builder().add(Notification.KEY, notification).add(CommonDataKeys.PROJECT, project).build()
        Notification.fire(notification, action, context)
    }
}

/**
 * A merge window that records the sessions it is shown and runs [script] on each (on the EDT, like the real one).
 * Install it once per test (an extension point can be masked once per test) and change [script] between runs.
 */
class ScriptedMergeDialog(@Volatile var script: (Project, AlignSession) -> Unit = { _, _ -> }) : RoleMergeDialog {
    val sessions = CopyOnWriteArrayList<AlignSession>()

    override fun show(project: Project, session: AlignSession) {
        sessions += session
        script(project, session)
    }
}

/** An [AlignUi] that records; [choose] answers the opening step (null: cancelled). */
class RecordingAlignUi : AlignUi {
    val setups = CopyOnWriteArrayList<AlignSetup>()
    val notices = CopyOnWriteArrayList<String>()
    val errors = CopyOnWriteArrayList<String>()

    @Volatile
    var choose: (AlignSetup) -> AlignRequest? = { null }

    override fun chooseCopies(project: Project, setup: AlignSetup): AlignRequest? {
        setups += setup
        return choose(setup)
    }

    override fun inform(project: Project, message: String) {
        notices += message
    }

    override fun error(project: Project, title: String, message: String) {
        errors += message
    }
}
