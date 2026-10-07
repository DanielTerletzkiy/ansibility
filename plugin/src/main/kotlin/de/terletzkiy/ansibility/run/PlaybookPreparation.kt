package de.terletzkiy.ansibility.run

import com.intellij.execution.ExecutionException
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.util.EnvironmentUtil
import de.terletzkiy.ansibility.api.VaultFailure
import de.terletzkiy.ansibility.api.VaultOperations
import de.terletzkiy.ansibility.api.VaultUnlockResult
import de.terletzkiy.ansibility.run.become.BecomePasswords
import de.terletzkiy.ansibility.run.events.RunCallback
import de.terletzkiy.ansibility.semantics.layout.CfgSyntax
import de.terletzkiy.ansibility.run.become.BecomeResult
import de.terletzkiy.ansibility.run.settings.RunnerRootSettings
import de.terletzkiy.ansibility.vault.identity.VaultIdentityRegistry
import de.terletzkiy.ansibility.vault.secrets.VaultSecretsService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly
import java.io.File
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * A run ready to start: the process, the lines printed before the command, the secrets [close] deletes when it ends,
 * and, when the run reports its events, their [events] setup.
 */
class PreparedRun(
    val process: PlaybookProcess,
    val secrets: RunSecrets,
    val header: List<String>,
    val events: RunEventsSetup? = null,
    /** The runner settings of the root the run was prepared with. */
    val runner: RunnerRootSettings = RunnerRootSettings.DEFAULT,
) : AutoCloseable {
    override fun close() = secrets.close()
}

/** How a run reports its events: the [token] of its frames, and the host file of a path Ansible reports (null: unknown). */
class RunEventsSetup(val token: String, val hostPath: (String) -> Path?)

/**
 * Turns a [PlaybookRunSpec] into a [PreparedRun]: asks for the environment when the root has several and none is
 * chosen, confirms production-like environments, checks the checkout against its upstream branch, gets the become
 * password, unlocks the root's vault ids, and builds the native or Compose command with the root's runner settings
 * (connection, run metadata, environment and Compose variables). A play or role run is the whole playbook with the
 * `--tags` the dialog chose for it.
 */
object PlaybookPreparation {
    private val PRODUCTION = Regex("(^|[^a-z])(prod|production|prd|live)([^a-z]|$)", RegexOption.IGNORE_CASE)
    private val DOCKER_DIRS = listOf("/usr/local/bin", "/opt/homebrew/bin", "/Applications/Docker.app/Contents/Resources/bin")

    @TestOnly
    @Volatile
    internal var dockerForTests: (() -> Path?)? = null

    @TestOnly
    @Volatile
    internal var gitForTests: (() -> Path?)? = null

    /** Whether running against [environment] asks for a confirmation first (not in check mode). */
    fun isProductionLike(environment: String): Boolean = PRODUCTION.containsMatchIn(environment)

    /** Null when the user cancels one of the prompts. Blocks: on the EDT behind a modal progress. */
    fun prepare(project: Project, spec: PlaybookRunSpec): PreparedRun? {
        val title = AnsibilityRunBundle.message("run.progress.prepare")
        return if (ApplicationManager.getApplication().isDispatchThread) {
            runWithModalProgressBlocking(project, title) { prepareAsync(project, spec) }
        } else {
            runBlocking { prepareAsync(project, spec) }
        }
    }

    private suspend fun prepareAsync(project: Project, spec: PlaybookRunSpec): PreparedRun? {
        val file = withContext(Dispatchers.IO) { LocalFileSystem.getInstance().refreshAndFindFileByPath(spec.playbook) }
            ?: throw ExecutionException(AnsibilityRunBundle.message("run.error.playbook.missing", spec.playbook))
        val context = PlaybookRunContext.collect(project, file)
            ?: throw ExecutionException(AnsibilityRunBundle.message("run.error.no.root", spec.playbook))

        var environmentId = spec.environment?.takeIf { context.environment(it) != null } ?: context.environments.singleOrNull()?.id
        if (environmentId == null && context.environments.size > 1) {
            environmentId = onEdt { EnvironmentChoiceDialog.choose(project, context) } ?: return null
        }
        val environment = context.environment(environmentId)
        if (environment != null && !spec.check && isProductionLike(environment.id)) {
            val confirmed = onEdt {
                MessageDialogBuilder.okCancel(
                    AnsibilityRunBundle.message("run.confirm.production.title"),
                    AnsibilityRunBundle.message("run.confirm.production.message", environment.label, context.playbook.name),
                ).yesText(AnsibilityRunBundle.message("run.confirm.production.run")).asWarning().ask(project)
            }
            if (!confirmed) return null
        }

        val runner = context.runner(project)
        val header = ArrayList<String>()
        if (spec.target.kind != TargetKind.PLAYBOOK) {
            header += AnsibilityRunBundle.message("run.header.target", TargetChoice.describe(spec.target), spec.tags.ifBlank { "-" })
        }
        environment?.let { header += AnsibilityRunBundle.message("run.header.environment", it.label) }
        if (runner.checkFreshness && !spec.skipFreshnessCheck && !spec.check && !checkFreshness(project, context, runner, header)) return null

        val become = if (spec.become ?: context.becomeByDefault(project, environment?.id, spec.target)) {
            when (val result = BecomePasswords.getInstance(project).obtain(context.becomeRoot, runner, environment?.id)) {
                is BecomeResult.Obtained -> {
                    header += AnsibilityRunBundle.message("run.header.become", result.origin)
                    result.password
                }
                BecomeResult.Cancelled -> return null
            }
        } else {
            null
        }

        val executor = context.executor(spec)
        val dockerTarget = if (executor == PlaybookExecutor.DOCKER) {
            context.dockerTarget(spec) ?: run {
                become?.fill('\u0000')
                throw ExecutionException(AnsibilityRunBundle.message("run.error.docker.no.service", context.root.displayName))
            }
        } else {
            null
        }
        val token = if (runner.runView) RunCallback.newToken() else null
        val secrets = try {
            secretsFor(project, context.root, header, become, vaultPlaceholder = dockerTarget?.vaultFileVariable != null, callback = token != null)
        } finally {
            become?.fill('\u0000')
        }

        try {
            val playbook = context.playbookPath
            val additions = additions(context, runner, header).let { base ->
                if (token == null) base else base.copy(environment = base.environment + callbackEnvironment(context, runner, dockerTarget, secrets, token))
            }
            val inventories = environment?.inventories.orEmpty()
            val process = if (dockerTarget != null) {
                docker(context, dockerTarget, spec, playbook, inventories, secrets, additions, header)
            } else {
                native(project, context, spec, playbook, inventories, secrets, additions, header)
            }
            val events = token?.let {
                RunEventsSetup(it) { path -> if (dockerTarget != null) dockerTarget.hostPath(path) else runCatching { Path.of(path) }.getOrNull() }
            }
            return PreparedRun(process, secrets, header, events)
        } catch (e: Throwable) {
            secrets.close()
            throw e
        }
    }

    /**
     * Unlocks [root]'s vault ids (a header line names them, or why they stayed locked) and writes the run's secrets:
     * the vault client, the [become] password script, the vault [vaultPlaceholder] and the events [callback].
     */
    internal suspend fun secretsFor(
        project: Project,
        root: de.terletzkiy.ansibility.api.AnsibleRoot,
        header: MutableList<String>,
        become: CharArray?,
        vaultPlaceholder: Boolean,
        callback: Boolean,
        /** False: unlock only what needs no question (files, password managers, remembered passwords), skip the rest. */
        askForVault: Boolean = true,
    ): RunSecrets = try {
        val unlock = if (askForVault) VaultOperations.getInstance(project).unlock(root)
        else VaultSecretsService.getInstance(project).unlock(root, interactive = false)
        when (unlock) {
            is VaultUnlockResult.Unlocked ->
                if (unlock.identities.isNotEmpty()) header += AnsibilityRunBundle.message("run.header.vault", unlock.identities.joinToString())
            is VaultUnlockResult.Failed -> if (unlock.failure != VaultFailure.NO_IDENTITY) {
                header += if (askForVault) AnsibilityRunBundle.message("run.header.vault.failed", unlock.failure.name.lowercase())
                else AnsibilityRunBundle.message("run.header.vault.unasked")
            }
        }
        val discovery = VaultIdentityRegistry.getInstance(project).discovery(root)
        withContext(Dispatchers.IO) {
            VaultSecretsService.getInstance(project).lease(discovery).use { lease ->
                RunSecrets.create(secretsBase(), lease.secrets, become, vaultPlaceholder, if (callback) RunCallback.source() else null)
            }
        }
    } catch (e: IOException) {
        throw ExecutionException(AnsibilityRunBundle.message("run.error.secrets", e.message.orEmpty()), e)
    }

    /** False when the checkout is behind (or could not be compared) and the user does not want to run anyway. */
    private suspend fun checkFreshness(project: Project, context: PlaybookRunContext, runner: RunnerRootSettings, header: MutableList<String>): Boolean {
        val result = withContext(Dispatchers.IO) {
            val git = (gitForTests ?: GitChecks::locateGit)() ?: return@withContext Freshness.NotARepository
            GitChecks(git, context.rootPath).freshness(runner.freshnessBranch)
        }
        return when (result) {
            Freshness.NotARepository -> true
            is Freshness.NoRemote -> {
                header += AnsibilityRunBundle.message("run.header.fresh.no.remote", result.remote)
                true
            }
            is Freshness.UpToDate -> {
                header += AnsibilityRunBundle.message("run.header.fresh", result.branch, result.upstream)
                true
            }
            is Freshness.Unverified -> confirm(
                project,
                AnsibilityRunBundle.message("run.fresh.unverified.title"),
                AnsibilityRunBundle.message("run.fresh.unverified.message", result.upstream, result.reason),
            ).also { if (it) header += AnsibilityRunBundle.message("run.header.fresh.unverified", result.upstream, result.reason) }
            is Freshness.Behind -> {
                val more = result.count - result.commits.size
                val commits = result.commits.joinToString("\n") + if (more > 0) "\n" + AnsibilityRunBundle.message("run.fresh.behind.more", more) else ""
                confirm(
                    project,
                    AnsibilityRunBundle.message("run.fresh.behind.title"),
                    AnsibilityRunBundle.message("run.fresh.behind.message", result.branch, result.count, result.upstream, commits),
                ).also { if (it) header += AnsibilityRunBundle.message("run.header.fresh.behind", result.branch, result.count, result.upstream) }
            }
        }
    }

    private suspend fun confirm(project: Project, title: String, message: String): Boolean = onEdt {
        MessageDialogBuilder.okCancel(title, message).yesText(AnsibilityRunBundle.message("run.fresh.run.anyway")).asWarning().ask(project)
    }

    /** The connection, run metadata and variables of the root's runner settings. */
    private suspend fun additions(context: PlaybookRunContext, runner: RunnerRootSettings, header: MutableList<String>): RunAdditions {
        val environment = LinkedHashMap<String, String>()
        if (runner.runMetadata) {
            val info = withContext(Dispatchers.IO) {
                (gitForTests ?: GitChecks::locateGit)()?.let { git -> GitChecks(git, context.rootPath).takeIf { it.isRepository() }?.info() }
            }
            if (info != null) {
                environment["GIT_URL"] = info.url
                environment["GIT_COMMIT"] = info.commit
                environment["GIT_BRANCH"] = info.branch
            }
            environment["PROVISION_USER"] = runner.remoteUser.trim().ifEmpty { System.getProperty("user.name").orEmpty() }
        }
        environment += runner.environmentVariables
        describeConnection(runner)?.let { header += AnsibilityRunBundle.message("run.header.connection", it) }
        return RunAdditions(RunConnection.extraVarsArgument(runner), environment, runner.composeVariables)
    }

    /**
     * The token of the run's events and `ANSIBLE_CALLBACK_PLUGINS` with the callback's directory added to the configured
     * paths: the runner's environment variable, else the Compose service's (in a container) or the IDE's, else
     * `ansible.cfg`'s `callback_plugins` (`ANSIBLE_CONFIG`'s file for a local run, else the root's).
     */
    internal suspend fun callbackEnvironment(
        context: PlaybookRunContext,
        runner: RunnerRootSettings,
        target: DockerTarget?,
        secrets: RunSecrets,
        token: String,
    ): Map<String, String> {
        val ours = if (target != null) "${PlaybookCommand.CONTAINER_SECRETS}/${RunSecrets.CALLBACKS}" else secrets.callbackDir?.toString() ?: return emptyMap()
        val environment = runner.environmentVariables[RunCallback.PLUGINS_VARIABLE]
            ?: if (target != null) target.callbackPlugins else EnvironmentUtil.getValue(RunCallback.PLUGINS_VARIABLE)
        val config = withContext(Dispatchers.IO) { configuredCallbackPlugins(context, local = target == null) }
        return mapOf(RunCallback.TOKEN_VARIABLE to token, RunCallback.PLUGINS_VARIABLE to RunCallback.pluginPath(environment, config, ours))
    }

    /**
     * `callback_plugins` of the `ansible.cfg` the run reads. Relative paths resolve against the file's directory: a
     * local run gets them absolute; in a container the run's working directory is the root, which holds the file.
     */
    internal fun configuredCallbackPlugins(context: PlaybookRunContext, local: Boolean): String? {
        val custom = if (local) EnvironmentUtil.getValue("ANSIBLE_CONFIG")?.let { runCatching { Path.of(it) }.getOrNull() }?.takeIf { it.isRegularFile() } else null
        val file = custom ?: context.rootPath.resolve("ansible.cfg").takeIf { it.isRegularFile() } ?: return null
        val value = try {
            CfgSyntax.read(file.readText()).document.value("defaults", "callback_plugins")
        } catch (_: IOException) {
            null
        } ?: return null
        if (!local) return value
        val dir = file.parent ?: return value
        return value.split(':').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(":") { path ->
            if (path.startsWith("/") || path.startsWith("~") || path.startsWith("$")) path else dir.resolve(path).normalize().toString()
        }
    }

    /** "as jane, via jump host jane@proxy:2222, without host key checks"; null when the settings change nothing. */
    fun describeConnection(runner: RunnerRootSettings): String? {
        if (!runner.changesConnection) return null
        val parts = ArrayList<String>()
        runner.remoteUser.trim().takeIf { it.isNotEmpty() }?.let { parts += AnsibilityRunBundle.message("run.connection.user", it) }
        val jump = runner.jumpHost
        if (jump.enabled && jump.host.isNotBlank()) {
            val user = jump.user.trim().ifEmpty { runner.remoteUser.trim() }
            val target = (if (user.isEmpty()) "" else "$user@") + jump.host.trim() + jump.port.trim().takeIf { it.isNotEmpty() }?.let { ":$it" }.orEmpty()
            parts += AnsibilityRunBundle.message("run.connection.jump", target)
        }
        if (runner.skipHostKeyChecking) parts += AnsibilityRunBundle.message("run.connection.no.host.keys")
        return parts.joinToString(", ")
    }

    private fun native(
        project: Project,
        context: PlaybookRunContext,
        spec: PlaybookRunSpec,
        playbook: Path,
        inventories: List<Path>,
        secrets: RunSecrets,
        additions: RunAdditions,
        header: MutableList<String>,
    ): PlaybookProcess {
        val executable = context.nativeExecutable
            ?: throw ExecutionException(AnsibilityRunBundle.message("run.error.native.missing", project.name))
        header += AnsibilityRunBundle.message("run.header.native", executable.toString())
        val path = EnvironmentUtil.getValue("PATH") ?: System.getenv("PATH").orEmpty()
        return PlaybookCommand.native(executable, spec, context.rootPath, playbook, inventories, secrets, path, additions)
    }

    private suspend fun docker(
        context: PlaybookRunContext,
        target: DockerTarget,
        spec: PlaybookRunSpec,
        playbook: Path,
        inventories: List<Path>,
        secrets: RunSecrets,
        additions: RunAdditions,
        header: MutableList<String>,
    ): PlaybookProcess {
        val docker = withContext(Dispatchers.IO) { (dockerForTests ?: ::locateDocker)() }
            ?: throw ExecutionException(AnsibilityRunBundle.message("run.error.docker.missing"))
        val inherited = EnvironmentUtil.getEnvironmentMap()
        val envFile = context.envFile(spec)
        val home = System.getProperty("user.home")
        val composeEnvironment = envFile?.let { file ->
            withContext(Dispatchers.IO) {
                try {
                    EnvFiles.parse(file.readText(), inherited, home)
                } catch (e: IOException) {
                    throw ExecutionException(AnsibilityRunBundle.message("run.error.env.file", file.toString(), e.message.orEmpty()), e)
                }
            }
        }.orEmpty()
        val knownHosts = home?.let { Path.of(it, ".ssh", "known_hosts") }?.takeIf { withContext(Dispatchers.IO) { it.isRegularFile() } }
        header += AnsibilityRunBundle.message("run.header.docker", target.presentable())
        envFile?.let { header += AnsibilityRunBundle.message("run.header.env.file", it.toString()) }
        val process = PlaybookCommand.docker(
            docker, target, spec, context.rootPath, playbook, inventories, secrets, composeEnvironment, inherited, SystemInfo.isMac, additions,
            knownHosts?.toString(),
        ) ?: throw ExecutionException(AnsibilityRunBundle.message("run.error.docker.unmounted", target.presentable()))
        val missing = PlaybookCommand.missingVariables(target, process, inherited)
        if (missing.isNotEmpty()) {
            throw ExecutionException(AnsibilityRunBundle.message("run.error.docker.variables", target.presentable(), missing.joinToString(), context.root.displayName))
        }
        return process
    }

    /** The `docker` CLI on the login shell's `PATH` or in the usual install directories. */
    fun locateDocker(): Path? {
        val path = EnvironmentUtil.getValue("PATH") ?: System.getenv("PATH").orEmpty()
        val home = System.getProperty("user.home")
        val dirs = path.split(File.pathSeparatorChar) + DOCKER_DIRS + listOfNotNull(home?.let { "$it/.docker/bin" }, home?.let { "$it/.orbstack/bin" })
        return dirs.asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { Path.of(it, "docker") }.getOrNull() }
            .firstOrNull { it.isExecutable() }
    }

    internal fun secretsBase(): Path = PathManager.getSystemDir().resolve("ansibility").resolve("run")

    private suspend fun <T> onEdt(block: () -> T): T = withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) { block() }
}
