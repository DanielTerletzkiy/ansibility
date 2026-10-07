package de.terletzkiy.ansibility.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.LocatableRunConfigurationOptions
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import de.terletzkiy.ansibility.toolwindow.AnsibilityToolWindowIcons
import java.nio.file.Path
import kotlin.io.path.name

/** The "Ansible Playbook" run configuration type. */
class AnsiblePlaybookConfigurationType : ConfigurationTypeBase(
    ID,
    AnsibilityRunBundle.message("run.type.name"),
    AnsibilityRunBundle.message("run.type.description"),
    NotNullLazyValue.createValue { AnsibilityToolWindowIcons.Root },
) {
    init {
        addFactory(AnsiblePlaybookConfigurationFactory(this))
    }

    val factory: ConfigurationFactory get() = configurationFactories.single()

    companion object {
        const val ID = "AnsibilityPlaybook"

        fun getInstance(): AnsiblePlaybookConfigurationType = ConfigurationTypeUtil.findConfigurationType(AnsiblePlaybookConfigurationType::class.java)
    }
}

class AnsiblePlaybookConfigurationFactory(type: AnsiblePlaybookConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = AnsiblePlaybookConfigurationType.ID

    override fun createTemplateConfiguration(project: Project): RunConfiguration = AnsiblePlaybookConfiguration(project, this, "")

    override fun getOptionsClass(): Class<out BaseState> = AnsiblePlaybookRunOptions::class.java
}

/** The stored form of a [PlaybookRunSpec]. */
class AnsiblePlaybookRunOptions : LocatableRunConfigurationOptions() {
    var playbook by string("")
    var targetKind by enum(TargetKind.PLAYBOOK)
    var playIndex by property(-1)
    var playName by string("")
    var roleIndex by property(-1)
    var roleName by string("")
    var environment by string("")
    var limit by string("")
    var tags by string("")
    var skipTags by string("")
    var extraVars by string("")
    var check by property(false)
    var diff by property(false)
    var verbosity by property(0)
    /** "true", "false", or empty for automatic (old configurations stored only `true`). */
    var become by string("")
    var skipFreshnessCheck by property(false)
    var executor by enum(PlaybookExecutor.AUTO)
    var composeFile by string("")
    var composeService by string("")
    var envFile by string("")
    var additionalArgs by string("")
}

/** Runs one playbook with [spec]: unlocks the root's vault ids and starts `ansible-playbook` locally or in its Compose service. */
class AnsiblePlaybookConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<AnsiblePlaybookRunOptions>(project, factory, name) {

    override fun getOptions(): AnsiblePlaybookRunOptions = super.getOptions() as AnsiblePlaybookRunOptions

    var spec: PlaybookRunSpec
        get() = with(options) {
            PlaybookRunSpec(
                playbook = playbook.orEmpty(),
                target = PlaybookTarget(targetKind, playIndex, playName.orEmpty(), roleIndex, roleName.orEmpty()),
                environment = environment?.takeIf { it.isNotBlank() },
                limit = limit.orEmpty(),
                tags = tags.orEmpty(),
                skipTags = skipTags.orEmpty(),
                extraVars = extraVars.orEmpty(),
                check = check,
                diff = diff,
                verbosity = verbosity,
                become = when (become) {
                    "true" -> true
                    "false" -> false
                    else -> null
                },
                skipFreshnessCheck = skipFreshnessCheck,
                executor = executor,
                composeFile = composeFile.orEmpty(),
                composeService = composeService.orEmpty(),
                envFile = envFile.orEmpty(),
                additionalArgs = additionalArgs.orEmpty(),
            )
        }
        set(value) = with(options) {
            playbook = value.playbook
            targetKind = value.target.kind
            playIndex = value.target.playIndex
            playName = value.target.playName
            roleIndex = value.target.roleIndex
            roleName = value.target.roleName
            environment = value.environment.orEmpty()
            limit = value.limit
            tags = value.tags
            skipTags = value.skipTags
            extraVars = value.extraVars
            check = value.check
            diff = value.diff
            verbosity = value.verbosity
            become = value.become?.toString().orEmpty()
            skipFreshnessCheck = value.skipFreshnessCheck
            executor = value.executor
            composeFile = value.composeFile
            composeService = value.composeService
            envFile = value.envFile
            additionalArgs = value.additionalArgs
        }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = PlaybookSettingsEditor(project)

    override fun checkConfiguration() {
        if (options.playbook.isNullOrBlank()) throw RuntimeConfigurationError(AnsibilityRunBundle.message("run.error.no.playbook"))
    }

    override fun suggestedName(): String? = options.playbook?.takeIf { it.isNotBlank() }?.let { nameFor(spec) }

    /** Null when the user cancels a prompt (environment, become password, production confirmation). */
    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
        val prepared = PlaybookPreparation.prepare(project, spec) ?: return null
        return PlaybookRunState(environment, prepared)
    }

    companion object {
        /** `playbook.yml`, `playbook.yml › System`, `playbook.yml › nginx`, each with ` [environment]` when one is chosen. */
        fun nameFor(spec: PlaybookRunSpec): String {
            val file = runCatching { Path.of(spec.playbook).name }.getOrDefault(spec.playbook)
            val part = when (spec.target.kind) {
                TargetKind.PLAYBOOK -> null
                TargetKind.PLAY -> spec.target.playName.ifEmpty { "#${spec.target.playIndex + 1}" }
                TargetKind.ROLE -> spec.target.roleName
            }
            val name = if (part == null) file else "$file \u203A $part"
            return if (spec.environment.isNullOrBlank()) name else "$name [${spec.environment}]"
        }
    }
}

/** Starts the prepared process in the Run console and deletes the run's secret scripts and temporary playbook when it ends. */
class PlaybookRunState(environment: ExecutionEnvironment, private val prepared: PreparedRun) : CommandLineState(environment) {
    override fun startProcess(): ProcessHandler {
        val process = prepared.process
        val commandLine = GeneralCommandLine(process.command)
            .withWorkingDirectory(process.workDir)
            .withEnvironment(process.environment)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withCharset(Charsets.UTF_8)
        val handler = try {
            KillableColoredProcessHandler(commandLine)
        } catch (e: ExecutionException) {
            prepared.close()
            throw e
        }
        handler.addProcessListener(object : ProcessListener {
            override fun startNotified(event: ProcessEvent) {
                prepared.header.forEach { handler.notifyTextAvailable("$it\n", ProcessOutputTypes.SYSTEM) }
                handler.notifyTextAvailable("${commandLine.commandLineString}\n\n", ProcessOutputTypes.SYSTEM)
            }

            override fun processTerminated(event: ProcessEvent) {
                prepared.close()
            }
        })
        ProcessTerminatedListener.attach(handler)
        return handler
    }
}
