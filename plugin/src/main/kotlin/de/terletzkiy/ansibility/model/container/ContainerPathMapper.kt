package de.terletzkiy.ansibility.model.container

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import org.jetbrains.yaml.YAMLLanguage
import org.jetbrains.yaml.psi.YAMLFile
import java.util.concurrent.ConcurrentHashMap

/** Where a [VolumeMapping] comes from. */
enum class MappingSource {
    /** The molecule convention: `/ansible/roles/<X>` from a molecule file is the enclosing roles dir's `<X>`. */
    MOLECULE,

    /** A `volumes:` entry of a `docker-compose*.yaml` file. */
    COMPOSE,
}

/** A container directory and the host directory mounted there. */
data class VolumeMapping(
    /** Absolute container path without a trailing slash, e.g. `/ansible/roles`. */
    val containerPath: String,
    val hostDir: VirtualFile,
    val source: MappingSource,
    /** The compose file that declares a [MappingSource.COMPOSE] mapping. */
    val composeFile: VirtualFile? = null,
)

/**
 * Maps paths inside the repo's Ansible containers back to project files (plan A.11 "Container paths").
 *
 * The repo runs Ansible in Docker: `docker-compose.ansible-molecule.yaml` mounts `./golden/roles:/ansible/roles`,
 * `docker-compose.ansible-lint.yaml` mounts `./golden:/ansible`, a repo's playbook image mounts `./:/ansible`.
 * Molecule's entrypoint (`golden/docker/ansible-molecule/bin/entrypoint.sh`) runs every scenario from
 * `/ansible/roles/<role>`, so molecule files reference roles as `/ansible/roles/<X>` (637 times).
 *
 * [map] tries, in order:
 * 1. for a molecule file inside a role, `/ansible/roles/<rest>` → `<the role's roles dir>/<rest>`;
 * 2. the `volumes:` of the `docker-compose*.y(a)ml` files in the context root's directory and its ancestors up to
 *    the project (or content root) directory: longest container prefix first, nearer compose files first.
 *
 * The first candidate that exists wins, because several compose files mount different host dirs at the same
 * container path (`./:/ansible` for playbooks, `./ansible:/ansible` for lint). Compose files are cached until the
 * structure or any YAML file changes. Call from any thread; a read lock is taken when needed.
 */
@Service(Service.Level.PROJECT)
class ContainerPathMapper(private val project: Project) {
    private val mappingsByDir: CachedValue<ConcurrentHashMap<VirtualFile, List<VolumeMapping>>> =
        CachedValuesManager.getManager(project).createCachedValue(
            {
                CachedValueProvider.Result.create(
                    ConcurrentHashMap(),
                    AnsibleWorkspace.getInstance(project).structureTracker,
                    PsiModificationTracker.getInstance(project).forLanguage(YAMLLanguage.INSTANCE),
                    ProjectRootManager.getInstance(project),
                )
            },
            false,
        )

    /** The project file or directory [containerPath] refers to, seen from [contextFile]; null when unmapped or missing. */
    fun map(containerPath: String, contextFile: VirtualFile): VirtualFile? = readLocked {
        val path = normalize(containerPath) ?: return@readLocked null
        candidates(path, contextFile).firstOrNull { it.isValid }
    }

    /**
     * The project file at the absolute host path [path], or null when the path lies outside every content root of
     * the project. Paths such as `/etc/nginx` or `/var/run/docker.sock` are never looked up, so resolving a
     * reference cannot pull arbitrary host directories into the VFS.
     */
    fun findProjectFile(path: String): VirtualFile? = readLocked {
        val normalized = normalize(path) ?: return@readLocked null
        ProjectRootManager.getInstance(project).contentRoots
            .firstOrNull { normalized == it.path || normalized.startsWith(it.path.trimEnd('/') + "/") }
            ?.let { root -> if (normalized == root.path) root else root.findFileByRelativePath(normalized.removePrefix(root.path.trimEnd('/')).trimStart('/')) }
    }

    /** Every mapping that applies to [contextFile], in the order [map] tries them. */
    fun mappings(contextFile: VirtualFile): List<VolumeMapping> = readLocked {
        listOfNotNull(moleculeMapping(contextFile)) + composeMappings(contextFile)
    }

    private fun candidates(path: String, contextFile: VirtualFile): Sequence<VirtualFile> =
        mappings(contextFile).asSequence()
            .filter { path == it.containerPath || path.startsWith(it.containerPath + "/") || it.containerPath == "/" }
            .sortedByDescending { it.containerPath.length }
            .mapNotNull { mapping ->
                val rest = path.removePrefix(mapping.containerPath).trimStart('/')
                if (rest.isEmpty()) mapping.hostDir else mapping.hostDir.findFileByRelativePath(rest)
            }

    /** `/ansible/roles` → the roles dir enclosing the molecule scenario of [contextFile]. */
    private fun moleculeMapping(contextFile: VirtualFile): VolumeMapping? {
        val context = AnsibleWorkspace.getInstance(project).contextOf(contextFile) ?: return null
        val roleDir = context.roleDir ?: return null
        val relative = VfsUtilCore.getRelativePath(contextFile, roleDir) ?: return null
        if (relative.substringBefore('/') != AnsibleLayout.MOLECULE) return null
        val rolesDir = roleDir.parent ?: return null
        return VolumeMapping(MOLECULE_ROLES, rolesDir, MappingSource.MOLECULE)
    }

    private fun composeMappings(contextFile: VirtualFile): List<VolumeMapping> {
        val start = AnsibleWorkspace.getInstance(project).rootFor(contextFile)?.dir
            ?: (if (contextFile.isDirectory) contextFile else contextFile.parent)
            ?: return emptyList()
        val cache = mappingsByDir.value
        cache[start]?.let { return it }
        val result = directoriesUpToProject(start).flatMap(::composeMappingsIn)
        cache[start] = result
        return result
    }

    /** [start] and its ancestors, up to the content root or project dir containing it (inclusive). */
    private fun directoriesUpToProject(start: VirtualFile): List<VirtualFile> {
        val index = ProjectFileIndex.getInstance(project)
        val stop = index.getContentRootForFile(start)
            ?: project.guessProjectDir()?.takeIf { VfsUtilCore.isAncestor(it, start, false) }
            ?: return listOf(start)
        val result = ArrayList<VirtualFile>()
        var current: VirtualFile? = start
        while (current != null) {
            result += current
            if (current == stop) break
            current = current.parent
        }
        return result
    }

    private fun composeMappingsIn(dir: VirtualFile): List<VolumeMapping> =
        dir.children.orEmpty()
            .filter { !it.isDirectory && isComposeFileName(it.name) }
            .sortedBy { it.name }
            .flatMap { file ->
                ProgressManager.checkCanceled()
                val yaml = YamlFiles.yamlFile(project, file) ?: return@flatMap emptyList()
                volumesOf(yaml).mapNotNull { volume ->
                    val host = resolveHost(file.parent, volume.host) ?: return@mapNotNull null
                    VolumeMapping(volume.container, host, MappingSource.COMPOSE, file)
                }
            }

    private fun volumesOf(file: YAMLFile): List<ComposeVolumes.Volume> =
        CachedValuesManager.getCachedValue(file, VOLUMES) {
            CachedValueProvider.Result.create(ComposeVolumes.parse(PsiYValueAdapter.documentValue(file)), file)
        }

    /**
     * A bind-mount source relative to the compose file's directory, or an absolute path inside the project; named
     * volumes, `$VAR` sources and host paths outside the project are skipped.
     */
    private fun resolveHost(composeDir: VirtualFile, host: String): VirtualFile? = when {
        host.startsWith("/") -> findProjectFile(host)
        host == "." || host == "./" -> composeDir
        host.startsWith("./") || host.startsWith("../") -> composeDir.findFileByRelativePath(host.removePrefix("./").trimEnd('/'))
        else -> null
    }?.takeIf { it.isDirectory }

    private fun <T> readLocked(action: () -> T): T =
        if (ApplicationManager.getApplication().isReadAccessAllowed) action() else runReadActionBlocking(action)

    companion object {
        /** The container directory molecule scenarios see the roles dir at. */
        const val MOLECULE_ROLES: String = "/ansible/roles"

        private val VOLUMES = Key.create<CachedValue<List<ComposeVolumes.Volume>>>("ansibility.model.composeVolumes")

        fun getInstance(project: Project): ContainerPathMapper = project.service()

        /** `docker-compose.yml`, `docker-compose.ansible-lint.yaml` … */
        fun isComposeFileName(name: String): Boolean = name.startsWith("docker-compose") && AnsibleLayout.isYamlName(name)

        /** An absolute container path without `.`/`..` segments and trailing slash; null for relative paths. */
        fun normalize(path: String): String? {
            if (!path.startsWith("/")) return null
            val segments = ArrayList<String>()
            for (segment in path.split('/')) {
                when (segment) {
                    "", "." -> Unit
                    ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                    else -> segments += segment
                }
            }
            return "/" + segments.joinToString("/")
        }
    }
}
