package de.terletzkiy.ansibility.vault.tab

import com.intellij.codeInsight.inline.completion.InlineCompletion
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.ui.EditorNotifications
import com.intellij.util.concurrency.ThreadingAssertions
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VaultDecryptResult
import de.terletzkiy.ansibility.api.VaultFailure
import de.terletzkiy.ansibility.api.VaultHeaderInfo
import de.terletzkiy.ansibility.api.VaultLockState
import de.terletzkiy.ansibility.api.VaultOperations
import de.terletzkiy.ansibility.api.VaultPurpose
import de.terletzkiy.ansibility.api.VaultStatusService
import de.terletzkiy.ansibility.api.VaultUnlockResult
import de.terletzkiy.ansibility.semantics.vault.EncryptIdentity
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.vault.VaultCorpusGuardException
import de.terletzkiy.ansibility.vault.VaultLog
import de.terletzkiy.ansibility.vault.actions.VaultValueText
import de.terletzkiy.ansibility.vault.envelope.VaultEnvelopes
import de.terletzkiy.ansibility.vault.secrets.VaultSecretsService
import de.terletzkiy.ansibility.vault.ui.VaultUiFeedback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.jetbrains.annotations.TestOnly
import java.io.IOException

/**
 * The decrypted vault tabs of the project (F7.8): [open] behind the banner's [Open decrypted], and every rule of their
 * lifecycle (F7.13):
 * - decryption only on [open] ([VaultOperations.decrypt] with [VaultPurpose.OPEN_DECRYPTED], in the background; a locked
 *   root is unlocked lazily, with the typed password checked against the file); one tab per file, a second [open] shows
 *   it again. A binary plaintext (vector v07) gets a notice instead of an editor: it is never shown as text, so the
 *   file stays read-only in the IDE;
 * - the tab is red ([DecryptedVaultTabColor]), never in Recent Files (removed from [EditorHistoryManager] on open,
 *   selection and close), never restored at startup (a light file's URL resolves to nothing), and inline completion is
 *   removed from its editors ([InlineCompletion.remove]), so no completion provider receives the text;
 * - closing a tab with unsaved edits asks [Save] [Don't Save] [Cancel] ([DecryptedTabPrompts]; Cancel and a failed save
 *   open it again); closing ends the session (the file is invalidated, its document wiped);
 * - a lock of the tab's id (Lock all, the idle lock, a root's lock) closes the tab at once; unsaved edits then need an
 *   unlock to be encrypted, so the question is [Unlock and Save] [Discard Edits]. [closeAllBeforeLock] lets Lock all ask
 *   while the ids are still unlocked;
 * - the project closes only after unsaved edits were saved or discarded ([canCloseProject], a `projectCloseHandler`
 *   that runs before the platform's own save).
 *
 * Sessions are confined to the EDT; the vetoer reaches its session through the file.
 */
@Service(Service.Level.PROJECT)
class DecryptedVaultTabs(private val project: Project, private val scope: CoroutineScope) : Disposable {
    /** What [open] needs to know about a whole-file vault before decrypting it. Not secret. */
    private class Target(val root: AnsibleRoot, val envelope: VaultEnvelope, val header: VaultHeaderInfo, val label: String, val base: ByteArray)

    private val sessions = LinkedHashMap<VirtualFile, DecryptedVaultSession>()

    @Volatile
    private var lastJob: Job? = null

    /** The most recent background job (open, unlock, a save after a question), for tests that wait for it. */
    @get:TestOnly
    val lastAction: Job? get() = lastJob

    /** The open sessions, oldest first. EDT. */
    internal val openSessions: List<DecryptedVaultSession> get() = sessions.values.filter { !it.isDisposed }

    /** The session of [original]'s tab, or null. EDT. */
    internal fun sessionOf(original: VirtualFile): DecryptedVaultSession? = sessions[original]?.takeUnless { it.isDisposed }

    /** The session behind [file], or null. EDT. */
    internal fun sessionOf(file: DecryptedVaultFile): DecryptedVaultSession? = sessionOf(file.original)?.takeIf { it.file === file }

    // ------------------------------------------------------------------------------------------------ open

    /** [Open decrypted]: decrypts the whole-file vault [original] in the background and opens it in a tab. EDT. */
    fun open(original: VirtualFile) {
        ThreadingAssertions.assertEventDispatchThread()
        sessionOf(original)?.let { session ->
            show(session)
            return
        }
        launch(TabTexts.message("tab.progress.open", original.name), original) { decryptAndOpen(original) }
    }

    /**
     * The banner's [Unlock…]: unlocks the ids of [original]'s root (consent, password sources, prompts) and decrypts
     * nothing. Being explicit, it asks again for a root declined with Not now earlier in the session.
     */
    fun unlock(original: VirtualFile) {
        ThreadingAssertions.assertEventDispatchThread()
        launch(TabTexts.message("progress.unlock"), original) {
            val root = readAction { AnsibleWorkspace.getInstance(project).rootFor(original) } ?: return@launch
            VaultSecretsService.getInstance(project).forgetDeclined(root)
            when (val result = VaultOperations.getInstance(project).unlock(root)) {
                is VaultUnlockResult.Unlocked -> onEdt { VaultUiFeedback.info(project, null, TabTexts.message("action.unlocked", result.identities.joinToString(", "))) }
                is VaultUnlockResult.Failed -> if (result.failure != VaultFailure.CANCELLED) onEdt { VaultUiFeedback.failure(project, null, result.failure) }
            }
        }
    }

    /** The tab banner's [Retry]: encrypts and saves the tab's text again, asking for an unlock when needed. EDT. */
    internal fun retrySave(file: DecryptedVaultFile) {
        val session = sessionOf(file) ?: return
        launch(TabTexts.message("tab.progress.save", session.original.name), session.original) { session.saveNow(interactive = true) }
    }

    /** The tab banner's [Overwrite]: the real file changed on disk, and the next save replaces that change. EDT. */
    internal fun overwriteDiskVersion(file: DecryptedVaultFile) {
        val session = sessionOf(file) ?: return
        launch(TabTexts.message("tab.progress.save", session.original.name), session.original) {
            if (session.acceptDiskVersion()) session.saveNow(interactive = true)
        }
    }

    private suspend fun decryptAndOpen(original: VirtualFile) {
        val target = readAction { target(original) }
        if (target == null) {
            onEdt { VaultUiFeedback.failure(project, null, VaultFailure.FORMAT) }
            return
        }
        when (val result = decryptUnlocking(original, target)) {
            is VaultDecryptResult.Failed -> if (result.failure != VaultFailure.CANCELLED) onEdt { VaultUiFeedback.failure(project, null, result.failure) }
            is VaultDecryptResult.Decrypted -> result.plaintext.use { plaintext ->
                val text = plaintext.read { VaultValueText.decode(it) }
                if (text == null) {
                    onEdt {
                        original.putUserData(BINARY_PLAINTEXT, true)
                        EditorNotifications.getInstance(project).updateNotifications(original)
                        VaultUiFeedback.info(project, null, TabTexts.message("tab.binary", original.name, plaintext.size))
                    }
                    return
                }
                // The tab may only ever write over the envelope it decrypted.
                val unchanged = readAction { original.isValid && contentOf(original)?.contentEquals(target.base) == true }
                if (!unchanged) {
                    onEdt { VaultUiFeedback.error(project, null, TabTexts.message("tab.changed.while.decrypting", original.name)) }
                    return
                }
                onEdt { openSession(original, target, result.identity, text) }
            }
        }
    }

    /** Decrypts [original]; a locked root is unlocked first (consent, password safe, a prompt checked against the file). */
    private suspend fun decryptUnlocking(original: VirtualFile, target: Target): VaultDecryptResult {
        val operations = VaultOperations.getInstance(project)
        val location = SourceLocation(original, 0)
        val first = operations.decrypt(location, VaultPurpose.OPEN_DECRYPTED)
        if (first !is VaultDecryptResult.Failed || first.failure != VaultFailure.LOCKED) return first
        val unlocked = VaultSecretsService.getInstance(project).unlock(target.root, label = null, verify = target.envelope)
        if (unlocked is VaultUnlockResult.Failed) return VaultDecryptResult.Failed(unlocked.failure, first.tried)
        return operations.decrypt(location, VaultPurpose.OPEN_DECRYPTED)
    }

    /** The facts [open] needs, or null for a file that is no well-formed whole-file vault of a root. Read action. */
    private fun target(original: VirtualFile): Target? {
        if (!original.isValid || !VaultEnvelopes.isWholeFileVault(original)) return null
        val located = VaultEnvelopes.ofFile(original) ?: return null
        val envelope = located.envelope ?: return null
        val root = AnsibleWorkspace.getInstance(project).rootFor(original) ?: return null
        val base = contentOf(original) ?: return null
        val defaultIdentity = VaultStatusService.getInstance(project).config(root).defaultIdentity
        return Target(root, envelope, located.header, EncryptIdentity.editLabel(envelope, defaultIdentity), base)
    }

    private fun openSession(original: VirtualFile, target: Target, identity: String, text: String) {
        if (project.isDisposed || !original.isValid) return
        sessionOf(original)?.let { session ->
            show(session)
            return
        }
        val session = DecryptedVaultSession(project, scope, original, target.root, identity, target.label, target.header, text, target.base)
        Disposer.register(this, session)
        sessions[original] = session
        VaultLog.event(VaultLog.Operation.DECRYPT, VaultLog.Event.DECRYPTED, original.path)
        show(session)
    }

    private fun show(session: DecryptedVaultSession) {
        FileEditorManager.getInstance(project).openFile(session.file, true)
        harden(session.file)
    }

    /** Keeps [file] out of the editor history and inline completion away from its editors. EDT. */
    internal fun harden(file: DecryptedVaultFile) {
        for (editor in FileEditorManager.getInstance(project).getAllEditors(file)) {
            (editor as? TextEditor)?.editor?.let { InlineCompletion.remove(it) }
        }
        EditorHistoryManager.getInstance(project).removeFile(file)
    }

    // ------------------------------------------------------------------------------------------------ close

    /** A decrypted tab was closed; asks about unsaved edits once the close is done. EDT. */
    internal fun fileClosed(file: DecryptedVaultFile) {
        val session = sessionOf(file) ?: return
        if (FileEditorManager.getInstance(project).isFileOpen(file)) return
        EditorHistoryManager.getInstance(project).removeFile(file)
        if (session.closingQuietly) return
        if (!session.isModified) {
            end(session)
            return
        }
        ApplicationManager.getApplication().invokeLater({ askAfterClose(session) }, ModalityState.nonModal(), project.disposed)
    }

    private fun askAfterClose(session: DecryptedVaultSession) {
        if (session.isDisposed || FileEditorManager.getInstance(project).isFileOpen(session.file)) return
        if (!session.isModified) {
            end(session)
            return
        }
        when (DecryptedTabPrompts.getInstance().askUnsaved(project, UnsavedTabsRequest(UnsavedTabsReason.CLOSE, listOf(session.file.name)))) {
            UnsavedTabsChoice.SAVE -> launch(TabTexts.message("tab.progress.save", session.original.name), session.original) {
                val saved = session.saveNow(interactive = true)
                onEdt { if (saved) end(session) else show(session) }
            }
            UnsavedTabsChoice.DISCARD -> end(session)
            UnsavedTabsChoice.CANCEL -> show(session)
        }
    }

    /** Ends [session] and forgets it. EDT. */
    private fun end(session: DecryptedVaultSession) {
        sessions.remove(session.original, session)
        Disposer.dispose(session)
    }

    // ------------------------------------------------------------------------------------------------ lock

    /** The vault status changed ([VaultTabStatusListener]): tabs whose id is locked now close. EDT. */
    internal fun lockStateChanged() {
        val locked = openSessions.filter { !it.closingQuietly && !isUnlocked(it) }
        if (locked.isEmpty()) return
        val editors = FileEditorManager.getInstance(project)
        for (session in locked) {
            session.closingQuietly = true
            editors.closeFile(session.file)
        }
        val (unsaved, clean) = locked.partition { it.isModified }
        clean.forEach(::end)
        if (unsaved.isNotEmpty()) resolveLocked(unsaved)
    }

    /** [Unlock and Save] until every edit is saved or you discard them: a locked id's tab never opens again. */
    private fun resolveLocked(unsaved: List<DecryptedVaultSession>) {
        val pending = unsaved.filter { !it.isDisposed }
        if (pending.isEmpty()) return
        val request = UnsavedTabsRequest(UnsavedTabsReason.LOCKED, pending.map { it.file.name })
        if (DecryptedTabPrompts.getInstance().askUnsaved(project, request) != UnsavedTabsChoice.SAVE) {
            pending.forEach(::end)
            return
        }
        launch(TabTexts.message("tab.progress.save", pending.first().original.name), pending.first().original) {
            val failed = pending.filterNot { it.saveNow(interactive = true) }
            onEdt {
                pending.filter { it !in failed }.forEach(::end)
                resolveLocked(failed)
            }
        }
    }

    /**
     * For Lock all, before it locks: asks about unsaved edits while the ids can still encrypt them ([Save and Lock]
     * [Discard and Lock] [Cancel]), then closes every decrypted tab. False when you cancelled or a save failed (the tab
     * then stays open and says why): the caller should not lock. EDT.
     */
    fun closeAllBeforeLock(): Boolean {
        ThreadingAssertions.assertEventDispatchThread()
        val open = openSessions
        if (open.isEmpty()) return true
        val unsaved = open.filter { it.isModified }
        if (unsaved.isNotEmpty()) {
            when (DecryptedTabPrompts.getInstance().askUnsaved(project, UnsavedTabsRequest(UnsavedTabsReason.BEFORE_LOCK, unsaved.map { it.file.name }))) {
                UnsavedTabsChoice.CANCEL -> return false
                UnsavedTabsChoice.SAVE -> if (unsaved.any { !it.saveBlocking(interactive = true) }) return false
                UnsavedTabsChoice.DISCARD -> Unit
            }
        }
        closeQuietly(open)
        return true
    }

    /** Project close: unsaved edits are saved or discarded first ([DecryptedTabsCloseHandler]). False keeps the project open. EDT. */
    internal fun canCloseProject(): Boolean {
        val open = openSessions
        val unsaved = open.filter { it.isModified }
        if (unsaved.isNotEmpty()) {
            when (DecryptedTabPrompts.getInstance().askUnsaved(project, UnsavedTabsRequest(UnsavedTabsReason.PROJECT_CLOSE, unsaved.map { it.file.name }))) {
                UnsavedTabsChoice.CANCEL -> return false
                UnsavedTabsChoice.SAVE -> if (unsaved.any { !it.saveBlocking(interactive = true) }) return false
                UnsavedTabsChoice.DISCARD -> Unit
            }
        }
        closeQuietly(open)
        return true
    }

    /**
     * Before a file action (V6b) rewrites [original] in place: its tab would save over the new content with the old
     * envelope's id, so it closes. True when there is no tab or it was closed; false when it has unsaved edits, which
     * stay (the caller leaves the file alone and says why). EDT.
     */
    fun closeBeforeRewrite(original: VirtualFile): Boolean {
        ThreadingAssertions.assertEventDispatchThread()
        val session = sessionOf(original) ?: return true
        if (session.isModified) return false
        closeQuietly(listOf(session))
        return true
    }

    private fun closeQuietly(sessions: List<DecryptedVaultSession>) {
        val editors = FileEditorManager.getInstance(project)
        for (session in sessions) {
            session.closingQuietly = true
            editors.closeFile(session.file)
            end(session)
        }
    }

    /** True while the id that decrypted [session]'s file is unlocked. */
    private fun isUnlocked(session: DecryptedVaultSession): Boolean =
        VaultStatusService.getInstance(project).config(session.root).identities.any { it.label == session.identity && it.lockState == VaultLockState.UNLOCKED }

    // ------------------------------------------------------------------------------------------------ helpers

    /** Runs [block] in the background with progress; unexpected errors are logged by class and reported. */
    private fun launch(title: String, file: VirtualFile, block: suspend CoroutineScope.() -> Unit) {
        lastJob = scope.launch {
            try {
                withBackgroundProgress(project, title) { block() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: VaultCorpusGuardException) {
                throw e
            } catch (e: Exception) {
                VaultLog.failure(VaultLog.Operation.DECRYPT, e, file.path)
                onEdt { VaultUiFeedback.failure(project, null, VaultFailure.SOURCE_UNAVAILABLE) }
            }
        }
    }

    private suspend fun <T> onEdt(block: () -> T): T = withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) { block() }

    private fun contentOf(file: VirtualFile): ByteArray? = try {
        file.contentsToByteArray()
    } catch (_: IOException) {
        null
    }

    /** Project close: every session ends with the service (they are its disposable children). */
    override fun dispose() {
        sessions.clear()
    }

    companion object {
        /** Set on a whole-file vault whose plaintext turned out binary (vector v07): the banner says it stays read-only. Not secret. */
        internal val BINARY_PLAINTEXT: Key<Boolean> = Key.create("ansibility.vault.binaryPlaintext")

        fun getInstance(project: Project): DecryptedVaultTabs = project.service()
    }
}
