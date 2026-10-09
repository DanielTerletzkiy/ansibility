package de.terletzkiy.ansibility.golden.compare

import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.DiffRequestChain
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.util.concurrency.annotations.RequiresEdt
import de.terletzkiy.ansibility.golden.AnsibilityGoldenBundle.message
import de.terletzkiy.ansibility.model.role.RoleCopy
import org.jetbrains.annotations.Nls

/** A copy offered by "Compare with…" (plan amendment R24, D182): its tier text and whether it is in the workspace scope. */
class CompareChoice(
    val copy: RoleCopy,
    /** The drift badge of the copy ("Δ tasks/templates", "reference"), or null when unknown. */
    @get:Nls val tier: String?,
    /** Inside the workspace scope; in-scope copies come first (D44: ranked, never filtered). */
    val inScope: Boolean,
) {
    /** The root's display name ("falcon"). */
    val name: String get() = copy.root.displayName

    /** The popup text: "falcon · Δ tasks/templates". */
    @get:Nls
    val text: String get() = tier?.let { message("compare.choice.text", name, it) } ?: name

    override fun toString(): String = "CompareChoice($name, $tier, inScope=$inScope)"
}

/**
 * The UI of the golden compare (plan amendment R24, D182): where a diff chain is shown, how a copy is chosen and how a
 * short notice is shown. An application service, so tests replace it (`ServiceContainerUtil.replaceService`) and
 * capture the chain instead of opening a diff.
 */
interface RoleDiffUi {
    /** Shows [chain] (the default opens an editor tab: `DiffManager.showDiff(project, chain, DiffDialogHints.DEFAULT)`). */
    @RequiresEdt
    fun show(project: Project, chain: DiffRequestChain)

    /**
     * Lets the user pick one of [choices] (in-scope first, then the rest under an "Outside scope" separator); calls
     * [chosen] on the EDT with the pick. [context] places the popup (null: centred in the project's window).
     */
    @RequiresEdt
    fun chooseCopy(project: Project, @Nls title: String, choices: List<CompareChoice>, context: DataContext?, chosen: (CompareChoice) -> Unit)

    /** A short notice ("web in falcon is the same as in golden"). */
    @RequiresEdt
    fun inform(project: Project, @Nls message: String)

    companion object {
        fun getInstance(): RoleDiffUi = service()
    }
}

/** The production [RoleDiffUi]: the platform's diff viewer, a list popup and a balloon of the "Ansibility" group. */
class DefaultRoleDiffUi : RoleDiffUi {
    override fun show(project: Project, chain: DiffRequestChain) {
        DiffManager.getInstance().showDiff(project, chain, DiffDialogHints.DEFAULT)
    }

    override fun chooseCopy(project: Project, title: String, choices: List<CompareChoice>, context: DataContext?, chosen: (CompareChoice) -> Unit) {
        val firstOutside = choices.firstOrNull { !it.inScope }?.takeIf { choices.any(CompareChoice::inScope) }
        val step = object : BaseListPopupStep<CompareChoice>(title, choices) {
            override fun getTextFor(value: CompareChoice): String = value.text

            override fun getSeparatorAbove(value: CompareChoice): ListSeparator? =
                if (value === firstOutside) ListSeparator(message("compare.choose.outsideScope")) else null

            override fun isSpeedSearchEnabled(): Boolean = true

            override fun onChosen(selectedValue: CompareChoice, finalChoice: Boolean): PopupStep<*>? = doFinalStep { chosen(selectedValue) }
        }
        val popup = JBPopupFactory.getInstance().createListPopup(step)
        // The choices are computed in the background: place the popup at the invoking component only while it is still shown.
        val anchor = context?.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT)
        if (context != null && anchor != null && anchor.isShowing) popup.showInBestPositionFor(context) else popup.showCenteredInCurrentWindow(project)
    }

    override fun inform(project: Project, message: String) {
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    }

    private companion object {
        /** The general "Ansibility" balloon group (`ansibility-core.xml`). */
        const val NOTIFICATION_GROUP = "Ansibility"
    }
}
