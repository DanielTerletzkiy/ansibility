package de.terletzkiy.ansibility.render.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.TextEditorWithPreviewProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.productMode.IdeProductMode
import de.terletzkiy.ansibility.render.AnsibilityRenderBundle
import de.terletzkiy.ansibility.render.service.TemplatePreviewService

/**
 * The template split editor (plan amendment R11, F11.2): editor, editor + preview and preview, like Markdown. The
 * default layout is "editor only", so nothing changes until the user toggles (D76). Not offered on a split-mode
 * backend, where the composite would have to be transferred.
 */
class RenderedTemplateEditorProvider : TextEditorWithPreviewProvider(RenderedPreviewEditorProvider()), DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean =
        !IdeProductMode.isBackend && TemplatePreviewService.getInstance(project).isTemplate(file) && super.accept(project, file)

    override fun getEditorTypeId(): String = EDITOR_TYPE_ID

    override fun createSplitEditor(firstEditor: TextEditor, secondEditor: FileEditor): FileEditor {
        (secondEditor as? RenderedPreviewEditor)?.attachSource(firstEditor.editor)
        return TextEditorWithPreview(firstEditor, secondEditor, AnsibilityRenderBundle.message("preview.editor.name"), TextEditorWithPreview.Layout.SHOW_EDITOR)
    }

    companion object {
        const val EDITOR_TYPE_ID: String = "ansibility-rendered-template"
    }
}

/** The preview half on its own; only [RenderedTemplateEditorProvider] uses it. */
class RenderedPreviewEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = TemplatePreviewService.getInstance(project).isTemplate(file)

    override fun createEditor(project: Project, file: VirtualFile): FileEditor = RenderedPreviewEditor(project, file)

    override fun getEditorTypeId(): String = "ansibility-rendered-preview"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR
}
