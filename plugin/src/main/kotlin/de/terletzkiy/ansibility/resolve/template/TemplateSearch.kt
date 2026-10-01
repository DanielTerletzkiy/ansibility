package de.terletzkiy.ansibility.resolve.template

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.model.container.ContainerPathMapper

/**
 * Where ansible-core looks for a template or file that a task names (plan A.7 query-time rules), as
 * `DataLoader.path_dwim_relative_stack` does for `_find_needle`: for every search path (the role directory, then the
 * task file's directory, then the playbook directory) `<path>/<subdir>/<name>` (unless the name already starts with
 * `<subdir>/`) and `<path>/<name>`. Absolute names are mapped through [ContainerPathMapper] (molecule's
 * `/ansible/roles`, compose volumes); unmapped absolute paths (`/etc/…`) never resolve.
 */
internal object TemplateSearch {
    const val TEMPLATES: String = "templates"
    const val FILES: String = "files"

    /** The search paths of a task written in [taskFile] (classified as [context]), nearest first. */
    fun searchPaths(taskFile: VirtualFile, context: FileContext): List<VirtualFile> =
        listOfNotNull(context.roleDir, taskFile.parent).distinct()

    /** The existing files [name] refers to from [taskFile], in search order (a template normally resolves to one). */
    fun resolve(project: Project, name: String, taskFile: VirtualFile, context: FileContext, subdir: String = TEMPLATES): List<VirtualFile> {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("~")) return emptyList()
        if (trimmed.startsWith("/")) {
            return listOfNotNull(ContainerPathMapper.getInstance(project).map(trimmed, taskFile)?.takeIf { !it.isDirectory })
        }
        return candidates(trimmed, searchPaths(taskFile, context), subdir).take(1)
    }

    /** Every existing `<path>/<subdir>/<name>` and `<path>/<name>` for [paths], in order, without duplicates. */
    fun candidates(name: String, paths: List<VirtualFile>, subdir: String): List<VirtualFile> {
        val result = LinkedHashSet<VirtualFile>()
        val underSubdir = name.substringBefore('/') == subdir
        for (path in paths) {
            ProgressManager.checkCanceled()
            if (!underSubdir) path.findFileByRelativePath("$subdir/$name")?.takeIf { !it.isDirectory }?.let(result::add)
            path.findFileByRelativePath(name)?.takeIf { !it.isDirectory }?.let(result::add)
        }
        return result.toList()
    }

    /** The existing directories a relative directory [dir] names below [paths] (both the `subdir` and the plain form). */
    fun directories(dir: String, paths: List<VirtualFile>, subdir: String): List<VirtualFile> {
        val result = LinkedHashSet<VirtualFile>()
        val clean = dir.trim('/')
        val underSubdir = clean.substringBefore('/') == subdir
        for (path in paths) {
            if (!underSubdir) (if (clean.isEmpty()) path.findChild(subdir) else path.findFileByRelativePath("$subdir/$clean"))?.takeIf { it.isDirectory }?.let(result::add)
            (if (clean.isEmpty()) path else path.findFileByRelativePath(clean))?.takeIf { it.isDirectory }?.let(result::add)
        }
        return result.toList()
    }

    /** The files below [dir] (recursively) whose path relative to [dir] starts with [prefix] and ends with [suffix]. */
    fun listing(dir: VirtualFile, prefix: String, suffix: String): List<VirtualFile> {
        val result = ArrayList<VirtualFile>()
        VfsUtilCore.iterateChildrenRecursively(dir, null) { file ->
            ProgressManager.checkCanceled()
            if (!file.isDirectory) {
                val relative = VfsUtilCore.getRelativePath(file, dir) ?: return@iterateChildrenRecursively true
                if (relative.startsWith(prefix) && relative.endsWith(suffix)) result += file
            }
            result.size < MAX_LISTING
        }
        return result
    }

    /** A shell glob (`*`, `?`, `[…]`) as a regex over one path segment. */
    fun globRegex(glob: String): Regex {
        val out = StringBuilder()
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                c == '*' -> out.append("[^/]*")
                c == '?' -> out.append("[^/]")
                c == '[' && glob.indexOf(']', i + 1) > i + 1 -> {
                    val end = glob.indexOf(']', i + 1)
                    val body = glob.substring(i + 1, end).replace("\\", "\\\\")
                    out.append('[').append(if (body.startsWith("!")) "^" + body.drop(1) else body).append(']')
                    i = end
                }
                else -> out.append(Regex.escape(c.toString()))
            }
            i++
        }
        return Regex(out.toString())
    }

    /** Listings stop after this many files (a dynamic `src` over a huge directory is not a template choice). */
    private const val MAX_LISTING = 500
}
