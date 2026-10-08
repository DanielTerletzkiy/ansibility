package de.terletzkiy.ansibility.run.view

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.util.Alarm
import com.intellij.util.concurrency.ThreadingAssertions
import de.terletzkiy.ansibility.run.events.RunEvent
import de.terletzkiy.ansibility.run.events.LineSplitter
import de.terletzkiy.ansibility.run.events.MoleculeLog
import de.terletzkiy.ansibility.run.events.RunEventFrames
import de.terletzkiy.ansibility.run.events.RunModel
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The [RunModel] of a running playbook: events arrive on the process reader thread and are applied on the EDT in
 * batches (at most every [BATCH_MILLIS]), after which the listeners refresh their views. Once [parent] is disposed
 * (the run's tab closed while the process still runs), the run's end is still applied, so the listeners that act on
 * it (results, the destroy countdown) hear of it.
 */
class RunEventCollector(parent: Disposable) {
    val model = RunModel()
    private val queue = ConcurrentLinkedQueue<RunEvent>()
    private val scheduled = AtomicBoolean()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parent)

    @Volatile
    private var exit: Int? = null

    @Volatile
    private var ended = false

    @Volatile
    private var disposed = false

    /** The end was applied to [model]. */
    @Volatile
    private var endApplied = false

    init {
        Disposer.register(parent) {
            disposed = true
            // A pending batch went with the alarm: the end must still be applied.
            if (ended && !endApplied) drainLater()
        }
    }

    /** Any thread. */
    fun accept(event: RunEvent) {
        queue += event
        schedule()
    }

    /** Any thread: the process ended with [code]. */
    fun finish(code: Int?) {
        exit = code
        ended = true
        schedule()
    }

    /** EDT: called after each batch. */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    private fun schedule() {
        if (disposed || alarm.isDisposed) {
            if (ended && !endApplied) drainLater()
            return
        }
        if (!scheduled.compareAndSet(false, true)) return
        alarm.addRequest({ drain() }, BATCH_MILLIS, ModalityState.any())
    }

    private fun drainLater() = ApplicationManager.getApplication().invokeLater({ drain() }, ModalityState.any())

    private fun drain() {
        ThreadingAssertions.assertEventDispatchThread()
        scheduled.set(false)
        while (true) model.apply(queue.poll() ?: break)
        if (ended && !model.finished) model.finish(exit)
        if (model.finished) endApplied = true
        listeners.forEach { it() }
    }

    /** Applies what arrived, now (tests). */
    @TestOnly
    fun drainForTests() {
        alarm.cancelAllRequests()
        drain()
    }

    companion object {
        const val BATCH_MILLIS = 100
    }
}

/**
 * Runs `ansible-playbook` (or Molecule) with its ANSI colours like a plain run, and takes the events of the Ansibility
 * callback out of stdout and stderr (see [RunEventFrames]; Molecule passes Ansible's stderr on through its stdout) into
 * [collector]; the console shows everything else unchanged. With [observeStages] it also reports Molecule's stage
 * lines ([MoleculeLog]): Molecule logs them on stderr, so they reach the collector in an order only roughly in step
 * with the events, which the model allows for (see [RunModel]).
 */
class RunEventsProcessHandler(
    commandLine: GeneralCommandLine,
    token: String,
    private val collector: RunEventCollector,
    private val observeStages: Boolean = false,
    /** False for one process of a batch: the batch ends the run when its last process ended. */
    private val endsRun: Boolean = true,
) : KillableColoredProcessHandler(commandLine) {
    private val lines = List(2) { LineSplitter { line -> MoleculeLog.stage(line)?.let(collector::accept) } }

    /** Per stream (stdout, stderr); a stream's lines are read in order with its events. Guarded by themselves. */
    private val frames = List(2) { stream ->
        RunEventFrames(token, onText = if (observeStages) lines[stream]::feed else null, onEvent = collector::accept)
    }

    init {
        addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                for (stream in 0..1) {
                    val rest = synchronized(frames[stream]) { frames[stream].flush().also { if (observeStages) lines[stream].flush() } }
                    if (rest.isNotEmpty()) super@RunEventsProcessHandler.coloredTextAvailable(rest, if (stream == 1) ProcessOutputTypes.STDERR else ProcessOutputTypes.STDOUT)
                }
                if (endsRun) collector.finish(event.exitCode)
            }
        })
    }

    override fun coloredTextAvailable(text: String, attributes: Key<*>) {
        val stream = when {
            ProcessOutputType.isStderr(attributes) -> 1
            ProcessOutputType.isStdout(attributes) -> 0
            else -> return super.coloredTextAvailable(text, attributes)
        }
        val rest = synchronized(frames[stream]) { frames[stream].filter(text) }
        if (rest.isNotEmpty()) super.coloredTextAvailable(rest, attributes)
    }
}
