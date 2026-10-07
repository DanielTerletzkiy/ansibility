package de.terletzkiy.ansibility.run.molecule

import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.api.WorkspaceScopeService
import de.terletzkiy.ansibility.context.switching.UseAsAnsibleContextAction
import de.terletzkiy.ansibility.run.AnsibilityRunBundle.message
import de.terletzkiy.ansibility.toolwindow.AnsibleToolWindowPanel
import de.terletzkiy.ansibility.toolwindow.model.TreeView

/** Runs one Molecule [command] on the role at [roleDir], for [scenario] (null: every scenario). */
internal class MoleculeRunAction(private val roleDir: VirtualFile, private val scenario: String?, private val command: MoleculeCommand) :
    DumbAwareAction(
        if (scenario == null) message("molecule.action.all", message("molecule.command.${command.id}"))
        else message("molecule.action.scenario", message("molecule.command.${command.id}"), scenario),
        null,
        if (command == MoleculeCommand.DESTROY) AllIcons.Actions.GC else MoleculeIcons.RunMolecule,
    ) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        MoleculeLauncher.run(project, MoleculeSpec(roleDir.path, scenario.orEmpty(), command))
    }
}

/**
 * The ▶ of Molecule scenario files, like those of a playbook: ▶▶ on the first line of `molecule.yml` (test, converge,
 * verify, idempotence, destroy, test keeping the instances), ▶ on the first line of `converge.yml` and `verify.yml`.
 */
class MoleculeRunLineMarkerContributor : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? {
        if (element.firstChild != null || element is PsiWhiteSpace) return null
        val file = element.containingFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        val commands = commandsOf(virtualFile.name) ?: return null
        if (firstLeaf(file) != element) return null
        val scenario = MoleculeRunContext.scenarioOf(virtualFile) ?: return null
        val roleDir = MoleculeRunContext.roleDirOf(virtualFile) ?: return null
        val actions = commands.map { MoleculeRunAction(roleDir, scenario, it) }.toTypedArray<AnAction>()
        val state = MoleculeResults.getInstance(element.project).scenarioState(roleDir.path, scenario)
        return Info(MoleculeIcons.gutter(state, all = virtualFile.name == CONFIG), actions) { message("molecule.gutter.tooltip", scenario) }
    }

    private fun commandsOf(name: String): List<MoleculeCommand>? = when (name) {
        CONFIG -> listOf(
            MoleculeCommand.TEST, MoleculeCommand.CONVERGE, MoleculeCommand.VERIFY, MoleculeCommand.IDEMPOTENCE, MoleculeCommand.DESTROY, MoleculeCommand.TEST_KEEP,
        )
        "converge.yml", "converge.yaml" -> listOf(MoleculeCommand.CONVERGE, MoleculeCommand.TEST)
        "verify.yml", "verify.yaml" -> listOf(MoleculeCommand.VERIFY, MoleculeCommand.TEST)
        else -> null
    }

    private fun firstLeaf(file: PsiElement): PsiElement? {
        var leaf: PsiElement? = PsiTreeUtil.firstChild(file)
        while (leaf != null && (leaf is PsiWhiteSpace || leaf.textLength == 0) && leaf !is PsiComment) leaf = PsiTreeUtil.nextLeaf(leaf)
        return leaf
    }

    private companion object {
        const val CONFIG = "molecule.yml"
    }
}

/**
 * "Run Molecule Test": `molecule test` of the selected role (every scenario) or scenario, from the Project view (a role,
 * its `molecule` folder or a scenario) and the rows of the Ansibility tool window. Several selected roles (Cmd/Shift-
 * click) run one after another in one tab (plan amendment R16).
 */
class RunMoleculeTestAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val targets = targets(e)
        e.presentation.isEnabledAndVisible = targets.isNotEmpty()
        val single = targets.singleOrNull()
        e.presentation.text = when {
            targets.isEmpty() -> message("molecule.action.role", "")
            single == null -> message("molecule.action.roles", targets.size)
            single.spec.scenario.isEmpty() -> message("molecule.action.role", single.name)
            else -> message("molecule.action.role.scenario", single.name, single.spec.scenario)
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        MoleculeBatchLauncher.run(project, targets(e))
    }

    private fun targets(e: AnActionEvent): List<MoleculeTarget> {
        val project = e.project ?: return emptyList()
        val panel = e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT)?.let { UseAsAnsibleContextAction.toolWindowPanelOf(it) }
        if (panel != null) {
            val nodes = e.updateSession.compute(this, "Ansibility tool window selection", ActionUpdateThread.EDT) { panel.selectedNodes() }
            return MoleculeTargets.ofNodes(nodes)
        }
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList() ?: listOfNotNull(e.getData(CommonDataKeys.VIRTUAL_FILE))
        return MoleculeTargets.ofFiles(project, files)
    }
}

/**
 * "Run Molecule Tests" in the Ansibility tool window's toolbar (Repos and Roles tabs, plan amendment R16): the selected
 * roles, or with none selected every role with Molecule scenarios in the workspace scope, after a confirmation; one
 * after another in one run tab.
 */
class RunMoleculeTestsAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val panel = panel(e)
        e.presentation.isEnabledAndVisible = panel != null && panel.view != TreeView.ENVIRONMENTS
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val panel = panel(e) ?: return
        val selected = MoleculeTargets.ofNodes(panel.selectedNodes())
        val targets = selected.ifEmpty { MoleculeTargets.inScope(panel.snapshot, WorkspaceScopeService.getInstance(project).current()) }
        if (targets.isEmpty()) {
            Messages.showInfoMessage(project, message("molecule.action.none"), message("molecule.action.all.title"))
            return
        }
        if (selected.isEmpty() && targets.size > 1) {
            val roots = targets.map { it.label }.distinct().joinToString(", ")
            val answer = Messages.showOkCancelDialog(
                project, message("molecule.action.all.confirm", targets.size, roots), message("molecule.action.all.title"),
                message("molecule.action.all.ok"), Messages.getCancelButton(), MoleculeIcons.Molecule,
            )
            if (answer != Messages.OK) return
        }
        MoleculeBatchLauncher.run(project, targets)
    }

    private fun panel(e: AnActionEvent): AnsibleToolWindowPanel? =
        e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT)?.let { UseAsAnsibleContextAction.toolWindowPanelOf(it) }
}
