package de.terletzkiy.ansibility.vault.monitor

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ide.projectView.ProjectView
import de.terletzkiy.ansibility.settings.PathGlob
import de.terletzkiy.ansibility.vault.identity.VaultProjectSettings
import de.terletzkiy.ansibility.vault.ui.AnsibilityVaultUiBundle.message

/**
 * What the Vault tab asks or shows outside itself (plan amendment R21, D165); an application service, so tests replace
 * it (`ansibility-vault-ui.xml`). EDT.
 */
interface SecretTabPrompts {
    /**
     * The glob to add to the plaintext key allowlist for the selected files, prefilled with [suggestion]; null when
     * cancelled.
     */
    fun askExcludeGlob(project: Project, suggestion: String): String?

    /** Selects [file] in the Project view and brings the view to the front. */
    fun selectInProjectView(project: Project, file: VirtualFile)

    companion object {
        fun getInstance(): SecretTabPrompts = service()
    }
}

/** The dialogs of [SecretTabPrompts]. */
class SecretTabDialogPrompts : SecretTabPrompts {
    override fun askExcludeGlob(project: Project, suggestion: String): String? = Messages.showInputDialog(
        project,
        message("monitor.exclude.message"),
        message("monitor.exclude.title"),
        null,
        suggestion,
        object : InputValidatorEx {
            override fun getErrorText(inputString: String): String? {
                val glob = PathGlob.compile(inputString.trim()) ?: return message("monitor.exclude.invalid")
                return if (VaultProjectSettings.isTooBroad(glob)) message("monitor.exclude.too.broad") else null
            }
        },
    )?.trim()

    override fun selectInProjectView(project: Project, file: VirtualFile) {
        ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.PROJECT_VIEW)?.activate(null)
        ProjectView.getInstance(project).select(null, file, true)
    }
}
