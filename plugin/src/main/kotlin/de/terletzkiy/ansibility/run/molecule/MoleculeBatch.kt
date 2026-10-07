package de.terletzkiy.ansibility.run.molecule

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.KillableProcess
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.util.concurrency.AppExecutorUtil
import de.terletzkiy.ansibility.run.AnsibilityRunBundle.message
import de.terletzkiy.ansibility.run.AnsibleRunState
import de.terletzkiy.ansibility.run.PreparedRun
import de.terletzkiy.ansibility.run.events.RunEvent
import de.terletzkiy.ansibility.run.events.RunModel
import de.terletzkiy.ansibility.run.view.AnsibleRunConsole
import de.terletzkiy.ansibility.run.view.AnsibleRunView
import de.terletzkiy.ansibility.run.view.RunEventCollector
import de.terletzkiy.ansibility.run.view.RunEventsProcessHandler
import de.terletzkiy.ansibility.run.view.RunViewActions
import kotlinx.coroutines.runBlocking
import org.jetbrains.annotations.TestOnly
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Icon
import kotlin.coroutines.cancellation.CancellationException

/** One role of a bulk Molecule run: what runs ([spec]) and how the run shows it ([name], [label]: "web", "falcon"). */
data class MoleculeTarget(val spec: MoleculeSpec, val name: String, val label: String)

/** A [MoleculeTarget] ready to run: its prepared process, or why it could not be prepared. */
class MoleculeBatchPlan(val target: MoleculeTarget, val prepared: PreparedRun?, val error: String?) {
    val key: String get() = target.spec.roleDir
    val info: RunEvent.UnitInfo get() = RunEvent.UnitInfo(key, target.name, target.label)
}

/**
 * Runs the roles of a bulk Molecule run one after another as one process (plan amendment R16): each role's `molecule`
 * is a child process whose output goes on to this handler's console and whose events go to [collector] between a
 * [RunEvent.UnitStart] and a [RunEvent.UnitEnd]. A failure moves on to the next role; Stop ends the current one (a
 * second Stop kills it), skips the rest and destroys the instances the stopped test left (R17). The exit code is 0 when
 * every role passed. Without [collector] (the run view off) it reports the roles' results itself.
 */
class MoleculeBatchProcessHandler(
    private val project: Project,
    private val plans: List<MoleculeBatchPlan>,
    private val collector: RunEventCollector?,
) : ProcessHandler(), KillableProcess {
    private val lock = Any()
    private var next = 0
    private var failed = 0
    private val terminated = AtomicBoolean()

    @Volatile
    private var current: ProcessHandler? = null

    @Volatile
    private var stopped = false

    init {
        addProcessListener(object : ProcessListener {
            override fun startNotified(event: ProcessEvent) {
                collector?.accept(RunEvent.Units(now(), plans.map { it.info }))
                AppExecutorUtil.getAppExecutorService().execute(::startNext)
            }
        })
    }

    /** Starts the next role that can start; finishes after the last one or once stopped. */
    private fun startNext() {
        while (true) {
            val plan = synchronized(lock) { if (stopped) null else plans.getOrNull(next++) } ?: return finish()
            notifyTextAvailable(message("molecule.batch.header", plan.target.name, plan.target.label) + "\n", ProcessOutputTypes.SYSTEM)
            collector?.accept(RunEvent.UnitStart(now(), plan.key))
            MoleculeResults.getInstance(project).started(plan.target.spec)
            val prepared = plan.prepared
            val child = try {
                if (prepared == null) throw ExecutionException(plan.error ?: message("molecule.batch.not.prepared"))
                prepared.header.forEach { notifyTextAvailable("$it\n", ProcessOutputTypes.SYSTEM) }
                val commandLine = AnsibleRunState.commandLine(prepared.process)
                notifyTextAvailable("${commandLine.commandLineString}\n\n", ProcessOutputTypes.SYSTEM)
                val events = prepared.events
                if (events != null && collector != null) RunEventsProcessHandler(commandLine, events.token, collector, observeStages = true, endsRun = false)
                else KillableColoredProcessHandler(commandLine)
            } catch (e: ExecutionException) {
                notifyTextAvailable("${e.message}\n\n", ProcessOutputTypes.STDERR)
                prepared?.close()
                ended(plan, RunModel.NOT_STARTED, e.message)
                continue
            }
            child.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) = notifyTextAvailable(event.text, outputType)

                override fun processTerminated(event: ProcessEvent) {
                    prepared.close()
                    if (stopped) {
                        // Stopped mid-test, Molecule could not destroy its instances.
                        ended(plan, RunModel.STOPPED)
                        notifyTextAvailable("\n" + message("molecule.batch.stopped.destroy", plan.target.name, plan.target.label) + "\n", ProcessOutputTypes.SYSTEM)
                        MoleculeCleanup.destroy(project, plan.target.spec)
                    } else {
                        ended(plan, event.exitCode)
                        notifyTextAvailable("\n", ProcessOutputTypes.SYSTEM)
                    }
                    AppExecutorUtil.getAppExecutorService().execute(::startNext)
                }
            })
            current = child
            child.startNotify()
            if (stopped) child.destroyProcess()
            return
        }
    }

    private fun ended(plan: MoleculeBatchPlan, exitCode: Int, problem: String? = null) {
        val stopped = exitCode == RunModel.STOPPED
        synchronized(lock) { if (exitCode != 0 && !stopped) failed++ }
        collector?.accept(RunEvent.UnitEnd(now(), plan.key, exitCode, problem))
        if (collector == null) {
            ApplicationManager.getApplication().invokeLater(
                { if (!project.isDisposed) MoleculeResults.getInstance(project).finished(plan.target.spec, emptyList(), exitCode, stopped) },
                ModalityState.any(),
            )
        }
    }

    private fun finish() {
        if (!terminated.compareAndSet(false, true)) return
        current = null
        // The plans that never started give back their secrets.
        synchronized(lock) { plans.drop(next).forEach { it.prepared?.close() } }
        val (done, failures) = synchronized(lock) { next.coerceAtMost(plans.size) to failed }
        val summary = if (stopped) message("molecule.batch.stopped", done, plans.size, failures) else message("molecule.batch.summary", done, plans.size, failures)
        notifyTextAvailable(summary + "\n", ProcessOutputTypes.SYSTEM)
        val exitCode = if (failures == 0 && done == plans.size && !stopped) 0 else 1
        collector?.finish(exitCode)
        notifyProcessTerminated(exitCode)
    }

    override fun destroyProcessImpl() {
        stopped = true
        val child = current
        if (child == null || child.isProcessTerminated) AppExecutorUtil.getAppExecutorService().execute(::startNext) else child.destroyProcess()
    }

    override fun detachProcessImpl() {
        stopped = true
        current?.detachProcess()
        if (terminated.compareAndSet(false, true)) notifyProcessDetached()
    }

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream? = null

    override fun canKillProcess(): Boolean = (current as? KillableProcess)?.canKillProcess() == true

    override fun killProcess() {
        (current as? KillableProcess)?.killProcess()
    }

    private fun now(): Double = System.currentTimeMillis() / 1000.0

    @TestOnly
    fun modelForTests(): RunModel? = collector?.model

}

/**
 * A bulk Molecule run (plan amendment R16): one run tab whose Plays tab shows the roles, each with its scenarios'
 * stages; the console shows each role's output under a header. Not a saved run configuration: the tab's Rerun runs the
 * same roles again, "Rerun Failed Roles" the failed ones.
 */
class MoleculeBatchProfile(private val project: Project, val targets: List<MoleculeTarget>) : RunProfile {
    override fun getName(): String = message("molecule.batch.name", targets.size, MoleculeCommand.TEST.id)

    override fun getIcon(): Icon = MoleculeIcons.Molecule

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
        // Before preparing: no destroy countdown may hit instances of these roles.
        val cleanup = MoleculeCleanup.getInstance(project)
        targets.forEach { cleanup.runStarting(it.spec) }
        val plans = prepare() ?: return null
        return MoleculeBatchState(environment, plans)
    }

    /**
     * Prepares every role under one progress (the vault prompts come first, not an hour in); null when cancelled.
     * Blocks: on the EDT behind a modal progress.
     */
    private fun prepare(): List<MoleculeBatchPlan>? {
        val plans = ArrayList<MoleculeBatchPlan>()
        val prepareAll: suspend () -> Unit = {
            for (target in targets) {
                plans += try {
                    MoleculeBatchPlan(target, MoleculePreparation.prepareAsync(project, target.spec), null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Shown in the role's row and console; logged for the cases the message does not explain.
                    LOG.warn("The Molecule run of ${target.spec.roleDir} could not be prepared", e)
                    MoleculeBatchPlan(target, null, e.message ?: e.toString())
                }
            }
        }
        try {
            if (ApplicationManager.getApplication().isDispatchThread) {
                runWithModalProgressBlocking(project, message("molecule.batch.progress", targets.size)) { prepareAll() }
            } else {
                runBlocking { prepareAll() }
            }
        } catch (e: CancellationException) {
            plans.forEach { it.prepared?.close() }
            return null
        }
        return plans
    }
}

private val LOG = logger<MoleculeBatchProfile>()

/** The run of a [MoleculeBatchProfile]: the batch handler, its console and the Plays tab with the roles. */
internal class MoleculeBatchState(private val environment: ExecutionEnvironment, private val plans: List<MoleculeBatchPlan>) : RunProfileState {
    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val project = environment.project
        val disposable = Disposer.newDisposable("AnsibilityMoleculeBatch")
        val collector = if (plans.any { it.prepared?.events != null }) RunEventCollector(disposable) else null
        val handler = MoleculeBatchProcessHandler(project, plans, collector)
        ProcessTerminatedListener.attach(handler)
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        console.attachToProcess(handler)
        val shown: ExecutionConsole = if (collector != null) {
            // Results per role once it ended, from its stages.
            val reported = HashSet<String>()
            collector.addListener {
                for (unit in collector.model.units) {
                    if (unit.ended == null || !reported.add(unit.key)) continue
                    val plan = plans.first { it.key == unit.key }
                    MoleculeResults.getInstance(project).finished(plan.target.spec, unit.stages, unit.exitCode ?: -1, stopped = unit.exitCode == RunModel.STOPPED)
                }
            }
            // Jump to source: the container paths of each role's service map back to host files.
            val hostPath = { path: String -> plans.firstNotNullOfOrNull { plan -> plan.prepared?.events?.hostPath?.invoke(path)?.takeIf { Files.exists(it) } } }
            val view = AnsibleRunView(project, collector, MoleculeBatchActions(project, plans), hostPath)
            AnsibleRunConsole(console, view).also { Disposer.register(it, disposable) }
        } else {
            Disposer.register(console, disposable)
            console
        }
        return DefaultExecutionResult(shown, handler)
    }
}

/** What the Plays tab of a bulk run offers: a role or a stage of it again, the failed roles again. */
internal class MoleculeBatchActions(private val project: Project, private val plans: List<MoleculeBatchPlan>) : RunViewActions {
    override fun rerunHosts(hosts: List<String>) = Unit
    override fun startAt(task: String) = Unit
    override fun runPart(play: String, role: String?) = Unit
    override val playbookActions: Boolean get() = false
    override val stageActions: Boolean get() = true
    override val unitActions: Boolean get() = true

    override fun runStage(scenario: String, action: String, unit: String?) {
        val command = MoleculeCommand.forAction(action) ?: return
        val plan = plans.firstOrNull { it.key == unit } ?: return
        MoleculeLauncher.run(project, plan.target.spec.copy(scenario = scenario, command = command))
    }

    override fun runUnits(keys: List<String>) = MoleculeBatchLauncher.run(project, plans.filter { it.key in keys }.map { it.target })
}

/** Starts Molecule on several roles: one role is a run of its own, several are a bulk run in one tab. */
object MoleculeBatchLauncher {
    fun run(project: Project, targets: List<MoleculeTarget>) {
        if (targets.isEmpty()) return
        if (targets.size == 1) {
            MoleculeLauncher.run(project, targets.single().spec)
            return
        }
        executeForTests?.let { return it(targets) }
        val profile = MoleculeBatchProfile(project, targets)
        try {
            ExecutionEnvironmentBuilder.create(project, DefaultRunExecutor.getRunExecutorInstance(), profile).buildAndExecute()
        } catch (e: ExecutionException) {
            ExecutionUtil.handleExecutionError(project, ToolWindowId.RUN, profile.name, e)
        }
    }

    /** Replaces the start of a bulk run (tests: what a button would run). */
    @TestOnly
    @Volatile
    internal var executeForTests: ((List<MoleculeTarget>) -> Unit)? = null
}
