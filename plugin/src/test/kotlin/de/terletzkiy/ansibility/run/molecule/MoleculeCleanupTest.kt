package de.terletzkiy.ansibility.run.molecule

import com.intellij.execution.RunManager
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings as PlatformRunnerSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.icons.AllIcons
import com.intellij.notification.Notification
import com.intellij.openapi.util.Disposer
import com.intellij.psi.SyntaxTraverser
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.ui.EditorNotificationPanel
import com.intellij.util.ref.GCWatcher
import com.intellij.util.ui.UIUtil
import de.terletzkiy.ansibility.api.RoleTestState
import de.terletzkiy.ansibility.api.RoleTests
import de.terletzkiy.ansibility.run.PlaybookExecutor
import de.terletzkiy.ansibility.run.PlaybookPreparation
import de.terletzkiy.ansibility.run.PreparingProcessHandler
import de.terletzkiy.ansibility.run.events.RunEvent
import de.terletzkiy.ansibility.run.events.RunModel
import de.terletzkiy.ansibility.run.notify.RecordedNotifications
import de.terletzkiy.ansibility.run.notify.RunNotifier
import de.terletzkiy.ansibility.run.notify.RunOutcomes
import de.terletzkiy.ansibility.run.notify.RunTarget
import de.terletzkiy.ansibility.run.settings.RunNotificationSettings
import de.terletzkiy.ansibility.run.settings.RunnerRootSettings
import de.terletzkiy.ansibility.run.settings.RunnerSettings
import de.terletzkiy.ansibility.run.view.AnsibleRunConsole
import de.terletzkiy.ansibility.run.view.RunBanner
import de.terletzkiy.ansibility.settings.RootKeys
import de.terletzkiy.ansibility.vault.VaultTestCase
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The destroy countdown after a Molecule run (plan amendment R15), the results of roles and scenarios (R16) and how a
 * Molecule run's end is told (R19, D147–D149: the countdown merged into the run's notification, automatic destroys).
 */
class MoleculeCleanupTest : VaultTestCase() {
    private object TestRunner : ProgramRunner<PlatformRunnerSettings> {
        override fun getRunnerId(): String = "AnsibilityMoleculeTest"
        override fun canRun(executorId: String, profile: RunProfile): Boolean = true
        override fun execute(environment: ExecutionEnvironment) = Unit
    }

    private lateinit var falcon: String
    private val role: String get() = "$falcon/roles/web"
    private var now = 1_000_000L
    private val runs = ArrayList<MoleculeSpec>()

    /** Whether each launched run was marked as started by the IDE itself (an automatic destroy). */
    private val automatic = ArrayList<Boolean>()
    private lateinit var notifications: RecordedNotifications
    private val cleanup get() = MoleculeCleanup.getInstance(project)
    private val results get() = MoleculeResults.getInstance(project)

    override fun setUp() {
        super.setUp()
        falcon = projectRoot("falcon")
        write("$role/tasks/main.yml", "- name: One\n  ansible.builtin.debug: {}\n")
        write("$role/molecule/default/molecule.yml", "---\ndriver:\n  name: default\n")
        write("$role/molecule/other/molecule.yml", "---\ndriver:\n  name: default\n")
        write("$falcon/roles/db/tasks/main.yml", "- name: One\n  ansible.builtin.debug: {}\n")
        cleanup.clockForTests = { now }
        MoleculeLauncher.executeForTests = { settings, _ ->
            runs += (settings.configuration as MoleculeConfiguration).spec
            automatic += (settings.configuration as MoleculeConfiguration).getUserData(MoleculeConfiguration.AUTOMATIC) == true
        }
        notifications = RecordedNotifications(project, testRootDisposable)
    }

    override fun tearDown() {
        try {
            cleanup.resetForTests()
            cleanup.clockForTests = null
            results.resetForTests()
            MoleculeLauncher.executeForTests = null
            MoleculeRunContext.moleculeForTests = null
            RunnerSettings.getInstance(project).loadState(RunnerSettings.StateBean())
            RunManager.getInstance(project).let { manager ->
                manager.getConfigurationSettingsList(MoleculeConfigurationType.getInstance()).forEach(manager::removeConfiguration)
            }
        } finally {
            super.tearDown()
        }
    }

    private fun spec(command: MoleculeCommand, scenario: String = "default", path: String = vf(role).path) = MoleculeSpec(path, scenario, command)

    /** The run of [spec] through its configuration, as the platform executes it: the tab first, not start-notified yet. */
    private fun execute(spec: MoleculeSpec): com.intellij.execution.ExecutionResult {
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val settings = MoleculeLauncher.settingsFor(project, spec)
        val environment = ExecutionEnvironment(executor, TestRunner, settings, project)
        val result = settings.configuration.getState(executor, environment)!!.execute(executor, TestRunner)!!
        Disposer.register(testRootDisposable, result.executionConsole)
        assertFalse("the tab opens before the run prepares", result.processHandler.isStartNotified)
        return result
    }

    /** A banner shown in a tab, as a test sees it. */
    private fun banner(): RunBanner = RunBanner().also { it.component() }

    fun testAConvergeIsDestroyedWhenNobodyKeepsItsInstances() {
        val banner = banner()
        val countdown = cleanup.runEnded(spec(MoleculeCommand.CONVERGE), 2, banner)!!
        assertEquals("web › default: the instances are destroyed in 2:00", banner.messageForTests())
        assertEquals(listOf("Keep Instances", "Destroy Now"), banner.linksForTests())
        val balloon = countdown.notificationForTests()!!
        assertEquals(listOf("Keep Instances", "Destroy Now"), balloon.actions.map { it.templateText })
        now += 61_000
        cleanup.tick()
        assertEquals("web › default: the instances are destroyed in 0:59", banner.messageForTests())
        assertTrue(runs.isEmpty())
        now += 59_000
        cleanup.tick()
        assertEquals(MoleculeCountdown.State.DESTROYED, countdown.state)
        assertNull("the banner goes", banner.messageForTests())
        assertTrue("and the balloon", balloon.isExpired)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals("the same role and scenario, the way the run went", listOf(spec(MoleculeCommand.DESTROY)), runs)
        assertNull(cleanup.countdownOf(vf(role).path))
    }

    fun testKeptInstancesStayUntilDestroyNow() {
        val banner = banner()
        val countdown = cleanup.runEnded(spec(MoleculeCommand.VERIFY, ""), 2, banner)!!
        assertEquals("web (all scenarios): the instances are destroyed in 2:00", banner.messageForTests())
        banner.clickForTests("Keep Instances")
        assertEquals(MoleculeCountdown.State.KEPT, countdown.state)
        assertEquals("web (all scenarios): the instances are kept", banner.messageForTests())
        assertEquals(listOf("Destroy Now"), banner.linksForTests())
        assertTrue(countdown.notificationForTests()!!.isExpired)
        now += 600_000
        cleanup.tick()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue("kept instances are not destroyed", runs.isEmpty())
        assertSame(countdown, cleanup.countdownOf(vf(role).path))
        banner.clickForTests("Destroy Now")
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(listOf(spec(MoleculeCommand.DESTROY, "")), runs)
        assertNull(banner.messageForTests())
    }

    fun testAnotherRunOfTheRoleCancelsTheCountdown() {
        val banner = banner()
        val countdown = cleanup.runEnded(spec(MoleculeCommand.IDEMPOTENCE), 2, banner)!!
        cleanup.runStarting(spec(MoleculeCommand.CONVERGE, "default", vf("$falcon/roles/db").path))
        assertEquals("another role leaves it alone", MoleculeCountdown.State.COUNTING, countdown.state)
        cleanup.runStarting(spec(MoleculeCommand.VERIFY, "other"))
        assertEquals("any run of the role stops it", MoleculeCountdown.State.CANCELLED, countdown.state)
        assertNull(banner.messageForTests())
        assertTrue(countdown.notificationForTests()!!.isExpired)
        now += 600_000
        cleanup.tick()
        countdown.destroyNow()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(runs.isEmpty())
        val next = cleanup.runEnded(spec(MoleculeCommand.CONVERGE), 2, banner)!!
        val replaced = cleanup.runEnded(spec(MoleculeCommand.CONVERGE, "other"), 2, banner)!!
        assertEquals("a new countdown of the role replaces the old one", MoleculeCountdown.State.CANCELLED, next.state)
        assertSame(replaced, cleanup.countdownOf(vf(role).path))
    }

    fun testOnlyRunsThatLeaveInstancesCountDown() {
        for (command in MoleculeCommand.entries) {
            val countdown = cleanup.runEnded(spec(command), 2, null)
            assertEquals(command.id, command in listOf(MoleculeCommand.CONVERGE, MoleculeCommand.VERIFY, MoleculeCommand.IDEMPOTENCE), countdown != null)
            cleanup.runStarting(spec(command))
        }
        assertNull("0 minutes keeps the instances", cleanup.runEnded(spec(MoleculeCommand.CONVERGE), 0, null))
    }

    fun testAStoppedRunCleansUp() {
        val banner = banner()
        assertNull("a stopped test destroys its instances at once", cleanup.runEnded(spec(MoleculeCommand.TEST), 0, banner, stopped = true))
        assertEquals("web \u203a default: stopped, so its instances are destroyed in a run of their own", banner.messageForTests())
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(listOf(spec(MoleculeCommand.DESTROY)), runs)
        assertNull("a test that ended by itself destroyed them itself", cleanup.runEnded(spec(MoleculeCommand.TEST, "other"), 2, null))
        assertNotNull("a stopped test that keeps its instances counts down", cleanup.runEnded(spec(MoleculeCommand.TEST_KEEP), 2, null, stopped = true))
        assertNotNull("a stopped converge counts down", cleanup.runEnded(spec(MoleculeCommand.CONVERGE, "other"), 2, null, stopped = true))
        results.finished(spec(MoleculeCommand.TEST), emptyList(), 0)
        results.finished(spec(MoleculeCommand.TEST), emptyList(), 130, stopped = true)
        assertEquals("a stopped run says nothing about the scenario", RoleTestState.PASSED, results.scenarioState(vf(role).path, "default"))
    }

    fun testStoppingATestRunDestroysItsInstances() {
        root(falcon)
        val molecule = base.resolve("venv/bin/molecule")
        Files.createDirectories(molecule.parent)
        Files.writeString(molecule, "#!/bin/sh\necho 'INFO     [default > converge] Executing' >&2\nexec sleep 30\n")
        molecule.toFile().setExecutable(true)
        MoleculeRunContext.moleculeForTests = { molecule }
        val spec = MoleculeSpec(vf(role).path, "default", MoleculeCommand.TEST, PlaybookExecutor.NATIVE)
        val result = execute(spec)
        val output = StringBuffer()
        result.processHandler.addProcessListener(object : com.intellij.execution.process.ProcessListener {
            override fun onTextAvailable(event: com.intellij.execution.process.ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                output.append(event.text)
            }
        })
        result.processHandler.startNotify()
        PlatformTestUtil.waitWithEventsDispatching("the test did not start", { "Executing" in output }, 30)
        result.processHandler.destroyProcess()
        assertTrue(result.processHandler.waitFor(TimeUnit.SECONDS.toMillis(30)))
        PlatformTestUtil.waitWithEventsDispatching("the stopped test was not cleaned up", { runs.isNotEmpty() }, 30)
        assertEquals(listOf(spec.copy(command = MoleculeCommand.DESTROY)), runs)
        assertEquals("the IDE's own destroy: it tells only of a failure", listOf(true), automatic)
        assertNull("no result from a stopped run", results.scenarioState(vf(role).path, "default"))
        assertNull("no countdown: the instances go now", cleanup.countdownOf(vf(role).path))
        // R19: the stop is told as a warning that says where it stopped and what cleanup follows.
        val notification = notifications.runs.single()
        assertEquals("Molecule test stopped: web \u203a default", notification.title)
        assertEquals(com.intellij.notification.NotificationType.WARNING, notification.type)
        assertTrue(notification.content, Regex("Stopped during converge after .+; its instances are destroyed in a run of their own\\.").matches(notification.content))
        assertEquals(listOf("Show Run", "Run Again"), RecordedNotifications.texts(notification))
    }

    fun testTheRunnerSettingsKeepTheMinutes() {
        val settings = RunnerSettings.getInstance(project)
        assertEquals(2, RunnerRootSettings.DEFAULT.moleculeDestroyMinutes)
        settings.update("falcon") { it.copy(moleculeDestroyMinutes = 0) }
        settings.update("tern") { it.copy(moleculeDestroyMinutes = 5) }
        val state = settings.state
        val bean = state.roots.first { it.key == "tern" }
        bean.moleculeDestroyMinutes = 100_000
        settings.loadState(state)
        assertEquals(0, settings.rootSettings("falcon").moleculeDestroyMinutes)
        assertEquals("capped", RunnerRootSettings.MAX_MOLECULE_DESTROY_MINUTES, settings.rootSettings("tern").moleculeDestroyMinutes)
    }

    fun testTheResultsFollowTheRuns() {
        val events = ArrayList<String>()
        project.messageBus.connect(testRootDisposable).subscribe(RoleTests.TOPIC, RoleTests.Listener { events += "changed" })
        assertEquals(RoleTestState.NOT_RUN, results.stateOf(vf(role)))
        assertEquals("no scenarios", RoleTestState.NONE, results.stateOf(vf("$falcon/roles/db")))
        val converge = spec(MoleculeCommand.CONVERGE, "")
        results.started(converge)
        assertEquals(RoleTestState.RUNNING, results.stateOf(vf(role)))
        val model = RunModel().apply {
            apply(RunEvent.Stage(1.0, "default", "converge", null))
            apply(RunEvent.Stage(2.0, "default", "converge", "Successful"))
            apply(RunEvent.Stage(3.0, "other", "converge", null))
            apply(RunEvent.Stage(4.0, "other", "converge", "Failed"))
            finish(1)
        }
        results.finished(converge, model.stages, 1)
        assertEquals(RoleTestState.PASSED, results.scenarioState(vf(role).path, "default"))
        assertEquals(RoleTestState.FAILED, results.scenarioState(vf(role).path, "other"))
        assertEquals("the worst scenario", RoleTestState.FAILED, results.stateOf(vf(role)))

        // Without stages (no run view) the exit code decides for the scenarios the run covered; a destroy is no test.
        results.finished(spec(MoleculeCommand.TEST, ""), emptyList(), 0)
        assertEquals(RoleTestState.PASSED, results.stateOf(vf(role)))
        results.finished(spec(MoleculeCommand.DESTROY, "other"), emptyList(), 1)
        assertEquals(RoleTestState.PASSED, results.scenarioState(vf(role).path, "other"))
        // Molecule failed after its last stage line: that scenario failed.
        val after = RunModel().apply {
            apply(RunEvent.Stage(1.0, "other", "verify", null))
            apply(RunEvent.Stage(2.0, "other", "verify", "Successful"))
            finish(2)
        }
        results.finished(spec(MoleculeCommand.VERIFY, "other"), after.stages, 2)
        assertEquals(RoleTestState.FAILED, results.scenarioState(vf(role).path, "other"))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(events.size >= 4)

        // The gutter of the scenario shows it like a test result.
        myFixture.configureFromExistingVirtualFile(vf("$role/molecule/default/molecule.yml"))
        val info = SyntaxTraverser.psiTraverser(myFixture.file).filter { it.firstChild == null }.toList().firstNotNullOf { MoleculeRunLineMarkerContributor().getInfo(it) }
        assertSame(AllIcons.RunConfigurations.TestState.Green2, info.icon)
        assertSame(AllIcons.RunConfigurations.TestState.Red2, MoleculeIcons.gutter(RoleTestState.FAILED, all = true))
        assertSame(AllIcons.RunConfigurations.TestState.Run_run, MoleculeIcons.gutter(null, all = true))
    }

    fun testAConvergeRunReportsItsResultAndCountsDownWhenItEnds() {
        root(falcon)
        val molecule = base.resolve("venv/bin/molecule")
        Files.createDirectories(molecule.parent)
        Files.writeString(molecule, "#!/bin/sh\necho 'INFO     [default > converge] Executing' >&2\nsleep 1\necho 'INFO     [default > converge] Executed: Successful' >&2\n")
        molecule.toFile().setExecutable(true)
        MoleculeRunContext.moleculeForTests = { molecule }
        val spec = MoleculeSpec(vf(role).path, "default", MoleculeCommand.CONVERGE, PlaybookExecutor.NATIVE)
        val result = execute(spec)
        // R19: the tab opens before the run prepares; the role runs once its process starts.
        assertEquals(RoleTestState.NOT_RUN, results.stateOf(vf(role)))
        result.processHandler.startNotify()
        PlatformTestUtil.waitWithEventsDispatching("the role did not run", { results.stateOf(vf(role)) == RoleTestState.RUNNING }, 30)
        assertTrue(result.processHandler.waitFor(TimeUnit.SECONDS.toMillis(30)))
        PlatformTestUtil.waitWithEventsDispatching("the run's end was not reported", { cleanup.countdownOf(vf(role).path) != null }, 30)
        assertEquals(RoleTestState.PASSED, results.scenarioState(vf(role).path, "default"))
        val panel = UIUtil.findComponentsOfType(result.executionConsole.component, EditorNotificationPanel::class.java).single()
        assertTrue(panel.text, "web \u203a default: the instances are destroyed in 2:00" in panel.text.orEmpty())
    }

    fun testARunThatDidNotStartLeavesTheResultsAloneAndCountsNothingDown() {
        root(falcon)
        MoleculeRunContext.moleculeForTests = { null }
        // A converge (it would count down) of a scenario the role does not have: its preparation fails.
        val spec = MoleculeSpec(vf(role).path, "nope", MoleculeCommand.CONVERGE, PlaybookExecutor.NATIVE)
        val result = execute(spec)
        val output = StringBuffer()
        result.processHandler.addProcessListener(object : com.intellij.execution.process.ProcessListener {
            override fun onTextAvailable(event: com.intellij.execution.process.ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                output.append(event.text)
            }
        })
        result.processHandler.startNotify()
        assertTrue(result.processHandler.waitFor(TimeUnit.SECONDS.toMillis(30)))
        assertEquals(RunModel.NOT_STARTED, result.processHandler.exitCode)
        assertTrue(output.toString(), "Not started: The role web has no Molecule scenario nope" in output)
        val panel = { UIUtil.findComponentsOfType(result.executionConsole.component, EditorNotificationPanel::class.java).singleOrNull() }
        PlatformTestUtil.waitWithEventsDispatching("the banner does not say it", { panel()?.text.orEmpty().startsWith("Not started") }, 30)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals("never running", RoleTestState.NOT_RUN, results.stateOf(vf(role)))
        assertNull("no result", results.scenarioState(vf(role).path, "default"))
        assertNull("no countdown", cleanup.countdownOf(vf(role).path))
        assertTrue(runs.isEmpty())
    }

    fun testADetachedRunLeavesItsInstancesAndResultsAlone() {
        root(falcon)
        val molecule = base.resolve("venv/bin/molecule")
        Files.createDirectories(molecule.parent)
        Files.writeString(molecule, "#!/bin/sh\necho 'INFO     [default > converge] Executing' >&2\nexec sleep 5\n")
        molecule.toFile().setExecutable(true)
        MoleculeRunContext.moleculeForTests = { molecule }
        val spec = MoleculeSpec(vf(role).path, "default", MoleculeCommand.CONVERGE, PlaybookExecutor.NATIVE)
        val scriptsBase = PlaybookPreparation.secretsBase()
        Files.createDirectories(scriptsBase)
        val before = Files.list(scriptsBase).use { it.toList() }.toSet()
        val result = execute(spec)
        val output = StringBuffer()
        result.processHandler.addProcessListener(object : com.intellij.execution.process.ProcessListener {
            override fun onTextAvailable(event: com.intellij.execution.process.ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                output.append(event.text)
            }
        })
        result.processHandler.startNotify()
        PlatformTestUtil.waitWithEventsDispatching("the converge did not start", { "Executing" in output }, 30)
        val scripts = Files.list(scriptsBase).use { it.toList() }.filter { it !in before }
        assertEquals("the run's scripts (its callback)", 1, scripts.size)
        PlatformTestUtil.waitWithEventsDispatching("the role does not run", { results.stateOf(vf(role)) == RoleTestState.RUNNING }, 30)
        result.processHandler.detachProcess()
        assertTrue(result.processHandler.waitFor(TimeUnit.SECONDS.toMillis(30)))
        PlatformTestUtil.waitWithEventsDispatching("the run's end was not applied", { results.stateOf(vf(role)) != RoleTestState.RUNNING }, 30)
        assertEquals("no result: it goes on without the IDE", RoleTestState.NOT_RUN, results.stateOf(vf(role)))
        assertNull("no countdown: its instances are in use", cleanup.countdownOf(vf(role).path))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue("no destroy", runs.isEmpty())
        assertFalse("its scripts are deleted", Files.exists(scripts.single()))
    }

    // ------------------------------------------------------------------ R19: the run's end notification

    /** A local `molecule` that runs [script] (sh). */
    private fun molecule(script: String) {
        val molecule = base.resolve("venv/bin/molecule")
        Files.createDirectories(molecule.parent)
        Files.writeString(molecule, "#!/bin/sh\n$script\n")
        molecule.toFile().setExecutable(true)
        MoleculeRunContext.moleculeForTests = { molecule }
    }

    private val passingConverge = "echo 'INFO     [default > converge] Executing' >&2\necho 'INFO     [default > converge] Executed: Successful' >&2"

    fun testAConvergeNotifiesOnceAndItsNotificationCarriesTheCountdown() {
        root(falcon)
        molecule(passingConverge)
        val spec = MoleculeSpec(vf(role).path, "default", MoleculeCommand.CONVERGE, PlaybookExecutor.NATIVE)
        val result = execute(spec)
        result.processHandler.startNotify()
        PlatformTestUtil.waitWithEventsDispatching("the run's end was not reported", { cleanup.countdownOf(vf(role).path) != null }, 30)
        val countdown = cleanup.countdownOf(vf(role).path)!!
        val run = notifications.runs.single()
        assertEquals("Molecule converge passed: web \u203a default", run.title)
        assertTrue(run.content, run.content.endsWith("The instances are destroyed in 2 min unless you keep them."))
        assertEquals(listOf("Keep Instances", "Destroy Now", "Show Run"), RecordedNotifications.texts(run))
        assertTrue("no balloon of the countdown's own", notifications.countdowns.isEmpty())
        assertNull(countdown.notificationForTests())
        assertSame(run, countdown.runNotificationForTests())
        assertEquals("the system notification", "Molecule converge passed: web \u203a default", notifications.system.single().first)

        notifications.click(run, "Keep Instances")
        assertEquals(MoleculeCountdown.State.KEPT, countdown.state)
        assertFalse("the run's notification stays in the history", run.isExpired)
        val banner = (result.executionConsole as AnsibleRunConsole)
        assertTrue(UIUtil.findComponentsOfType(banner.component, EditorNotificationPanel::class.java).single().text.orEmpty().contains("the instances are kept"))
        notifications.click(run, "Destroy Now")
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(listOf(spec.copy(command = MoleculeCommand.DESTROY)), runs)
        assertEquals("Destroy Now is the user's, not automatic", listOf(false), automatic)
        notifications.click(run, "Destroy Now")
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals("a link of an ended countdown does nothing", 1, runs.size)
    }

    fun testAnEndedCountdownLetsItsRunTabGo() {
        // The run's notification stays in the history after its countdown ended; it must not keep the run's tab.
        val (notification, banner) = endedCountdownNotification()
        assertTrue("the notification keeps the ended countdown's banner, and so the run's tab", banner.tryCollect(10_000))
        assertFalse("it stays in the history", notification.isExpired)
        notifications.click(notification, "Destroy Now")
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue("its links do nothing any more", runs.isEmpty())
    }

    /**
     * A converge's countdown whose links are on its run's end notification, ended by another run of the role; only the
     * notification is kept, the run tab's banner only watched.
     */
    private fun endedCountdownNotification(): Pair<Notification, GCWatcher> {
        val banner = banner()
        val countdown = cleanup.runEnded(spec(MoleculeCommand.CONVERGE), 2, banner, balloon = false)!!
        val outcome = RunOutcomes.molecule("web \u203a default", MoleculeCommand.CONVERGE, null, 0, false, 3.0, countdownMinutes = 2)
        val target = RunTarget("web", DefaultRunExecutor.getRunExecutorInstance(), NopProcessHandler(), null)
        val notification = RunNotifier.getInstance(project).runEnded(outcome, target, countdown.notificationActions())!!
        countdown.attach(notification)
        assertEquals(listOf("Keep Instances", "Destroy Now"), RecordedNotifications.texts(notification))
        cleanup.runStarting(spec(MoleculeCommand.CONVERGE))
        assertEquals(MoleculeCountdown.State.CANCELLED, countdown.state)
        assertNull(banner.messageForTests())
        return notification to GCWatcher.tracking(banner)
    }

    fun testWithoutARunNotificationTheCountdownShowsItsOwnBalloon() {
        root(falcon)
        molecule(passingConverge)
        val spec = MoleculeSpec(vf(role).path, "default", MoleculeCommand.CONVERGE, PlaybookExecutor.NATIVE)
        RunNotifier.getInstance(project).inViewForTests = { true }
        execute(spec).processHandler.startNotify()
        PlatformTestUtil.waitWithEventsDispatching("the run's end was not reported", { cleanup.countdownOf(vf(role).path) != null }, 30)
        assertTrue("the user watches the run", notifications.runs.isEmpty())
        val balloon = notifications.countdowns.single()
        assertSame(balloon, cleanup.countdownOf(vf(role).path)!!.notificationForTests())
        assertEquals(listOf("Keep Instances", "Destroy Now"), RecordedNotifications.texts(balloon))

        RunNotificationSettings.getInstance().whenToNotify = RunNotificationSettings.When.NEVER
        RunNotifier.getInstance(project).inViewForTests = { false }
        execute(spec).processHandler.startNotify()
        PlatformTestUtil.waitWithEventsDispatching("the second run's countdown did not start", { notifications.countdowns.size == 2 }, 30)
        assertTrue("notifications are off", notifications.runs.isEmpty())
        assertTrue("the first countdown went with the new run", balloon.isExpired)
    }

    fun testAutomaticDestroysNotifyOnlyWhenTheyFail() {
        // A countdown that runs out destroys by itself; so does a stopped test.
        cleanup.runEnded(spec(MoleculeCommand.CONVERGE), 2, null)
        now += 121_000
        cleanup.tick()
        cleanup.runEnded(spec(MoleculeCommand.TEST, "other"), 0, null, stopped = true)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(listOf(spec(MoleculeCommand.DESTROY), spec(MoleculeCommand.DESTROY, "other")), runs)
        assertEquals(listOf(true, true), automatic)

        // The destroy runs: one that passes says nothing, one that fails says so, the user's own says it passed.
        root(falcon)
        val exit = base.resolve("exit-code")
        molecule("exit $(cat '$exit')")
        val destroy = MoleculeSpec(vf(role).path, "default", MoleculeCommand.DESTROY, PlaybookExecutor.NATIVE)
        val counts = ArrayList<Int>()
        for ((code, marked) in listOf(0 to true, 1 to true, 0 to false)) {
            Files.writeString(exit, code.toString())
            val configuration = MoleculeLauncher.settingsFor(project, destroy).configuration as MoleculeConfiguration
            configuration.putUserData(MoleculeConfiguration.AUTOMATIC, marked.takeIf { it })
            val result = execute(destroy)
            assertNull("getState takes the mark", configuration.getUserData(MoleculeConfiguration.AUTOMATIC))
            result.processHandler.startNotify()
            assertTrue(result.processHandler.waitFor(TimeUnit.SECONDS.toMillis(30)))
            val view = (result.executionConsole as AnsibleRunConsole).viewForTests()!!
            PlatformTestUtil.waitWithEventsDispatching("the run's end was not applied", { view.tree.emptyText.text.startsWith("No plays") }, 30)
            counts += notifications.runs.size
        }
        assertEquals("the automatic destroy that passed says nothing", listOf(0, 1, 2), counts)
        assertEquals(listOf("Molecule destroy failed: web \u203a default", "Molecule destroy finished: web \u203a default"), notifications.runs.map { it.title })
        assertTrue(notifications.runs[0].content, notifications.runs[0].content.startsWith("molecule destroy exited with code 1"))
    }

    fun testARunWhoseTabWasClosedStillEnds() {
        root(falcon)
        molecule("echo 'INFO     [default > converge] Executing' >&2\nsleep 2\necho 'INFO     [default > converge] Executed: Successful' >&2")
        val spec = MoleculeSpec(vf(role).path, "default", MoleculeCommand.CONVERGE, PlaybookExecutor.NATIVE)
        val result = execute(spec)
        result.processHandler.startNotify()
        PlatformTestUtil.waitWithEventsDispatching("the role did not run", { results.stateOf(vf(role)) == RoleTestState.RUNNING }, 30)
        // The user closes the tab and lets the process go on.
        Disposer.dispose(result.executionConsole)
        assertTrue(result.processHandler.waitFor(TimeUnit.SECONDS.toMillis(30)))
        PlatformTestUtil.waitWithEventsDispatching("the run's end was lost with its tab", { notifications.runs.isNotEmpty() }, 30)
        assertEquals("its result", RoleTestState.PASSED, results.scenarioState(vf(role).path, "default"))
        assertNotNull("its countdown", cleanup.countdownOf(vf(role).path))
        val run = notifications.runs.single()
        assertEquals("Molecule converge passed: web \u203a default", run.title)
        assertEquals("no Show Run: the tab is gone", listOf("Keep Instances", "Destroy Now"), RecordedNotifications.texts(run))
    }

    fun testARunThatDidNotStartNotifiesWhyAndOffersTheRunnerSettings() {
        val root = root(falcon)
        MoleculeRunContext.moleculeForTests = { null }
        val result = execute(MoleculeSpec(vf(role).path, "nope", MoleculeCommand.CONVERGE, PlaybookExecutor.NATIVE))
        result.processHandler.startNotify()
        PlatformTestUtil.waitWithEventsDispatching("the failure was not told", { notifications.runs.isNotEmpty() }, 30)
        val run = notifications.runs.single()
        assertEquals("Molecule converge did not start: web \u203a nope", run.title)
        assertEquals("The role web has no Molecule scenario nope", run.content)
        assertEquals(listOf("Open Runner Settings", "Run Again", "Show Run"), RecordedNotifications.texts(run))
        val opened = ArrayList<String?>()
        RunNotifier.getInstance(project).openSettingsForTests = { opened += it }
        notifications.click(run, "Open Runner Settings")
        assertEquals("the role's root", listOf(RootKeys.keyOf(project, root.dir)), opened)
    }

    // ------------------------------------------------------------------ R19 integration fixes

    /** What [result]'s process prints, from now on. */
    private fun output(result: com.intellij.execution.ExecutionResult): StringBuffer {
        val output = StringBuffer()
        result.processHandler.addProcessListener(object : com.intellij.execution.process.ProcessListener {
            override fun onTextAvailable(event: com.intellij.execution.process.ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                output.append(event.text)
            }
        })
        return output
    }

    /** Runs what MoleculeLauncher starts (the destroys) for real, as the platform does: each run with its output. */
    private fun runLaunched(): List<Pair<com.intellij.execution.ExecutionResult, StringBuffer>> {
        val launched = CopyOnWriteArrayList<Pair<com.intellij.execution.ExecutionResult, StringBuffer>>()
        MoleculeLauncher.executeForTests = { settings, executor ->
            runs += (settings.configuration as MoleculeConfiguration).spec
            val environment = ExecutionEnvironment(executor, TestRunner, settings, project)
            val result = settings.configuration.getState(executor, environment)!!.execute(executor, TestRunner)!!
            Disposer.register(testRootDisposable, result.executionConsole)
            launched += result to output(result)
            result.processHandler.startNotify()
        }
        return launched
    }

    private val waitingForTheDestroy = "Preparing: waiting for the destroy of web \u203a default to finish\u2026"

    fun testARunOfTheRoleWaitsForTheDestroyOfAStoppedTest() {
        root(falcon)
        val gate = base.resolve("destroy-gate")
        // The destroy runs until the test lets it end; any other command converges at once.
        molecule(
            """
            case "${'$'}1" in
              destroy) echo 'destroying' >&2; while [ ! -f '$gate' ]; do sleep 0.1; done ;;
              *) echo 'INFO     [default > converge] Executing' >&2 ;;
            esac
            """.trimIndent(),
        )
        val destroys = runLaunched()
        val spec = MoleculeSpec(vf(role).path, "default", MoleculeCommand.TEST, PlaybookExecutor.NATIVE)
        // The user stopped a test of web: its instances are destroyed in a run of their own, which counts at once.
        assertNull(cleanup.runEnded(spec, 2, null, stopped = true))
        assertTrue(cleanup.isDestroying(spec.roleDir))

        // Run Again, right away.
        val again = execute(spec)
        val output = output(again)
        again.processHandler.startNotify()
        PlatformTestUtil.waitWithEventsDispatching("the destroy did not start", { destroys.singleOrNull()?.second?.contains("destroying") == true }, 30)
        PlatformTestUtil.waitWithEventsDispatching("the run does not wait for the destroy", { waitingForTheDestroy in output }, 30)
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1500)
        PlatformTestUtil.waitWithEventsDispatching("the clock stopped", { System.nanoTime() > until }, 10)
        assertFalse("the test does not start while the destroy runs: $output", "Executing" in output)
        assertTrue((again.processHandler as PreparingProcessHandler).isPreparing)

        Files.writeString(gate, "")
        PlatformTestUtil.waitWithEventsDispatching("the run did not go on after the destroy", { "Executing" in output }, 30)
        assertTrue("the destroy ended first", destroys.single().first.processHandler.isProcessTerminated)
        assertFalse(cleanup.isDestroying(spec.roleDir))
        PlatformTestUtil.waitWithEventsDispatching("the run did not end", { again.processHandler.isProcessTerminated }, 30)
        assertEquals(0, again.processHandler.exitCode)
    }

    fun testStopWhileARunWaitsForADestroyEndsItNotStarted() {
        root(falcon)
        molecule(passingConverge)
        val spec = MoleculeSpec(vf(role).path, "default", MoleculeCommand.CONVERGE, PlaybookExecutor.NATIVE)
        // A stopped test's destroy, launched but not under way yet (MoleculeLauncher only records it).
        cleanup.runEnded(spec.copy(command = MoleculeCommand.TEST), 0, null, stopped = true)
        val run = execute(spec)
        val output = output(run)
        run.processHandler.startNotify()
        PlatformTestUtil.waitWithEventsDispatching("the run does not wait for the destroy", { waitingForTheDestroy in output }, 30)

        run.processHandler.destroyProcess()
        PlatformTestUtil.waitWithEventsDispatching("Stop did not end the wait", { run.processHandler.isProcessTerminated }, 5)
        assertEquals(RunModel.NOT_STARTED, run.processHandler.exitCode)
        assertTrue(output.toString(), "Not started: stopped while preparing" in output)
        assertFalse("nothing ran", "Executing" in output)
        assertTrue("the destroy still counts", cleanup.isDestroying(spec.roleDir))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue("the user stopped it: nothing to tell", notifications.runs.isEmpty())
    }

    fun testADestroyThatNeverStartsDoesNotHoldRunsBack() {
        root(falcon)
        molecule(passingConverge)
        cleanup.launchTimeoutMillisForTests = 500
        val spec = MoleculeSpec(vf(role).path, "default", MoleculeCommand.CONVERGE, PlaybookExecutor.NATIVE)
        // The platform never starts the stopped test's destroy (MoleculeLauncher only records it).
        cleanup.runEnded(spec.copy(command = MoleculeCommand.TEST), 0, null, stopped = true)
        val run = execute(spec)
        val output = output(run)
        run.processHandler.startNotify()
        PlatformTestUtil.waitWithEventsDispatching("the run waits for good", { "Executing" in output }, 30)
        assertTrue(output.toString(), waitingForTheDestroy in output)
        assertFalse(cleanup.isDestroying(spec.roleDir))
        PlatformTestUtil.waitWithEventsDispatching("the run did not end", { run.processHandler.isProcessTerminated }, 30)

        // A destroy whose launch fails counts no longer either.
        MoleculeLauncher.executeForTests = { _, _ -> throw IllegalStateException("refused") }
        MoleculeCleanup.destroy(project, spec)
        assertTrue(cleanup.isDestroying(spec.roleDir))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertFalse(cleanup.isDestroying(spec.roleDir))
    }

    fun testARunThatDoesNotStartPutsTheCountdownBack() {
        root(falcon)
        MoleculeRunContext.moleculeForTests = { null }
        val banner = banner()
        val countdown = cleanup.runEnded(spec(MoleculeCommand.CONVERGE), 2, banner)!!
        val balloons = notifications.countdowns.size
        // A verify of a scenario the role does not have: its start cancels the countdown, then its preparation fails.
        val failing = MoleculeSpec(vf(role).path, "nope", MoleculeCommand.VERIFY, PlaybookExecutor.NATIVE)
        val result = execute(failing)
        assertEquals(MoleculeCountdown.State.CANCELLED, countdown.state)
        assertNull(cleanup.countdownOf(vf(role).path))
        result.processHandler.startNotify()
        PlatformTestUtil.waitWithEventsDispatching("the countdown did not come back", { cleanup.countdownOf(vf(role).path) != null }, 30)
        val again = cleanup.countdownOf(vf(role).path)!!
        assertNotSame(countdown, again)
        assertEquals(MoleculeCountdown.State.COUNTING, again.state)
        assertEquals("the same minutes", 2, again.minutes)
        assertEquals("the converge's role and scenario", spec(MoleculeCommand.CONVERGE), again.spec)
        assertEquals("in the converge's tab again", "web \u203a default: the instances are destroyed in 2:00", banner.messageForTests())
        // Its links are on the "did not start" notification, not in a balloon of its own.
        val notification = notifications.runs.single()
        assertEquals("Molecule verify did not start: web \u203a nope", notification.title)
        assertEquals(listOf("Keep Instances", "Destroy Now", "Open Runner Settings"), RecordedNotifications.texts(notification))
        assertEquals("The role web has no Molecule scenario nope<br>The instances are destroyed in 2 min unless you keep them.", notification.content)
        assertNull(again.notificationForTests())
        assertSame(notification, again.runNotificationForTests())
        assertEquals("no second balloon", balloons, notifications.countdowns.size)

        // Kept instances stay kept.
        again.keep()
        val other = execute(failing)
        assertEquals(MoleculeCountdown.State.CANCELLED, again.state)
        other.processHandler.startNotify()
        PlatformTestUtil.waitWithEventsDispatching("the kept countdown did not come back", { cleanup.countdownOf(vf(role).path)?.state == MoleculeCountdown.State.KEPT }, 30)
        assertEquals("web \u203a default: the instances are kept", banner.messageForTests())
        assertTrue(runs.isEmpty())
    }

    fun testCopiesOfARoleInTwoRootsKeepTheirOwnNotifications() {
        val tern = projectRoot("tern")
        write("$tern/roles/web/tasks/main.yml", "- name: One\n  ansible.builtin.debug: {}\n")
        write("$tern/roles/web/molecule/default/molecule.yml", "---\ndriver:\n  name: default\n")
        root(falcon)
        root(tern)
        molecule(passingConverge)
        fun converge(copy: String) {
            val count = notifications.runs.size
            execute(MoleculeSpec(vf(copy).path, "default", MoleculeCommand.CONVERGE, PlaybookExecutor.NATIVE)).processHandler.startNotify()
            PlatformTestUtil.waitWithEventsDispatching("the converge of $copy did not notify", { notifications.runs.size == count + 1 }, 30)
        }
        converge(role)
        converge("$tern/roles/web")
        val (falconRun, ternRun) = notifications.runs
        assertEquals("Molecule converge passed: web (falcon) \u203a default", falconRun.title)
        assertEquals("Molecule converge passed: web (tern) \u203a default", ternRun.title)
        assertFalse("the other copy's run leaves it", falconRun.isExpired)
        assertNotNull("each copy counts down", cleanup.countdownOf(vf(role).path))
        assertNotNull(cleanup.countdownOf(vf("$tern/roles/web").path))

        // A rerun of the same copy replaces its own notification only.
        converge(role)
        assertTrue(falconRun.isExpired)
        assertFalse(ternRun.isExpired)
    }

    // ------------------------------------------------------------------ R19 final fixes

    /** A `molecule` whose destroy runs until [gate] exists and whose first other run goes on until it is stopped. */
    private fun moleculeWithHeldDestroy(gate: java.nio.file.Path) {
        val first = base.resolve("first-run")
        molecule(
            """
            case "${'$'}1" in
              destroy) echo 'destroying' >&2; while [ ! -f '$gate' ]; do sleep 0.1; done ;;
              *) echo 'INFO     [default > converge] Executing' >&2
                 if [ ! -f '$first' ]; then touch '$first'; exec sleep 30; fi ;;
            esac
            """.trimIndent(),
        )
    }

    /** Waits [millis] while the EDT goes on. */
    private fun pass(millis: Long) {
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
        PlatformTestUtil.waitWithEventsDispatching("the clock stopped", { System.nanoTime() > until }, 30)
    }

    fun testTheRerunOfAStoppedTestWaitsForItsDestroy() {
        root(falcon)
        val gate = base.resolve("destroy-gate")
        moleculeWithHeldDestroy(gate)
        val destroys = runLaunched()
        val spec = MoleculeSpec(vf(role).path, "default", MoleculeCommand.TEST, PlaybookExecutor.NATIVE)
        val first = execute(spec)
        val firstOutput = output(first)
        first.processHandler.startNotify()
        PlatformTestUtil.waitWithEventsDispatching("the test did not start", { "Executing" in firstOutput }, 30)

        // Rerun: the platform stops the test and starts the next run as soon as its handler ended, before the end
        // reaches the EDT (the EDT is busy here until then).
        first.processHandler.destroyProcess()
        assertTrue(first.processHandler.waitFor(TimeUnit.SECONDS.toMillis(30)))
        assertTrue("the stopped test's destroy counts before its end reaches the EDT", cleanup.isDestroying(spec.roleDir))
        val again = execute(spec)
        val output = output(again)
        again.processHandler.startNotify()

        PlatformTestUtil.waitWithEventsDispatching("the rerun does not wait for the destroy", { waitingForTheDestroy in output }, 30)
        PlatformTestUtil.waitWithEventsDispatching("the destroy did not start", { destroys.singleOrNull()?.second?.contains("destroying") == true }, 30)
        pass(1500)
        assertFalse("the rerun does not start while the destroy runs: $output", "Executing" in output)
        assertTrue((again.processHandler as PreparingProcessHandler).isPreparing)
        assertEquals("one destroy, the one the stop marked", listOf(spec.copy(command = MoleculeCommand.DESTROY)), runs)

        Files.writeString(gate, "")
        PlatformTestUtil.waitWithEventsDispatching("the rerun did not go on after the destroy", { "Executing" in output }, 30)
        assertTrue("the destroy ended first", destroys.single().first.processHandler.isProcessTerminated)
        assertFalse("nothing counts any more", cleanup.isDestroying(spec.roleDir))
        PlatformTestUtil.waitWithEventsDispatching("the rerun did not end", { again.processHandler.isProcessTerminated }, 30)
        assertEquals(0, again.processHandler.exitCode)
    }

    fun testADestroyThatThePlatformTookHoldsRunsBackHoweverLongItWaits() {
        root(falcon)
        molecule(
            """
            case "${'$'}1" in
              destroy) echo 'destroying' >&2 ;;
              *) echo 'INFO     [default > converge] Executing' >&2 ;;
            esac
            """.trimIndent(),
        )
        cleanup.launchTimeoutMillisForTests = 300
        // The platform takes each destroy (getState) and then waits before it runs it, as it does for indexing.
        val held = ArrayList<Pair<ExecutionEnvironment, com.intellij.execution.configurations.RunProfileState>>()
        MoleculeLauncher.executeForTests = { settings, executor ->
            runs += (settings.configuration as MoleculeConfiguration).spec
            val environment = ExecutionEnvironment(executor, TestRunner, settings, project)
            held += environment to settings.configuration.getState(executor, environment)!!
        }
        val spec = MoleculeSpec(vf(role).path, "default", MoleculeCommand.CONVERGE, PlaybookExecutor.NATIVE)
        cleanup.runEnded(spec.copy(command = MoleculeCommand.TEST), 0, null, stopped = true)
        val run = execute(spec)
        val output = output(run)
        run.processHandler.startNotify()
        PlatformTestUtil.waitWithEventsDispatching("the run does not wait for the destroy", { waitingForTheDestroy in output }, 30)
        pass(1200)
        assertFalse("the run waits past the launch timeout: $output", "Executing" in output)
        assertTrue(cleanup.isDestroying(spec.roleDir))

        // Indexing ended: the destroy runs, then the run goes on.
        val (environment, state) = held.single()
        val destroy = state.execute(environment.executor, TestRunner)!!
        Disposer.register(testRootDisposable, destroy.executionConsole)
        destroy.processHandler.startNotify()
        PlatformTestUtil.waitWithEventsDispatching("the run did not go on after the destroy", { "Executing" in output }, 30)
        assertTrue("the destroy ended first", destroy.processHandler.isProcessTerminated)
        assertFalse(cleanup.isDestroying(spec.roleDir))
        PlatformTestUtil.waitWithEventsDispatching("the run did not end", { run.processHandler.isProcessTerminated }, 30)

        // A taken destroy that the platform then reports as not started holds nothing back.
        held.clear()
        cleanup.runEnded(spec.copy(command = MoleculeCommand.TEST), 0, null, stopped = true)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        val (notStarted, _) = held.single()
        assertTrue(cleanup.isDestroying(spec.roleDir))
        project.messageBus.syncPublisher(com.intellij.execution.ExecutionManager.EXECUTION_TOPIC).processNotStarted(notStarted.executor.id, notStarted, null)
        assertFalse("the platform's word ends it", cleanup.isDestroying(spec.roleDir))
    }

    fun testADestroyWhoseLaunchIsHeldBackStillHoldsRunsBack() {
        root(falcon)
        molecule(passingConverge)
        cleanup.launchTimeoutMillisForTests = 300
        // A countdown ran out under a modal dialog: its destroy counts, its launch waits until the dialog closes.
        val pending = cleanup.destroyLaunching(spec(MoleculeCommand.DESTROY))
        val spec = MoleculeSpec(vf(role).path, "default", MoleculeCommand.CONVERGE, PlaybookExecutor.NATIVE)
        val run = execute(spec)
        val output = output(run)
        run.processHandler.startNotify()
        PlatformTestUtil.waitWithEventsDispatching("the run does not wait for the destroy", { waitingForTheDestroy in output }, 30)
        pass(1200)
        assertFalse("the run waits until the destroy is launched: $output", "Executing" in output)

        // The dialog closed and the destroy went to the platform, which never runs it: the timeout counts from now.
        cleanup.destroyHandedOver(pending)
        val handedOver = System.nanoTime()
        PlatformTestUtil.waitWithEventsDispatching("the run waits for good", { "Executing" in output }, 30)
        assertTrue("it waited for the launch timeout", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - handedOver) >= 250)
        assertFalse(cleanup.isDestroying(spec.roleDir))
        PlatformTestUtil.waitWithEventsDispatching("the run did not end", { run.processHandler.isProcessTerminated }, 30)
    }

    fun testACountdownThatComesBackNamesTheCopyOfItsRole() {
        val tern = projectRoot("tern")
        write("$tern/roles/web/tasks/main.yml", "- name: One\n  ansible.builtin.debug: {}\n")
        write("$tern/roles/web/molecule/default/molecule.yml", "---\ndriver:\n  name: default\n")
        root(falcon)
        root(tern)
        molecule(passingConverge)
        val web = vf("$tern/roles/web").path
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val settings = MoleculeLauncher.settingsFor(project, MoleculeSpec(web, "default", MoleculeCommand.CONVERGE, PlaybookExecutor.NATIVE))
        val converge = settings.configuration.getState(executor, ExecutionEnvironment(executor, TestRunner, settings, project)) as MoleculeRunState
        val result = converge.execute(executor, TestRunner)!!
        Disposer.register(testRootDisposable, result.executionConsole)
        result.processHandler.startNotify()
        PlatformTestUtil.waitWithEventsDispatching("the converge did not notify", { notifications.runs.isNotEmpty() }, 30)
        val banner = converge.bannerForTests()
        assertEquals("web (tern) \u203a default: the instances are destroyed in 2:00", banner.messageForTests())

        // A verify of tern's web that does not start: the countdown comes back, named like the run, on its notification.
        MoleculeRunContext.moleculeForTests = { null }
        execute(MoleculeSpec(web, "default", MoleculeCommand.VERIFY, PlaybookExecutor.NATIVE)).processHandler.startNotify()
        PlatformTestUtil.waitWithEventsDispatching("the verify did not notify", { notifications.runs.size == 2 }, 30)
        val notification = notifications.runs[1]
        assertEquals("Molecule verify did not start: web (tern) \u203a default", notification.title)
        assertEquals(listOf("Keep Instances", "Destroy Now", "Open Runner Settings"), RecordedNotifications.texts(notification))
        assertTrue(notifications.countdowns.isEmpty())
        assertEquals("web (tern) \u203a default: the instances are destroyed in 2:00", banner.messageForTests())
        notifications.click(notification, "Keep Instances")
        assertEquals("web (tern) \u203a default: the instances are kept", banner.messageForTests())

        // Stop while preparing: no run notification, so the countdown that comes back shows its own balloon, named too.
        cleanup.resetForTests()
        assertNotNull(cleanup.runEnded(MoleculeSpec(web, "default", MoleculeCommand.CONVERGE), 2, null, root = "tern"))
        val release = java.util.concurrent.CountDownLatch(1)
        MoleculeRunContext.moleculeForTests = {
            release.await(30, TimeUnit.SECONDS)
            null
        }
        try {
            val stopped = execute(MoleculeSpec(web, "default", MoleculeCommand.VERIFY, PlaybookExecutor.NATIVE))
            val output = output(stopped)
            stopped.processHandler.startNotify()
            PlatformTestUtil.waitWithEventsDispatching("the verify did not prepare", { "Preparing" in output }, 30)
            stopped.processHandler.destroyProcess()
            PlatformTestUtil.waitWithEventsDispatching("the countdown did not come back", { notifications.countdowns.isNotEmpty() }, 30)
        } finally {
            release.countDown()
        }
        assertEquals("web (tern) \u203a default: the instances are destroyed in 2 min unless you keep them.", notifications.countdowns.single().content)
    }
}
