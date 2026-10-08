package de.terletzkiy.ansibility.run

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.notification.Notification
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.run.events.RunModel
import de.terletzkiy.ansibility.run.notify.RunAction
import de.terletzkiy.ansibility.run.notify.RunNotifier
import de.terletzkiy.ansibility.run.notify.RunOutcome
import de.terletzkiy.ansibility.run.notify.RunTarget
import de.terletzkiy.ansibility.run.view.AnsibleRunConsole
import de.terletzkiy.ansibility.run.view.AnsibleRunView
import de.terletzkiy.ansibility.run.view.RunBanner
import de.terletzkiy.ansibility.run.view.RunEventCollector
import de.terletzkiy.ansibility.run.view.RunEventsProcessHandler
import de.terletzkiy.ansibility.run.view.RunViewActions
import de.terletzkiy.ansibility.run.view.RunViewTexts
import org.jetbrains.annotations.TestOnly
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

/**
 * The run of a playbook or of Molecule. Its tab opens at once (plan amendment R19, D144): [execute] only builds the
 * console, the "Plays" tab ([AnsibleRunView]) and the [banner], and returns a [PreparingProcessHandler] that runs
 * [preparer] once the platform start-notifies it and then starts the run's process in the same tab. While the run
 * prepares, the console, the banner and the Plays tab say what it does; a run that does not start says why there
 * (D145). The Plays tab goes again when the prepared run reports no events (the run view is off for its root); the
 * console shows the output unchanged either way. The run's secret scripts are deleted when its process ends, and a
 * run that never started leaves none. [observeStages] reads Molecule's stage lines too.
 *
 * A run's end is told in a notification (R19, D147–D149): subclasses call [notifyEnd] from [processEnded]; a run whose
 * preparation failed notifies here (a cancelled question or a Stop while preparing does not: the user did that). A
 * run's notification replaces the previous one of the same [notificationKey] only (the same configuration of the same
 * playbook or role), and its title names the run's root when the workspace has several ([titled]).
 */
open class AnsibleRunState(
    environment: ExecutionEnvironment,
    private val preparer: RunPreparer,
    private val observeStages: Boolean = false,
) : CommandLineState(environment) {
    private var collector: RunEventCollector? = null
    private var handler: PreparingProcessHandler? = null
    private var console: AnsibleRunConsole? = null
    private var executor: Executor? = null
    private var ended = false

    /** When the run's process started (epoch milliseconds), 0 before. */
    @Volatile
    private var startedAt = 0L

    @Volatile
    private var notStarted: NotStarted? = null

    /**
     * The display name of the run's root when the workspace has more than one root, else null (a notification's title
     * names it, [titled]); known once the run prepares.
     */
    @Volatile
    protected var rootName: String? = null
        private set

    /** The prepared run once its process starts; null while it prepares and for a run that never started. */
    @Volatile
    protected var prepared: PreparedRun? = null
        private set

    /** Whether the user stopped the run (Stop) rather than it ending by itself. */
    protected val stopped: Boolean get() = handler?.stopped == true

    /** Whether the run was detached from its process, which may go on without the IDE. */
    protected val detached: Boolean get() = handler?.detached == true

    /** The message above the run's tabs: what it prepares, why it did not start, a Molecule run's destroy countdown. */
    protected val banner: RunBanner = RunBanner()

    /** What the run view offers besides navigation: reruns and the like. */
    open fun actions(project: Project): RunViewActions = RunViewActions.NONE

    /** The prepared process starts. EDT. */
    protected open fun processStarting() = Unit

    /**
     * Any thread (the process's), once, before [processEnded]: the process ended with [exitCode] and the run's handler
     * is about to end. The platform may start the next run as soon as it has (its Rerun waits for that only), before
     * [processEnded] runs on the EDT: what that run must see of this one's end happens here.
     */
    protected open fun processEnding(exitCode: Int) = Unit

    /** EDT, once: the process ended with [exitCode]; [model] holds all its events (null when the run reports none). */
    protected open fun processEnded(exitCode: Int, model: RunModel?) = Unit

    /**
     * EDT, once, instead of [processEnded]: the run never started its process ([why]: its preparation failed, the user
     * cancelled one of its questions or stopped it while it prepared). Its console, banner and Plays tab say so
     * already; this is the hook for whatever else should hear of it.
     */
    protected open fun processNotStarted(why: NotStarted) = Unit

    /**
     * What the notification of a run that did not start is about: the run's tab name by default (Molecule runs name
     * their command and role).
     */
    protected open fun notStartedOutcome(reason: String): RunOutcome = RunOutcome.NotStarted(titled(environment.runProfile.name), null, reason)

    /** What the notification of a run whose preparation failed offers first (a Molecule run's destroy countdown). EDT. */
    protected open fun notStartedActions(): List<RunAction> = emptyList()

    /** EDT, after [processNotStarted]: the notification of a run whose preparation failed, or null when none showed. */
    protected open fun notStartedNotified(notification: Notification?) = Unit

    /** A path in the run's root (the playbook, the role), whose Runner settings a run that did not start offers; null: none. */
    protected open fun settingsPath(): String? = null

    /**
     * What names this run for its notification: the next run of the same key replaces its notification. The run
     * configuration's id is its type and name only, which runs of same-named playbooks or role copies in different
     * roots share, so the key adds the run's [settingsPath] (the playbook, the role).
     */
    protected open fun notificationKey(): String = settingsPath()?.let { configurationId() + "|" + it } ?: configurationId()

    /** The run configuration's unique id (its type and name), or the run profile's name. */
    protected fun configurationId(): String = environment.runnerAndConfigurationSettings?.uniqueID ?: environment.runProfile.name

    /** [name] with the run's root when the workspace has several ("site.yml [staging] (falcon)"). */
    protected fun titled(name: String): String = rootName?.let { AnsibilityRunBundle.message("run.notification.name.root", name, it) } ?: name

    private fun ended(exitCode: Int, model: RunModel) {
        if (ended) return
        ended = true
        val why = notStarted
        if (why != null) {
            processNotStarted(why)
            if (why is NotStarted.Failed) notifyNotStarted(why)
        } else {
            processEnded(exitCode, model.takeIf { prepared?.events != null })
        }
    }

    private fun notifyNotStarted(why: NotStarted.Failed) {
        val project = environment.project
        val target = runTarget()
        val notification = if (target == null || project.isDisposed) {
            null
        } else {
            val notifier = RunNotifier.getInstance(project)
            val actions = notStartedActions() + listOfNotNull(notifier.openSettings(settingsPath()), notifier.runAgain(environment), notifier.showRun(target))
            notifyEnd(notStartedOutcome(why.reason), actions)
        }
        notStartedNotified(notification)
    }

    /** Where the run shows, for its notification; null before [execute]. */
    protected fun runTarget(): RunTarget? {
        val executor = executor ?: return null
        val handler = handler ?: return null
        return RunTarget(notificationKey(), executor, handler, console)
    }

    /** Seconds from the start of the run's process until now; null when it never started. */
    protected fun runSeconds(): Double? = startedAt.takeIf { it > 0 }?.let { (System.currentTimeMillis() - it) / 1000.0 }

    /**
     * EDT: tells the user how the run ended ([outcome], with [actions]) unless they watch its tab or their settings say
     * otherwise. Returns the notification shown, or null.
     */
    protected fun notifyEnd(outcome: RunOutcome, actions: List<RunAction>): Notification? {
        val project = environment.project
        if (project.isDisposed) return null
        val target = runTarget() ?: return null
        return RunNotifier.getInstance(project).runEnded(outcome, target, actions)
    }

    /** What the run's tab hears of the preparation; each call goes on to the EDT, in order. */
    private val listener = object : PreparingProcessHandler.Listener {
        override fun phase(text: String) = onEdt {
            if (!ended && prepared == null) banner.show(AnsibilityRunBundle.message("run.prepare.phase", text), emptyList(), RunBanner.Status.INFO)
        }

        override fun starting(prepared: PreparedRun) {
            this@AnsibleRunState.prepared = prepared
            startedAt = System.currentTimeMillis()
            onEdt {
                banner.hide()
                if (prepared.events == null) console?.dropView()
                processStarting()
            }
        }

        override fun ending(exitCode: Int) = processEnding(exitCode)

        override fun notStarted(why: NotStarted) {
            notStarted = why
            onEdt {
                val status = if (why is NotStarted.Failed) RunBanner.Status.ERROR else RunBanner.Status.WARNING
                banner.show(AnsibilityRunBundle.message("run.prepare.not.started", RunViewTexts.firstLine(why.text)), emptyList(), status)
            }
        }
    }

    private fun onEdt(block: () -> Unit) {
        val project = environment.project
        ApplicationManager.getApplication().invokeLater(block, ModalityState.any(), project.disposed)
    }

    /** The handler that prepares the run and then starts its process; not start-notified (the platform does that). */
    override fun startProcess(): ProcessHandler {
        val collector = collector ?: throw ExecutionException(AnsibilityRunBundle.message("run.error.console"))
        val scope = RunPreparations.getInstance(environment.project).scope
        val handler = PreparingProcessHandler(scope, collector, listener, { reporter ->
            rootName = rootNameOf(settingsPath())
            preparer.prepare(reporter)
        }) { prepared, commandLine ->
            val events = prepared.events
            if (events != null) RunEventsProcessHandler(commandLine, events.token, collector, observeStages, endsRun = false)
            else KillableColoredProcessHandler(commandLine)
        }
        ProcessTerminatedListener.attach(handler)
        this.handler = handler
        return handler
    }

    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val project = environment.project
        this.executor = executor
        val disposable = Disposer.newDisposable("AnsibilityRun")
        val collector = RunEventCollector(disposable)
        this.collector = collector
        // The end comes with the model's last batch, so it holds every event (and a run that did not start says why).
        collector.addListener { if (collector.model.finished) ended(collector.model.exitCode ?: -1, collector.model) }
        val handler = startProcess() as PreparingProcessHandler
        val console = createConsole(executor) ?: run {
            Disposer.dispose(disposable)
            throw ExecutionException(AnsibilityRunBundle.message("run.error.console"))
        }
        // Attached before the platform start-notifies the handler: the preparation's first line lands in it.
        console.attachToProcess(handler)
        val view = AnsibleRunView(project, collector, actions(project)) { path -> prepared?.events?.hostPath?.invoke(path) }
        val shown = AnsibleRunConsole(console, view, banner)
        this.console = shown
        Disposer.register(shown, disposable)
        Disposer.register(shown) { handler.stopPreparing() }
        return DefaultExecutionResult(shown, handler, *createActions(shown, handler, executor))
    }

    /**
     * The display name of the root [path] lies in (its nearest existing parent, for a path that is gone) when the
     * workspace has more than one root, else null. Off the EDT, in a read action.
     */
    private suspend fun rootNameOf(path: String?): String? {
        val start = path?.let { runCatching { Path.of(it) }.getOrNull() } ?: return null
        val project = environment.project
        return try {
            val file = generateSequence(start) { it.parent }.firstNotNullOfOrNull { LocalFileSystem.getInstance().findFileByNioFile(it) } ?: return null
            readAction {
                if (project.isDisposed) return@readAction null
                val workspace = AnsibleWorkspace.getInstance(project)
                if (workspace.roots().size < 2) null else workspace.rootFor(file)?.displayName
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Only a title is at stake: the run goes on.
            null
        }
    }

    /** The banner of the run's tabs (tests). */
    @TestOnly
    fun bannerForTests(): RunBanner = banner

    companion object {
        /** The command line of a prepared process: its working directory and environment on top of the IDE's console environment. */
        fun commandLine(process: PlaybookProcess): GeneralCommandLine = GeneralCommandLine(process.command)
            .withWorkingDirectory(process.workDir)
            .withEnvironment(process.environment)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withCharset(Charsets.UTF_8)
    }
}
