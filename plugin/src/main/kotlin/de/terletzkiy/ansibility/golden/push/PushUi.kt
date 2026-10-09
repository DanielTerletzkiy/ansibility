package de.terletzkiy.ansibility.golden.push

import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.util.concurrency.annotations.RequiresEdt
import de.terletzkiy.ansibility.golden.AnsibilityGoldenBundle.message
import de.terletzkiy.ansibility.golden.GoldenNotifications
import org.jetbrains.annotations.Nls

/**
 * The UI of Push Role to Repos (plan amendment R24, D188–D190): the dialog, the confirmation before writing files Undo
 * cannot restore, notices and the notification afterwards. An application service, so tests replace it
 * (`ServiceContainerUtil.replaceService`) and choose rows and options instead of clicking.
 */
interface PushUi {
    /** Shows the Push dialog of [model] (rows load in the background); the choice, or null when cancelled. */
    @RequiresEdt
    fun choose(project: Project, model: PushModel): PushChoice?

    /**
     * Asks before a push that writes [binaries] ("falcon: files/logo.png"): binary files Undo cannot restore (git can).
     * True to go on.
     */
    @RequiresEdt
    fun confirmBinary(project: Project, @Nls roleName: String, binaries: List<String>): Boolean

    /**
     * The one question before the notification's Undo reverts a push ([question]: "Undo the push of web to falcon,
     * heron?"); the platform does not ask per repo (D190). True to undo.
     */
    @RequiresEdt
    fun confirmUndo(project: Project, @Nls question: String): Boolean

    /** Tells why nothing was written (a stale or read-only file, a changed source). */
    @RequiresEdt
    fun inform(project: Project, @Nls message: String)

    /** Shows [notification] (the summary after a push, with Undo, Run Molecule Tests and Commit…). */
    @RequiresEdt
    fun notify(project: Project, notification: Notification)

    companion object {
        /** The group of Push's notifications ([GoldenNotifications.GROUP_ID]). */
        const val NOTIFICATION_GROUP: String = GoldenNotifications.GROUP_ID

        fun getInstance(): PushUi = service()
    }
}

/** The production [PushUi]: [PushDialog], a Messages confirmation and balloons of [GoldenNotifications.GROUP_ID]. */
class DefaultPushUi : PushUi {
    override fun choose(project: Project, model: PushModel): PushChoice? {
        val dialog = PushDialog(project, model)
        return if (dialog.showAndGet()) dialog.choice() else null
    }

    override fun confirmBinary(project: Project, roleName: String, binaries: List<String>): Boolean {
        val listed = binaries.take(MAX_LISTED).joinToString("\n") +
            (if (binaries.size > MAX_LISTED) "\n" + message("push.binary.more", binaries.size - MAX_LISTED) else "")
        val answer = Messages.showOkCancelDialog(
            project,
            message("push.binary.message", binaries.size, listed),
            message("push.binary.title", roleName),
            message("push.binary.ok"),
            Messages.getCancelButton(),
            Messages.getWarningIcon(),
        )
        return answer == Messages.OK
    }

    override fun confirmUndo(project: Project, question: String): Boolean = Messages.showOkCancelDialog(
        project,
        question,
        message("push.undo.title"),
        message("push.undo.ok"),
        Messages.getCancelButton(),
        Messages.getQuestionIcon(),
    ) == Messages.OK

    override fun inform(project: Project, message: String) {
        NotificationGroupManager.getInstance().getNotificationGroup(GoldenNotifications.NOTICE_GROUP_ID)
            .createNotification(message, NotificationType.WARNING)
            .notify(project)
    }

    override fun notify(project: Project, notification: Notification) {
        notification.notify(project)
    }

    private companion object {
        const val MAX_LISTED = 15
    }
}
