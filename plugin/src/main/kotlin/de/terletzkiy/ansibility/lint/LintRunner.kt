package de.terletzkiy.ansibility.lint

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.EnvironmentUtil
import com.intellij.util.execution.ParametersListUtil
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.runtime.AnsibleTool
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.run.DockerTarget
import de.terletzkiy.ansibility.run.DockerTargets
import de.terletzkiy.ansibility.run.PlaybookPreparation
import de.terletzkiy.ansibility.runtime.AnsibleToolchain
import de.terletzkiy.ansibility.settings.RootKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** The outcome of one ansible-lint run: its findings, or why it could not lint. */
sealed interface LintOutcome {
    data class Findings(val findings: List<LintFinding>, val where: String) : LintOutcome

    data class Failed(val reason: String) : LintOutcome
}

/**
 * Runs ansible-lint for a root (plan amendment R28): locally, or in the root's Compose service whose name contains
 * "lint" (D-L1, with `--entrypoint ansible-lint`), from the root's lint directory (the root; a nested playbook root's
 * parent), with `-f sarif` and the root's config ([configOf], as a container path in Compose). Paths in the output are
 * mapped back to the host.
 */
object LintRunner {
    /** What a run needs, resolved in a read action. */
    class Plan(val command: List<String>, val workDir: Path, val environment: Map<String, String>, val resolve: (String) -> Path?, val where: String)

    /** The directory ansible-lint runs in for [root]: its own directory, a nested playbook root's parent. */
    fun lintDir(root: AnsibleRoot): VirtualFile = if (root.kind == RootKind.NESTED_PLAYBOOK) root.parentDir ?: root.dir else root.dir

    /** The config of [root]: the setting, else a config next to the lint directory or above it, up to the repository top. */
    fun configOf(project: Project, root: AnsibleRoot, settings: LintRootSettings): VirtualFile? {
        val dir = lintDir(root)
        settings.config?.let { return dir.findFileByRelativePath(it) }
        var current: VirtualFile? = dir
        val top = RootKeys.projectDir(project)
        while (current != null) {
            AnsibleLayout.LINT_CONFIG_NAMES.firstNotNullOfOrNull { current.findChild(it)?.takeIf { f -> !f.isDirectory } }?.let { return it }
            current.findFileByRelativePath(DOT_CONFIG)?.let { return it }
            if (current.findChild(".git") != null || current == top) break
            current = current.parent
        }
        return null
    }

    /** The Compose services that can lint [root] (a "lint" service mounting its lint directory), best first. */
    fun composeTargets(project: Project, root: AnsibleRoot): List<DockerTarget> =
        DockerTargets.find(project, lintDir(root), System.getProperty("user.home")?.let(Path::of))
            .filter { "lint" in it.service.lowercase() }

    /** A local ansible-lint: the setting, next to ansible-playbook, on PATH, or in the usual places. */
    fun localExecutable(project: Project, settings: LintRootSettings): Path? {
        executableForTests?.let { return it() }
        settings.executable?.let { path -> return Path.of(path).takeIf { Files.isExecutable(it) } }
        AnsibleToolchain.getInstance().locate(AnsibleTool.ANSIBLE_PLAYBOOK, project)?.resolveSibling(EXECUTABLE)?.takeIf { Files.isExecutable(it) }?.let { return it }
        val path = EnvironmentUtil.getValue("PATH") ?: System.getenv("PATH").orEmpty()
        val home = System.getProperty("user.home").orEmpty()
        return (path.split(File.pathSeparatorChar) + listOf("/opt/homebrew/bin", "/usr/local/bin", "$home/.local/bin"))
            .asSequence().filter { it.isNotBlank() }.mapNotNull { runCatching { Path.of(it, EXECUTABLE) }.getOrNull() }
            .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
    }

    /**
     * The command that lints [files] of [root] (all in its lint directory), or a [LintOutcome.Failed] saying why none
     * can run. [fix] adds `--fix=<rule>` (the rule's transform plus ansible-lint's YAML reformatting). Read action.
     */
    fun plan(project: Project, root: AnsibleRoot, files: List<VirtualFile>, fix: String? = null): Any {
        val settings = LintSettings.getInstance(project).rootSettings(RootKeys.keyOf(project, root.dir))
        val dir = lintDir(root)
        val hostDir = dir.toNioPath()
        val common = buildList {
            addAll(listOf("-f", "sarif", "--nocolor"))
            if (settings.offline) add("--offline")
            if (fix != null) add("--fix=$fix")
            addAll(ParametersListUtil.parse(settings.extraArgs))
        }
        val targets = if (settings.executor == LintExecutor.LOCAL) emptyList() else composeTargets(project, root)
        val target = settings.service?.let { name -> targets.firstOrNull { it.service == name } } ?: targets.firstOrNull()
        if (settings.executor == LintExecutor.COMPOSE && target == null) return LintOutcome.Failed(message("lint.failed.no.service", root.displayName))
        if (target != null && settings.executor != LintExecutor.LOCAL) {
            val docker = dockerForTests?.invoke() ?: PlaybookPreparation.locateDocker() ?: return LintOutcome.Failed(message("lint.failed.no.docker"))
            val containerDir = target.containerPath(hostDir) ?: return LintOutcome.Failed(message("lint.failed.not.mounted", target.presentable()))
            val paths = files.map { file -> target.containerPath(file.toNioPath()) ?: return LintOutcome.Failed(message("lint.failed.not.mounted", target.presentable())) }
            // The image's own entrypoint is not used: some pass no arguments on (`ansible-lint -c ansible-lint.yml`
            // without "$@") and would lint the whole repository in their own output format.
            val config = configOf(project, root, settings)?.let { target.containerPath(it.toNioPath()) }
            val command = listOf(docker.toString(), "compose", "-f", target.composeFile.toString(), "run", "--rm", "-T", "-w", containerDir,
                "--entrypoint", EXECUTABLE, target.service) + common + listOfNotNull(config?.let { "-c" }, config) + paths
            val resolve: (String) -> Path? = { uri ->
                val path = uri.removePrefix("file://")
                if (path.startsWith("/")) target.hostPath(path) else hostDir.resolve(path).normalize()
            }
            return Plan(command, target.composeFile.parent ?: hostDir, emptyMap(), resolve, target.presentable())
        }
        val executable = localExecutable(project, settings) ?: return LintOutcome.Failed(message("lint.failed.no.executable"))
        val config = configOf(project, root, settings)
        val configArgs = if (config != null) listOf("-c", relative(hostDir, config.toNioPath())) else emptyList()
        val command = listOf(executable.toString()) + common + configArgs + files.map { relative(hostDir, it.toNioPath()) }
        val path = (listOf(executable.parent.toString()) + (EnvironmentUtil.getValue("PATH") ?: System.getenv("PATH").orEmpty()).split(File.pathSeparatorChar))
            .filter { it.isNotBlank() }.distinct().joinToString(File.pathSeparator)
        val resolve: (String) -> Path? = { uri ->
            val p = uri.removePrefix("file://")
            if (p.startsWith("/")) Path.of(p) else hostDir.resolve(p).normalize()
        }
        return Plan(command, hostDir, mapOf("PATH" to path, "NO_COLOR" to "1", "ANSIBLE_NOCOLOR" to "1"), resolve, executable.toString())
    }

    /** Lints [files] of [root]; cancelling the coroutine stops the process. */
    suspend fun run(project: Project, root: AnsibleRoot, files: List<VirtualFile>, fix: String? = null, timeoutMillis: Int = TIMEOUT_MILLIS): LintOutcome {
        val plan = readAction { plan(project, root, files, fix) }
        if (plan is LintOutcome) return plan
        plan as Plan
        val result = withContext(Dispatchers.IO) {
            val commandLine = GeneralCommandLine(plan.command)
                .withWorkingDirectory(plan.workDir)
                .withEnvironment(plan.environment)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                .withCharset(StandardCharsets.UTF_8)
            val handler = try {
                CapturingProcessHandler(commandLine)
            } catch (e: Exception) {
                return@withContext null to (e.message ?: e.javaClass.simpleName)
            }
            val stop = currentCoroutineContext().job.invokeOnCompletion { cause -> if (cause != null) handler.destroyProcess() }
            try {
                handler.runProcess(timeoutMillis, true) to null
            } finally {
                stop.dispose()
            }
        }
        val output = result.first ?: return LintOutcome.Failed(message("lint.failed.start", result.second.orEmpty()))
        if (output.isTimeout) return LintOutcome.Failed(message("lint.failed.timeout", timeoutMillis / 1000))
        return try {
            LintOutcome.Findings(LintSarif.parse(output.stdout, plan.resolve), plan.where)
        } catch (_: LintOutputException) {
            LintOutcome.Failed(message("lint.failed.output", output.exitCode, excerpt(output.stdout, output.stderr)))
        }
    }

    /**
     * What to show of a run that printed no SARIF: ansible-lint's own lines (stdout first, then stderr), without Docker
     * Compose's progress lines (`Container … Creating`, `Network …`, image build steps), at most 8 lines.
     */
    internal fun excerpt(stdout: String, stderr: String): String {
        val lines = (stdout.lines() + stderr.lines()).map(String::trimEnd).filter { it.isNotBlank() && !COMPOSE_NOISE.containsMatchIn(it) }
        val text = lines.take(8).joinToString("\n").ifEmpty { stderr.trim().lines().takeLast(3).joinToString("\n") }
        return StringUtil.shortenTextWithEllipsis(text, 800, 0)
    }

    private val COMPOSE_NOISE = Regex("""^\s*(Container |Network |Volume |#\d+ |\[\+\] |Pulling|Building|Built|Creating|Created|Starting|Started)""")

    private fun relative(base: Path, path: Path): String =
        runCatching { base.relativize(path.normalize()).toString() }.getOrDefault(path.toString()).ifEmpty { "." }

    const val EXECUTABLE = "ansible-lint"
    private const val DOT_CONFIG = ".config/ansible-lint.yml"
    private const val TIMEOUT_MILLIS = 180_000

    @TestOnly
    @Volatile
    internal var executableForTests: (() -> Path?)? = null

    @TestOnly
    @Volatile
    internal var dockerForTests: (() -> Path?)? = null
}
