package de.terletzkiy.ansibility.context.switching

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsActions
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.ContextWidgetSegment
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.api.WidgetSegment
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext
import org.jetbrains.annotations.Nls

// The action groups of the context popups (plan amendment R7/R8, F8.1). They are plain actions so the status-bar
// widget, the tool-window context button and Switch Context share them, keyboard navigation and speed search come
// from the platform's action popups, and tests drive them through `getChildren` and `actionPerformed`. Groups compute
// their children on the background update thread (in a read action) from the host-context model; performing a choice
// runs on the EDT and goes through [ContextSwitcher].

/** The id of the registered Switch Context action (`ansibility-host.xml`). */
const val SWITCH_CONTEXT_ACTION_ID: String = "Ansibility.SwitchContext"

/**
 * The context popup of one root (the status-bar widget, the tool-window button): the Environment, Host and Play
 * submenus, the stored selection's problem, the Follow-editor toggle, each widget segment's popup actions under the
 * segment's text, then [extra] actions and Switch Context. [segments] null computes them for [file]. [only], when it
 * gives hosts, narrows the Environment and Host submenus to them (the template preview offers the hosts that render it).
 */
class ContextPopupGroup(
    private val root: AnsibleRoot,
    private val file: VirtualFile?,
    private val segments: List<WidgetSegment>? = null,
    private val extra: List<AnAction> = emptyList(),
    private val only: ((Project) -> Collection<HostKey>?)? = null,
) : ActionGroup(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project ?: return EMPTY_ARRAY
        val children = ArrayList<AnAction>()
        val hosts = only?.takeUnless { DumbService.isDumb(project) }?.let { readLocked { it(project) } }
        children += selectionActions(project, root, hosts)
        children += Separator.create()
        children += FollowEditorAction()
        val shown = segments ?: file?.let { file -> readLocked { ContextWidgetSegment.segments(project, file) } }.orEmpty()
        for (segment in shown) {
            if (segment.popupActions.isEmpty()) continue
            children += Separator.create(segment.text)
            children += segment.popupActions
        }
        children += Separator.create()
        children += extra
        ActionManager.getInstance().getAction(SWITCH_CONTEXT_ACTION_ID)?.let { children += it }
        return children.toTypedArray()
    }

    companion object {
        /**
         * The Environment, Host and Play submenus of [root] and the stored selection's problem, or one info line when
         * the root has no inventory or the IDE is indexing. [only] narrows the Environment and Host submenus.
         */
        fun selectionActions(project: Project, root: AnsibleRoot, only: Collection<HostKey>? = null): List<AnAction> {
            if (DumbService.isDumb(project)) return listOf(InfoAction(ContextTexts.message("popup.indexing")))
            val choices = ContextChoices(project)
            if (choices.environments(root).isEmpty()) {
                return listOf(InfoAction(ContextTexts.message("popup.no.inventory", root.displayName)))
            }
            val selection = AnsibleContextService.getInstance(project).selection(root)
            val environment = (selection.environment as? EnvironmentChoice.Named)?.name
            val play = selection.play?.let { choices.playLabel(root, it) ?: it } ?: ContextTexts.message("popup.play.auto")
            val actions = mutableListOf<AnAction>(
                EnvironmentGroup(root, ContextTexts.message("popup.environment", environment ?: ContextTexts.message("selection.all.environments")), only),
                HostGroup(root, ContextTexts.message("popup.host", selection.host ?: ContextTexts.message("popup.host.all")), only),
                PlayGroup(root, ContextTexts.message("popup.play", play)),
            )
            choices.problem(root)?.let { actions += InfoAction(ContextTexts.message("popup.problem", it)) }
            return actions
        }
    }
}

/**
 * The Environment submenu: All environments, then each environment with its host count. With [only], just the
 * environments holding one of those hosts, counting those hosts (every environment when none of them is in the inventory).
 */
class EnvironmentGroup(
    private val root: AnsibleRoot,
    @NlsActions.ActionText text: String,
    private val only: Collection<HostKey>? = null,
) : ActionGroup(), DumbAware {
    init {
        plainText(text)
        isPopup = true
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project ?: return EMPTY_ARRAY
        val choices = ContextChoices(project)
        val selection = AnsibleContextService.getInstance(project).selection(root)
        val selected = (selection.environment as? EnvironmentChoice.Named)?.name
        val known = choices.environments(root)
        val counts = only?.groupingBy { it.environment }?.eachCount()
        val environments = counts?.let { count -> known.mapNotNull { env -> count[env.name]?.let { env.copy(hostCount = it) } } }?.ifEmpty { null } ?: known
        val all = ChoiceAction(
            ContextTexts.message("selection.all.environments"),
            ContextTexts.hostCount(environments.sumOf { it.hostCount }),
            selected == null,
        ) { ContextSwitcher.selectEnvironment(it, root, null) }
        val named = environments.map { option ->
            ChoiceAction(option.name, ContextTexts.hostCount(option.hostCount), option.name == selected) {
                ContextSwitcher.selectEnvironment(it, root, option.name)
            }
        }
        return (listOf(all) + named).toTypedArray()
    }
}

/**
 * The Host submenu: All hosts, then the hosts of the selected environment (of every environment, qualified with it,
 * under All) with their address and the shared-address badge (`1 of 7 names on 192.0.2.43`). With [only], just those
 * hosts; when the selected environment holds none of them, those of every environment, qualified with theirs.
 */
class HostGroup(
    private val root: AnsibleRoot,
    @NlsActions.ActionText text: String,
    private val only: Collection<HostKey>? = null,
) : ActionGroup(), DumbAware {
    init {
        plainText(text)
        isPopup = true
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project ?: return EMPTY_ARRAY
        val selection = AnsibleContextService.getInstance(project).selection(root)
        val environment = (selection.environment as? EnvironmentChoice.Named)?.name
        val all = ChoiceAction(ContextTexts.message("popup.host.all"), null, selection.host == null) {
            ContextSwitcher.selectEnvironment(it, root, environment)
        }
        val hosts = offered(ContextChoices(project), environment).map { option ->
            val text = if (option.environment != environment) "${option.environment}${ContextTexts.CHAIN}${option.host}" else option.host
            ChoiceAction(text, hostBadge(option), option.environment == environment && option.host == selection.host) {
                ContextSwitcher.selectHost(it, root, option.environment, option.host)
            }
        }
        return (listOf(all) + hosts).toTypedArray()
    }

    private fun offered(choices: ContextChoices, environment: String?): List<HostOption> {
        val hosts = choices.hosts(root, environment)
        val wanted = only?.mapTo(HashSet()) { it.environment to it.host } ?: return hosts
        fun List<HostOption>.wanted() = filter { (it.environment to it.host) in wanted }
        return hosts.wanted().ifEmpty { null }
            ?: environment?.let { choices.hosts(root, null).wanted().ifEmpty { null } }
            ?: hosts
    }
}

/** The Play submenu: Auto, then the plays that hit the selected host (or environment, or root). */
class PlayGroup(private val root: AnsibleRoot, @NlsActions.ActionText text: String) : ActionGroup(), DumbAware {
    init {
        plainText(text)
        isPopup = true
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project ?: return EMPTY_ARRAY
        val selection = AnsibleContextService.getInstance(project).selection(root)
        return playChoices(project, root, selection, markCurrent = true) { project, key -> ContextSwitcher.selectPlay(project, root, key) }.toTypedArray()
    }
}

/**
 * Auto and the plays that hit [selection]'s host, environment or root, for the Play submenu and Switch Context, with
 * [selection]'s play checked when [markCurrent]; choosing one calls [choose] with the play key (null: Auto).
 */
internal fun playChoices(
    project: Project,
    root: AnsibleRoot,
    selection: RootContext,
    markCurrent: Boolean,
    choose: (Project, String?) -> Unit,
): List<AnAction> {
    val environment = (selection.environment as? EnvironmentChoice.Named)?.name
    val plays = ContextChoices(project).plays(root, environment, selection.host)
    val auto = ChoiceAction(ContextTexts.message("popup.play.auto"), ContextTexts.message("popup.play.auto.detail"), markCurrent && selection.play == null) {
        choose(it, null)
    }
    if (plays.isEmpty()) return listOf(auto, InfoAction(ContextTexts.message("popup.plays.none")))
    return listOf(auto) + plays.map { option ->
        ChoiceAction(option.label, null, markCurrent && option.key == selection.play) { choose(it, option.key) }
    }
}

/** The grey text of a host choice: its address, or `1 of 7 names on 192.0.2.43` when other names of the root share it. */
@Nls
internal fun hostBadge(option: HostOption): String? {
    val address = option.address ?: return null
    if (option.sharedBy <= 1) return address
    return ContextTexts.message("popup.address.shared", option.sharedBy, address)
}

/**
 * One choice of a context popup: [text] with an optional grey [secondary] text, checked when [selected]; performing it
 * calls [choose] on the EDT.
 */
class ChoiceAction(
    @NlsActions.ActionText text: String,
    @Nls private val secondary: String?,
    private val selected: Boolean,
    private val choose: (Project) -> Unit,
) : DumbAwareAction(), Toggleable {
    init {
        plainText(text)
    }

    /** Whether this choice is the current selection (the popup shows it checked). */
    val isCurrent: Boolean get() = selected

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.putClientProperty(ActionUtil.SECONDARY_TEXT, secondary)
        Toggleable.setSelected(e.presentation, selected)
    }

    override fun actionPerformed(e: AnActionEvent) {
        choose(e.project ?: return)
    }
}

/** A grey, non-selectable line of a context popup (the file scope, a problem, "no inventory"). */
class InfoAction(@NlsActions.ActionText text: String) : DumbAwareAction() {
    init {
        plainText(text)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = true
        e.presentation.isEnabled = false
    }

    override fun actionPerformed(e: AnActionEvent) = Unit
}

/**
 * D33 "Follow editor" (project-wide, on by default): a file always narrows the context to the hosts it applies to; with
 * Follow editor on, a file whose hosts the selection does not include uses its own hosts (the override banner says
 * so), off it is not loaded for the selection. The stored selections never change.
 */
class FollowEditorAction : DumbAwareToggleAction(
    ContextTexts.message("popup.follow.editor"),
    ContextTexts.message("popup.follow.editor.description"),
    null,
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean = e.project?.let(ContextSwitcher::followsEditor) ?: false

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        ContextSwitcher.setFollowEditor(e.project ?: return, state)
    }
}

/**
 * Sets [text] as this action's text without mnemonic parsing: names such as `host_vars`, `app_mono` or
 * `danger_zone` keep their underscores.
 */
internal fun AnAction.plainText(@NlsActions.ActionText text: String) {
    templatePresentation.setText(text, false)
}

internal fun <T> readLocked(action: () -> T): T =
    if (ApplicationManager.getApplication().isReadAccessAllowed) action() else runReadActionBlocking(action)
