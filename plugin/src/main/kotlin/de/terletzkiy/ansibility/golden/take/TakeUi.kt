package de.terletzkiy.ansibility.golden.take

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.StatusBar
import com.intellij.util.concurrency.annotations.RequiresEdt
import org.jetbrains.annotations.Nls

/**
 * The small UI of the single-file takes (plan amendment R24, X121): the two confirmations (a delete, a key or vault
 * file), notices and the short note after a take. An application service, so tests replace it (`replaceService`) and
 * answer without a dialog.
 */
interface TakeUi {
    /** Asks before a take deletes a file ([message] names it and where). True to delete. */
    @RequiresEdt
    fun confirmDelete(project: Project, @Nls title: String, @Nls message: String): Boolean

    /** Asks before a take copies a key or vault file (as it is, never decrypted). True to copy. */
    @RequiresEdt
    fun confirmSensitive(project: Project, @Nls title: String, @Nls message: String): Boolean

    /** Tells why nothing was written, or that a written file cannot be undone. */
    @RequiresEdt
    fun inform(project: Project, @Nls message: String)

    /** The short note after a take ("Took tasks/main.yml from golden into falcon"): the status bar. */
    @RequiresEdt
    fun done(project: Project, @Nls message: String)

    companion object {
        /** The general "Ansibility" balloon group (`ansibility-core.xml`). */
        const val NOTIFICATION_GROUP: String = "Ansibility"

        fun getInstance(): TakeUi = service()
    }
}

/** The production [TakeUi]: Messages confirmations, balloons of the "Ansibility" group and the status bar. */
class DefaultTakeUi : TakeUi {
    override fun confirmDelete(project: Project, title: String, message: String): Boolean = Messages.showOkCancelDialog(
        project, message, title, AnsibilityTakeTexts.deleteButton(), Messages.getCancelButton(), Messages.getWarningIcon(),
    ) == Messages.OK

    override fun confirmSensitive(project: Project, title: String, message: String): Boolean = Messages.showOkCancelDialog(
        project, message, title, AnsibilityTakeTexts.takeButton(), Messages.getCancelButton(), Messages.getWarningIcon(),
    ) == Messages.OK

    override fun inform(project: Project, message: String) {
        NotificationGroupManager.getInstance().getNotificationGroup(TakeUi.NOTIFICATION_GROUP)
            .createNotification(message, NotificationType.WARNING)
            .notify(project)
    }

    override fun done(project: Project, message: String) {
        StatusBar.Info.set(message, project)
    }
}
