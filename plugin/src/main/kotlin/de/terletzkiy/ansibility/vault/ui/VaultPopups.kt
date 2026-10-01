package de.terletzkiy.ansibility.vault.ui

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.ui.awt.RelativePoint
import de.terletzkiy.ansibility.api.SourceLocation

/** Where the vault popups appear: below the value's line when it is visible in [Editor], else at the caret, else centred. */
internal object VaultPopups {
    fun show(project: Project, popup: JBPopup, editor: Editor?, location: SourceLocation) {
        val usable = editor?.takeUnless { it.isDisposed }
        when {
            usable == null -> popup.showCenteredInCurrentWindow(project)
            FileDocumentManager.getInstance().getFile(usable.document) == location.file && location.offset <= usable.document.textLength -> {
                val point = usable.visualPositionToXY(usable.offsetToVisualPosition(location.offset))
                point.translate(0, usable.lineHeight)
                if (usable.scrollingModel.visibleArea.contains(point)) popup.show(RelativePoint(usable.contentComponent, point))
                else popup.showInBestPositionFor(usable)
            }
            else -> popup.showInBestPositionFor(usable)
        }
    }
}
