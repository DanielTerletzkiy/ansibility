package de.terletzkiy.ansibility.context.switching

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.util.NlsActions
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext
import org.jetbrains.annotations.Nls

/**
 * "Ansibility: Switch Context…" (plan amendment R7/R8, F8.1; reachable from Find Action and Search Everywhere, no
 * default shortcut). A popup of every root with an inventory, the selected editor's root first and then the roots of
 * R9's workspace scope, listing `falcon › All environments`, `falcon › prod` and root-qualified hosts
 * (`falcon › prod › prod-prod1`) with speed search: type a host name and press Enter to set its environment and host
 * together (the play is kept while it still hits the host, else Auto). Right arrow on an entry opens its plays
 * (Auto, then the plays that hit it).
 */
class SwitchContextAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null &&
            AnsibleWorkspace.getInstance(project).roots().any { !it.detached && it.environmentsDir != null }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        createPopup(project, e.getData(CommonDataKeys.VIRTUAL_FILE), e.dataContext).showInBestPositionFor(e.dataContext)
    }

    companion object {
        private const val MAX_ROWS = 20

        /** The Switch Context popup, with the current choice preselected. */
        fun createPopup(project: Project, file: VirtualFile?, dataContext: DataContext): ListPopup =
            JBPopupFactory.getInstance().createActionGroupPopup(
                ContextTexts.message("switch.title"),
                SwitchContextGroup(file),
                dataContext,
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                true,
                null,
                MAX_ROWS,
                { action -> action is TargetChoiceGroup && action.isCurrent },
                ActionPlaces.POPUP,
            )
    }
}

/** The entries of the Switch Context popup for the roots [ContextChoices.switchableRoots] orders around [file]'s root. */
class SwitchContextGroup(private val file: VirtualFile?) : ActionGroup(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project ?: return EMPTY_ARRAY
        if (DumbService.isDumb(project)) return arrayOf(InfoAction(ContextTexts.message("popup.indexing")))
        val choices = ContextChoices(project)
        val preferred = readLocked { file?.let { AnsibleWorkspace.getInstance(project).rootFor(it) } }
        val roots = choices.switchableRoots(preferred)
        if (roots.isEmpty()) return arrayOf(InfoAction(ContextTexts.message("switch.no.roots")))
        val children = ArrayList<AnAction>()
        for (root in roots) {
            children += Separator.create(root.displayName)
            children += entries(project, choices, root)
        }
        return children.toTypedArray()
    }

    private fun entries(project: Project, choices: ContextChoices, root: AnsibleRoot): List<AnAction> {
        val selection = AnsibleContextService.getInstance(project).selection(root)
        val environments = choices.environments(root)
        val entries = ArrayList<AnAction>()
        entries += TargetChoiceGroup(
            ContextTarget(root, null),
            ContextTexts.message("switch.environment", root.displayName, ContextTexts.message("selection.all.environments")),
            ContextTexts.hostCount(environments.sumOf { it.hostCount }),
            selection,
        )
        for (environment in environments) {
            entries += TargetChoiceGroup(
                ContextTarget(root, environment.name),
                ContextTexts.message("switch.environment", root.displayName, environment.name),
                ContextTexts.hostCount(environment.hostCount),
                selection,
            )
        }
        for (host in choices.hosts(root, null)) {
            entries += TargetChoiceGroup(
                ContextTarget(root, host.environment, host.host),
                ContextTexts.message("switch.host", root.displayName, host.environment, host.host),
                hostBadge(host),
                selection,
            )
        }
        return entries
    }
}

/**
 * One entry of Switch Context: performing it makes [target] the context of its root; as a submenu it lists Auto and
 * the plays that hit the target, each choosing env, host and play together.
 */
class TargetChoiceGroup(
    val target: ContextTarget,
    @NlsActions.ActionText text: String,
    @Nls private val secondary: String?,
    private val selection: RootContext,
) : ActionGroup(), DumbAware, Toggleable {
    init {
        plainText(text)
        isPopup = true
    }

    /** Whether [target] is its root's current selection. */
    val isCurrent: Boolean get() = target.isSelected(selection)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isPerformGroup = true
        e.presentation.isPopupGroup = true
        // The plays are listed only when the submenu opens; an entry is never empty (Auto is always there).
        e.presentation.isDisableGroupIfEmpty = false
        e.presentation.isHideGroupIfEmpty = false
        e.presentation.putClientProperty(ActionUtil.SECONDARY_TEXT, secondary)
        Toggleable.setSelected(e.presentation, isCurrent)
    }

    override fun actionPerformed(e: AnActionEvent) {
        ContextSwitcher.use(e.project ?: return, target)
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project ?: return EMPTY_ARRAY
        val base = RootContext(target.environment?.let { EnvironmentChoice.Named(it) } ?: EnvironmentChoice.All, target.host)
        return playChoices(project, target.root, base.copy(play = selection.play), markCurrent = isCurrent) { project, key ->
            ContextSwitcher.select(project, target.root, base.copy(play = key))
        }.toTypedArray()
    }
}
