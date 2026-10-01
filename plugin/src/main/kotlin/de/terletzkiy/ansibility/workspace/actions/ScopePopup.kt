package de.terletzkiy.ansibility.workspace.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import de.terletzkiy.ansibility.api.ScopeChoice
import de.terletzkiy.ansibility.workspace.AnsibilityScopeBundle
import de.terletzkiy.ansibility.workspace.ScopeEntry
import de.terletzkiy.ansibility.workspace.ScopeOrigin
import de.terletzkiy.ansibility.workspace.WorkspaceScopeServiceImpl
import org.jetbrains.annotations.Nls

/**
 * The scope selector's popup (plan amendment R9, F9.1 "Shows"), shared by the toolbar combo and "Switch Scope…":
 *
 * ```
 * ● All roots                         11 roots
 * ○ Current file's root               falcon (follows the editor)
 * ── Project scopes ──
 * ○ falcon                               falcon
 * ○ pelican                               pelican + pelican › danger_zone/database
 * ○ hawk                               matches no Ansible root
 * ── Other scopes ──
 * ○ Project Files · Open Files …
 * ──
 * ○ Choose roots…
 *   Not covered by a named scope: thrush, golden
 * ──
 *   Edit Scopes… · Open in Project View
 * ```
 *
 * Built on the EDT from [WorkspaceScopeServiceImpl.catalog], which never walks: a coverage still being computed
 * reads "…". Every choice is a [DumbAwareToggleAction] (BGT update) whose secondary text is its coverage.
 */
object ScopePopup {
    /** The popup's actions for [project]; empty when the workspace scope service is not the plugin's. */
    fun group(project: Project): DefaultActionGroup {
        val group = DefaultActionGroup()
        val service = WorkspaceScopeServiceImpl.getInstance(project) ?: return group
        val current = service.currentScope()
        val catalog = service.catalog()

        group.add(
            ChoiceAction(
                ScopeChoice.AllRoots,
                AnsibilityScopeBundle.message("scope.label.all"),
                AnsibilityScopeBundle.message("popup.all.secondary", catalog.roots.size),
            ),
        )
        val editorRoot = service.editorRoot()
        group.add(
            ChoiceAction(
                ScopeChoice.CurrentFileRoot,
                AnsibilityScopeBundle.message("scope.label.current"),
                editorRoot?.let { AnsibilityScopeBundle.message("popup.current.secondary", it.displayName) }
                    ?: AnsibilityScopeBundle.message("popup.current.secondary.none"),
            ),
        )

        val deleted = (current.choice as? ScopeChoice.Named)?.takeIf { current.namedScope == null }
        if (catalog.userScopes.isNotEmpty() || deleted != null) {
            group.addSeparator(AnsibilityScopeBundle.message("popup.group.project"))
            for (entry in catalog.userScopes) {
                group.add(ChoiceAction(ScopeChoice.Named(entry.scopeId), entryText(entry), ScopeCommands.coverageText(entry.coverage)))
            }
            if (deleted != null) {
                group.add(
                    ChoiceAction(
                        deleted,
                        AnsibilityScopeBundle.message("scope.label.deleted", deleted.scopeId),
                        AnsibilityScopeBundle.message("popup.deleted.secondary"),
                    ),
                )
            }
        }
        if (catalog.predefinedScopes.isNotEmpty()) {
            group.addSeparator(AnsibilityScopeBundle.message("popup.group.other"))
            for (entry in catalog.predefinedScopes) {
                group.add(ChoiceAction(ScopeChoice.Named(entry.scopeId), entryText(entry), ScopeCommands.coverageText(entry.coverage)))
            }
        }

        group.addSeparator()
        val chosenLabel = current.label.takeIf { current.choice is ScopeChoice.Roots }
        group.add(ChooseRootsAction(project, chosenLabel))
        val uncovered = catalog.uncovered
        if (uncovered != null && uncovered.isNotEmpty() && catalog.userScopes.isNotEmpty()) {
            group.add(InfoAction(AnsibilityScopeBundle.message("popup.uncovered", uncovered.joinToString(", ") { it.displayName })))
        }

        group.addSeparator()
        group.add(CommandAction(AnsibilityScopeBundle.message("popup.editScopes"), enabled = { true }) { ScopeCommands.editScopes(project) })
        group.add(
            CommandAction(AnsibilityScopeBundle.message("popup.openInProjectView"), enabled = { ScopeCommands.projectViewTarget(project) != null }) {
                ScopeCommands.openInProjectView(project)
            },
        )
        return group
    }

    @Nls
    private fun entryText(entry: ScopeEntry): String =
        if (entry.origin == ScopeOrigin.LOCAL) AnsibilityScopeBundle.message("popup.scope.local", entry.scope.presentableName) else entry.scope.presentableName

    /** One choice of the selector: selected when it is the stored choice; choosing it stores it. */
    class ChoiceAction(val choice: ScopeChoice, @Nls text: String, @Nls val secondaryText: String?) : DumbAwareToggleAction() {
        init {
            plainText(text)
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun isSelected(e: AnActionEvent): Boolean {
            val project = e.project ?: return false
            return WorkspaceScopeServiceImpl.getInstance(project)?.choice() == choice
        }

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (!state) return
            val project = e.project ?: return
            WorkspaceScopeServiceImpl.getInstance(project)?.set(choice)
        }

        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.putClientProperty(ActionUtil.SECONDARY_TEXT, secondaryText)
        }
    }

    /** "Choose roots…": selected while chosen roots are the scope; always opens the dialog. */
    class ChooseRootsAction(private val project: Project, @Nls private val chosenLabel: String?) : DumbAwareToggleAction() {
        init {
            plainText(AnsibilityScopeBundle.message("popup.chooseRoots"))
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun isSelected(e: AnActionEvent): Boolean = WorkspaceScopeServiceImpl.getInstance(project)?.choice() is ScopeChoice.Roots

        override fun setSelected(e: AnActionEvent, state: Boolean) = ScopeCommands.chooseRoots(project)

        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.putClientProperty(ActionUtil.SECONDARY_TEXT, chosenLabel)
        }
    }

    /** A disabled line of information (the "not covered by a named scope" footer). */
    class InfoAction(@Nls text: String) : DumbAwareAction() {
        init {
            plainText(text)
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = false
        }

        override fun actionPerformed(e: AnActionEvent) = Unit
    }

    /** A plain command of the popup ("Edit Scopes…", "Open in Project View"). */
    class CommandAction(@Nls text: String, private val enabled: () -> Boolean, private val run: () -> Unit) : DumbAwareAction() {
        init {
            plainText(text)
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = enabled()
        }

        override fun actionPerformed(e: AnActionEvent) = run()
    }
}

/** Sets [text] as the action's text without mnemonic parsing: scope and root names may contain `_` or `&`. */
private fun AnAction.plainText(@Nls text: String) {
    templatePresentation.setText(text, false)
}
