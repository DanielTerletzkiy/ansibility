package de.terletzkiy.ansibility.vault.actions

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VFileProperty
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.ThreadingAssertions
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VaultDecryptResult
import de.terletzkiy.ansibility.api.VaultEncryptResult
import de.terletzkiy.ansibility.api.VaultFailure
import de.terletzkiy.ansibility.api.VaultOperations
import de.terletzkiy.ansibility.api.VaultPurpose
import de.terletzkiy.ansibility.api.VaultRootConfig
import de.terletzkiy.ansibility.api.VaultStatusService
import de.terletzkiy.ansibility.semantics.vault.EncryptIdentity
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.semantics.vault.VaultShapeKind
import de.terletzkiy.ansibility.vault.AnsibilityVaultBundle
import de.terletzkiy.ansibility.vault.VaultCorpusGuardException
import de.terletzkiy.ansibility.vault.VaultLog
import de.terletzkiy.ansibility.vault.envelope.VaultEnvelopes
import de.terletzkiy.ansibility.vault.envelope.WholeFileShapes
import de.terletzkiy.ansibility.vault.identity.SecretPlan
import de.terletzkiy.ansibility.vault.identity.VaultIdentityRegistry
import de.terletzkiy.ansibility.vault.reencrypt.Decryption
import de.terletzkiy.ansibility.vault.reencrypt.Encryption
import de.terletzkiy.ansibility.vault.reencrypt.FileOutcome
import de.terletzkiy.ansibility.vault.reencrypt.ItemOutcome
import de.terletzkiy.ansibility.vault.reencrypt.NewPlaintext
import de.terletzkiy.ansibility.vault.reencrypt.ReencryptItem
import de.terletzkiy.ansibility.vault.reencrypt.ReencryptTarget
import de.terletzkiy.ansibility.vault.reencrypt.ReencryptionEngine
import de.terletzkiy.ansibility.vault.reencrypt.ReencryptionRun
import de.terletzkiy.ansibility.vault.reencrypt.ReencryptionWriter
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
 *
 * Since plan amendment R29 the crypto and write mechanics live in the re-encryption engine
 * (`vault.reencrypt.ReencryptionEngine`, `ReencryptionWriter`), shared with Regenerate Secrets… and Rotate Vault
 * Password…; Rekey and Change Id run on it per file (write mode 1), with unchanged behaviour.
 */
@Service(Service.Level.PROJECT)
class VaultFileOperations(private val project: Project, private val scope: CoroutineScope) {
    @Volatile
    private var lastJob: Job? = null

    @Volatile
    private var modes: FileModes = FileModes.POSIX

    /** The re-encryption engine (plan amendment R29) and its writer, which hold R18's mechanics now. */
    private val engine = ReencryptionEngine(project)
    private val writer = ReencryptionWriter(project) { modes }

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
            val run = ReencryptionRun(FileOperation.ENCRYPT.purpose)
            runBatch(Batch(skipped, history = true) { message("file.encrypt.done", it.size, it.joinToString(", ")) }) { batch ->
                for (plan in planned) {
                    currentCoroutineContext().ensureActive()
                    val label = labels.getValue(plan.file)
                    batch.record(encryptFile(plan.file, plan.root, label, run), plan.file) { message("file.name.with.id", plan.file.name, label) }
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
    private suspend fun encryptFile(file: VirtualFile, root: AnsibleRoot, label: String, run: ReencryptionRun): FileOutcome {
        engine.ensureUnlocked(root, label, run)?.let { return FileOutcome.Failed(it) }
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
                    writer.write(file, stamp, ReencryptionWriter.Content.Envelope(result.envelope), message(FileOperation.ENCRYPT.commandKey), private = true)
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
            val run = ReencryptionRun(FileOperation.DECRYPT.purpose)
            runBatch(Batch(skipped) { message("file.decrypt.done", it.size, it.joinToString(", ")) }) { batch ->
                for ((file, root) in planned) {
                    currentCoroutineContext().ensureActive()
                    val outcome = withWholeFile(file, root, run) { stamp, plaintext ->
                        onEdt { writer.write(file, stamp, ReencryptionWriter.Content.Plain(plaintext), message(FileOperation.DECRYPT.commandKey), private = true) }
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
                        val outcome = run.engineRun.failedIds[root to target]?.let { FileOutcome.Failed(it) } ?: when (item) {
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
    private inner class Reencryption(val operation: FileOperation) {
        /** The engine's run: a root whose id could not be unlocked (or whose unlock you cancelled) is not asked again. */
        val engineRun = ReencryptionRun(operation.purpose)

        /**
         * Change id: re-encrypt what the target's secret does not decrypt? By root and the id that does, asked once each:
         * the same id in another root is another secret, and that root has its own target.
         */
        private val agreed = HashMap<Pair<AnsibleRoot, String>, Boolean>()

        /** How the engine encrypts for [target]: Rekey with its secret, Change Id relabelling where it can (D131). */
        fun encryption(target: String): Encryption =
            if (operation == FileOperation.CHANGE_ID) Encryption.ChangeId(target) { root, identity -> confirm(root, target, identity) }
            else Encryption.ConfiguredId(target)

        private suspend fun confirm(root: AnsibleRoot, target: String, identity: String): Boolean = agreed.getOrPut(root to identity) {
            onEdt { VaultActionPrompts.getInstance().confirmRekeyFilesForChangeId(project, root.displayName, target, identity) }
        }
    }

    private suspend fun reencryptWholeFile(file: VirtualFile, root: AnsibleRoot, target: String, run: Reencryption): FileOutcome {
        val item = ReencryptItem(root, ReencryptTarget.WholeFile(file), Decryption.RootSecrets, NewPlaintext.Keep, run.encryption(target))
        val prepared = engine.prepare(listOf(item), run.engineRun).items.single()
        val ready = prepared.ready ?: return FileOutcome.of(prepared.outcome)
        val stamp = prepared.stamp ?: return FileOutcome.Changed
        return onEdt { writer.write(file, stamp, ReencryptionWriter.Content.Envelope(ready.envelope.format()), message(run.operation.commandKey), private = false) }
    }

    /** All `!vault` values of [file] to [target]: decrypted and encrypted first, then written in one command (or not at all). */
    private suspend fun reencryptValues(file: VirtualFile, root: AnsibleRoot, target: String, run: Reencryption): FileOutcome {
        val refs = readAction { PsiManager.getInstance(project).findFile(file)?.let(VaultValuePsi::vaultScalars).orEmpty().mapNotNull(VaultValueRef::of) }
        if (refs.isEmpty()) return FileOutcome.Unchanged
        val items = refs.map { ReencryptItem(root, ReencryptTarget.Value(it), Decryption.RootSecrets, NewPlaintext.Keep, run.encryption(target)) }
        val plan = engine.prepare(items, run.engineRun, stopAtFirstFailure = true)
        plan.items.firstOrNull { it.ready == null && it.outcome != ItemOutcome.Unchanged }?.let { return FileOutcome.of(it.outcome) }
        val replacements = plan.items.mapNotNull { prepared -> prepared.ready?.let { (prepared.item.target as ReencryptTarget.Value).ref to it.envelope } }
        if (replacements.isEmpty()) return FileOutcome.Unchanged
        return onEdt { writer.writeValues(file, replacements, message(run.operation.commandKey)) }
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
            engine.ensureUnlocked(root, label, ReencryptionRun(FileOperation.ENCRYPT.purpose))?.let { failure -> return@launch onEdt { VaultUiFeedback.failure(project, null, failure) } }
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
                        private = writer.makePrivate(it)
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
     * Decrypts the whole-file vault [file] (unlocking its root lazily, once per [run]) and runs [block] with the stamp
     * it was read at and the plaintext (zeroed afterwards).
     */
    private suspend fun withWholeFile(file: VirtualFile, root: AnsibleRoot, run: ReencryptionRun, block: suspend (Long, ByteArray) -> FileOutcome): FileOutcome {
        val (stamp, envelope) = readAction { file.modificationStamp to VaultEnvelopes.ofFile(file)?.envelope }
        envelope ?: return FileOutcome.Failed(VaultFailure.FORMAT)
        val decrypted = when (val result = engine.decryptUnlocking(SourceLocation(file, 0), root, envelope, run)) {
            is VaultDecryptResult.Failed -> return FileOutcome.Failed(result.failure)
            is VaultDecryptResult.Decrypted -> result
        }
        return decrypted.plaintext.use { plaintext ->
            val bytes = plaintext.read { it.copyOf() }
            try {
                block(stamp, bytes)
            } finally {
                bytes.fill(0)
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ writing

    /** Saves the unsaved documents of [selection]'s files and of the files below its folders: the operations read what is on disk. EDT. */
    internal fun saveDocuments(selection: List<VirtualFile>) {
        val documents = FileDocumentManager.getInstance()
        for (document in documents.unsavedDocuments) {
            val file = documents.getFile(document) ?: continue
            if (selection.any { VfsUtilCore.isAncestor(it, file, false) }) documents.saveDocument(document)
        }
    }

    /** The ids of [config] that may encrypt, once per label, in Ansible's order. */
    private fun choices(config: VaultRootConfig): List<VaultIdentityChoice> =
        config.identities.distinctBy { it.label }.map { VaultIdentityChoice(it.label, it.lockState) }

    // ------------------------------------------------------------------------------------------------ outcome

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
                FileOutcome.NotVerified -> failed += message("file.failed.verify", file.name)
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

        private val MAGIC = VaultEnvelope.MAGIC.toByteArray(Charsets.US_ASCII)

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
