package de.terletzkiy.ansibility.run

import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.context.switching.UseAsAnsibleContextAction
import de.terletzkiy.ansibility.toolwindow.model.PlaybookNode
import org.jetbrains.yaml.psi.YAMLFile

/** Whether [file] is a playbook of an Ansible root. */
internal fun isPlaybook(project: Project, file: VirtualFile): Boolean =
    file.isValid && !file.isDirectory && AnsibleWorkspace.getInstance(project).contextOf(file)?.kind == FileKind.PLAYBOOK

/** The playbook of the event: the selected playbook row of the Ansibility tool window, else the context file. */
private fun AnActionEvent.playbook(action: DumbAwareAction): VirtualFile? {
    val project = project ?: return null
    val component = getData(PlatformCoreDataKeys.CONTEXT_COMPONENT)
    val panel = component?.let { UseAsAnsibleContextAction.toolWindowPanelOf(it) }
    if (panel != null) {
        val node = updateSession.compute(action, "Ansibility tool window selection", ActionUpdateThread.EDT) { panel.selectedNode() }
        return (node as? PlaybookNode)?.file?.takeIf { isPlaybook(project, it) }
    }
    return getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { isPlaybook(project, it) }
}

/** "Run Playbook…": the run dialog (environment, limit, tags, …) for the playbook at hand. */
class RunPlaybookAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.playbook(this) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val playbook = e.playbook(this) ?: return
        PlaybookLauncher.openDialog(project, playbook)
    }
}

/** "Run Again": the playbook's last run configuration, without the dialog. */
class RerunPlaybookAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val playbook = e.playbook(this)
        val last = if (project == null || playbook == null) null else PlaybookLauncher.configurations(project, playbook.path).firstOrNull()
        e.presentation.isEnabledAndVisible = last != null
        if (last != null) e.presentation.text = AnsibilityRunBundle.message("action.Ansibility.RerunPlaybook.text.named", last.name)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val playbook = e.playbook(this) ?: return
        PlaybookLauncher.rerun(project, playbook)
    }
}

/**
 * The gutter icons of a playbook, like those of a Compose file: ▶▶ on the first line runs the playbook, ▶ on each
 * play and on each role entry (an item of `roles:`, an `import_role`/`include_role` task) runs the playbook with the
 * `--tags` that select that play or role. When the first play starts on the first line, its icon carries both.
 */
class PlaybookRunLineMarkerContributor : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? {
        if (element.firstChild != null || element is PsiWhiteSpace) return null
        val file = element.containingFile as? YAMLFile ?: return null
        val first = firstLeaf(file) == element
        val entry = PlaybookParts.entryAt(element)
        if (!first && entry == null) return null
        val virtualFile = file.virtualFile ?: return null
        if (!isPlaybook(element.project, virtualFile)) return null
        val actions = ArrayList<AnAction>()
        if (first) actions += targetActions(virtualFile, PlaybookTarget.PLAYBOOK, virtualFile.name, AnsibilityRunBundle.message("run.gutter.run.playbook"))
        val tooltip = when (entry) {
            is PlaybookPlay -> {
                actions += targetActions(virtualFile, entry.target, entry.label, AnsibilityRunBundle.message("run.gutter.run.play", entry.label))
                AnsibilityRunBundle.message("run.gutter.tooltip.play", entry.label)
            }
            is PlaybookRole -> {
                actions += targetActions(virtualFile, entry.target, entry.name, AnsibilityRunBundle.message("run.gutter.run.role", entry.name))
                AnsibilityRunBundle.message("run.gutter.tooltip.role", entry.name)
            }
            else -> null
        }
        val icon = if (first) AllIcons.RunConfigurations.TestState.Run_run else AllIcons.RunConfigurations.TestState.Run
        val text = if (first) AnsibilityRunBundle.message("run.gutter.tooltip") else tooltip.orEmpty()
        return Info(icon, actions.toTypedArray()) { text }
    }

    private fun targetActions(playbook: VirtualFile, target: PlaybookTarget, label: String, runText: String): List<AnAction> =
        listOf(TargetRunAction(playbook, target, runText), TargetQuickRunAction(playbook, target, label))

    private fun firstLeaf(file: PsiElement): PsiElement? {
        var leaf: PsiElement? = PsiTreeUtil.firstChild(file)
        while (leaf != null && (leaf is PsiWhiteSpace || leaf.textLength == 0) && leaf !is PsiComment) leaf = PsiTreeUtil.nextLeaf(leaf)
        return leaf
    }
}

/** "Run Play 'System'…": the run dialog for one part of the playbook, filled in from its (or the playbook's) last run. */
private class TargetRunAction(private val playbook: VirtualFile, private val target: PlaybookTarget, text: String) :
    DumbAwareAction(text, null, AllIcons.Actions.Execute) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        PlaybookLauncher.openDialog(project, playbook, target)
    }
}

/**
 * Without the dialog: "Run 'x' Again" with the target's last configuration, else "Run 'x' with Last Settings" (the
 * playbook's last run with the tags that select the target; the dialog when the target lacks tags); hidden when the
 * playbook never ran.
 */
private class TargetQuickRunAction(private val playbook: VirtualFile, private val target: PlaybookTarget, private val label: String) :
    DumbAwareAction(AnsibilityRunBundle.message("run.gutter.last", label), null, AllIcons.Actions.Rerun) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val own = project?.let { PlaybookLauncher.configurations(it, playbook.path, target).firstOrNull() }
        val last = if (own == null && project != null) PlaybookLauncher.lastSpec(project, playbook) else null
        e.presentation.isEnabledAndVisible = own != null || last != null
        e.presentation.text = when {
            own != null -> AnsibilityRunBundle.message("action.Ansibility.RerunPlaybook.text.named", own.name)
            last?.environment != null -> AnsibilityRunBundle.message("run.gutter.last.environment", label, last.environment)
            else -> AnsibilityRunBundle.message("run.gutter.last", label)
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (PlaybookLauncher.configurations(project, playbook.path, target).isNotEmpty()) {
            PlaybookLauncher.rerun(project, playbook, target)
        } else {
            PlaybookLauncher.runWithLastSettings(project, playbook, target)
        }
    }
}
