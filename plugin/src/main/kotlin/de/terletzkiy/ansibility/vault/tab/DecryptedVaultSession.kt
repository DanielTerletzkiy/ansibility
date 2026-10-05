package de.terletzkiy.ansibility.vault.tab

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.TransactionGuard
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.undo.UndoUtil
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.SafeWriteRequestor
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.ui.EditorNotifications
import com.intellij.util.concurrency.ThreadingAssertions
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.VaultEncryptResult
import de.terletzkiy.ansibility.api.VaultFailure
import de.terletzkiy.ansibility.api.VaultHeaderInfo
import de.terletzkiy.ansibility.api.VaultOperations
import de.terletzkiy.ansibility.api.VaultUnlockResult
import de.terletzkiy.ansibility.semantics.vault.EncryptIdentity
import de.terletzkiy.ansibility.semantics.vault.EnvelopeParse
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.vault.VaultLog
import de.terletzkiy.ansibility.vault.crypto.VaultCrypto
import de.terletzkiy.ansibility.vault.crypto.Verification
import de.terletzkiy.ansibility.vault.identity.VaultIdentityRegistry
import de.terletzkiy.ansibility.vault.identity.VaultProjectSettings
import de.terletzkiy.ansibility.vault.secrets.VaultSecretsService
import de.terletzkiy.ansibility.vault.ui.VaultUiFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files

/** Why the edits of a decrypted tab are not in the real file; shown in the tab's banner. Carries no data. */
internal sealed interface TabProblem {
    /** Encrypting failed: locked, no id, a cancelled unlock. */
    class Failed(val failure: VaultFailure) : TabProblem {
        override fun toString(): String = "Failed($failure)"
    }

    /** The real file changed since it was decrypted or last saved: a save would overwrite that change. */
    data object ChangedOnDisk : TabProblem

    /** The real file is gone or cannot be read. */
    data object Gone : TabProblem
}

/**
 * One open decrypted tab (F7.8): the whole-file vault [original], the [DecryptedVaultFile] showing its plaintext, and
 * the save path chosen by spike S-V1.
 *
 * **Save.** Every save of the tab's document (Cmd+S, Save All, autosave, project close) first asks
 * [DecryptedVaultSaveVetoer], which calls [maySave]:
 * - text equal to the file's content (the plaintext last written) saves at once, and the platform writes nothing
 *   (`ansible-vault edit`'s "File not changed": no new salt churns into git or Local History);
 * - an implicit save is vetoed while "Encrypt only on explicit save" is on;
 * - otherwise the save is vetoed (the document stays unsaved, without an error) while the text is encrypted in the
 *   background with the secret of [identity], the id that decrypted the file, under [label], the label of the file's
 *   header ([EncryptIdentity.editLabel]: `ansible-vault edit` keeps "the same vault-id and version as in the header").
 *   The save then runs again on the EDT, the vetoer lets it pass, and [commit] writes the prepared envelope to
 *   [original] in the save's write action. Nothing cryptographic runs on the EDT or in a write action.
 *
 * [commit] refuses with an `IOException` (so the platform keeps the edit unsaved and says why) when the bytes are not
 * the ones that were encrypted, when [original] no longer holds what was decrypted or last saved
 * ([TabProblem.ChangedOnDisk]: another change is never overwritten), or when [original] has unsaved edits of its own.
 * The real file is written in place through the VFS, so its file mode is kept (D29: edit keeps the mode). An explicit
 * save may unlock the id lazily (a prompt); an implicit one never asks anything.
 *
 * Plaintext lives in the document, the file's content and, between encrypting and writing, in one prepared array that
 * is zeroed as soon as it is written or dropped. [dispose] wipes the document and the file and invalidates the file.
 */
internal class DecryptedVaultSession(
    val project: Project,
    private val scope: CoroutineScope,
    val original: VirtualFile,
    val root: AnsibleRoot,
    /** The id whose secret decrypted the file: every save encrypts with it. */
    val identity: String,
    /** The header label every save writes. */
    val label: String,
    /** The header the file had when it was decrypted (the banner shows it). */
    val header: VaultHeaderInfo,
    text: String,
    /** The bytes of [original] when it was decrypted: what a save may overwrite. Ciphertext, not secret. */
    base: ByteArray,
) : Disposable, DecryptedVaultFile.Owner {
    /** An envelope encrypted for the document at [stamp], waiting for the save that writes it. */
    private class Prepared(
        val stamp: Long,
        val plaintext: ByteArray,
        val envelope: ByteArray,
        val fingerprint: String,
        val rootKey: String,
        val labelMatches: Boolean,
    ) {
        fun zero() = plaintext.fill(0)

        override fun toString(): String = "Prepared($stamp, ***)"
    }

    /** What encrypting the text gave. */
    private sealed interface Outcome {
        class Ready(val prepared: Prepared) : Outcome

        class Problem(val problem: TabProblem) : Outcome
    }

    private val lock = Any()
    private var prepared: Prepared? = null
    private var preparingStamp: Long? = null

    @Volatile
    private var baseBytes: ByteArray = base

    val file: DecryptedVaultFile = DecryptedVaultFile(original, DecryptedVaultFile.fileTypeFor(original), text, this)

    /** The tab's document, created with the file so it is tracked from its first edit. */
    val document: Document = FileDocumentManager.getInstance().getDocument(file) ?: error("no document for ${file.name}")

    /** Why the last save did not reach the real file, or null. */
    @Volatile
    var problem: TabProblem? = null
        private set

    @Volatile
    var isDisposed: Boolean = false
        private set

    /** Set while [DecryptedVaultTabs] closes the tab itself (lock, project close), so closing it asks nothing more. */
    @Volatile
    var closingQuietly: Boolean = false

    init {
        // Editing a decrypted tab is vault activity: the idle lock counts from the last keystroke.
        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (!isDisposed) VaultSecretsService.getInstance(project).touch()
            }
        }, this)
    }

    /** True when the tab holds edits that are not in the real file. */
    val isModified: Boolean get() = !isDisposed && !StringUtil.equals(document.immutableCharSequence, file.content)

    // ------------------------------------------------------------------------------------------------ save

    /**
     * The vetoer's decision for a save of the tab's document ([explicit]: Cmd+S, Save All, project close; implicit:
     * autosave). True lets the save write; false keeps the document unsaved and starts the background encryption,
     * which repeats the save when it is done. Any thread: the platform may save from a background write action.
     */
    override fun maySave(explicit: Boolean): Boolean {
        if (isDisposed) return true // the platform drops an invalid file's document without writing anything
        val text = document.immutableCharSequence
        if (StringUtil.equals(text, file.content)) return true // unchanged: the platform compares and writes nothing
        if (!explicit && VaultProjectSettings.getInstance(project).encryptOnlyOnExplicitSave) return false
        val current = problem
        if (current == TabProblem.ChangedOnDisk || current == TabProblem.Gone) return false // the tab's banner resolves it
        val stamp = document.modificationStamp
        synchronized(lock) {
            prepared?.let { ready ->
                if (ready.stamp == stamp) return true
                ready.zero()
                prepared = null
            }
            if (preparingStamp == stamp) return false
            preparingStamp = stamp
        }
        scope.launch { prepareAndSave(stamp, text.toString(), explicit) }
        return false
    }

    /**
     * Encrypts the current text and saves it (the Save of the questions, the banner's Retry): true when the real file
     * holds the tab's text afterwards. [interactive] may unlock the id with a prompt. Never on the EDT.
     */
    suspend fun saveNow(interactive: Boolean): Boolean {
        val (stamp, text) = readAction { document.modificationStamp to document.immutableCharSequence.toString() }
        if (!isModified) return true
        return when (val outcome = encrypt(stamp, text, interactive)) {
            is Outcome.Problem -> {
                report(outcome.problem)
                false
            }
            is Outcome.Ready -> withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) { install(outcome.prepared) && !isModified }
        }
    }

    /** [saveNow] for an EDT caller that must wait for the answer (project close): the encryption runs under a modal progress. */
    fun saveBlocking(interactive: Boolean): Boolean {
        ThreadingAssertions.assertEventDispatchThread()
        if (!isModified) return true
        val stamp = document.modificationStamp
        val text = document.immutableCharSequence.toString()
        val outcome = runWithModalProgressBlocking(project, TabTexts.message("tab.progress.save", original.name)) {
            encrypt(stamp, text, interactive)
        }
        return when (outcome) {
            is Outcome.Problem -> {
                report(outcome.problem)
                false
            }
            is Outcome.Ready -> install(outcome.prepared) && !isModified
        }
    }

    /** Accepts the real file as it is now (the banner's Overwrite): the next save replaces it. Never on the EDT. */
    suspend fun acceptDiskVersion(): Boolean {
        val bytes = readOriginal() ?: return false
        baseBytes = bytes
        problem = null
        refreshBanner()
        return true
    }

    private suspend fun prepareAndSave(stamp: Long, text: String, explicit: Boolean) {
        val outcome = try {
            encrypt(stamp, text, interactive = explicit)
        } finally {
            synchronized(lock) { if (preparingStamp == stamp) preparingStamp = null }
        }
        when (outcome) {
            is Outcome.Problem -> {
                report(outcome.problem)
                val cancelled = (outcome.problem as? TabProblem.Failed)?.failure == VaultFailure.CANCELLED
                if (explicit && !cancelled) {
                    withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                        VaultUiFeedback.error(project, null, TabTexts.problem(outcome.problem, original.name))
                    }
                }
            }
            is Outcome.Ready -> withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) { install(outcome.prepared) }
        }
    }

    /** The prepared envelope for [text] at [stamp], or why there is none. Background only. */
    private suspend fun encrypt(stamp: Long, text: String, interactive: Boolean): Outcome {
        val onDisk = readOriginal() ?: return Outcome.Problem(TabProblem.Gone)
        if (!onDisk.contentEquals(baseBytes)) return Outcome.Problem(TabProblem.ChangedOnDisk)
        val plaintext = text.toByteArray(Charsets.UTF_8)
        var kept = false
        try {
            val operations = VaultOperations.getInstance(project)
            var result = operations.encrypt(root, plaintext, identity, original)
            if (interactive && result is VaultEncryptResult.Failed && result.failure == VaultFailure.LOCKED) {
                val verify = (VaultEnvelope.parse(baseBytes) as? EnvelopeParse.Ok)?.envelope
                when (val unlocked = VaultSecretsService.getInstance(project).unlock(root, identity, verify)) {
                    is VaultUnlockResult.Failed -> return Outcome.Problem(TabProblem.Failed(unlocked.failure))
                    is VaultUnlockResult.Unlocked -> result = operations.encrypt(root, plaintext, identity, original)
                }
            }
            val encrypted = when (result) {
                is VaultEncryptResult.Failed -> return Outcome.Problem(TabProblem.Failed(result.failure))
                is VaultEncryptResult.Encrypted -> result
            }
            val envelope = (VaultEnvelope.parse(encrypted.envelope) as? EnvelopeParse.Ok)?.envelope?.withLabel(label)
                ?: return Outcome.Problem(TabProblem.Failed(VaultFailure.FORMAT))
            val discovery = VaultIdentityRegistry.getInstance(project).discovery(root)
            val fingerprint = VaultCrypto.getInstance(project).fingerprint(envelope)
            val labelMatches = envelope.labelOrDefault(discovery.config.defaultIdentity) == identity
            kept = true
            return Outcome.Ready(Prepared(stamp, plaintext, envelope.formatBytes(), fingerprint, discovery.rootKey, labelMatches))
        } finally {
            if (!kept) plaintext.fill(0)
        }
    }

    /** Hands [ready] to the next save and runs it; true when the document is saved afterwards. EDT, write-safe. */
    private fun install(ready: Prepared): Boolean {
        if (isDisposed || document.modificationStamp != ready.stamp) {
            ready.zero()
            return false
        }
        synchronized(lock) {
            prepared?.zero()
            prepared = ready
        }
        val documents = FileDocumentManager.getInstance()
        documents.saveDocument(document)
        return !documents.isDocumentUnsaved(document)
    }

    /**
     * Writes the prepared envelope of [bytes] to [original], from the save's write action (the file's output stream).
     * Throws [IOException] instead of writing anything else.
     */
    override fun commit(file: DecryptedVaultFile, bytes: ByteArray, modificationStamp: Long) {
        ApplicationManager.getApplication().assertWriteAccessAllowed()
        val ready = synchronized(lock) {
            prepared?.takeIf { it.stamp == modificationStamp && it.plaintext.contentEquals(bytes) }?.also { prepared = null }
        } ?: throw IOException(TabTexts.message("tab.save.not.ready", original.name))
        try {
            if (!original.isValid) {
                problem = TabProblem.Gone
                throw IOException(TabTexts.problem(TabProblem.Gone, original.name))
            }
            if (!original.contentsToByteArray().contentEquals(baseBytes)) {
                problem = TabProblem.ChangedOnDisk
                throw IOException(TabTexts.problem(TabProblem.ChangedOnDisk, original.name))
            }
            val documents = FileDocumentManager.getInstance()
            documents.getCachedDocument(original)?.let { originalDocument ->
                if (documents.isDocumentUnsaved(originalDocument)) throw IOException(TabTexts.message("tab.save.original.unsaved", original.name))
            }
            original.setBinaryContent(ready.envelope, -1, -1, OriginalWriter)
            baseBytes = ready.envelope
            file.markSaved(String(bytes, Charsets.UTF_8), modificationStamp)
            problem = null
        } finally {
            ready.zero()
        }
        VaultLog.event(VaultLog.Operation.ENCRYPT, VaultLog.Event.ENCRYPTED, original.path)
        // Out of the save's write action: "decrypts with" stays verified for the new envelope (the banner and V104-V106).
        ApplicationManager.getApplication().invokeLater({
            if (VaultCrypto.getInstance(project).record(ready.rootKey, ready.fingerprint, Verification(identity, listOf(identity), ready.labelMatches))) {
                VaultSecretsService.getInstance(project).notifyChanged()
            }
            refreshBanner()
        }, project.disposed)
    }

    /** Records [problem] for the tab's banner; vault failures are logged by class. */
    private fun report(problem: TabProblem) {
        this.problem = problem
        if (problem is TabProblem.Failed) VaultLog.failure(VaultLog.Operation.ENCRYPT, problem.failure, original.path)
        refreshBanner()
    }

    private fun refreshBanner() {
        if (!project.isDisposed && !isDisposed) EditorNotifications.getInstance(project).updateNotifications(file)
    }

    /** The bytes of [original] on disk (in the VFS for a file of another file system), or null when it is gone. */
    private suspend fun readOriginal(): ByteArray? {
        val path = readAction { original.takeIf { it.isValid }?.let { it.fileSystem.getNioPath(it) } }
        if (path != null) {
            return withContext(Dispatchers.IO) {
                try {
                    Files.readAllBytes(path)
                } catch (_: IOException) {
                    null
                }
            }
        }
        return readAction {
            try {
                original.takeIf { it.isValid }?.contentsToByteArray()
            } catch (_: IOException) {
                null
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ lifecycle

    /**
     * Ends the tab: zeroes the prepared plaintext, wipes the document and the file, invalidates the file (so the
     * platform drops its document without writing it) and removes it from the editor history. EDT; through
     * `Disposer.dispose`, which also removes the document listener.
     */
    override fun dispose() {
        if (isDisposed) return
        isDisposed = true
        synchronized(lock) {
            prepared?.zero()
            prepared = null
            preparingStamp = null
        }
        val application = ApplicationManager.getApplication()
        application.runWriteAction {
            CommandProcessor.getInstance().runUndoTransparentAction {
                UndoUtil.disableUndoIn(document) { if (document.isWritable) document.setText("") }
            }
        }
        file.markSaved("", document.modificationStamp)
        file.isValid = false
        val documents = FileDocumentManager.getInstance()
        if (documents.isDocumentUnsaved(document)) {
            if (TransactionGuard.getInstance().isWritingAllowed) {
                documents.saveDocument(document)
            } else {
                application.invokeLater({ documents.saveDocument(document) }, ModalityState.nonModal())
            }
        }
        if (!project.isDisposed) EditorHistoryManager.getInstance(project).removeFile(file)
    }

    override fun toString(): String = "DecryptedVaultSession(${original.name}, id $identity)"

    /** The requestor of writes to [original]: not the document manager, so an open editor of it reloads; safe write. */
    private object OriginalWriter : SafeWriteRequestor
}
