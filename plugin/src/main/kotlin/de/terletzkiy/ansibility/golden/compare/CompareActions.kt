package de.terletzkiy.ansibility.golden.compare

import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import de.terletzkiy.ansibility.golden.AnsibilityGoldenBundle.message
import de.terletzkiy.ansibility.golden.GoldenTarget
import de.terletzkiy.ansibility.golden.GoldenTargets
import de.terletzkiy.ansibility.model.role.RoleCatalog
import de.terletzkiy.ansibility.toolwindow.AnsibleToolWindowFactory
import org.jetbrains.annotations.Nls

/**
 * The texts of the golden actions by place (DEV.md, Branding): the short text inside menus (context menus, the main
 * menu), "Ansibility: …" elsewhere (Find Action, Keymap, toolbars), like `VaultActionTexts`.
 */
internal object GoldenActionTexts {
    fun inMenu(e: AnActionEvent): Boolean = e.isFromContextMenu || e.isFromMainMenu

    @Nls
    fun forPlace(e: AnActionEvent, @Nls menuText: String): String = if (inMenu(e)) menuText else message("action.prefixed", menuText)

    /**
     * The short menu text of a golden action from its "Ansibility: …" template text ([forPlace]'s two forms): what the
     * tool window's details buttons show, so a button and the menu item it stands for have one name.
     */
    @Nls
    fun menuText(@Nls templateText: String): String = templateText.removePrefix(message("action.prefixed", ""))
}

/**
 * The "Ansibility Golden" submenu of the editor and Project-view popups (`Ansibility.Golden.Menu`, plan amendment R24
 * review fix U10): the golden actions in one place, like R19's "Ansibility Vault" submenu. Hidden outside role copies
 * and whenever none of its actions applies.
 */
class GoldenActionGroup : DefaultActionGroup(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isVisible = project != null && !project.isDisposed && GoldenTargets.of(project, e.dataContext) != null
        e.presentation.isHideGroupIfEmpty = true
    }
}

/**
 * "Compare with Golden" (plan amendment R24, D182): the role copy, a role file (also one that exists only in golden),
 * the editor's file or the Project view's selection against the golden copy of the same role. Hidden when the
 * selection is not inside a role copy, when the role has no golden copy (no golden root, or golden lacks the role)
 * and on the golden copy itself.
 */
open class CompareWithGoldenAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val target = targetOf(e)
        e.presentation.isEnabledAndVisible = target != null
        if (target != null) e.presentation.text = GoldenActionTexts.forPlace(e, message("action.compare.golden.menu"))
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = targetOf(e) ?: return
        RoleCompare.getInstance(project).compareWithGolden(target)
    }

    /** The selection's target when it has a golden copy other than itself. */
    protected open fun targetOf(e: AnActionEvent): GoldenTarget? = goldenTargetOf(e.project, e)

    companion object {
        /** [GoldenTargets.of] when the role has a golden copy and the target is not that copy. */
        fun goldenTargetOf(project: Project?, e: AnActionEvent): GoldenTarget? {
            if (project == null || project.isDisposed) return null
            val target = GoldenTargets.of(project, e.dataContext) ?: return null
            val golden = RoleCatalog.getInstance(project).snapshot().reference(target.copy.name) ?: return null
            return target.takeIf { golden.dir != it.copy.dir }
        }
    }
}

/**
 * Your Compare shortcut (`Diff.ShowDiff`, Cmd+D / Ctrl+D) for "Compare with Golden" inside the Ansibility tool window
 * only (registered with `use-shortcut-of`, so it follows the keymap; R9's F9.6 `copyShortcutFrom`). It is in no menu,
 * and it is off everywhere else, where the same keys duplicate a line or compare files.
 */
class CompareWithGoldenShortcutAction : CompareWithGoldenAction() {
    override fun targetOf(e: AnActionEvent): GoldenTarget? {
        if (e.place == ActionPlaces.ACTION_SEARCH) return null
        if (e.getData(PlatformDataKeys.TOOL_WINDOW)?.id != AnsibleToolWindowFactory.ID) return null
        return super.targetOf(e)
    }
}

/**
 * "Compare with…" (plan amendment R24, D182): a popup of the other copies of the same role with their tier, in-scope
 * first (D44), then the same chain between the pick (left) and this copy (right). Shown when the role has another copy.
 */
class CompareWithAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val target = targetOf(e)
        e.presentation.isEnabledAndVisible = target != null
        if (target != null) e.presentation.text = GoldenActionTexts.forPlace(e, message("action.compare.with.menu"))
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = targetOf(e) ?: return
        RoleCompare.getInstance(project).compareWith(target, e.dataContext)
    }

    private fun targetOf(e: AnActionEvent): GoldenTarget? {
        val project = e.project?.takeIf { !it.isDisposed } ?: return null
        val target = GoldenTargets.of(project, e.dataContext) ?: return null
        return target.takeIf { RoleCatalog.getInstance(project).snapshot().copies(it.copy.name).size > 1 }
    }
}
