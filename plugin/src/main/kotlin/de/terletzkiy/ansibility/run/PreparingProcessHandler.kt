package de.terletzkiy.ansibility.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.KillableProcess
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import de.terletzkiy.ansibility.run.events.RunEvent
import de.terletzkiy.ansibility.run.events.RunModel
import de.terletzkiy.ansibility.run.view.RunEventCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.annotations.Nls
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException

/** What a run's preparation does now, for its tab ("unlocking the vault ids of falcon"). Any thread. */
fun interface PreparationReporter {
    fun phase(@Nls text: String)

    companion object {
        /** Tells nobody (a preparation outside a run tab, tests). */
        val NONE = PreparationReporter { }
    }
}

/** Prepares a run in its tab (plan amendment R19): the [PreparedRun], or null when the user cancelled one of its questions. */
fun interface RunPreparer {
    suspend fun prepare(reporter: PreparationReporter): PreparedRun?

    companion object {
        /** A run prepared already (tests). */
        fun of(prepared: PreparedRun): RunPreparer = RunPreparer { prepared }
    }
}

/** Why a run never started its process (plan amendment R19, D145). */
sealed interface NotStarted {
    /** What the run's tab says after "Not started: ". */
    @get:Nls
    val text: String

    /** The preparation failed: [reason] says why (the message of what failed; the first line is the summary). */
    class Failed(@Nls val reason: String) : NotStarted {
        override val text: String get() = reason

        override fun toString(): String = "Failed($reason)"
    }

    /** The user cancelled one of the preparation's questions (environment, confirmation, password). */
    data object Cancelled : NotStarted {
        override val text: String get() = AnsibilityRunBundle.message("run.prepare.cancelled")
    }

    /** The user stopped the run (or closed its tab, or the project) while it prepared. */
    data object Stopped : NotStarted {
        override val text: String get() = AnsibilityRunBundle.message("run.prepare.stopped")
    }
}

/** The coroutine scope run preparations run in (plan amendment R19): closing the project cancels them. */
@Service(Service.Level.PROJECT)
class RunPreparations(val scope: CoroutineScope) {
    companion object {
        fun getInstance(project: Project): RunPreparations = project.service()
    }
}

/**
 * A run whose processes it starts itself, one at a time (plan amendment R19): Stop, kill and the input go to the
 * current child, whose output goes on to this handler and so to the run's console. [PreparingProcessHandler] has one
 * child, a bulk Molecule run one per role. Subclasses end themselves (`notifyProcessTerminated`) when their run ended.
 */
abstract class ChainedProcessHandler : ProcessHandler(), KillableProcess {
    /** The child process of now, or null (before the first, between two). */
    @Volatile
    protected var current: ProcessHandler? = null

    /** Whether the user stopped the run (Stop, closing its tab or the project) rather than it ending by itself. */
    @Volatile
    var stopped: Boolean = false
        protected set

    /** Whether the run was detached: its process may go on without the IDE. */
    @Volatile
    var detached: Boolean = false
        protected set

    /**
     * Starts [child] as the current process: its output goes on to this handler, and [ended] gets its exit code once
     * it ended (on its thread, after the child's own listeners). A Stop that came meanwhile stops it at once.
     */
    protected fun startChild(child: ProcessHandler, ended: (Int) -> Unit) {
        child.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) = notifyTextAvailable(event.text, outputType)

            override fun processTerminated(event: ProcessEvent) = ended(event.exitCode)
        })
        current = child
        child.startNotify()
        when {
            detached -> child.detachProcess()
            stopped -> child.destroyProcess()
        }
    }

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream? = current?.processInput

    override fun canKillProcess(): Boolean = (current as? KillableProcess)?.canKillProcess() == true

    override fun killProcess() {
        (current as? KillableProcess)?.killProcess()
    }

    protected fun now(): Double = System.currentTimeMillis() / 1000.0
}

/**
 * The process of one run, prepared in the run's tab (plan amendment R19, D144–D145). The platform shows the tab with
 * this handler and then start-notifies it; [prepare] then runs in a coroutine of [scope] on `Dispatchers.IO` (it reads
 * files and runs git, and the run's process is built there too) and says what it does through its
 * [PreparationReporter] ("Preparing: …" lines in the console, [RunEvent.Preparing] for the Plays tab,
 * [Listener.phase] for the banner); the questions it asks stay dialogs. Once prepared, the run's header and command
 * line are printed and [child] builds the real process, which starts in the same tab: this handler passes on its
 * output, Stop, kill and exit code, and closes the [PreparedRun] (the run's secret scripts) once it ended. [collector]
 * hears of the end ([RunEventCollector.finish]) after the child's last event.
 *
 * A run that does not start ends this handler with [RunModel.NOT_STARTED] after [Listener.notStarted]: its
 * preparation failed (the reason on stderr; an unexpected error too), the user cancelled a question ([prepare]
 * returned null), or Stop came while it prepared (the job is cancelled, this handler ends at once without waiting for
 * a blocking step, and a [PreparedRun] produced afterwards is closed).
 */
class PreparingProcessHandler(
    private val scope: CoroutineScope,
    private val collector: RunEventCollector?,
    private val listener: Listener,
    private val prepare: suspend (PreparationReporter) -> PreparedRun?,
    private val child: (PreparedRun, GeneralCommandLine) -> ProcessHandler,
) : ChainedProcessHandler() {
    /** What the run's tab hears of the preparation. Any thread; each call is quick (the tab posts to the EDT). */
    interface Listener {
        /** The preparation does [text] now. */
        fun phase(@Nls text: String) = Unit

        /** The run is prepared and its process built: it starts now. */
        fun starting(prepared: PreparedRun) = Unit

        /** The run does not start, [why]; once, before this handler ends. */
        fun notStarted(why: NotStarted) = Unit

        /**
         * The run's process ended with [exitCode]; once, on its thread, before this handler ends: the platform may
         * start the next run (Rerun) as soon as this handler ended, before the run's end reaches the EDT.
         */
        fun ending(exitCode: Int) = Unit
    }

    private enum class State { NEW, PREPARING, STARTING, RUNNING, ENDED }

    private val lock = Any()
    private var state = State.NEW
    private var job: Job? = null

    private val reporter = PreparationReporter { text ->
        // Under the lock: nothing of a phase shows after the run ended.
        synchronized(lock) {
            if (state != State.PREPARING) return@PreparationReporter
            notifyTextAvailable(AnsibilityRunBundle.message("run.prepare.phase", text) + "\n", ProcessOutputTypes.SYSTEM)
            collector?.accept(RunEvent.Preparing(now(), text))
            listener.phase(text)
        }
    }

    init {
        addProcessListener(object : ProcessListener {
            override fun startNotified(event: ProcessEvent) = startPreparing()
        })
    }

    private fun startPreparing() {
        synchronized(lock) {
            if (state != State.NEW) return
            state = State.PREPARING
            // On IO: the preparation reads files and runs git, and the run's process is built (forked) here too.
            job = scope.launch(Dispatchers.IO) { prepareAndStart() }
        }
    }

    private suspend fun prepareAndStart() {
        val prepared = try {
            prepare(reporter)
        } catch (e: Throwable) {
            when (e) {
                // Stop ended the run already (a blocking step may say so with a ProcessCanceledException, which is
                // one too); else the project closes.
                is CancellationException -> {
                    end(NotStarted.Stopped)
                    throw e
                }
                is ExecutionException -> end(NotStarted.Failed(e.message ?: e.toString()))
                else -> failed("A run could not be prepared", e)
            }
            return
        }
        if (prepared == null) return end(NotStarted.Cancelled)
        start(prepared)
    }

    /** Something unexpected ([e], an error too) ended the run before it started; nothing stays "preparing" for good. */
    private fun failed(what: String, e: Throwable) {
        LOG.warn(what, e)
        end(NotStarted.Failed(e.message ?: e.toString()))
        if (e is VirtualMachineError) throw e
    }

    private fun start(prepared: PreparedRun) {
        synchronized(lock) {
            if (state != State.PREPARING) {
                // Stopped while it prepared: the scripts it wrote go.
                prepared.close()
                return
            }
            state = State.STARTING
        }
        // Whatever fails until the process runs ends the run (a Stop now waits for that) and deletes its scripts.
        val process = try {
            collector?.accept(RunEvent.Preparing(now(), null))
            val commandLine = AnsibleRunState.commandLine(prepared.process)
            prepared.header.forEach { notifyTextAvailable("$it\n", ProcessOutputTypes.SYSTEM) }
            notifyTextAvailable("${commandLine.commandLineString}\n\n", ProcessOutputTypes.SYSTEM)
            child(prepared, commandLine)
        } catch (e: Throwable) {
            prepared.close()
            if (e is ExecutionException) end(NotStarted.Failed(e.message ?: e.toString())) else failed("A run's process could not be started", e)
            return
        }
        synchronized(lock) { state = State.RUNNING }
        listener.starting(prepared)
        startChild(process) { exitCode ->
            try {
                listener.ending(exitCode)
            } catch (e: Exception) {
                // The run still ends.
                LOG.warn("A run's end could not be prepared", e)
            }
            prepared.close()
            collector?.finish(exitCode)
            synchronized(lock) { state = State.ENDED }
            if (detached) notifyProcessDetached() else notifyProcessTerminated(exitCode)
        }
    }

    /** Ends the run that did not start ([why]); nothing when it started or ended already. */
    private fun end(why: NotStarted) {
        synchronized(lock) {
            if (state == State.RUNNING || state == State.ENDED) return
            state = State.ENDED
            listener.notStarted(why)
            val failed = why is NotStarted.Failed
            notifyTextAvailable(
                AnsibilityRunBundle.message("run.prepare.not.started", why.text) + "\n",
                if (failed) ProcessOutputTypes.STDERR else ProcessOutputTypes.SYSTEM,
            )
            collector?.accept(RunEvent.NotStarted(now(), why.text))
            collector?.finish(RunModel.NOT_STARTED)
        }
        if (detached) notifyProcessDetached() else notifyProcessTerminated(RunModel.NOT_STARTED)
    }

    override fun destroyProcessImpl() {
        stopped = true
        stop()
    }

    override fun detachProcessImpl() {
        detached = true
        stop()
    }

    /** Stop or detach: while preparing the run ends now; a process gets the Stop; while it starts, the start sees it. */
    private fun stop() {
        val (preparing, running) = synchronized(lock) {
            when (state) {
                State.NEW, State.PREPARING -> true to null
                State.RUNNING -> false to current
                State.STARTING, State.ENDED -> false to null
            }
        }
        if (preparing) {
            job?.cancel()
            end(NotStarted.Stopped)
        }
        running?.let { if (detached) it.detachProcess() else it.destroyProcess() }
    }

    /** The run's tab went while the run still prepares: it stops, so no process starts behind a closed tab. */
    fun stopPreparing() {
        val preparing = synchronized(lock) { state == State.NEW || state == State.PREPARING }
        if (preparing) destroyProcess()
    }

    /** Whether the run still prepares (tests). */
    val isPreparing: Boolean get() = synchronized(lock) { state == State.PREPARING }

    private companion object {
        val LOG = logger<PreparingProcessHandler>()
    }
}
