package de.terletzkiy.ansibility.vault.ui

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiManager
import de.terletzkiy.ansibility.api.VaultStatusListener
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the X93 lock icons current: after an unlock, a lock or a verification change ([VaultStatusListener]), the
 * open YAML files that hold `!vault` values are re-highlighted once, so their gutter shows the new state. Bursts of
 * events (an unlock of several ids) are merged into one restart on the EDT. Registered as a project listener.
 */
class VaultGutterRefresher(private val project: Project) : VaultStatusListener {
    private val scheduled = AtomicBoolean()

    override fun vaultStatusChanged() {
        if (!scheduled.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater({
            scheduled.set(false)
            restartOpenVaultFiles()
        }, project.disposed)
    }

    private fun restartOpenVaultFiles() {
        val documents = FileDocumentManager.getInstance()
        val psiManager = PsiManager.getInstance(project)
        val daemon = DaemonCodeAnalyzer.getInstance(project)
        for (file in FileEditorManager.getInstance(project).openFiles) {
            val document = documents.getCachedDocument(file) ?: continue
            if (!StringUtil.contains(document.charsSequence, VAULT_TAG)) continue
            psiManager.findFile(file)?.let { daemon.restart(it, REASON) }
        }
    }

    private companion object {
        const val VAULT_TAG = "!vault"
        const val REASON = "Ansibility vault lock state changed"
    }
}
