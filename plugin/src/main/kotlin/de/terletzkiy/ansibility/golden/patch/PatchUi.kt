package de.terletzkiy.ansibility.golden.patch

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.util.concurrency.annotations.RequiresEdt
import de.terletzkiy.ansibility.golden.AnsibilityGoldenBundle.message
import de.terletzkiy.ansibility.golden.GoldenNotifications
import org.jetbrains.annotations.Nls
import java.nio.file.Path

/**
 * The UI of "Copy as Patch for Golden…" (plan amendment R25, X126): the dialog (files, "Include key and vault files",
 * Copy to Clipboard or Save as .patch…), the save dialog, notices and the notification afterwards. An application
 * service, so tests replace it (`ServiceContainerUtil.replaceService`) and choose without a dialog.
 */
interface PatchUi {
    /** Shows the patch dialog of [model]: the user's choice, or null when cancelled. */
    @RequiresEdt
    fun choose(project: Project, model: PatchModel): PatchChoice?

    /** Asks where to save the patch ([defaultFileName] proposed); the file, or null when cancelled. */
    @RequiresEdt
    fun saveTarget(project: Project, defaultFileName: String): Path?

    /** Tells why there is no patch (nothing differs, only binary files) or why it was not saved. */
    @RequiresEdt
    fun inform(project: Project, @Nls message: String)

    /** The note afterwards: "Patch for web (3 files) copied — apply it in the golden repository with git apply". */
    @RequiresEdt
    fun notify(project: Project, @Nls message: String)

    companion object {
        fun getInstance(): PatchUi = service()
    }
}

/** The production [PatchUi]: [PatchDialog], the platform's save dialog and balloons of the "Ansibility" group. */
class DefaultPatchUi : PatchUi {
    override fun choose(project: Project, model: PatchModel): PatchChoice? {
        val dialog = PatchDialog(project, model)
        return if (dialog.showAndGet()) dialog.choice() else null
    }

    override fun saveTarget(project: Project, defaultFileName: String): Path? {
        val descriptor = FileSaverDescriptor(message("patch.save.title"), message("patch.save.description"))
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        return dialog.save(project.guessProjectDir(), defaultFileName)?.file?.toPath()
    }

    override fun inform(project: Project, message: String) = notification(project, message, NotificationType.WARNING)

    override fun notify(project: Project, message: String) = notification(project, message, NotificationType.INFORMATION)

    private fun notification(project: Project, @Nls message: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup(GoldenNotifications.NOTICE_GROUP_ID)
            .createNotification(message, type)
            .notify(project)
    }
}
