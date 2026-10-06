package de.terletzkiy.ansibility.navigation

import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.model.role.RoleLayout

/** Console hyperlinks for ansible-playbook output (plan X60), in every console of the project. */
class AnsibleConsoleFilterProvider : ConsoleFilterProvider {
    override fun getDefaultFilters(project: Project): Array<Filter> = arrayOf(AnsibleConsoleFilter(project))
}

/**
 * Links `task path: /ansible/roles/haproxy/tasks/main.yml:12` (a container path is remapped onto the Ansible root
 * that has the same relative file, preferring a root whose directory is named like the mount) and
 * `TASK [haproxy : Configure server]` (the named task of that role, project roots before role libraries).
 */
class AnsibleConsoleFilter(private val project: Project) : Filter, DumbAware {
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val start = entireLength - line.length
        TASK_PATH.find(line)?.let { match ->
            val path = match.groupValues[1]
            val lineNumber = match.groupValues[2].toInt() - 1
            val file = resolvePath(path) ?: return null
            val range = match.groups[1]!!.range
            return Filter.Result(start + range.first, start + match.range.last + 1, OpenFileHyperlinkInfo(project, file, lineNumber))
        }
        TASK_HEADER.find(line)?.let { match ->
            val role = match.groupValues[1].trim()
            val name = match.groupValues[2].trim()
            val (file, lineNumber) = findTask(role, name) ?: return null
            val range = match.groups[2]!!.range
            return Filter.Result(start + range.first, start + range.last + 1, OpenFileHyperlinkInfo(project, file, lineNumber))
        }
        return null
    }

    /** The file at [path], else the same relative path below an Ansible root (container mounts), longest suffix first. */
    fun resolvePath(path: String): VirtualFile? {
        LocalFileSystem.getInstance().findFileByPath(path)?.takeIf { !it.isDirectory }?.let { return it }
        val segments = path.trimStart('/').split('/')
        val roots = runReadAction { AnsibleWorkspace.getInstance(project).roots().filter { !it.detached } }
        for (drop in segments.indices) {
            val relative = segments.drop(drop).joinToString("/")
            if (relative.isEmpty()) break
            val found = roots.mapNotNull { root -> root.dir.findFileByRelativePath(relative)?.takeIf { !it.isDirectory }?.let { root to it } }
            if (found.isEmpty()) continue
            val mount = segments.getOrNull(drop - 1)
            return (found.firstOrNull { (root, _) -> root.dir.name == mount } ?: found.first()).second
        }
        return null
    }

    /** The task named [name] in role [role]'s task files, as (file, 0-based line). */
    fun findTask(role: String, name: String): Pair<VirtualFile, Int>? = runReadAction {
        val workspace = AnsibleWorkspace.getInstance(project)
        val registry = RoleRegistry.getInstance(project)
        for (root in workspace.roots().filter { !it.detached }.sortedBy { it.kind == RootKind.ROLE_LIBRARY }) {
            val ref = registry.roles(root).firstOrNull { it.name == role } ?: continue
            for (file in RoleLayout.taskFiles(ref.dir) + RoleLayout.handlerFiles(ref.dir)) {
                val text = FileDocumentManager.getInstance().getDocument(file)?.text ?: runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: continue
                text.lineSequence().forEachIndexed { index, content ->
                    val task = NAME_LINE.find(content)?.groupValues?.get(1)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")
                    if (task == name) return@runReadAction file to index
                }
            }
        }
        null
    }

    private companion object {
        val TASK_PATH = Regex("""task path: (\S+?\.ya?ml):(\d+)""")
        val TASK_HEADER = Regex("""(?:TASK|RUNNING HANDLER) \[([^:\]]+) : ([^\]]+)]""")
        val NAME_LINE = Regex("""^\s*(?:-\s+)?name:\s*(.+?)\s*$""")
    }
}
