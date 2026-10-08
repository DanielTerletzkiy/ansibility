package de.terletzkiy.ansibility.vault.monitor

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.BadgeIconSupplier
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import de.terletzkiy.ansibility.toolwindow.AnsibilityToolWindowIcons
import de.terletzkiy.ansibility.toolwindow.AnsibleToolWindowFactory
import de.terletzkiy.ansibility.vault.ui.AnsibilityVaultUiBundle.message
import javax.swing.Icon

/**
 * The "Vault" tab of the Ansibility tool window (plan amendment R21, D165): creating it ([addTo]), finding out whether
 * it is in view ([isInView]), bringing it to the front with findings selected ([show]), and the red badge on the tool
 * window's icon while an ERROR finding exists ([installBadge]).
 */
object SecretTab {
    /** Adds the Vault tab (a [SecretHealthPanel]) after the tool window's other tabs. EDT. */
    fun addTo(project: Project, toolWindow: ToolWindow): Content {
        val panel = SecretHealthPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, message("monitor.tab.title"), false)
        content.isCloseable = false
        content.setDisposer(panel)
        content.preferredFocusableComponent = panel.tree
        toolWindow.contentManager.addContent(content)
        return content
    }

    /** True while the Vault tab is the selected tab of the visible Ansibility tool window. EDT. */
    fun isInView(project: Project): Boolean {
        val window = ToolWindowManager.getInstance(project).getToolWindow(AnsibleToolWindowFactory.ID) ?: return false
        if (!window.isVisible) return false
        return window.contentManagerIfCreated?.selectedContent?.component is SecretHealthPanel
    }

    /**
     * Activates the Ansibility tool window, selects the Vault tab and in it the findings with [keys]; opens no file.
     * EDT.
     */
    fun show(project: Project, keys: Collection<String>) {
        val window = ToolWindowManager.getInstance(project).getToolWindow(AnsibleToolWindowFactory.ID) ?: return
        window.activate({
            val manager = window.contentManager
            val content = manager.contents.firstOrNull { it.component is SecretHealthPanel } ?: return@activate
            manager.setSelectedContent(content, true)
            (content.component as SecretHealthPanel).select(keys)
        }, true)
    }

    /**
     * Puts a red badge on [toolWindow]'s icon while the snapshot has an ERROR finding, and takes it off again
     * (`BadgeIconSupplier`, public API); until [parent] is disposed. The icon is only touched when the badge changes.
     */
    fun installBadge(project: Project, toolWindow: ToolWindow, parent: Disposable = toolWindow.disposable) {
        val badge = Badge(toolWindow, toolWindow.icon ?: AnsibilityToolWindowIcons.ToolWindow)
        project.messageBus.connect(parent).subscribe(
            SecretHealthListener.TOPIC,
            SecretHealthListener { _, new ->
                ApplicationManager.getApplication().invokeLater({ badge.show(new.hasErrors) }, ModalityState.any(), project.disposed)
            },
        )
        badge.show(SecretHealthService.getInstance(project).snapshot.hasErrors)
    }

    private class Badge(private val toolWindow: ToolWindow, original: Icon) {
        private val supplier = BadgeIconSupplier(original)
        private var badged = false

        fun show(errors: Boolean) {
            if (errors == badged) return
            badged = errors
            toolWindow.setIcon(if (errors) supplier.getErrorIcon(true) else supplier.originalIcon)
        }
    }
}
