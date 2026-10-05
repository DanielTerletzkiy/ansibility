package de.terletzkiy.ansibility.render.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.context.switching.ContextPopupGroup
import de.terletzkiy.ansibility.context.switching.ContextTexts
import de.terletzkiy.ansibility.render.AnsibilityRenderBundle
import de.terletzkiy.ansibility.render.service.TemplatePreviewService
import java.awt.datatransfer.StringSelection
import javax.swing.JComponent

/** The context picker `[falcon · ops › ops-ops1 ▾]`: the root's Ansible context, shared with every other feature (D77). */
internal class RenderContextAction(private val file: VirtualFile) : ComboBoxAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project ?: return
        val root = AnsibleWorkspace.getInstance(project).rootFor(file)
        e.presentation.isEnabledAndVisible = root != null
        if (root != null) e.presentation.setText(ContextTexts.buttonText(root, AnsibleContextService.getInstance(project).selection(root)), false)
    }

    override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup {
        val project = CommonDataKeys.PROJECT.getData(dataContext) ?: return DefaultActionGroup()
        val root = AnsibleWorkspace.getInstance(project).rootFor(file) ?: return DefaultActionGroup()
        return DefaultActionGroup(ContextPopupGroup(root, file))
    }
}

/** The render-target picker `[Deploy alloy config files · item 1 of 4 ▾]`: preview-local, never stored in the context. */
internal class RenderTargetAction(private val preview: RenderedPreviewEditor) : ComboBoxAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val report = preview.report
        val chosen = report?.choices?.firstOrNull { it.pick == report.pick }
        e.presentation.setText(chosen?.label ?: AnsibilityRenderBundle.message("render.target.none"), false)
        e.presentation.description = AnsibilityRenderBundle.message("render.target.description")
        e.presentation.isEnabled = (report?.choices?.size ?: 0) > 1
    }

    override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup {
        val group = DefaultActionGroup()
        preview.report?.choices?.forEach { choice ->
            group.add(object : DumbAwareAction(choice.label) {
                override fun actionPerformed(e: AnActionEvent) {
                    preview.pick = choice.pick
                }
            })
        }
        return group
    }
}

internal class RefreshRenderedAction(private val preview: RenderedPreviewEditor) : DumbAwareAction(
    AnsibilityRenderBundle.message("action.Ansibility.Render.Refresh.text"),
    AnsibilityRenderBundle.message("action.Ansibility.Render.Refresh.description"),
    AllIcons.Actions.Refresh,
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) = preview.request()
}

/** Copy Rendered: a complete output only; with placeholders it becomes "Copy with Markers" (D80). */
internal class CopyRenderedAction(private val preview: RenderedPreviewEditor) : DumbAwareAction(
    AnsibilityRenderBundle.message("action.Ansibility.Render.Copy.text"),
    AnsibilityRenderBundle.message("action.Ansibility.Render.Copy.description"),
    AllIcons.Actions.Copy,
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val report = preview.report
        e.presentation.isEnabled = report?.rendered != null && report.secretsShown == 0
        if (report != null && report.secretsShown > 0) {
            e.presentation.text = AnsibilityRenderBundle.message("action.Ansibility.Render.Copy.text")
            e.presentation.description = AnsibilityRenderBundle.message("render.copy.vault.description")
        } else if (report != null && !report.complete) {
            e.presentation.text = AnsibilityRenderBundle.message("render.copy.markers")
            e.presentation.description = AnsibilityRenderBundle.message("render.copy.markers.description")
        } else {
            e.presentation.text = AnsibilityRenderBundle.message("action.Ansibility.Render.Copy.text")
            e.presentation.description = AnsibilityRenderBundle.message("action.Ansibility.Render.Copy.description")
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val text = preview.report?.takeIf { it.rendered != null && it.secretsShown == 0 }?.text ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }
}

/**
 * "Render vault values" of one preview tab: off on every open; on, the preview decrypts the vault values it meets
 * and prints them. Shown once the render met a vault value.
 */
internal class RenderVaultValuesAction(private val preview: RenderedPreviewEditor) : DumbAwareToggleAction(
    AnsibilityRenderBundle.message("action.Ansibility.Render.Vault.text"),
    AnsibilityRenderBundle.message("action.Ansibility.Render.Vault.description"),
    AllIcons.Nodes.Padlock,
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isVisible = preview.showVaultValues || preview.report?.secretSources?.isNotEmpty() == true
    }

    override fun isSelected(e: AnActionEvent): Boolean = preview.showVaultValues

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        preview.showVaultValues = state
    }
}

/** "Ansibility: Show Rendered Template": switches the template's tab to editor + preview. */
class ShowRenderedTemplateAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = project != null && file != null && TemplatePreviewService.getInstance(project).isTemplate(file)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val manager = FileEditorManager.getInstance(project)
        val editor = manager.getSelectedEditor(file) as? TextEditorWithPreview
            ?: manager.openFile(file, true).filterIsInstance<TextEditorWithPreview>().firstOrNull()
            ?: return
        editor.setLayout(TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW)
    }
}
