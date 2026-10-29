package de.terletzkiy.ansibility.vault.rotate

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.ex.DocumentEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.ide.progress.withModalProgress
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.ThreadingAssertions
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VaultEnvelopeKind
import de.terletzkiy.ansibility.api.VaultFailure
import de.terletzkiy.ansibility.api.VaultHeaderInfo
import de.terletzkiy.ansibility.api.VaultPurpose
import de.terletzkiy.ansibility.api.VaultUnlockResult
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.index.IndexInput
import de.terletzkiy.ansibility.index.InContext
import de.terletzkiy.ansibility.index.PathFacts
import de.terletzkiy.ansibility.index.vault.VaultCounts
import de.terletzkiy.ansibility.index.vault.VaultIndex
import de.terletzkiy.ansibility.index.vault.VaultIndexEntry
import de.terletzkiy.ansibility.index.vault.VaultIndexQueries
import de.terletzkiy.ansibility.semantics.vault.EnvelopeParse
import de.terletzkiy.ansibility.semantics.vault.SecretBytes
import de.terletzkiy.ansibility.semantics.vault.VaultAes256
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.semantics.vault.VaultFormatException
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.vault.AnsibilityVaultBundle
import de.terletzkiy.ansibility.vault.VaultCorpusGuardException
import de.terletzkiy.ansibility.vault.VaultLog
import de.terletzkiy.ansibility.vault.actions.VaultFileOperations
import de.terletzkiy.ansibility.vault.actions.VaultFileSelection
import de.terletzkiy.ansibility.vault.actions.VaultValuePsi
import de.terletzkiy.ansibility.vault.actions.VaultValueRef
import de.terletzkiy.ansibility.vault.envelope.VaultEnvelopes
import de.terletzkiy.ansibility.vault.identity.ConsentTarget
import de.terletzkiy.ansibility.vault.identity.DiscoveredIdentity
import de.terletzkiy.ansibility.vault.identity.SecretPlan
import de.terletzkiy.ansibility.vault.identity.VaultDiscovery
import de.terletzkiy.ansibility.vault.identity.VaultIdentityRegistry
import de.terletzkiy.ansibility.vault.reencrypt.AtomicFiles
import de.terletzkiy.ansibility.vault.reencrypt.Decryption
import de.terletzkiy.ansibility.vault.reencrypt.Encryption
import de.terletzkiy.ansibility.vault.reencrypt.ItemOutcome
import de.terletzkiy.ansibility.vault.reencrypt.NewPlaintext
import de.terletzkiy.ansibility.vault.reencrypt.PreparedItem
import de.terletzkiy.ansibility.vault.reencrypt.PreparedPlan
import de.terletzkiy.ansibility.vault.reencrypt.ReencryptItem
import de.terletzkiy.ansibility.vault.reencrypt.ReencryptTarget
import de.terletzkiy.ansibility.vault.reencrypt.ReencryptionEngine
import de.terletzkiy.ansibility.vault.reencrypt.ReencryptionRun
import de.terletzkiy.ansibility.vault.reencrypt.ReencryptionWriter
import de.terletzkiy.ansibility.vault.regenerate.RegenerateService
import de.terletzkiy.ansibility.vault.secrets.CurrentSecret
import de.terletzkiy.ansibility.vault.secrets.PendingRotationRecord
import de.terletzkiy.ansibility.vault.secrets.StoreSnapshot
import de.terletzkiy.ansibility.vault.secrets.VaultSecretsService
import de.terletzkiy.ansibility.vault.secrets.VaultUserState
import de.terletzkiy.ansibility.vault.tab.DecryptedVaultTabs
import de.terletzkiy.ansibility.vault.ui.AnsibilityVaultUiBundle.message
import de.terletzkiy.ansibility.vault.ui.VaultClipboard
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
import org.jetbrains.yaml.YAMLLanguage
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLScalar
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date

/**
 * Rotate Vault Password… (plan amendment R29, Part 1, D204–D214): every vault the current password of a vault id
 * opens, in the roots you choose, is encrypted again with a new password, and the new password goes where the id's
 * password comes from.
 *
 * **The overriding rule** (review of Part 1): a rotation never leaves vault files that no durably stored password
 * opens, and never overwrites a stored password that is not the old password being rotated. When in doubt it stops
 * before anything is written or stored, with a message that says why and what to do.
 *
 * The flow, on the re-encryption engine ([ReencryptionEngine], [ReencryptionWriter] write mode 3):
 * 1. the roots and their counts per id from the `ansible.vault` index (a smart read), each id's password source per
 *    root ([RotationSources]); every root whose source of the id has the key of a root holding vaults of it joins
 *    that root's group (D206; review, decision 5);
 * 2. the dialog ([RotationPrompts.chooseRotation], D205): id, roots, the new password (generated or typed);
 * 3. the preflight (D207, D209), in the background with progress and Cancel:
 *    - the id of each chosen root is unlocked through the normal path (asking only what an unlock asks);
 *    - what the rotation covers: the index's envelopes (vars files without a YAML name parsed as YAML, decision 3),
 *      a file-system scan of the roots for whole-file vaults and large YAML files the index does not list (decision
 *      4), and the vaulted password files of the roots' chains (decision 2);
 *    - the old password of each root is proven, never assumed (decision 1): of the distinct unlocked secrets of the id,
 *      the one that opens the id's envelopes; it must come from the source the dialog showed, roots of one group must
 *      agree, and the IDE password store entry and the password file the rotation will overwrite must hold it;
 *    - every envelope is decrypted with the old password, encrypted with the new one, header kept (D208), and
 *      verified. Envelopes of another id whose own password is the same stay with that id (decision 11); envelopes of
 *      the id the old password does not open, and malformed ones, are left alone; a decrypted tab with unsaved edits
 *      stops the rotation (decision 18). Ids of other unlocked roots whose secret equals the old one are reported
 *      (compared in constant time, never unlocked for it);
 * 4. the review ([RotationPrompts.review]) with "Also regenerate secrets…" (D214: Regenerate Secrets…'s selection over
 *    the same values, new values encrypted with the new password in the same pass);
 * 5. the writes, each only after the one before succeeded (D210–D213), after the files were compared with the bytes
 *    the preflight read (decision 7) and the destinations checked again: the new password into the IDE password store
 *    (and the old one into its "before rotation" entry), all vault files through the file system (atomically, not
 *    undoable, all or nothing, Local History labels), the password files that may be written (atomically, 0600,
 *    consent renewed), then the secret in memory; a refused or failed vault write puts the password store back. A
 *    source the IDE did not write is recorded as a pending rotation (decision 9): until it gives the new password,
 *    the stored new password comes first;
 * 6. the summary (D213) in the vault notification group, with Regenerate Secrets…, Copy New Password and Check.
 *
 * Old and new passwords exist only as [SecretBytes] and character arrays that are zeroed when the run ends; logs name
 * files, keys and failure classes only; no text shown contains a password or a value.
 */
@Service(Service.Level.PROJECT)
class RotationService(private val project: Project, private val scope: CoroutineScope) {
    private val engine = ReencryptionEngine(project)
    private val writer = ReencryptionWriter(project) { VaultFileOperations.FileModes.POSIX }
    private val sources = RotationSources(project)

    @Volatile
    private var lastJob: Job? = null

    /** The running or last rotation (or Check, or Copy New Password), for tests that wait for it. */
    @get:TestOnly
    val lastRun: Job? get() = lastJob

    /** The summary of the last rotation that wrote something (tests; the notification carries the same). */
    @Volatile
    @get:TestOnly
    var lastSummary: RotationSummary? = null
        private set

    /** Forgets the last run and its summary (the light test project is shared between tests). */
    @TestOnly
    fun resetForTests() {
        lastSummary = null
        lastJob = null
        onStepForTests = null
    }

    /** The effects of a rotation, in their order (D210–D213). */
    enum class Step { STORE, FILES, SOURCES, MEMORY }

    /** Called right before each [Step] (tests assert the order of effects). */
    @Volatile
    @get:TestOnly
    @set:TestOnly
    var onStepForTests: ((Step) -> Unit)? = null

    /** What the action started from: everything, a selection of files and folders, or one root of the settings page. */
    private sealed interface Preselect {
        data object All : Preselect

        class Files(val selection: List<VirtualFile>) : Preselect

        class Root(val rootKey: String) : Preselect
    }

    /**
     * Rotate Vault Password… on [selection] (D204): the roots it touches (a root, a repository folder, a folder, a
     * file) are preselected; an empty selection (Tools menu, Find Action without a file) preselects every root. EDT.
     */
    fun rotate(selection: List<VirtualFile>) {
        ThreadingAssertions.assertEventDispatchThread()
        start(if (selection.isEmpty()) Preselect.All else Preselect.Files(selection), null, null)
    }

    /**
     * From Settings › Vault (D204): the root [rootKey] (all roots when null) and the id [identity] preselected; [after]
     * runs on the EDT when the run ended, however it ended (the page reloads its state, review decision 8). EDT.
     */
    fun rotate(rootKey: String?, identity: String?, after: (() -> Unit)? = null) {
        ThreadingAssertions.assertEventDispatchThread()
        start(rootKey?.let(Preselect::Root) ?: Preselect.All, identity, after)
    }

    // ------------------------------------------------------------------------------------------------ the flow

    private fun start(preselect: Preselect, identity: String?, after: (() -> Unit)?) {
        val modality = ModalityState.current()
        val state = RunState()
        lastJob = scope.launch(modality.asContextElement()) {
            try {
                // From a modal dialog (the settings page) the progress is modal too, so its Cancel can be reached.
                if (modality == ModalityState.nonModal()) withBackgroundProgress(project, message("rotate.progress")) { flow(preselect, identity, state) }
                else withModalProgress(project, message("rotate.progress")) { flow(preselect, identity, state) }
            } catch (e: CancellationException) {
                if (!state.writing) withContext(NonCancellable) { onEdt { VaultUiFeedback.info(project, null, message("rotate.cancelled")) } }
                throw e
            } catch (e: VaultCorpusGuardException) {
                throw e
            } catch (e: Exception) {
                VaultLog.failure(VaultLog.Operation.ENCRYPT, e)
                withContext(NonCancellable) { onEdt { VaultUiFeedback.failure(project, null, VaultFailure.SOURCE_UNAVAILABLE) } }
            } finally {
                if (after != null) withContext(NonCancellable) { onEdt { after() } }
            }
        }
    }

    private suspend fun flow(preselect: Preselect, identity: String?, state: RunState) {
        val scanned = smartReadAction(project) { scan() }
        val preselected = readAction { preselectedKeys(preselect, scanned) }
        val request = withContext(Dispatchers.IO) { request(scanned, preselected, identity, explicit = preselect is Preselect.Root) }
            ?: return onEdt { VaultUiFeedback.info(project, null, message("rotate.none")) }
        val password = onEdt { RotationPrompts.getInstance().chooseRotation(project, request) } ?: return
        password.use {
            val secret = it.secret() ?: return onEdt { VaultUiFeedback.error(project, null, message("rotate.failed.password")) }
            try {
                rotate(request, it, secret, state)
            } finally {
                secret.zero()
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ 1. scan

    /** A vault root with its discovery and its envelope counts by effective label. Not secret. */
    private class ScannedRoot(val root: AnsibleRoot, val discovery: VaultDiscovery, val counts: Map<String, VaultCounts>) {
        val rootKey: String get() = discovery.rootKey

        override fun toString(): String = "ScannedRoot(${discovery.rootKey})"
    }

    /** Every vault root (nested playbook roots count as their parent's) with its counts per id. Smart read action. */
    private fun scan(): List<ScannedRoot> {
        val registry = VaultIdentityRegistry.getInstance(project)
        val roots = AnsibleWorkspace.getInstance(project).roots().filter { !it.detached }
        return roots.map(registry::vaultRoot).distinctBy { it.dir }.map { root ->
            val discovery = registry.discovery(root)
            val entries = envelopesOf(root, roots)
            ScannedRoot(root, discovery, entries.groupBy { it.value.labelOrDefault(discovery.config.defaultIdentity) }.mapValues { VaultCounts.of(it.value) })
        }
    }

    /**
     * The envelopes of the vault root [root]: its own files and those of the nested playbook roots that share its ids,
     * never a file of another root its family reaches (a role library elsewhere keeps its own password). Smart read.
     */
    private fun envelopesOf(root: AnsibleRoot, roots: List<AnsibleRoot>): List<InContext<VaultIndexEntry>> {
        val registry = VaultIdentityRegistry.getInstance(project)
        return roots.filter { registry.vaultRoot(it).dir == root.dir }.flatMap { own ->
            VaultIndexQueries.envelopes(project, own).filter { it.context.root.dir == own.dir }
        }
    }

    /** The keys of the roots [preselect] touches (all of them when it touches none). Read action. */
    private fun preselectedKeys(preselect: Preselect, scanned: List<ScannedRoot>): Set<String> {
        val all = scanned.mapTo(LinkedHashSet()) { it.rootKey }
        val registry = VaultIdentityRegistry.getInstance(project)
        val workspace = AnsibleWorkspace.getInstance(project)
        val keys = when (preselect) {
            Preselect.All -> all
            is Preselect.Root -> setOf(preselect.rootKey)
            is Preselect.Files -> VaultFileSelection.inOrAboveRoots(project, preselect.selection).flatMap { item ->
                workspace.rootFor(item)?.let { listOf(registry.vaultRoot(it)) }
                    ?: workspace.roots().filter { !it.detached && VfsUtilCore.isAncestor(item, it.dir, false) }.map(registry::vaultRoot)
            }.mapTo(LinkedHashSet()) { registry.rootKey(it) }
        }
        return (keys intersect all).ifEmpty { all }
    }

    /**
     * The dialog's model: the ids that label vaults of the preselected roots (their counts summed), and per id every
     * root that holds vaults of it, with its password source, grouped by source. The source key of the id is computed
     * for every root whose chain has the id, whatever envelopes it holds, and a root whose key equals a holder's joins
     * that group (review, decision 5: rotating the shared source would lock it out otherwise). A root chosen on the
     * settings page ([explicit]) also offers the ids it configures without vaults. Null when there is no id at all.
     * Blocking (sources may read a consented `.env.local`); off the EDT.
     */
    private fun request(scanned: List<ScannedRoot>, preselected: Set<String>, identity: String?, explicit: Boolean): RotationRequest? {
        val involved = scanned.filter { it.rootKey in preselected }
        val labels = (involved.flatMap { it.counts.keys } + if (explicit) involved.flatMap { it.discovery.labels } else emptyList()).distinct()
        if (labels.isEmpty()) return null
        val identities = labels.map { label ->
            val holders = scanned.filter { label in it.counts || explicit && it.rootKey in preselected && label in it.discovery.labels }
            val candidates = scanned.filter { it in holders || label in it.discovery.labels }
            val sourceOf = candidates.associateWith { sources.sourceOf(it.discovery, label) }
            val keys = holders.mapTo(HashSet()) { sourceOf.getValue(it).key }
            val rows = candidates.filter { sourceOf.getValue(it).key in keys }.map { scannedRoot ->
                RotationRoot(
                    scannedRoot.root, scannedRoot.rootKey, scannedRoot.root.displayName, scannedRoot.counts[label] ?: VaultCounts.NONE,
                    sourceOf.getValue(scannedRoot), scannedRoot.rootKey in preselected,
                )
            }
            val groups = rows.groupBy { it.source.key }.map { (_, members) -> RotationGroup(members.first().source, members) }
            RotationIdentity(label, sum(involved.mapNotNull { it.counts[label] }), groups)
        }
        val default = identity ?: involved.firstOrNull()?.discovery?.config?.defaultIdentity
        return RotationRequest(identities, default)
    }

    private fun sum(counts: List<VaultCounts>): VaultCounts =
        VaultCounts(counts.sumOf { it.inline }, counts.sumOf { it.inlineFiles }, counts.sumOf { it.files }, counts.sumOf { it.malformed })

    // ------------------------------------------------------------------------------------------------ 3. preflight

    /** Whose an envelope is, by its effective label (review, decision 11). */
    private sealed interface Owner {
        /** Labelled with the rotated id (unlabelled ones when it is the default identity). */
        data object Id : Owner

        /** A label with no chain entry in the root: the rotated id's when the old password opens it. */
        data object Unclaimed : Owner

        /** Another id with its own chain entry in the root: [label]'s when its own password is the same. */
        class Other(val label: String) : Owner
    }

    /** Where an envelope was found. */
    private enum class Origin {
        /** The `ansible.vault` index. */
        INDEX,

        /** The file-system scan of the roots (decision 4). */
        SCAN,

        /** A vaulted password file of a chosen root's chain (decision 2). */
        PASSWORD_FILE,

        /** A vaulted password file only other roots' chains name, outside the chosen roots: never rotated. */
        FOREIGN,
    }

    /**
     * One envelope of a chosen root: where it is, its effective label and owner, whether it parsed when it was found,
     * how the review names it, and for a vaulted password file its consent target (renewed after the write) and the
     * other roots whose chain names it ([foreign]). Not secret.
     */
    private class Envelope(
        val root: RotationRoot,
        val file: VirtualFile,
        val effectiveLabel: String,
        val owner: Owner,
        val key: String?,
        val wellFormed: Boolean,
        val origin: Origin,
    ) {
        var consentTarget: ConsentTarget? = null

        /** "heron's 'prod'": an unchosen root whose chain names this file as a vaulted password file. */
        var foreign: String? = null

        override fun toString(): String = "Envelope(${file.name}, $key, $effectiveLabel, $origin)"
    }

    /** What the preflight covers: every envelope with its engine target, and the files parsed as YAML from their text. */
    private class Found(val envelopes: List<Pair<Envelope, ReencryptTarget>>, val parsed: Map<VirtualFile, Int>)

    /** The old password of one root, proven (decision 1), and the chain entry treated as its source. Holds a secret. */
    private class Old(val secret: SecretBytes, val source: DiscoveredIdentity?) : AutoCloseable {
        override fun close() = secret.zero()

        override fun toString(): String = "Old(***)"
    }

    /** The password-file decision of one group: write it or not (D211), after the preflight looked at it. */
    private class Decision(val group: RotationGroup, val write: Boolean, val vaulted: Boolean)

    /** A preflight that ended without writing anything: what to say. */
    private class Stop(@Nls val text: String, val cancelled: Boolean = false) : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    private suspend fun rotate(request: RotationRequest, password: NewPassword, new: SecretBytes, state: RunState) {
        val label = request.identity.label
        val groups = request.chosen
        if (groups.isEmpty()) return onEdt { VaultUiFeedback.info(project, null, message("rotate.nothing.chosen")) }
        val roots = groups.flatMap { it.roots }
        onEdt { VaultFileOperations.getInstance(project).saveDocuments(roots.map { it.root.dir }) }
        val run = ReencryptionRun(VaultPurpose.REKEY)
        val olds = LinkedHashMap<RotationRoot, Old>()
        try {
            for (root in roots) {
                currentCoroutineContext().ensureActive()
                unlockId(root, label)
            }
            val found = collect(roots, label)
            prove(groups, label, found, olds)
            val decisions = withContext(Dispatchers.IO) {
                groups.map { group ->
                    val vaulted = group.source.file?.let(sources::isVaulted) == true
                    Decision(group, group.write && group.writable && !vaulted, vaulted)
                }
            }
            withContext(Dispatchers.IO) { checkDestinations(label, decisions, olds) }
            val equal = equalPasswords(olds, label, roots.mapTo(HashSet()) { it.rootKey })
            // Decision 7: the bytes on disk the preflight works from; the write compares them again before the first byte.
            val expected = withContext(Dispatchers.IO) { found.envelopes.map { it.first.file }.distinct().mapNotNull { file -> diskBytes(file)?.let { file to it } }.toMap() }
            val items = found.envelopes.map { (envelope, target) ->
                ReencryptItem(envelope.root.root, target, Decryption.Explicit(olds.getValue(envelope.root).secret), NewPlaintext.Keep, Encryption.Explicit(new, null, keepHeader = true), envelope)
            }
            var plan = engine.prepare(items, run)
            for ((file, sequence) in found.parsed) if (plan.sequences[file] != sequence) throw Stop(message("rotate.stale.changed", escape(file.name)))
            val leftAlone = ArrayList<String>()
            plan = classify(plan, label, olds, leftAlone)
            stopOnModifiedTabs(plan)
            proveOpened(roots, label, found, plan)
            val review = review(label, roots, plan, leftAlone, equal, decisions)
            if (!onEdt { RotationPrompts.getInstance().review(project, review) }) return
            var regenerated = 0
            if (review.alsoRegenerate && review.canRegenerate) {
                val valueItems = plan.items.filter { it.ready != null && it.item.target is ReencryptTarget.Value }.map { it.item }
                val chosen = RegenerateService.getInstance(project).chooseForRotation(valueItems, label, run) ?: return
                if (chosen.isNotEmpty()) {
                    val replaced = chosen.map { (item, value) -> ReencryptItem(item.root, item.target, item.decryption, value, item.encryption, item.tag) }
                    val prepared = engine.prepare(replaced, run)
                    if (prepared.items.any { it.ready == null }) throw Stop(message("rotate.failed.regenerate"))
                    val byTarget = prepared.items.associateBy { it.item.target }
                    plan = PreparedPlan(plan.items.map { byTarget[it.item.target] ?: it }, plan.sequences, plan.documents)
                    regenerated = chosen.size
                }
            }
            state.writing = true
            withContext(NonCancellable) { write(label, roots, plan, expected, olds, password, new, decisions, leftAlone, equal, regenerated) }
        } catch (stop: Stop) {
            onEdt { if (stop.cancelled) VaultUiFeedback.info(project, null, stop.text) else VaultUiFeedback.error(project, null, stop.text) }
        } finally {
            olds.values.forEach(Old::close)
        }
    }

    /**
     * Unlocks id [label] in [root] through the normal path when it is locked there (the prompt checks a typed password
     * against an envelope of the id). Stops the rotation when the unlock fails or is cancelled.
     */
    private suspend fun unlockId(root: RotationRoot, label: String) {
        val secrets = VaultSecretsService.getInstance(project)
        val discovery = VaultIdentityRegistry.getInstance(project).discovery(root.root)
        if (label in secrets.unlockedLabels(discovery)) return
        val verify = smartReadAction(project) { firstEnvelope(root, label) }
        when (val result = secrets.unlock(root.root, label, verify)) {
            is VaultUnlockResult.Failed -> throw if (result.failure == VaultFailure.CANCELLED) Stop(message("rotate.cancelled"), cancelled = true)
            else Stop(message("rotate.failed.unlock", root.displayName, AnsibilityVaultBundle.failure(result.failure)))
            is VaultUnlockResult.Unlocked -> if (label !in result.identities) {
                throw Stop(message("rotate.failed.unlock", root.displayName, AnsibilityVaultBundle.failure(VaultFailure.LOCKED)))
            }
        }
    }

    /** An envelope of id [label] in [root] that a typed password is checked against, or null. Smart read. */
    private fun firstEnvelope(root: RotationRoot, label: String): VaultEnvelope? {
        val defaultIdentity = VaultIdentityRegistry.getInstance(project).discovery(root.root).config.defaultIdentity
        val roots = AnsibleWorkspace.getInstance(project).roots()
        return envelopesOf(root.root, roots).asSequence()
            .filter { it.value.wellFormed && it.value.labelOrDefault(defaultIdentity) == label }
            .firstNotNullOfOrNull { VaultEnvelopes.at(project, SourceLocation(it.file, it.value.offset))?.envelope }
    }

    // ------------------------------------------------------------------------------------------------ 3a. what is covered

    /**
     * Everything the rotation covers in [roots] (D207): the index's envelopes, a file-system scan for whole-file vaults
     * and YAML files the index does not list (decision 4), and the vaulted password files of the roots' chains
     * (decision 2), plus the vaulted password files other roots' chains name (they are only looked at: rotating them
     * would lock those roots out). Whole files are listed once.
     */
    private suspend fun collect(roots: List<RotationRoot>, label: String): Found {
        val (indexed, parsed, covered) = smartReadAction(project) { fromIndex(roots, label) }
        val result = ArrayList(indexed)
        val parsedAll = HashMap(parsed)
        val known = HashMap<VirtualFile, Envelope>()
        for ((envelope, target) in result) if (target is ReencryptTarget.WholeFile) known[envelope.file] = envelope
        scanFileSystem(roots, label, covered, result, parsedAll, known)
        passwordFiles(roots, label, result, known)
        return Found(result, parsedAll)
    }

    /**
     * The index's envelopes of [roots]: whole-file vaults, and the `!vault` values of their YAML files; a vars file that
     * the IDE does not type as YAML (`group_vars/web`) is parsed as YAML from its document text, as the index parses it
     * (decision 3). Stops when the index counts other values in a file than the preflight finds there (a safety net:
     * none may stay behind). Returns the envelopes, the files parsed from text with their document's modification
     * sequence, and every file the index listed. Smart read action.
     */
    private fun fromIndex(roots: List<RotationRoot>, label: String): Triple<List<Pair<Envelope, ReencryptTarget>>, Map<VirtualFile, Int>, Set<VirtualFile>> {
        val all = AnsibleWorkspace.getInstance(project).roots()
        val psi = PsiManager.getInstance(project)
        val result = ArrayList<Pair<Envelope, ReencryptTarget>>()
        val parsed = HashMap<VirtualFile, Int>()
        val covered = HashSet<VirtualFile>()
        for (root in roots) {
            val owners = OwnerRules(root, label)
            for ((file, entries) in envelopesOf(root.root, all).groupBy { it.file }) {
                ProgressManager.checkCanceled()
                covered += file
                if (entries.any { it.value.kind == VaultEnvelopeKind.FILE }) {
                    val entry = entries.first().value
                    val effective = entry.labelOrDefault(owners.defaultIdentity)
                    result += Envelope(root, file, effective, owners.of(effective), null, entry.wellFormed, Origin.INDEX) to ReencryptTarget.WholeFile(file)
                    continue
                }
                val inline = entries.count { it.value.kind == VaultEnvelopeKind.INLINE }
                val refs = valuesOf(file, psi, parsed)
                if (refs.size != inline) throw Stop(message("rotate.failed.count", escape(relative(root.root, file)), inline, refs.size))
                for ((scalar, ref) in refs) result += valueEnvelope(root, owners, file, scalar, ref, Origin.INDEX) to ReencryptTarget.Value(ref)
            }
        }
        return Triple(result, parsed, covered)
    }

    /**
     * The `!vault` values of [file] with their refs: from the IDE's PSI when it is YAML, else from a YAML parse of its
     * document text ([VaultValueRef.ofParsed]), whose document modification sequence goes to [parsed]. Read action.
     */
    private fun valuesOf(file: VirtualFile, psi: PsiManager, parsed: MutableMap<VirtualFile, Int>): List<Pair<YAMLScalar, VaultValueRef>> {
        val psiFile = psi.findFile(file)
        if (psiFile is YAMLFile) return VaultValuePsi.vaultScalars(psiFile).mapNotNull { scalar -> VaultValueRef.of(scalar)?.let { scalar to it } }
        val document = FileDocumentManager.getInstance().getDocument(file) as? DocumentEx ?: return emptyList()
        val yaml = PsiFileFactory.getInstance(project).createFileFromText(file.name, YAMLLanguage.INSTANCE, document.immutableCharSequence) as? YAMLFile
            ?: return emptyList()
        parsed[file] = document.modificationSequence
        return VaultValuePsi.vaultScalars(yaml).mapNotNull { scalar -> VaultValueRef.ofParsed(scalar, file)?.let { scalar to it } }
    }

    private fun valueEnvelope(root: RotationRoot, owners: OwnerRules, file: VirtualFile, scalar: YAMLScalar, ref: VaultValueRef, origin: Origin): Envelope {
        val effective = VaultValuePsi.header(scalar)?.label ?: owners.defaultIdentity
        return Envelope(root, file, effective, owners.of(effective), keyOf(scalar), ref.envelope != null, origin)
    }

    /** Whose an effective label is in [root] (decision 11): the rotated id, another id of the chain, or nobody's. */
    private inner class OwnerRules(root: RotationRoot, private val label: String) {
        private val discovery = VaultIdentityRegistry.getInstance(project).discovery(root.root)
        private val labels = (discovery.labels + VaultSecretsService.getInstance(project).extraInteractiveLabels(discovery)).toSet()
        val defaultIdentity: String = discovery.config.defaultIdentity

        fun of(effective: String): Owner = when {
            effective == label -> Owner.Id
            effective in labels -> Owner.Other(effective)
            else -> Owner.Unclaimed
        }
    }

    /**
     * Decision 4: the chosen roots' directories on the file system, for whole-file vaults of any name and size (the
     * index lists only YAML names, files below `files/`, `*.key` and `*.vault`, up to 1 MiB) and YAML files larger
     * than the index reads that contain `$ANSIBLE_VAULT`. Skipped: `.git` and other ignored names, IDE-excluded
     * folders, Ansibility's ignored paths, detached worktrees and the directories of other roots (their vaults keep
     * their own password); only file heads are read to find whole-file vaults. Leftover temporary files of an earlier
     * rotation are removed (decision 17). Adds to [result], [parsed] and [known].
     */
    private suspend fun scanFileSystem(
        roots: List<RotationRoot>, label: String, covered: Set<VirtualFile>, result: MutableList<Pair<Envelope, ReencryptTarget>>,
        parsed: MutableMap<VirtualFile, Int>, known: MutableMap<VirtualFile, Envelope>,
    ) {
        val walked = readAction { roots.associateWith { walk(it, covered) } }
        withContext(Dispatchers.IO) { for ((_, files) in walked) for (leftover in files.second) runCatching { Files.deleteIfExists(leftover) } }
        for ((root, files) in walked) {
            val owners = readAction { OwnerRules(root, label) }
            // Only the first bytes of each file are read, all in one go.
            val heads = withContext(Dispatchers.IO) {
                files.first.filter { it !in known }.mapNotNull { file -> file.toNioPathOrNull()?.let(::head)?.let { file to it } }
            }
            for ((file, head) in heads) {
                currentCoroutineContext().ensureActive()
                val path = file.toNioPathOrNull() ?: continue
                if (VaultEnvelope.isEncryptedFile(head)) {
                    if (file.length > MAX_WHOLE_FILE) throw Stop(message("rotate.failed.large", escape(relative(root.root, file))))
                    val envelope = withContext(Dispatchers.IO) { diskBytes(file) }?.let { (VaultEnvelope.parse(it) as? EnvelopeParse.Ok)?.envelope }
                    val effective = envelope?.labelOrDefault(owners.defaultIdentity) ?: headerLabel(head) ?: owners.defaultIdentity
                    val found = Envelope(root, file, effective, owners.of(effective), null, envelope != null, Origin.SCAN)
                    result += found to ReencryptTarget.WholeFile(file)
                    known[file] = found
                    continue
                }
                if (!isYaml(file) || !withContext(Dispatchers.IO) { mentionsVault(path) }) continue
                val values = readAction {
                    val local = HashMap<VirtualFile, Int>()
                    valuesOf(file, PsiManager.getInstance(project), local).map { (scalar, ref) -> valueEnvelope(root, owners, file, scalar, ref, Origin.SCAN) to ReencryptTarget.Value(ref) } to local
                }
                if (values.first.isEmpty()) throw Stop(message("rotate.failed.count", escape(relative(root.root, file)), 1, 0))
                result += values.first
                parsed += values.second
            }
        }
    }

    /**
     * The files below [root]'s directory that the scan looks at (not listed by the index, not under a skipped
     * directory) and the leftover temporary files it removes. Read action.
     */
    private fun walk(root: RotationRoot, covered: Set<VirtualFile>): Pair<List<VirtualFile>, List<Path>> {
        val workspace = AnsibleWorkspace.getInstance(project)
        val registry = VaultIdentityRegistry.getInstance(project)
        val own = registry.vaultRoot(root.root).dir
        val skipped = HashSet<VirtualFile>()
        for (other in workspace.roots()) if (other.detached || registry.vaultRoot(other).dir != own) skipped += other.dir
        AnsibleWorkspaceImpl.getInstance(project)?.detachedWorktrees()?.forEach { skipped += it.dir }
        skipped -= own
        val fileIndex = ProjectFileIndex.getInstance(project)
        val types = FileTypeManager.getInstance()
        val ignored = AnsibilityProjectSettings.getInstance(project).ignoredPathMatcher()
        val files = ArrayList<VirtualFile>()
        val leftovers = ArrayList<Path>()
        VfsUtilCore.visitChildrenRecursively(own, object : VirtualFileVisitor<Any>() {
            override fun visitFile(file: VirtualFile): Boolean {
                ProgressManager.checkCanceled()
                if (file != own && (file in skipped || types.isFileIgnored(file) || file.name == ".git" || fileIndex.isExcluded(file) || ignored(file))) return false
                if (file.isDirectory) return true
                if (AtomicFiles.isLeftover(file.name)) {
                    file.toNioPathOrNull()?.let(leftovers::add)
                    return false
                }
                if (file !in covered && !VaultIndex.InputFilter.acceptInput(file)) files += file
                return false
            }
        })
        return files to leftovers
    }

    /** A YAML file Ansible loads (a YAML name, or a vars file without one). Path names only. */
    private fun isYaml(file: VirtualFile): Boolean {
        val facts = PathFacts.of(file)
        return facts.isYamlName || IndexInput.isVarsWithoutYamlName(facts)
    }

    /**
     * Decision 2: the vaulted password files of the chosen roots' chains (any id), read under their consent, as
     * whole-file items; a password file without a consent that holds stops the rotation (whether it is a vault the old
     * password opens cannot be known). The vaulted password files other roots' chains name are added for a look only
     * ([Origin.FOREIGN]); a file the chosen roots cover anyway is marked with the root that names it.
     */
    private suspend fun passwordFiles(roots: List<RotationRoot>, label: String, result: MutableList<Pair<Envelope, ReencryptTarget>>, known: MutableMap<VirtualFile, Envelope>) {
        val registry = VaultIdentityRegistry.getInstance(project)
        val chosen = roots.mapTo(HashSet()) { it.rootKey }
        for (root in roots) {
            val discovery = registry.discovery(root.root)
            val owners = readAction { OwnerRules(root, label) }
            for ((identity, target) in withContext(Dispatchers.IO) { passwordFileTargets(discovery) }) {
                val path = target.path ?: continue
                val content = withContext(Dispatchers.IO) { sources.consented(target) }
                if (content == null) {
                    if (withContext(Dispatchers.IO) { registry.access.isRegularFile(path) }) {
                        throw Stop(message("rotate.failed.consent", escape(sourceName(target)), escape(identity.label), escape(root.displayName)))
                    }
                    continue
                }
                val envelope = try {
                    if (!RotationSources.isVault(content)) continue
                    (VaultEnvelope.parse(content) as? EnvelopeParse.Ok)?.envelope
                } finally {
                    content.fill(0)
                }
                val file = withContext(Dispatchers.IO) { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) } ?: continue
                known[file]?.let {
                    it.consentTarget = target
                    continue
                }
                val effective = envelope?.labelOrDefault(owners.defaultIdentity) ?: owners.defaultIdentity
                val found = Envelope(root, file, effective, owners.of(effective), null, envelope != null, Origin.PASSWORD_FILE).also { it.consentTarget = target }
                result += found to ReencryptTarget.WholeFile(file)
                known[file] = found
            }
        }
        // Other roots' vaulted password files: only those whose consent holds are looked at.
        val first = roots.first()
        for (discovery in registry.discoveries()) {
            if (discovery.rootKey in chosen) continue
            for ((identity, target) in withContext(Dispatchers.IO) { passwordFileTargets(discovery) }) {
                val path = target.path ?: continue
                val content = withContext(Dispatchers.IO) { sources.consented(target) } ?: continue
                val vault = try {
                    RotationSources.isVault(content)
                } finally {
                    content.fill(0)
                }
                if (!vault) continue
                val file = withContext(Dispatchers.IO) { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) } ?: continue
                val name = message("rotate.foreign.name", discovery.root.displayName, identity.label)
                val existing = known[file]
                if (existing != null) {
                    // The chosen roots hold it too, but rotating it would lock that root out: when the old password opens
                    // it, the rotation stops (classify).
                    existing.foreign = existing.foreign ?: name
                    continue
                }
                val found = Envelope(first, file, label, Owner.Unclaimed, null, true, Origin.FOREIGN).also { it.foreign = name }
                result += found to ReencryptTarget.WholeFile(file)
                known[file] = found
            }
        }
    }

    /** The password files of [discovery]'s chain (a `.env.local` resolved under its consent), with their identity. Blocking. */
    private fun passwordFileTargets(discovery: VaultDiscovery): List<Pair<DiscoveredIdentity, ConsentTarget>> =
        discovery.identities.mapNotNull { identity ->
            when (val plan = identity.plan) {
                is SecretPlan.PasswordFile -> identity to plan.target
                is SecretPlan.EnvLocal -> sources.sourceOf(discovery, identity.label, identity, read = true).file?.let { identity to it }
                else -> null
            }
        }.distinctBy { it.second.locator }

    // ------------------------------------------------------------------------------------------------ 3b. the old password

    /**
     * Decision 1: the old password of every root is proven, never assumed. Of the distinct unlocked secrets of [label]
     * (chain order), a single one is taken and proven after the preflight ([proveOpened]); of several, the one that opens
     * the root's envelopes of the id (a test decrypt each, until one opens) — none, or two different ones, stop. The
     * chain entry it came from (a pending rotation's stored entry stands in for the source still to be updated) must be
     * the source the dialog showed, and every root of a group must have the same old password.
     */
    private suspend fun prove(groups: List<RotationGroup>, label: String, found: Found, olds: MutableMap<RotationRoot, Old>) {
        val secrets = VaultSecretsService.getInstance(project)
        val registry = VaultIdentityRegistry.getInstance(project)
        for (group in groups) {
            for (root in group.roots) {
                currentCoroutineContext().ensureActive()
                val candidates = secrets.secretsOf(root.root, label)
                try {
                    if (candidates.isEmpty()) throw Stop(message("rotate.failed.unlock", root.displayName, AnsibilityVaultBundle.failure(VaultFailure.LOCKED)))
                    val chosen = if (candidates.size == 1) candidates.single() else opening(root, label, candidates, found)
                    val discovery = registry.discovery(root.root)
                    val source = sources.standIn(discovery, label, chosen.identity)
                    val actual = withContext(Dispatchers.IO) { sources.sourceOf(discovery, label, source, read = true) }
                    if (actual.key != root.source.key) {
                        throw Stop(
                            if (candidates.size > 1) message("rotate.failed.other.source", escape(root.displayName), escape(label), escape(actual.display), escape(root.source.display))
                            else message("rotate.failed.source", escape(root.displayName), escape(actual.display)),
                        )
                    }
                    olds[root] = Old(chosen.secret.copy(), source)
                } finally {
                    candidates.forEach(CurrentSecret::close)
                }
            }
            val first = olds.getValue(group.roots.first())
            if (group.roots.any { !same(olds.getValue(it).secret, first.secret) }) {
                throw Stop(message("rotate.failed.group", escape(RotationTexts.roots(group)), escape(group.source.display), escape(label)))
            }
        }
    }

    /** Of several distinct [candidates], the one that opens [root]'s envelopes of [label] (and unclaimed ones); stops when none or two do. */
    private suspend fun opening(root: RotationRoot, label: String, candidates: List<CurrentSecret>, found: Found): CurrentSecret {
        val used = LinkedHashSet<Int>()
        for ((envelope, target) in found.envelopes) {
            if (envelope.root != root || envelope.owner is Owner.Other || envelope.origin == Origin.FOREIGN) continue
            currentCoroutineContext().ensureActive()
            val parsed = envelopeOf(target) ?: continue
            val index = withContext(Dispatchers.Default) { candidates.indexOfFirst { opens(parsed, it.secret) } }
            if (index >= 0) used += index
        }
        val discovery = VaultIdentityRegistry.getInstance(project).discovery(root.root)
        fun display(candidate: CurrentSecret): String = sources.sourceOf(discovery, label, sources.standIn(discovery, label, candidate.identity), read = false).display
        if (used.isEmpty()) throw Stop(message("rotate.failed.none", escape(label), escape(root.displayName), escape(root.source.display)))
        if (used.size > 1) {
            val (a, b) = used.take(2).map { candidates[it] }
            throw Stop(message("rotate.failed.two", escape(label), escape(root.displayName), escape(display(a)), escape(display(b))))
        }
        return candidates[used.single()]
    }

    /** The envelope of [target] as the preflight reads it: a value's captured text, or a whole file's content. */
    private suspend fun envelopeOf(target: ReencryptTarget): VaultEnvelope? = when (target) {
        is ReencryptTarget.Value -> target.ref.envelope
        is ReencryptTarget.WholeFile -> readAction { VaultEnvelopes.ofFile(target.file)?.envelope }
        is ReencryptTarget.PlainValue -> null
    }

    /** True when [secret] decrypts [envelope] (the decrypted copy is zeroed at once). */
    private fun opens(envelope: VaultEnvelope, secret: SecretBytes): Boolean {
        val opened = try {
            VaultAes256.decrypt(envelope, secret)
        } catch (_: VaultFormatException) {
            null
        }
        opened?.fill(0)
        return opened != null
    }

    /**
     * Decision 1: before anything is written or stored, the IDE password store entry each root's rotation writes
     * ([VaultSecretsService.rotationEntry]) holds nothing or the old password, and every password file that will be
     * written still holds it (read under its consent). Anything else stops the rotation, naming it. Blocking.
     */
    private fun checkDestinations(label: String, decisions: List<Decision>, olds: Map<RotationRoot, Old>) {
        val secrets = VaultSecretsService.getInstance(project)
        val registry = VaultIdentityRegistry.getInstance(project)
        for (decision in decisions) {
            for (root in decision.group.roots) {
                val old = olds.getValue(root)
                val entry = secrets.rotationEntry(registry.discovery(root.root), label, old.source)
                val state = try {
                    secrets.entryState(entry, label, old.secret)
                } catch (e: Exception) {
                    VaultLog.failure(VaultLog.Operation.PASSWORD_SAFE, e, root.rootKey, label)
                    throw Stop(message("rotate.failed.store"))
                }
                if (state == VaultSecretsService.EntryState.OTHER) throw Stop(message("rotate.failed.entry", escape(label), escape(root.displayName)))
            }
            if (!decision.write) continue
            val target = decision.group.source.file ?: continue
            val name = escape(RotationTexts.file(decision.group.source))
            when (sources.holds(target, olds.getValue(decision.group.roots.first()).secret)) {
                RotationSources.Holds.OLD, RotationSources.Holds.VAULTED -> Unit
                RotationSources.Holds.OTHER -> throw Stop(message("rotate.failed.file.other", name, escape(label)))
                RotationSources.Holds.NOT_READ -> throw Stop(message("rotate.failed.file.unread", name, escape(label)))
                RotationSources.Holds.UNREADABLE -> throw Stop(message("rotate.failed.file.unreadable", name))
            }
        }
    }

    /**
     * D209: the ids of other roots (and the other ids of the chosen roots) that are unlocked in this session and whose
     * secret equals an old password of the rotation, compared in constant time. Nothing is unlocked for it.
     */
    private fun equalPasswords(olds: Map<RotationRoot, Old>, label: String, chosen: Set<String>): List<String> {
        val secrets = VaultSecretsService.getInstance(project)
        val notes = LinkedHashSet<String>()
        for (discovery in VaultIdentityRegistry.getInstance(project).discoveries()) {
            secrets.lease(discovery).use { lease ->
                for (secret in lease.secrets) {
                    if (discovery.rootKey in chosen && secret.label == label) continue
                    if (olds.values.any { old -> same(old.secret, secret.secret) }) notes += message("rotate.equal", discovery.root.displayName, secret.label)
                }
            }
        }
        return notes.toList()
    }

    private fun same(a: SecretBytes, b: SecretBytes): Boolean = a.read { left -> b.read { right -> MessageDigest.isEqual(left, right) } }

    private fun keyOf(scalar: YAMLScalar): String = de.terletzkiy.ansibility.yaml.YamlPaths.keyPath(scalar).joinToString(".").ifEmpty { VaultValuePsi.keyName(scalar).orEmpty() }

    // ------------------------------------------------------------------------------------------------ 3c. what is rotated

    /**
     * D207 and decision 11: what the old password opened is ready when it is the rotated id's (labelled with it,
     * unlabelled for the default identity, or a label without a chain entry); an envelope of another id with its own
     * chain entry whose password is the same stays with that id (left alone with a note), and when that id is locked
     * and cannot be unlocked without asking, the rotation stops. A vaulted password file of a root that is not
     * included stays as it is (a note), or stops the rotation when the chosen roots hold it. An envelope of the id the
     * old password does not open, or that did not parse when it was found, is left alone with the reason; one of
     * another id it does not open is not this rotation's. A new envelope that did not verify, or a well-formed envelope
     * whose plaintext could not be encrypted again (its content is vault data itself), stops the rotation.
     */
    private suspend fun classify(plan: PreparedPlan, label: String, olds: Map<RotationRoot, Old>, leftAlone: MutableList<String>): PreparedPlan {
        val drop = HashSet<ReencryptItem>()
        val others = HashMap<Pair<RotationRoot, String>, Boolean>()
        for (prepared in plan.items) {
            val envelope = prepared.item.tag as Envelope
            when (val outcome = prepared.outcome) {
                is ItemOutcome.Ready -> {
                    envelope.foreign?.let { name ->
                        if (envelope.origin == Origin.FOREIGN) {
                            leftAlone += leftText(envelope, message("rotate.left.foreign", name))
                            drop += prepared.item
                            continue
                        }
                        throw Stop(message("rotate.failed.foreign", escape(leftName(envelope)), escape(name)))
                    }
                    val owner = envelope.owner
                    if (owner is Owner.Other && belongsTo(envelope.root, owner.label, label, olds.getValue(envelope.root).secret, others)) {
                        leftAlone += leftText(envelope, message("rotate.left.other", owner.label))
                        drop += prepared.item
                    }
                }
                ItemOutcome.Unchanged, ItemOutcome.LeftOut -> Unit
                ItemOutcome.NotVerified -> throw Stop(message("rotate.failed.verify", envelope.file.name))
                is ItemOutcome.Failed -> {
                    if (envelope.origin == Origin.FOREIGN) continue
                    if (outcome.failure == VaultFailure.FORMAT && envelope.wellFormed) throw Stop(message("rotate.failed.encrypt", escape(leftName(envelope))))
                    if (envelope.owner != Owner.Id) continue
                    val reason = when (outcome.failure) {
                        VaultFailure.WRONG_SECRET -> message("rotate.left.wrong")
                        VaultFailure.FORMAT, VaultFailure.UNKNOWN_CIPHER -> message("rotate.left.format")
                        else -> AnsibilityVaultBundle.failure(outcome.failure)
                    }
                    leftAlone += leftText(envelope, reason)
                }
                ItemOutcome.Changed, ItemOutcome.Declined -> leftAlone += leftText(envelope, message("rotate.left.changed"))
            }
        }
        return if (drop.isEmpty()) plan else plan.without(drop, ItemOutcome.LeftOut)
    }

    /**
     * Whether an envelope labelled [other] in [root] that the old password of [label] opens is [other]'s (decision 11):
     * true when one of [other]'s unlocked secrets is that same password (distinct passwords cannot both open it). A
     * locked [other] is unlocked without asking anything; when it stays locked, the rotation stops.
     */
    private suspend fun belongsTo(root: RotationRoot, other: String, label: String, old: SecretBytes, cache: MutableMap<Pair<RotationRoot, String>, Boolean>): Boolean =
        cache.getOrPut(root to other) {
            val secrets = VaultSecretsService.getInstance(project)
            var all = secrets.secretsOf(root.root, other)
            if (all.isEmpty()) {
                secrets.unlock(root.root, other, verify = null, interactive = false)
                all = secrets.secretsOf(root.root, other)
                if (all.isEmpty()) throw Stop(message("rotate.failed.other.locked", escape(root.displayName), escape(other), escape(label)))
            }
            try {
                all.any { same(it.secret, old) }
            } finally {
                all.forEach(CurrentSecret::close)
            }
        }

    /** Decision 18: a whole-file vault whose decrypted tab has unsaved edits stops the rotation (save or discard it first). */
    private suspend fun stopOnModifiedTabs(plan: PreparedPlan) {
        val modified = onEdt {
            val tabs = DecryptedVaultTabs.getInstance(project)
            plan.items.firstOrNull { it.ready != null && it.item.target is ReencryptTarget.WholeFile && tabs.sessionOf(it.item.file)?.isModified == true }?.item
        } ?: return
        throw Stop(message("rotate.stale.tab", escape(modified.file.name)))
    }

    /**
     * Decision 1, after the preflight: a root with well-formed envelopes of the id must have at least one the old
     * password opened (else that password is not the id's), and the rotation as a whole must rotate something.
     */
    private fun proveOpened(roots: List<RotationRoot>, label: String, found: Found, plan: PreparedPlan) {
        val ready = plan.items.filter { it.ready != null }.map { it.item.tag as Envelope }
        for (root in roots) {
            val ofId = found.envelopes.any { (envelope, _) -> envelope.root == root && envelope.owner == Owner.Id && envelope.origin != Origin.FOREIGN && envelope.wellFormed }
            if (ofId && ready.none { it.root == root && it.owner == Owner.Id }) {
                throw Stop(message("rotate.failed.none", escape(label), escape(root.displayName), escape(root.source.display)))
            }
        }
        if (ready.isEmpty()) {
            val root = roots.first()
            throw Stop(message("rotate.failed.none", escape(label), escape(root.displayName), escape(root.source.display)))
        }
    }

    private fun leftName(envelope: Envelope): String =
        relative(envelope.root.root, envelope.file) + (envelope.key?.let { ": $it" } ?: "")

    @Nls
    private fun leftText(envelope: Envelope, @Nls reason: String): String {
        val path = relative(envelope.root.root, envelope.file)
        return if (envelope.key == null) message("rotate.left.file", escape(path), reason) else message("rotate.left.value", escape(path), escape(envelope.key), reason)
    }

    private fun relative(root: AnsibleRoot, file: VirtualFile): String = VfsUtilCore.getRelativePath(file, root.dir) ?: file.presentableUrl

    /** The review's model (D209): per root what will be rewritten, left alone, equal passwords, where the new password goes. */
    private suspend fun review(
        label: String, roots: List<RotationRoot>, plan: PreparedPlan, leftAlone: List<String>, equal: List<String>, decisions: List<Decision>,
    ): RotationReview = readAction {
        val ready = plan.items.filter { it.ready != null }
        val reviewRoots = roots.map { root ->
            val own = ready.filter { (it.item.tag as Envelope).root == root }
            ReviewRoot(
                root.displayName, own.count { it.item.target is ReencryptTarget.Value }, own.count { it.item.target is ReencryptTarget.WholeFile },
                own.map { relative(root.root, it.item.file) }.distinct(),
            )
        }
        val destinations = ArrayList<String>()
        destinations += message("rotate.review.store", roots.joinToString(", ") { it.displayName })
        for (decision in decisions) destinations += destinationText(decision)
        RotationReview(label, reviewRoots, leftAlone, equal, destinations, ready.any { it.item.target is ReencryptTarget.Value })
    }

    @Nls
    private fun destinationText(decision: Decision): String {
        val source = decision.group.source
        return when {
            decision.write -> message("rotate.dialog.write.file", RotationTexts.file(source))
            decision.vaulted -> message("rotate.review.vaulted", source.display)
            source.destination == Destination.PASSWORD_FILE && !decision.group.writable -> RotationTexts.destination(decision.group)
            source.destination == Destination.PASSWORD_FILE -> message("rotate.review.not.written", source.display)
            else -> RotationTexts.destination(decision.group)
        }
    }

    // ------------------------------------------------------------------------------------------------ 5. writes

    private suspend fun write(
        label: String, roots: List<RotationRoot>, plan: PreparedPlan, expected: Map<VirtualFile, ByteArray>, olds: Map<RotationRoot, Old>,
        password: NewPassword, new: SecretBytes, decisions: List<Decision>, leftAlone: List<String>, equal: List<String>, regenerated: Int,
    ) {
        // Decision 7, then decision 1 again: nothing changed during the review, before anything is stored.
        onEdt { writer.changedOnDisk(plan, expected) }?.let { throw Stop(staleText(it)) }
        withContext(Dispatchers.IO) { checkDestinations(label, decisions, olds) }
        val secrets = VaultSecretsService.getInstance(project)
        val registry = VaultIdentityRegistry.getInstance(project)
        val snapshots = ArrayList<StoreSnapshot>()
        val newChars = password.chars()
        try {
            // 1. The IDE password store (D211, D212): durable on this machine before any vault file changes.
            step(Step.STORE)
            val entries = roots.associateWith { secrets.rotationEntry(registry.discovery(it.root), label, olds.getValue(it).source) }
            val stored = withContext(Dispatchers.IO) {
                runCatching {
                    val now = System.currentTimeMillis()
                    for (root in roots) {
                        snapshots += secrets.snapshotStore(root.root, label, entries.getValue(root))
                        val previous = olds.getValue(root).secret.read { it.copyOf() }
                        try {
                            secrets.storeRotation(root.root, label, entries.getValue(root), newChars, previous, now)
                        } finally {
                            previous.fill(0)
                        }
                    }
                }
            }
            if (stored.isFailure) {
                restore(snapshots)
                throw Stop(message("rotate.failed.store"))
            }

            // 2. Every vault file, all or nothing, not undoable (D210). The store goes back on any failure before the
            // files are written (decision 6); the writer reports a failure midway itself.
            step(Step.FILES)
            val outcome = try {
                onEdt { writer.writeAllFileSystem(plan, expected, message("rotate.label.before", label), message("rotate.label.after", label)) }
            } catch (e: Throwable) {
                restore(snapshots)
                if (e is CancellationException || e is VaultCorpusGuardException) throw e
                VaultLog.failure(VaultLog.Operation.ENCRYPT, e)
                throw Stop(message("rotate.failed.write.error"))
            }
            when (outcome) {
                is ReencryptionWriter.FileSystemOutcome.NotWritten -> {
                    restore(snapshots)
                    throw Stop(staleText(outcome.stale))
                }
                is ReencryptionWriter.FileSystemOutcome.Failed -> {
                    if (outcome.notRestored.isEmpty()) {
                        restore(snapshots)
                        throw Stop(message("rotate.failed.write", escape(outcome.file.name)))
                    }
                    // Files that still hold new envelopes need the new password: it stays stored and unlocked (decision 6).
                    return partial(label, roots, outcome, new, entries)
                }
                is ReencryptionWriter.FileSystemOutcome.Written, ReencryptionWriter.FileSystemOutcome.Nothing -> Unit
            }
            renewVaultedConsents(plan)

            // 3. The sources that may be written (D211); a failure here leaves the rest as it is (summary, Copy).
            step(Step.SOURCES)
            val written = ArrayList<RotationGroup>()
            val failed = ArrayList<RotationGroup>()
            val changed = ArrayList<RotationGroup>()
            val bytes = new.read { it.copyOf() }
            try {
                for (decision in decisions) {
                    val file = decision.group.source.file ?: continue
                    if (!decision.write) continue
                    val old = olds.getValue(decision.group.roots.first()).secret
                    when (withContext(Dispatchers.IO) { sources.replacePasswordFile(file, bytes, old) }) {
                        RotationSources.Replaced.WRITTEN -> written += decision.group
                        RotationSources.Replaced.CHANGED -> changed += decision.group
                        RotationSources.Replaced.FAILED -> failed += decision.group
                    }
                }
            } finally {
                bytes.fill(0)
            }

            // 4. The secret in memory (D213): the id stays unlocked, with the new password.
            step(Step.MEMORY)
            for (root in roots) secrets.switchRotated(root.root, label, olds.getValue(root).secret, new, entries.getValue(root))
            RotationHints.getInstance(project).rotatedHere(roots.map { it.rootKey }, label)
            val yourself = decisions.filter { !it.write && it.group.source.destination in setOf(Destination.YOURSELF, Destination.PASSWORD_FILE) }.map { it.group }
            recordPending(label, decisions.map { it.group }, failed + changed + yourself, olds)

            val historyLabel = (outcome as? ReencryptionWriter.FileSystemOutcome.Written)?.labelBefore
            val summary = summary(label, roots, plan, regenerated, written, failed, changed, yourself, leftAlone, equal, historyLabel)
            lastSummary = summary
            onEdt { notify(summary) }
        } finally {
            newChars.fill('\u0000')
            snapshots.forEach(StoreSnapshot::close)
        }
    }

    /**
     * Decision 9: every root of a group whose source the IDE did not write ([unwritten]) gets a pending rotation (its
     * source's chain slot), so unlocks skip that source while it gives the old password and the stored new password
     * comes first; a group whose source now holds the new password ends an earlier pending rotation.
     */
    private fun recordPending(label: String, groups: List<RotationGroup>, unwritten: List<RotationGroup>, olds: Map<RotationRoot, Old>) {
        val state = VaultUserState.getInstance()
        val registry = VaultIdentityRegistry.getInstance(project)
        val now = System.currentTimeMillis()
        for (group in groups) {
            for (root in group.roots) {
                val canonical = registry.discovery(root.root).canonicalRootPath
                if (group in unwritten) state.recordPendingRotation(PendingRotationRecord(canonical, label, olds.getValue(root).source?.slot, now))
                else state.forgetPendingRotation(canonical, label)
            }
        }
    }

    /** Decision 2: a vaulted password file the rotation re-encrypted keeps its consent (the IDE wrote its new content). */
    private suspend fun renewVaultedConsents(plan: PreparedPlan) {
        val renewals = plan.items.mapNotNull { prepared ->
            val target = (prepared.item.tag as Envelope).consentTarget ?: return@mapNotNull null
            prepared.ready?.envelope?.formatBytes()?.let { target to it }
        }
        if (renewals.isEmpty()) return
        withContext(Dispatchers.IO) { for ((target, bytes) in renewals) sources.renewConsent(target, bytes) }
    }

    /**
     * Decision 6, the case that cannot be all or nothing: writing a file failed and some written files could not be put
     * back. They need the new password, so it stays in the IDE password store and is unlocked too ([new] next to the
     * old secrets); the password source keeps the old one. An error notification names the files and offers Copy New
     * Password.
     */
    private suspend fun partial(label: String, roots: List<RotationRoot>, outcome: ReencryptionWriter.FileSystemOutcome.Failed, new: SecretBytes, entries: Map<RotationRoot, String>) {
        val secrets = VaultSecretsService.getInstance(project)
        for (root in roots) secrets.addRotated(root.root, label, new)
        val text = message("rotate.failed.partial", escape(outcome.file.name), escape(outcome.notRestored.joinToString(", ") { it.name }))
        val first = roots.first()
        onEdt {
            val notification = Notification(GROUP, message("notification.title"), text, NotificationType.ERROR)
            notification.addAction(NotificationAction.createSimple(message("rotate.action.copy")) { copyFromStore(first.root, label, entries.getValue(first)) })
            notification.notify(project)
        }
    }

    private fun step(step: Step) {
        onStepForTests?.invoke(step)
    }

    private suspend fun restore(snapshots: List<StoreSnapshot>) {
        val secrets = VaultSecretsService.getInstance(project)
        withContext(Dispatchers.IO) { snapshots.asReversed().forEach(secrets::restoreStore) }
    }

    @Nls
    private fun staleText(stale: ReencryptionWriter.Stale): String {
        val name = escape(stale.file.name)
        return when (stale) {
            is ReencryptionWriter.Stale.ReadOnly -> message("rotate.stale.read.only", name)
            is ReencryptionWriter.Stale.TabModified -> message("rotate.stale.tab", name)
            is ReencryptionWriter.Stale.Changed -> message("rotate.stale.changed", name)
        }
    }

    // ------------------------------------------------------------------------------------------------ 6. summary

    private fun summary(
        label: String, roots: List<RotationRoot>, plan: PreparedPlan, regenerated: Int, written: List<RotationGroup>, failed: List<RotationGroup>,
        changed: List<RotationGroup>, yourself: List<RotationGroup>, leftAlone: List<String>, equal: List<String>, historyLabel: String?,
    ): RotationSummary {
        val ready = plan.items.filter { it.ready != null }
        val values = ready.count { it.item.target is ReencryptTarget.Value }
        val vaultFiles = ready.count { it.item.target is ReencryptTarget.WholeFile }
        val files = ready.map { it.item.file }.distinct().size
        val parts = ArrayList<String>()
        parts += message("rotate.done", escape(label), values, vaultFiles, files, escape(roots.joinToString(", ") { it.displayName }))
        if (regenerated > 0) parts += message("rotate.done.regenerated", regenerated)
        parts += message("rotate.done.store")
        if (written.isNotEmpty()) parts += message("rotate.done.written", escape(written.joinToString(", ") { RotationTexts.file(it.source) }))
        if (failed.isNotEmpty()) parts += message("rotate.done.not.written", escape(failed.joinToString(", ") { RotationTexts.file(it.source) }))
        if (changed.isNotEmpty()) parts += message("rotate.done.changed", escape(changed.joinToString(", ") { RotationTexts.file(it.source) }))
        if (yourself.isNotEmpty()) parts += message("rotate.done.yourself", escape(yourself.joinToString(", ") { it.source.display }))
        parts += message("rotate.done.elsewhere")
        parts += message("rotate.done.history")
        if (leftAlone.isNotEmpty()) parts += message("rotate.done.left.alone", leftAlone.take(MAX_LISTED).joinToString("; ") + more(leftAlone.size))
        if (equal.isNotEmpty()) parts += equal.joinToString(" ")
        val unwritten = (failed + changed + yourself).map { group -> UnwrittenSource(group.roots, group.source) }
        return RotationSummary(
            label, roots.map { it.root }, values, vaultFiles, files, regenerated, written.map { RotationTexts.file(it.source) }, unwritten, parts.joinToString("<br>"), historyLabel,
        )
    }

    private fun more(size: Int): String = if (size > MAX_LISTED) "; " + message("rotate.review.more", size - MAX_LISTED) else ""

    /** The summary notification (D213), group `Ansibility.Vault`, with Regenerate Secrets…, and Copy New Password and Check for sources not written. EDT. */
    private fun notify(summary: RotationSummary) {
        val notification = Notification(GROUP, message("notification.title"), summary.text, NotificationType.INFORMATION)
        notification.addAction(NotificationAction.createSimple(message("rotate.action.regenerate")) { regenerateAfter(summary) })
        if (summary.unwritten.isNotEmpty()) {
            notification.addAction(NotificationAction.createSimple(message("rotate.action.copy")) { copyNewPassword(summary) })
            notification.addAction(NotificationAction.createSimple(message("rotate.action.check")) { check(summary) })
        }
        notification.notify(project)
    }

    /** D213: Regenerate Secrets… for the rotated roots (the old values are in git history). EDT. */
    fun regenerateAfter(summary: RotationSummary) {
        ThreadingAssertions.assertEventDispatchThread()
        RegenerateService.getInstance(project).regenerate(summary.roots.filter { it.dir.isValid }.map { it.dir })
    }

    /**
     * Copy New Password: the rotated password as the IDE password store keeps it (the root's own entry, which Check
     * compares with), never the chain, which after the idle lock may give the old one again (review, decision 9). The
     * Keychain is read in the background; the copy goes through [VaultClipboard]. EDT.
     */
    fun copyNewPassword(summary: RotationSummary) {
        ThreadingAssertions.assertEventDispatchThread()
        val root = summary.unwritten.firstOrNull()?.roots?.firstOrNull()?.root ?: summary.roots.firstOrNull() ?: return
        val discovery = VaultIdentityRegistry.getInstance(project).discovery(root)
        copyFromStore(root, summary.label, de.terletzkiy.ansibility.vault.identity.VaultPasswordSafeKeys.serviceName(discovery.canonicalRootPath, summary.label))
    }

    /** Copies the entry [serviceName] of [label] (read off the EDT) through [VaultClipboard]. EDT. */
    private fun copyFromStore(root: AnsibleRoot, label: String, serviceName: String) {
        lastJob = scope.launch(ModalityState.current().asContextElement()) {
            val secrets = VaultSecretsService.getInstance(project)
            val chars = withContext(Dispatchers.IO) { secrets.readEntry(serviceName, label) }
                ?: return@launch onEdt { VaultUiFeedback.info(project, null, message("rotate.copy.missing", escape(label), escape(root.displayName))) }
            try {
                onEdt {
                    VaultClipboard.getInstance().copy(String(chars))
                    VaultUiFeedback.info(project, null, message("rotate.copied"))
                }
            } finally {
                chars.fill('\u0000')
            }
        }
    }

    /**
     * Check (D211): reads each source that was not written again through the normal load path and compares it with the
     * new password and the previous one (both from the IDE password store), in constant time; the answer says
     * "matches", "still the old password" or "different", never a value. "Matches" ends the pending rotation of the
     * source's roots (decision 9). EDT; the reads run in the background.
     */
    fun check(summary: RotationSummary) {
        ThreadingAssertions.assertEventDispatchThread()
        lastJob = scope.launch(ModalityState.current().asContextElement()) {
            val lines = ArrayList<String>()
            withBackgroundProgress(project, message("rotate.check.progress")) {
                for (unwritten in summary.unwritten) lines += message("rotate.check.result", escape(unwritten.source.display), checkText(unwritten, summary.label))
            }
            onEdt { VaultUiFeedback.info(project, null, lines.joinToString("<br>")) }
        }
    }

    @Nls
    private suspend fun checkText(unwritten: UnwrittenSource, label: String): String {
        val root = unwritten.roots.first()
        val identity = unwritten.source.identity ?: return message("rotate.check.unreadable")
        val secrets = VaultSecretsService.getInstance(project)
        val loaded = try {
            secrets.loadSourceAgain(root.root, identity)
        } catch (e: CancellationException) {
            throw e
        } catch (e: VaultCorpusGuardException) {
            throw e
        } catch (e: Exception) {
            VaultLog.failure(VaultLog.Operation.LOAD_SOURCE, e, unwritten.source.display, label)
            null
        } ?: return message("rotate.check.unreadable")
        return loaded.use { secret ->
            val stored = withContext(Dispatchers.IO) { secrets.readRotatedPassword(root.root, label) }
            val matches = stored?.use { same(it, secret) } == true
            if (matches) {
                val registry = VaultIdentityRegistry.getInstance(project)
                for (member in unwritten.roots) VaultUserState.getInstance().forgetPendingRotation(registry.discovery(member.root).canonicalRootPath, label)
                return@use message("rotate.check.matches")
            }
            val previous = withContext(Dispatchers.IO) { secrets.readPreviousPassword(root.root, label) }
            if (previous?.use { same(it, secret) } == true) message("rotate.check.old") else message("rotate.check.different")
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private fun escape(text: String): String = StringUtil.escapeXmlEntities(text)

    /** The bytes of [file] on disk, or null. Blocking. */
    private fun diskBytes(file: VirtualFile): ByteArray? {
        val path = file.toNioPathOrNull() ?: return null
        return try {
            Files.readAllBytes(path)
        } catch (_: IOException) {
            null
        }
    }

    /** The first bytes of [path] (enough for `$ANSIBLE_VAULT`), or null. Blocking. */
    private fun head(path: Path): ByteArray? = try {
        Files.newInputStream(path).use { it.readNBytes(VaultHeaderInfo.MAGIC.length) }
    } catch (_: IOException) {
        null
    }

    /** The label of a whole-file vault's header line, from its first bytes; null for an unlabelled one. */
    private fun headerLabel(head: ByteArray): String? = String(head, Charsets.ISO_8859_1).lineSequence().first().split(';').getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() }

    /** True when [path] contains `$ANSIBLE_VAULT` (a YAML file larger than the index reads). Blocking. */
    private fun mentionsVault(path: Path): Boolean {
        val mark = VaultHeaderInfo.MAGIC.toByteArray(Charsets.US_ASCII)
        val bytes = try {
            Files.readAllBytes(path)
        } catch (_: IOException) {
            return false
        }
        return (0..bytes.size - mark.size).any { start -> mark.indices.all { bytes[start + it] == mark[it] } }
    }

    /** The name of a password file target as the dialog shows it. */
    private fun sourceName(target: ConsentTarget): String = target.display

    /** Whether a run got as far as writing (then Cancel no longer means "nothing was written"). */
    private class RunState {
        @Volatile
        var writing: Boolean = false
    }

    private suspend fun <T> onEdt(block: () -> T): T = withContext(Dispatchers.EDT) { block() }

    companion object {
        /** The vault notification group (sticky; plan amendment R21). */
        const val GROUP: String = "Ansibility.Vault"

        private const val MAX_LISTED = 10

        /** The largest whole-file vault the IDE re-encrypts (the envelope reader's limit). */
        private const val MAX_WHOLE_FILE = 16L * 1024 * 1024

        /** The date of a previous password as the settings page shows it. */
        @Nls
        fun date(millis: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

        fun getInstance(project: Project): RotationService = project.service()
    }
}

/** A password source a rotation did not write, and the roots of its group (for Copy, Check and the pending state). */
internal class UnwrittenSource(val roots: List<RotationRoot>, val source: RotationSource) {
    override fun toString(): String = "UnwrittenSource(${source.key})"
}

/**
 * What a rotation did (plan amendment R29, D213): the id, the roots, the counts, the files written, the sources the IDE
 * did not write (for Copy and Check), and the summary [text] (HTML). Never a password or a value.
 */
class RotationSummary internal constructor(
    val label: String,
    val roots: List<AnsibleRoot>,
    val values: Int,
    val vaultFiles: Int,
    val files: Int,
    val regenerated: Int,
    val written: List<String>,
    internal val unwritten: List<UnwrittenSource>,
    @Nls val text: String,
    /** The id of the Local History label put before the vault files were written, or null when none was written. */
    val historyLabel: String?,
) {
    override fun toString(): String = "RotationSummary($label, $values values, $vaultFiles vault files)"
}
