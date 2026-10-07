package de.terletzkiy.ansibility.run.molecule

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
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import de.terletzkiy.ansibility.run.AnsibilityRunBundle.message
import de.terletzkiy.ansibility.run.AnsibleRunState
import de.terletzkiy.ansibility.run.PlaybookExecutor
import de.terletzkiy.ansibility.run.PreparedRun
import de.terletzkiy.ansibility.run.events.RunModel
import de.terletzkiy.ansibility.run.view.RunBanner
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

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
        val spec = spec
        // Before preparing: a destroy countdown of the role must not hit the instances this run works on.
        MoleculeCleanup.getInstance(project).runStarting(spec)
        val prepared = MoleculePreparation.prepare(project, spec) ?: return null
        return MoleculeRunState(environment, prepared, spec)
    }
}

/**
 * A Molecule run: the Plays tab with its stages, the results of its scenarios (gutter, Roles tab) and, for a command
 * that leaves instances, the destroy countdown in a banner above its tabs once it ended (plan amendment R15).
 */
internal class MoleculeRunState(environment: ExecutionEnvironment, private val prepared: PreparedRun, private val spec: MoleculeSpec) :
    AnsibleRunState(environment, prepared, observeStages = true) {
    override val banner: RunBanner = RunBanner()

    override fun actions(project: Project): RunViewActions = MoleculeViewActions(project, spec)

    override fun processStarting() = MoleculeResults.getInstance(environment.project).started(spec)

    override fun processEnded(exitCode: Int, model: RunModel?) {
        val project = environment.project
        if (project.isDisposed) return
        MoleculeResults.getInstance(project).finished(spec, model?.stages.orEmpty(), exitCode, stopped)
        MoleculeCleanup.getInstance(project).runEnded(spec, prepared.runner.moleculeDestroyMinutes, banner, stopped)
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

    fun run(project: Project, spec: MoleculeSpec, executor: Executor = DefaultRunExecutor.getRunExecutorInstance()): RunnerAndConfigurationSettings {
        val settings = settingsFor(project, spec)
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
