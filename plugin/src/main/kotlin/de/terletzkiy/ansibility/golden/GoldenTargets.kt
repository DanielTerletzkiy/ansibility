package de.terletzkiy.ansibility.golden

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.model.role.RoleCatalog
import de.terletzkiy.ansibility.model.role.RoleCatalogSnapshot
import de.terletzkiy.ansibility.model.role.RoleCopy

/**
 * What a golden action works on (plan amendment R24): a role copy and, optionally, a path inside it.
 *
 * [relPath] is relative to the role directory with `/` separators ("tasks/main.yml", or "tasks" for a folder); null
 * stands for the whole copy. It may name a file that exists only in another copy (a "only in golden" row of the
 * tool window).
 */
data class GoldenTarget(val copy: RoleCopy, val relPath: String?)

/**
 * The one way the golden actions find their [GoldenTarget] in a data context (plan amendment R24):
 *
 * 1. [GoldenDataKeys.ROLE_COPY] (and [GoldenDataKeys.ROLE_PATH]): the Ansibility tool window's role copy and file rows;
 * 2. else [CommonDataKeys.VIRTUAL_FILE] inside a role copy of the catalog (`RoleCatalog.copyContaining`) and its path
 *    relative to the role directory: the editor, the Project view and the tool window's role file rows.
 *
 * VFS-only (the catalog snapshot takes a read lock when the caller holds none); safe in a BGT `update`.
 */
object GoldenTargets {
    /** The target of [context], or null when it names nothing inside a role copy. */
    fun of(project: Project, context: DataContext): GoldenTarget? {
        if (project.isDisposed) return null
        val catalog = RoleCatalog.getInstance(project).snapshot()
        val roleDir = context.getData(GoldenDataKeys.ROLE_COPY)
        if (roleDir != null) return ofRoleCopy(catalog, roleDir, context.getData(GoldenDataKeys.ROLE_PATH))
        val file = context.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        return ofFile(catalog, file)
    }

    /** The target of [file] (a role directory or anything below one) in [catalog], or null. */
    fun ofFile(catalog: RoleCatalogSnapshot, file: VirtualFile): GoldenTarget? {
        if (!file.isValid) return null
        val copy = catalog.copyContaining(file) ?: return null
        return GoldenTarget(copy, relativePath(copy.dir, file))
    }

    private fun ofRoleCopy(catalog: RoleCatalogSnapshot, roleDir: VirtualFile, rolePath: String?): GoldenTarget? {
        val copy = catalog.copyOf(roleDir) ?: catalog.copyContaining(roleDir) ?: return null
        val prefix = relativePath(copy.dir, roleDir)
        val inner = normalize(rolePath)
        val path = when {
            prefix == null -> inner
            inner == null -> prefix
            else -> "$prefix/$inner"
        }
        return GoldenTarget(copy, path)
    }

    /** [file]'s path below [dir] with `/` separators, or null for [dir] itself (or a file outside it). */
    fun relativePath(dir: VirtualFile, file: VirtualFile): String? =
        VfsUtilCore.getRelativePath(file, dir, '/')?.takeIf { it.isNotEmpty() }

    /** A role path without leading or trailing separators, or null when empty or when it leaves the role (`..`). */
    fun normalize(path: String?): String? {
        val trimmed = path?.replace('\\', '/')?.trim('/') ?: return null
        if (trimmed.isEmpty()) return null
        if (trimmed.split('/').any { it == ".." || it == "." || it.isEmpty() }) return null
        return trimmed
    }
}
