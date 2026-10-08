package de.terletzkiy.ansibility.vault.actions

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadConstraint
import com.intellij.openapi.application.constrainedReadAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.BasicUndoableAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.ex.DocumentEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VFileProperty
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.ui.EditorNotifications
import com.intellij.util.concurrency.ThreadingAssertions
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VaultDecryptResult
import de.terletzkiy.ansibility.api.VaultEncryptResult
import de.terletzkiy.ansibility.api.VaultFailure
import de.terletzkiy.ansibility.api.VaultLockState
import de.terletzkiy.ansibility.api.VaultOperations
import de.terletzkiy.ansibility.api.VaultPurpose
import de.terletzkiy.ansibility.api.VaultRootConfig
import de.terletzkiy.ansibility.api.VaultStatusService
import de.terletzkiy.ansibility.api.VaultUnlockResult
import de.terletzkiy.ansibility.semantics.vault.EncryptIdentity
import de.terletzkiy.ansibility.semantics.vault.EnvelopeParse
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.vault.AnsibilityVaultBundle
import de.terletzkiy.ansibility.vault.VaultCorpusGuardException
import de.terletzkiy.ansibility.vault.VaultLog
import de.terletzkiy.ansibility.semantics.vault.VaultShapeKind
import de.terletzkiy.ansibility.vault.envelope.VaultEnvelopes
import de.terletzkiy.ansibility.vault.envelope.WholeFileShapes
import de.terletzkiy.ansibility.vault.identity.SecretPlan
import de.terletzkiy.ansibility.vault.identity.VaultIdentityRegistry
import de.terletzkiy.ansibility.vault.secrets.VaultSecretsService
import de.terletzkiy.ansibility.vault.tab.DecryptedVaultTabs
import de.terletzkiy.ansibility.vault.ui.AnsibilityVaultUiBundle.message
import de.terletzkiy.ansibility.vault.ui.VaultUiFeedback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.TestOnly
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.psi.YAMLScalar
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * The file operations of the vault (V6b of plan amendment R7–R8: F7.5, F7.6, F7.8): encrypt and decrypt files in
 * place, rekey and change the id of every vault (whole-file vaults and the `!vault` values of YAML files) in files,
 * folders and roots, and new vault files.
 *
 * - Each operation starts on the EDT, finds its files in a background read action (folders recursively, hidden,
 *   ignored and excluded files left out), asks its questions on the EDT and runs its crypto in a coroutine of this
 *   service with progress and Cancel; a summary names what was done, every failure by file and what was skipped, also
 *   after Cancel (what was written stays written).
 * - One undoable command per file, all or nothing per file: a file that changed meanwhile, or whose decrypted tab has
 *   unsaved edits, is left alone and named in the summary. Text goes through the file's document; when the saved bytes
 *   are not exactly what ansible-vault would write (a charset, the trailing-space stripper), the exact bytes follow in
 *   the same command. Undo restores the text, the byte order mark and the line separator (after exact bytes the
 *   platform reloads the document as an external change, which ends its undo history); a write that fails puts the old
 *   text back into the document.
 * - A `!vault` value is decrypted where its smart pointer finds it right before the decrypt, and only while its
 *   document does not change, so an edit above it never hands one value's plaintext to another.
 * - The vault id: for encrypting, as for a value ([VaultValueActions.encryptChoice]: the environment's id, Ansible's
 *   rule), asked once per root when that is ambiguous; for rekey and change id, chosen per root with a preview of what
 *   the root's part of the selection holds. A root is unlocked at most once per run: after a failed or cancelled
 *   unlock its other files fail at once, and the summary names what you cancelled or declined.
 * - File modes (D29, ansible-vault's): encrypting and decrypting in place write 0600, set before the first byte is
 *   written (a file whose mode cannot be set is not written); rekey and change id keep the mode.
 * - Never encrypted: a file that is a password source of its root (`.vault-pass`, a script, `.env.local`), and a file
 *   that already holds an envelope Ansible does not see (ANS-V107, plan amendment R21: a `!vault |` line, a preamble,
 *   indentation or a byte order mark before `$ANSIBLE_VAULT`): encrypting it would wrap the envelope a second time and
 *   hide the defect, so the summary points to Convert to whole-file vault instead.
 *
 * Plaintext lives in short-lived arrays that are zeroed when done; only Decrypt File in Place, after its confirmation,
 * writes it, and the undo stack of an encrypted file holds its old text (in memory, as for any edit).
 */
@Service(Service.Level.PROJECT)
class VaultFileOperations(private val project: Project, private val scope: CoroutineScope) {
    @Volatile
    private var lastJob: Job? = null

    @Volatile
    private var modes: FileModes = FileModes.POSIX

    /** The running or last operation, for tests that wait for it. */
    @get:TestOnly
    val lastOperation: Job? get() = lastJob

    /** Sets file modes: ansible-vault's 0600 on a POSIX file system, nothing elsewhere. Tests replace it. */
    fun interface FileModes {
        @Throws(IOException::class)
        fun makePrivate(path: Path)

        companion object {
            val POSIX: FileModes = FileModes { path ->
                if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
            }
        }
    }

    /** Replaces the file modes until [parent] is disposed. */
    @TestOnly
    fun setModesForTests(modes: FileModes, parent: Disposable) {
        this.modes = modes
        Disposer.register(parent) { this.modes = FileModes.POSIX }
    }

    // ------------------------------------------------------------------------------------------------ selection

    /**
     * The files of [selection]: files as they are, folders with their files below (hidden, ignored and excluded files
     * and folders and symbolic links left out). Read action.
     */
    fun filesOf(selection: List<VirtualFile>): List<VirtualFile> {
        val index = ProjectFileIndex.getInstance(project)
        val types = FileTypeManager.getInstance()
        val files = LinkedHashSet<VirtualFile>()
        for (item in selection) {
            if (!item.isValid || !item.isInLocalFileSystem) continue
            if (!item.isDirectory) {
                files += item
                continue
            }
            VfsUtilCore.iterateChildrenRecursively(
                item,
                { it == item || !(it.name.startsWith(".") || it.`is`(VFileProperty.SYMLINK) || types.isFileIgnored(it) || index.isExcluded(it)) },
                { file ->
                    ProgressManager.checkCanceled()
                    if (!file.isDirectory) files += file
                    true
                },
            )
        }
        return files.toList()
    }

    /**
     * True when [file] is a password source of [root]: a password file, a script, `.env.local` or the file it names.
     * Never encrypted here; ANS-V108 reports one under version control (plan amendment R21, D161). Reads no content:
     * the discovery is cached, and the file system is asked for the real path only when a source has the file's name
     * or the file is a symbolic link. Read action.
     */
    fun isPasswordSource(root: AnsibleRoot, file: VirtualFile): Boolean {
        val discovery = VaultIdentityRegistry.getInstance(project).discovery(root)
        val sources = discovery.consentTargets.flatMap { listOfNotNull(it.path, it.declared) } +
            discovery.identities.mapNotNull { (it.plan as? SecretPlan.Script)?.path }
        return isOneOf(file, sources)
    }

    /**
     * True when [file] is a vault password *file* of [root] (ANS-V108, plan amendment R21, D161): a configured or
     * conventional password file, or the file `.env.local` is expected to name; not a password script or client script
     * (code that fetches the password, which may well be committed) and not `.env.local` itself (it names the file).
     * Reads no content. Read action.
     */
    fun isPasswordFile(root: AnsibleRoot, file: VirtualFile): Boolean = isOneOf(file, passwordFiles(root))

    /** The paths of [root]'s vault password files ([isPasswordFile]), as configured and as real paths. Read action. */
    fun passwordFiles(root: AnsibleRoot): List<Path> {
        val discovery = VaultIdentityRegistry.getInstance(project).discovery(root)
        return discovery.identities.flatMap { identity ->
            val target = when (val plan = identity.plan) {
                is SecretPlan.PasswordFile -> plan.target
                is SecretPlan.EnvLocal -> plan.expected
                else -> null
            }
            listOfNotNull(target?.path, target?.declared)
        }.distinct()
    }

    /** True when [file] is one of [sources] (by its path, or its real path when a source has its name or it is a link). */
    private fun isOneOf(file: VirtualFile, sources: List<Path>): Boolean {
        if (sources.isEmpty()) return false
        val path = file.toNioPathOrNull()?.toAbsolutePath()?.normalize() ?: return false
        // The real path keeps the file's name unless the file itself is a link: skip the I/O for every other file.
        if (sources.none { it.fileName?.toString() == file.name } && !file.`is`(VFileProperty.SYMLINK)) return false
        val real = try {
            path.toRealPath()
        } catch (_: IOException) {
            path
        }
        return sources.any { source -> source.toAbsolutePath().normalize().let { it == path || it == real } }
    }

    // ------------------------------------------------------------------------------------------------ encrypt

    /** Ansibility Vault › Encrypt File: encrypts the plaintext files of [selection] in place (0600). EDT. */
    fun encrypt(selection: List<VirtualFile>) {
        ThreadingAssertions.assertEventDispatchThread()
        saveDocuments(selection)
        launch(message("file.progress.encrypt")) {
            val skipped = ArrayList<String>()
            val planned = ArrayList<EncryptPlan>()
            for ((file, root, skip) in readAction { scanForEncrypt(selection) }) {
                if (root == null || skip != null) {
                    skipped += skip ?: message("file.skipped.no.root", file.name)
                    continue
                }
                // The environment's id or Ansible's rule, without asking yet (the neighbours' labels need PSI).
                val config = readAction { VaultStatusService.getInstance(project).config(root) }
                val choices = choices(config)
                val choice = if (choices.isEmpty()) EncryptIdentity.Choice.NoIdentity else readAction {
                    val environments = AnsibleWorkspace.getInstance(project).contextOf(file)?.environments.orEmpty()
                    VaultValueActions.getInstance(project).encryptChoice(root, environments, file, config, choices.map { it.label })
                }
                if (choice is EncryptIdentity.Choice.NotFound || choice == EncryptIdentity.Choice.NoIdentity) {
                    skipped += message("file.skipped.no.identity", file.name)
                    continue
                }
                planned += EncryptPlan(file, root, choice, choices)
            }
            if (planned.isEmpty()) return@launch onEdt { finishNothing(message("file.encrypt.none"), skipped) }
            val labels = onEdt { encryptLabels(planned) } ?: return@launch
            val unlocks = Unlocks()
            runBatch(Batch(skipped, history = true) { message("file.encrypt.done", it.size, it.joinToString(", ")) }) { batch ->
                for (plan in planned) {
                    currentCoroutineContext().ensureActive()
                    val label = labels.getValue(plan.file)
                    batch.record(encryptFile(plan.file, plan.root, label, unlocks), plan.file) { message("file.name.with.id", plan.file.name, label) }
                }
            }
        }
    }

    /** A file to encrypt, its root and the id choice Ansible's rule made for it. */
    private class EncryptPlan(val file: VirtualFile, val root: AnsibleRoot, val choice: EncryptIdentity.Choice, val choices: List<VaultIdentityChoice>)

    /** A file of [scanForEncrypt]: to encrypt (its root), or skipped (why). */
    private data class Scanned(val file: VirtualFile, val root: AnsibleRoot?, val skipped: String?)

    /**
     * The files of [selection] to encrypt, and the ones skipped: outside a root, already encrypted, a password source,
     * holding an envelope that is not a whole-file vault (ANS-V107, judged on the file's first bytes). Read action.
     */
    private fun scanForEncrypt(selection: List<VirtualFile>): List<Scanned> {
        val workspace = AnsibleWorkspace.getInstance(project)
        return filesOf(selection).map { file ->
            ProgressManager.checkCanceled()
            val root = workspace.rootFor(file)
            when {
                root == null -> Scanned(file, null, message("file.skipped.no.root", file.name))
                VaultEnvelopes.isWholeFileVault(file) -> Scanned(file, root, message("file.skipped.encrypted", file.name))
                isPasswordSource(root, file) -> Scanned(file, root, message("file.skipped.password", file.name))
                else -> wrappedVault(file)?.let { Scanned(file, root, it) } ?: Scanned(file, root, null)
            }
        }
    }

    /** Why [file] is skipped when its head is a wrapped vault (ANS-V107), or null. Read action. */
    private fun wrappedVault(file: VirtualFile): String? {
        val shape = WholeFileShapes.ofHead(project, file) ?: return null
        return when {
            !shape.kind.isWrapped -> null
            shape.kind == VaultShapeKind.BYTE_ORDER_MARK -> message("file.skipped.byte.order.mark", file.name)
            else -> message("file.skipped.not.whole.file", file.name)
        }
    }

    /**
     * The questions before encrypting: the files when there are several, then the id of every root where Ansible's rule
     * leaves a choice (once per root). The id of every file, or null when you cancelled. EDT.
     */
    private fun encryptLabels(planned: List<EncryptPlan>): Map<VirtualFile, String>? {
        val prompts = VaultActionPrompts.getInstance()
        if (planned.size > 1 && !prompts.confirmFiles(project, FileOperation.ENCRYPT, planned.map { it.file.name })) return null
        val asked = HashMap<AnsibleRoot, String>()
        val labels = LinkedHashMap<VirtualFile, String>()
        for (plan in planned) {
            labels[plan.file] = when (val choice = plan.choice) {
                is EncryptIdentity.Choice.Chosen -> choice.label
                is EncryptIdentity.Choice.Ambiguous -> asked[plan.root] ?: prompts.chooseIdentity(
                    project,
                    VaultIdentityRequest(message("file.encrypt.title"), message("file.encrypt.choose", plan.root.displayName), plan.choices, choice.preselected),
                )?.also { asked[plan.root] = it } ?: return null
                is EncryptIdentity.Choice.NotFound, EncryptIdentity.Choice.NoIdentity -> continue
            }
        }
        return labels
    }

    /** Encrypts [file] with [label]'s secret and writes the envelope over it, provided it did not change meanwhile. */
    private suspend fun encryptFile(file: VirtualFile, root: AnsibleRoot, label: String, unlocks: Unlocks): FileOutcome {
        ensureUnlocked(root, label, unlocks)?.let { return FileOutcome.Failed(it) }
        val stamp = readAction { file.modificationStamp }
        val bytes = try {
            withContext(Dispatchers.IO) { file.contentsToByteArray() }
        } catch (e: IOException) {
            VaultLog.failure(VaultLog.Operation.ENCRYPT, e, file.path)
            return FileOutcome.Failed(VaultFailure.SOURCE_UNAVAILABLE)
        }
        try {
            return when (val result = VaultOperations.getInstance(project).encrypt(root, bytes, label, file)) {
                is VaultEncryptResult.Failed -> FileOutcome.Failed(result.failure)
                is VaultEncryptResult.Encrypted -> onEdt {
                    write(file, stamp, Content.Envelope(result.envelope), message(FileOperation.ENCRYPT.commandKey), private = true)
                }
            }
        } finally {
            bytes.fill(0)
        }
    }

    // ------------------------------------------------------------------------------------------------ decrypt

    /** Decrypt File in Place: decrypts the whole-file vaults of [selection] in place (0600), after a confirmation. EDT. */
    fun decrypt(selection: List<VirtualFile>) {
        ThreadingAssertions.assertEventDispatchThread()
        saveDocuments(selection)
        launch(message("file.progress.decrypt")) {
            val (planned, skipped) = readAction { vaultFiles(filesOf(selection)) }
            if (planned.isEmpty()) return@launch onEdt { finishNothing(message("file.decrypt.none"), skipped) }
            if (!onEdt { VaultActionPrompts.getInstance().confirmFiles(project, FileOperation.DECRYPT, planned.map { it.first.name }) }) return@launch
            val unlocks = Unlocks()
            runBatch(Batch(skipped) { message("file.decrypt.done", it.size, it.joinToString(", ")) }) { batch ->
                for ((file, root) in planned) {
                    currentCoroutineContext().ensureActive()
                    val outcome = withWholeFile(file, root, FileOperation.DECRYPT.purpose, unlocks) { stamp, _, plaintext, _ ->
                        onEdt { write(file, stamp, Content.Plain(plaintext), message(FileOperation.DECRYPT.commandKey), private = true) }
                    }
                    batch.record(outcome, file) { file.name }
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ rekey, change id

    /** Rekey…: encrypts every vault in [selection] (whole files and `!vault` values) again with a chosen id's secret. EDT. */
    fun rekey(selection: List<VirtualFile>) = reencrypt(selection, FileOperation.REKEY)

    /**
     * Change Id…: labels every vault in [selection] with a chosen id: only the header where that id's secret decrypts
     * it (labels are not covered by the HMAC, so this always decrypts first), a full rekey for the others after one
     * question per id that decrypts them. EDT.
     */
    fun changeId(selection: List<VirtualFile>) = reencrypt(selection, FileOperation.CHANGE_ID)

    private fun reencrypt(selection: List<VirtualFile>, operation: FileOperation) {
        ThreadingAssertions.assertEventDispatchThread()
        saveDocuments(selection)
        val rekey = operation == FileOperation.REKEY
        launch(message(if (rekey) "file.progress.rekey" else "file.progress.change.id")) {
            val (byRoot, scanSkipped) = readAction { scanForReencrypt(selection) }
            val skipped = scanSkipped.toMutableList()
            if (byRoot.isEmpty()) return@launch onEdt { finishNothing(message("file.reencrypt.none"), skipped) }
            val targets = onEdt { reencryptTargets(byRoot, operation, skipped) } ?: return@launch
            val run = Reencryption(operation)
            val ids = targets.values.distinct().joinToString(", ")
            runBatch(Batch(skipped) { message(if (rekey) "file.rekey.done" else "file.change.id.done", it.size, ids, it.joinToString(", ")) }) { batch ->
                for ((root, target) in targets) {
                    for (item in byRoot.getValue(root)) {
                        currentCoroutineContext().ensureActive()
                        val outcome = run.unlocks.ids[root to target]?.let { FileOutcome.Failed(it) } ?: when (item) {
                            is Target.WholeFile -> reencryptWholeFile(item.file, root, target, run)
                            is Target.Values -> reencryptValues(item.file, root, target, run)
                        }
                        batch.record(outcome, item.file) { item.file.name }
                    }
                }
            }
        }
    }

    /** The target id of every root, asked with a preview of what the root's part of the selection holds; null when you cancelled. EDT. */
    private fun reencryptTargets(byRoot: Map<AnsibleRoot, List<Target>>, operation: FileOperation, skipped: MutableList<String>): Map<AnsibleRoot, String>? {
        val targets = LinkedHashMap<AnsibleRoot, String>()
        for ((root, items) in byRoot) {
            val choices = choices(runReadActionBlocking { VaultStatusService.getInstance(project).config(root) })
            if (choices.isEmpty()) {
                skipped += message("file.skipped.root.no.identity", root.displayName)
                continue
            }
            val rekey = operation == FileOperation.REKEY
            val title = message(if (rekey) "file.rekey.title" else "file.change.id.title")
            val text = message(if (rekey) "file.rekey.choose" else "file.change.id.choose", preview(items), root.displayName)
            targets[root] = VaultActionPrompts.getInstance().chooseIdentity(project, VaultIdentityRequest(title, text, choices, null)) ?: return null
        }
        return targets
    }

    /** "1 vault file, 2 !vault values in 1 YAML file": the preview of one root's part of a selection. */
    @Nls
    private fun preview(items: List<Target>): String {
        val files = items.count { it is Target.WholeFile }
        val values = items.filterIsInstance<Target.Values>()
        return listOfNotNull(
            message("file.preview.files", files).takeIf { files > 0 },
            message("file.preview.values", values.sumOf { it.count }, values.size).takeIf { values.isNotEmpty() },
        ).joinToString(", ")
    }

    /** The answers and unlocks of one rekey or change id run (on its coroutine). */
    private class Reencryption(val operation: FileOperation) {
        val relabelOnly: Boolean get() = operation == FileOperation.CHANGE_ID

        /**
         * Change id: re-encrypt what the target's secret does not decrypt? By root and the id that does, asked once each:
         * the same id in another root is another secret, and that root has its own target.
         */
        val agreed = HashMap<Pair<AnsibleRoot, String>, Boolean>()

        /** A root whose target id could not be unlocked (or whose unlock you cancelled): its other files are not tried. */
        val unlocks = Unlocks()
    }

    private suspend fun reencryptWholeFile(file: VirtualFile, root: AnsibleRoot, target: String, run: Reencryption): FileOutcome =
        withWholeFile(file, root, run.operation.purpose, run.unlocks) { stamp, envelope, plaintext, identity ->
            when (val next = newEnvelope(run, root, file, envelope, plaintext, identity, target)) {
                is NewEnvelope.Ready -> onEdt { write(file, stamp, Content.Envelope(next.text), message(run.operation.commandKey), private = false) }
                NewEnvelope.Same -> FileOutcome.Unchanged
                NewEnvelope.Declined -> FileOutcome.Cancelled
                is NewEnvelope.Failed -> FileOutcome.Failed(next.failure)
            }
        }

    /** All `!vault` values of [file] to [target]: decrypted and encrypted first, then written in one command (or not at all). */
    private suspend fun reencryptValues(file: VirtualFile, root: AnsibleRoot, target: String, run: Reencryption): FileOutcome {
        val refs = readAction { PsiManager.getInstance(project).findFile(file)?.let(VaultValuePsi::vaultScalars).orEmpty().mapNotNull(VaultValueRef::of) }
        if (refs.isEmpty()) return FileOutcome.Unchanged
        val replacements = ArrayList<Pair<VaultValueRef, VaultEnvelope>>()
        for (ref in refs) {
            currentCoroutineContext().ensureActive()
            val envelope = ref.envelope ?: return FileOutcome.Failed(VaultFailure.FORMAT)
            val decrypted = when (val result = decryptValue(ref, root, envelope, run)) {
                null -> return FileOutcome.Changed
                is VaultDecryptResult.Failed -> return FileOutcome.Failed(result.failure)
                is VaultDecryptResult.Decrypted -> result
            }
            val next = decrypted.plaintext.use { plaintext ->
                val bytes = plaintext.read { it.copyOf() }
                try {
                    newEnvelope(run, root, file, envelope, bytes, decrypted.identity, target)
                } finally {
                    bytes.fill(0)
                }
            }
            when (next) {
                is NewEnvelope.Ready -> replacements += ref to (parse(next.text) ?: return FileOutcome.Failed(VaultFailure.FORMAT))
                NewEnvelope.Same -> Unit
                NewEnvelope.Declined -> return FileOutcome.Cancelled
                is NewEnvelope.Failed -> return FileOutcome.Failed(next.failure)
            }
        }
        if (replacements.isEmpty()) return FileOutcome.Unchanged
        return onEdt { writeValues(file, replacements, message(run.operation.commandKey)) }
    }

    /**
     * Decrypts the `!vault` value [ref] captured and no other. The vault services resolve an offset, and an edit above the
     * value moves it, so the value is found again through its smart pointer right before each try (documents committed),
     * and a try counts only when the document did not change until it returned; otherwise it is tried again. Null when
     * the value changed or is gone, or its document kept changing.
     */
    private suspend fun decryptValue(ref: VaultValueRef, root: AnsibleRoot, envelope: VaultEnvelope, run: Reencryption): VaultDecryptResult? {
        repeat(VALUE_ATTEMPTS) {
            val pinned = constrainedReadAction(ReadConstraint.withDocumentsCommitted(project)) { pin(ref) } ?: return null
            val result = decryptUnlocking(pinned.location, root, envelope, run.operation.purpose, run.unlocks)
            if (readAction { pinned.document.modificationSequence } == pinned.sequence) return result
            when (result) {
                is VaultDecryptResult.Decrypted -> result.plaintext.close()
                // A cancelled prompt stays cancelled; any other failure may be another value's.
                is VaultDecryptResult.Failed -> if (result.failure == VaultFailure.CANCELLED) return result
            }
        }
        return null
    }

    /** Where [ref]'s value is now, with its document and the document's modification sequence; null when the value changed or is gone. Read action. */
    private fun pin(ref: VaultValueRef): Pinned? {
        val scalar = ref.scalar?.takeIf { it.isValid && it.textValue == ref.envelopeText } ?: return null
        val document = FileDocumentManager.getInstance().getDocument(ref.file) as? DocumentEx ?: return null
        return Pinned(SourceLocation(ref.file, VaultValuePsi.valueStart(scalar)), document, document.modificationSequence)
    }

    /**
     * A `!vault` value found again right before a decrypt. The modification sequence grows with every change of the
     * document, undo included (the modification stamp goes back on undo), so an unchanged one means the decrypt resolved
     * [location] in the same text.
     */
    private class Pinned(val location: SourceLocation, val document: DocumentEx, val sequence: Int)

    /**
     * The envelope [plaintext] gets under [target]: for change id, the same envelope relabelled when [identity] (the id
     * that decrypted it) is the target, else encrypted again with the target's secret (for change id after one
     * question per decrypting id).
     */
    private suspend fun newEnvelope(
        run: Reencryption, root: AnsibleRoot, file: VirtualFile, envelope: VaultEnvelope, plaintext: ByteArray, identity: String, target: String,
    ): NewEnvelope {
        if (run.relabelOnly && identity == target) {
            val relabelled = envelope.withLabel(target)
            return if (relabelled == envelope) NewEnvelope.Same else NewEnvelope.Ready(relabelled.format())
        }
        if (run.relabelOnly) {
            val yes = run.agreed.getOrPut(root to identity) {
                onEdt { VaultActionPrompts.getInstance().confirmRekeyFilesForChangeId(project, root.displayName, target, identity) }
            }
            if (!yes) return NewEnvelope.Declined
        }
        ensureUnlocked(root, target, run.unlocks)?.let { return NewEnvelope.Failed(it) }
        return when (val result = VaultOperations.getInstance(project).encrypt(root, plaintext, target, file)) {
            is VaultEncryptResult.Failed -> NewEnvelope.Failed(result.failure)
            is VaultEncryptResult.Encrypted -> NewEnvelope.Ready(result.envelope)
        }
    }

    // ------------------------------------------------------------------------------------------------ new file

    /** New › Ansibility Vault File in [directory]: created encrypted (0600) and opened in the decrypted tab. EDT. */
    fun newVaultFile(directory: VirtualFile) {
        ThreadingAssertions.assertEventDispatchThread()
        val root = runReadActionBlocking { AnsibleWorkspace.getInstance(project).rootFor(directory) } ?: return
        val config = runReadActionBlocking { VaultStatusService.getInstance(project).config(root) }
        val choices = choices(config)
        if (choices.isEmpty()) return VaultUiFeedback.failure(project, null, VaultFailure.NO_IDENTITY)
        val name = VaultActionPrompts.getInstance().askVaultFileName(project, directory.presentableUrl) { candidate ->
            when {
                candidate.isBlank() || candidate == "." || candidate == ".." || candidate.contains('/') || candidate.contains('\\') -> message("file.new.invalid")
                directory.findChild(candidate) != null -> message("file.new.exists", candidate)
                else -> null
            }
        } ?: return
        val environments = runReadActionBlocking { AnsibleWorkspace.getInstance(project).contextOf(directory)?.environments.orEmpty() }
        val label = when (val choice = VaultValueActions.getInstance(project).encryptChoice(root, environments, directory, config, choices.map { it.label })) {
            is EncryptIdentity.Choice.Chosen -> choice.label
            is EncryptIdentity.Choice.Ambiguous -> VaultActionPrompts.getInstance().chooseIdentity(
                project, VaultIdentityRequest(message("file.new.title"), message("file.new.choose", name), choices, choice.preselected),
            ) ?: return
            is EncryptIdentity.Choice.NotFound, EncryptIdentity.Choice.NoIdentity -> return VaultUiFeedback.failure(project, null, VaultFailure.NO_IDENTITY)
        }
        // A YAML vault starts as a YAML document; anything else starts empty.
        val start = if (name.endsWith(".yml") || name.endsWith(".yaml")) "---\n" else ""
        launch(message("file.progress.new")) {
            ensureUnlocked(root, label, Unlocks())?.let { failure -> return@launch onEdt { VaultUiFeedback.failure(project, null, failure) } }
            val envelope = when (val result = VaultOperations.getInstance(project).encrypt(root, start.toByteArray(Charsets.UTF_8), label, null)) {
                is VaultEncryptResult.Failed -> return@launch onEdt { VaultUiFeedback.failure(project, null, result.failure) }
                is VaultEncryptResult.Encrypted -> result.envelope
            }
            onEdt {
                if (!directory.isValid || directory.findChild(name) != null) return@onEdt VaultUiFeedback.error(project, null, message("file.new.exists", name))
                var private = false
                val file = WriteCommandAction.writeCommandAction(project).withName(message("file.command.new")).compute<VirtualFile, IOException> {
                    directory.createChildData(this, name).also {
                        // The mode before the content (an envelope, so the file is kept when the mode cannot be set).
                        private = makePrivate(it)
                        it.setBinaryContent(envelope.toByteArray(Charsets.US_ASCII))
                    }
                }
                if (!private) VaultUiFeedback.error(project, null, message("file.new.not.private", name))
                VaultLog.event(VaultLog.Operation.ENCRYPT, VaultLog.Event.ENCRYPTED, file.path)
                DecryptedVaultTabs.getInstance(project).open(file)
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ targets

    /** What rekey and change id work on in one file. */
    private sealed interface Target {
        val file: VirtualFile

        class WholeFile(override val file: VirtualFile) : Target

        class Values(override val file: VirtualFile, val count: Int) : Target
    }

    /** The whole-file vaults of [files] with their roots, and the names of those outside every root. Read action. */
    private fun vaultFiles(files: List<VirtualFile>): Pair<List<Pair<VirtualFile, AnsibleRoot>>, List<String>> {
        val workspace = AnsibleWorkspace.getInstance(project)
        val skipped = ArrayList<String>()
        val vaults = files.filter(VaultEnvelopes::isWholeFileVault).mapNotNull { file ->
            val root = workspace.rootFor(file)
            if (root == null) skipped += message("file.skipped.no.root", file.name)
            root?.let { file to it }
        }
        return vaults to skipped
    }

    /** Every vault of [selection] by root (whole files, then YAML files with `!vault` values), and what was skipped. Read action. */
    private fun scanForReencrypt(selection: List<VirtualFile>): Pair<Map<AnsibleRoot, List<Target>>, List<String>> {
        val files = filesOf(selection)
        val (wholeFiles, skipped) = vaultFiles(files)
        val byRoot = LinkedHashMap<AnsibleRoot, MutableList<Target>>()
        for ((file, root) in wholeFiles) byRoot.getOrPut(root, ::ArrayList) += Target.WholeFile(file)
        val workspace = AnsibleWorkspace.getInstance(project)
        val psi = PsiManager.getInstance(project)
        val types = FileTypeRegistry.getInstance()
        for (file in files) {
            ProgressManager.checkCanceled()
            if (!types.isFileOfType(file, YAMLFileType.YML) || VaultEnvelopes.isWholeFileVault(file) || !mentionsVault(file)) continue
            val root = workspace.rootFor(file) ?: continue
            val count = psi.findFile(file)?.let(VaultValuePsi::vaultScalars)?.size ?: 0
            if (count > 0) byRoot.getOrPut(root, ::ArrayList) += Target.Values(file, count)
        }
        return byRoot to skipped
    }

    /** True when [file]'s bytes contain `$ANSIBLE_VAULT`: only those are parsed for `!vault` values (files up to 16 MiB). */
    private fun mentionsVault(file: VirtualFile): Boolean {
        if (file.length > MAX_SCANNED_BYTES) return false
        val bytes = try {
            file.contentsToByteArray()
        } catch (_: IOException) {
            return false
        }
        return (0..bytes.size - MAGIC.size).any { start -> MAGIC.indices.all { bytes[start + it] == MAGIC[it] } }
    }

    // ------------------------------------------------------------------------------------------------ crypto

    /**
     * Decrypts the whole-file vault [file] (unlocking its root lazily) and runs [block] with the stamp it was read at,
     * its envelope, the plaintext (zeroed afterwards) and the id that decrypted it.
     */
    private suspend fun withWholeFile(
        file: VirtualFile, root: AnsibleRoot, purpose: VaultPurpose, unlocks: Unlocks, block: suspend (Long, VaultEnvelope, ByteArray, String) -> FileOutcome,
    ): FileOutcome {
        val (stamp, envelope) = readAction { file.modificationStamp to VaultEnvelopes.ofFile(file)?.envelope }
        envelope ?: return FileOutcome.Failed(VaultFailure.FORMAT)
        val decrypted = when (val result = decryptUnlocking(SourceLocation(file, 0), root, envelope, purpose, unlocks)) {
            is VaultDecryptResult.Failed -> return FileOutcome.Failed(result.failure)
            is VaultDecryptResult.Decrypted -> result
        }
        return decrypted.plaintext.use { plaintext ->
            val bytes = plaintext.read { it.copyOf() }
            try {
                block(stamp, envelope, bytes, decrypted.identity)
            } finally {
                bytes.fill(0)
            }
        }
    }

    /**
     * Decrypts at [location]; a locked root is unlocked first (consent, password safe, a prompt checked against
     * [envelope]), once per run: when that failed or you cancelled it, or the root is still locked afterwards, its later
     * files fail at once without asking again.
     */
    private suspend fun decryptUnlocking(location: SourceLocation, root: AnsibleRoot, envelope: VaultEnvelope, purpose: VaultPurpose, unlocks: Unlocks): VaultDecryptResult {
        val operations = VaultOperations.getInstance(project)
        val first = operations.decrypt(location, purpose)
        if (first !is VaultDecryptResult.Failed || first.failure != VaultFailure.LOCKED) return first
        unlocks.decrypting[root]?.let { return VaultDecryptResult.Failed(it, first.tried) }
        val failure = (VaultSecretsService.getInstance(project).unlock(root, label = null, verify = envelope) as? VaultUnlockResult.Failed)?.failure
        unlocks.decrypting[root] = failure ?: VaultFailure.LOCKED
        if (failure != null) return VaultDecryptResult.Failed(failure, first.tried)
        return operations.decrypt(location, purpose)
    }

    /** Unlocks [label] of [root] when it is locked, once per run; the failure, or null when it is unlocked now. */
    private suspend fun ensureUnlocked(root: AnsibleRoot, label: String, unlocks: Unlocks): VaultFailure? {
        unlocks.ids[root to label]?.let { return it }
        val identity = readAction { VaultStatusService.getInstance(project).config(root).identities.firstOrNull { it.label == label } }
            ?: return VaultFailure.NO_IDENTITY
        if (identity.lockState == VaultLockState.UNLOCKED) return null
        val failure = when (val result = VaultOperations.getInstance(project).unlock(root, label)) {
            is VaultUnlockResult.Unlocked -> if (label in result.identities) null else VaultFailure.LOCKED
            is VaultUnlockResult.Failed -> result.failure
        }
        failure?.let { unlocks.ids[root to label] = it }
        return failure
    }

    /**
     * The unlocks of one run, so a root asks for its passwords at most once: an id whose unlock failed (or whose prompt
     * you cancelled) is not unlocked again, nor a root unlocked once for decrypting; their later files fail at once with
     * that failure.
     */
    private class Unlocks {
        /** The failed unlocks of ids to encrypt with, by root and id. */
        val ids = HashMap<Pair<AnsibleRoot, String>, VaultFailure>()

        /** The roots unlocked once for decrypting, with the failure their files get while they are still locked. */
        val decrypting = HashMap<AnsibleRoot, VaultFailure>()
    }

    // ------------------------------------------------------------------------------------------------ writing

    private sealed interface Content {
        /** An envelope: ASCII lines, LF, no byte order mark (Ansible reads `$ANSIBLE_VAULT` from the first byte). */
        class Envelope(val text: String) : Content

        /** Decrypted bytes (the caller zeroes them): UTF-8 text through the document, anything else as it is. */
        class Plain(val bytes: ByteArray) : Content
    }

    /**
     * Writes [content] over [file] when it is still at [stamp], in one undoable command (Edit › Undo restores the old
     * text, byte order mark and line separator): text through its document, saved at once; binary content as bytes. When
     * the saved bytes differ from [content]'s exact bytes (a charset of the file, the trailing-space stripper, mixed line
     * separators), the exact bytes are written over them in the same command. With [private] the file gets 0600 before
     * anything is written, and is left alone when that fails. EDT.
     */
    private fun write(file: VirtualFile, stamp: Long, content: Content, @Nls commandName: String, private: Boolean): FileOutcome {
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
    private class Rewrite(val text: CharSequence, val form: FileForm)

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

    /** Replaces the `!vault` values of [file] with their new envelopes in one command, or nothing when one of them changed. EDT. */
    private fun writeValues(file: VirtualFile, replacements: List<Pair<VaultValueRef, VaultEnvelope>>, @Nls commandName: String): FileOutcome {
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

    /**
     * 0600, like ansible-vault's encrypt and decrypt in place (D29); file systems without POSIX modes are left alone.
     * False when the mode could not be set (a file of another user, a mount that refuses it).
     */
    private fun makePrivate(file: VirtualFile): Boolean {
        val path: Path = file.toNioPathOrNull() ?: return true
        return try {
            modes.makePrivate(path)
            true
        } catch (e: IOException) {
            VaultLog.failure(VaultLog.Operation.ENCRYPT, e, file.path)
            false
        }
    }

    /** Saves the unsaved documents of [selection]'s files and of the files below its folders: the operations read what is on disk. EDT. */
    private fun saveDocuments(selection: List<VirtualFile>) {
        val documents = FileDocumentManager.getInstance()
        for (document in documents.unsavedDocuments) {
            val file = documents.getFile(document) ?: continue
            if (selection.any { VfsUtilCore.isAncestor(it, file, false) }) documents.saveDocument(document)
        }
    }

    private fun parse(text: String): VaultEnvelope? = (VaultEnvelope.parse(text) as? EnvelopeParse.Ok)?.envelope

    /** The ids of [config] that may encrypt, once per label, in Ansible's order. */
    private fun choices(config: VaultRootConfig): List<VaultIdentityChoice> =
        config.identities.distinctBy { it.label }.map { VaultIdentityChoice(it.label, it.lockState) }

    // ------------------------------------------------------------------------------------------------ outcome

    /** How one file of a batch ended. Carries no data beyond a failure class. */
    private sealed interface FileOutcome {
        data object Written : FileOutcome

        /** Nothing to write: no vault in it, or already labelled with the target id. */
        data object Unchanged : FileOutcome

        /** The file (or its document) changed while the crypto ran. */
        data object Changed : FileOutcome

        /** Its decrypted tab has unsaved edits. */
        data object TabModified : FileOutcome

        data object ReadOnly : FileOutcome

        /** Its mode could not be set to 0600 (D29), so nothing was written. */
        data object NotPrivate : FileOutcome

        /** Writing failed; its document has the old text again. */
        data object NotWritten : FileOutcome

        /** You declined a question about it. */
        data object Cancelled : FileOutcome

        data class Failed(val failure: VaultFailure) : FileOutcome
    }

    private sealed interface NewEnvelope {
        class Ready(val text: String) : NewEnvelope

        data object Same : NewEnvelope

        data object Declined : NewEnvelope

        class Failed(val failure: VaultFailure) : NewEnvelope
    }

    /** What one batch did, for its summary: file names and failure classes only. */
    private class Batch(val skipped: List<String>, val history: Boolean = false, val doneText: (List<String>) -> String) {
        val done = ArrayList<String>()
        val failed = ArrayList<String>()

        /** The files left alone because you cancelled a prompt or declined a question. */
        val declined = ArrayList<String>()
        var cancelled = false

        fun record(outcome: FileOutcome, file: VirtualFile, name: () -> String) {
            when (outcome) {
                FileOutcome.Written -> done += name()
                FileOutcome.Unchanged -> Unit
                FileOutcome.Cancelled -> declined += file.name
                FileOutcome.Changed -> failed += message("file.failed.changed", file.name)
                FileOutcome.TabModified -> failed += message("file.failed.tab", file.name)
                FileOutcome.ReadOnly -> failed += message("file.failed.read.only", file.name)
                FileOutcome.NotPrivate -> failed += message("file.failed.private", file.name)
                FileOutcome.NotWritten -> failed += message("file.failed.write", file.name)
                is FileOutcome.Failed ->
                    if (outcome.failure == VaultFailure.CANCELLED) declined += file.name
                    else failed += message("file.failed", file.name, AnsibilityVaultBundle.failure(outcome.failure))
            }
        }
    }

    /** Runs [block] over [batch] and shows its summary, also after Cancel. */
    private suspend fun runBatch(batch: Batch, block: suspend (Batch) -> Unit) {
        try {
            block(batch)
        } catch (e: CancellationException) {
            batch.cancelled = true
            throw e
        } finally {
            withContext(NonCancellable) { onEdt { finish(batch) } }
        }
    }

    /**
     * The summary: what was done, Cancel, what failed (by file), what you cancelled or declined, what was skipped; the
     * history note after encrypting. EDT.
     */
    private fun finish(batch: Batch) {
        val nothing = !batch.cancelled && batch.failed.isEmpty() && batch.declined.isEmpty()
        val parts = listOfNotNull(
            batch.done.takeIf { it.isNotEmpty() }?.let(batch.doneText) ?: message("file.summary.nothing").takeIf { nothing },
            message("file.summary.cancelled").takeIf { batch.cancelled },
            batch.failed.takeIf { it.isNotEmpty() }?.let { message("file.summary.failed", it.joinToString("; ")) },
            batch.declined.takeIf { it.isNotEmpty() }?.let { message("file.summary.declined", it.joinToString(", ")) },
            batch.skipped.takeIf { it.isNotEmpty() }?.let { message("file.summary.skipped", it.joinToString("; ")) },
            message("file.summary.history").takeIf { batch.history && batch.done.isNotEmpty() },
        )
        val text = parts.joinToString(" ")
        if (batch.failed.isEmpty()) VaultUiFeedback.info(project, null, text) else VaultUiFeedback.error(project, null, text)
    }

    /** The summary when a selection holds nothing to do: [none] and what was skipped. EDT. */
    private fun finishNothing(@Nls none: String, skipped: List<String>) {
        val text = listOfNotNull(none, skipped.takeIf { it.isNotEmpty() }?.let { message("file.summary.skipped", it.joinToString("; ")) }).joinToString(" ")
        VaultUiFeedback.info(project, null, text)
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** Runs [block] in the background with progress and Cancel; unexpected errors are logged by class and reported. */
    private fun launch(@Nls title: String, block: suspend CoroutineScope.() -> Unit) {
        lastJob = scope.launch {
            try {
                withBackgroundProgress(project, title) { block() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: VaultCorpusGuardException) {
                throw e
            } catch (e: Exception) {
                VaultLog.failure(VaultLog.Operation.ENCRYPT, e)
                onEdt { VaultUiFeedback.failure(project, null, VaultFailure.SOURCE_UNAVAILABLE) }
            }
        }
    }

    private suspend fun <T> onEdt(block: () -> T): T = withContext(Dispatchers.EDT) { block() }

    companion object {
        /** Larger YAML files are not searched for `!vault` values. */
        private const val MAX_SCANNED_BYTES = 16L * 1024 * 1024

        /** How often a `!vault` value is decrypted again while its document keeps changing. */
        private const val VALUE_ATTEMPTS = 3
        private val MAGIC = VaultEnvelope.MAGIC.toByteArray(Charsets.US_ASCII)

        /** The UTF-8 byte order mark (the platform's constant is internal API). */
        private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        fun getInstance(project: Project): VaultFileOperations = project.service()
    }
}

/** What a batch of the file actions does: its command name (undo, redo) and the purpose its decryption runs under. */
enum class FileOperation(internal val commandKey: String, internal val purpose: VaultPurpose) {
    /** Ansibility Vault › Encrypt File; it decrypts nothing, so [purpose] is never used. */
    ENCRYPT("file.command.encrypt", VaultPurpose.REKEY),
    DECRYPT("file.command.decrypt", VaultPurpose.DECRYPT_TO_PLAIN),
    REKEY("file.command.rekey", VaultPurpose.REKEY),
    CHANGE_ID("file.command.change.id", VaultPurpose.CHANGE_ID),
}
