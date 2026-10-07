package de.terletzkiy.ansibility.run.molecule

import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings as PlatformRunnerSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.testFramework.PlatformTestUtil
import de.terletzkiy.ansibility.api.RoleTestState
import de.terletzkiy.ansibility.run.PlaybookExecutor
import de.terletzkiy.ansibility.run.events.HostStatus
import de.terletzkiy.ansibility.run.events.RunModel
import de.terletzkiy.ansibility.vault.VaultTestCase
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** A bulk Molecule run (plan amendment R16): roles one after another in one process, Stop, and its view actions. */
class MoleculeBatchTest : VaultTestCase() {
    private object TestRunner : ProgramRunner<PlatformRunnerSettings> {
        override fun getRunnerId(): String = "AnsibilityMoleculeBatchTest"
        override fun canRun(executorId: String, profile: RunProfile): Boolean = true
        override fun execute(environment: ExecutionEnvironment) = Unit
    }

    private lateinit var falcon: String

    override fun setUp() {
        super.setUp()
        falcon = projectRoot("falcon")
        for (role in listOf("web", "db")) {
            write("$falcon/roles/$role/tasks/main.yml", "- name: One\n  ansible.builtin.debug: {}\n")
            write("$falcon/roles/$role/molecule/default/molecule.yml", "---\ndriver:\n  name: default\n")
        }
        root(falcon)
    }

    override fun tearDown() {
        try {
            MoleculeRunContext.moleculeForTests = null
            MoleculeResults.getInstance(project).resetForTests()
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

    private fun plan(target: MoleculeTarget) = MoleculeBatchPlan(target, await { MoleculePreparation.prepareAsync(project, target.spec) }, null)

    private class Started(val handler: MoleculeBatchProcessHandler, val output: StringBuffer) {
        val model: RunModel get() = handler.modelForTests()!!
    }

    private fun start(plans: List<MoleculeBatchPlan>): Started {
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val profile = MoleculeBatchProfile(project, plans.map { it.target })
        val environment = ExecutionEnvironment(executor, TestRunner, MoleculeLauncher.settingsFor(project, plans.first().target.spec), project)
        val result = MoleculeBatchState(environment, plans).execute(executor, TestRunner)
        Disposer.register(testRootDisposable, result.executionConsole)
        assertEquals("Molecule: ${plans.size} roles (test)", profile.name)
        val handler = result.processHandler as MoleculeBatchProcessHandler
        val output = StringBuffer()
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
        val unprepared = MoleculeBatchPlan(MoleculeTarget(MoleculeSpec(base.resolve("elsewhere/lb").toString()), "lb", "tern"), null, "No Docker Compose service with Molecule mounts the role lb")
        val run = start(listOf(plan(target("web")), plan(target("db")), unprepared))
        assertTrue(run.handler.waitFor(TimeUnit.SECONDS.toMillis(60)))
        PlatformTestUtil.waitWithEventsDispatching("the batch did not end", { run.model.finished }, 30)
        assertEquals("a role failed", 1, run.handler.exitCode)
        assertEquals(listOf("web", "db", "lb"), run.model.units.map { it.name })
        assertEquals(listOf(HostStatus.OK, HostStatus.FAILED, HostStatus.FAILED), run.model.units.map { it.status })
        assertEquals(listOf(0, 2, RunModel.NOT_STARTED), run.model.units.map { it.exitCode })
        val lb = run.model.units[2]
        assertEquals("the role says why it did not start", "No Docker Compose service with Molecule mounts the role lb", lb.problem)
        assertEquals("Not started: No Docker Compose service with Molecule mounts the role lb", de.terletzkiy.ansibility.run.view.RunViewTexts.unitStatus(lb, true))
        assertEquals("each role keeps its own stage", listOf(1, 1, 0), run.model.units.map { it.stages.size })
        val output = run.output.toString()
        assertTrue(output, "── web (falcon) ──" in output && "── db (falcon) ──" in output)
        assertTrue(output, "No Docker Compose service with Molecule mounts the role lb" in output)
        assertTrue(output, "Ran 3 of 3 roles: 2 failed." in output)
        val results = MoleculeResults.getInstance(project)
        assertEquals(RoleTestState.PASSED, results.scenarioState(vf("$falcon/roles/web").path, "default"))
        assertEquals(RoleTestState.FAILED, results.scenarioState(vf("$falcon/roles/db").path, "default"))
        assertEquals("nothing runs any more", RoleTestState.FAILED, results.stateOf(vf("$falcon/roles/db")))
    }

    fun testStoppingABatchEndsTheCurrentRoleSkipsTheRestAndDestroysItsInstances() {
        val runs = ArrayList<MoleculeSpec>()
        MoleculeLauncher.executeForTests = { settings, _ -> runs += (settings.configuration as MoleculeConfiguration).spec }
        molecule("echo 'INFO     [default > converge] Executing' >&2\nexec sleep 30")
        val plans = listOf(plan(target("web")), plan(target("db")))
        val run = start(plans)
        PlatformTestUtil.waitWithEventsDispatching("the first role did not start", { run.model.units.firstOrNull()?.stages?.isNotEmpty() == true }, 30)
        run.handler.destroyProcess()
        assertTrue(run.handler.waitFor(TimeUnit.SECONDS.toMillis(30)))
        PlatformTestUtil.waitWithEventsDispatching("the batch did not end", { run.model.finished }, 30)
        assertEquals(1, run.handler.exitCode)
        val (web, db) = run.model.units
        assertTrue("stopped, not failed", web.stopped)
        assertNull("db never ran", db.started)
        assertFalse("the skipped role's secrets are gone", Files.exists(plans[1].prepared!!.secrets.callbackDir!!))
        val output = run.output.toString()
        assertTrue(output, "Stopped: 1 of 2 roles started, 0 failed." in output)
        assertTrue(output, "the instances of web (falcon) are destroyed in a run of their own" in output)
        PlatformTestUtil.waitWithEventsDispatching("the stopped role was not cleaned up", { runs.isNotEmpty() }, 30)
        assertEquals(listOf(target("web").spec.copy(command = MoleculeCommand.DESTROY)), runs)
        assertNull("no result from the stopped role", MoleculeResults.getInstance(project).scenarioState(vf("$falcon/roles/web").path, "default"))
    }

    fun testThePlaysTabOfABatchRunsRolesAndStagesAgain() {
        val runs = ArrayList<MoleculeSpec>()
        MoleculeLauncher.executeForTests = { settings, _ -> runs += (settings.configuration as MoleculeConfiguration).spec }
        val batches = ArrayList<List<MoleculeTarget>>()
        MoleculeBatchLauncher.executeForTests = { batches += it }
        val plans = listOf(target("web"), target("db")).map { MoleculeBatchPlan(it, null, "not prepared in this test") }
        val actions = MoleculeBatchActions(project, plans)
        assertTrue(actions.unitActions && actions.stageActions && !actions.playbookActions)
        actions.runStage("default", "verify", plans[1].key)
        actions.runStage("default", "prepare", plans[1].key)
        assertEquals("that role's stage, alone", listOf(target("db").spec.copy(scenario = "default", command = MoleculeCommand.VERIFY)), runs)
        actions.runUnits(plans.map { it.key })
        assertEquals(listOf(listOf(target("web"), target("db"))), batches)
        actions.runUnits(listOf(plans[0].key))
        assertEquals("one role is a run of its own", target("web").spec, runs.last())
    }
}
