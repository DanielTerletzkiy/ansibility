package de.terletzkiy.ansibility.golden.remote.git4idea.impl

import com.intellij.externalProcessAuthHelper.AuthenticationMode
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Computable
import de.terletzkiy.ansibility.golden.remote.GitOp
import de.terletzkiy.ansibility.golden.remote.GitRequest
import de.terletzkiy.ansibility.golden.remote.GitResult
import de.terletzkiy.ansibility.golden.remote.GoldenGitTransport
import de.terletzkiy.ansibility.golden.remote.GoldenMirrorCommands
import de.terletzkiy.ansibility.golden.remote.GoldenMirrorTexts
import de.terletzkiy.ansibility.golden.remote.Interaction
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitCommandNotTrustedException
import git4idea.commands.GitLineHandler
import git4idea.commands.GitStandardProgressAnalyzer
import git4idea.config.GitExecutableManager
import git4idea.config.GitVersion
import git4idea.config.GitVersionIdentificationException

/**
 * The golden mirror's git through the bundled Git plugin (plan amendment R25, D202). Registered only by the optional
 * fragment `ansibility-git.xml` (loaded with `Git4Idea`); no always-loaded class imports `git4idea` (GitIsolationTest).
 *
 * - The IDE's credentials: EXPLICIT commands run in the Git plugin's FULL mode (password safe, the hosting accounts, then
 *   the IDE's login or passphrase dialog); BACKGROUND ones in SILENT mode (stored credentials only, never a dialog),
 *   with low priority and without a terminal.
 * - Every network command gets its URL (`setUrl`: HTTP authentication needs it); local commands get no credential
 *   callbacks at all.
 * - Silent handlers: no VCS console and no INFO log lines with the URL or the output.
 * - `sparse-checkout` and `checkout` take the read lock (`readLockingCommand`): the mirror is not a project repository,
 *   so they must not block the user's own git operations.
 * - Never a null project, and the project's trust is checked first: the Git plugin throws
 *   [GitCommandNotTrustedException] (an IllegalStateException) for untrusted projects and for directories outside one.
 * - `identifyVersion` first, which shows nothing (the Git plugin's own check would show a "git not found" balloon), and
 *   git 2.25 or later.
 *
 * API note: `com.intellij.externalProcessAuthHelper.AuthenticationMode` lives in a platform module whose descriptor says
 * visibility="internal", but it is the parameter type of the public `GitLineHandler.setIgnoreAuthenticationMode`, it is
 * not annotated, and the plugin verifier accepts it (R25 research; `verifyPlugin` confirms each release).
 */
class Git4IdeaGoldenGitTransport : GoldenGitTransport {
    override fun run(request: GitRequest): GitResult {
        val project = request.project
        if (project.isDisposed) throw ProcessCanceledException()
        if (!TrustedProjects.isProjectTrusted(project)) return GitResult.notStarted(GoldenMirrorTexts.untrusted())
        val manager = GitExecutableManager.getInstance()
        val executable = manager.getExecutable(project, request.workDir)
        val version = try {
            manager.identifyVersion(project, executable)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: GitVersionIdentificationException) {
            return GitResult.notStarted(GoldenMirrorTexts.gitMissing())
        }
        if (!version.isLaterOrEqual(MIN_VERSION)) return GitResult.notStarted(GoldenMirrorTexts.gitTooOld())
        val command = commandOf(request.op)
        val result = try {
            Git.getInstance().runCommand(
                Computable {
                    GitLineHandler(project, request.workDir, executable, command, request.config).apply {
                        setSilent(true)
                        val url = request.remoteUrl
                        if (url != null) setUrl(url) else setEnableInteractiveCallbacks(false)
                        setIgnoreAuthenticationMode(if (request.interaction == Interaction.EXPLICIT) AuthenticationMode.FULL else AuthenticationMode.SILENT)
                        if (request.interaction == Interaction.BACKGROUND) {
                            withLowPriority()
                            withNoTty()
                        }
                        request.env.forEach { (name, value) -> addCustomEnvironmentVariable(name, value) }
                        addParameters(request.args)
                        if (request.progress) {
                            ProgressManager.getInstance().progressIndicator?.let { addLineListener(GitStandardProgressAnalyzer.createListener(it)) }
                        }
                    }
                },
            )
        } catch (_: GitCommandNotTrustedException) {
            return GitResult.notStarted(GoldenMirrorTexts.untrusted())
        }
        return GitResult(
            exitCode = result.exitCode,
            output = result.output,
            errorOutput = result.errorOutput,
            authenticationFailed = result.isAuthenticationFailed,
            startFailed = result.exitCode == -1,
        )
    }

    private fun commandOf(op: GitOp): GitCommand = when (op) {
        GitOp.CLONE -> GitCommand.CLONE
        GitOp.LS_REMOTE -> GitCommand.LS_REMOTE
        GitOp.FETCH -> GitCommand.FETCH
        GitOp.SPARSE_CHECKOUT -> GitCommand.SPARSE_CHECKOUT.readLockingCommand()
        GitOp.CHECKOUT -> GitCommand.CHECKOUT.readLockingCommand()
        GitOp.REV_PARSE -> GitCommand.REV_PARSE
        GitOp.LOG -> GitCommand.LOG
        GitOp.LS_TREE -> GitCommand.LS_TREE
    }

    private companion object {
        val MIN_VERSION = GitVersion(GoldenMirrorCommands.MIN_GIT_MAJOR, GoldenMirrorCommands.MIN_GIT_MINOR, 0, 0)
    }
}
