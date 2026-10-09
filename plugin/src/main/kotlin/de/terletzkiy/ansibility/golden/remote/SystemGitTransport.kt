package de.terletzkiy.ansibility.golden.remote

import com.intellij.openapi.progress.ProgressManager
import com.intellij.util.EnvironmentUtil
import de.terletzkiy.ansibility.run.GitChecks
import org.jetbrains.annotations.TestOnly
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * The golden mirror's git without the bundled Git plugin (plan amendment R25, D202): the system git (`run.GitChecks`'
 * `locateGit` and process runner) that never prompts. On top of [GoldenMirrorCommands.safetyConfig] and its
 * environment it adds no askpass program and no interactive credential helper ([GoldenMirrorCommands.SYSTEM_GIT_CONFIG]),
 * removes `GIT_ASKPASS`/`SSH_ASKPASS`, and runs SSH in batch mode unless the user chose an SSH command
 * ([GoldenMirrorCommands.systemGitEnvironment]). Your SSH agent and credential helper still work. stdin is the null
 * device; cancellation (`ProgressManager.checkCanceled`) and the timeout end git and its children (`runtime.ProcessTrees`).
 *
 * Note: on macOS, `/usr/bin/git` without the command line tools opens a system dialog; the Git plugin's transport is
 * the default wherever the IDE has it.
 */
object SystemGitTransport : GoldenGitTransport {
    @Volatile
    private var gitOverride: (() -> Path?)? = null

    /** Whether each git executable is 2.25 or later. */
    private val supported = ConcurrentHashMap<Path, Boolean>()

    /** Whether the user configured `core.sshCommand`, by git executable, with the time it was read. */
    private val sshCommands = ConcurrentHashMap<Path, Pair<Long, Boolean>>()

    override fun run(request: GitRequest): GitResult {
        val git = (gitOverride ?: GitChecks::locateGit)() ?: return GitResult.notStarted(GoldenMirrorTexts.gitMissing())
        val inherited = EnvironmentUtil.getEnvironmentMap()
        if (!supported.computeIfAbsent(git) { isSupported(it, request.workDir) }) return GitResult.notStarted(GoldenMirrorTexts.gitTooOld())
        val sshCommand = request.op.network && sshCommandConfigured(git, request.workDir)
        val env = GoldenMirrorCommands.systemGitEnvironment(inherited, request.env, sshCommand)
        val command = listOf(git.toString()) +
            (request.config + GoldenMirrorCommands.SYSTEM_GIT_CONFIG).flatMap { listOf("-c", it) } +
            request.op.command + request.args
        val output = GitChecks.ProcessRunner.run(command, request.workDir, request.timeoutSeconds, { target ->
            target.clear()
            target.putAll(env)
        }, ProgressManager::checkCanceled) ?: return GitResult.notStarted(GoldenMirrorTexts.gitDidNotFinish())
        return GitResult(output.exit, output.stdout.lines().filter { it.isNotEmpty() }, output.stderr.lines().filter { it.isNotBlank() })
    }

    private fun isSupported(git: Path, dir: Path): Boolean {
        val output = GitChecks.ProcessRunner.run(listOf(git.toString(), "--version"), dir, PROBE_TIMEOUT_SECONDS) ?: return false
        return output.exit == 0 && GoldenMirrorCommands.isSupportedVersion(output.stdout)
    }

    /** `git config --get core.sshCommand` (system and global; read again after [SSH_COMMAND_TTL_MILLIS]). */
    private fun sshCommandConfigured(git: Path, dir: Path): Boolean {
        val now = System.currentTimeMillis()
        sshCommands[git]?.let { (at, value) -> if (now - at < SSH_COMMAND_TTL_MILLIS) return value }
        val output = GitChecks.ProcessRunner.run(listOf(git.toString(), "config", "--get", "core.sshCommand"), dir, PROBE_TIMEOUT_SECONDS)
        val value = output != null && output.exit == 0 && output.stdout.isNotBlank()
        sshCommands[git] = now to value
        return value
    }

    /** Uses [git] (null: none found) instead of `GitChecks.locateGit` until [resetForTests]. */
    @TestOnly
    fun useGitForTests(git: () -> Path?) {
        gitOverride = git
        supported.clear()
        sshCommands.clear()
    }

    @TestOnly
    fun resetForTests() {
        gitOverride = null
        supported.clear()
        sshCommands.clear()
    }

    private const val PROBE_TIMEOUT_SECONDS = 10L
    private const val SSH_COMMAND_TTL_MILLIS = 5 * 60 * 1000L
}
