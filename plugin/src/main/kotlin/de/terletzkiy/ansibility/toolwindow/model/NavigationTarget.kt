package de.terletzkiy.ansibility.toolwindow.model

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.SourceLocation

/**
 * Where a tree node or a details link jumps to (plan F6.3): an [offset] in a file (a group key or host entry in
 * `hosts.yml`, a var file, `ansible.cfg`), or a directory, which the platform selects in the Project view.
 */
data class NavigationTarget(val file: VirtualFile, val offset: Int = 0) {
    /** The descriptor that opens the target; `OpenFileDescriptor(project, vf, offset).navigate(true)` jumps there. */
    fun descriptor(project: Project): OpenFileDescriptor =
        if (file.isDirectory) OpenFileDescriptor(project, file) else OpenFileDescriptor(project, file, offset.coerceAtLeast(0))

    companion object {
        /** The target of [location], or null. */
        fun of(location: SourceLocation?): NavigationTarget? = location?.let { NavigationTarget(it.file, it.offset) }
    }
}
