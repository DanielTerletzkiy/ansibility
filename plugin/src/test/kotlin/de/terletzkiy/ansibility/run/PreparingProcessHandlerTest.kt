package de.terletzkiy.ansibility.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings as PlatformRunnerSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.run.events.RunModel
import de.terletzkiy.ansibility.run.view.AnsibleRunConsole
import de.terletzkiy.ansibility.run.view.RunBanner
import de.terletzkiy.ansibility.run.view.RunEventCollector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.coroutines.ContinuationInterceptor

/**
 * A run prepared in its tab (plan amendment R19, D144–D145): the phases, the start of the real process in the same
 * handler, Stop and kill, the runs that do not start, and the hooks of [AnsibleRunState].
 */
class PreparingProcessHandlerTest : BasePlatformTestCase() {
    private object TestRunner : ProgramRunner<PlatformRunnerSettings> {
        override fun getRunnerId(): String = "AnsibilityPreparingTest"
        override fun canRun(executorId: String, profile: RunProfile): Boolean = true
        override fun execute(environment: ExecutionEnvironment) = Unit
    }

    private lateinit var dir: Path

    override fun setUp() {
        super.setUp()
        dir = FileUtil.createTempDirectory("ansibility-preparing", null, true).toPath().toRealPath()
    }

    /** A prepared run of `sh -c [script]` with a scripts directory of its own and one header line. */
    private fun prepared(script: String, events: RunEventsSetup? = null): PreparedRun {
        val secrets = RunSecrets.create(dir.resolve("secrets"), emptyList(), null, callback = "# a callback")
        return PreparedRun(PlaybookProcess(listOf("sh", "-c", script), dir, emptyMap()), secrets, listOf("Header one"), events)
    }

    private class Run(val handler: PreparingProcessHandler, val collector: RunEventCollector) {
        val calls = CopyOnWriteArrayList<String>()
        val stdout = StringBuffer()
        val stderr = StringBuffer()
        val system = StringBuffer()
        val model: RunModel get() = collector.drainForTests().let { collector.model }
    }

    private fun run(
        child: (PreparedRun, GeneralCommandLine) -> ProcessHandler = { _, commandLine -> KillableColoredProcessHandler(commandLine) },
        prepare: suspend (PreparationReporter) -> PreparedRun?,
    ): Run {
        val collector = RunEventCollector(testRootDisposable)
        lateinit var run: Run
        val listener = object : PreparingProcessHandler.Listener {
            override fun phase(text: String) {
                run.calls += "phase $text"
            }

            override fun starting(prepared: PreparedRun) {
                run.calls += "starting"
            }

            override fun notStarted(why: NotStarted) {
                run.calls += "not started: ${why.text}"
            }
        }
        val handler = PreparingProcessHandler(RunPreparations.getInstance(project).scope, collector, listener, prepare, child)
        run = Run(handler, collector)
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                when {
                    ProcessOutputType.isStderr(outputType) -> run.stderr.append(event.text)
                    ProcessOutputType.isStdout(outputType) -> run.stdout.append(event.text)
                    else -> run.system.append(event.text)
                }
            }
        })
        handler.startNotify()
        return run
    }

    private fun Run.awaitEnd() = assertTrue("the run did not end", handler.waitFor(TimeUnit.SECONDS.toMillis(30)))

    fun testThePhasesComeFirstThenTheProcessStartsInTheSameHandler() {
        val gate = CompletableDeferred<Unit>()
        val prepared = prepared("echo out; echo err >&2; exit 3")
        val run = run { reporter ->
            reporter.phase("reading the playbook")
            gate.await()
            reporter.phase("unlocking the vault ids of falcon")
            prepared
        }
        PlatformTestUtil.waitWithEventsDispatching("no phase", { run.calls.isNotEmpty() }, 10)
        assertTrue(run.handler.isPreparing)
        assertEquals("reading the playbook", run.model.preparing)
        assertFalse("nothing runs while it prepares", run.handler.canKillProcess())
        gate.complete(Unit)
        run.awaitEnd()
        assertEquals("the process's exit code is the run's", 3, run.handler.exitCode)
        val system = run.system.toString()
        assertTrue(system, system.startsWith("Preparing: reading the playbook…\nPreparing: unlocking the vault ids of falcon…\nHeader one\nsh -c "))
        assertTrue("then the command line: $system", system.lines().drop(3).first().contains("exit 3"))
        assertEquals("out\n", run.stdout.toString())
        assertEquals("err\n", run.stderr.toString())
        assertEquals(listOf("phase reading the playbook", "phase unlocking the vault ids of falcon", "starting"), run.calls)
        assertFalse("its scripts are deleted when it ends", Files.exists(prepared.secrets.dir!!))
        val model = run.model
        assertTrue(model.finished)
        assertEquals(3, model.exitCode)
        assertNull("done preparing", model.preparing)
        assertNull(model.notStarted)
        assertFalse(run.handler.stopped)
    }

    fun testStopWhilePreparingEndsAtOnceAndClosesWhatComesLate() {
        val gate = CompletableDeferred<Unit>()
        val late = prepared("echo never")
        val run = run { reporter ->
            reporter.phase("waiting for a password manager")
            // A step that cannot be cancelled: it ends after the Stop.
            withContext(NonCancellable) { gate.await() }
            reporter.phase("after the Stop")
            late
        }
        PlatformTestUtil.waitWithEventsDispatching("no phase", { run.calls.isNotEmpty() }, 10)
        run.handler.destroyProcess()
        assertTrue("Stop does not wait for the preparation", run.handler.waitFor(TimeUnit.SECONDS.toMillis(5)))
        assertEquals(RunModel.NOT_STARTED, run.handler.exitCode)
        assertTrue(run.handler.stopped)
        assertTrue(run.system.toString(), "Not started: stopped while preparing\n" in run.system)
        assertEquals("not started: stopped while preparing", run.calls.last())
        assertEquals("stopped while preparing", run.model.notStarted)

        gate.complete(Unit)
        PlatformTestUtil.waitWithEventsDispatching("the late run was not closed", { !Files.exists(late.secrets.dir!!) }, 10)
        assertFalse("no process starts", "starting" in run.calls)
        assertFalse("nothing of the preparation shows after the end", "after the Stop" in run.system)
        assertEquals("", run.stdout.toString())
    }

    fun testARunThatDoesNotStartSaysWhy() {
        val failed = run { throw ExecutionException("No docker executable found on PATH") }
        failed.awaitEnd()
        assertEquals(RunModel.NOT_STARTED, failed.handler.exitCode)
        assertEquals("the reason goes to stderr", "Not started: No docker executable found on PATH\n", failed.stderr.toString())
        assertEquals(listOf("not started: No docker executable found on PATH"), failed.calls)
        val model = failed.model
        assertTrue(model.finished)
        assertEquals(RunModel.NOT_STARTED, model.exitCode)
        assertEquals("No docker executable found on PATH", model.notStarted)
        assertFalse("no events of a process", model.hasEvents)

        val cancelled = run { null }
        cancelled.awaitEnd()
        assertEquals(RunModel.NOT_STARTED, cancelled.handler.exitCode)
        assertTrue(cancelled.system.toString(), "Not started: cancelled\n" in cancelled.system)
        assertEquals("a cancel is no error", "", cancelled.stderr.toString())
        assertFalse(cancelled.handler.stopped)

        val broken = run { error("something unexpected") }
        broken.awaitEnd()
        assertEquals("Not started: something unexpected\n", broken.stderr.toString())

        val prepared = prepared("exit 0")
        val unstartable = run({ _, _ -> throw ExecutionException("Cannot run program \"sh\"") }) { prepared }
        unstartable.awaitEnd()
        assertEquals(RunModel.NOT_STARTED, unstartable.handler.exitCode)
        assertTrue(unstartable.stderr.toString(), "Not started: Cannot run program \"sh\"" in unstartable.stderr)
        assertFalse("its scripts are deleted", Files.exists(prepared.secrets.dir!!))
        assertFalse("starting" in unstartable.calls)
    }

    fun testAnErrorWhilePreparingOrStartingEndsTheRun() {
        // An error (a class that cannot load, say) is no exception: the run still ends instead of preparing for good.
        val broken = run { throw NoClassDefFoundError("de/terletzkiy/ansibility/Gone") }
        broken.awaitEnd()
        assertEquals(RunModel.NOT_STARTED, broken.handler.exitCode)
        assertEquals("Not started: de/terletzkiy/ansibility/Gone\n", broken.stderr.toString())
        assertEquals(listOf("not started: de/terletzkiy/ansibility/Gone"), broken.calls)
        assertEquals("de/terletzkiy/ansibility/Gone", broken.model.notStarted)

        // An error while its process is built: the run ends too (Stop could not), and its scripts go.
        val prepared = prepared("exit 0")
        val unbuildable = run({ _, _ -> throw NoClassDefFoundError("de/terletzkiy/ansibility/Handler") }) { prepared }
        unbuildable.awaitEnd()
        assertEquals(RunModel.NOT_STARTED, unbuildable.handler.exitCode)
        assertTrue(unbuildable.stderr.toString(), "Not started: de/terletzkiy/ansibility/Handler" in unbuildable.stderr)
        assertFalse("its scripts are deleted", Files.exists(prepared.secrets.dir!!))
        assertFalse("starting" in unbuildable.calls)
    }

    fun testARunPreparesAndBuildsItsProcessOnIo() {
        // The process is built in the same coroutine right after the preparation: on IO too (DEV.md, threading).
        var dispatcher: Any? = null
        val run = run {
            dispatcher = currentCoroutineContext()[ContinuationInterceptor]
            prepared("exit 0")
        }
        run.awaitEnd()
        assertEquals(0, run.handler.exitCode)
        assertSame(Dispatchers.IO, dispatcher)
    }

    fun testStopAndKillGoToTheProcess() {
        // Ignores the soft Stop (SIGINT), so only a kill ends it.
        val run = run { prepared("trap '' INT TERM; echo started; while true; do sleep 1; done") }
        PlatformTestUtil.waitWithEventsDispatching("the process did not start", { "started" in run.stdout }, 10)
        run.handler.destroyProcess()
        assertTrue(run.handler.stopped)
        assertFalse("still running", run.handler.waitFor(500))
        assertTrue("a second Stop can kill it", run.handler.canKillProcess())
        run.handler.killProcess()
        run.awaitEnd()
        assertFalse("it started", run.handler.exitCode == RunModel.NOT_STARTED)
        assertFalse(run.system.toString(), "Not started" in run.system)
    }

    fun testDetachAndStopBeforeTheStartAreSafe() {
        val gate = CompletableDeferred<Unit>()
        val detached = run { gate.await(); prepared("exit 0") }
        PlatformTestUtil.waitWithEventsDispatching("not preparing", { detached.handler.isPreparing }, 10)
        detached.handler.detachProcess()
        detached.awaitEnd()
        assertTrue(detached.handler.detached)
        assertEquals(listOf("not started: stopped while preparing"), detached.calls)

        // Stop before the platform start-notified the handler waits for it, then ends the run without a process.
        val collector = RunEventCollector(testRootDisposable)
        val calls = CopyOnWriteArrayList<String>()
        val handler = PreparingProcessHandler(
            RunPreparations.getInstance(project).scope, collector,
            object : PreparingProcessHandler.Listener {
                override fun notStarted(why: NotStarted) {
                    calls += why.text
                }
            },
            { CompletableDeferred<PreparedRun?>().await() },
            { _, commandLine -> KillableColoredProcessHandler(commandLine) },
        )
        handler.destroyProcess()
        assertFalse(handler.isProcessTerminated)
        handler.startNotify()
        assertTrue(handler.waitFor(TimeUnit.SECONDS.toMillis(10)))
        assertEquals(RunModel.NOT_STARTED, handler.exitCode)
        assertEquals(listOf("stopped while preparing"), calls)
    }

    // ------------------------------------------------------------------ AnsibleRunState

    /** A run state with [preparer] that records its hooks. */
    private inner class Recording(preparer: RunPreparer) : AnsibleRunState(environment(), preparer) {
        val hooks = CopyOnWriteArrayList<String>()

        override fun processStarting() {
            hooks += "starting"
        }

        override fun processEnded(exitCode: Int, model: RunModel?) {
            hooks += "ended $exitCode ${if (model == null) "without" else "with"} model"
        }

        override fun processNotStarted(why: NotStarted) {
            hooks += "not started: ${why.text}"
        }

        val shown: RunBanner get() = bannerForTests()

        lateinit var handler: PreparingProcessHandler
        lateinit var console: AnsibleRunConsole

        fun start(): Recording {
            val executor = DefaultRunExecutor.getRunExecutorInstance()
            val result = execute(executor, TestRunner)
            Disposer.register(testRootDisposable, result.executionConsole)
            handler = result.processHandler as PreparingProcessHandler
            console = result.executionConsole as AnsibleRunConsole
            assertFalse(handler.isStartNotified)
            handler.startNotify()
            return this
        }

        fun awaitHooks(count: Int = 1) = PlatformTestUtil.waitWithEventsDispatching("the run's end was not reported: $hooks", { hooks.size >= count }, 30)
    }

    private fun environment(): ExecutionEnvironment {
        val settings = RunManager.getInstance(project).createConfiguration("site.yml", AnsiblePlaybookConfigurationType.getInstance().factory)
        return ExecutionEnvironment(DefaultRunExecutor.getRunExecutorInstance(), TestRunner, settings, project)
    }

    fun testTheRunStateHearsOfTheStartTheEndOrWhyItDidNotStart() {
        val ran = Recording(RunPreparer { prepared("exit 0") }).start()
        ran.awaitHooks(2)
        assertEquals("a run without events has no model", listOf("starting", "ended 0 without model"), ran.hooks)
        assertTrue("its Plays tab goes: the run view is off", ran.console.viewDroppedForTests())
        assertNull("no banner once it started", ran.shown.messageForTests())

        val withEvents = Recording(RunPreparer { prepared("exit 2", RunEventsSetup("token") { null }) }).start()
        withEvents.awaitHooks(2)
        assertEquals(listOf("starting", "ended 2 with model"), withEvents.hooks)
        assertFalse(withEvents.console.viewDroppedForTests())

        val failed = Recording(RunPreparer { throw ExecutionException("The playbook /work/gone.yml does not exist\nsecond line") }).start()
        failed.awaitHooks()
        assertEquals(listOf("not started: The playbook /work/gone.yml does not exist\nsecond line"), failed.hooks)
        assertEquals("the first line", "Not started: The playbook /work/gone.yml does not exist", failed.shown.messageForTests())
        assertEquals(RunBanner.Status.ERROR, failed.shown.statusForTests())
        assertEquals("Not started: The playbook /work/gone.yml does not exist", failed.console.viewForTests()!!.tree.emptyText.text)

        val cancelled = Recording(RunPreparer { null }).start()
        cancelled.awaitHooks()
        assertEquals(listOf("not started: cancelled"), cancelled.hooks)
        assertEquals(RunBanner.Status.WARNING, cancelled.shown.statusForTests())

        val gate = CompletableDeferred<Unit>()
        val stopped = Recording(RunPreparer { reporter ->
            reporter.phase("checking that the checkout is up to date with main")
            gate.await()
            null
        }).start()
        PlatformTestUtil.waitWithEventsDispatching("no phase", { stopped.shown.messageForTests() != null }, 10)
        assertEquals("Preparing: checking that the checkout is up to date with main…", stopped.shown.messageForTests())
        assertEquals(RunBanner.Status.INFO, stopped.shown.statusForTests())
        PlatformTestUtil.waitWithEventsDispatching("the Plays tab does not say it", { stopped.console.viewForTests()!!.tree.emptyText.text.startsWith("Preparing") }, 10)
        assertEquals("Preparing: checking that the checkout is up to date with main…", stopped.console.viewForTests()!!.tree.emptyText.text)
        stopped.handler.destroyProcess()
        stopped.awaitHooks()
        assertEquals(listOf("not started: stopped while preparing"), stopped.hooks)
    }

    fun testClosingTheTabWhileARunPreparesStopsIt() {
        val gate = CompletableDeferred<Unit>()
        val state = Recording(RunPreparer { gate.await(); prepared("exit 0") })
        val result = state.execute(DefaultRunExecutor.getRunExecutorInstance(), TestRunner)
        val handler = result.processHandler as PreparingProcessHandler
        handler.startNotify()
        PlatformTestUtil.waitWithEventsDispatching("not preparing", { handler.isPreparing }, 10)
        Disposer.dispose(result.executionConsole)
        assertTrue(handler.waitFor(TimeUnit.SECONDS.toMillis(10)))
        assertEquals(RunModel.NOT_STARTED, handler.exitCode)
        // The tab went before the end was applied: the run still hears of it.
        state.awaitHooks()
        assertEquals(listOf("not started: stopped while preparing"), state.hooks)
    }
}
