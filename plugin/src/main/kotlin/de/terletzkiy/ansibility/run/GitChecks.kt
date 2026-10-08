package de.terletzkiy.ansibility.run

import com.intellij.util.EnvironmentUtil
import de.terletzkiy.ansibility.runtime.ProcessTrees
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** The git facts a run passes on (`GIT_URL`, `GIT_COMMIT`, `GIT_BRANCH`); empty strings when git does not know them. */
data class GitRunInfo(val url: String, val commit: String, val branch: String)

/** Whether the checkout of a root is behind the branch it is provisioned from. */
sealed interface Freshness {
    /** Not in a git repository, or no git executable. */
    data object NotARepository : Freshness

    /** The repository has no such remote; nothing to compare with. */
    data class NoRemote(val remote: String) : Freshness

    /** The fetch failed (offline, no credentials, timeout): the user decides. */
    data class Unverified(val upstream: String, val reason: String) : Freshness

    data class UpToDate(val branch: String, val upstream: String) : Freshness

    /** [commits] are the newest missing ones (`<hash>  <age>  <subject>`), at most [GitChecks.SHOWN_COMMITS]. */
    data class Behind(val branch: String, val upstream: String, val count: Int, val commits: List<String>) : Freshness
}

/**
 * The git checks before a run, as the provisioning scripts do them: fetch the upstream branch, count the commits the
 * checkout is missing, and read the facts a report callback wants. Blocking (processes with timeouts, no prompts:
 * `GIT_TERMINAL_PROMPT=0`, stdin from the null device); call off the EDT. Interrupting the thread ends the git
 * process it waits for and its children ([InterruptedException]), so a run stopped while it prepares does not wait
 * for a fetch and leaves no `ssh` behind.
 */
class GitChecks(private val git: Path, private val dir: Path, private val runner: Runner = ProcessRunner) {
    /** One git invocation; null when it could not start or timed out. */
    fun interface Runner {
        fun run(command: List<String>, dir: Path, timeoutSeconds: Long): Output?
    }

    class Output(val exit: Int, val stdout: String, val stderr: String)

    fun isRepository(): Boolean = git("rev-parse", "--is-inside-work-tree")?.takeIf { it.exit == 0 }?.stdout?.trim() == "true"

    fun info(): GitRunInfo = GitRunInfo(
        url = withoutCredentials(value("config", "--get", "remote.origin.url")),
        commit = value("rev-parse", "HEAD"),
        branch = value("rev-parse", "--abbrev-ref", "HEAD"),
    )

    /** Compares HEAD with [setting] (`origin/main`, `main`; blank: the remote's default branch) after fetching it. */
    fun freshness(setting: String): Freshness {
        if (!isRepository()) return Freshness.NotARepository
        val (remote, branch) = upstream(setting.trim())
        if (git("remote", "get-url", remote)?.exit != 0) return Freshness.NoRemote(remote)
        val upstream = "$remote/$branch"
        val fetch = git(FETCH_TIMEOUT, "fetch", "--quiet", remote, branch)
            ?: return Freshness.Unverified(upstream, "timed out")
        if (fetch.exit != 0) return Freshness.Unverified(upstream, fetch.stderr.trim().lineSequence().lastOrNull().orEmpty().ifEmpty { "exit ${fetch.exit}" })
        val current = value("rev-parse", "--abbrev-ref", "HEAD").ifEmpty { "HEAD" }
        val count = value("rev-list", "--count", "HEAD..$upstream").toIntOrNull() ?: 0
        if (count == 0) return Freshness.UpToDate(current, upstream)
        val log = git("log", "-n", SHOWN_COMMITS.toString(), "--no-decorate", "--pretty=format:%h  %cr  %s", "HEAD..$upstream")?.stdout.orEmpty()
        return Freshness.Behind(current, upstream, count, log.lines().filter { it.isNotBlank() })
    }

    /** The remote and branch of [setting]: `remote/branch` when the first segment is a remote, else `origin/<setting>`. */
    private fun upstream(setting: String): Pair<String, String> {
        if (setting.isNotEmpty()) {
            val first = setting.substringBefore('/')
            if ('/' in setting && first in remotes()) return first to setting.substringAfter('/')
            return ORIGIN to setting
        }
        val head = value("symbolic-ref", "--quiet", "--short", "refs/remotes/$ORIGIN/HEAD")
        if (head.startsWith("$ORIGIN/")) return ORIGIN to head.removePrefix("$ORIGIN/")
        val known = listOf("main", "master").firstOrNull { git("rev-parse", "--verify", "--quiet", "refs/remotes/$ORIGIN/$it")?.exit == 0 }
        return ORIGIN to (known ?: "main")
    }

    private fun remotes(): Set<String> = value("remote").lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun value(vararg args: String): String = git(*args)?.takeIf { it.exit == 0 }?.stdout?.trim().orEmpty()

    private fun git(vararg args: String): Output? = git(TIMEOUT, *args)

    private fun git(timeout: Long, vararg args: String): Output? = runner.run(listOf(git.toString()) + args, dir, timeout)

    companion object {
        const val SHOWN_COMMITS = 20
        private val USER_INFO = Regex("""^(https?://)[^/@]*@""", RegexOption.IGNORE_CASE)

        /** [url] without the user info of an HTTP(S) URL, where tokens live; SSH users (`git@`) are kept. */
        fun withoutCredentials(url: String): String = USER_INFO.replace(url, "$1")
        private const val ORIGIN = "origin"
        private const val TIMEOUT = 10L
        private const val FETCH_TIMEOUT = 30L

        /** `git` on the login shell's `PATH`, else in the usual places. */
        fun locateGit(): Path? {
            val path = EnvironmentUtil.getValue("PATH") ?: System.getenv("PATH").orEmpty()
            val dirs = path.split(File.pathSeparatorChar) + listOf("/usr/bin", "/usr/local/bin", "/opt/homebrew/bin")
            return dirs.asSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { runCatching { Path.of(it, "git") }.getOrNull() }
                .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
        }
    }

    /**
     * Runs git with the login shell's environment, without prompts; an interrupt or the timeout ends git and every
     * process it started ([ProcessTrees.end]), and an interrupt is rethrown.
     */
    object ProcessRunner : Runner {
        override fun run(command: List<String>, dir: Path, timeoutSeconds: Long): Output? {
            val process = try {
                ProcessBuilder(command)
                    .directory(dir.toFile())
                    .redirectInput(ProcessBuilder.Redirect.from(File(if (File.separatorChar == '\\') "NUL" else "/dev/null")))
                    .apply {
                        environment().putAll(EnvironmentUtil.getEnvironmentMap())
                        environment()["GIT_TERMINAL_PROMPT"] = "0"
                        environment()["LC_ALL"] = "C"
                    }
                    .start()
            } catch (_: Exception) {
                return null
            }
            val out = StringBuilder()
            val err = StringBuilder()
            val readers = listOf(
                Thread { runCatching { out.append(process.inputStream.bufferedReader().readText()) } },
                Thread { runCatching { err.append(process.errorStream.bufferedReader().readText()) } },
            ).onEach { it.isDaemon = true; it.start() }
            try {
                if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                    ProcessTrees.end(process)
                    return null
                }
            } catch (e: InterruptedException) {
                // The run was stopped while it prepared (runInterruptible): git goes with it, and so do the processes
                // it started (ssh, git-remote-https, credential helpers), which may wait for an approval.
                ProcessTrees.end(process)
                throw e
            }
            readers.forEach { it.join(TimeUnit.SECONDS.toMillis(2)) }
            return Output(process.exitValue(), out.toString(), err.toString())
        }
    }
}
