package de.terletzkiy.ansibility.navigation

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.model.container.ContainerPathMapper

/** One place a relative path is looked up: [relative] below [base]. */
internal data class Candidate(val base: VirtualFile, val relative: String) {
    /** The file or directory this candidate names, if it exists. */
    fun find(): VirtualFile? = if (relative.isEmpty()) base else base.findFileByRelativePath(relative)
}

/**
 * The file search of ansible-core's action plugins (`_find_needle` → `DataLoader.path_dwim_relative_stack`) for
 * `template`/`copy` `src` and template includes: for each search path (the role, then the task file's directory),
 * `<path>/<dirname>/<source>` (unless the source already starts with `<dirname>/`) and `<path>/<source>`, then the
 * same below the playbook directories. A task file in a subdirectory of `<role>/tasks` also looks at
 * `<role>/<dirname>/<source>` and `<role>/tasks/<source>`.
 */
internal object NeedleSearch {
    /** The candidates for [source] (relative, `./` allowed) in search order, without duplicates. */
    fun candidates(
        dirname: String,
        source: String,
        roleDir: VirtualFile?,
        taskDir: VirtualFile?,
        playbookDirs: List<VirtualFile> = emptyList(),
    ): List<Candidate> {
        val normalized = source.removePrefix("./")
        val skipDirname = normalized.substringBefore('/') == dirname
        val withDirname = if (normalized.isEmpty()) dirname else "$dirname/$normalized"
        val result = LinkedHashSet<Candidate>()
        for (path in listOfNotNull(roleDir, taskDir).distinct()) {
            val parent = path.parent
            if (roleDir != null && path != roleDir && parent?.name == TASKS && VfsUtilCore.isAncestor(roleDir, parent, false)) {
                parent.parent?.let { result += Candidate(it, withDirname) }
                result += Candidate(parent, normalized)
            }
            if (!skipDirname) result += Candidate(path, withDirname)
            result += Candidate(path, normalized)
        }
        for (base in playbookDirs) {
            if (!skipDirname) result += Candidate(base, withDirname)
            result += Candidate(base, normalized)
        }
        return result.toList()
    }

    /** The first candidate that exists; a directory counts only with [acceptDirectory]. */
    fun resolve(candidates: List<Candidate>, acceptDirectory: Boolean): VirtualFile? =
        candidates.firstNotNullOfOrNull { candidate -> candidate.find()?.takeIf { it.isValid && (acceptDirectory || !it.isDirectory) } }

    private const val TASKS = "tasks"
}

/** How an absolute path in a reference relates to the project (plan A.11 "Container paths"). */
internal sealed interface AbsolutePath {
    /** A container path that [ContainerPathMapper] maps (or a project path); [file] is null when nothing exists there. */
    data class Mapped(val file: VirtualFile?) : AbsolutePath

    /** A path on the managed host or elsewhere outside the project (`/etc/…`, `/tmp/…`): never resolved, never an error. */
    data object Unmapped : AbsolutePath

    companion object {
        /** Classifies the absolute [path] written in [contextFile]. */
        fun of(project: Project, path: String, contextFile: VirtualFile): AbsolutePath {
            val normalized = ContainerPathMapper.normalize(path) ?: return Unmapped
            val mapper = ContainerPathMapper.getInstance(project)
            mapper.map(normalized, contextFile)?.let { return Mapped(it) }
            val mapped = mapper.mappings(contextFile).any { mapping ->
                normalized == mapping.containerPath || normalized.startsWith(mapping.containerPath.trimEnd('/') + "/")
            }
            if (mapped) return Mapped(null)
            val inProject = ProjectRootManager.getInstance(project).contentRoots.any { root ->
                normalized == root.path || normalized.startsWith(root.path.trimEnd('/') + "/")
            }
            return if (inProject) Mapped(mapper.findProjectFile(normalized)) else Unmapped
        }
    }
}

/**
 * Finds a role directory the way ansible-core's `RoleDefinition._load_role_path` searches (the same order as the
 * play graph): `<playbook dir>/roles`, the root's roles dirs (`roles_path` included), the dependent role's roles
 * directory, then each playbook dir itself. Absolute paths go through [AbsolutePath] (`/ansible/roles/haproxy` in
 * molecule). Collection roles (`ns.coll.role`) are never in the project.
 */
internal object RoleLocator {
    /** The outcome of one lookup. */
    sealed interface Result {
        data class Found(val dir: VirtualFile) : Result

        /** Nothing in the project; [certain] when ansible-core could not find it anywhere else either. */
        data class Missing(val certain: Boolean) : Result

        /** A reference the project cannot answer (templated, unmapped absolute path). */
        data object Unknown : Result
    }

    fun locate(
        project: Project,
        written: String,
        contextFile: VirtualFile,
        root: AnsibleRoot,
        playbookDirs: List<VirtualFile>,
        dependentRoleDir: VirtualFile?,
    ): Result {
        val reference = written.trim().trimEnd('/')
        if (reference.isEmpty() || RefOccurrence.isTemplated(reference)) return Result.Unknown
        // A galaxy source (`src: https://…`, `git+ssh://…`, an archive) is installed, never looked up.
        if ("://" in reference || reference.startsWith("git+") || reference.endsWith(".tar.gz")) return Result.Unknown
        if (reference.startsWith("/")) {
            return when (val absolute = AbsolutePath.of(project, reference, contextFile)) {
                is AbsolutePath.Mapped -> absolute.file?.takeIf { it.isDirectory }?.let { Result.Found(it) } ?: Result.Missing(certain = true)
                AbsolutePath.Unmapped -> Result.Unknown
            }
        }
        for (dir in searchPath(root, playbookDirs, dependentRoleDir)) {
            dir.findFileByRelativePath(reference)?.takeIf { it.isDirectory }?.let { return Result.Found(it) }
        }
        if ('/' !in reference && '.' in reference) return Result.Missing(certain = false)
        return Result.Missing(certain = RefCaches.getInstance(project).isClosedRoleSearchPath(root))
    }

    /** The directories a role name is looked up in, in order. */
    fun searchPath(root: AnsibleRoot, playbookDirs: List<VirtualFile>, dependentRoleDir: VirtualFile?): List<VirtualFile> =
        (playbookDirs.mapNotNull { it.findChild(AnsibleLayout.ROLES)?.takeIf(VirtualFile::isDirectory) } +
            root.rolesDirs +
            listOfNotNull(dependentRoleDir?.parent) +
            playbookDirs).filter { it.isValid }.distinct()

    /** The file Ctrl+B opens for a role: `tasks/main.yml` (or `.yaml`), else the directory. */
    fun entryFile(roleDir: VirtualFile): VirtualFile =
        MAIN_TASKS.firstNotNullOfOrNull { roleDir.findFileByRelativePath(it)?.takeIf { file -> !file.isDirectory } } ?: roleDir

    private val MAIN_TASKS = listOf("tasks/main.yml", "tasks/main.yaml", "tasks/main")
}
