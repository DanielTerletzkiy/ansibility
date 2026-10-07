package de.terletzkiy.ansibility.run

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.psi.PsiManager
import org.jetbrains.yaml.psi.YAMLFile

/**
 * Opens the run dialog of a playbook (or of one of its plays or roles) and runs it through an "Ansible Playbook"
 * configuration: the last one of the playbook and target (temporary unless saved) is reused, so the next run starts
 * from the same choices. A play or role runs the whole playbook with the `--tags` that select it ([TagSelection]),
 * starting from the playbook's last run; tags it lacks are added to the playbook when the user agrees.
 */
object PlaybookLauncher {
    /**
     * The configurations of [playbook] (of [target] only, when given): the selected one first, then temporary ones
     * (the last runs), then saved ones.
     */
    fun configurations(project: Project, playbook: String, target: PlaybookTarget? = null): List<RunnerAndConfigurationSettings> {
        val runManager = RunManager.getInstance(project)
        val selected = runManager.selectedConfiguration
        return runManager.getConfigurationSettingsList(AnsiblePlaybookConfigurationType.getInstance())
            .filter { settings ->
                val spec = (settings.configuration as? AnsiblePlaybookConfiguration)?.spec
                spec?.playbook == playbook && (target == null || spec.target.sameAs(target))
            }
            .sortedWith(compareBy({ it != selected }, { !it.isTemporary }))
    }

    /** The playbook's last run settings, for a run "with the last settings"; null when it never ran. */
    fun lastSpec(project: Project, playbook: VirtualFile): PlaybookRunSpec? =
        (configurations(project, playbook.path).firstOrNull()?.configuration as? AnsiblePlaybookConfiguration)?.spec

    /** Runs [playbook] with its last configuration (of [target], when given); opens the dialog when it has none. */
    fun rerun(project: Project, playbook: VirtualFile, target: PlaybookTarget? = null) {
        val last = configurations(project, playbook.path, target).firstOrNull() ?: return openDialog(project, playbook, target ?: PlaybookTarget.PLAYBOOK)
        RunManager.getInstance(project).selectedConfiguration = last
        ProgramRunnerUtil.executeConfiguration(last, DefaultRunExecutor.getRunExecutorInstance())
    }

    /**
     * Runs [target] with the playbook's last settings and the tags that select it, without the dialog; opens the dialog
     * when the playbook never ran or the target lacks tags.
     */
    fun runWithLastSettings(project: Project, playbook: VirtualFile, target: PlaybookTarget) {
        val last = lastSpec(project, playbook) ?: return openDialog(project, playbook, target)
        if (target.kind == TargetKind.PLAYBOOK) {
            run(project, last.copy(target = target, tags = if (last.target.kind == TargetKind.PLAYBOOK) last.tags else ""), save = false)
            return
        }
        val selection = runReadActionBlocking {
            (PsiManager.getInstance(project).findFile(playbook) as? YAMLFile)?.let { PlaybookParts.selection(PlaybookParts.plays(it), target) }
        }
        if (selection == null || selection.tags.isEmpty() || selection.additions.isNotEmpty()) return openDialog(project, playbook, target)
        run(project, last.copy(target = target, tags = selection.tags.joinToString(",")), save = false)
    }

    /** EDT: collects the playbook's context behind a modal progress, shows the dialog and runs what the user chose. */
    fun openDialog(project: Project, playbook: VirtualFile, target: PlaybookTarget = PlaybookTarget.PLAYBOOK) {
        val context = runWithModalProgressBlocking(project, AnsibilityRunBundle.message("run.progress.collect")) {
            PlaybookRunContext.collect(project, playbook)
        } ?: return
        val own = configurations(project, playbook.path, target).firstOrNull()
        val base = (own ?: configurations(project, playbook.path).firstOrNull())?.configuration as? AnsiblePlaybookConfiguration
        val initial = base?.spec?.let { context.withTarget(it, target) }
            ?.let { spec -> if (context.environment(spec.environment) == null) spec.copy(environment = context.preselectedEnvironment()) else spec }
            ?: context.initialSpec(target)
        val dialog = RunPlaybookDialog(project, context, initial, saved = own != null && !own.isTemporary)
        if (!dialog.showAndGet()) return
        val additions = dialog.additions
        if (!PlaybookTagEdits.apply(project, playbook, additions)) {
            Messages.showErrorDialog(project, AnsibilityRunBundle.message("run.tags.add.failed", playbook.name), AnsibilityRunBundle.message("run.tags.command"))
            return
        }
        run(project, dialog.spec, dialog.save)
    }

    /**
     * Runs [spec]: a configuration of the playbook for the same target and environment is updated (a temporary one of
     * the target otherwise), else a new one is added, temporary unless [save].
     */
    fun run(project: Project, spec: PlaybookRunSpec, save: Boolean): RunnerAndConfigurationSettings {
        val runManager = RunManager.getInstance(project)
        val existing = configurations(project, spec.playbook, spec.target)
        val name = AnsiblePlaybookConfiguration.nameFor(spec)
        val reused = existing.firstOrNull { (it.configuration as AnsiblePlaybookConfiguration).spec.environment == spec.environment }
            ?: existing.firstOrNull { it.isTemporary }
        val settings = reused ?: runManager.createConfiguration(name, AnsiblePlaybookConfigurationType.getInstance().factory)
        (settings.configuration as AnsiblePlaybookConfiguration).spec = spec
        if (reused == null || reused.isTemporary) settings.name = name
        if (reused == null) {
            settings.isTemporary = !save
            runManager.addConfiguration(settings)
        } else if (save && reused.isTemporary) {
            runManager.makeStable(reused)
        }
        runManager.selectedConfiguration = settings
        ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
        return settings
    }
}
