package de.terletzkiy.ansibility.run.molecule

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings as PlatformRunnerSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.notification.NotificationType
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.testFramework.PlatformTestUtil
import de.terletzkiy.ansibility.api.RoleTestState
import de.terletzkiy.ansibility.run.PlaybookExecutor
import de.terletzkiy.ansibility.run.PreparationReporter
import de.terletzkiy.ansibility.run.PreparedRun
import de.terletzkiy.ansibility.run.events.HostStatus
import de.terletzkiy.ansibility.run.events.RunModel
import de.terletzkiy.ansibility.run.notify.RecordedNotifications
import de.terletzkiy.ansibility.run.view.RunBanner
import de.terletzkiy.ansibility.run.view.RunViewTexts
import de.terletzkiy.ansibility.vault.VaultTestCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.ContinuationInterceptor

/**
 * A bulk Molecule run (plan amendments R16, R19): roles one after another in one process, each prepared right before it
 * runs, Stop (also while a role prepares), detach, its view actions, and its one end notification (D147–D149).
 */
class MoleculeBatchTest : VaultTestCase() {
    private object TestRunner : ProgramRunner<PlatformRunnerSettings> {
        override fun getRunnerId(): String = "AnsibilityMoleculeBatchTest"
        override fun canRun(executorId: String, profile: RunProfile): Boolean = true
        override fun execute(environment: ExecutionEnvironment) = Unit
    }

    private lateinit var falcon: String
    private val results get() = MoleculeResults.getInstance(project)
    private lateinit var notifications: RecordedNotifications
    private val batches = CopyOnWriteArrayList<List<String>>()

    override fun setUp() {
        super.setUp()
        falcon = projectRoot("falcon")
        for (role in listOf("web", "db")) {
            write("$falcon/roles/$role/tasks/main.yml", "- name: One\n  ansible.builtin.debug: {}\n")
            write("$falcon/roles/$role/molecule/default/molecule.yml", "---\ndriver:\n  name: default\n")
        }
        root(falcon)
        notifications = RecordedNotifications(project, testRootDisposable)
    }

    /** Records the bulk runs the notification's links start (role names). */
    private fun recordBatches() {
        MoleculeBatchLauncher.executeForTests = { batches += it.map { target -> target.name } }
    }

    override fun tearDown() {
        try {
            MoleculeRunContext.moleculeForTests = null
            results.resetForTests()
            MoleculeCleanup.getInstance(project).resetForTests()
            MoleculeLauncher.executeForTests = null
            MoleculeBatchLauncher.executeForTests = null
        } finally {
            super.tearDown()
        }
    }

    /** A `molecule` that runs [script] (sh), first on the path of every run. */
    private fun molecule(script: String): Path {
        val molecule = base.resolve("venv/bin/molecule")
        Files.createDirectories(molecule.parent)
        Files.writeString(molecule, "#!/bin/sh\n$script\n")
        molecule.toFile().setExecutable(true)
        MoleculeRunContext.moleculeForTests = { molecule }
        return molecule
    }

    private fun target(role: String) = MoleculeTarget(MoleculeSpec(vf("$falcon/roles/$role").path, executor = PlaybookExecutor.NATIVE), role, "falcon")

    private class Started(val handler: MoleculeBatchProcessHandler, val output: StringBuffer) {
        val model: RunModel get() = handler.modelForTests()
    }

    /**
     * Starts a bulk run of [targets] the way the platform does (the tab first, then start-notified); [prepare] stands
     * in for each role's preparation (null: the real one, through the profile).
     */
    private fun start(
        targets: List<MoleculeTarget>,
        output: StringBuffer = StringBuffer(),
        prepare: (suspend (MoleculeTarget, PreparationReporter) -> PreparedRun)? = null,
    ): Started {
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val profile = MoleculeBatchProfile(project, targets)
        val environment = ExecutionEnvironment(executor, TestRunner, MoleculeLauncher.settingsFor(project, targets.first().spec), project)
        val state = if (prepare == null) profile.getState(executor, environment) else MoleculeBatchState(environment, targets, prepare)
        val result = state.execute(executor, TestRunner)!!
        Disposer.register(testRootDisposable, result.executionConsole)
        assertEquals("Molecule: ${targets.size} roles (test)", profile.name)
        val handler = result.processHandler as MoleculeBatchProcessHandler
        assertFalse("the tab opens before anything prepares", handler.isStartNotified)
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                output.append(event.text)
            }
        })
        handler.startNotify()
        return Started(handler, output)
    }

    fun testABatchRunsItsRolesOneAfterAnother() {
        molecule(
            """
            echo 'INFO     [default > converge] Executing' >&2
            case "${'$'}(basename "${'$'}PWD")" in
              db) echo 'ERROR    [default > converge] Executed: Failed' >&2; exit 2 ;;
              *) echo 'INFO     [default > converge] Executed: Successful' >&2 ;;
            esac
            """.trimIndent(),
        )
        val lb = MoleculeTarget(MoleculeSpec(base.resolve("elsewhere/lb").toString()), "lb", "tern")
        val output = StringBuffer()
        // What the console showed when each role began to prepare: R19 prepares a role right before it runs.
        val seenAtPrepare = CopyOnWriteArrayList<Pair<String, String>>()
        val dispatchers = CopyOnWriteArrayList<Any?>()
        val run = start(listOf(target("web"), target("db"), lb), output) { target, reporter ->
            seenAtPrepare += target.name to output.toString()
            dispatchers += currentCoroutineContext()[ContinuationInterceptor]
            if (target === lb) throw ExecutionException("No Docker Compose service with Molecule mounts the role lb")
            MoleculePreparation.prepareAsync(project, target.spec, reporter)
        }
        // The batch begins each role on the EDT: wait dispatching events.
        PlatformTestUtil.waitWithEventsDispatching("the batch did not end", { run.handler.isProcessTerminated }, 60)
        PlatformTestUtil.waitWithEventsDispatching("the batch did not end", { run.model.finished }, 30)
        assertEquals("a role failed", 1, run.handler.exitCode)
        assertEquals(listOf("web", "db", "lb"), run.model.units.map { it.name })
        assertEquals(listOf(HostStatus.OK, HostStatus.FAILED, HostStatus.FAILED), run.model.units.map { it.status })
        assertEquals(listOf(0, 2, RunModel.NOT_STARTED), run.model.units.map { it.exitCode })
        val unit = run.model.units[2]
        assertEquals("the role says why it did not start", "No Docker Compose service with Molecule mounts the role lb", unit.problem)
        assertEquals("Not started: No Docker Compose service with Molecule mounts the role lb", RunViewTexts.unitStatus(unit, true))
        assertEquals("each role keeps its own stage", listOf(1, 1, 0), run.model.units.map { it.stages.size })
        assertEquals(listOf("web", "db", "lb"), seenAtPrepare.map { it.first })
        assertEquals("each role prepares, and its process is built, on IO", List(3) { Dispatchers.IO }, dispatchers)
        assertFalse("the first role prepares at once", "Executing" in seenAtPrepare[0].second)
        assertTrue("db prepares once web ran", "Executed: Successful" in seenAtPrepare[1].second && "Ran" !in seenAtPrepare[1].second)
        assertTrue("lb prepares once db ran", "Executed: Failed" in seenAtPrepare[2].second)
        val text = output.toString()
        assertTrue(text, "── web (falcon) ──" in text && "── db (falcon) ──" in text)
        assertTrue(text, "Preparing: reading the role, its scenarios and its Molecule service…" in text)
        assertTrue(text, "Not started: No Docker Compose service with Molecule mounts the role lb" in text)
        assertTrue(text, "Ran 3 of 3 roles: 2 failed." in text)
        assertEquals(RoleTestState.PASSED, results.scenarioState(vf("$falcon/roles/web").path, "default"))
        assertEquals(RoleTestState.FAILED, results.scenarioState(vf("$falcon/roles/db").path, "default"))
        assertEquals("nothing runs any more", RoleTestState.FAILED, results.stateOf(vf("$falcon/roles/db")))

        // R19: one notification for the whole run (the roles' processes tell nothing of their own).
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        val notification = notifications.runs.single()
        assertEquals("Molecule tests: 2 of 3 roles failed", notification.title)
        val lines = notification.content.split("<br>")
        assertEquals(listOf("db (falcon): converge failed", "lb (tern): not started: No Docker Compose service with Molecule mounts the role lb"), lines.take(2))
        assertTrue(lines.last(), lines.last().startsWith("1 passed"))
        assertEquals(listOf("Rerun Failed Roles", "Show Run"), RecordedNotifications.texts(notification))
        recordBatches()
        notifications.click(notification, "Rerun Failed Roles")
        assertEquals(listOf(listOf("db", "lb")), batches)
        assertEquals("the system notification has counts only", "1 passed · 2 failed · 0 not run", notifications.system.single().second.substringBeforeLast(" · "))
    }

    fun testStoppingABatchEndsTheCurrentRoleSkipsTheRestAndDestroysItsInstances() {
        val runs = ArrayList<MoleculeSpec>()
        MoleculeLauncher.executeForTests = { settings, _ -> runs += (settings.configuration as MoleculeConfiguration).spec }
        molecule("echo 'INFO     [default > converge] Executing' >&2\nexec sleep 30")
        val prepared = CopyOnWriteArrayList<PreparedRun>()
        val run = start(listOf(target("web"), target("db"))) { target, reporter ->
            MoleculePreparation.prepareAsync(project, target.spec, reporter).also { prepared += it }
        }
        PlatformTestUtil.waitWithEventsDispatching("the first role did not start", { run.model.units.firstOrNull()?.stages?.isNotEmpty() == true }, 30)
        run.handler.destroyProcess()
        // The batch begins each role on the EDT: wait dispatching events.
        PlatformTestUtil.waitWithEventsDispatching("the batch did not end", { run.handler.isProcessTerminated }, 30)
        PlatformTestUtil.waitWithEventsDispatching("the batch did not end", { run.model.finished }, 30)
        assertEquals(1, run.handler.exitCode)
        val (web, db) = run.model.units
        assertTrue("stopped, not failed", web.stopped)
        assertNull("db never ran", db.started)
        assertEquals("db was never prepared", 1, prepared.size)
        assertFalse("the stopped role's scripts are gone", Files.exists(prepared.single().secrets.callbackDir!!))
        val output = run.output.toString()
        assertTrue(output, "Stopped: 1 of 2 roles started, 0 failed." in output)
        assertTrue(output, "the instances of web (falcon) are destroyed in a run of their own" in output)
        PlatformTestUtil.waitWithEventsDispatching("the stopped role was not cleaned up", { runs.isNotEmpty() }, 30)
        assertEquals(listOf(target("web").spec.copy(command = MoleculeCommand.DESTROY)), runs)
        assertNull("no result from the stopped role", results.scenarioState(vf("$falcon/roles/web").path, "default"))
        assertEquals("it runs no more", RoleTestState.NOT_RUN, results.stateOf(vf("$falcon/roles/web")))

        // R19: one notification says where it stopped and what cleanup follows; the stopped role's destroy is automatic.
        val notification = notifications.runs.single()
        // The counts of the console's summary: web started (and was stopped), db never ran.
        assertEquals("Molecule tests stopped: 1 of 2 roles started", notification.title)
        assertEquals(NotificationType.WARNING, notification.type)
        val lines = notification.content.split("<br>")
        assertEquals("Stopped during web (falcon); its instances are destroyed in a run of their own", lines.first())
        assertTrue(lines.last(), lines.last().startsWith("0 passed · 0 failed · 1 stopped · 1 not run"))
        assertEquals(listOf("Run the 2 Remaining Roles", "Show Run"), RecordedNotifications.texts(notification))
        recordBatches()
        notifications.click(notification, "Run the 2 Remaining Roles")
        assertEquals("the stopped role and the one that never ran", listOf(listOf("web", "db")), batches)
    }

    fun testStopWhileARolePreparesEndsTheBatchAtOnce() {
        val runs = ArrayList<MoleculeSpec>()
        MoleculeLauncher.executeForTests = { settings, _ -> runs += (settings.configuration as MoleculeConfiguration).spec }
        molecule("exit 0")
        val late = await { MoleculePreparation.prepareAsync(project, target("web").spec) }
        val callbacks = late.secrets.callbackDir!!
        val gate = CompletableDeferred<Unit>()
        val asked = CopyOnWriteArrayList<String>()
        val run = start(listOf(target("web"), target("db"))) { target, reporter ->
            asked += target.name
            reporter.phase("waiting for the test")
            // A step that cannot be cancelled (a password manager, a git fetch): it ends after the Stop.
            withContext(NonCancellable) { gate.await() }
            late
        }
        PlatformTestUtil.waitWithEventsDispatching("web did not prepare", { run.model.units.firstOrNull()?.preparing != null }, 30)
        val web = run.model.units.first()
        assertEquals("Preparing: waiting for the test…", RunViewTexts.unitStatus(web, false))
        assertTrue("its row says so", RunViewTexts.showsUnitStatus(web))
        assertEquals(RoleTestState.RUNNING, results.stateOf(vf("$falcon/roles/web")))

        run.handler.destroyProcess()
        assertTrue("Stop does not wait for the preparation", run.handler.waitFor(TimeUnit.SECONDS.toMillis(5)))
        PlatformTestUtil.waitWithEventsDispatching("the batch did not end", { run.model.finished }, 30)
        assertTrue("stopped, not failed", web.stopped)
        assertNull(web.preparing)
        assertNull("db never ran", run.model.units[1].started)
        assertEquals(listOf("web"), asked)
        assertTrue(run.output.toString(), "Stopped: 1 of 2 roles started, 0 failed." in run.output)
        assertEquals("its results are balanced", RoleTestState.NOT_RUN, results.stateOf(vf("$falcon/roles/web")))

        // The preparation ends after all: what it produced is closed, nothing starts, no destroy (no instances yet).
        gate.complete(Unit)
        PlatformTestUtil.waitWithEventsDispatching("the late scripts were not deleted", { !Files.exists(callbacks) }, 30)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(runs.isEmpty())
        assertFalse(run.output.toString(), "── db (falcon) ──" in run.output)
        assertEquals("Molecule tests stopped: 1 of 2 roles started", notifications.runs.single().title)
        assertEquals("Stopped during web (falcon)", notifications.runs.single().content.split("<br>").first())
    }

    fun testDetachingABatchClosesTheScriptsOfItsRole() {
        val runs = ArrayList<MoleculeSpec>()
        MoleculeLauncher.executeForTests = { settings, _ -> runs += (settings.configuration as MoleculeConfiguration).spec }
        molecule("echo 'INFO     [default > converge] Executing' >&2\nexec sleep 5")
        val prepared = CopyOnWriteArrayList<PreparedRun>()
        val run = start(listOf(target("web"), target("db"))) { target, reporter ->
            MoleculePreparation.prepareAsync(project, target.spec, reporter).also { prepared += it }
        }
        PlatformTestUtil.waitWithEventsDispatching("the first role did not start", { run.model.units.firstOrNull()?.stages?.isNotEmpty() == true }, 30)
        run.handler.detachProcess()
        // The batch begins each role on the EDT: wait dispatching events.
        PlatformTestUtil.waitWithEventsDispatching("the batch did not end", { run.handler.isProcessTerminated }, 30)
        PlatformTestUtil.waitWithEventsDispatching("the batch did not end", { run.model.finished }, 30)
        assertFalse("the detached role's scripts are gone", Files.exists(prepared.single().secrets.callbackDir!!))
        assertEquals("db was never prepared", 1, prepared.size)
        assertTrue(run.model.units.first().stopped)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue("a detached test goes on: its instances stay", runs.isEmpty())
        assertEquals(RoleTestState.NOT_RUN, results.stateOf(vf("$falcon/roles/web")))
        assertTrue("a detached run tells nothing: it goes on without the IDE", notifications.runs.isEmpty())
    }

    fun testABatchThatPassedNotifiesOnceAfterItsLastRole() {
        molecule("echo 'INFO     [default > converge] Executing' >&2\necho 'INFO     [default > converge] Executed: Successful' >&2")
        val run = start(listOf(target("web"), target("db")))
        // The batch begins each role on the EDT: wait dispatching events.
        PlatformTestUtil.waitWithEventsDispatching("the batch did not end", { run.handler.isProcessTerminated }, 60)
        PlatformTestUtil.waitWithEventsDispatching("the batch did not notify", { notifications.runs.isNotEmpty() }, 30)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        val notification = notifications.runs.single()
        assertEquals("Molecule tests passed: 2 roles", notification.title)
        assertTrue(notification.content, notification.content.startsWith("web, db"))
        assertEquals(NotificationType.INFORMATION, notification.type)
        assertEquals(listOf("Show Run"), RecordedNotifications.texts(notification))
    }

    fun testThePlaysTabOfABatchRunsRolesAndStagesAgain() {
        val runs = ArrayList<MoleculeSpec>()
        MoleculeLauncher.executeForTests = { settings, _ -> runs += (settings.configuration as MoleculeConfiguration).spec }
        val batches = ArrayList<List<MoleculeTarget>>()
        MoleculeBatchLauncher.executeForTests = { batches += it }
        val targets = listOf(target("web"), target("db"))
        val actions = MoleculeBatchActions(project, targets)
        assertTrue(actions.unitActions && actions.stageActions && !actions.playbookActions)
        actions.runStage("default", "verify", targets[1].key)
        actions.runStage("default", "prepare", targets[1].key)
        assertEquals("that role's stage, alone", listOf(target("db").spec.copy(scenario = "default", command = MoleculeCommand.VERIFY)), runs)
        actions.runUnits(targets.map { it.key })
        assertEquals(listOf(listOf(target("web"), target("db"))), batches)
        actions.runUnits(listOf(targets[0].key))
        assertEquals("one role is a run of its own", target("web").spec, runs.last())
    }

    // ------------------------------------------------------------------ R19 integration fixes

    fun testTwoScenariosOfOneRoleAreTwoUnitsWithTheirOwnResults() {
        write("$falcon/roles/web/molecule/docker/molecule.yml", "---\ndriver:\n  name: default\n")
        refresh()
        // Arguments: test --scenario-name <scenario>; docker fails, default passes.
        molecule(
            """
            echo "INFO     [${'$'}3 > converge] Executing" >&2
            case "${'$'}3" in
              docker) echo "ERROR    [${'$'}3 > converge] Executed: Failed" >&2; exit 2 ;;
              *) echo "INFO     [${'$'}3 > converge] Executed: Successful" >&2 ;;
            esac
            """.trimIndent(),
        )
        val web = vf("$falcon/roles/web").path
        val targets = listOf("default", "docker").map { MoleculeTarget(MoleculeSpec(web, it, executor = PlaybookExecutor.NATIVE), "web", "falcon") }
        assertEquals("a key per target", 2, targets.map { it.key }.distinct().size)
        val run = start(targets)
        PlatformTestUtil.waitWithEventsDispatching("the batch did not end", { run.model.finished }, 60)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals("two units", listOf("web \u203a default", "web \u203a docker"), run.model.units.map { it.name })
        assertEquals(listOf(0, 2), run.model.units.map { it.exitCode })
        assertEquals("each scenario's stage in its own unit", listOf(listOf("default"), listOf("docker")), run.model.units.map { unit -> unit.stages.map { it.scenario } })
        assertEquals(RoleTestState.PASSED, results.scenarioState(web, "default"))
        assertEquals(RoleTestState.FAILED, results.scenarioState(web, "docker"))
        assertFalse("started and finished are balanced", results.isRunning(web))
        assertEquals(RoleTestState.FAILED, results.stateOf(vf("$falcon/roles/web")))
        val text = run.output.toString()
        assertTrue(text, "\u2500\u2500 web \u203a docker (falcon) \u2500\u2500" in text)
        val notification = notifications.runs.single()
        assertEquals("Molecule tests: 1 of 2 roles failed", notification.title)
        assertEquals("web \u203a docker (falcon): converge failed", notification.content.split("<br>").first())
        assertTrue(notification.content, notification.content.split("<br>").last().startsWith("1 passed"))
        val reruns = ArrayList<MoleculeSpec>()
        MoleculeLauncher.executeForTests = { settings, _ -> reruns += (settings.configuration as MoleculeConfiguration).spec }
        notifications.click(notification, "Rerun Failed Roles")
        assertEquals("the failed scenario only", listOf(targets[1].spec), reruns)
    }

    fun testARoleThatNeverBeginsKeepsItsDestroyCountdown() {
        val cleanup = MoleculeCleanup.getInstance(project)
        val web = vf("$falcon/roles/web").path
        val db = vf("$falcon/roles/db").path
        // Converges of web and db left their instances: both count down.
        val webCountdown = cleanup.runEnded(MoleculeSpec(web, "default", MoleculeCommand.CONVERGE), 2, null)!!
        val dbCountdown = cleanup.runEnded(MoleculeSpec(db, "default", MoleculeCommand.CONVERGE), 2, null)!!
        // The real preparation (through the profile), held while it looks for molecule.
        val release = CountDownLatch(1)
        MoleculeRunContext.moleculeForTests = {
            release.await(30, TimeUnit.SECONDS)
            null
        }
        try {
            val run = start(listOf(target("web"), target("db")))
            PlatformTestUtil.waitWithEventsDispatching("web did not prepare", { run.model.units.firstOrNull()?.preparing != null }, 30)
            assertEquals("web began: its countdown went", MoleculeCountdown.State.CANCELLED, webCountdown.state)
            assertSame("db did not begin: its countdown runs on", dbCountdown, cleanup.countdownOf(db))

            run.handler.destroyProcess()
            PlatformTestUtil.waitWithEventsDispatching("the batch did not end", { run.model.finished }, 30)
            PlatformTestUtil.waitWithEventsDispatching("web's countdown did not come back", { cleanup.countdownOf(web) != null }, 30)
            assertNull("db never ran", run.model.units[1].started)
            val again = cleanup.countdownOf(web)!!
            assertNotSame(webCountdown, again)
            assertEquals(MoleculeCountdown.State.COUNTING, again.state)
            assertEquals(2, again.minutes)
            assertEquals(webCountdown.spec, again.spec)
            assertSame("db never began", dbCountdown, cleanup.countdownOf(db))
            assertEquals(MoleculeCountdown.State.COUNTING, dbCountdown.state)
        } finally {
            release.countDown()
        }
    }

    fun testARoleThatDoesNotStartGetsItsDestroyCountdownBack() {
        val cleanup = MoleculeCleanup.getInstance(project)
        val web = vf("$falcon/roles/web").path
        val banner = RunBanner().also { it.component() }
        val countdown = cleanup.runEnded(MoleculeSpec(web, "default", MoleculeCommand.CONVERGE), 2, banner)!!
        val run = start(listOf(target("web"), target("db"))) { target, _ ->
            throw ExecutionException("No Docker Compose service with Molecule mounts the role ${target.name}")
        }
        PlatformTestUtil.waitWithEventsDispatching("the batch did not end", { run.model.finished }, 30)
        PlatformTestUtil.waitWithEventsDispatching("web's countdown did not come back", { cleanup.countdownOf(web) != null }, 30)
        assertEquals(listOf(RunModel.NOT_STARTED, RunModel.NOT_STARTED), run.model.units.map { it.exitCode })
        val again = cleanup.countdownOf(web)!!
        assertNotSame(countdown, again)
        assertEquals(MoleculeCountdown.State.COUNTING, again.state)
        assertEquals(2, again.minutes)
        assertEquals(countdown.spec, again.spec)
        assertTrue("in the converge's tab again: ${banner.messageForTests()}", banner.messageForTests().orEmpty().startsWith("web \u203a default: the instances are destroyed in "))
        assertFalse(results.isRunning(web))
    }

    fun testTwoScenariosOfARoleThatBothDoNotStartGetItsCountdownBack() {
        write("$falcon/roles/web/molecule/docker/molecule.yml", "---\ndriver:\n  name: default\n")
        refresh()
        val cleanup = MoleculeCleanup.getInstance(project)
        val web = vf("$falcon/roles/web").path
        val banner = RunBanner().also { it.component() }
        val countdown = cleanup.runEnded(MoleculeSpec(web, "default", MoleculeCommand.CONVERGE), 2, banner)!!
        // Both scenarios of web fail to prepare for a reason of the role's: the second begins before the first's end
        // reaches the EDT, and must not keep the countdown away as a run of the role.
        val targets = listOf("default", "docker").map { MoleculeTarget(MoleculeSpec(web, it, executor = PlaybookExecutor.NATIVE), "web", "falcon") }
        val run = start(targets) { _, _ -> throw ExecutionException("No Docker Compose service with Molecule mounts the role web") }
        PlatformTestUtil.waitWithEventsDispatching("the batch did not end", { run.model.finished }, 30)
        PlatformTestUtil.waitWithEventsDispatching("web's countdown did not come back", { cleanup.countdownOf(web) != null }, 30)
        assertEquals(listOf(RunModel.NOT_STARTED, RunModel.NOT_STARTED), run.model.units.map { it.exitCode })
        val again = cleanup.countdownOf(web)!!
        assertNotSame(countdown, again)
        assertEquals(MoleculeCountdown.State.COUNTING, again.state)
        assertEquals(2, again.minutes)
        assertEquals(countdown.spec, again.spec)
        assertFalse(results.isRunning(web))
    }
}
