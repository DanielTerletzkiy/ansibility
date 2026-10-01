package de.terletzkiy.ansibility.dispatch

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.datatransfer.StringSelection

/**
 * X75 "Ansibility: Show Ansible Context" (Tools menu and editor popup): a popup with the [AnsibleContextReport]
 * for the caret, i.e. file kind, root, role and entry point, layer and environment, molecule scenario, target
 * version, the classified site and why its variable does or does not resolve, with a button that copies it all.
 *
 * Enabled for files inside Ansible roots; in context menus it is hidden elsewhere. Not dumb-aware: the site
 * classifiers and the variable service use indexes.
 */
class ShowAnsibleContextAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val inRoot = project != null && !project.isDisposed && file != null && !file.isDirectory &&
            AnsibleWorkspace.getInstance(project).rootFor(file) != null
        e.presentation.isEnabled = inRoot
        e.presentation.isVisible = inRoot || !e.isFromContextMenu
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        AnsibleContextPopup.getInstance(project).show(file, editor, editor?.caretModel?.offset)
    }
}

/** Builds the X75 report off the EDT and shows it; a light project service so the work is cancelled with the project. */
@Service(Service.Level.PROJECT)
class AnsibleContextPopup(private val project: Project, private val scope: CoroutineScope) {

    /** Computes the report for [file] at [offset] in a smart read action and shows it near the caret of [editor]. */
    fun show(file: VirtualFile, editor: Editor?, offset: Int?) {
        scope.launch {
            val report = smartReadAction(project) { AnsibleContextReport.build(project, file, offset) } ?: return@launch
            withContext(Dispatchers.EDT) {
                if (project.isDisposed || editor?.isDisposed == true) return@withContext
                val popup = createPopup(report)
                if (editor != null) popup.showInBestPositionFor(editor) else popup.showCenteredInCurrentWindow(project)
            }
        }
    }

    private fun createPopup(report: AnsibleContextReport): JBPopup {
        val content = panel {
            for (line in report.lines) {
                row(line.label) { label(line.value) }
            }
            separator()
            row {
                link(AnsibilityDispatchBundle.message("report.copy")) {
                    CopyPasteManager.getInstance().setContents(StringSelection(report.asText()))
                }
            }
        }.apply { border = JBUI.Borders.empty(8, 12) }
        return JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, null)
            .setTitle(AnsibilityDispatchBundle.message("report.title"))
            .setRequestFocus(true)
            .setResizable(true)
            .setMovable(true)
            .createPopup()
    }

    companion object {
        fun getInstance(project: Project): AnsibleContextPopup = project.service()
    }
}
