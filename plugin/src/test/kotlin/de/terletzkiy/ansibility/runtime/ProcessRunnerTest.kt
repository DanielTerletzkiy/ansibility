package de.terletzkiy.ansibility.runtime

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.ThrowableRunnable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/** [ProcessRunnerImpl] with fake executables (shell scripts in a temporary directory). */
class ProcessRunnerTest : RuntimeTestCase() {
    private val runner: ProcessRunnerImpl get() = ProcessRunner.getInstance() as ProcessRunnerImpl

    override fun setUp() {
        super.setUp()
        runner.executionObserver = null
    }

    override fun tearDown() {
        try {
            runner.executionObserver = null
        } finally {
            super.tearDown()
        }
    }

    override fun runTestRunnable(testRunnable: ThrowableRunnable<Throwable>) {
        if (SystemInfo.isWindows) return // the fake tools are POSIX shell scripts
        super.runTestRunnable(testRunnable)
    }

    fun testStdinIsTheNullDeviceAndTheProcessRunsOutsideTheProject() {
        val dir = tempDir()
        val tool = script(
            dir,
            "ansible-doc",
            """
            if read -r line; then echo "stdin=data"; else echo "stdin=eof"; fi
            echo "cwd=$(pwd -P)"
            echo "nocolor=${'$'}ANSIBLE_NOCOLOR"
            echo "localtemp=${'$'}ANSIBLE_LOCAL_TEMP"
            echo "config=${'$'}{ANSIBLE_CONFIG:-none}"
            echo "home=${'$'}{ANSIBLE_HOME:-unset}"
            echo "path=${'$'}PATH"
            echo '{"ok": true}' >&2
            """.trimIndent(),
        )
        val config = dir.resolve("ansible.cfg").also { Files.writeString(it, "[defaults]\n") }
        val result = runBlocking { runner.run(ToolCommand(tool, listOf("-j"), ansibleConfig = config)) }
        assertTrue(result.describe(), result.isSuccess)
        val out = result.stdout.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals("stdin must be /dev/null, not an open pipe", "eof", out["stdin"])
        assertEquals(ProcessRunnerImpl.workDirectory.toRealPath().toString(), out["cwd"])
        assertFalse("never inside the project", out.getValue("cwd").startsWith(project.basePath ?: "/nonexistent"))
        assertEquals("1", out["nocolor"])
        assertEquals(ProcessRunnerImpl.localTempDirectory.toString(), out["localtemp"])
        assertEquals(config.toString(), out["config"])
        assertEquals("unset", out["home"])
        assertTrue("the tool's directory comes first on PATH", out.getValue("path").startsWith("$dir:"))
        assertEquals("{\"ok\": true}", result.stderr.trim())
    }

    fun testNoConfigWithoutARoot() {
        val tool = script(tempDir(), "ansible", "echo \"config=\${ANSIBLE_CONFIG:-none}\"")
        val result = runBlocking { runner.run(ToolCommand(tool, emptyList())) }
        assertEquals("config=none", result.stdout.trim())
    }

    fun testTimeoutKillsTheProcess() {
        val tool = script(tempDir(), "slow", "exec sleep 30")
        val started = System.nanoTime()
        val result = runBlocking { runner.run(ToolCommand(tool, emptyList(), timeout = 1.seconds)) }
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue(result.timedOut)
        assertFalse(result.isSuccess)
        assertTrue("returned after ${elapsed}ms", elapsed < 15_000)
    }

    fun testOutputLimit() {
        val tool = script(tempDir(), "chatty", "i=0\nwhile [ \$i -lt 2000 ]; do echo 0123456789012345678901234567890123456789; i=\$((i+1)); done")
        val result = runBlocking { runner.run(ToolCommand(tool, emptyList(), outputLimit = 1000)) }
        assertTrue(result.truncated)
        assertFalse(result.isSuccess)
        assertTrue(result.stdout.length <= 1000)
    }

    fun testExitCodeAndStartFailure() {
        val failing = script(tempDir(), "failing", "echo boom >&2\nexit 3")
        val result = runBlocking { runner.run(ToolCommand(failing, emptyList())) }
        assertEquals(3, result.exitCode)
        assertFalse(result.isSuccess)
        assertEquals("exit 3: boom", result.describe())

        val missing = runBlocking { runner.run(ToolCommand(tempDir().resolve("does-not-exist"), emptyList())) }
        assertNotNull(missing.startFailure)
        assertFalse(missing.isSuccess)
    }

    fun testNeverRunsOnTheEdt() {
        assertTrue("platform tests start on the EDT", ApplicationManager.getApplication().isDispatchThread)
        val edt = Thread.currentThread()
        var executedOn: Thread? = null
        runner.executionObserver = { executedOn = it }
        val tool = script(tempDir(), "ansible", "echo ok")
        val result = runBlocking { runner.run(ToolCommand(tool, emptyList())) }
        assertEquals("ok", result.stdout.trim())
        assertNotNull(executedOn)
        assertNotSame(edt, executedOn)
    }

    fun testRefusesToRunUnderAReadAction() {
        val tool = script(tempDir(), "ansible", "echo ok")
        val failure = ApplicationManager.getApplication().executeOnPooledThread<Throwable?> {
            runReadActionBlocking {
                runCatching { runBlocking { runner.run(ToolCommand(tool, emptyList())) } }.exceptionOrNull()
            }
        }.get(30, TimeUnit.SECONDS)
        assertInstanceOf(failure, IllegalStateException::class.java)
    }

    fun testConcurrentIdenticalRequestsShareOneProcess() {
        val dir = tempDir()
        val counter = dir.resolve("count")
        val tool = script(dir, "ansible-doc", "echo x >> '$counter'\nsleep 1\necho shared")
        val command = ToolCommand(tool, listOf("-t", "module", "-j", "ansible.builtin.copy"))
        val results = runBlocking {
            (1..4).map { async { runner.run(command) } }.awaitAll()
        }
        assertTrue(results.all { it.stdout.trim() == "shared" })
        assertEquals("one process for four identical requests", 1, Files.readAllLines(counter).size)

        runBlocking { runner.run(command) }
        assertEquals("a later request runs again", 2, Files.readAllLines(counter).size)
    }
}
