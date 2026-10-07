package de.terletzkiy.ansibility.run.molecule

import com.intellij.execution.RunManager
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings as PlatformRunnerSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.Disposer
import com.intellij.psi.SyntaxTraverser
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.ui.EditorNotificationPanel
import com.intellij.util.ui.UIUtil
import de.terletzkiy.ansibility.api.RoleTestState
import de.terletzkiy.ansibility.api.RoleTests
import de.terletzkiy.ansibility.run.PlaybookExecutor
import de.terletzkiy.ansibility.run.events.RunEvent
import de.terletzkiy.ansibility.run.events.RunModel
import de.terletzkiy.ansibility.run.settings.RunnerRootSettings
import de.terletzkiy.ansibility.run.settings.RunnerSettings
import de.terletzkiy.ansibility.run.view.RunBanner
import de.terletzkiy.ansibility.vault.VaultTestCase
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** The destroy countdown after a Molecule run (plan amendment R15) and the results of roles and scenarios (R16). */
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
        MoleculeLauncher.executeForTests = { settings, _ -> runs += (settings.configuration as MoleculeConfiguration).spec }
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
        val prepared = await { MoleculePreparation.prepare(project, spec) }!!
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val environment = ExecutionEnvironment(executor, TestRunner, MoleculeLauncher.settingsFor(project, spec), project)
        val result = MoleculeRunState(environment, prepared, spec).execute(executor, TestRunner)
        Disposer.register(testRootDisposable, result.executionConsole)
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
        assertNull("no result from a stopped run", results.scenarioState(vf(role).path, "default"))
        assertNull("no countdown: the instances go now", cleanup.countdownOf(vf(role).path))
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
        Files.writeString(molecule, "#!/bin/sh\necho 'INFO     [default > converge] Executing' >&2\necho 'INFO     [default > converge] Executed: Successful' >&2\n")
        molecule.toFile().setExecutable(true)
        MoleculeRunContext.moleculeForTests = { molecule }
        val spec = MoleculeSpec(vf(role).path, "default", MoleculeCommand.CONVERGE, PlaybookExecutor.NATIVE)
        val prepared = await { MoleculePreparation.prepare(project, spec) }!!
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val environment = ExecutionEnvironment(executor, TestRunner, MoleculeLauncher.settingsFor(project, spec), project)
        val result = MoleculeRunState(environment, prepared, spec).execute(executor, TestRunner)
        Disposer.register(testRootDisposable, result.executionConsole)
        assertEquals(RoleTestState.RUNNING, results.stateOf(vf(role)))
        result.processHandler.startNotify()
        assertTrue(result.processHandler.waitFor(TimeUnit.SECONDS.toMillis(30)))
        PlatformTestUtil.waitWithEventsDispatching("the run's end was not reported", { cleanup.countdownOf(vf(role).path) != null }, 30)
        assertEquals(RoleTestState.PASSED, results.scenarioState(vf(role).path, "default"))
        val panel = UIUtil.findComponentsOfType(result.executionConsole.component, EditorNotificationPanel::class.java).single()
        assertTrue(panel.text, "web \u203a default: the instances are destroyed in 2:00" in panel.text.orEmpty())
    }
}
