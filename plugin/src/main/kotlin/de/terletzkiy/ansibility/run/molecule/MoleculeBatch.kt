package de.terletzkiy.ansibility.run.molecule

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowId
import de.terletzkiy.ansibility.run.AnsibilityRunBundle.message
import de.terletzkiy.ansibility.run.AnsibleRunState
import de.terletzkiy.ansibility.run.ChainedProcessHandler
import de.terletzkiy.ansibility.run.PreparationReporter
import de.terletzkiy.ansibility.run.PreparedRun
import de.terletzkiy.ansibility.run.RunEventsSetup
import de.terletzkiy.ansibility.run.RunPreparations
import de.terletzkiy.ansibility.run.events.RunEvent
import de.terletzkiy.ansibility.run.events.RunModel
import de.terletzkiy.ansibility.run.notify.RunAction
import de.terletzkiy.ansibility.run.notify.RunNotifier
import de.terletzkiy.ansibility.run.notify.RunOutcomes
import de.terletzkiy.ansibility.run.notify.RunTarget
import de.terletzkiy.ansibility.run.view.AnsibleRunConsole
import de.terletzkiy.ansibility.run.view.AnsibleRunView
import de.terletzkiy.ansibility.run.view.RunEventCollector
import de.terletzkiy.ansibility.run.view.RunEventsProcessHandler
import de.terletzkiy.ansibility.run.view.RunViewActions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Icon
import kotlin.coroutines.cancellation.CancellationException

/** One role of a bulk Molecule run: what runs ([spec]) and how the run shows it ([name], [label]: "web", "falcon"). */
data class MoleculeTarget(val spec: MoleculeSpec, val name: String, val label: String) {
    /**
     * The target in the run's events: the role's directory, with "#scenario" for one scenario of it, so that two
     * scenarios of one role in a bulk run are two units (their results still go to the role and scenario: [spec]).
     */
    val key: String get() = if (spec.scenario.isBlank()) spec.roleDir else spec.roleDir + "#" + spec.scenario

    /** What the run shows of it: "web", or "web › default" for one scenario. */
    val title: String get() = if (spec.scenario.isBlank()) name else message("molecule.cleanup.subject", name, spec.scenario)

    val info: RunEvent.UnitInfo get() = RunEvent.UnitInfo(key, title, label)
}

/**
 * Runs the roles of a bulk Molecule run one after another as one process (plan amendment R16): each role is prepared
 * right before it runs (R19, D146: the first starts at once, a role's scripts live while it runs) and its `molecule`
 * is a child process whose output goes on to this handler's console and whose events go to [collector] between a
 * [RunEvent.UnitStart] and a [RunEvent.UnitEnd]; while a role prepares, its row and the console say what it does. A
 * role that cannot be prepared ends with [RunModel.NOT_STARTED] and its problem; a failure moves on to the next role.
 * Stop ends the current role (a second Stop kills it; a role that still prepares ends at once), skips the rest and
 * destroys the instances a stopped test left (R17). The exit code is 0 when every role passed.
 *
 * A role's destroy countdown goes when the role begins (on the EDT, before it prepares), not when the batch starts: a
 * role that never begins keeps its countdown, and one that does not start its process gets it back
 * ([putCountdownBack]) unless a later unit of the role (another scenario) began meanwhile, which then holds it.
 */
class MoleculeBatchProcessHandler(
    private val project: Project,
    private val targets: List<MoleculeTarget>,
    private val collector: RunEventCollector,
    private val scope: CoroutineScope,
    private val prepare: suspend (MoleculeTarget, PreparationReporter) -> PreparedRun,
) : ChainedProcessHandler() {
    private enum class Phase { IDLE, PREPARING, STARTING, RUNNING, ENDED }

    private val lock = Any()
    private var phase = Phase.IDLE
    private var preparing: MoleculeTarget? = null
    private var begun = 0
    private var failed = 0
    private var job: Job? = null
    private val terminated = AtomicBoolean()

    /** How each role that started reports its events: Jump to Source maps its container paths back. */
    private val events = ConcurrentHashMap<String, RunEventsSetup>()

    /**
     * Role directory → the countdown the begin of a unit of the role cancelled, until a unit of the role starts its
     * process: units of one role (its scenarios) hand it on to each other.
     */
    private val rearms = ConcurrentHashMap<String, MoleculeCleanup.Rearm>()

    /** Role directory → the key of the unit of the role that began last. Under [lock]. */
    private val latest = HashMap<String, String>()

    /** The key of the role whose instances a Stop destroys in a run of their own (it was stopped mid-test), or null. */
    @Volatile
    var destroyedAfterStop: String? = null
        private set

    private val reporter = PreparationReporter { text ->
        // Under the lock: nothing of a phase shows after its role ended.
        synchronized(lock) {
            if (phase != Phase.PREPARING) return@PreparationReporter
            notifyTextAvailable(message("run.prepare.phase", text) + "\n", ProcessOutputTypes.SYSTEM)
            collector.accept(RunEvent.Preparing(now(), text))
        }
    }

    init {
        addProcessListener(object : ProcessListener {
            override fun startNotified(event: ProcessEvent) {
                collector.accept(RunEvent.Units(now(), targets.map { it.info }))
                // On IO: each role's preparation reads files, and its process is built (forked) here too.
                synchronized(lock) { job = scope.launch(Dispatchers.IO) { runAll() } }
            }
        })
    }

    /**
     * The unit [key] ended without starting its process (its preparation failed, Stop came while it prepared): puts
     * back the destroy countdown that its begin (or that of an earlier unit of the role) cancelled, unless a later unit
     * of the role began, which holds it now (and puts it back when it does not start either). Under the lock, so no
     * unit of the role begins meanwhile: the batch's own units never count as runs of the role that keep it away.
     * EDT, once the unit's results were recorded. Returns the countdown put back, or null.
     */
    fun putCountdownBack(key: String): MoleculeCountdown? {
        val roleDir = targets.firstOrNull { it.key == key }?.spec?.roleDir ?: return null
        return synchronized(lock) {
            if (latest[roleDir] != key) return null
            val rearm = rearms.remove(roleDir) ?: return null
            MoleculeCleanup.getInstance(project).runNotStarted(rearm)
        }
    }

    /** The host file of a path a role's run reported (the container paths of each role's service), or null. */
    fun hostPath(path: String): Path? = events.values.firstNotNullOfOrNull { setup -> setup.hostPath(path)?.takeIf { Files.exists(it) } }

    private suspend fun runAll() {
        try {
            for (target in targets) {
                if (!begin(target)) break
                runRole(target)
            }
        } finally {
            finish()
        }
    }

    /**
     * Whether [target] may begin (the run was not stopped): it starts (its header, its row, its results say it runs)
     * and prepares from now. Under the lock, so a Stop sees all of that or none.
     */
    private fun begin(target: MoleculeTarget): Boolean = synchronized(lock) {
        if (stopped || detached || phase == Phase.ENDED) return false
        begun++
        preparing = target
        latest[target.spec.roleDir] = target.key
        phase = Phase.PREPARING
        notifyTextAvailable(message("molecule.batch.header", target.title, target.label) + "\n", ProcessOutputTypes.SYSTEM)
        collector.accept(RunEvent.UnitStart(now(), target.key))
        MoleculeResults.getInstance(project).started(target.spec)
        true
    }

    /** Moves from [from] to [to]; false when Stop came in between (it ended the role already). */
    private fun claim(from: Phase, to: Phase): Boolean = synchronized(lock) {
        if (phase != from) return false
        phase = to
        if (to != Phase.PREPARING) preparing = null
        true
    }

    /** Prepares and runs [target]; returns once it ended (or did not start). */
    private suspend fun runRole(target: MoleculeTarget) {
        // The role begins: no destroy countdown of it may hit the instances it works on. On the EDT (the countdowns
        // live there) and before it prepares, so a countdown that runs out first is a destroy its preparation waits for.
        withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
            if (project.isDisposed || synchronized(lock) { phase != Phase.PREPARING || preparing !== target }) return@withContext
            // None: an earlier unit of the role may have cancelled it, and its rearm passes on to this one.
            MoleculeCleanup.getInstance(project).runStarting(target.spec)?.let { rearms[target.spec.roleDir] = it }
        }
        val prepared = try {
            prepare(target, reporter)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Shown in the role's row and console; logged for the cases the message does not explain.
            if (e !is ExecutionException) LOG.warn("The Molecule run of ${target.spec.roleDir} could not be prepared", e)
            notStarted(target, Phase.PREPARING, e.message ?: e.toString())
            return
        }
        if (!claim(Phase.PREPARING, Phase.STARTING)) {
            // Stopped while it prepared: the scripts it wrote go.
            prepared.close()
            return
        }
        prepared.events?.let { events[target.key] = it }
        collector.accept(RunEvent.Preparing(now(), null))
        prepared.header.forEach { notifyTextAvailable("$it\n", ProcessOutputTypes.SYSTEM) }
        val commandLine = AnsibleRunState.commandLine(prepared.process)
        notifyTextAvailable("${commandLine.commandLineString}\n\n", ProcessOutputTypes.SYSTEM)
        val child = try {
            val setup = prepared.events
            if (setup != null) RunEventsProcessHandler(commandLine, setup.token, collector, observeStages = true, endsRun = false)
            else KillableColoredProcessHandler(commandLine)
        } catch (e: Exception) {
            if (e !is ExecutionException) LOG.warn("The Molecule run of ${target.spec.roleDir} could not be started", e)
            prepared.close()
            notStarted(target, Phase.STARTING, e.message ?: e.toString())
            return
        }
        val exit = CompletableDeferred<Int>()
        synchronized(lock) { phase = Phase.RUNNING }
        // Its process works on the instances: the cancelled countdown stays gone.
        rearms.remove(target.spec.roleDir)
        val exitCode = try {
            startChild(child) { exit.complete(it) }
            exit.await()
        } finally {
            prepared.close()
        }
        claim(Phase.RUNNING, Phase.IDLE)
        when {
            // Detached, it goes on without the IDE: no result, and its instances are still in use.
            detached -> ended(target, RunModel.STOPPED)
            stopped -> {
                // Stopped mid-test, Molecule could not destroy its instances.
                destroyedAfterStop = target.key
                ended(target, RunModel.STOPPED)
                notifyTextAvailable("\n" + message("molecule.batch.stopped.destroy", target.title, target.label) + "\n", ProcessOutputTypes.SYSTEM)
                MoleculeCleanup.destroy(project, target.spec, automatic = true)
            }
            else -> {
                ended(target, exitCode)
                notifyTextAvailable("\n", ProcessOutputTypes.SYSTEM)
            }
        }
    }

    /** [target] could not be prepared or started: [problem] says why (nothing when Stop ended it first). */
    private fun notStarted(target: MoleculeTarget, from: Phase, problem: String) {
        if (!claim(from, Phase.IDLE)) return
        notifyTextAvailable(message("run.prepare.not.started", problem) + "\n\n", ProcessOutputTypes.STDERR)
        ended(target, RunModel.NOT_STARTED, problem)
    }

    private fun ended(target: MoleculeTarget, exitCode: Int, problem: String? = null) {
        synchronized(lock) { if (exitCode != 0 && exitCode != RunModel.STOPPED) failed++ }
        collector.accept(RunEvent.UnitEnd(now(), target.key, exitCode, problem))
    }

    private fun finish() {
        if (!terminated.compareAndSet(false, true)) return
        // The project closes while a role prepares: it ends like a stopped one.
        val pending = synchronized(lock) { preparing.also { preparing = null; phase = Phase.ENDED } }
        pending?.let { ended(it, RunModel.STOPPED) }
        // The project closes while a role runs: its process goes with the run.
        current?.takeIf { !it.isProcessTerminated }?.let { if (detached) it.detachProcess() else it.destroyProcess() }
        current = null
        val (done, failures) = synchronized(lock) { begun to failed }
        val ended = stopped || detached
        val summary = if (ended) message("molecule.batch.stopped", done, targets.size, failures) else message("molecule.batch.summary", done, targets.size, failures)
        notifyTextAvailable(summary + "\n", ProcessOutputTypes.SYSTEM)
        val exitCode = if (failures == 0 && done == targets.size && !ended) 0 else 1
        collector.finish(exitCode)
        if (detached) notifyProcessDetached() else notifyProcessTerminated(exitCode)
    }

    override fun destroyProcessImpl() {
        stopped = true
        stop()
    }

    override fun detachProcessImpl() {
        detached = true
        stop()
    }

    /** A role that prepares ends now (and the run with it); a running one gets the Stop; between roles the loop ends. */
    private fun stop() {
        var stoppedWhilePreparing: MoleculeTarget? = null
        val running = synchronized(lock) {
            when (phase) {
                Phase.PREPARING -> {
                    stoppedWhilePreparing = preparing
                    preparing = null
                    phase = Phase.ENDED
                    null
                }
                Phase.RUNNING -> current
                else -> null
            }
        }
        stoppedWhilePreparing?.let { target ->
            job?.cancel()
            ended(target, RunModel.STOPPED)
            finish()
        }
        running?.let { if (detached) it.detachProcess() else it.destroyProcess() }
    }

    /** The run's tab went while a role still prepares: the run stops, so no process starts behind a closed tab. */
    fun stopPreparing() {
        if (synchronized(lock) { phase == Phase.PREPARING }) destroyProcess()
    }

    @TestOnly
    fun modelForTests(): RunModel = collector.model
}

/**
 * A bulk Molecule run (plan amendment R16): one run tab whose Plays tab shows the roles, each with its scenarios'
 * stages; the console shows each role's output under a header. Not a saved run configuration: the tab's Rerun runs the
 * same roles again, "Rerun Failed Roles" the failed ones.
 */
class MoleculeBatchProfile(private val project: Project, val targets: List<MoleculeTarget>) : RunProfile {
    override fun getName(): String = message("molecule.batch.name", targets.size, MoleculeCommand.TEST.id)

    override fun getIcon(): Icon = MoleculeIcons.Molecule

    /**
     * Cheap, on the EDT (plan amendment R19): each role prepares in the run's tab right before it runs, and its destroy
     * countdown goes when it begins (a role that never begins keeps its own).
     */
    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        MoleculeBatchState(environment, targets) { target, reporter -> MoleculePreparation.prepareAsync(project, target.spec, reporter) }
}

private val LOG = logger<MoleculeBatchProfile>()

/**
 * The run of a [MoleculeBatchProfile]: the batch handler, its console and the Plays tab with the roles. Its end is told
 * in one notification (plan amendment R19, D147–D149; the roles' processes are no run states and never notify): which
 * roles failed or did not start, or where a Stop ended it, with "Rerun Failed Roles", "Run the N Remaining Roles" and
 * "Show Run".
 */
internal class MoleculeBatchState(
    private val environment: ExecutionEnvironment,
    private val targets: List<MoleculeTarget>,
    private val prepare: suspend (MoleculeTarget, PreparationReporter) -> PreparedRun,
) : RunProfileState {
    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val project = environment.project
        val disposable = Disposer.newDisposable("AnsibilityMoleculeBatch")
        // Always the Plays tab: it lists the roles from the start, whatever each role's run view setting.
        val collector = RunEventCollector(disposable)
        val handler = MoleculeBatchProcessHandler(project, targets, collector, RunPreparations.getInstance(project).scope, prepare)
        ProcessTerminatedListener.attach(handler)
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        console.attachToProcess(handler)
        // Results per role once it ended, from its stages (from its exit code when it reported none); a role that did
        // not start its process puts back the destroy countdown its begin cancelled (unless a later unit of it began).
        val reported = HashSet<String>()
        collector.addListener {
            if (project.isDisposed) return@addListener
            for (unit in collector.model.units) {
                if (unit.ended == null || !reported.add(unit.key)) continue
                val target = targets.first { it.key == unit.key }
                MoleculeResults.getInstance(project).finished(target.spec, unit.stages, unit.exitCode ?: -1, stopped = unit.exitCode == RunModel.STOPPED)
                handler.putCountdownBack(unit.key)
            }
        }
        val view = AnsibleRunView(project, collector, MoleculeBatchActions(project, targets), handler::hostPath)
        val shown = AnsibleRunConsole(console, view)
        Disposer.register(shown, disposable)
        Disposer.register(shown) { handler.stopPreparing() }
        // One notification once the model holds the batch's end (also after the tab was closed: the end still comes).
        val notified = AtomicBoolean()
        collector.addListener {
            if (collector.model.finished && notified.compareAndSet(false, true)) runEnded(RunTarget(key(), executor, handler, shown), handler, collector.model)
        }
        return DefaultExecutionResult(shown, handler)
    }

    /** The batch's key for its notification: the same roles run again replace it. */
    private fun key(): String = "molecule-batch:" + targets.joinToString("|") { it.key }

    /** EDT: notifies how the batch ended unless it was detached (its roles go on without the IDE). */
    private fun runEnded(target: RunTarget, handler: MoleculeBatchProcessHandler, model: RunModel) {
        val project = environment.project
        if (project.isDisposed || handler.detached) return
        val notifier = RunNotifier.getInstance(project)
        val failed = model.units.filter { it.status.isFailure && !it.stopped }.mapNotNull { unit -> targets.firstOrNull { it.key == unit.key } }
        val remaining = model.units.filter { it.started == null || it.stopped }.mapNotNull { unit -> targets.firstOrNull { it.key == unit.key } }
        val actions = listOfNotNull(
            remaining.takeIf { handler.stopped && it.isNotEmpty() }?.let { RunAction(message("run.notification.action.remaining", it.size)) { MoleculeBatchLauncher.run(project, it) } },
            failed.takeIf { it.isNotEmpty() }?.let { RunAction(message("run.view.action.rerun.failed.units")) { MoleculeBatchLauncher.run(project, it) } },
            notifier.showRun(target),
        )
        notifier.runEnded(RunOutcomes.batch(model, handler.stopped, handler.destroyedAfterStop), target, actions)
    }
}

/** What the Plays tab of a bulk run offers: a role or a stage of it again, the failed roles again. */
internal class MoleculeBatchActions(private val project: Project, private val targets: List<MoleculeTarget>) : RunViewActions {
    override fun rerunHosts(hosts: List<String>) = Unit
    override fun startAt(task: String) = Unit
    override fun runPart(play: String, role: String?) = Unit
    override val playbookActions: Boolean get() = false
    override val stageActions: Boolean get() = true
    override val unitActions: Boolean get() = true

    override fun runStage(scenario: String, action: String, unit: String?) {
        val command = MoleculeCommand.forAction(action) ?: return
        val target = targets.firstOrNull { it.key == unit } ?: return
        MoleculeLauncher.run(project, target.spec.copy(scenario = scenario, command = command))
    }

    override fun runUnits(keys: List<String>) = MoleculeBatchLauncher.run(project, targets.filter { it.key in keys })
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
