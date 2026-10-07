package de.terletzkiy.ansibility.run

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.psi.PsiManager
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.VaultSourceKind
import de.terletzkiy.ansibility.api.ProjectLayoutService
import de.terletzkiy.ansibility.context.host.AnsibleContextServiceImpl
import de.terletzkiy.ansibility.context.host.symbols.HostSymbols
import de.terletzkiy.ansibility.index.AnsibleIndexQueries
import de.terletzkiy.ansibility.run.become.BecomeRoot
import de.terletzkiy.ansibility.run.settings.RunnerRootSettings
import de.terletzkiy.ansibility.run.settings.RunnerSettings
import de.terletzkiy.ansibility.runtime.AnsibleTool
import de.terletzkiy.ansibility.runtime.AnsibleToolchain
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.yaml.psi.YAMLFile
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/** One environment a playbook can run against: its inventory sources and, for completion, its hosts and groups. */
class RunEnvironment(val id: String, val label: String, val inventories: List<Path>, val hosts: List<String>, val groups: List<String>)

/**
 * One part of the playbook the run dialog offers: the whole playbook, a play, or a role of a play ([text] as shown),
 * with the [selection] that selects it through `--tags` (null for the whole playbook).
 */
class TargetChoice(val target: PlaybookTarget, val text: String, val selection: TagSelection? = null, val usesBecome: Boolean = false) {
    override fun toString(): String = text

    companion object {
        /** The whole playbook, then each play followed by its roles. */
        fun of(plays: List<PlaybookPlay>): List<TargetChoice> {
            fun choice(target: PlaybookTarget, text: String) =
                TargetChoice(target, text, PlaybookParts.selection(plays, target), PlaybookParts.usesBecome(plays, target))
            return listOf(choice(PlaybookTarget.PLAYBOOK, AnsibilityRunBundle.message("run.target.playbook"))) + plays.flatMap { play ->
                val playText = AnsibilityRunBundle.message(if (play.isImport) "run.target.import" else "run.target.play", play.label)
                listOf(choice(play.target, playText)) + play.roles.map { role ->
                    choice(role.target, AnsibilityRunBundle.message(if (role.fromTask) "run.target.role.task" else "run.target.role", role.name, role.section))
                }
            }
        }

        /** How a header or a configuration name refers to [target]: "play System", "role nginx of play Web". */
        fun describe(target: PlaybookTarget): String = when (target.kind) {
            TargetKind.PLAYBOOK -> AnsibilityRunBundle.message("run.describe.playbook")
            TargetKind.PLAY -> AnsibilityRunBundle.message("run.describe.play", target.playName.ifEmpty { "#${target.playIndex + 1}" })
            TargetKind.ROLE -> AnsibilityRunBundle.message("run.describe.role", target.roleName, target.playName.ifEmpty { "#${target.playIndex + 1}" })
        }
    }
}

/**
 * What the run dialog and the run itself need to know about a playbook: its root (the one holding the inventories
 * and `ansible.cfg`, the working directory), the environments, the context selection, the tags written in the
 * root, the local `ansible-playbook` and the Compose services that mount the root.
 */
class PlaybookRunContext(
    val playbook: VirtualFile,
    val playbookPath: Path,
    val root: AnsibleRoot,
    val rootPath: Path,
    val environments: List<RunEnvironment>,
    val tags: List<String>,
    val selectedEnvironment: String?,
    val selectedHost: String?,
    val nativeExecutable: Path?,
    val dockerTargets: List<DockerTarget>,
    val defaultEnvFile: Path?,
    /** The key of [root] in the runner settings. */
    val rootKey: String = "",
    /** The parts of the playbook a run can be limited to, the whole playbook first. */
    val targets: List<TargetChoice> = listOf(TargetChoice(PlaybookTarget.PLAYBOOK, "")),
) {
    fun environment(id: String?): RunEnvironment? = environments.firstOrNull { it.id == id }

    fun target(target: PlaybookTarget): TargetChoice? = targets.firstOrNull { it.target.sameAs(target) }

    val becomeRoot: BecomeRoot get() = BecomeRoot(rootPath, root.displayName)

    /** The root's runner settings as they are now (they may change while the dialog is open). */
    fun runner(project: Project): RunnerRootSettings = RunnerSettings.getInstance(project).rootSettings(rootKey)

    /**
     * Whether a run passes the become password when the configuration leaves it automatic: when the runner settings
     * name a source for [environment] (not "ask"), or when [target] becomes another user (`become:` in the playbook).
     */
    fun becomeByDefault(project: Project, environment: String?, target: PlaybookTarget): Boolean {
        val kind = runner(project).becomeSource(environment)?.kind
        return (kind != null && kind != VaultSourceKind.PROMPT) || target(target)?.usesBecome == true
    }

    /** The environment to start with: the context's, else the only one; null when the user has to choose. */
    fun preselectedEnvironment(): String? = selectedEnvironment?.takeIf { environment(it) != null } ?: environments.singleOrNull()?.id

    /** Whether [spec] leaves the environment open although the root has several. */
    fun lacksEnvironment(spec: PlaybookRunSpec): Boolean = environments.size > 1 && environment(spec.environment) == null

    /** The Compose service [spec] names, else the best discovered one. */
    fun dockerTarget(spec: PlaybookRunSpec): DockerTarget? {
        if (spec.composeService.isBlank()) return dockerTargets.firstOrNull()
        return dockerTargets.firstOrNull { it.service == spec.composeService && (spec.composeFile.isBlank() || it.composeFile.toString() == spec.composeFile) }
    }

    /** [spec]'s executor with AUTO decided: an Ansible Compose service, else a local install, else any Compose service. */
    fun executor(spec: PlaybookRunSpec): PlaybookExecutor = when (spec.executor) {
        PlaybookExecutor.NATIVE, PlaybookExecutor.DOCKER -> spec.executor
        PlaybookExecutor.AUTO -> when {
            dockerTargets.any { it.rank <= 1 } -> PlaybookExecutor.DOCKER
            nativeExecutable != null -> PlaybookExecutor.NATIVE
            dockerTargets.isNotEmpty() -> PlaybookExecutor.DOCKER
            else -> PlaybookExecutor.NATIVE
        }
    }

    /** [spec]'s env file, else `<root>/.env.local` when it exists. */
    fun envFile(spec: PlaybookRunSpec): Path? = spec.envFile.trim().takeIf { it.isNotEmpty() }?.let { rootPath.resolve(it) } ?: defaultEnvFile

    /** The spec a first run of this playbook starts from: the preselected environment and the context's host as the limit. */
    fun initialSpec(target: PlaybookTarget = PlaybookTarget.PLAYBOOK): PlaybookRunSpec = withTarget(
        PlaybookRunSpec(playbook = playbookPath.toString(), environment = preselectedEnvironment(), limit = selectedHost.orEmpty()),
        target,
    )

    /**
     * [spec] limited to [target]: a play or role gets the tags that select it (with the tags still to add); the whole
     * playbook keeps the tags of a whole-playbook spec and drops those of a play or role.
     */
    fun withTarget(spec: PlaybookRunSpec, target: PlaybookTarget): PlaybookRunSpec {
        if (target.kind == TargetKind.PLAYBOOK) return spec.copy(target = target, tags = if (spec.target.kind == TargetKind.PLAYBOOK) spec.tags else "")
        val selection = target(target)?.selection ?: return spec.copy(target = target)
        return spec.copy(target = target, tags = selection.tagsWithAdditions.joinToString(","))
    }

    companion object {
        const val DEFAULT_ENV_FILE = ".env.local"

        /** Collects the context of [playbook]; null when it is not in an Ansible root. Do not call on the EDT. */
        suspend fun collect(project: Project, playbook: VirtualFile): PlaybookRunContext? {
            val home = System.getProperty("user.home")?.let { runCatching { Path.of(it) }.getOrNull() }
            val partial = readAction { readPart(project, playbook, home) } ?: return null
            return withContext(Dispatchers.IO) {
                val native = AnsibleToolchain.getInstance().locate(AnsibleTool.ANSIBLE_PLAYBOOK, project)
                val envFile = partial.rootPath.resolve(DEFAULT_ENV_FILE).takeIf { it.isRegularFile() }
                partial.copy(nativeExecutable = native, defaultEnvFile = envFile)
            }
        }

        private fun readPart(project: Project, playbook: VirtualFile, home: Path?): PlaybookRunContext? {
            if (project.isDisposed || !playbook.isValid) return null
            val playbookPath = playbook.toNioPathOrNull() ?: return null
            val ownRoot = AnsibleWorkspace.getInstance(project).rootFor(playbook) ?: return null
            val root = AnsibleContextServiceImpl.getInstance(project)?.inventoryRoot(ownRoot) ?: ownRoot
            val rootPath = root.dir.toNioPathOrNull() ?: return null
            val symbols = HostSymbols.environments(project, ownRoot).associateBy { it.name }
            val environments = ProjectLayoutService.getInstance(project).layout(root).inventories.map { def ->
                val graph = symbols[def.id]?.graph
                RunEnvironment(
                    id = def.id,
                    label = def.label,
                    inventories = def.sources.mapNotNull { source -> source.file?.toNioPathOrNull() ?: resolve(rootPath, source.path) },
                    hosts = graph?.hosts?.keys?.sorted().orEmpty(),
                    groups = graph?.groups?.keys?.filter { it != "all" && it != "ungrouped" }?.sorted().orEmpty(),
                )
            }
            val tags = try {
                AnsibleIndexQueries.tagNames(project, ownRoot).sorted()
            } catch (_: IndexNotReadyException) {
                emptyList()
            }
            val selection = AnsibleContextService.getInstance(project).selection(ownRoot)
            val plays = (PsiManager.getInstance(project).findFile(playbook) as? YAMLFile)?.let(PlaybookParts::plays).orEmpty()
            return PlaybookRunContext(
                playbook = playbook,
                playbookPath = playbookPath,
                root = root,
                rootPath = rootPath,
                environments = environments,
                tags = tags,
                selectedEnvironment = (selection.environment as? EnvironmentChoice.Named)?.name,
                selectedHost = selection.host,
                nativeExecutable = null,
                dockerTargets = DockerTargets.find(project, root.dir, home),
                defaultEnvFile = null,
                rootKey = RootKeys.keyOf(project, root.dir),
                targets = TargetChoice.of(plays),
            )
        }

        private fun resolve(base: Path, path: String): Path? = try {
            base.resolve(path).normalize()
        } catch (_: InvalidPathException) {
            null
        }
    }

    private fun copy(nativeExecutable: Path?, defaultEnvFile: Path?) = PlaybookRunContext(
        playbook, playbookPath, root, rootPath, environments, tags, selectedEnvironment, selectedHost, nativeExecutable, dockerTargets, defaultEnvFile,
        rootKey, targets,
    )
}
