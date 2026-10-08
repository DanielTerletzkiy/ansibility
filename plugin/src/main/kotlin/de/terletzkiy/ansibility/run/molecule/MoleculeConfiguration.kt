package de.terletzkiy.ansibility.run.molecule

import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.LocatableRunConfigurationOptions
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.notification.Notification
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import de.terletzkiy.ansibility.run.AnsibilityRunBundle.message
import de.terletzkiy.ansibility.run.AnsibleRunState
import de.terletzkiy.ansibility.run.NotStarted
import de.terletzkiy.ansibility.run.PlaybookExecutor
import de.terletzkiy.ansibility.run.PreparedRun
import de.terletzkiy.ansibility.run.RunPreparer
import de.terletzkiy.ansibility.run.events.RunModel
import de.terletzkiy.ansibility.run.notify.RunAction
import de.terletzkiy.ansibility.run.notify.RunNotifier
import de.terletzkiy.ansibility.run.notify.RunOutcome
import de.terletzkiy.ansibility.run.notify.RunOutcomes
import de.terletzkiy.ansibility.run.view.RunViewActions
import org.jetbrains.annotations.TestOnly
import javax.swing.JComponent

/** The "Molecule" run configuration type. */
class MoleculeConfigurationType : ConfigurationTypeBase(
    ID,
    message("molecule.type.name"),
    message("molecule.type.description"),
    NotNullLazyValue.createValue { MoleculeIcons.Molecule },
) {
    init {
        addFactory(MoleculeConfigurationFactory(this))
    }

    val factory: ConfigurationFactory get() = configurationFactories.single()

    companion object {
        const val ID = "AnsibilityMolecule"

        fun getInstance(): MoleculeConfigurationType = ConfigurationTypeUtil.findConfigurationType(MoleculeConfigurationType::class.java)
    }
}

class MoleculeConfigurationFactory(type: MoleculeConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = MoleculeConfigurationType.ID

    override fun createTemplateConfiguration(project: Project): RunConfiguration = MoleculeConfiguration(project, this, "")

    override fun getOptionsClass(): Class<out BaseState> = MoleculeRunOptions::class.java
}

/** The stored form of a [MoleculeSpec]. */
class MoleculeRunOptions : LocatableRunConfigurationOptions() {
    var roleDir by string("")
    var scenario by string("")
    var command by string(MoleculeCommand.TEST.id)
    var executor by enum(PlaybookExecutor.AUTO)
    var composeFile by string("")
    var composeService by string("")
    var additionalArgs by string("")
}

/** Runs Molecule on a role: its scenarios' runs show in the "Plays" tab, grouped by stage. */
class MoleculeConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<MoleculeRunOptions>(project, factory, name) {

    override fun getOptions(): MoleculeRunOptions = super.getOptions() as MoleculeRunOptions

    var spec: MoleculeSpec
        get() = with(options) {
            MoleculeSpec(
                roleDir = roleDir.orEmpty(),
                scenario = scenario.orEmpty(),
                command = MoleculeCommand.of(command),
                executor = executor,
                composeFile = composeFile.orEmpty(),
                composeService = composeService.orEmpty(),
                additionalArgs = additionalArgs.orEmpty(),
            )
        }
        set(value) = with(options) {
            roleDir = value.roleDir
            scenario = value.scenario
            command = value.command.id
            executor = value.executor
            composeFile = value.composeFile
            composeService = value.composeService
            additionalArgs = value.additionalArgs
        }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = MoleculeSettingsEditor(project)

    override fun checkConfiguration() {
        if (options.roleDir.isNullOrBlank()) throw RuntimeConfigurationError(message("molecule.error.no.role"))
    }

    override fun suggestedName(): String? = options.roleDir?.takeIf { it.isNotBlank() }?.let { spec.name() }

    /** Cheap, on the EDT (plan amendment R19): the run prepares in its tab. */
    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        val spec = spec
        // A destroy the IDE started by itself (MoleculeLauncher.run marks it): it notifies only when it fails.
        val automatic = getUserData(AUTOMATIC) == true
        putUserData(AUTOMATIC, null)
        // A destroy MoleculeCleanup launched: under way from now (whatever the platform still waits for), runs of the
        // role wait until this run ended. The environment carries it for the platform's word that the run did not start.
        val cleanup = MoleculeCleanup.getInstance(project)
        val pending = getUserData(PENDING_DESTROY)
        putUserData(PENDING_DESTROY, null)
        val destroy = pending?.takeIf { spec.command == MoleculeCommand.DESTROY }
        if (pending != null && destroy == null) cleanup.destroyEnded(pending)
        destroy?.let {
            cleanup.destroyLaunched(it)
            environment.putUserData(PENDING_DESTROY, it)
        }
        // Before preparing: a destroy countdown of the role must not hit the instances this run works on. A run that
        // then does not start puts it back.
        val rearm = cleanup.runStarting(spec)
        return MoleculeRunState(environment, RunPreparer { reporter -> MoleculePreparation.prepareAsync(project, spec, reporter) }, spec, automatic, rearm, destroy)
    }

    companion object {
        /** Marks the next run of a configuration as started by the IDE itself (an automatic destroy); [getState] clears it. */
        internal val AUTOMATIC: Key<Boolean> = Key.create("ansibility.molecule.automatic")

        /**
         * The destroy [MoleculeCleanup.destroy] launches with the next run of a configuration; [getState] takes it and
         * puts it into the run's environment.
         */
        internal val PENDING_DESTROY: Key<MoleculeCleanup.PendingDestroy> = Key.create("ansibility.molecule.pendingDestroy")
    }
}

/**
 * A Molecule run: the Plays tab with its stages, the results of its scenarios (gutter, Roles tab) and, for a command
 * that leaves instances, the destroy countdown in a banner above its tabs once it ended (plan amendment R15). The role
 * runs (its results say so) from when its process starts; a run that did not start leaves its results alone and
 * starts no countdown. Its end is told in a notification (R19, D147–D149) that carries the countdown's "Keep
 * Instances" and "Destroy Now"; without one (the user watches the tab, or turned notifications off) the countdown
 * shows its own balloon as in R15. An [automatic] destroy notifies only when it fails.
 *
 * The countdown of the role that this run's start cancelled ([rearm]) comes back when the run does not start its
 * process (its links on the "did not start" notification when one shows); a [destroy] that [MoleculeCleanup.destroy]
 * launched holds back runs of the role until this run ended. A stopped test marks its destroy before its handler
 * reports the end, so a run of the role that the platform starts right then (Rerun) waits for it. Notifications are
 * keyed by role, scenario and command, and name the role's root when the workspace has several, as the countdowns do.
 */
internal class MoleculeRunState(
    environment: ExecutionEnvironment,
    preparer: RunPreparer,
    private val spec: MoleculeSpec,
    private val automatic: Boolean = false,
    /** EDT. */
    private var rearm: MoleculeCleanup.Rearm? = null,
    private val destroy: MoleculeCleanup.PendingDestroy? = null,
) : AnsibleRunState(environment, preparer, observeStages = true) {
    /** A run prepared already (tests). */
    constructor(environment: ExecutionEnvironment, prepared: PreparedRun, spec: MoleculeSpec, automatic: Boolean = false) :
        this(environment, RunPreparer.of(prepared), spec, automatic)

    /** The destroy of this test, stopped: marked when its process ended ([processEnding]), launched in [processEnded]. */
    @Volatile
    private var stopDestroy: MoleculeCleanup.PendingDestroy? = null

    /** The countdown that came back because this run's preparation failed, until its notification showed, or null. EDT. */
    private var notStartedCountdown: MoleculeCountdown? = null

    override fun actions(project: Project): RunViewActions = MoleculeViewActions(project, spec)

    /** The tab exists: the [destroy] that this run carries out ends with this run's handler (also when it does not start). */
    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val destroy = destroy ?: return super.execute(executor, runner)
        val cleanup = MoleculeCleanup.getInstance(environment.project)
        val result = try {
            super.execute(executor, runner)
        } catch (e: Throwable) {
            cleanup.destroyEnded(destroy)
            throw e
        }
        result.processHandler?.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) = cleanup.destroyEnded(destroy)
        })
        return result
    }

    /**
     * A stopped test could not destroy its instances: their destroy counts from now, before the handler reports the
     * end, because the platform's Rerun starts the next run of the role as soon as it has (that run then waits for
     * the destroy, which [processEnded] launches).
     */
    override fun processEnding(exitCode: Int) {
        val project = environment.project
        if (!stopped || detached || spec.command != MoleculeCommand.TEST || project.isDisposed) return
        stopDestroy = MoleculeCleanup.getInstance(project).destroyLaunching(spec.copy(command = MoleculeCommand.DESTROY))
    }

    override fun processStarting() {
        val project = environment.project
        // The process works on the role's instances from now: the countdown its start cancelled stays gone, and one that
        // came while it prepared (another run of the role ended) goes too.
        rearm = null
        MoleculeCleanup.getInstance(project).runStarting(spec)
        MoleculeResults.getInstance(project).started(spec)
    }

    override fun processNotStarted(why: NotStarted) {
        val project = environment.project
        if (project.isDisposed) return
        val cleanup = MoleculeCleanup.getInstance(project)
        destroy?.let(cleanup::destroyEnded)
        val countdown = cleanup.runNotStarted(rearm.also { rearm = null }, balloon = why !is NotStarted.Failed)
        // A failed preparation is told in a notification that carries the countdown's links (notStartedNotified).
        if (why is NotStarted.Failed) notStartedCountdown = countdown
    }

    /** The countdown that came back, while it counts: its links lead the "did not start" notification. */
    private fun countingAgain(): MoleculeCountdown? = notStartedCountdown?.takeIf { it.state == MoleculeCountdown.State.COUNTING }

    override fun notStartedActions(): List<RunAction> = countingAgain()?.notificationActions().orEmpty()

    override fun notStartedNotified(notification: Notification?) {
        val countdown = notStartedCountdown.also { notStartedCountdown = null } ?: return
        if (notification != null && countdown.state == MoleculeCountdown.State.COUNTING) countdown.attach(notification) else countdown.showBalloon()
    }

    override fun settingsPath(): String = spec.roleDir

    /** The role, scenario and command: a copy of the role in another root, or another command, has its own notification. */
    override fun notificationKey(): String = "molecule:" + spec.roleDir + "|" + spec.scenario + "|" + spec.command.id

    /** "web › default", "web (tern) › default" when the workspace has several roots. */
    private fun subject(): String = MoleculeCleanup.subject(spec, rootName)

    override fun notStartedOutcome(reason: String): RunOutcome = RunOutcome.NotStarted(subject(), spec.command, reason, countingAgain()?.minutes)

    override fun processEnded(exitCode: Int, model: RunModel?) {
        val project = environment.project
        // The stopped test's destroy that processEnding marked: runEnded launches it (the project's end ends it).
        val marked = stopDestroy.also { stopDestroy = null }
        if (project.isDisposed) return
        val cleanup = MoleculeCleanup.getInstance(project)
        destroy?.let(cleanup::destroyEnded)
        var handed = false
        try {
            // A detached run goes on without the IDE: no result, and its instances are still in use.
            MoleculeResults.getInstance(project).finished(spec, model?.stages.orEmpty(), exitCode, stopped || detached)
            if (detached) return
            val minutes = prepared?.runner?.moleculeDestroyMinutes ?: 0
            val countdown = cleanup.runEnded(spec, minutes, banner, stopped, balloon = false, root = rootName, pending = marked)
            handed = true
            notifyEnded(model, exitCode, countdown)
        } finally {
            // Not launched: no run of the role may wait for it.
            if (!handed) marked?.let(cleanup::destroyEnded)
        }
    }

    private fun notifyEnded(model: RunModel?, exitCode: Int, countdown: MoleculeCountdown?) {
        val outcome = RunOutcomes.molecule(
            subject(), spec.command, model, exitCode, stopped, runSeconds(),
            countdownMinutes = countdown?.minutes,
            destroyFollows = stopped && spec.command == MoleculeCommand.TEST,
            automatic = automatic,
        )
        val notification = notifyEnd(outcome, endActions(model, exitCode, countdown))
        // The countdown's links are on the run's notification, or in a balloon of its own.
        countdown?.let { if (notification != null) it.attach(notification) else it.showBalloon() }
    }

    /** Keep Instances and Destroy Now while a countdown runs; Show Failed Task after a failure; Run Again; Show Run. */
    private fun endActions(model: RunModel?, exitCode: Int, countdown: MoleculeCountdown?): List<RunAction> {
        val target = runTarget() ?: return emptyList()
        val notifier = RunNotifier.getInstance(environment.project)
        val failedTask = model?.takeIf { exitCode != 0 && !stopped }?.let(RunOutcomes::failedTask)?.let { notifier.showFailedTask(target, it) }
        return when {
            countdown != null -> countdown.notificationActions() + listOfNotNull(failedTask ?: notifier.showRun(target))
            failedTask != null -> listOfNotNull(failedTask, notifier.runAgain(environment), notifier.showRun(target))
            else -> listOfNotNull(notifier.showRun(target), notifier.runAgain(environment))
        }
    }
}

/** What the Plays tab of a Molecule run offers: a stage's action again, alone, in the scenario; no playbook actions. */
internal class MoleculeViewActions(private val project: Project, private val spec: MoleculeSpec) : RunViewActions {
    override fun rerunHosts(hosts: List<String>) = Unit
    override fun startAt(task: String) = Unit
    override fun runPart(play: String, role: String?) = Unit
    override val playbookActions: Boolean get() = false
    override val stageActions: Boolean get() = true

    override fun runStage(scenario: String, action: String, unit: String?) {
        val command = MoleculeCommand.forAction(action) ?: return
        MoleculeLauncher.run(project, spec.copy(scenario = scenario, command = command))
    }
}

/** The editor of a "Molecule" run configuration. */
class MoleculeSettingsEditor(project: Project) : SettingsEditor<MoleculeConfiguration>() {
    private val roleField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.singleDir().withTitle(message("molecule.form.role.choose")))
    }
    private val scenarioField = JBTextField().apply { emptyText.text = message("molecule.all.scenarios") }
    private val commandCombo = ComboBox(MoleculeCommand.entries.toTypedArray()).apply {
        renderer = textListCellRenderer("") { message("molecule.command.${it.id}") }
    }
    private val executorCombo = ComboBox(PlaybookExecutor.entries.toTypedArray()).apply {
        renderer = textListCellRenderer("") { message("molecule.form.executor.${it.name.lowercase()}") }
    }
    private val composeFileField = JBTextField()
    private val composeServiceField = JBTextField().apply { emptyText.text = message("molecule.form.service.discovered") }
    private val argsField = JBTextField()

    override fun resetEditorFrom(configuration: MoleculeConfiguration) {
        val spec = configuration.spec
        roleField.text = spec.roleDir
        scenarioField.text = spec.scenario
        commandCombo.item = spec.command
        executorCombo.item = spec.executor
        composeFileField.text = spec.composeFile
        composeServiceField.text = spec.composeService
        argsField.text = spec.additionalArgs
    }

    override fun applyEditorTo(configuration: MoleculeConfiguration) {
        configuration.spec = MoleculeSpec(
            roleDir = roleField.text.trim(),
            scenario = scenarioField.text.trim(),
            command = commandCombo.item ?: MoleculeCommand.TEST,
            executor = executorCombo.item ?: PlaybookExecutor.AUTO,
            composeFile = composeFileField.text.trim(),
            composeService = composeServiceField.text.trim(),
            additionalArgs = argsField.text.trim(),
        )
    }

    override fun createEditor(): JComponent = panel {
        row(message("molecule.form.role")) { cell(roleField).align(AlignX.FILL) }
        row(message("molecule.form.scenario")) { cell(scenarioField).align(AlignX.FILL) }
        row(message("molecule.form.command")) { cell(commandCombo) }
        row(message("molecule.form.executor")) { cell(executorCombo) }
        row(message("molecule.form.compose.file")) { cell(composeFileField).align(AlignX.FILL) }
        row(message("molecule.form.compose.service")) { cell(composeServiceField).align(AlignX.FILL) }
        row(message("run.form.args")) { cell(argsField).align(AlignX.FILL) }
        row { comment(message("molecule.form.comment")) }
    }
}

/**
 * Runs Molecule through a "Molecule" configuration: the one of the same role, scenario and command is reused (a
 * temporary one is created otherwise), so the run configurations list holds the last runs.
 */
object MoleculeLauncher {
    fun configurations(project: Project, spec: MoleculeSpec): List<RunnerAndConfigurationSettings> =
        RunManager.getInstance(project).getConfigurationSettingsList(MoleculeConfigurationType.getInstance()).filter { settings ->
            val other = (settings.configuration as? MoleculeConfiguration)?.spec ?: return@filter false
            other.roleDir == spec.roleDir && other.scenario == spec.scenario && other.command == spec.command
        }

    /** The configuration that runs [spec]: one of the same role, scenario and command, else a new temporary one. */
    fun settingsFor(project: Project, spec: MoleculeSpec): RunnerAndConfigurationSettings {
        val runManager = RunManager.getInstance(project)
        return configurations(project, spec).firstOrNull() ?: runManager.createConfiguration(spec.name(), MoleculeConfigurationType.getInstance().factory).also {
            (it.configuration as MoleculeConfiguration).spec = spec
            it.isTemporary = true
            runManager.addConfiguration(it)
        }
    }

    /**
     * Runs [spec]; an [automatic] run is one the IDE started by itself (a destroy), which notifies only when it fails;
     * [pending] is the destroy [MoleculeCleanup.destroy] tracks, which the run reports on.
     */
    fun run(
        project: Project,
        spec: MoleculeSpec,
        executor: Executor = DefaultRunExecutor.getRunExecutorInstance(),
        automatic: Boolean = false,
        pending: MoleculeCleanup.PendingDestroy? = null,
    ): RunnerAndConfigurationSettings {
        val settings = settingsFor(project, spec)
        (settings.configuration as? MoleculeConfiguration)?.let { configuration ->
            configuration.putUserData(MoleculeConfiguration.AUTOMATIC, automatic.takeIf { it })
            configuration.putUserData(MoleculeConfiguration.PENDING_DESTROY, pending)
        }
        RunManager.getInstance(project).selectedConfiguration = settings
        val execute = executeForTests
        if (execute != null) execute(settings, executor) else ProgramRunnerUtil.executeConfiguration(settings, executor)
        return settings
    }

    /** Replaces the execution of a configuration (tests: what a button would run, without running it). */
    @TestOnly
    @Volatile
    internal var executeForTests: ((RunnerAndConfigurationSettings, Executor) -> Unit)? = null
}
