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
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.ide.progress.withModalProgress
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.ThreadingAssertions
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VaultEnvelopeKind
import de.terletzkiy.ansibility.api.VaultFailure
import de.terletzkiy.ansibility.api.VaultPurpose
import de.terletzkiy.ansibility.api.VaultUnlockResult
import de.terletzkiy.ansibility.index.InContext
import de.terletzkiy.ansibility.index.vault.VaultCounts
import de.terletzkiy.ansibility.index.vault.VaultIndexEntry
import de.terletzkiy.ansibility.index.vault.VaultIndexQueries
import de.terletzkiy.ansibility.semantics.vault.SecretBytes
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.vault.AnsibilityVaultBundle
import de.terletzkiy.ansibility.vault.VaultCorpusGuardException
import de.terletzkiy.ansibility.vault.VaultLog
import de.terletzkiy.ansibility.vault.actions.VaultFileOperations
import de.terletzkiy.ansibility.vault.actions.VaultFileSelection
import de.terletzkiy.ansibility.vault.actions.VaultValuePsi
import de.terletzkiy.ansibility.vault.actions.VaultValueRef
import de.terletzkiy.ansibility.vault.envelope.VaultEnvelopes
import de.terletzkiy.ansibility.vault.identity.VaultDiscovery
import de.terletzkiy.ansibility.vault.identity.VaultIdentityRegistry
import de.terletzkiy.ansibility.vault.reencrypt.Decryption
import de.terletzkiy.ansibility.vault.reencrypt.Encryption
import de.terletzkiy.ansibility.vault.reencrypt.ItemOutcome
import de.terletzkiy.ansibility.vault.reencrypt.NewPlaintext
import de.terletzkiy.ansibility.vault.reencrypt.PreparedPlan
import de.terletzkiy.ansibility.vault.reencrypt.ReencryptItem
import de.terletzkiy.ansibility.vault.reencrypt.ReencryptTarget
import de.terletzkiy.ansibility.vault.reencrypt.ReencryptionEngine
import de.terletzkiy.ansibility.vault.reencrypt.ReencryptionRun
import de.terletzkiy.ansibility.vault.reencrypt.ReencryptionWriter
import de.terletzkiy.ansibility.vault.regenerate.RegenerateService
import de.terletzkiy.ansibility.vault.secrets.StoreSnapshot
import de.terletzkiy.ansibility.vault.secrets.VaultSecretsService
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
import org.jetbrains.yaml.psi.YAMLScalar
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date

/**
 * Rotate Vault Password… (plan amendment R29, Part 1, D204–D214): every vault the current password of a vault id
 * opens, in the roots you choose, is encrypted again with a new password, and the new password goes where the id's
 * password comes from.
 *
 * The flow, on the re-encryption engine ([ReencryptionEngine], [ReencryptionWriter] write mode 3):
 * 1. the roots and their counts per id from the `ansible.vault` index (a smart read), each id's password source per
 *    root ([RotationSources]); roots sharing a source are one group (D206);
 * 2. the dialog ([RotationPrompts.chooseRotation], D205): id, roots, the new password (generated or typed);
 * 3. the preflight (D207, D209), in the background with progress and Cancel: the id of each chosen root is unlocked
 *    through the normal path (asking only what an unlock asks), its current secret taken, and every envelope of the
 *    chosen roots is decrypted with that one secret; those it opens are encrypted with the new password, header kept
 *    (D208), and verified. Envelopes of the id it does not open, and malformed ones, are left alone; files with unsaved
 *    edits in a decrypted tab too. Ids of other unlocked roots whose secret equals the old one are reported (compared
 *    in constant time, never unlocked for it);
 * 4. the review ([RotationPrompts.review]) with "Also regenerate secrets…" (D214: Regenerate Secrets…'s selection over
 *    the same values, new values encrypted with the new password in the same pass);
 * 5. the writes, each only after the one before succeeded (D210–D213): the new password into the IDE password store
 *    (and the old one into its "before rotation" entry), all vault files through the file system (not undoable, all
 *    or nothing, Local History labels), the password files that may be written (atomically, 0600, consent renewed),
 *    then the secret in memory; a refused vault write puts the password store back;
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

    /** The running or last rotation, for tests that wait for it. */
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
        start(if (selection.isEmpty()) Preselect.All else Preselect.Files(selection), null)
    }

    /** From Settings › Vault (D204): the root [rootKey] (all roots when null) and the id [identity] preselected. EDT. */
    fun rotate(rootKey: String?, identity: String?) {
        ThreadingAssertions.assertEventDispatchThread()
        start(rootKey?.let(Preselect::Root) ?: Preselect.All, identity)
    }

    // ------------------------------------------------------------------------------------------------ the flow

    private fun start(preselect: Preselect, identity: String?) {
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
     * root that holds vaults of it, with its password source, grouped by source. A root chosen on the settings page
     * ([explicit]) also offers the ids it configures without vaults. A root without vaults of an id is never listed for
     * it otherwise: rotating would only ask for its password. Null when there is no id at all. Blocking (sources may
     * read a consented `.env.local`); off the EDT.
     */
    private fun request(scanned: List<ScannedRoot>, preselected: Set<String>, identity: String?, explicit: Boolean): RotationRequest? {
        val involved = scanned.filter { it.rootKey in preselected }
        val labels = (involved.flatMap { it.counts.keys } + if (explicit) involved.flatMap { it.discovery.labels } else emptyList()).distinct()
        if (labels.isEmpty()) return null
        val identities = labels.map { label ->
            val holders = scanned.filter { label in it.counts || explicit && it.rootKey in preselected && label in it.discovery.labels }
            val rows = holders.map { scannedRoot ->
                RotationRoot(
                    scannedRoot.root, scannedRoot.rootKey, scannedRoot.root.displayName, scannedRoot.counts[label] ?: VaultCounts.NONE,
                    sources.sourceOf(scannedRoot.discovery, label), scannedRoot.rootKey in preselected,
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

    /**
     * One envelope of a chosen root: where it is, its effective label, whether it parsed when it was found, and how the
     * review names it. Not secret.
     */
    private class Envelope(val root: RotationRoot, val file: VirtualFile, val effectiveLabel: String, val key: String?, val wellFormed: Boolean) {
        override fun toString(): String = "Envelope(${file.name}, $key, $effectiveLabel)"
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
        val olds = LinkedHashMap<RotationRoot, SecretBytes>()
        try {
            for (root in roots) {
                currentCoroutineContext().ensureActive()
                olds[root] = oldSecret(root, label)
            }
            val decisions = withContext(Dispatchers.IO) {
                groups.map { group ->
                    val vaulted = group.source.file?.let(sources::isVaulted) == true
                    Decision(group, group.write && group.source.destination == Destination.PASSWORD_FILE && !vaulted, vaulted)
                }
            }
            val equal = equalPasswords(olds, label, roots.mapTo(HashSet()) { it.rootKey })
            val envelopes = smartReadAction(project) { envelopes(roots) }
            val items = envelopes.map { (envelope, target) ->
                ReencryptItem(envelope.root.root, target, Decryption.Explicit(olds.getValue(envelope.root)), NewPlaintext.Keep, Encryption.Explicit(new, null, keepHeader = true), envelope)
            }
            var plan = engine.prepare(items, run)
            val leftAlone = ArrayList<String>()
            plan = classify(plan, label, leftAlone)
            plan = withoutModifiedTabs(plan, leftAlone)
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
                    plan = PreparedPlan(plan.items.map { byTarget[it.item.target] ?: it }, plan.sequences)
                    regenerated = chosen.size
                }
            }
            state.writing = true
            withContext(NonCancellable) { write(label, roots, plan, olds, password, new, decisions, leftAlone, equal, regenerated) }
        } catch (stop: Stop) {
            onEdt { if (stop.cancelled) VaultUiFeedback.info(project, null, stop.text) else VaultUiFeedback.error(project, null, stop.text) }
        } finally {
            olds.values.forEach(SecretBytes::zero)
        }
    }

    /**
     * The id's current secret in [root] (a copy the caller zeroes), after the normal unlock when it is locked (the
     * prompt checks a typed password against an envelope of the id). Stops the rotation when the unlock fails, or when
     * the secret came from another source than the dialog showed (D206: the groups were built on it).
     */
    private suspend fun oldSecret(root: RotationRoot, label: String): SecretBytes {
        val secrets = VaultSecretsService.getInstance(project)
        var current = secrets.currentSecret(root.root, label)
        if (current == null) {
            val verify = smartReadAction(project) { firstEnvelope(root, label) }
            when (val result = secrets.unlock(root.root, label, verify)) {
                is VaultUnlockResult.Failed -> throw if (result.failure == VaultFailure.CANCELLED) Stop(message("rotate.cancelled"), cancelled = true)
                else Stop(message("rotate.failed.unlock", root.displayName, AnsibilityVaultBundle.failure(result.failure)))
                is VaultUnlockResult.Unlocked -> Unit
            }
            current = secrets.currentSecret(root.root, label) ?: throw Stop(message("rotate.failed.unlock", root.displayName, AnsibilityVaultBundle.failure(VaultFailure.LOCKED)))
        }
        current.use {
            val discovery = VaultIdentityRegistry.getInstance(project).discovery(root.root)
            val actual = withContext(Dispatchers.IO) { sources.sourceOf(discovery, label, it.identity, read = true) }
            if (actual.key != root.source.key) throw Stop(message("rotate.failed.source", root.displayName, actual.display))
            return it.secret.copy()
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

    /**
     * D209: the ids of other roots (and the other ids of the chosen roots) that are unlocked in this session and whose
     * secret equals an old password of the rotation, compared in constant time. Nothing is unlocked for it.
     */
    private fun equalPasswords(olds: Map<RotationRoot, SecretBytes>, label: String, chosen: Set<String>): List<String> {
        val secrets = VaultSecretsService.getInstance(project)
        val notes = LinkedHashSet<String>()
        for (discovery in VaultIdentityRegistry.getInstance(project).discoveries()) {
            secrets.lease(discovery).use { lease ->
                for (secret in lease.secrets) {
                    if (discovery.rootKey in chosen && secret.label == label) continue
                    if (olds.values.any { old -> same(old, secret.secret) }) notes += message("rotate.equal", discovery.root.displayName, secret.label)
                }
            }
        }
        return notes.toList()
    }

    private fun same(a: SecretBytes, b: SecretBytes): Boolean = a.read { left -> b.read { right -> MessageDigest.isEqual(left, right) } }

    /** Every envelope of the chosen [roots] with its engine target: whole-file vaults and the `!vault` values of YAML files. Smart read. */
    private fun envelopes(roots: List<RotationRoot>): List<Pair<Envelope, ReencryptTarget>> {
        val all = AnsibleWorkspace.getInstance(project).roots()
        val registry = VaultIdentityRegistry.getInstance(project)
        val psi = PsiManager.getInstance(project)
        val result = ArrayList<Pair<Envelope, ReencryptTarget>>()
        for (root in roots) {
            val defaultIdentity = registry.discovery(root.root).config.defaultIdentity
            for ((file, entries) in envelopesOf(root.root, all).groupBy { it.file }) {
                if (entries.any { it.value.kind == VaultEnvelopeKind.FILE }) {
                    val entry = entries.first().value
                    result += Envelope(root, file, entry.labelOrDefault(defaultIdentity), null, entry.wellFormed) to ReencryptTarget.WholeFile(file)
                    continue
                }
                val psiFile = psi.findFile(file) ?: continue
                for (scalar in VaultValuePsi.vaultScalars(psiFile)) {
                    val ref = VaultValueRef.of(scalar) ?: continue
                    val label = VaultValuePsi.header(scalar)?.label ?: defaultIdentity
                    result += Envelope(root, file, label, keyOf(scalar), ref.envelope != null) to ReencryptTarget.Value(ref)
                }
            }
        }
        return result
    }

    private fun keyOf(scalar: YAMLScalar): String = de.terletzkiy.ansibility.yaml.YamlPaths.keyPath(scalar).joinToString(".").ifEmpty { VaultValuePsi.keyName(scalar).orEmpty() }

    /**
     * D207: what the old password opened is ready; an envelope of the id it does not open, or that did not parse when it
     * was found, is left alone with the reason; an envelope of another id it does not open is not this rotation's. A new
     * envelope that did not verify, or a well-formed envelope whose plaintext could not be encrypted again (its content
     * is vault data itself), stops the rotation: the old password opens it, so it may not stay behind.
     */
    private fun classify(plan: PreparedPlan, label: String, leftAlone: MutableList<String>): PreparedPlan {
        for (prepared in plan.items) {
            val envelope = prepared.item.tag as Envelope
            when (val outcome = prepared.outcome) {
                is ItemOutcome.Ready, ItemOutcome.Unchanged, ItemOutcome.LeftOut -> Unit
                ItemOutcome.NotVerified -> throw Stop(message("rotate.failed.verify", envelope.file.name))
                is ItemOutcome.Failed -> {
                    if (outcome.failure == VaultFailure.FORMAT && envelope.wellFormed) throw Stop(message("rotate.failed.encrypt", escape(leftName(envelope))))
                    if (envelope.effectiveLabel != label) continue
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
        return plan
    }

    /** D209: a whole-file vault whose decrypted tab has unsaved edits is left alone (saving the tab encrypts it with the new password). */
    private suspend fun withoutModifiedTabs(plan: PreparedPlan, leftAlone: MutableList<String>): PreparedPlan {
        val modified = onEdt {
            val tabs = DecryptedVaultTabs.getInstance(project)
            plan.items.filter { it.ready != null && it.item.target is ReencryptTarget.WholeFile && tabs.sessionOf(it.item.file)?.isModified == true }.map { it.item }
        }
        if (modified.isEmpty()) return plan
        for (item in modified) leftAlone += leftText(item.tag as Envelope, message("rotate.left.tab"))
        return plan.without(modified.toSet(), ItemOutcome.LeftOut)
    }

    private fun leftName(envelope: Envelope): String =
        relative(envelope.root.root, envelope.file) + (envelope.key?.let { ": $it" } ?: "")

    @Nls
    private fun leftText(envelope: Envelope, @Nls reason: String): String {
        val path = relative(envelope.root.root, envelope.file)
        return if (envelope.key == null) message("rotate.left.file", escape(path), reason) else message("rotate.left.value", escape(path), escape(envelope.key), reason)
    }

    private fun relative(root: AnsibleRoot, file: VirtualFile): String = VfsUtilCore.getRelativePath(file, root.dir) ?: file.name

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
            decision.write -> message("rotate.dialog.write.file", source.display)
            decision.vaulted -> message("rotate.review.vaulted", source.display)
            source.destination == Destination.PASSWORD_FILE -> message("rotate.review.not.written", source.display)
            else -> RotationTexts.destination(decision.group)
        }
    }

    // ------------------------------------------------------------------------------------------------ 5. writes

    private suspend fun write(
        label: String, roots: List<RotationRoot>, plan: PreparedPlan, olds: Map<RotationRoot, SecretBytes>, password: NewPassword, new: SecretBytes,
        decisions: List<Decision>, leftAlone: List<String>, equal: List<String>, regenerated: Int,
    ) {
        onEdt { writer.staleFile(plan) }?.let { throw Stop(staleText(it)) }
        val secrets = VaultSecretsService.getInstance(project)
        val snapshots = ArrayList<StoreSnapshot>()
        val newChars = password.chars()
        try {
            // 1. The IDE password store (D211, D212): durable on this machine before any vault file changes.
            step(Step.STORE)
            val stored = withContext(Dispatchers.IO) {
                runCatching {
                    val now = System.currentTimeMillis()
                    for (root in roots) {
                        snapshots += secrets.snapshotStore(root.root, label)
                        val previous = olds.getValue(root).read { NewPassword.chars(it) }
                        try {
                            secrets.storeRotation(root.root, label, newChars, previous, now)
                        } finally {
                            previous?.fill('\u0000')
                        }
                    }
                }
            }
            if (stored.isFailure) {
                restore(snapshots)
                throw Stop(message("rotate.failed.store"))
            }

            // 2. Every vault file, all or nothing, not undoable (D210).
            step(Step.FILES)
            val outcome = onEdt { writer.writeAllFileSystem(plan, message("rotate.label.before", label), message("rotate.label.after", label)) }
            when (outcome) {
                is ReencryptionWriter.FileSystemOutcome.NotWritten -> {
                    restore(snapshots)
                    throw Stop(staleText(outcome.stale))
                }
                is ReencryptionWriter.FileSystemOutcome.Failed -> {
                    // Files that still hold new envelopes need the new password: then the password store keeps it.
                    if (outcome.notRestored.isEmpty()) restore(snapshots)
                    throw Stop(failedText(outcome))
                }
                is ReencryptionWriter.FileSystemOutcome.Written, ReencryptionWriter.FileSystemOutcome.Nothing -> Unit
            }

            // 3. The sources that may be written (D211); a failure here leaves the rest as it is (summary, Copy).
            step(Step.SOURCES)
            val written = ArrayList<RotationGroup>()
            val failed = ArrayList<RotationGroup>()
            val bytes = new.read { it.copyOf() }
            try {
                for (decision in decisions) {
                    val file = decision.group.source.file ?: continue
                    if (!decision.write) continue
                    if (withContext(Dispatchers.IO) { sources.replacePasswordFile(file, bytes) }) written += decision.group else failed += decision.group
                }
            } finally {
                bytes.fill(0)
            }

            // 4. The secret in memory (D213): the id stays unlocked, with the new password.
            step(Step.MEMORY)
            for (root in roots) secrets.switchRotated(root.root, label, olds.getValue(root), new)
            RotationHints.getInstance(project).rotatedHere(roots.map { it.rootKey }, label)

            val yourself = decisions.filter { !it.write && it.group.source.destination in setOf(Destination.YOURSELF, Destination.PASSWORD_FILE) }.map { it.group }
            val historyLabel = (outcome as? ReencryptionWriter.FileSystemOutcome.Written)?.labelBefore
            val summary = summary(label, roots, plan, regenerated, written, failed, yourself, leftAlone, equal, historyLabel)
            lastSummary = summary
            onEdt { notify(summary) }
        } finally {
            newChars.fill('\u0000')
            snapshots.forEach(StoreSnapshot::close)
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

    @Nls
    private fun failedText(outcome: ReencryptionWriter.FileSystemOutcome.Failed): String {
        val text = message("rotate.failed.write", escape(outcome.file.name))
        if (outcome.notRestored.isEmpty()) return text
        return text + " " + message("rotate.failed.write.kept", outcome.notRestored.joinToString(", ") { escape(it.name) })
    }

    // ------------------------------------------------------------------------------------------------ 6. summary

    private fun summary(
        label: String, roots: List<RotationRoot>, plan: PreparedPlan, regenerated: Int, written: List<RotationGroup>, failed: List<RotationGroup>,
        yourself: List<RotationGroup>, leftAlone: List<String>, equal: List<String>, historyLabel: String?,
    ): RotationSummary {
        val ready = plan.items.filter { it.ready != null }
        val values = ready.count { it.item.target is ReencryptTarget.Value }
        val vaultFiles = ready.count { it.item.target is ReencryptTarget.WholeFile }
        val files = ready.map { it.item.file }.distinct().size
        val parts = ArrayList<String>()
        parts += message("rotate.done", escape(label), values, vaultFiles, files, escape(roots.joinToString(", ") { it.displayName }))
        if (regenerated > 0) parts += message("rotate.done.regenerated", regenerated)
        parts += message("rotate.done.store")
        if (written.isNotEmpty()) parts += message("rotate.done.written", escape(written.joinToString(", ") { it.source.display }))
        if (failed.isNotEmpty()) parts += message("rotate.done.not.written", escape(failed.joinToString(", ") { it.source.display }))
        if (yourself.isNotEmpty()) parts += message("rotate.done.yourself", escape(yourself.joinToString(", ") { it.source.display }))
        parts += message("rotate.done.elsewhere")
        parts += message("rotate.done.history")
        if (leftAlone.isNotEmpty()) parts += message("rotate.done.left.alone", leftAlone.take(MAX_LISTED).joinToString("; ") + more(leftAlone.size))
        if (equal.isNotEmpty()) parts += equal.joinToString(" ")
        val unwritten = (failed + yourself).map { group -> group.roots.first() to group.source }
        return RotationSummary(
            label, roots.map { it.root }, values, vaultFiles, files, regenerated, written.map { it.source.display }, unwritten, parts.joinToString("<br>"), historyLabel,
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

    /** Copy New Password: the id's current secret (while it is unlocked) through [VaultClipboard]. EDT. */
    fun copyNewPassword(summary: RotationSummary) {
        ThreadingAssertions.assertEventDispatchThread()
        val root = summary.roots.firstOrNull() ?: return
        val current = VaultSecretsService.getInstance(project).currentSecret(root, summary.label)
            ?: return VaultUiFeedback.info(project, null, message("rotate.copy.locked", summary.label))
        current.use { secret ->
            val chars = secret.secret.read { NewPassword.chars(it) } ?: return
            try {
                VaultClipboard.getInstance().copy(String(chars))
            } finally {
                chars.fill('\u0000')
            }
        }
        VaultUiFeedback.info(project, null, message("rotate.copied"))
    }

    /**
     * Check (D211): reads each source that was not written again through the normal load path and compares it with the
     * new password and the previous one (both from the IDE password store), in constant time; the answer says
     * "matches", "still the old password" or "different", never a value. EDT; the reads run in the background.
     */
    fun check(summary: RotationSummary) {
        ThreadingAssertions.assertEventDispatchThread()
        lastJob = scope.launch(ModalityState.current().asContextElement()) {
            val lines = ArrayList<String>()
            withBackgroundProgress(project, message("rotate.check.progress")) {
                for ((root, source) in summary.unwritten) lines += message("rotate.check.result", escape(source.display), checkText(root, source, summary.label))
            }
            onEdt { VaultUiFeedback.info(project, null, lines.joinToString("<br>")) }
        }
    }

    @Nls
    private suspend fun checkText(root: RotationRoot, source: RotationSource, label: String): String {
        val identity = source.identity ?: return message("rotate.check.unreadable")
        val secrets = VaultSecretsService.getInstance(project)
        val loaded = try {
            secrets.loadSourceAgain(root.root, identity)
        } catch (e: CancellationException) {
            throw e
        } catch (e: VaultCorpusGuardException) {
            throw e
        } catch (e: Exception) {
            VaultLog.failure(VaultLog.Operation.LOAD_SOURCE, e, source.display, label)
            null
        } ?: return message("rotate.check.unreadable")
        return loaded.use { secret ->
            val stored = withContext(Dispatchers.IO) { secrets.readStoredPassword(root.root, label) }
            val matches = stored?.use { same(it, secret) } == true
            if (matches) return@use message("rotate.check.matches")
            val previous = withContext(Dispatchers.IO) { secrets.readPreviousPassword(root.root, label) }
            if (previous?.use { same(it, secret) } == true) message("rotate.check.old") else message("rotate.check.different")
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private fun escape(text: String): String = StringUtil.escapeXmlEntities(text)

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

        /** The date of a previous password as the settings page shows it. */
        @Nls
        fun date(millis: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

        fun getInstance(project: Project): RotationService = project.service()
    }
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
    internal val unwritten: List<Pair<RotationRoot, RotationSource>>,
    @Nls val text: String,
    /** The id of the Local History label put before the vault files were written, or null when none was written. */
    val historyLabel: String?,
) {
    override fun toString(): String = "RotationSummary($label, $values values, $vaultFiles vault files)"
}
