package de.terletzkiy.ansibility.runtime

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import de.terletzkiy.ansibility.api.LocalAnsibleInstall
import de.terletzkiy.ansibility.api.LocalAnsibleRuntime
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly
import java.io.File
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/** The Ansible command-line tools the plugin runs. */
enum class AnsibleTool(val executableName: String) {
    ANSIBLE("ansible"),
    ANSIBLE_DOC("ansible-doc"),
    ANSIBLE_INVENTORY("ansible-inventory"),
}

/**
 * Application service that finds the Ansible tools and probes the local install (plan A.8, A.11, D10).
 *
 * Lookup order: the executable chosen in the settings ([AnsibleRuntimeOptions.explicitExecutable]), the project
 * SDK's `bin` directory (a Python virtualenv's interpreter lives there), every `PATH` entry of the shell
 * environment ([EnvironmentUtil]), then `/opt/homebrew/bin` and `/usr/local/bin`, which GUI launches on macOS do
 * not inherit. Probes (`ansible --version`) run through [ProcessRunner] and are cached per executable (its real
 * path and time stamp, so an upgrade is noticed) and `ansible.cfg`.
 */
@Service(Service.Level.APP)
class AnsibleToolchain(private val scope: CoroutineScope) {
    private val probes = ConcurrentHashMap<ProbeKey, Deferred<LocalAnsibleInstall?>>()
    private val projectProbes = ConcurrentHashMap<ProjectKey, Deferred<LocalAnsibleInstall?>>()
    private val found = SimpleModificationTracker()

    /** Bumped when a background probe started by [cachedInstall] finds an install. */
    val probeTracker: ModificationTracker get() = found

    /** The first existing executable of [tool] in lookup order, or null. Reads the file system and the shell environment: call it off the EDT. */
    fun locate(tool: AnsibleTool, project: Project?): Path? = candidates(tool, project).firstOrNull(::isExecutable)

    /** Every place [locate] looks for [tool], in order, without checking that they exist. Call it off the EDT. */
    fun candidates(tool: AnsibleTool, project: Project?): List<Path> {
        val result = LinkedHashSet<Path>()
        explicitCandidate(tool)?.let(result::add)
        sdkBinDirectory(project)?.let { result.add(it.resolve(tool.executableName)) }
        for (entry in searchDirectories()) {
            if (entry.isBlank()) continue
            toPath(entry)?.let { result.add(it.resolve(tool.executableName)) }
        }
        return result.toList()
    }

    /**
     * Probes the `ansible` found for [project] with [ansibleConfig] as `ANSIBLE_CONFIG`; null when no `ansible` is
     * found or it does not answer. Concurrent and repeated calls share one probe.
     */
    suspend fun probe(project: Project?, ansibleConfig: Path? = null): LocalAnsibleInstall? {
        val ansible = withContext(Dispatchers.IO) { locate(AnsibleTool.ANSIBLE, project) } ?: return null
        val key = ProbeKey(ansible.toString(), withContext(Dispatchers.IO) { stamp(ansible) }, ansibleConfig?.toString())
        val deferred = probes.computeIfAbsent(key) { scope.async(start = CoroutineStart.LAZY) { runProbe(ansible, ansibleConfig) } }
        deferred.start()
        return deferred.await()
    }

    /**
     * Non-blocking: the finished probe for [project] (no `ansible.cfg`), or null while it runs or when nothing was
     * found. The first call starts the probe; when it finds an install, [probeTracker] is bumped and highlighting of
     * the open projects restarts.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun cachedInstall(project: Project): LocalAnsibleInstall? {
        val key = ProjectKey(AnsibleRuntimeOptions.getInstance().explicitExecutable(AnsibleTool.ANSIBLE), sdkHome(project))
        val deferred = projectProbes.computeIfAbsent(key) {
            scope.async(start = CoroutineStart.LAZY) { probe(project) }.also { started ->
                started.invokeOnCompletion { failure ->
                    if (failure == null && started.getCompleted() != null) announceProbe()
                }
            }
        }
        deferred.start()
        return if (deferred.isCompleted && !deferred.isCancelled) runCatching { deferred.getCompleted() }.getOrNull() else null
    }

    /**
     * Collection name → version of every collection the install can load, first search path wins (as for plugin
     * loading): the `ansible collection location` paths, then the `ansible_collections` next to ansible-core. Read
     * from `MANIFEST.json` (or `galaxy.yml` for source checkouts), without running Python. `ansible.builtin` is the core.
     * Reads files: call it off the EDT.
     */
    fun collectionVersions(install: LocalAnsibleInstall): Map<String, String> {
        val versions = sortedMapOf<String, String>()
        versions["ansible.builtin"] = install.coreVersion.toString()
        val sitePackages = install.moduleLocation?.let(::toPath)?.parent
        val roots = install.collectionPaths.mapNotNull(::toPath) + listOfNotNull(sitePackages)
        for (root in roots) {
            val collectionsDir = if (root.name == COLLECTIONS_DIR) root else root.resolve(COLLECTIONS_DIR)
            if (!collectionsDir.isDirectory()) continue
            for (namespace in list(collectionsDir)) {
                if (!namespace.isDirectory() || namespace.name.contains('.')) continue
                for (collection in list(namespace)) {
                    if (!collection.isDirectory()) continue
                    val name = "${namespace.name}.${collection.name}"
                    if (name in versions) continue
                    readCollectionVersion(collection)?.let { versions[name] = it }
                }
            }
        }
        return versions
    }

    /** Replaces `PATH` and the fallback directories (tests make the lookup independent of the machine). */
    @Volatile
    internal var searchDirectoriesOverride: List<String>? = null

    /** `PATH` of the shell environment, then [FALLBACK_DIRECTORIES]. */
    private fun searchDirectories(): List<String> {
        searchDirectoriesOverride?.let { return it }
        val path = EnvironmentUtil.getValue("PATH") ?: System.getenv("PATH").orEmpty()
        val fallbacks = if (SystemInfo.isWindows) emptyList() else FALLBACK_DIRECTORIES
        return path.split(File.pathSeparatorChar) + fallbacks
    }

    /** Forgets every probe (the application outlives a test). */
    @TestOnly
    internal fun resetForTests() {
        probes.clear()
        projectProbes.clear()
        searchDirectoriesOverride = null
    }

    private suspend fun runProbe(ansible: Path, ansibleConfig: Path?): LocalAnsibleInstall? {
        val result = ProcessRunner.getInstance().run(ToolCommand(ansible, listOf("--version"), ansibleConfig))
        if (!result.isSuccess) return null
        return parseVersion(result.stdout, ansible.toString()).also {
            if (it == null) LOG.info("Unexpected `$ansible --version` output: ${result.stdout.take(200)}")
        }
    }

    private fun announceProbe() {
        found.incModificationCount()
        for (project in ProjectManager.getInstance().openProjects) {
            if (!project.isDisposed) DaemonCodeAnalyzer.getInstance(project).restart("ansibility: local ansible probed")
        }
    }

    private fun explicitCandidate(tool: AnsibleTool): Path? {
        val configured = AnsibleRuntimeOptions.getInstance().explicitExecutable(tool)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val path = toPath(configured) ?: return null
        return when {
            path.isDirectory() -> path.resolve(tool.executableName)
            path.name == tool.executableName -> path
            else -> path.resolveSibling(tool.executableName)
        }
    }

    private fun sdkBinDirectory(project: Project?): Path? = sdkHome(project)?.let(::toPath)?.parent

    private fun sdkHome(project: Project?): String? =
        if (project == null || project.isDisposed) null else ProjectRootManager.getInstance(project).projectSdk?.homePath

    private data class ProbeKey(val executable: String, val stamp: String, val config: String?)

    private data class ProjectKey(val explicit: String?, val sdkHome: String?)

    companion object {
        private val LOG = logger<AnsibleToolchain>()
        private const val COLLECTIONS_DIR = "ansible_collections"

        /** Where Homebrew and other installers put the tools; GUI launches on macOS lack them on `PATH`. */
        val FALLBACK_DIRECTORIES: List<String> = listOf("/opt/homebrew/bin", "/usr/local/bin")

        private val VERSION_LINE = Regex("""^ansible(?:-[a-z]+)?\s+(?:\[core\s+)?(\d+\.\d+(?:\.\d+)?)""")
        private val GALAXY_VERSION = Regex("""^version:\s*['"]?([^'"\s#]+)""", RegexOption.MULTILINE)

        fun getInstance(): AnsibleToolchain = service()

        /**
         * Reads `ansible --version`: `ansible [core 2.21.4]` (or `ansible 2.9.27`), then `ansible python module
         * location = …`, `ansible collection location = a:b` and `executable location = …`.
         */
        fun parseVersion(output: String, executable: String): LocalAnsibleInstall? {
            val lines = output.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.toList()
            val version = lines.firstOrNull()?.let { VERSION_LINE.find(it.trim()) }?.groupValues?.get(1)?.let(CoreVersion::parse)
                ?: return null
            val fields = lines.drop(1).mapNotNull { line ->
                val eq = line.indexOf(" = ")
                if (eq < 0) null else line.substring(0, eq).trim() to line.substring(eq + 3).trim()
            }.toMap()
            return LocalAnsibleInstall(
                coreVersion = version,
                executable = fields["executable location"]?.takeIf { it.isNotEmpty() } ?: executable,
                moduleLocation = fields["ansible python module location"]?.takeIf { it.isNotEmpty() },
                collectionPaths = fields["ansible collection location"]
                    ?.split(File.pathSeparatorChar)?.map(String::trim)?.filter(String::isNotEmpty).orEmpty(),
            )
        }

        internal fun isExecutable(path: Path): Boolean = Files.isRegularFile(path) && Files.isExecutable(path)

        /** A collection's version from `MANIFEST.json` (`collection_info.version`) or `galaxy.yml`. */
        internal fun readCollectionVersion(collectionDir: Path): String? {
            val manifest = collectionDir.resolve("MANIFEST.json")
            if (Files.isRegularFile(manifest)) {
                val info = runCatching { Json.parseObject(Files.readString(manifest))["collection_info"] as? Map<*, *> }.getOrNull()
                return info?.get("version")?.toString()
            }
            val galaxy = collectionDir.resolve("galaxy.yml")
            if (Files.isRegularFile(galaxy)) {
                return runCatching { GALAXY_VERSION.find(Files.readString(galaxy))?.groupValues?.get(1) }.getOrNull()
            }
            return null
        }

        private fun stamp(executable: Path): String = runCatching {
            val real = executable.toRealPath()
            "$real@${Files.getLastModifiedTime(real).toMillis()}"
        }.getOrDefault("")

        private fun list(dir: Path): List<Path> = runCatching { Files.list(dir).use { stream -> stream.sorted().toList() } }.getOrDefault(emptyList())

        private fun toPath(text: String): Path? = try {
            Path.of(text)
        } catch (_: InvalidPathException) {
            null
        }
    }
}

/** The [LocalAnsibleRuntime] of the plugin: [AnsibleToolchain]'s cached probe, gated by [AnsibleRuntimeOptions.localTargetProbe]. */
class LocalAnsibleRuntimeImpl : LocalAnsibleRuntime {
    override fun localInstall(project: Project): LocalAnsibleInstall? =
        if (AnsibleRuntimeOptions.getInstance().localTargetProbe) AnsibleToolchain.getInstance().cachedInstall(project) else null

    override val probeTracker: ModificationTracker = ModificationTracker {
        AnsibleToolchain.getInstance().probeTracker.modificationCount + AnsibleRuntimeOptions.getInstance().tracker.modificationCount
    }
}
