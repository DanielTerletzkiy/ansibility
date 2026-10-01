package de.terletzkiy.ansibility.runtime

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessAdapter
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One run of an Ansible tool. Equal commands that run at the same time share one process.
 *
 * [ansibleConfig] is exported as `ANSIBLE_CONFIG` (a root's `ansible.cfg`); without it, no project config is read,
 * because the process runs in the plugin's own working directory.
 */
data class ToolCommand(
    val executable: Path,
    val arguments: List<String>,
    val ansibleConfig: Path? = null,
    val timeout: Duration = DEFAULT_TIMEOUT,
    /** Maximum characters of stdout plus stderr; beyond it the process is killed and the result is [ToolResult.truncated]. */
    val outputLimit: Int = DEFAULT_OUTPUT_LIMIT,
) {
    companion object {
        /** Plan A.11: 20 s. `ansible-doc -j` for 60 modules takes about a second. */
        val DEFAULT_TIMEOUT: Duration = 20.seconds

        /** 32 M characters; 73 modules of docs are about 1.1 MB. */
        const val DEFAULT_OUTPUT_LIMIT: Int = 32 * 1024 * 1024
    }
}

/** The outcome of a [ToolCommand]. */
data class ToolResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
    /** Why the process could not be started; the other fields are empty then. */
    val startFailure: String? = null,
) {
    val isSuccess: Boolean get() = startFailure == null && !timedOut && !truncated && exitCode == 0

    /** A one-line description for logs. */
    fun describe(): String = when {
        startFailure != null -> "could not start: $startFailure"
        timedOut -> "timed out"
        truncated -> "output limit exceeded"
        else -> "exit $exitCode" + stderr.trim().lineSequence().lastOrNull()?.takeIf { it.isNotEmpty() }?.let { ": ${it.take(300)}" }.orEmpty()
    }
}

/**
 * Application service that runs Ansible tools (plan A.11, A.10). Implementations never run a process on the EDT
 * or under a read lock, and never inside the repository.
 */
interface ProcessRunner {
    /** Runs [command] on a background thread and suspends until it finishes, times out or exceeds its output limit. */
    suspend fun run(command: ToolCommand): ToolResult

    companion object {
        fun getInstance(): ProcessRunner = service()
    }
}

/**
 * The [ProcessRunner]: a [GeneralCommandLine] with stdin from the null device (ansible aborts with "requires
 * blocking IO" otherwise), the plugin's working directory under the system dir, `ANSIBLE_NOCOLOR=1`,
 * `ANSIBLE_LOCAL_TEMP` under the system dir, `ANSIBLE_CONFIG` for a root, and the tool's own directory first on
 * `PATH` (GUI launches lack `/opt/homebrew/bin`). `ANSIBLE_HOME` is never set. Output is captured by a
 * [CapturingProcessHandler] with the command's timeout and output limit, on [Dispatchers.IO] in the service scope.
 */
class ProcessRunnerImpl(private val scope: CoroutineScope) : ProcessRunner {
    private val inFlight = ConcurrentHashMap<ToolCommand, Deferred<ToolResult>>()

    /** Called on the thread that runs each process, before it starts (tests assert the thread). */
    @Volatile
    internal var executionObserver: ((Thread) -> Unit)? = null

    override suspend fun run(command: ToolCommand): ToolResult {
        val app = ApplicationManager.getApplication()
        // A coroutine on the EDT merely suspends here; a thread blocking on this call inside a read action would
        // hold the read lock for the whole run.
        check(app.isDispatchThread || !app.isReadAccessAllowed) { "Ansible tools must not be run under a read action" }
        while (true) {
            val deferred = inFlight.computeIfAbsent(command) { key ->
                scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) { execute(key) }
                    .also { started -> started.invokeOnCompletion { inFlight.remove(key, started) } }
            }
            if (deferred.isCompleted) {
                // finished before its completion handler ran: a new request gets a new process
                inFlight.remove(command, deferred)
                continue
            }
            deferred.start()
            return deferred.await()
        }
    }

    private fun execute(command: ToolCommand): ToolResult {
        val app = ApplicationManager.getApplication()
        check(!app.isDispatchThread) { "Ansible tools must not run on the EDT" }
        check(!app.isReadAccessAllowed) { "Ansible tools must not run under a read lock" }
        executionObserver?.invoke(Thread.currentThread())
        val commandLine = GeneralCommandLine(listOf(command.executable.toString()) + command.arguments)
            .withInput(nullDevice())
            .withWorkingDirectory(ensureDirectory(workDirectory))
            .withCharset(Charsets.UTF_8)
            .withEnvironment(environment(command))
        val handler = try {
            CappedCapturingHandler(commandLine, command.outputLimit)
        } catch (e: ExecutionException) {
            LOG.info("Could not start ${command.executable}: ${e.message}")
            return ToolResult(-1, "", "", startFailure = e.message ?: e.javaClass.simpleName)
        }
        val output = handler.runProcess(command.timeout.inWholeMilliseconds.coerceIn(1, Int.MAX_VALUE.toLong()).toInt(), true)
        return ToolResult(
            exitCode = if (output.isExitCodeSet) output.exitCode else -1,
            stdout = output.stdout,
            stderr = output.stderr,
            timedOut = output.isTimeout,
            truncated = handler.truncated,
        ).also { if (!it.isSuccess) LOG.info("${command.executable.fileName} ${command.arguments.take(4).joinToString(" ")}: ${it.describe()}") }
    }

    private fun environment(command: ToolCommand): Map<String, String> {
        val env = LinkedHashMap<String, String>()
        env["ANSIBLE_NOCOLOR"] = "1"
        env["ANSIBLE_FORCE_COLOR"] = "0"
        env["ANSIBLE_LOCAL_TEMP"] = ensureDirectory(localTempDirectory).toString()
        command.ansibleConfig?.let { env["ANSIBLE_CONFIG"] = it.toString() }
        val inherited = EnvironmentUtil.getValue("PATH") ?: System.getenv("PATH").orEmpty()
        val toolDir = command.executable.parent?.toString()
        env["PATH"] = listOfNotNull(toolDir, inherited.takeIf { it.isNotEmpty() }).joinToString(File.pathSeparator)
        return env
    }

    /** Captures output like [CapturingProcessHandler] but kills the process once the output exceeds [limit] characters. */
    private class CappedCapturingHandler(commandLine: GeneralCommandLine, private val limit: Int) : CapturingProcessHandler(commandLine) {
        @Volatile
        var truncated: Boolean = false

        override fun createProcessAdapter(processOutput: ProcessOutput): CapturingProcessAdapter =
            object : CapturingProcessAdapter(processOutput) {
                private var size = 0L

                override fun addToOutput(text: String, outputType: Key<*>) {
                    if (truncated) return
                    size += text.length
                    if (size > limit) {
                        truncated = true
                        destroyProcess()
                        return
                    }
                    super.addToOutput(text, outputType)
                }
            }
    }

    companion object {
        private val LOG = logger<ProcessRunnerImpl>()

        /** Where tools run: `<system dir>/ansibility/work`, never a repository. */
        val workDirectory: Path get() = PathManager.getSystemDir().resolve("ansibility").resolve("work")

        /** `ANSIBLE_LOCAL_TEMP`: `<system dir>/ansibility/tmp`. */
        val localTempDirectory: Path get() = PathManager.getSystemDir().resolve("ansibility").resolve("tmp")

        private fun nullDevice(): File = File(if (SystemInfo.isWindows) "NUL" else "/dev/null")

        private fun ensureDirectory(dir: Path): Path {
            if (!Files.isDirectory(dir)) Files.createDirectories(dir)
            return dir
        }
    }
}
