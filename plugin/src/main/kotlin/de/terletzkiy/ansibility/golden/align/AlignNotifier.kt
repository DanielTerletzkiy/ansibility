package de.terletzkiy.ansibility.golden.align

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.annotations.RequiresEdt
import de.terletzkiy.ansibility.golden.AnsibilityGoldenBundle.message
import de.terletzkiy.ansibility.golden.GoldenDataKeys
import de.terletzkiy.ansibility.golden.GoldenNotifications
import de.terletzkiy.ansibility.model.role.RoleCopy
import de.terletzkiy.ansibility.run.molecule.MoleculeBatchLauncher
import de.terletzkiy.ansibility.run.molecule.MoleculeScenarios
import de.terletzkiy.ansibility.run.molecule.MoleculeSpec
import de.terletzkiy.ansibility.run.molecule.MoleculeTarget
import de.terletzkiy.ansibility.toolwindow.model.GoldenActionIds
import org.jetbrains.annotations.Nls

/**
 * The end-of-align notification (plan amendment R24, D187): "Aligned web in golden: 3 merged, 2 taken from falcon,
 * 1 kept", in the "Ansibility" balloon group, with the follow-ups that apply:
 *
 * - **Push to Repos…** runs `Ansibility.Golden.PushToRepos` (D188) for the target copy, while that action is registered;
 * - **Run Molecule Tests** runs the target copy's scenarios, when it has some and the Molecule run setting is on;
 * - **Show Local History** opens the platform's Local History of the target role directory (the revert point is the
 *   label "Before aligning …").
 */
object AlignNotifier {
    /** The group of Align's notifications ([GoldenNotifications.GROUP_ID]). */
    const val GROUP_ID: String = GoldenNotifications.GROUP_ID

    /** The platform's Show History action (`intellij.platform.lvcs.xml`): Local History of the selected file or directory. */
    const val SHOW_HISTORY_ACTION: String = "LocalHistory.ShowHistory"

    /** Where the follow-up actions say they come from. */
    const val PLACE: String = "AnsibilityAlignNotification"

    /**
     * Shows the summary of [session]. A window that merged and took nothing (cancelled, or only "kept") says "Nothing
     * aligned in web (golden)" and offers no follow-up: there is nothing to push, test or revert (U8). EDT.
     */
    @RequiresEdt
    fun summary(project: Project, session: AlignSession): Notification {
        val target = session.target
        val outcome = session.outcome
        val aligned = outcome.merged + outcome.taken > 0
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
            .createNotification(if (aligned) session.texts.summary(outcome) else session.texts.nothingAligned, NotificationType.INFORMATION)
            .setDisplayId("ansibility.golden.align")
        for ((text, run) in if (aligned) followUps(project, target) else emptyList()) {
            notification.addAction(NotificationAction.create(text) { _, clicked ->
                clicked.hideBalloon()
                run()
            })
        }
        notification.notify(project)
        return notification
    }

    /** The follow-up buttons that apply to [target] now, with what they do. */
    internal fun followUps(project: Project, target: RoleCopy): List<Pair<@Nls String, () -> Unit>> = buildList {
        val actions = ActionManager.getInstance()
        if (actions.getAction(GoldenActionIds.PUSH_TO_REPOS) != null) {
            add(message("align.notification.push") to { runAction(project, GoldenActionIds.PUSH_TO_REPOS, pushContext(project, target.dir)) })
        }
        if (MoleculeScenarios.runsTests(project) && MoleculeScenarios.hasScenarios(target.dir)) {
            add(message("align.notification.molecule") to { runMolecule(project, target) })
        }
        if (actions.getAction(SHOW_HISTORY_ACTION) != null) {
            add(message("align.notification.history") to { runAction(project, SHOW_HISTORY_ACTION, historyContext(project, target.dir)) })
        }
    }

    /** Push's data context: the aligned copy as `GoldenDataKeys.ROLE_COPY`, as the Roles tab gives it. */
    internal fun pushContext(project: Project, dir: VirtualFile): DataContext =
        SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(GoldenDataKeys.ROLE_COPY, dir).build()

    /** Local History's data context: the role directory as the selected file. */
    internal fun historyContext(project: Project, dir: VirtualFile): DataContext = SimpleDataContext.builder()
        .add(CommonDataKeys.PROJECT, project)
        .add(CommonDataKeys.VIRTUAL_FILE, dir)
        .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(dir))
        .build()

    private fun runMolecule(project: Project, target: RoleCopy) {
        if (!target.dir.isValid) return
        MoleculeBatchLauncher.run(project, listOf(MoleculeTarget(MoleculeSpec(target.dir.path), target.name, target.root.displayName)))
    }

    /** Runs the registered action [id] with [context] when it is enabled there. EDT. */
    private fun runAction(project: Project, id: String, context: DataContext) {
        if (project.isDisposed) return
        val action = ActionManager.getInstance().getAction(id) ?: return
        val event = AnActionEvent.createEvent(action, context, action.templatePresentation.clone(), PLACE, ActionUiKind.NONE, null)
        ActionUtil.updateAction(action, event)
        if (event.presentation.isEnabled) ActionUtil.performAction(action, event)
    }
}
