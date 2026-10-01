package de.terletzkiy.ansibility.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * Keys under which per-root settings and workspace state are stored: the root directory relative to the project
 * directory (`repos/falcon/ansible`, `golden`), `.` for the project directory itself, or the absolute path for a root
 * outside the project directory. Relative keys keep `.idea/ansibility.xml` valid for every clone of the repo.
 */
object RootKeys {
    /** The key of the directory [dir] in [project]. */
    fun keyOf(project: Project, dir: VirtualFile): String {
        val base = baseDir(project)
        if (base != null) {
            val relative = if (base == dir) "" else VfsUtilCore.getRelativePath(dir, base, '/')
            if (relative != null) return relative.ifEmpty { PROJECT_DIR }
        }
        val basePath = project.basePath
        if (basePath != null && FileUtil.isAncestor(basePath, dir.path, false)) {
            return FileUtil.getRelativePath(basePath, dir.path, '/')?.takeIf { it.isNotEmpty() && it != "." } ?: PROJECT_DIR
        }
        return dir.path
    }

    /** The path of [file] relative to the project directory, or null when it lies outside. */
    fun relativePath(project: Project, file: VirtualFile): String? {
        val base = baseDir(project) ?: return null
        return relativePath(base, file)
    }

    /** The path of [file] relative to [base], or null when it lies outside. */
    fun relativePath(base: VirtualFile, file: VirtualFile): String? =
        if (base == file) "" else VfsUtilCore.getRelativePath(file, base, '/')

    /** The project directory the keys are relative to, or null (a disposed project, or one without a directory). */
    fun projectDir(project: Project): VirtualFile? = baseDir(project)

    /** The key of the project directory itself. */
    const val PROJECT_DIR: String = "."

    private fun baseDir(project: Project): VirtualFile? =
        if (project.isDisposed) null else project.guessProjectDir()?.takeIf { it.isValid && it.isDirectory }
}
