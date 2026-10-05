package de.terletzkiy.ansibility.vault.tab

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentSynchronizationVetoer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.impl.EditorTabColorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseHandler
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotifications
import com.intellij.ui.JBColor
import de.terletzkiy.ansibility.api.VaultStatusListener
import java.awt.Color

/**
 * Every save of a decrypted tab's document asks its session first ([DecryptedVaultSession.maySave]): the encryption
 * runs in the background and the save repeats when the envelope is ready. Other documents are never vetoed.
 */
class DecryptedVaultSaveVetoer : FileDocumentSynchronizationVetoer() {
    override fun maySaveDocument(document: Document, isSaveExplicit: Boolean): Boolean {
        val file = FileDocumentManager.getInstance().getFile(document) as? DecryptedVaultFile ?: return true
        return file.maySave(isSaveExplicit)
    }
}

/** The red tab of a decrypted vault: plaintext is on screen. */
class DecryptedVaultTabColor : EditorTabColorProvider, DumbAware {
    override fun getEditorTabColor(project: Project, file: VirtualFile): Color? = if (file is DecryptedVaultFile) COLOR else null

    private companion object {
        val COLOR = JBColor(Color(0xF7D4D4), Color(0x5E2E2E))
    }
}

/** Lock-state changes close the tabs of locked ids and refresh the whole-file banners. */
class VaultTabStatusListener(private val project: Project) : VaultStatusListener {
    override fun vaultStatusChanged() {
        ApplicationManager.getApplication().invokeLater({
            DecryptedVaultTabs.getInstance(project).lockStateChanged()
            EditorNotifications.getInstance(project).updateAllNotifications()
        }, ModalityState.nonModal(), project.disposed)
    }
}

/** The project closes only after unsaved decrypted edits were saved or discarded. */
class DecryptedTabsCloseHandler : ProjectCloseHandler {
    override fun canClose(project: Project): Boolean {
        if (!ApplicationManager.getApplication().isDispatchThread) return true
        val tabs = project.getServiceIfCreated(DecryptedVaultTabs::class.java) ?: return true
        return tabs.canCloseProject()
    }
}

/** Closing a decrypted tab ends its session; opening or selecting one keeps it out of the editor history. */
class DecryptedTabsFileListener(private val project: Project) : FileEditorManagerListener {
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        if (file is DecryptedVaultFile) DecryptedVaultTabs.getInstance(project).harden(file)
    }

    override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
        if (file is DecryptedVaultFile) DecryptedVaultTabs.getInstance(project).fileClosed(file)
    }

    override fun selectionChanged(event: FileEditorManagerEvent) {
        (event.newFile as? DecryptedVaultFile)?.let { DecryptedVaultTabs.getInstance(project).harden(it) }
    }
}
