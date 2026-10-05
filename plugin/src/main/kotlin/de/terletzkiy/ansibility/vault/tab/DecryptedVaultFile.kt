package de.terletzkiy.ansibility.vault.tab

import com.intellij.openapi.editor.impl.TrailingSpacesStripper
import com.intellij.openapi.fileEditor.impl.FileDocumentManagerBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.LocalTimeCounter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * The in-memory file of a decrypted vault tab (F7.8): `<name> (decrypted)`, holding the plaintext of the whole-file
 * vault [original] with the original's highlighting (YAML for a vars file, plain text for a `*.key` or `*.vault`).
 *
 * A [LightVirtualFile] is not a `VirtualFileWithId` and not in the local file system, so the plaintext never reaches a
 * persistent index, stubs, Local History or the VFS cache; the editor history and the startup reopen never see it (its
 * URL resolves to nothing), and [DecryptedVaultTabs] also removes it from the history explicitly. The file's content is
 * the plaintext last written to [original] (the "no change, no write" baseline).
 *
 * **Saving (spike S-V1, `LightFileSaveSpikeTest`): path (a).** The file carries
 * [FileDocumentManagerBase.TRACK_NON_PHYSICAL] from birth, so its document is tracked like a physical one: the tab shows
 * the modified marker, and Cmd+S, Save All, autosave (frame deactivation, idle) and project close all save it through
 * [getOutputStream] in the save's write action, with the document's bytes. The platform skips the write when the text
 * equals the file's content. Path (b), flushing in `beforeAllDocumentsSaving`, was rejected: it misses single-document
 * saves, gives no modified state, and would need its own dirty tracking. The encryption itself never runs inside the
 * save: [DecryptedVaultSaveVetoer] defers a save until [DecryptedVaultSession] has encrypted the text in the background,
 * and [getOutputStream] / [setBinaryContent] only write that prepared envelope to [original]
 * ([DecryptedVaultSession.commit]). Trailing-space stripping and the final-newline rule are off for the file: a secret's
 * whitespace is content, and the saved bytes must be the bytes that were encrypted.
 *
 * **Split mode:** documents, the save pipeline, this file and the vetoer all live on the backend, where saves (also
 * the ones a frontend Cmd+S triggers) run exactly as in a local IDE; the frontend only renders the editor, so the
 * plaintext crosses the (encrypted) frontend connection, as F7.13 rule 8 documents. Nothing here needs the frontend.
 */
class DecryptedVaultFile internal constructor(
    original: VirtualFile,
    fileType: FileType,
    text: String,
    private val owner: Owner,
) : LightVirtualFile(nameOf(original), fileType, text, Charsets.UTF_8, LocalTimeCounter.currentTime()) {
    /** The tab that saves the file ([DecryptedVaultSession]). */
    internal interface Owner {
        /** Whether a save of the file's document may write now ([DecryptedVaultSaveVetoer]). */
        fun maySave(explicit: Boolean): Boolean

        /** Writes the bytes of a save to the real file; throws [IOException] when it cannot (the save then stays pending). */
        @Throws(IOException::class)
        fun commit(file: DecryptedVaultFile, bytes: ByteArray, modificationStamp: Long)
    }

    init {
        setOriginalFile(original)
        putUserData(FileDocumentManagerBase.TRACK_NON_PHYSICAL, true)
        TrailingSpacesStripper.setEnabled(this, false)
        // The document keeps the plaintext's own line breaks (a light file's document accepts CR), and "\n" tells the
        // save to write them as they are instead of converting them.
        detectedLineSeparator = "\n"
    }

    /** The whole-file vault this tab decrypts. */
    val original: VirtualFile get() = originalFile

    override fun getOutputStream(requestor: Any?, newModificationStamp: Long, newTimeStamp: Long): OutputStream =
        object : ByteArrayOutputStream() {
            private var closed = false

            override fun close() {
                if (closed) return
                closed = true
                val bytes = toByteArray()
                try {
                    owner.commit(this@DecryptedVaultFile, bytes, newModificationStamp)
                } finally {
                    bytes.fill(0)
                    buf.fill(0)
                }
            }
        }

    override fun setBinaryContent(content: ByteArray, newModificationStamp: Long, newTimeStamp: Long, requestor: Any?) =
        owner.commit(this, content, newModificationStamp)

    /** [Owner.maySave] of the tab. */
    internal fun maySave(explicit: Boolean): Boolean = owner.maySave(explicit)

    /** The session that owns the file (any thread: its banner state is volatile). */
    internal val session: DecryptedVaultSession? get() = owner as? DecryptedVaultSession

    /** Records that [text] is what [original] holds now, at [modificationStamp] (the document's stamp at the save). */
    internal fun markSaved(text: CharSequence, modificationStamp: Long) {
        setContent(null, text, false)
        setModificationStamp(modificationStamp)
    }

    override fun toString(): String = "DecryptedVaultFile(${original.name})"

    companion object {
        /** True when [file] is a decrypted vault tab. Other areas use it to stay silent there (ANS-X001) or to find the root. */
        @JvmStatic
        fun isDecryptedTab(file: VirtualFile?): Boolean = file is DecryptedVaultFile

        /** The whole-file vault behind a decrypted tab, or null for any other file. */
        @JvmStatic
        fun originalOf(file: VirtualFile?): VirtualFile? = (file as? DecryptedVaultFile)?.original

        /** The tab name, `<name> (decrypted)`. */
        internal fun nameOf(original: VirtualFile): String = TabTexts.message("tab.name", original.name)

        /** The original's file type for the plaintext: its own (YAML for a vars file), plain text when it has none. */
        internal fun fileTypeFor(original: VirtualFile): FileType =
            original.fileType.takeUnless { it.isBinary || it == UnknownFileType.INSTANCE } ?: PlainTextFileType.INSTANCE
    }
}
