package de.terletzkiy.ansibility.navigation

import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Opens a file at an offset as a recorded navigation, so Navigate › Back returns to where the jump started and
 * Forward goes there again. The platform only records a back place for navigation inside a command marked as
 * navigation; our Go to Declaration targets, gutter targets, Find Usages targets and tool-window rows navigate
 * themselves (their own `navigate()`, sometimes in a later EDT event), outside such a command.
 */
object EditorJumps {
    fun open(project: Project, file: VirtualFile, offset: Int, requestFocus: Boolean = true) =
        open(project, OpenFileDescriptor(project, file, offset.coerceAtLeast(0)), requestFocus)

    fun open(project: Project, descriptor: OpenFileDescriptor, requestFocus: Boolean = true) {
        if (project.isDisposed) return
        CommandProcessor.getInstance().executeCommand(project, {
            IdeDocumentHistory.getInstance(project).includeCurrentCommandAsNavigation()
            descriptor.navigate(requestFocus)
        }, "", null)
    }
}
