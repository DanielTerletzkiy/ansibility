package de.terletzkiy.ansibility.workspace.crossroot

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiFile
import com.intellij.ui.content.ContentFactory
import de.terletzkiy.ansibility.context.MoleculeVisibility
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
        open(project, name, file.originalFile.viewProvider.virtualFile)
    }

    companion object {
        fun nameAt(file: PsiFile, offset: Int): String? = VarUsageSearch.symbolAt(file, offset)?.name

        /**
         * Shows the report tab for [name] as a request started in [origin] sees it, reusing an open one. A request from a
         * Molecule file lists Molecule definitions whatever the setting (plan amendment R20, D154), so it gets a tab of
         * its own.
         */
        fun open(project: Project, name: String, origin: VirtualFile? = null) {
            val window = ToolWindowManager.getInstance(project).getToolWindow(AnsibleToolWindowFactory.ID) ?: return
            val molecule = origin != null && MoleculeVisibility.isMoleculeFile(project, origin)
            window.activate {
                val manager = window.contentManager
                val title = if (molecule) message("allrepos.tab.molecule", name) else message("allrepos.tab", name)
                manager.contents.firstOrNull { it.displayName == title && it.component is VarInAllReposPanel }?.let {
                    manager.setSelectedContent(it)
                    (it.component as VarInAllReposPanel).refresh()
                    return@activate
                }
                val panel = VarInAllReposPanel(project, name, origin.takeIf { molecule })
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
