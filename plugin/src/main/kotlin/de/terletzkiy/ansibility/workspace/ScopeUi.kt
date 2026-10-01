package de.terletzkiy.ansibility.workspace

import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.projectView.impl.AbstractProjectViewPane
import com.intellij.ide.projectView.impl.ProjectViewPane
import com.intellij.ide.scopeView.ScopeViewPane
import com.intellij.ide.util.scopeChooser.EditScopesDialog
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiManager

/** Where "Open in Project view" goes for the current scope. */
sealed interface ProjectViewTarget {
    /** The Project view's Scope pane on the named scope [presentableName] (its sub-ids are matched by name). */
    data class ScopePane(val scopeId: String, val presentableName: String) : ProjectViewTarget

    /** The Project pane with [dir] (the one chosen root, or the current file's root) selected. */
    data class Directory(val dir: VirtualFile) : ProjectViewTarget
}

/**
 * The modal and cross-window UI of the scope selector, behind one application service so the actions stay testable
 * (tests replace it with a recording fake). Every method runs on the EDT.
 */
interface ScopeUi {
    /** Shows "Choose roots…" over [options] with [initial] checked; the chosen keys, or null when cancelled. */
    fun chooseRoots(project: Project, options: List<RootOption>, initial: Set<String>): Set<String>?

    /** Opens the platform's Scopes settings ([EditScopesDialog]), selecting the user scope [scopeName] when given. */
    fun editScopes(project: Project, scopeName: String?)

    /** Activates the Project view and shows [target] there. */
    fun openInProjectView(project: Project, target: ProjectViewTarget)

    companion object {
        fun getInstance(): ScopeUi = service()
    }
}

/**
 * The platform implementation of [ScopeUi]: [ChooseRootsDialog], [EditScopesDialog.showDialog], and the Project view
 * through `ProjectView.changeView(ScopeViewPane.ID, subId)` / `ProjectView.select`.
 *
 * The Scope pane's sub-ids are the platform's own filter keys, not scope ids, and the pane's filter API
 * (`ScopeViewPane.getFilters`, `getCurrentFilter`, `select`) is Internal; so the sub-id is found through the public
 * `AbstractProjectViewPane.getSubIds()` and `getPresentableSubIdName(subId)`, by the scope's presentable name.
 */
class DefaultScopeUi : ScopeUi {
    override fun chooseRoots(project: Project, options: List<RootOption>, initial: Set<String>): Set<String>? {
        val dialog = ChooseRootsDialog(project, options, initial)
        return if (dialog.showAndGet()) dialog.selection() else null
    }

    override fun editScopes(project: Project, scopeName: String?) {
        EditScopesDialog.showDialog(project, scopeName)
    }

    override fun openInProjectView(project: Project, target: ProjectViewTarget) {
        val window = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.PROJECT_VIEW) ?: return
        window.activate {
            if (project.isDisposed) return@activate
            val view = ProjectView.getInstance(project)
            when (target) {
                is ProjectViewTarget.ScopePane -> view.changeView(ScopeViewPane.ID, scopePaneSubId(view, target.presentableName))
                is ProjectViewTarget.Directory -> view.changeViewCB(ProjectViewPane.ID, null).doWhenDone {
                    if (!target.dir.isValid || project.isDisposed) return@doWhenDone
                    val element = runReadActionBlocking { PsiManager.getInstance(project).findDirectory(target.dir) }
                    view.select(element, target.dir, true)
                }
            }
        }
    }

    companion object {
        /** The Scope pane's sub-id whose presentable name is [presentableName], or null (the pane then keeps its scope). */
        fun scopePaneSubId(view: ProjectView, presentableName: String): String? =
            view.getProjectViewPaneById(ScopeViewPane.ID)?.let { subIdOf(it, presentableName) }

        /** The sub-id of [pane] presented as [presentableName], or null. */
        fun subIdOf(pane: AbstractProjectViewPane, presentableName: String): String? =
            pane.subIds.firstOrNull { pane.getPresentableSubIdName(it) == presentableName }
    }
}
