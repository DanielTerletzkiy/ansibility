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
import org.jetbrains.annotations.TestOnly
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
        // "Save as run configuration" adds a new saved configuration, so it starts off: a saved one is changed in its
        // own editor, never by a run from here (configurationFor).
        val dialog = RunPlaybookDialog(project, context, initial, saved = false)
        if (!dialog.showAndGet()) return
        val additions = dialog.additions
        if (!PlaybookTagEdits.apply(project, playbook, additions)) {
            Messages.showErrorDialog(project, AnsibilityRunBundle.message("run.tags.add.failed", playbook.name), AnsibilityRunBundle.message("run.tags.command"))
            return
        }
        run(project, dialog.spec, dialog.save)
    }

    /**
     * Runs [spec] once, through a configuration that is not added to the run configurations (a rerun of failed hosts,
     * a start at a task): the playbook's own configurations keep their settings.
     */
    fun runOnce(project: Project, spec: PlaybookRunSpec, name: String) {
        runOnceForTests?.let { return it(spec, name) }
        val settings = RunManager.getInstance(project).createConfiguration(name, AnsiblePlaybookConfigurationType.getInstance().factory)
        (settings.configuration as AnsiblePlaybookConfiguration).spec = spec
        settings.isTemporary = true
        ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
    }

    /** Replaces [runOnce] (tests: what a rerun would run, without running it). */
    @TestOnly
    @Volatile
    internal var runOnceForTests: ((PlaybookRunSpec, String) -> Unit)? = null

    /** Runs [spec] through the configuration [configurationFor] picks, and selects it. */
    fun run(project: Project, spec: PlaybookRunSpec, save: Boolean): RunnerAndConfigurationSettings {
        val settings = configurationFor(project, spec, save)
        RunManager.getInstance(project).selectedConfiguration = settings
        ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
        return settings
    }

    /**
     * The configuration a run of [spec] goes through. A saved configuration is never changed here, only in its own
     * editor: one of the playbook with exactly [spec] is taken as it is (made permanent with [save]). Otherwise [save]
     * adds a new saved configuration under a name of its own, and a run without [save] updates a temporary
     * configuration of the playbook's target (the one of the same environment first) or adds a new temporary one.
     */
    internal fun configurationFor(project: Project, spec: PlaybookRunSpec, save: Boolean): RunnerAndConfigurationSettings {
        val runManager = RunManager.getInstance(project)
        val type = AnsiblePlaybookConfigurationType.getInstance()
        val existing = configurations(project, spec.playbook, spec.target)
        fun specOf(settings: RunnerAndConfigurationSettings) = (settings.configuration as AnsiblePlaybookConfiguration).spec
        existing.firstOrNull { specOf(it) == spec }?.let { identical ->
            if (save && identical.isTemporary) runManager.makeStable(identical)
            return identical
        }
        val name = AnsiblePlaybookConfiguration.nameFor(spec)
        if (save) {
            val created = runManager.createConfiguration(runManager.suggestUniqueName(name, type), type.factory)
            (created.configuration as AnsiblePlaybookConfiguration).spec = spec
            runManager.addConfiguration(created)
            return created
        }
        val temporary = existing.firstOrNull { it.isTemporary && specOf(it).environment == spec.environment }
            ?: existing.firstOrNull { it.isTemporary }
        if (temporary != null) {
            (temporary.configuration as AnsiblePlaybookConfiguration).spec = spec
            temporary.name = name
            return temporary
        }
        val created = runManager.createConfiguration(name, type.factory)
        (created.configuration as AnsiblePlaybookConfiguration).spec = spec
        created.isTemporary = true
        runManager.addConfiguration(created)
        return created
    }
}
