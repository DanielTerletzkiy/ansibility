package de.terletzkiy.ansibility.golden.sync

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.settings.RootKeys
import java.io.IOException
import java.nio.file.Path

/**
 * Symbolic links around a role directory the golden features would write (plan amendment R24, D191: never write or
 * delete through a link). A role copy that is a link to another copy (`falcon/roles/haproxy` → `golden/roles/haproxy`)
 * looks like a copy of its own in the catalog, but writing to it writes the other one.
 */
object RoleLinks {
    /**
     * Where [dir] really is when writing there would go through a symbolic link: [dir] itself or a directory between
     * it and its Ansible root's directory (the project directory outside any root) is a link, or its canonical path is
     * not below that root's canonical directory. Null when [dir] is a plain directory of its root. VFS and file
     * system checks only; any thread.
     */
    fun linkedTo(project: Project, dir: VirtualFile): String? {
        if (!dir.isValid) return null
        val stop = rootDirOf(project, dir)
        var current: VirtualFile? = dir
        while (current != null && current != stop) {
            if (RoleFiles.isLink(current)) return realPath(dir)?.toString() ?: current.path
            if (stop == null) break
            current = current.parent
        }
        if (stop == null || stop == dir) return null
        val real = realPath(dir) ?: return null
        val root = realPath(stop) ?: return null
        return if (real.startsWith(root)) null else real.toString()
    }

    /** The canonical path of [file] on the local file system (links resolved), or null (not local, gone). */
    fun realPath(file: VirtualFile): Path? = try {
        file.toNioPathOrNull()?.toRealPath()
    } catch (_: IOException) {
        null
    }

    /**
     * Two of [dirs] that are the same directory on disk, or one inside the other, once links are resolved: (first,
     * second, the canonical path of the first). Null when they are all apart.
     */
    fun overlapping(dirs: List<VirtualFile>): Triple<VirtualFile, VirtualFile, String>? {
        val real = dirs.mapNotNull { dir -> realPath(dir)?.let { dir to it } }
        for (i in real.indices) {
            for (j in i + 1 until real.size) {
                val (a, pa) = real[i]
                val (b, pb) = real[j]
                if (pa.startsWith(pb) || pb.startsWith(pa)) return Triple(a, b, pa.toString())
            }
        }
        return null
    }

    /** The directory of the innermost root containing [dir], else the project directory when it contains [dir]. */
    private fun rootDirOf(project: Project, dir: VirtualFile): VirtualFile? {
        if (project.isDisposed) return null
        AnsibleWorkspace.getInstance(project).rootFor(dir)?.dir?.let { return it }
        return RootKeys.projectDir(project)?.takeIf { VfsUtilCore.isAncestor(it, dir, false) }
    }
}
