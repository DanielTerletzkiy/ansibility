package de.terletzkiy.ansibility.vault.reencrypt

import com.intellij.history.LocalHistory
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.BasicUndoableAction
import com.intellij.openapi.command.undo.DocumentReference
import com.intellij.openapi.command.undo.DocumentReferenceManager
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.command.undo.UndoableAction
import com.intellij.openapi.command.undo.UnexpectedUndoException
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.ex.DocumentEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.ui.EditorNotifications
import com.intellij.util.concurrency.ThreadingAssertions
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.vault.VaultLog
import de.terletzkiy.ansibility.vault.actions.VaultFileOperations
import de.terletzkiy.ansibility.vault.actions.VaultReplacement
import de.terletzkiy.ansibility.vault.actions.VaultValuePsi
import de.terletzkiy.ansibility.vault.actions.VaultValueRef
import de.terletzkiy.ansibility.vault.actions.VaultValueText
import de.terletzkiy.ansibility.vault.tab.DecryptedVaultTabs
import de.terletzkiy.ansibility.vault.ui.AnsibilityVaultUiBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.TestOnly
import org.jetbrains.yaml.psi.YAMLScalar
import java.io.IOException
import java.nio.file.Path

/**
 * The write side of the re-encryption engine (plan amendment R29): R18's write mechanics moved out of
 * `VaultFileOperations`, plus the all-or-nothing write of Part 2. Everything here runs on the EDT.
 *
 * Write modes:
 * 1. **per file**, one undoable command each ([write], [writeValues]): R18's Encrypt / Decrypt in place, Rekey and
 *    Change Id, unchanged. A file that changed meanwhile, has unsaved edits or unsaved edits in its decrypted tab is
 *    left alone (D132); an open decrypted tab of a rewritten whole file is closed first; whole-file writes are byte-exact
 *    with 0600 before the first byte where asked.
 * 2. **all files in one undoable global command** ([writeAll], Part 2, D221): before the first change every file's
 *    document must still have the modification sequence the preflight saw and every value must still be where its
 *    pointer says ([staleFile]); otherwise nothing is written. Local History labels before and after.
 * 3. **file-system writes that are not undoable** ([writeAllFileSystem], Part 1, D210), all or nothing: the same
 *    [staleFile] precondition and Local History labels as [writeAll], then each file's new bytes ([newText] for a YAML
 *    file, the envelope for a whole-file vault) written through the VFS outside any command, like `ansible-vault
 *    rekey` writes them; a failed write puts the old bytes (envelopes, no secret) of the files written before it back.
 */
internal class ReencryptionWriter(private val project: Project, private val modes: () -> VaultFileOperations.FileModes) {
    sealed interface Content {
        /** An envelope: ASCII lines, LF, no byte order mark (Ansible reads `$ANSIBLE_VAULT` from the first byte). */
        class Envelope(val text: String) : Content

        /** Decrypted bytes (the caller zeroes them): UTF-8 text through the document, anything else as it is. */
        class Plain(val bytes: ByteArray) : Content {
            override fun toString(): String = "Plain(***)"
        }
    }

    // ------------------------------------------------------------------------------------------------ mode 1: whole files

    /**
     * Writes [content] over [file] when it is still at [stamp], in one undoable command (Edit › Undo restores the old
     * text, byte order mark and line separator): text through its document, saved at once; binary content as bytes. When
     * the saved bytes differ from [content]'s exact bytes (a charset of the file, the trailing-space stripper, mixed line
     * separators), the exact bytes are written over them in the same command. With [private] the file gets 0600 before
     * anything is written, and is left alone when that fails.
     */
    fun write(file: VirtualFile, stamp: Long, content: Content, @Nls commandName: String, private: Boolean): FileOutcome {
        ThreadingAssertions.assertEventDispatchThread()
        if (!file.isValid) return FileOutcome.Changed
        val documents = FileDocumentManager.getInstance()
        if (file.modificationStamp != stamp || documents.getCachedDocument(file)?.let(documents::isDocumentUnsaved) == true) return FileOutcome.Changed
        if (!file.isWritable) return FileOutcome.ReadOnly
        if (!DecryptedVaultTabs.getInstance(project).closeBeforeRewrite(file)) return FileOutcome.TabModified
        // D29 before the first byte: decrypted content is never readable by others, not even until the mode is set.
        if (private && !makePrivate(file)) return FileOutcome.NotPrivate
        val exact = when (content) {
            is Content.Envelope -> content.text.toByteArray(Charsets.US_ASCII)
            is Content.Plain -> content.bytes
        }
        val rewrite = rewriteOf(file, content)
        val document = rewrite?.let { documents.getDocument(file) }
        var written = false
        WriteCommandAction.writeCommandAction(project).withName(commandName).withGlobalUndo().run<RuntimeException> {
            written = if (rewrite == null || document == null) writeBytes(file, exact) else writeText(file, document, rewrite, exact, content is Content.Plain)
        }
        if (!written) return FileOutcome.NotWritten
        EditorNotifications.getInstance(project).updateNotifications(file)
        return FileOutcome.Written
    }

    /** The byte order mark and line separator a file is saved with (its document holds the text, with LF). */
    private class FileForm(val bom: ByteArray?, val separator: String?) {
        fun applyTo(file: VirtualFile) {
            file.bom = bom
            file.detectedLineSeparator = separator
        }

        companion object {
            fun of(file: VirtualFile) = FileForm(file.bom, file.detectedLineSeparator)
        }
    }

    /** A document text and the [FileForm] it is saved with. */
    private class Rewrite(val text: CharSequence, val form: FileForm) {
        override fun toString(): String = "Rewrite(***)"
    }

    /** Undo and redo of a [FileForm] change, in the command of the text change it goes with (the document's undo has the text). */
    private class FileFormUndo(document: Document, private val file: VirtualFile, private val before: FileForm, private val after: FileForm) :
        BasicUndoableAction(document) {
        override fun undo() = before.applyTo(file)

        override fun redo() = after.applyTo(file)
    }

    /** [content] as the text of [file]'s document, or null when it is no text (a binary file type, bytes that are no UTF-8). */
    private fun rewriteOf(file: VirtualFile, content: Content): Rewrite? {
        if (file.fileType.isBinary) return null
        return when (content) {
            is Content.Envelope -> Rewrite(content.text, FileForm(null, "\n"))
            is Content.Plain -> {
                val bytes = content.bytes
                val hasBom = bytes.size >= UTF8_BOM.size && UTF8_BOM.indices.all { bytes[it] == UTF8_BOM[it] }
                val body = if (hasBom) bytes.copyOfRange(UTF8_BOM.size, bytes.size) else bytes
                val decoded = try {
                    VaultValueText.decode(body)
                } finally {
                    if (hasBom) body.fill(0)
                } ?: return null
                val form = FileForm(if (hasBom) UTF8_BOM.copyOf() else null, if ("\r\n" in decoded) "\r\n" else "\n")
                Rewrite(StringUtil.convertLineSeparators(decoded), form)
            }
        }
    }

    /**
     * Gives [document] the text of [rewrite] and saves it, then writes [exact] over the saved bytes when they differ
     * ([secret]: they are zeroed afterwards). When either fails (an I/O error the save reported, a vetoer), the old text
     * goes back and is saved, so nothing new (no plaintext) waits in the document for autosave; false then. Inside the
     * caller's command.
     */
    private fun writeText(file: VirtualFile, document: Document, rewrite: Rewrite, exact: ByteArray, secret: Boolean): Boolean {
        val documents = FileDocumentManager.getInstance()
        val old = Rewrite(document.immutableCharSequence, FileForm.of(file))
        replace(file, document, rewrite)
        documents.saveDocument(document)
        if (!documents.isDocumentUnsaved(document) && (savedExactly(file, exact, secret) || writeBytes(file, exact))) return true
        replace(file, document, old)
        documents.saveDocument(document)
        return false
    }

    /** [document] gets [rewrite]'s text, [file] its form, undoably. Inside a command. */
    private fun replace(file: VirtualFile, document: Document, rewrite: Rewrite) {
        UndoManager.getInstance(project).undoableActionPerformed(FileFormUndo(document, file, FileForm.of(file), rewrite.form))
        rewrite.form.applyTo(file)
        document.setText(rewrite.text)
    }

    /** True when [file] holds exactly [exact] ([secret]: the bytes read are zeroed afterwards). */
    private fun savedExactly(file: VirtualFile, exact: ByteArray, secret: Boolean): Boolean {
        val saved = try {
            file.contentsToByteArray()
        } catch (_: IOException) {
            return false
        }
        try {
            return saved.contentEquals(exact)
        } finally {
            if (secret) saved.fill(0)
        }
    }

    /**
     * Writes [exact] as [file]'s bytes, as they are (a local file's `setBinaryContent` adds no byte order mark; one in
     * [exact] is written once). False on an I/O error. Write action.
     */
    private fun writeBytes(file: VirtualFile, exact: ByteArray): Boolean {
        return try {
            file.setBinaryContent(exact)
            true
        } catch (e: IOException) {
            VaultLog.failure(VaultLog.Operation.ENCRYPT, e, file.path)
            false
        }
    }

    /**
     * 0600, like ansible-vault's encrypt and decrypt in place (D29); file systems without POSIX modes are left alone.
     * False when the mode could not be set (a file of another user, a mount that refuses it).
     */
    fun makePrivate(file: VirtualFile): Boolean {
        val path: Path = file.toNioPathOrNull() ?: return true
        return try {
            modes().makePrivate(path)
            true
        } catch (e: IOException) {
            VaultLog.failure(VaultLog.Operation.ENCRYPT, e, file.path)
            false
        }
    }

    // ------------------------------------------------------------------------------------------------ mode 1: values

    /** Replaces the `!vault` values of [file] with their new envelopes in one command, or nothing when one of them changed. */
    fun writeValues(file: VirtualFile, replacements: List<Pair<VaultValueRef, VaultEnvelope>>, @Nls commandName: String): FileOutcome {
        ThreadingAssertions.assertEventDispatchThread()
        if (!file.isValid) return FileOutcome.Changed
        if (!file.isWritable) return FileOutcome.ReadOnly
        val documents = PsiDocumentManager.getInstance(project)
        documents.commitAllDocuments()
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return FileOutcome.NotWritten
        val changes = replacements.map { (ref, envelope) ->
            val scalar = ref.scalar?.takeIf(YAMLScalar::isValid) ?: return FileOutcome.Changed
            if (scalar.textValue != ref.envelopeText) return FileOutcome.Changed
            VaultValueText.envelopeReplacement(scalar, document.charsSequence, envelope)
        }.sortedByDescending { it.range.startOffset }
        var written = false
        WriteCommandAction.writeCommandAction(project, PsiManager.getInstance(project).findFile(file)).withName(commandName).withGlobalUndo().run<RuntimeException> {
            for (change in changes) document.replaceString(change.range.startOffset, change.range.endOffset, change.text)
            documents.commitDocument(document)
            written = true
        }
        if (!written) return FileOutcome.ReadOnly
        FileDocumentManager.getInstance().saveDocument(document)
        return FileOutcome.Written
    }

    // ------------------------------------------------------------------------------------------------ mode 2: all files, one command

    /** Why an all-or-nothing write did not start, with the file it is about. */
    sealed interface Stale {
        val file: VirtualFile

        /** The file, its document or one of its values changed after the preflight (or is gone). */
        class Changed(override val file: VirtualFile) : Stale

        class ReadOnly(override val file: VirtualFile) : Stale

        /** Its decrypted tab has unsaved edits (a whole-file vault). */
        class TabModified(override val file: VirtualFile) : Stale
    }

    /** How [writeAll] ended. */
    sealed interface AllOutcome {
        /** Every ready item was written: [files] in order, [values] new envelopes; [labelBefore] is the Local History label id. */
        class Written(val files: List<VirtualFile>, val values: Int, val labelBefore: String?) : AllOutcome

        /** Nothing was written: [stale] says which file stopped the write. */
        class NotWritten(val stale: Stale) : AllOutcome

        /** No item was ready. */
        data object Nothing : AllOutcome
    }

    /**
     * Mode 2 (D221): writes every ready item of [plan] in ONE undoable global command named [commandName] (all files,
     * all or nothing), with the Local History labels [labelBefore] and [labelAfter] around it; then saves the documents.
     * Supports inline values and plaintext values (`!vault` blocks in YAML documents); a whole-file item stops the write
     * as changed (whole files go through mode 1 or mode 3). Nothing is written when [staleFile] finds a file.
     */
    fun writeAll(plan: PreparedPlan, @Nls commandName: String, @Nls labelBefore: String, @Nls labelAfter: String): AllOutcome {
        ThreadingAssertions.assertEventDispatchThread()
        val byFile = plan.readyByFile
        if (byFile.isEmpty()) return AllOutcome.Nothing
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        staleFile(plan)?.let { return AllOutcome.NotWritten(it) }
        val edits = LinkedHashMap<Document, List<VaultReplacement>>()
        val psiFiles = ArrayList<PsiFile>()
        for ((file, items) in byFile) {
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return AllOutcome.NotWritten(Stale.Changed(file))
            edits[document] = items.map { replacementOf(it, document) ?: return AllOutcome.NotWritten(Stale.Changed(file)) }
                .sortedByDescending { it.range.startOffset }
            if (overlaps(edits.getValue(document))) return AllOutcome.NotWritten(Stale.Changed(file))
            PsiManager.getInstance(project).findFile(file)?.let(psiFiles::add)
        }
        val history = LocalHistory.getInstance()
        val before = history.putSystemLabel(project, labelBefore).id
        val action = history.startAction(commandName)
        var written = false
        try {
            // The files are made writable (or the command refused) before anything changes: all or nothing.
            WriteCommandAction.writeCommandAction(project, *psiFiles.toTypedArray())
                .withName(commandName)
                .withGlobalUndo()
                .shouldRecordActionForActiveDocument(false)
                .run<RuntimeException> {
                    for ((document, changes) in edits) {
                        for (change in changes) document.replaceString(change.range.startOffset, change.range.endOffset, change.text)
                        PsiDocumentManager.getInstance(project).commitDocument(document)
                    }
                    written = true
                }
        } finally {
            action.finish()
        }
        if (!written) return AllOutcome.NotWritten(Stale.ReadOnly(byFile.keys.first()))
        val documents = FileDocumentManager.getInstance()
        for (document in edits.keys) documents.saveDocument(document)
        history.putSystemLabel(project, labelAfter)
        return AllOutcome.Written(byFile.keys.toList(), byFile.values.sumOf { it.size }, before)
    }

    /**
     * The first file of [plan]'s ready items that must stop an all-or-nothing write (modes 2 and 3): gone, read-only,
     * its document changed since the preflight took its modification sequence, unsaved, a value no longer where its
     * pointer says (or no longer holding the text the preflight read), a whole-file vault not at the stamp it was read
     * at or with unsaved edits in its decrypted tab. Null when everything is as the preflight saw it. Documents committed.
     */
    fun staleFile(plan: PreparedPlan): Stale? {
        ThreadingAssertions.assertEventDispatchThread()
        val documents = FileDocumentManager.getInstance()
        for ((file, items) in plan.readyByFile) {
            if (!file.isValid) return Stale.Changed(file)
            if (!file.isWritable) return Stale.ReadOnly(file)
            val document = documents.getCachedDocument(file)
            if (document != null && documents.isDocumentUnsaved(document)) return Stale.Changed(file)
            val seen = plan.sequences[file]
            if (seen != null && (document as? DocumentEx ?: documents.getDocument(file) as? DocumentEx)?.modificationSequence != seen) return Stale.Changed(file)
            for (prepared in items) {
                when (val target = prepared.item.target) {
                    is ReencryptTarget.WholeFile -> {
                        if (file.modificationStamp != prepared.stamp) return Stale.Changed(file)
                        if (DecryptedVaultTabs.getInstance(project).sessionOf(file)?.isModified == true) return Stale.TabModified(file)
                    }
                    is ReencryptTarget.Value -> if (target.ref.scalar?.takeIf { it.isValid }?.textValue != target.ref.envelopeText) return Stale.Changed(file)
                    is ReencryptTarget.PlainValue -> if (target.ref.scalar?.takeIf { it.isValid }?.textValue != target.ref.value) return Stale.Changed(file)
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------------------------------------------ mode 3: file system, not undoable

    /** How [writeAllFileSystem] ended. */
    sealed interface FileSystemOutcome {
        /** Every ready item was written: [files] in order, [items] new envelopes; [labelBefore] is the Local History label id. */
        class Written(val files: List<VirtualFile>, val items: Int, val labelBefore: String?) : FileSystemOutcome

        /** Nothing was written: [stale] says which file stopped the write before the first byte. */
        class NotWritten(val stale: Stale) : FileSystemOutcome

        /**
         * Writing [file] failed after [restored] and [notRestored] had been written: [restored] got their old bytes back,
         * [notRestored] keep the new envelopes. Nothing of [file] itself was written.
         */
        class Failed(val file: VirtualFile, val restored: List<VirtualFile>, val notRestored: List<VirtualFile>) : FileSystemOutcome

        /** No item was ready. */
        data object Nothing : FileSystemOutcome
    }

    /** One file of a mode 3 write: its bytes before and after (envelopes and YAML, never a secret). */
    private class FileWrite(val file: VirtualFile, val old: ByteArray, val new: ByteArray, val whole: Boolean) {
        override fun toString(): String = "FileWrite(${file.name})"
    }

    /**
     * Mode 3 (Rotate Vault Password…, D210): writes every ready item of [plan] through the file system, all files or
     * none, and **not undoable**: an Undo would bring back envelopes the new password cannot open while the password
     * source already holds the new one. Before the first byte: [staleFile] (a file that changed since the preflight
     * stops everything), every file's new bytes (a YAML file: [newText] in its charset, byte order mark and line
     * separator; a whole-file vault: its envelope as ansible-vault formats it) and old bytes are made, and the decrypted
     * tabs of rewritten whole files are closed. Local History gets [labelBefore] and [labelAfter]. A file whose document
     * is loaded reloads from disk; [refuseUndo] makes sure no Undo of an earlier edit brings its old text back. When a
     * write fails (an I/O error), the files written before it get their old bytes back and the outcome says so.
     */
    fun writeAllFileSystem(plan: PreparedPlan, @Nls labelBefore: String, @Nls labelAfter: String): FileSystemOutcome {
        ThreadingAssertions.assertEventDispatchThread()
        val byFile = plan.readyByFile
        if (byFile.isEmpty()) return FileSystemOutcome.Nothing
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        staleFile(plan)?.let { return FileSystemOutcome.NotWritten(it) }
        val writes = ArrayList<FileWrite>()
        for ((file, items) in byFile) {
            val whole = items.singleOrNull()?.takeIf { it.item.target is ReencryptTarget.WholeFile }
            val bytes = if (whole != null) {
                whole.ready?.envelope?.formatBytes() ?: return FileSystemOutcome.NotWritten(Stale.Changed(file))
            } else {
                if (items.any { it.item.target is ReencryptTarget.WholeFile }) return FileSystemOutcome.NotWritten(Stale.Changed(file))
                val document = FileDocumentManager.getInstance().getDocument(file) ?: return FileSystemOutcome.NotWritten(Stale.Changed(file))
                val text = newText(document, items) ?: return FileSystemOutcome.NotWritten(Stale.Changed(file))
                encode(file, text)
            }
            val old = try {
                file.contentsToByteArray()
            } catch (e: IOException) {
                VaultLog.failure(VaultLog.Operation.ENCRYPT, e, file.path)
                return FileSystemOutcome.NotWritten(Stale.Changed(file))
            }
            writes += FileWrite(file, old, bytes, whole != null)
        }
        val tabs = DecryptedVaultTabs.getInstance(project)
        for (write in writes) if (write.whole && !tabs.closeBeforeRewrite(write.file)) return FileSystemOutcome.NotWritten(Stale.TabModified(write.file))
        val history = LocalHistory.getInstance()
        val before = history.putSystemLabel(project, labelBefore).id
        val done = ArrayList<FileWrite>()
        for (write in writes) {
            if (!writeNotUndoable(write.file, write.new)) {
                val restored = ArrayList<VirtualFile>()
                val notRestored = ArrayList<VirtualFile>()
                for (written in done.asReversed()) if (writeNotUndoable(written.file, written.old)) restored += written.file else notRestored += written.file
                history.putSystemLabel(project, labelAfter)
                return FileSystemOutcome.Failed(write.file, restored, notRestored)
            }
            done += write
            if (write.whole) EditorNotifications.getInstance(project).updateNotifications(write.file)
        }
        history.putSystemLabel(project, labelAfter)
        return FileSystemOutcome.Written(writes.map { it.file }, byFile.values.sumOf { it.size }, before)
    }

    /** [text] (a document's, LF) as [file]'s bytes: its line separator, charset and byte order mark, as a save would write them. */
    private fun encode(file: VirtualFile, text: String): ByteArray {
        val separator = file.detectedLineSeparator ?: LF
        val body = (if (separator == LF) text else StringUtil.convertLineSeparators(text, separator)).toByteArray(file.charset)
        val bom = file.bom ?: return body
        return bom + body
    }

    /**
     * Writes [bytes] as [file]'s content through the VFS, and makes sure no Undo brings back what it held before (old
     * envelopes the new password cannot open). In 262 a file whose document is loaded reloads it from disk as an
     * undoable "Reload from Disk", inside the write; so the write runs in a command of its own that also records an
     * action refusing to be undone ([RefusedUndo]): an Undo of that command stops at the refusal, and the reload stays.
     * False on an I/O error (nothing of the file was written then).
     */
    private fun writeNotUndoable(file: VirtualFile, bytes: ByteArray): Boolean {
        var written = false
        CommandProcessor.getInstance().executeCommand(
            project,
            {
                written = try {
                    failWriteForTests?.let { fail -> if (fail(file)) throw IOException("a write the test refuses") }
                    WriteAction.run<IOException> { file.setBinaryContent(bytes) }
                    true
                } catch (e: IOException) {
                    VaultLog.failure(VaultLog.Operation.ENCRYPT, e, file.path)
                    false
                }
                val document = FileDocumentManager.getInstance().getCachedDocument(file)
                if (written && document != null) {
                    UndoManager.getInstance(project).undoableActionPerformed(RefusedUndo(DocumentReferenceManager.getInstance().create(document)))
                }
            },
            AnsibilityVaultUiBundle.message("rotate.undo.command"), null,
        )
        return written
    }

    /**
     * An undoable action that refuses to be undone or redone ([UnexpectedUndoException]): the platform then says the
     * change cannot be undone and leaves the document as it is (plan amendment R29, D210).
     */
    private class RefusedUndo(private val reference: DocumentReference) : UndoableAction {
        override fun undo(): Unit = throw UnexpectedUndoException(AnsibilityVaultUiBundle.message("rotate.undo.refused"))

        override fun redo(): Unit = throw UnexpectedUndoException(AnsibilityVaultUiBundle.message("rotate.undo.refused"))

        override fun getAffectedDocuments(): Array<DocumentReference> = arrayOf(reference)

        override fun isGlobal(): Boolean = false
    }

    /**
     * The text of [document] with every ready value of [items] replaced (mode 3 writes these bytes through the file
     * system); null when a value is not where its pointer says. Documents committed.
     */
    fun newText(document: Document, items: List<PreparedItem>): String? {
        val changes = items.map { replacementOf(it, document) ?: return null }.sortedByDescending { it.range.startOffset }
        if (overlaps(changes)) return null
        val text = StringBuilder(document.immutableCharSequence)
        for (change in changes) text.replace(change.range.startOffset, change.range.endOffset, change.text)
        return text.toString()
    }

    /** The replacement that writes [prepared]'s new envelope into its value, keeping the key, its anchor, tag position and indentation. */
    private fun replacementOf(prepared: PreparedItem, document: Document): VaultReplacement? {
        val envelope = prepared.ready?.envelope ?: return null
        return when (val target = prepared.item.target) {
            is ReencryptTarget.Value -> target.ref.scalar?.takeIf { it.isValid && it.textValue == target.ref.envelopeText }
                ?.let { VaultValueText.envelopeReplacement(it, document.charsSequence, envelope) }
            is ReencryptTarget.PlainValue -> target.ref.scalar?.takeIf { it.isValid && it.textValue == target.ref.value }
                ?.let { plainReplacement(it, document.charsSequence, envelope) }
            is ReencryptTarget.WholeFile -> null
        }
    }

    /**
     * A plaintext value becomes a `!vault |` block ([VaultValueText.envelopeReplacement]); an anchor written before the
     * value (`vault_x: &pw placeholder`) stays where it is.
     */
    private fun plainReplacement(scalar: YAMLScalar, text: CharSequence, envelope: VaultEnvelope): VaultReplacement {
        val replacement = VaultValueText.envelopeReplacement(scalar, text, envelope)
        val anchorEnd = VaultValuePsi.anchorEnd(scalar) ?: return replacement
        if (anchorEnd <= replacement.range.startOffset || anchorEnd >= replacement.range.endOffset) return replacement
        var start = anchorEnd
        while (start < replacement.range.endOffset && (text[start] == ' ' || text[start] == '\t')) start++
        return VaultReplacement(TextRange(start, replacement.range.endOffset), replacement.text)
    }

    private fun overlaps(changes: List<VaultReplacement>): Boolean =
        changes.sortedBy { it.range.startOffset }.zipWithNext().any { (a, b) -> a.range.endOffset > b.range.startOffset }

    companion object {
        /** Makes mode 3 fail when it writes a file this returns true for (tests only: a failure midway restores the written files). */
        @Volatile
        @get:TestOnly
        @set:TestOnly
        var failWriteForTests: ((VirtualFile) -> Boolean)? = null

        private const val LF = "\n"

        /** The UTF-8 byte order mark (the platform's constant is internal API). */
        private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}
