package de.terletzkiy.ansibility.vault.ui

import com.intellij.codeInsight.hint.HintManager
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.ThreadingAssertions
import de.terletzkiy.ansibility.api.VaultFailure
import de.terletzkiy.ansibility.vault.AnsibilityVaultBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Short results of vault actions: an editor hint next to the caret when the editor is showing, else a notification of
 * the `Ansibility` group titled "Ansibility Vault" (plan amendment R19, D141). Texts are bundle messages and failure
 * classes only, never values.
 */
internal object VaultUiFeedback {
    private const val GROUP = "Ansibility"

    /** What was reported, for tests (filled only in unit-test mode). */
    private val reported = CopyOnWriteArrayList<String>()

    fun info(project: Project, editor: Editor?, @Nls text: String) = show(project, editor, text, error = false)

    fun error(project: Project, editor: Editor?, @Nls text: String) = show(project, editor, text, error = true)

    fun failure(project: Project, editor: Editor?, failure: VaultFailure) = error(project, editor, AnsibilityVaultBundle.failure(failure))

    private fun show(project: Project, editor: Editor?, text: String, error: Boolean) {
        ThreadingAssertions.assertEventDispatchThread()
        if (ApplicationManager.getApplication().isUnitTestMode) reported += text
        if (project.isDisposed) return
        if (editor != null && !editor.isDisposed && editor.component.isShowing) {
            if (error) HintManager.getInstance().showErrorHint(editor, text) else HintManager.getInstance().showInformationHint(editor, text)
            return
        }
        val type = if (error) NotificationType.ERROR else NotificationType.INFORMATION
        Notification(GROUP, AnsibilityVaultUiBundle.message("notification.title"), text, type).notify(project)
    }

    /** The texts reported since the last call, oldest first. */
    @TestOnly
    fun drainForTests(): List<String> = reported.toList().also { reported.clear() }
}
