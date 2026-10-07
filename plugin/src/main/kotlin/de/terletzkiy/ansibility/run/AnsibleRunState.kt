package de.terletzkiy.ansibility.run

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.BaseProcessHandler
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import de.terletzkiy.ansibility.run.view.AnsibleRunConsole
import de.terletzkiy.ansibility.run.events.RunModel
import de.terletzkiy.ansibility.run.view.AnsibleRunView
import de.terletzkiy.ansibility.run.view.RunEventCollector
import de.terletzkiy.ansibility.run.view.RunBanner
import de.terletzkiy.ansibility.run.view.RunEventsProcessHandler
import de.terletzkiy.ansibility.run.view.RunViewActions

/**
 * Starts a prepared Ansible process (a playbook or a Molecule run) and deletes the run's secret scripts when it ends.
 * A run that reports its events ([PreparedRun.events]) gets the "Plays" tab ([AnsibleRunView]) in front of its
 * console, which shows the output unchanged either way; [observeStages] reads Molecule's stage lines too.
 */
open class AnsibleRunState(environment: ExecutionEnvironment, private val prepared: PreparedRun, private val observeStages: Boolean = false) :
    CommandLineState(environment) {
    private var collector: RunEventCollector? = null
    private var ended = false

    /** Whether the user stopped the process (Stop) rather than it ending by itself. */
    @Volatile
    protected var stopped: Boolean = false
        private set

    /** What the run view offers besides navigation: reruns and the like. */
    open fun actions(project: Project): RunViewActions = RunViewActions.NONE

    /** A message above the run's tabs once it has one (a Molecule run's destroy countdown), or null. */
    protected open val banner: RunBanner? get() = null

    /** The process was started. EDT (where runs execute). */
    protected open fun processStarting() = Unit

    /** EDT, once: the process ended with [exitCode]; [model] holds all its events (null without the run view). */
    protected open fun processEnded(exitCode: Int, model: RunModel?) = Unit

    private fun ended(exitCode: Int, model: RunModel?) {
        if (ended) return
        ended = true
        processEnded(exitCode, model)
    }

    override fun startProcess(): ProcessHandler {
        val commandLine = commandLine(prepared.process)
        val events = prepared.events
        val handler = try {
            val collector = this.collector
            if (events != null && collector != null) RunEventsProcessHandler(commandLine, events.token, collector, observeStages)
            else KillableColoredProcessHandler(commandLine)
        } catch (e: ExecutionException) {
            prepared.close()
            throw e
        }
        handler.addProcessListener(object : ProcessListener {
            override fun startNotified(event: ProcessEvent) {
                prepared.header.forEach { handler.notifyTextAvailable("$it\n", ProcessOutputTypes.SYSTEM) }
                handler.notifyTextAvailable("${commandLine.commandLineString}\n\n", ProcessOutputTypes.SYSTEM)
            }

            override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {
                // Fired before Stop destroys the process (still alive) and when it ended by itself (exited already).
                if (willBeDestroyed && (handler as? BaseProcessHandler<*>)?.process?.isAlive == true) stopped = true
            }

            override fun processTerminated(event: ProcessEvent) {
                prepared.close()
                // With the run view the model's last batch reports the end (see execute), so it holds every event.
                if (collector == null) ApplicationManager.getApplication().invokeLater({ ended(event.exitCode, null) }, ModalityState.any())
            }
        })
        ProcessTerminatedListener.attach(handler)
        processStarting()
        return handler
    }

    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val project = environment.project
        val events = prepared.events
        val disposable = Disposer.newDisposable("AnsibilityRun")
        val collector = events?.let { RunEventCollector(disposable) }
        this.collector = collector
        collector?.addListener { if (collector.model.finished) ended(collector.model.exitCode ?: -1, collector.model) }
        val handler = startProcess()
        val console = createConsole(executor) ?: throw ExecutionException(AnsibilityRunBundle.message("run.error.console"))
        console.attachToProcess(handler)
        val banner = banner
        val shown: ExecutionConsole = if (collector != null) {
            val view = AnsibleRunView(project, collector, actions(project), events.hostPath)
            AnsibleRunConsole(console, view, banner).also { Disposer.register(it, disposable) }
        } else if (banner != null) {
            AnsibleRunConsole(console, null, banner).also { Disposer.register(it, disposable) }
        } else {
            Disposer.register(console, disposable)
            console
        }
        return DefaultExecutionResult(shown, handler, *createActions(shown as? ConsoleView, handler, executor))
    }

    companion object {
        /** The command line of a prepared process: its working directory and environment on top of the IDE's console environment. */
        fun commandLine(process: PlaybookProcess): GeneralCommandLine = GeneralCommandLine(process.command)
            .withWorkingDirectory(process.workDir)
            .withEnvironment(process.environment)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withCharset(Charsets.UTF_8)
    }
}
