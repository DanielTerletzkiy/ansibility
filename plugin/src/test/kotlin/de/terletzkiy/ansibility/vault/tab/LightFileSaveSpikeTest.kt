package de.terletzkiy.ansibility.vault.tab

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileDocumentSynchronizationVetoer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.FileDocumentManagerBase
import com.intellij.openapi.fileEditor.impl.FileDocumentManagerImpl
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Spike S-V1 (plan amendment R7/R8, M4.6), kept in the suite so a platform change that breaks the decrypted tab's save
 * path fails here first: how the IDE saves a [LightVirtualFile], on the 262 platform.
 *
 * - **(a) [FileDocumentManagerBase.TRACK_NON_PHYSICAL]** on the file before its document exists: edits mark the
 *   document unsaved (the tab's `*`), and Cmd+S ([FileDocumentManager.saveDocument], Save All), autosave
 *   (`saveAllDocuments(false)`, what frame deactivation and the idle autosave call) and project close all reach the
 *   file's own `getOutputStream`, in a write action, with the bytes of the document. A save whose text equals the
 *   file's content writes nothing (the platform compares them first). A [FileDocumentSynchronizationVetoer] that says no
 *   keeps the document unsaved without an error dialog, and is told whether the save is explicit.
 * - **(b) `beforeAllDocumentsSaving`**: fires for Save All and autosave but not for a single-document save, and without
 *   (a) a light file's document is never unsaved, so the tab shows no `*` and nothing knows about unsaved edits.
 * - **Closing** an editor with unsaved edits asks nothing: the document stays unsaved and the next save writes it.
 *
 * Path (a) is the one [DecryptedVaultFile] uses; see its KDoc for split mode.
 */
class LightFileSaveSpikeTest : BasePlatformTestCase() {
    /** A light file that records what the IDE writes to it. */
    private class RecordingFile(text: String) : LightVirtualFile("spike.txt", PlainTextFileType.INSTANCE, text) {
        val writes = CopyOnWriteArrayList<String>()
        val writeActions = CopyOnWriteArrayList<Boolean>()

        override fun getOutputStream(requestor: Any?, newModificationStamp: Long, newTimeStamp: Long): OutputStream {
            val delegate = super.getOutputStream(requestor, newModificationStamp, newTimeStamp)
            return object : ByteArrayOutputStream() {
                override fun close() {
                    writes += toString(Charsets.UTF_8)
                    writeActions += ApplicationManager.getApplication().isWriteAccessAllowed
                    delegate.write(toByteArray())
                    delegate.close()
                }
            }
        }
    }

    private val manager: FileDocumentManager get() = FileDocumentManager.getInstance()

    private fun tracked(text: String): RecordingFile =
        RecordingFile(text).apply { putUserData(FileDocumentManagerBase.TRACK_NON_PHYSICAL, true) }

    private fun documentOf(file: LightVirtualFile): Document = manager.getDocument(file)!!

    private fun type(document: Document, text: String) =
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, text) }

    private fun replace(document: Document, text: String) =
        WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }

    override fun tearDown() {
        try {
            WriteCommandAction.runWriteCommandAction(project) { (manager as FileDocumentManagerImpl).dropAllUnsavedDocuments() }
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testUntrackedLightFileIsNeverSaved() {
        val file = RecordingFile("one\n")
        val document = documentOf(file)
        type(document, "two\n")
        assertFalse("without TRACK_NON_PHYSICAL the document is never unsaved", manager.isDocumentUnsaved(document))
        manager.saveAllDocuments()
        manager.saveDocument(document)
        assertEmpty(file.writes)
    }

    fun testTrackedLightFileSavesOnCmdSSaveAllAndAutosave() {
        val file = tracked("one\n")
        val document = documentOf(file)

        type(document, "two\n")
        assertTrue(manager.isDocumentUnsaved(document))
        assertTrue(manager.isFileModified(file))
        manager.saveDocument(document)
        assertEquals(listOf("one\ntwo\n"), file.writes)
        assertFalse(manager.isDocumentUnsaved(document))

        type(document, "three\n")
        manager.saveAllDocuments()
        assertEquals("one\ntwo\nthree\n", file.writes.last())

        type(document, "four\n")
        (manager as FileDocumentManagerImpl).saveAllDocuments(false)
        assertEquals("autosave writes too", "one\ntwo\nthree\nfour\n", file.writes.last())
        assertEquals("every write ran in a write action", listOf(true, true, true), file.writeActions)
        assertFalse(manager.isDocumentUnsaved(document))
    }

    fun testUnchangedTextIsNotWritten() {
        val file = tracked("same\n")
        val document = documentOf(file)
        replace(document, "changed\n")
        replace(document, "same\n")
        assertTrue("an edit and its reversal still leave the document unsaved", manager.isDocumentUnsaved(document))
        manager.saveAllDocuments()
        assertEmpty("the platform compares the text with the file's content first", file.writes)
        assertFalse(manager.isDocumentUnsaved(document))
    }

    fun testVetoerKeepsTheDocumentUnsavedAndSeesExplicitness() {
        val file = tracked("one\n")
        val document = documentOf(file)
        val asked = CopyOnWriteArrayList<Boolean>()
        val vetoer = object : FileDocumentSynchronizationVetoer() {
            override fun maySaveDocument(document: Document, isSaveExplicit: Boolean): Boolean {
                if (manager.getFile(document) != file) return true
                asked += isSaveExplicit
                return isSaveExplicit
            }
        }
        FileDocumentSynchronizationVetoer.EP_NAME.point.registerExtension(vetoer, testRootDisposable)

        type(document, "two\n")
        (manager as FileDocumentManagerImpl).saveAllDocuments(false)
        assertEmpty(file.writes)
        assertTrue("a veto keeps the edit unsaved, without an error", manager.isDocumentUnsaved(document))
        manager.saveAllDocuments()
        assertEquals(listOf("one\ntwo\n"), file.writes)
        assertEquals(listOf(false, true), asked)
    }

    fun testBeforeAllDocumentsSavingMissesSingleDocumentSaves() {
        val file = tracked("one\n")
        val document = documentOf(file)
        var beforeAll = 0
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable).subscribe(
            FileDocumentManagerListener.TOPIC,
            object : FileDocumentManagerListener {
                override fun beforeAllDocumentsSaving() {
                    beforeAll++
                }
            },
        )
        type(document, "two\n")
        manager.saveDocument(document)
        assertEquals("Cmd+S on one document does not announce itself through beforeAllDocumentsSaving", 0, beforeAll)
        assertEquals(1, file.writes.size)
        manager.saveAllDocuments()
        assertEquals(1, beforeAll)
    }

    fun testClosingAnEditorKeepsTheUnsavedDocument() {
        val file = tracked("one\n")
        val editors = FileEditorManager.getInstance(project)
        editors.openFile(file, true)
        val document = documentOf(file)
        type(document, "two\n")
        editors.closeFile(file)
        assertFalse(editors.isFileOpen(file))
        assertTrue("closing asks nothing and keeps the edit", manager.isDocumentUnsaved(document))
        manager.saveAllDocuments()
        assertEquals("the next save writes the closed file", listOf("one\ntwo\n"), file.writes)
    }
}
