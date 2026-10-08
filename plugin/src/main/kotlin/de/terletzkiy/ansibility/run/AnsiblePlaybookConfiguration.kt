package de.terletzkiy.ansibility.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.LocatableRunConfigurationOptions
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.execution.ParametersListUtil
import de.terletzkiy.ansibility.run.events.RunModel
import de.terletzkiy.ansibility.run.notify.RunAction
import de.terletzkiy.ansibility.run.notify.RunNotifier
import de.terletzkiy.ansibility.run.notify.RunOutcomes
import de.terletzkiy.ansibility.run.view.RunViewActions
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

    /**
     * Cheap, on the EDT (plan amendment R19): the run prepares in its tab, which says what it does, and that it did not
     * start when the user cancels a question (environment choice, production confirmation, branch behind or not
     * verified, become password). A cancelled vault password prompt does not end the run: it runs without vault secrets
     * and its header says so ("Vault not unlocked (cancelled)"). Cancel there is the only way to run a playbook in a
     * root without vault: such a root still has the `default` vault id, which is asked for when nothing else supplies it.
     */
    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        val spec = spec
        return PlaybookRunState(environment, RunPreparer { reporter -> PlaybookPreparation.prepareAsync(project, spec, reporter) }, spec)
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

/**
 * A playbook run: the shared [AnsibleRunState], with reruns of hosts, starts at a task and runs of a play or role. Its
 * end is told in a notification (plan amendment R19, D147–D149): passed, failed on which hosts at which task, stopped
 * where, or failed before any play, with "Show Failed Task", "Rerun Failed Hosts", "Run Again" and "Show Run".
 */
class PlaybookRunState(environment: ExecutionEnvironment, preparer: RunPreparer, private val spec: PlaybookRunSpec) :
    AnsibleRunState(environment, preparer) {
    override fun settingsPath(): String = spec.playbook

    /** The playbook and the configuration: a same-named playbook of another root has its own notification. */
    override fun notificationKey(): String = "playbook:" + spec.playbook + "|" + configurationId()

    override fun processEnded(exitCode: Int, model: RunModel?) {
        val project = environment.project
        // A detached run goes on without the IDE: its end is not known.
        if (project.isDisposed || detached) return
        val target = runTarget() ?: return
        val notifier = RunNotifier.getInstance(project)
        val outcome = RunOutcomes.playbook(titled(environment.runProfile.name), model, exitCode, stopped, runSeconds())
        val failedHosts = model?.failedHosts().orEmpty()
        val actions = if (failedHosts.isNotEmpty() && !stopped) {
            // The notification stays in the history: its actions hold the spec and names, not this run.
            val spec = spec
            listOfNotNull(
                model?.let(RunOutcomes::failedTask)?.let { notifier.showFailedTask(target, it) },
                RunAction(AnsibilityRunBundle.message("run.view.action.rerun.failed")) { rerunHosts(project, spec, failedHosts) },
                notifier.runAgain(environment),
                notifier.showRun(target),
            )
        } else {
            listOfNotNull(notifier.showRun(target), notifier.runAgain(environment))
        }
        notifyEnd(outcome, actions)
    }

    /** The run view's actions: one-off runs of the same configuration, and the run dialog of a play or role. */
    override fun actions(project: Project): RunViewActions = object : RunViewActions {
        override fun rerunHosts(hosts: List<String>) = PlaybookRunState.rerunHosts(project, spec, hosts)

        override fun startAt(task: String) {
            val args = (spec.additionalArgs + " " + ParametersListUtil.join("--start-at-task", task)).trim()
            PlaybookLauncher.runOnce(project, spec.copy(additionalArgs = args), AnsibilityRunBundle.message("run.view.once.start", AnsiblePlaybookConfiguration.nameFor(spec), task))
        }

        override fun runPart(play: String, role: String?) {
            val file = LocalFileSystem.getInstance().findFileByPath(spec.playbook) ?: return
            val target = if (role == null) PlaybookTarget.play(-1, play) else PlaybookTarget.role(-1, play, -1, role)
            PlaybookLauncher.openDialog(project, file, target)
        }
    }

    private companion object {
        /** Runs [spec] once more on [hosts] only (`--limit`), through a configuration of its own. */
        fun rerunHosts(project: Project, spec: PlaybookRunSpec, hosts: List<String>) {
            if (hosts.isEmpty()) return
            val limit = hosts.joinToString(",")
            PlaybookLauncher.runOnce(project, spec.copy(limit = limit), AnsibilityRunBundle.message("run.view.once.hosts", AnsiblePlaybookConfiguration.nameFor(spec), limit))
        }
    }
}
