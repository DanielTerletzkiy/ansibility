package de.terletzkiy.ansibility.vault.reencrypt

import com.intellij.openapi.application.ReadConstraint
import com.intellij.openapi.application.constrainedReadAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.ex.DocumentEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VaultDecryptResult
import de.terletzkiy.ansibility.api.VaultEncryptResult
import de.terletzkiy.ansibility.api.VaultFailure
import de.terletzkiy.ansibility.api.VaultLockState
import de.terletzkiy.ansibility.api.VaultOperations
import de.terletzkiy.ansibility.api.VaultPurpose
import de.terletzkiy.ansibility.api.VaultStatusService
import de.terletzkiy.ansibility.api.VaultUnlockResult
import de.terletzkiy.ansibility.semantics.vault.EnvelopeParse
import de.terletzkiy.ansibility.semantics.vault.SecretBytes
import de.terletzkiy.ansibility.semantics.vault.VaultAes256
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.semantics.vault.VaultFormatException
import de.terletzkiy.ansibility.vault.VaultLog
import de.terletzkiy.ansibility.vault.actions.VaultValuePsi
import de.terletzkiy.ansibility.vault.actions.VaultValueRef
import de.terletzkiy.ansibility.vault.crypto.VaultCrypto
import de.terletzkiy.ansibility.vault.crypto.Verification
import de.terletzkiy.ansibility.vault.envelope.VaultEnvelopes
import de.terletzkiy.ansibility.vault.identity.VaultIdentityRegistry
import de.terletzkiy.ansibility.vault.secrets.VaultSecretsService
import de.terletzkiy.ansibility.yaml.YamlPsi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly
import java.security.MessageDigest

/**
 * The answers and unlocks of one run of the engine (on its coroutine): a root is unlocked at most once per run, so a
 * cancelled or failed unlock is not asked again and the root's later items fail at once with that failure (R18, D131).
 * [purpose] is what the root-secret decrypts run under.
 */
internal class ReencryptionRun(val purpose: VaultPurpose) {
    /** The failed unlocks of ids to encrypt with, by root and id. */
    val failedIds = HashMap<Pair<AnsibleRoot, String>, VaultFailure>()

    /** The roots unlocked once for decrypting, with the failure their items get while they are still locked. */
    val decrypting = HashMap<AnsibleRoot, VaultFailure>()

    /** The roots of [decrypting] whose unlock succeeded (their entry only says "unlocked once already"). */
    val unlocked = HashSet<AnsibleRoot>()

    /**
     * After a pause (Part 2's selection dialog, which may stay open for any time): roots unlocked earlier in this run
     * may have locked again meanwhile (auto-lock, Lock All), so each may be unlocked once more, prompting as usual. Failed
     * and cancelled unlocks stay failed (plan amendment R29 review, finding 8).
     */
    fun resumeAfterPause() {
        for (root in unlocked) decrypting.remove(root)
        unlocked.clear()
    }

    override fun toString(): String = "ReencryptionRun($purpose)"
}

/** What [ReencryptionEngine.inspect] learnt about one item: [result] from the inspector, or why it was not decrypted. */
internal class Inspection<T>(val item: ReencryptItem, val result: T?, val identity: String?, val outcome: ItemOutcome?) {
    override fun toString(): String = "Inspection(${item.target}, $identity, $outcome)"
}

/**
 * The re-encryption engine of plan amendment R29 ("Shared engine"): R18's re-encryption mechanics moved out of
 * `VaultFileOperations`, so Rekey…, Change Id…, Regenerate Secrets… (Part 2) and Rotate Vault Password… (Part 1) run
 * on one implementation of the safety rules.
 *
 * - **Decrypting** ([Decryption]): with the root's unlocked secrets (an inline value found again through its smart
 *   pointer right before each try, and the try counted only when its document did not change meanwhile, D131), or
 *   with one explicit secret on the envelope the item captured. A root is unlocked lazily, once per [ReencryptionRun].
 * - **The plaintext** ([NewPlaintext]): kept, or replaced by bytes the caller supplies, which the engine owns once
 *   taken and zeroes after encrypting. Old plaintext is zeroed as soon as the item's crypto is done.
 * - **Encrypting** ([Encryption]): with a configured id's secret ([VaultOperations.encrypt]), with the secret that
 *   decrypted the old envelope under its old header (D220), or with an explicit secret under an explicit or the old
 *   header (D208).
 * - **Verifying**: every new envelope is decrypted again with the secret it was encrypted with and compared with the
 *   plaintext in constant time before it is accepted; a mismatch leaves the item out ([ItemOutcome.NotVerified]).
 *
 * All of this is the preflight ([prepare]): crypto only, in memory, on the caller's coroutine (which shows progress
 * and Cancel); nothing is written. [ReencryptionWriter] writes a [PreparedPlan] on the EDT. Logs name files, keys and
 * failure classes only ([VaultLog]).
 */
internal class ReencryptionEngine(private val project: Project) {
    /**
     * Prepares every item of [items] in order: decrypt, the new plaintext, encrypt, verify. With [stopAtFirstFailure]
     * the preflight ends at the first item that is not ready or unchanged (R18: a file is written whole or not at all),
     * and the rest of the items are not touched. The documents' modification sequences of the items' files are taken
     * before the first item, for the all-or-nothing writes. Checks for cancellation before each item.
     */
    suspend fun prepare(items: List<ReencryptItem>, run: ReencryptionRun, stopAtFirstFailure: Boolean = false): PreparedPlan {
        val (documents, sequences) = readAction { documentsOf(items.map { it.file }.distinct()).let { it to it.mapValues { (_, document) -> document.modificationSequence } } }
        val prepared = ArrayList<PreparedItem>()
        try {
            for (item in items) {
                currentCoroutineContext().ensureActive()
                val result = prepareItem(item, run)
                prepared += result
                if (stopAtFirstFailure && result.ready == null && result.outcome != ItemOutcome.Unchanged) break
            }
        } finally {
            // Values handed over but never taken (Cancel, a failure that stopped the run) are zeroed now.
            for (item in items) (item.plaintext as? NewPlaintext.Replace)?.source?.close()
        }
        return PreparedPlan(prepared, sequences, documents)
    }

    /**
     * Decrypts every item of [items] (as [prepare] would) and hands its plaintext to [inspector], which returns facts
     * that are not secret (a shape, a keyed digest); the plaintext is zeroed right after. A [ReencryptTarget.PlainValue]
     * is handed `null`. Nothing is encrypted or written. Part 2's preflight before its selection dialog.
     */
    suspend fun <T> inspect(items: List<ReencryptItem>, run: ReencryptionRun, inspector: (ReencryptItem, ByteArray?) -> T): List<Inspection<T>> {
        val result = ArrayList<Inspection<T>>()
        for (item in items) {
            currentCoroutineContext().ensureActive()
            if (item.target is ReencryptTarget.PlainValue) {
                result += Inspection(item, inspector(item, null), null, null)
                continue
            }
            when (val opened = open(item, run)) {
                is Opening.Left -> result += Inspection(item, null, null, opened.outcome)
                is Opening.Opened -> try {
                    result += Inspection(item, inspector(item, opened.bytes), opened.identity, null)
                } finally {
                    opened.bytes?.fill(0)
                }
            }
        }
        return result
    }

    private suspend fun prepareItem(item: ReencryptItem, run: ReencryptionRun): PreparedItem {
        val opened = when (val opening = open(item, run)) {
            is Opening.Left -> return PreparedItem(item, opening.outcome, opening.stamp)
            is Opening.Opened -> opening
        }
        var plaintext: ByteArray? = null
        try {
            plaintext = when (val plan = item.plaintext) {
                NewPlaintext.Keep -> opened.bytes ?: return PreparedItem(item, ItemOutcome.Failed(VaultFailure.FORMAT), opened.stamp)
                is NewPlaintext.Replace -> {
                    opened.bytes?.fill(0)
                    plan.source.take() ?: return PreparedItem(item, ItemOutcome.Failed(VaultFailure.CANCELLED), opened.stamp)
                }
            }
            return PreparedItem(item, encrypt(item, run, opened, plaintext), opened.stamp)
        } finally {
            plaintext?.fill(0)
            opened.bytes?.fill(0)
        }
    }

    // ------------------------------------------------------------------------------------------------ decrypting

    /** The old envelope of an item opened: its plaintext (null for a plaintext value), the id that decrypted it, and the stamp of a whole file. */
    private sealed interface Opening {
        class Opened(val bytes: ByteArray?, val identity: String?, val envelope: VaultEnvelope?, val stamp: Long?) : Opening {
            override fun toString(): String = "Opened(***, $identity)"
        }

        class Left(val outcome: ItemOutcome, val stamp: Long? = null) : Opening
    }

    private suspend fun open(item: ReencryptItem, run: ReencryptionRun): Opening = when (val target = item.target) {
        is ReencryptTarget.PlainValue -> when (item.decryption) {
            Decryption.None -> Opening.Opened(null, null, null, null)
            else -> Opening.Left(ItemOutcome.Failed(VaultFailure.FORMAT))
        }
        is ReencryptTarget.WholeFile -> {
            val (stamp, envelope) = readAction { target.file.modificationStamp to VaultEnvelopes.ofFile(target.file)?.envelope }
            if (envelope == null) Opening.Left(ItemOutcome.Failed(VaultFailure.FORMAT), stamp)
            else when (val decryption = item.decryption) {
                Decryption.RootSecrets -> opened(decryptUnlocking(SourceLocation(target.file, 0), item.root, envelope, run), envelope, stamp)
                is Decryption.Explicit -> explicit(envelope, decryption.secret, stamp)
                Decryption.None -> Opening.Left(ItemOutcome.Failed(VaultFailure.FORMAT), stamp)
            }
        }
        is ReencryptTarget.Value -> {
            val envelope = target.ref.envelope
            if (envelope == null) Opening.Left(ItemOutcome.Failed(VaultFailure.FORMAT))
            else when (val decryption = item.decryption) {
                Decryption.RootSecrets -> decryptValue(target.ref, item.root, envelope, run)?.let { opened(it, envelope, null) } ?: Opening.Left(ItemOutcome.Changed)
                is Decryption.Explicit -> explicit(envelope, decryption.secret, null)
                Decryption.None -> Opening.Left(ItemOutcome.Failed(VaultFailure.FORMAT))
            }
        }
    }

    private fun opened(result: VaultDecryptResult, envelope: VaultEnvelope, stamp: Long?): Opening = when (result) {
        is VaultDecryptResult.Failed -> Opening.Left(ItemOutcome.Failed(result.failure), stamp)
        is VaultDecryptResult.Decrypted -> result.plaintext.use { plaintext -> Opening.Opened(plaintext.read { it.copyOf() }, result.identity, envelope, stamp) }
    }

    /**
     * Decrypts [envelope] with [secret] alone (Part 1): no chain, no matching rules, no cache. The cipher runs
     * non-cancellable, so its result always reaches this function (a `withContext` that changes the dispatcher would
     * discard it on a Cancel, hence the switch inside `NonCancellable`), and a Cancel meanwhile zeroes it here.
     */
    private suspend fun explicit(envelope: VaultEnvelope, secret: SecretBytes, stamp: Long?): Opening {
        val bytes = try {
            withContext(NonCancellable) { withContext(Dispatchers.Default) { VaultAes256.decrypt(envelope, secret) } }
        } catch (_: VaultFormatException) {
            return Opening.Left(ItemOutcome.Failed(VaultFailure.FORMAT), stamp)
        }
        try {
            currentCoroutineContext().ensureActive()
        } catch (e: CancellationException) {
            bytes?.fill(0)
            throw e
        }
        return bytes?.let { Opening.Opened(it, null, envelope, stamp) } ?: Opening.Left(ItemOutcome.Failed(VaultFailure.WRONG_SECRET), stamp)
    }

    /**
     * Decrypts the `!vault` value [ref] captured and no other. The vault services resolve an offset, and an edit above
     * the value moves it, so the value is found again through its smart pointer right before each try (documents
     * committed), and a try counts only when the document did not change until it returned; otherwise it is tried
     * again. Null when the value changed or is gone, or its document kept changing (D131).
     */
    private suspend fun decryptValue(ref: VaultValueRef, root: AnsibleRoot, envelope: VaultEnvelope, run: ReencryptionRun): VaultDecryptResult? {
        repeat(VALUE_ATTEMPTS) {
            val pinned = constrainedReadAction(ReadConstraint.withDocumentsCommitted(project)) { pin(ref) } ?: return null
            val result = decryptUnlocking(pinned.location, root, envelope, run)
            val sequence = try {
                readAction { pinned.document.modificationSequence }
            } catch (e: CancellationException) {
                // Cancelled right after a successful decrypt: the plaintext is zeroed before the run ends.
                (result as? VaultDecryptResult.Decrypted)?.plaintext?.close()
                throw e
            }
            if (sequence == pinned.sequence) return result
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
        val scalar = ref.scalar?.takeIf { it.isValid && YamlPsi.loadedText(it) == ref.envelopeText } ?: return null
        val document = FileDocumentManager.getInstance().getDocument(ref.file) as? DocumentEx ?: return null
        return Pinned(SourceLocation(ref.file, VaultValuePsi.valueStart(scalar)), document, document.modificationSequence)
    }

    /**
     * A `!vault` value found again right before a decrypt. The modification sequence grows with every change of the
     * document, undo included (the modification stamp goes back on undo), so an unchanged one means the decrypt
     * resolved [location] in the same text.
     */
    private class Pinned(val location: SourceLocation, val document: DocumentEx, val sequence: Int)

    /**
     * Decrypts at [location]; a locked root is unlocked first (consent, password safe, a prompt checked against
     * [envelope]), once per run: when that failed or you cancelled it, or the root is still locked afterwards, its later
     * items fail at once without asking again.
     */
    suspend fun decryptUnlocking(location: SourceLocation, root: AnsibleRoot, envelope: VaultEnvelope, run: ReencryptionRun): VaultDecryptResult {
        val operations = VaultOperations.getInstance(project)
        val first = operations.decrypt(location, run.purpose)
        if (first !is VaultDecryptResult.Failed || first.failure != VaultFailure.LOCKED) return first
        run.decrypting[root]?.let { return VaultDecryptResult.Failed(it, first.tried) }
        val failure = (VaultSecretsService.getInstance(project).unlock(root, label = null, verify = envelope) as? VaultUnlockResult.Failed)?.failure
        run.decrypting[root] = failure ?: VaultFailure.LOCKED
        if (failure == null) run.unlocked += root
        if (failure != null) return VaultDecryptResult.Failed(failure, first.tried)
        return operations.decrypt(location, run.purpose)
    }

    /** Unlocks [label] of [root] when it is locked, once per run; the failure, or null when it is unlocked now. */
    suspend fun ensureUnlocked(root: AnsibleRoot, label: String, run: ReencryptionRun): VaultFailure? {
        run.failedIds[root to label]?.let { return it }
        val identity = readAction { VaultStatusService.getInstance(project).config(root).identities.firstOrNull { it.label == label } }
            ?: return VaultFailure.NO_IDENTITY
        if (identity.lockState == VaultLockState.UNLOCKED) return null
        val failure = when (val result = VaultOperations.getInstance(project).unlock(root, label)) {
            is VaultUnlockResult.Unlocked -> if (label in result.identities) null else VaultFailure.LOCKED
            is VaultUnlockResult.Failed -> result.failure
        }
        failure?.let { run.failedIds[root to label] = it }
        return failure
    }

    // ------------------------------------------------------------------------------------------------ encrypting

    /** The new envelope of [item] for [plaintext] (the caller zeroes it), verified; or why there is none. */
    private suspend fun encrypt(item: ReencryptItem, run: ReencryptionRun, opened: Opening.Opened, plaintext: ByteArray): ItemOutcome =
        when (val encryption = item.encryption) {
            is Encryption.ConfiguredId -> withConfiguredId(item, run, encryption.label, plaintext) { it }
            is Encryption.ChangeId -> {
                val identity = opened.identity ?: return ItemOutcome.Failed(VaultFailure.NO_IDENTITY)
                val old = opened.envelope
                if (identity == encryption.label && old != null) {
                    // The decrypt just proved that this id's secret opens the payload; labels are not covered by the HMAC.
                    val relabelled = old.withLabel(encryption.label)
                    if (relabelled == old) ItemOutcome.Unchanged else ItemOutcome.Ready(relabelled, identity)
                } else if (!encryption.confirm(item.root, identity)) {
                    ItemOutcome.Declined
                } else {
                    withConfiguredId(item, run, encryption.label, plaintext) { it }
                }
            }
            Encryption.SameAsBefore -> {
                val identity = opened.identity ?: return ItemOutcome.Failed(VaultFailure.NO_IDENTITY)
                val old = opened.envelope ?: return ItemOutcome.Failed(VaultFailure.FORMAT)
                // The id just decrypted the old envelope, so it is unlocked (also an id the prompt fallback added).
                withOpeningSecret(item, identity, old, plaintext)
            }
            is Encryption.Explicit -> {
                val label = if (encryption.keepHeader) opened.envelope?.label else encryption.label
                // ansible-vault refuses plaintext that is vault data itself; so does the codec.
                if (VaultEnvelope.isEncrypted(plaintext)) return ItemOutcome.Failed(VaultFailure.FORMAT)
                val envelope = withContext(Dispatchers.Default) { VaultAes256.encrypt(plaintext, encryption.secret, label) }.let(::tampered)
                    ?: return ItemOutcome.Failed(VaultFailure.FORMAT)
                if (verify(envelope, encryption.secret, plaintext)) ItemOutcome.Ready(envelope, null) else notVerified(item)
            }
        }

    /**
     * Encrypts [plaintext] with the secret of [label] ([VaultOperations.encrypt]; with [unlock], unlocking it once per
     * run first), lets [header] adjust the header (labels are not covered by the HMAC), and verifies the result with
     * that secret.
     */
    private suspend fun withConfiguredId(
        item: ReencryptItem, run: ReencryptionRun, label: String, plaintext: ByteArray, unlock: Boolean = true, header: (VaultEnvelope) -> VaultEnvelope,
    ): ItemOutcome {
        if (unlock) ensureUnlocked(item.root, label, run)?.let { return ItemOutcome.Failed(it) }
        val text = when (val result = VaultOperations.getInstance(project).encrypt(item.root, plaintext, label, item.file)) {
            is VaultEncryptResult.Failed -> return ItemOutcome.Failed(result.failure)
            is VaultEncryptResult.Encrypted -> result.envelope
        }
        val envelope = (VaultEnvelope.parse(text) as? EnvelopeParse.Ok)?.envelope?.let(header)?.let(::tampered)
            ?: return ItemOutcome.Failed(VaultFailure.FORMAT)
        val discovery = VaultIdentityRegistry.getInstance(project).discovery(item.root)
        val verified = VaultSecretsService.getInstance(project).lease(discovery).use { lease ->
            // The secret VaultOperations.encrypt took: the first one with that label (Ansible's match_best_secret).
            lease.secrets.firstOrNull { it.label == label }?.secret?.let { verify(envelope, it, plaintext) } ?: false
        }
        return if (verified) ItemOutcome.Ready(envelope, label) else notVerified(item)
    }

    /**
     * D220 ([Encryption.SameAsBefore]): encrypts [plaintext] with exactly the secret that opened [old], under [old]'s
     * header as written ([VaultEnvelope.keepingHeaderOf]), with the root's `vault_encrypt_salt` like any encrypt, and
     * verifies the result with that secret. Two secrets may share the id [identity] (plan amendment R29 review, finding
     * 4): then the secret is the first of them in the root's chain order that decrypts [old], as Ansible tries them.
     * The verification cache learns the new envelope, as after [VaultOperations.encrypt].
     */
    private suspend fun withOpeningSecret(item: ReencryptItem, identity: String, old: VaultEnvelope, plaintext: ByteArray): ItemOutcome {
        // ansible-vault refuses plaintext that is vault data itself; so does the codec.
        if (VaultEnvelope.isEncrypted(plaintext)) return ItemOutcome.Failed(VaultFailure.FORMAT)
        val discovery = VaultIdentityRegistry.getInstance(project).discovery(item.root)
        val crypto = VaultCrypto.getInstance(project)
        val secrets = VaultSecretsService.getInstance(project)
        return secrets.lease(discovery).use { lease ->
            val candidates = lease.secrets.filter { it.label == identity }.map { it.secret }
            val secret = when (candidates.size) {
                0 -> return ItemOutcome.Failed(VaultFailure.LOCKED)
                1 -> candidates.single()
                else -> candidates.firstOrNull { opens(old, it) } ?: return ItemOutcome.Failed(VaultFailure.WRONG_SECRET)
            }
            val salt = discovery.config.encryptSaltBytes()
            val envelope = withContext(Dispatchers.Default) { crypto.encrypt(plaintext, secret, identity, salt) }
                .keepingHeaderOf(old).let(::tampered) ?: return ItemOutcome.Failed(VaultFailure.FORMAT)
            if (!verify(envelope, secret, plaintext)) return notVerified(item)
            // Keyed as a later decrypt reads it back from the file (a parsed envelope formats ansible-vault's header).
            val written = (VaultEnvelope.parse(envelope.format()) as? EnvelopeParse.Ok)?.envelope ?: envelope
            val labelMatches = written.labelOrDefault(discovery.config.defaultIdentity) == identity
            if (crypto.record(discovery.rootKey, crypto.fingerprint(written), Verification(identity, listOf(identity), labelMatches))) secrets.notifyChanged()
            VaultLog.event(VaultLog.Operation.ENCRYPT, VaultLog.Event.ENCRYPTED, item.file.path, keyOf(item))
            ItemOutcome.Ready(envelope, identity)
        }
    }

    /** True when [secret] decrypts [envelope] (the decrypted copy is zeroed at once). */
    private suspend fun opens(envelope: VaultEnvelope, secret: SecretBytes): Boolean = withContext(Dispatchers.Default) {
        val opened = try {
            VaultAes256.decrypt(envelope, secret)
        } catch (_: VaultFormatException) {
            null
        }
        opened?.fill(0)
        opened != null
    }

    /** The YAML key of [item]'s value, for log lines; null for a whole file or a sequence item. */
    private fun keyOf(item: ReencryptItem): String? = when (val target = item.target) {
        is ReencryptTarget.Value -> target.ref.keyName
        is ReencryptTarget.PlainValue -> target.ref.keyName
        is ReencryptTarget.WholeFile -> null
    }

    /** True when [envelope] decrypts with [secret] to exactly [expected] (compared in constant time); the decrypted copy is zeroed. */
    private suspend fun verify(envelope: VaultEnvelope, secret: SecretBytes, expected: ByteArray): Boolean = withContext(Dispatchers.Default) {
        val opened = try {
            VaultAes256.decrypt(envelope, secret)
        } catch (_: VaultFormatException) {
            null
        } ?: return@withContext false
        try {
            MessageDigest.isEqual(opened, expected)
        } finally {
            opened.fill(0)
        }
    }

    private fun notVerified(item: ReencryptItem): ItemOutcome {
        VaultLog.event(VaultLog.Operation.ENCRYPT, VaultLog.Event.NOT_VERIFIED, item.file.path, (item.target as? ReencryptTarget.Value)?.ref?.keyName)
        return ItemOutcome.NotVerified
    }

    /** [envelope], or what [tamperForTests] makes of it (a test that proves a failed verification writes nothing). */
    private fun tampered(envelope: VaultEnvelope): VaultEnvelope? {
        val tamper = tamperForTests ?: return envelope
        return (VaultEnvelope.parse(tamper(envelope.format())) as? EnvelopeParse.Ok)?.envelope
    }

    /** The documents of [files] (only files whose document is loaded or can be), for the plan to hold. Read action. */
    private fun documentsOf(files: List<VirtualFile>): Map<VirtualFile, DocumentEx> {
        val documents = FileDocumentManager.getInstance()
        return files.mapNotNull { file -> (documents.getDocument(file) as? DocumentEx)?.let { file to it } }.toMap()
    }

    companion object {
        /** How often a `!vault` value is decrypted again while its document keeps changing. */
        private const val VALUE_ATTEMPTS = 3

        /** Rewrites every new envelope's text before it is verified (tests only: a corrupted envelope must never be written). */
        @Volatile
        @get:TestOnly
        @set:TestOnly
        var tamperForTests: ((String) -> String)? = null
    }
}
