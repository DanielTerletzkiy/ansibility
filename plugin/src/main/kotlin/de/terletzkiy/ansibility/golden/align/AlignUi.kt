package de.terletzkiy.ansibility.golden.align

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.util.concurrency.annotations.RequiresEdt
import de.terletzkiy.ansibility.golden.GoldenNotifications
import org.jetbrains.annotations.Nls

/**
 * The small UI around Align (plan amendment R24, D184/D187): the opening step of Align Role…, notices and errors. An
 * application service, so tests replace it (`replaceService`) and pick copies without a dialog.
 */
interface AlignUi {
    /**
     * The opening step of Align Role… for [setup]: the target, the source and "Include key and vault files"; null when
     * cancelled.
     */
    @RequiresEdt
    fun chooseCopies(project: Project, setup: AlignSetup): AlignRequest?

    /** A short notice ("web in golden is the same as in falcon"). */
    @RequiresEdt
    fun inform(project: Project, @Nls message: String)

    /** An error that stops a step (a stale or read-only target). */
    @RequiresEdt
    fun error(project: Project, @Nls title: String, @Nls message: String)

    companion object {
        fun getInstance(): AlignUi = service()
    }
}

/** The production [AlignUi]: [AlignRoleDialog], a balloon of the "Ansibility" group for notices, an error dialog. */
class DefaultAlignUi : AlignUi {
    override fun chooseCopies(project: Project, setup: AlignSetup): AlignRequest? = AlignRoleDialog(project, setup).choose()

    override fun inform(project: Project, message: String) {
        NotificationGroupManager.getInstance().getNotificationGroup(GoldenNotifications.NOTICE_GROUP_ID)
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    }

    override fun error(project: Project, title: String, message: String) {
        Messages.showErrorDialog(project, message, title)
    }
}
