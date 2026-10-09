package de.terletzkiy.ansibility.run.molecule

import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.api.WorkspaceScope
import de.terletzkiy.ansibility.api.WorkspaceScopeService
import de.terletzkiy.ansibility.context.switching.UseAsAnsibleContextAction
import de.terletzkiy.ansibility.model.role.ExternalGoldenRoot
import de.terletzkiy.ansibility.run.AnsibilityRunBundle.message
import de.terletzkiy.ansibility.toolwindow.AnsibleToolWindowPanel
import de.terletzkiy.ansibility.toolwindow.AnsibleToolWindowScope
import de.terletzkiy.ansibility.toolwindow.model.AnsibleTreeNode
import de.terletzkiy.ansibility.toolwindow.model.RoleNameNode
import de.terletzkiy.ansibility.toolwindow.model.TreeView
import de.terletzkiy.ansibility.toolwindow.model.WorkspaceSnapshot
import de.terletzkiy.ansibility.toolwindow.model.rootsKnown
import de.terletzkiy.ansibility.workspace.WorkspaceScopeImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.Nls

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
 * Only in a scenario (a `molecule.yml` next to the file: without it Molecule would not run it) and only with "Run
 * Molecule tests" on (plan amendments R19, D140, and R20, D152); never in the external golden root (R25, D199).
 */
class MoleculeRunLineMarkerContributor : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? {
        if (element.firstChild != null || element is PsiWhiteSpace) return null
        val file = element.containingFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        val commands = commandsOf(virtualFile.name) ?: return null
        if (firstLeaf(file) != element) return null
        if (!MoleculeScenarios.inScenario(virtualFile) || !MoleculeScenarios.runsTests(element.project)) return null
        // R25 (D199): never on the external golden root (a git mirror or a folder outside the project).
        if (ExternalGoldenRoot.getInstance(element.project).isUnder(virtualFile)) return null
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
 * its `molecule` folder or a scenario) and the rows of the Ansibility tool window, where a role-name row stands for its
 * copies in the scope picker's roots (plan amendment R19, D143: the entry hides when the scope holds none, and a single
 * copy names its repo). Several selected roles (Cmd/Shift-click) run one after another in one tab (R16).
 */
class RunMoleculeTestAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val selection = e.project?.let { selection(e, it) }
        val targets = selection?.targets.orEmpty()
        e.presentation.isEnabledAndVisible = targets.isNotEmpty()
        val single = targets.singleOrNull()
        e.presentation.text = when {
            targets.isEmpty() -> message("molecule.action.role", "")
            single == null -> message("molecule.action.roles", targets.size)
            single.spec.scenario.isNotEmpty() -> message("molecule.action.role.scenario", single.name, single.spec.scenario)
            // A role-name row's copy: which repo's (the row lists them all; the scope picked this one).
            selection?.fromRoleRow == true -> message("molecule.action.role.copy", single.name, single.label)
            else -> message("molecule.action.role", single.name)
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val panel = panelOf(e)
        if (panel == null) {
            MoleculeBatchLauncher.run(project, MoleculeTargets.ofFiles(project, filesOf(e)))
            return
        }
        val nodes = panel.selectedNodes()
        withScope(project, needsRoots = nodes.any { it is RoleNameNode }) { scope -> MoleculeBatchLauncher.run(project, MoleculeTargets.ofNodes(nodes, scope)) }
    }

    /** What the event's selection runs: on the update thread (BGT), where a named scope's roots may be worked out. */
    private fun selection(e: AnActionEvent, project: Project): Selection {
        val panel = panelOf(e)
        if (panel != null) {
            val nodes = e.updateSession.compute(this, "Ansibility tool window selection", ActionUpdateThread.EDT) { panel.selectedNodes() }
            return Selection(MoleculeTargets.ofNodes(nodes, WorkspaceScopeService.getInstance(project).current()), nodes.any { it is RoleNameNode })
        }
        return Selection(MoleculeTargets.ofFiles(project, filesOf(e)), fromRoleRow = false)
    }

    private fun filesOf(e: AnActionEvent): List<VirtualFile> =
        e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList() ?: listOfNotNull(e.getData(CommonDataKeys.VIRTUAL_FILE))

    /** The targets of a selection, and whether it holds a role-name row. */
    private class Selection(val targets: List<MoleculeTarget>, val fromRoleRow: Boolean)
}

/**
 * "Run Molecule Tests" in the Ansibility tool window's toolbar (Repos and Roles tabs, plan amendments R16, R19): the
 * selected rows (a role-name row: its copies in the scope), or with none selected every role with Molecule scenarios
 * in the scope, after a confirmation; one after another in one run tab. It shows only when a role of the scope's roots
 * has scenarios (D140: the snapshot knows which, so the update reads no VFS), and a selection without tests in the
 * scope runs nothing but says why and how to test the scope (D143), instead of falling back to the scope.
 */
class RunMoleculeTestsAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val panel = panelOf(e)
        e.presentation.isEnabledAndVisible = project != null && panel != null && panel.view != TreeView.ENVIRONMENTS &&
            MoleculeTargets.anyInScope(panel.snapshot, WorkspaceScopeService.getInstance(project).current())
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val panel = panelOf(e) ?: return
        val nodes = panel.selectedNodes()
        val snapshot = panel.snapshot
        withScope(project, needsRoots = nodes.isEmpty() || nodes.any { it is RoleNameNode }) { scope -> run(project, nodes, snapshot, scope) }
    }

    private fun run(project: Project, nodes: List<AnsibleTreeNode>, snapshot: WorkspaceSnapshot, scope: WorkspaceScope) {
        val title = message("molecule.action.all.title")
        val label = (scope as? WorkspaceScopeImpl)?.label.orEmpty()
        if (nodes.isNotEmpty()) {
            val targets = MoleculeTargets.ofNodes(nodes, scope)
            if (targets.isEmpty()) {
                Messages.showInfoMessage(project, nothingSelected(nodes, label), title)
                return
            }
            MoleculeBatchLauncher.run(project, targets)
            return
        }
        val targets = MoleculeTargets.inScope(snapshot, scope)
        if (targets.isEmpty()) {
            Messages.showInfoMessage(project, message("molecule.action.none", label), title)
            return
        }
        if (targets.size > 1) {
            val roots = targets.map { it.label }.distinct().joinToString(", ")
            val answer = Messages.showOkCancelDialog(
                project, message("molecule.action.all.confirm", targets.size, roots), title,
                message("molecule.action.all.ok"), Messages.getCancelButton(), MoleculeIcons.Molecule,
            )
            if (answer != Messages.OK) return
        }
        MoleculeBatchLauncher.run(project, targets)
    }

    /**
     * Why the selection runs nothing, and the way out (D143): role-name rows whose tested copies all lie outside the
     * scope say so; any other selection (a role without scenarios, a host, a playbook) has no Molecule tests at all.
     * Both say how to test every role of the scope [scopeLabel] instead.
     */
    @Nls
    private fun nothingSelected(nodes: List<AnsibleTreeNode>, scopeLabel: String): String {
        val outside = nodes.count { it is RoleNameNode && MoleculeTargets.hasTests(it) }
        return if (outside > 0) message("molecule.action.none.outside", scopeLabel, outside) else message("molecule.action.none.selection", scopeLabel)
    }
}

/** The Ansibility tool window panel the event comes from, or null (the Project view, the editor). */
private fun panelOf(e: AnActionEvent): AnsibleToolWindowPanel? =
    e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT)?.let { UseAsAnsibleContextAction.toolWindowPanelOf(it) }

/**
 * Runs [block] on the EDT with the current workspace scope. When [needsRoots] and the scope's roots are not known yet
 * (a named scope the workspace scope service is still computing), they are worked out in a background read action
 * first: that walks the roots' files, never on the EDT (R19/D143). Otherwise [block] runs at once.
 */
private fun withScope(project: Project, needsRoots: Boolean, block: (WorkspaceScope) -> Unit) {
    val scope = WorkspaceScopeService.getInstance(project).current()
    if (!needsRoots || scope.rootsKnown()) return block(scope)
    AnsibleToolWindowScope.getInstance(project).scope.launch(Dispatchers.Default) {
        readAction { scope.roots }
        withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) {
            if (!project.isDisposed) block(scope)
        }
    }
}
