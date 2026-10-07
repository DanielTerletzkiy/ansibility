package de.terletzkiy.ansibility.run

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.psi.PsiManager
import de.terletzkiy.ansibility.model.container.ComposeVolumes
import de.terletzkiy.ansibility.model.container.ContainerPathMapper
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import org.jetbrains.yaml.psi.YAMLFile
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * A Compose service that can run a playbook of a root: it bind-mounts a directory holding the root. [vaultFileVariable]
 * and [sshSocketVariable] are the Compose variables naming the host file mounted at the service's
 * `ANSIBLE_VAULT_PASSWORD_FILE` and the host socket mounted at its `SSH_AUTH_SOCK` (`${VAR}:/target` volumes);
 * [variables] are all volumes whose source is a variable. Lower [rank] is a better match.
 */
data class DockerTarget(
    val composeFile: Path,
    val service: String,
    val mounts: List<Mount>,
    val vaultFileVariable: String? = null,
    val sshSocketVariable: String? = null,
    val rank: Int = DockerTargets.rank(service),
    val variables: List<VolumeVariable> = emptyList(),
    /** The service's own `ANSIBLE_CALLBACK_PLUGINS`, which a run keeps in front of Ansibility's callback. */
    val callbackPlugins: String? = null,
) {
    data class Mount(val host: Path, val container: String)

    /** A volume `$NAME:/container` or `${NAME…}:/container`; [hasDefault] for `${NAME-…}`/`${NAME:-…}`, which Compose fills itself. */
    data class VolumeVariable(val name: String, val container: String, val hasDefault: Boolean)

    /** [host] inside the container, through the deepest mount holding it; null when no mount does. */
    fun containerPath(host: Path): String? {
        val normalized = host.normalize()
        val mount = mounts.filter { normalized.startsWith(it.host) }.maxByOrNull { it.host.nameCount } ?: return null
        val rest = mount.host.relativize(normalized).joinToString("/")
        return if (rest.isEmpty()) mount.container else "${mount.container.trimEnd('/')}/$rest"
    }

    fun presentable(): String = "$service (${composeFile.fileName})"

    /** The host path of [container] through the deepest mount holding it; null outside the mounts. */
    fun hostPath(container: String): Path? {
        val mount = mounts.filter { container == it.container || container.startsWith(it.container.trimEnd('/') + "/") }
            .maxByOrNull { it.container.length } ?: return null
        val rest = container.removePrefix(mount.container).trimStart('/')
        return if (rest.isEmpty()) mount.host else mount.host.resolve(rest).normalize()
    }
}

/** Finds the [DockerTarget]s of a root in the Compose files of its directory and of its ancestors up to the content root. */
object DockerTargets {
    private val VARIABLE = Regex("""^\$\{?([A-Za-z_][A-Za-z0-9_]*)""")
    private val DEFAULTED = Regex("""^\$\{[A-Za-z_][A-Za-z0-9_]*:?-""")
    private val OTHER_TOOLS = listOf("lint", "molecule", "galaxy", "vault", "doc", "test")

    /** 0 for a `…playbook…` service, 1 for another `…ansible…` one, 2 for unrelated names, 3 for other Ansible tools (lint, molecule, …). */
    fun rank(service: String): Int {
        val name = service.lowercase()
        val otherTool = OTHER_TOOLS.any { it in name }
        return when {
            "playbook" in name -> 0
            otherTool -> 3
            "ansible" in name -> 1
            else -> 2
        }
    }

    /** The services of one Compose file that mount [rootDir], best first. */
    fun of(composeFile: Path, services: List<ComposeVolumes.Service>, rootDir: Path, home: Path?): List<DockerTarget> {
        val base = composeFile.parent ?: return emptyList()
        return services.mapNotNull { service ->
            val mounts = service.volumes.mapNotNull { volume -> hostPath(volume.host, base, home)?.let { DockerTarget.Mount(it, volume.container) } }
            if (mounts.none { rootDir.normalize().startsWith(it.host) }) return@mapNotNull null
            DockerTarget(
                composeFile = composeFile,
                service = service.name,
                mounts = mounts,
                vaultFileVariable = variableMountedAt(service, "ANSIBLE_VAULT_PASSWORD_FILE"),
                sshSocketVariable = variableMountedAt(service, "SSH_AUTH_SOCK"),
                callbackPlugins = service.environment["ANSIBLE_CALLBACK_PLUGINS"],
                variables = service.volumes.mapNotNull { volume ->
                    VARIABLE.find(volume.host)?.groupValues?.get(1)?.let { DockerTarget.VolumeVariable(it, volume.container, DEFAULTED.containsMatchIn(volume.host)) }
                },
            )
        }.sortedBy { it.rank }
    }

    /** Read action: the targets of [rootDir], best first. */
    fun find(project: Project, rootDir: VirtualFile, home: Path?): List<DockerTarget> {
        val root = rootDir.toNioPathOrNull() ?: return emptyList()
        val psi = PsiManager.getInstance(project)
        return directoriesUpToContentRoot(project, rootDir)
            .flatMap { dir -> dir.children.filter { !it.isDirectory && ContainerPathMapper.isComposeFileName(it.name) }.sortedBy { it.name } }
            .flatMap { file ->
                val yaml = psi.findFile(file) as? YAMLFile ?: return@flatMap emptyList()
                val path = file.toNioPathOrNull() ?: return@flatMap emptyList()
                of(path, ComposeVolumes.services(PsiYValueAdapter.documentValue(yaml)), root, home)
            }
            .sortedBy { it.rank }
    }

    private fun variableMountedAt(service: ComposeVolumes.Service, variable: String): String? {
        val target = service.environment[variable]?.let { ContainerPathMapper.normalize(it.trim()) } ?: return null
        val volume = service.volumes.firstOrNull { it.container == target } ?: return null
        return VARIABLE.find(volume.host)?.groupValues?.get(1)
    }

    private fun hostPath(host: String, base: Path, home: Path?): Path? {
        if (host.startsWith("$")) return null
        return try {
            when {
                host == "~" -> home
                host.startsWith("~/") -> home?.resolve(host.removePrefix("~/"))
                else -> base.resolve(host)
            }?.normalize()
        } catch (_: InvalidPathException) {
            null
        }
    }

    private fun directoriesUpToContentRoot(project: Project, start: VirtualFile): List<VirtualFile> {
        val stop = ProjectFileIndex.getInstance(project).getContentRootForFile(start)
            ?: project.guessProjectDir()?.takeIf { VfsUtilCore.isAncestor(it, start, false) }
            ?: return listOf(start)
        return generateSequence(start) { if (it == stop) null else it.parent }.toList()
    }
}
