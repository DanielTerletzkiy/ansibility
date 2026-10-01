package de.terletzkiy.ansibility.context

import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent

/**
 * Decides which VFS events change the Ansible structure (plan A.9) and so must bump the structure tracker.
 *
 * Only events below one of [basePaths] (the directories the last root scan walked) count, judged on the path
 * *below* that base, so a project that happens to live under a directory called `build` is not ignored.
 *
 * Relevant: creating, deleting, moving or renaming any directory, `ansible.cfg`, `hosts.y*ml`,
 * `argument_specs.y*ml`, `requirements.y*ml`, a `Dockerfile`, a `docker-compose*.y*ml`, a `playbook-*.y*ml`,
 * a `.git` file, or any file below `roles/`, `environments/`, `group_vars/`, `host_vars/` or `molecule/`; and
 * content changes of the named files, whose content feeds cross-file caches. Events inside
 * [AnsibleLayout.SKIPPED_DIRS] (`node_modules`, the inside of `.git/`) never count.
 */
class StructureChangeFilter(basePaths: Collection<String>) {
    private val bases: List<String> = basePaths.map { it.trimEnd('/') }.filter { it.isNotEmpty() }.distinct()

    fun isRelevant(event: VFileEvent): Boolean = when (event) {
        is VFileContentChangeEvent -> below(event.path)?.let(::isRelevantContentChange) == true
        is VFileCreateEvent -> isRelevantPath(event.path, event.isDirectory)
        is VFileDeleteEvent -> isRelevantPath(event.path, event.file.isDirectory)
        is VFileMoveEvent ->
            isRelevantPath(event.oldPath, event.file.isDirectory) || isRelevantPath(event.newPath, event.file.isDirectory)
        is VFileCopyEvent -> isRelevantPath("${event.newParent.path}/${event.newChildName}", event.file.isDirectory)
        is VFilePropertyChangeEvent -> event.isRename &&
            (isRelevantPath(event.oldPath, event.file.isDirectory) || isRelevantPath(event.newPath, event.file.isDirectory))
        else -> false
    }

    /** Whether creating or deleting the file or directory at the absolute [path] changes the structure. */
    fun isRelevantPath(path: String, isDirectory: Boolean): Boolean {
        val segments = below(path) ?: return false
        return isRelevantCreateOrDelete(segments, isDirectory)
    }

    /** The segments of [path] below the first base containing it, or null when it is outside every base. */
    private fun below(path: String): List<String>? {
        val normalized = path.trimEnd('/')
        for (base in bases) {
            if (normalized == base) return emptyList()
            if (normalized.startsWith("$base/")) return normalized.substring(base.length + 1).split('/').filter { it.isNotEmpty() }
        }
        return null
    }

    companion object {
        private val STRUCTURAL_PARENTS = setOf(
            AnsibleLayout.ROLES, AnsibleLayout.ENVIRONMENTS, "inventories", AnsibleLayout.GROUP_VARS,
            AnsibleLayout.HOST_VARS, AnsibleLayout.MOLECULE,
        )

        /** [segments] are relative to a scanned base; empty means the base itself. */
        fun isRelevantCreateOrDelete(segments: List<String>, isDirectory: Boolean): Boolean {
            val name = segments.lastOrNull() ?: return true
            val parents = segments.dropLast(1)
            if (parents.any { it in AnsibleLayout.SKIPPED_DIRS }) return false
            if (isDirectory) return name !in AnsibleLayout.SKIPPED_DIRS
            return isStructuralName(name) || AnsibleLayout.isPlaybookName(name) || parents.any { it in STRUCTURAL_PARENTS }
        }

        /** Whether a content change of the file at [segments] (relative to a scanned base) matters. */
        fun isRelevantContentChange(segments: List<String>): Boolean {
            val name = segments.lastOrNull() ?: return false
            if (segments.dropLast(1).any { it in AnsibleLayout.SKIPPED_DIRS }) return false
            return isStructuralName(name)
        }

        private fun isStructuralName(name: String): Boolean =
            name == AnsibleLayout.ANSIBLE_CFG ||
                name == AnsibleLayout.DOT_GIT ||
                AnsibleLayout.isHostsFileName(name) ||
                AnsibleLayout.isArgumentSpecsName(name) ||
                AnsibleLayout.isRequirementsName(name) ||
                AnsibleLayout.isDockerfileName(name) ||
                (name.startsWith("docker-compose") && AnsibleLayout.isYamlName(name))
    }
}
