package de.terletzkiy.ansibility.golden.remote

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * Runs one git command for the golden mirror (plan amendment R25, D202). Extension point
 * `de.terletzkiy.ansibility.goldenGitTransport` (dynamic): the optional fragment `ansibility-git.xml` registers the
 * bundled Git plugin's transport, which asks for credentials in the IDE's own dialogs ([Interaction.EXPLICIT]) and
 * remembers them in the password safe. Without an extension, [SystemGitTransport] runs the system git without ever
 * prompting.
 *
 * [run] blocks: call it on a background thread outside read actions. It is cancellable through
 * `ProgressManager.checkCanceled()` (a coroutine job or a progress indicator): a cancelled command ends git and its
 * children and throws a cancellation exception.
 */
interface GoldenGitTransport {
    /** Runs [request]; failures (git missing or too old, an untrusted project, a refused URL) come back as a failed [GitResult]. */
    fun run(request: GitRequest): GitResult

    companion object {
        val EP_NAME: ExtensionPointName<GoldenGitTransport> = ExtensionPointName("de.terletzkiy.ansibility.goldenGitTransport")

        /** The registered transport (the Git plugin's), else [SystemGitTransport]. */
        fun current(): GoldenGitTransport = EP_NAME.extensionList.firstOrNull() ?: SystemGitTransport
    }
}

/** The git commands the mirror runs (the Git plugin has no generic command; these map onto its `GitCommand`s). */
enum class GitOp(val command: String, val network: Boolean) {
    CLONE("clone", true),
    LS_REMOTE("ls-remote", true),
    FETCH("fetch", true),

    /** Network in a partial clone: checking out files fetches their missing blobs. */
    SPARSE_CHECKOUT("sparse-checkout", true),

    /** Network in a partial clone, as [SPARSE_CHECKOUT]. */
    CHECKOUT("checkout", true),
    REV_PARSE("rev-parse", false),
    LOG("log", false),
    LS_TREE("ls-tree", false),
}

/**
 * Who asked (D202): BACKGROUND commands never show a dialog (the Git plugin's SILENT mode: stored credentials only;
 * the system git without prompts); EXPLICIT ones (Fetch Now, Test Connection, the first clone) may ask (FULL).
 */
enum class Interaction { BACKGROUND, EXPLICIT }

/**
 * One git invocation: `git -c <config>… <op> <args>…` in [workDir] with [env] added to the environment.
 * [remoteUrl] is the repository the command may contact (the Git plugin's HTTP authentication needs it); null for
 * local commands, which never get credential callbacks. [progress]: report `--progress` output to the current
 * progress indicator.
 */
class GitRequest(
    val project: Project,
    val workDir: Path,
    val op: GitOp,
    val args: List<String>,
    val remoteUrl: String?,
    val interaction: Interaction,
    val config: List<String>,
    val env: Map<String, String>,
    val progress: Boolean = false,
    /** Seconds the system git may run before it is ended; the Git plugin's transport is bounded by the caller. */
    val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) {
    /** The command line without the executable, for tests and debug logs: `-c k=v … <op> <args>`. Contains no credentials (the URL is validated). */
    fun commandLine(): List<String> = config.flatMap { listOf("-c", it) } + op.command + args

    override fun toString(): String = "git ${op.command} (${interaction.name.lowercase()})"

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS: Long = 15 * 60
    }
}

/** What git answered. [startFailed]: git did not run (missing, too old, untrusted project); [errorOutput] says why. */
class GitResult(
    val exitCode: Int,
    val output: List<String>,
    val errorOutput: List<String>,
    /** The Git plugin detected a failed HTTP authentication. */
    val authenticationFailed: Boolean = false,
    val startFailed: Boolean = false,
) {
    val success: Boolean
        get() = exitCode == 0 && !startFailed

    override fun toString(): String = "GitResult(exit=$exitCode, startFailed=$startFailed, auth=$authenticationFailed)"

    companion object {
        /** A command that did not run, with [reason] as its error output. */
        fun notStarted(reason: String): GitResult = GitResult(-1, emptyList(), listOf(reason), startFailed = true)
    }
}
