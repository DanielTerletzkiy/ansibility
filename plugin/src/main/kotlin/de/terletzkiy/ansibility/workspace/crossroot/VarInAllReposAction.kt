package de.terletzkiy.ansibility.workspace.crossroot

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiFile
import com.intellij.ui.content.ContentFactory
import de.terletzkiy.ansibility.toolwindow.AnsibleToolWindowFactory
import de.terletzkiy.ansibility.vars.usages.VarUsageSearch
import de.terletzkiy.ansibility.workspace.AnsibilityScopeBundle.message

/**
 * "Ansibility: This Variable in All Repos" (plan amendment R9, F9.8) on a variable usage or key: opens (or reuses)
 * the variable's closable report tab in the Ansibility tool window. Disabled while indexing.
 */
class VarInAllReposAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.PSI_FILE)
        val editor = e.getData(CommonDataKeys.EDITOR)
        if (project == null || file == null || editor == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        if (DumbService.isDumb(project)) {
            e.presentation.isVisible = true
            e.presentation.isEnabled = false
            e.presentation.description = message("allrepos.dumb")
            return
        }
        e.presentation.isEnabledAndVisible = nameAt(file, editor.caretModel.offset) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val name = nameAt(file, editor.caretModel.offset) ?: return
        open(project, name)
    }

    companion object {
        fun nameAt(file: PsiFile, offset: Int): String? = VarUsageSearch.symbolAt(file, offset)?.name

        /** Shows the report tab for [name], reusing an open one. */
        fun open(project: Project, name: String) {
            val window = ToolWindowManager.getInstance(project).getToolWindow(AnsibleToolWindowFactory.ID) ?: return
            window.activate {
                val manager = window.contentManager
                val title = message("allrepos.tab", name)
                manager.contents.firstOrNull { it.displayName == title && it.component is VarInAllReposPanel }?.let {
                    manager.setSelectedContent(it)
                    (it.component as VarInAllReposPanel).refresh()
                    return@activate
                }
                val panel = VarInAllReposPanel(project, name)
                val content = ContentFactory.getInstance().createContent(panel, title, false)
                content.isCloseable = true
                content.setDisposer(panel)
                content.preferredFocusableComponent = panel.tree
                manager.addContent(content)
                manager.setSelectedContent(content)
            }
        }
    }
}
