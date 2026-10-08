package de.terletzkiy.ansibility.vault.actions

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.ThreadingAssertions
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VaultDecryptResult
import de.terletzkiy.ansibility.api.VaultEncryptResult
import de.terletzkiy.ansibility.api.VaultFailure
import de.terletzkiy.ansibility.api.VaultLockState
import de.terletzkiy.ansibility.api.VaultOperations
import de.terletzkiy.ansibility.api.VaultPlaintext
import de.terletzkiy.ansibility.api.VaultPurpose
import de.terletzkiy.ansibility.api.VaultRootConfig
import de.terletzkiy.ansibility.api.VaultStatusService
import de.terletzkiy.ansibility.api.VaultUnlockResult
import de.terletzkiy.ansibility.semantics.vault.EncryptIdentity
import de.terletzkiy.ansibility.semantics.vault.EnvelopeParse
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.Yaml11Resolver
import de.terletzkiy.ansibility.vault.VaultCorpusGuardException
import de.terletzkiy.ansibility.vault.VaultLog
import de.terletzkiy.ansibility.vault.identity.VaultIdentityRegistry
import de.terletzkiy.ansibility.vault.identity.VaultProjectSettings
import de.terletzkiy.ansibility.vault.secrets.VaultSecretsService
import de.terletzkiy.ansibility.vault.ui.AnsibilityVaultUiBundle
import de.terletzkiy.ansibility.vault.ui.VaultClipboard
import de.terletzkiy.ansibility.vault.ui.VaultEditService
import de.terletzkiy.ansibility.vault.ui.VaultUiFeedback
import de.terletzkiy.ansibility.vault.ui.VaultUiTimings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.annotations.TestOnly

/**
 * The explicit actions on single inline vault values (F7.1–F7.6), shared by the intentions, the X93 gutter, the
 * Reveal popup and the card's Reveal link.
 *
 * Each action starts on the EDT (choices and confirmations first, so nothing is decrypted before you agree), runs
 * its crypto in a coroutine of this service through [VaultOperations] (which prompts for consent and passwords on
 * the EDT itself and runs the cipher on `Dispatchers.Default`), and writes on the EDT through [VaultValueWriter] in
 * one undoable command. A root whose ids are locked is unlocked lazily, checking a typed password against the value.
 *
 * Plaintext lives only in caller-owned [VaultPlaintext]s and short-lived arrays, which are zeroed when done; the only
 * Strings are those a surface needs to show or write the value on request (the popups, the clipboard, Decrypt to
 * plain value). Failures are reported by class ([VaultFailure]) and logged through `VaultLog` with file and key only.
 */
@Service(Service.Level.PROJECT)
class VaultValueActions(private val project: Project, private val scope: CoroutineScope) {
    @Volatile
    private var lastJob: Job? = null

    /** The most recent action's job, for tests that wait for it. */
    @get:TestOnly
    val lastAction: Job? get() = lastJob

    /** F7.1 Reveal: the timed, masked popup ([VaultOperations.reveal] hands the plaintext to the registered presenter). */
    internal fun reveal(ref: VaultValueRef, editor: Editor?) {
        ThreadingAssertions.assertEventDispatchThread()
        VaultOperations.getInstance(project).reveal(ref.location, editor)
    }

    /** F7.1 Copy: decrypts and puts the value on the clipboard, which is cleared after 30 s. */
    internal fun copy(ref: VaultValueRef, editor: Editor?) {
        launch(message("progress.copy"), ref, editor) {
            when (val result = decryptUnlocking(ref, VaultPurpose.COPY)) {
                is VaultDecryptResult.Failed -> onEdt { VaultUiFeedback.failure(project, editor, result.failure) }
                is VaultDecryptResult.Decrypted -> result.plaintext.use { plaintext ->
                    onEdt {
                        val text = plaintext.read { VaultValueText.decode(it) }
                        if (text == null) {
                            VaultUiFeedback.error(project, editor, message("reveal.binary"))
                        } else {
                            VaultClipboard.getInstance().copy(text)
                            VaultUiFeedback.info(project, editor, message("action.copied", VaultUiTimings.seconds(VaultUiTimings.CLIPBOARD_MILLIS)))
                        }
                    }
                }
            }
        }
    }

    /** F7.2 Edit: decrypts and opens the edit popup, which owns the plaintext from then on. */
    internal fun edit(ref: VaultValueRef, editor: Editor?) {
        launch(message("progress.edit"), ref, editor) {
            when (val result = decryptUnlocking(ref, VaultPurpose.EDIT)) {
                is VaultDecryptResult.Failed -> onEdt { VaultUiFeedback.failure(project, editor, result.failure) }
                is VaultDecryptResult.Decrypted -> try {
                    val version = editLabel(ref)?.let(VaultEnvelope::versionFor) ?: VaultEnvelope.VERSION_1_1
                    onEdt { VaultEditService.getInstance(project).open(ref, editor?.takeUnless { it.isDisposed }, result.identity, result.plaintext, version) }
                } catch (e: Throwable) {
                    result.plaintext.close()
                    throw e
                }
            }
        }
    }

    /**
     * X93's Unlock vault ids…: unlocks the ids of the value's root through [VaultOperations.unlock] (consent before the
     * first read, then the password sources and prompts) and decrypts nothing. Being explicit, it asks again for a
     * root declined with Not now earlier in the session ([VaultSecretsService.forgetDeclined]).
     */
    internal fun unlock(ref: VaultValueRef, editor: Editor?) {
        launch(message("progress.unlock"), ref, editor) {
            VaultSecretsService.getInstance(project).forgetDeclined(ref.root)
            when (val result = VaultOperations.getInstance(project).unlock(ref.root)) {
                is VaultUnlockResult.Unlocked -> onEdt { VaultUiFeedback.info(project, editor, message("action.unlocked", result.identities.joinToString(", "))) }
                is VaultUnlockResult.Failed -> if (result.failure != VaultFailure.CANCELLED) onEdt { VaultUiFeedback.failure(project, editor, result.failure) }
            }
        }
    }

    /** Edit for the value at [location] (the Reveal popup's Edit… button). */
    internal fun editAt(location: SourceLocation, editor: Editor?) {
        ThreadingAssertions.assertEventDispatchThread()
        val ref = runReadActionBlocking { refAt(location) } ?: return
        edit(ref, editor)
    }

    /**
     * Encrypt and Replace of the edit popup: encrypts [plaintext] (zeroed afterwards) with [identity], keeps the
     * value's header label as `ansible-vault edit` does ([EncryptIdentity.editLabel]), writes it, and reports the
     * outcome to [done] on the EDT.
     */
    internal fun reencrypt(ref: VaultValueRef, identity: String, plaintext: ByteArray, done: (VaultWriteOutcome) -> Unit) {
        launch(message("progress.reencrypt"), ref, null) {
            val outcome = try {
                val label = editLabel(ref)
                encryptAndWrite(ref, identity, plaintext, message("command.edit")) { envelope -> if (label == null) envelope else envelope.withLabel(label) }
            } finally {
                plaintext.fill(0)
            }
            onEdt { done(outcome) }
        }
    }

    /**
     * F7.3 Encrypt value: warns about type changes and Jinja (never showing the value), lets you choose the id when
     * Ansible's rules leave a choice, then writes a `!vault |` block in the file's layout.
     */
    internal fun encrypt(ref: PlainValueRef, editor: Editor?) {
        ThreadingAssertions.assertEventDispatchThread()
        val config = runReadActionBlocking { VaultStatusService.getInstance(project).config(ref.root) }
        val choices = choices(config)
        if (choices.isEmpty()) return VaultUiFeedback.failure(project, editor, VaultFailure.NO_IDENTITY)
        val warnings = encryptWarnings(ref)
        val label = when (val choice = encryptChoice(ref.root, ref.environments, ref.file, config, choices.map { it.label })) {
            is EncryptIdentity.Choice.Chosen ->
                if (warnings.isEmpty()) choice.label
                else VaultActionPrompts.getInstance().confirmEncrypt(project, VaultEncryptRequest(ref.keyName, warnings, choices.filter { it.label == choice.label }, choice.label))
            is EncryptIdentity.Choice.Ambiguous ->
                VaultActionPrompts.getInstance().confirmEncrypt(project, VaultEncryptRequest(ref.keyName, warnings, choices, choice.preselected))
            is EncryptIdentity.Choice.NotFound, EncryptIdentity.Choice.NoIdentity -> return VaultUiFeedback.failure(project, editor, VaultFailure.NO_IDENTITY)
        } ?: return
        val bytes = ref.value.toByteArray(Charsets.UTF_8)
        launch(message("progress.encrypt"), null, editor) {
            val outcome = try {
                encryptAndWrite(ref.root, ref.file, label, bytes, message("command.encrypt"), { ref.scalar }, ref.value) { it }
            } finally {
                bytes.fill(0)
            }
            onEdt { report(editor, outcome) { message("action.written.encrypt", it) } }
        }
    }

    /**
     * F7.4 Decrypt to plain value: asks first (Cancel is the default), then writes the value as a double-quoted
     * string (`!unsafe` when it holds Jinja syntax, which a vault value never had templated).
     */
    internal fun decryptToPlain(ref: VaultValueRef, editor: Editor?) {
        ThreadingAssertions.assertEventDispatchThread()
        if (!VaultActionPrompts.getInstance().confirmDecryptToPlain(project, VaultDecryptRequest(ref.keyName, ref.file.name))) return
        launch(message("progress.decrypt"), ref, editor) {
            val outcome = when (val result = decryptUnlocking(ref, VaultPurpose.DECRYPT_TO_PLAIN)) {
                is VaultDecryptResult.Failed -> VaultWriteOutcome.Failed(result.failure)
                is VaultDecryptResult.Decrypted -> result.plaintext.use { plaintext ->
                    onEdt {
                        val text = plaintext.read { VaultValueText.decode(it) }
                        if (text == null) {
                            VaultUiFeedback.error(project, editor, message("reveal.binary"))
                            null
                        } else {
                            VaultValueWriter.write(project, { ref.scalar }, ref.envelopeText, message("command.decrypt"), result.identity) { scalar, document ->
                                VaultValueText.plainReplacement(scalar, document, text)
                            }
                        }
                    }
                }
            } ?: return@launch
            onEdt { report(editor, outcome) { message("action.written.decrypt") } }
        }
    }

    /** F7.5 Rekey to id…: decrypts and encrypts again with the chosen id's secret (new salt; that id's header). */
    internal fun rekey(ref: VaultValueRef, editor: Editor?) {
        ThreadingAssertions.assertEventDispatchThread()
        val config = runReadActionBlocking { VaultStatusService.getInstance(project).config(ref.root) }
        val choices = choices(config)
        if (choices.isEmpty()) return VaultUiFeedback.error(project, editor, message("action.no.identity"))
        val current = ref.envelope?.labelOrDefault(config.defaultIdentity)
        val request = VaultIdentityRequest(message("rekey.title"), message("rekey.message", ref.keyName ?: ref.file.name), choices, current)
        val target = VaultActionPrompts.getInstance().chooseIdentity(project, request) ?: return
        launch(message("progress.rekey"), ref, editor) {
            val outcome = rekeyTo(ref, target, message("command.rekey"))
            onEdt { report(editor, outcome) { message("action.written.rekey", it) } }
        }
    }

    /**
     * F7.6 Change id…: the value is decrypted (unlocking its root lazily) and the chosen id unlocked; when that id's
     * secret is the one that decrypts it, only the header is rewritten (labels are not covered by the HMAC, so this
     * always verifies first), otherwise you are asked whether to re-encrypt it with that id's secret.
     */
    internal fun changeId(ref: VaultValueRef, editor: Editor?) {
        ThreadingAssertions.assertEventDispatchThread()
        val config = runReadActionBlocking { VaultStatusService.getInstance(project).config(ref.root) }
        val current = ref.envelope?.labelOrDefault(config.defaultIdentity)
        val choices = choices(config).filter { it.label != current }
        if (choices.isEmpty()) return VaultUiFeedback.error(project, editor, message("action.no.identity"))
        val request = VaultIdentityRequest(message("change.id.title"), message("change.id.message", ref.keyName ?: ref.file.name), choices, null)
        val target = VaultActionPrompts.getInstance().chooseIdentity(project, request) ?: return
        launch(message("progress.change.id"), ref, editor) {
            val outcome = changeIdTo(ref, target)
            onEdt {
                report(editor, outcome) { identity ->
                    if (outcome is VaultWriteOutcome.Written && outcome.relabelled) message("action.written.relabel", identity) else message("action.written.rekey", identity)
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ flows

    private suspend fun rekeyTo(ref: VaultValueRef, target: String, commandName: String): VaultWriteOutcome =
        when (val result = decryptUnlocking(ref, VaultPurpose.REKEY)) {
            is VaultDecryptResult.Failed -> VaultWriteOutcome.Failed(result.failure)
            is VaultDecryptResult.Decrypted -> {
                val bytes = result.plaintext.use { plaintext -> plaintext.read { it.copyOf() } }
                try {
                    encryptAndWrite(ref, target, bytes, commandName) { it }
                } finally {
                    bytes.fill(0)
                }
            }
        }

    /**
     * Decrypting first unlocks the whole root lazily (one consent for every source), so the target id is usually
     * unlocked by then; otherwise it is unlocked on its own before it is compared with the decrypting id.
     */
    private suspend fun changeIdTo(ref: VaultValueRef, target: String): VaultWriteOutcome {
        val decrypted = when (val result = decryptUnlocking(ref, VaultPurpose.CHANGE_ID)) {
            is VaultDecryptResult.Failed -> return VaultWriteOutcome.Failed(result.failure)
            is VaultDecryptResult.Decrypted -> result
        }
        val bytes = decrypted.plaintext.use { plaintext -> plaintext.read { it.copyOf() } }
        try {
            ensureUnlocked(ref.root, target)?.let { return VaultWriteOutcome.Failed(it) }
            if (decrypted.identity == target) {
                val envelope = ref.envelope ?: return VaultWriteOutcome.Failed(VaultFailure.FORMAT)
                val relabelled = envelope.withLabel(target)
                val outcome = onEdt {
                    VaultValueWriter.write(project, { ref.scalar }, ref.envelopeText, message("command.change.id"), target) { scalar, document ->
                        VaultValueText.envelopeReplacement(scalar, document, relabelled)
                    }
                }
                return if (outcome is VaultWriteOutcome.Written) VaultWriteOutcome.Written(target, relabelled = true) else outcome
            }
            if (!onEdt { VaultActionPrompts.getInstance().confirmRekeyForChangeId(project, target, decrypted.identity) }) {
                return VaultWriteOutcome.Failed(VaultFailure.CANCELLED)
            }
            return encryptAndWrite(ref, target, bytes, message("command.change.id")) { it }
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun encryptAndWrite(
        ref: VaultValueRef,
        identity: String,
        plaintext: ByteArray,
        commandName: String,
        adjust: (VaultEnvelope) -> VaultEnvelope,
    ): VaultWriteOutcome = encryptAndWrite(ref.root, ref.file, identity, plaintext, commandName, { ref.scalar }, ref.envelopeText, adjust)

    private suspend fun encryptAndWrite(
        root: AnsibleRoot,
        file: VirtualFile,
        identity: String,
        plaintext: ByteArray,
        commandName: String,
        scalarOf: () -> YAMLScalar?,
        expected: String,
        adjust: (VaultEnvelope) -> VaultEnvelope,
    ): VaultWriteOutcome {
        ensureUnlocked(root, identity)?.let { return VaultWriteOutcome.Failed(it) }
        val encrypted = when (val result = VaultOperations.getInstance(project).encrypt(root, plaintext, identity, file)) {
            is VaultEncryptResult.Failed -> return VaultWriteOutcome.Failed(result.failure)
            is VaultEncryptResult.Encrypted -> result
        }
        val envelope = (VaultEnvelope.parse(encrypted.envelope) as? EnvelopeParse.Ok)?.envelope?.let(adjust)
            ?: return VaultWriteOutcome.Failed(VaultFailure.FORMAT)
        return onEdt {
            VaultValueWriter.write(project, scalarOf, expected, commandName, encrypted.identity) { scalar, document ->
                VaultValueText.envelopeReplacement(scalar, document, envelope)
            }
        }
    }

    /** Decrypts [ref] for [purpose]; a locked root is unlocked first (consent, password safe, prompt checked against the value). */
    private suspend fun decryptUnlocking(ref: VaultValueRef, purpose: VaultPurpose): VaultDecryptResult {
        val operations = VaultOperations.getInstance(project)
        val first = operations.decrypt(ref.location, purpose)
        if (first !is VaultDecryptResult.Failed || first.failure != VaultFailure.LOCKED) return first
        val unlocked = VaultSecretsService.getInstance(project).unlock(ref.root, label = null, verify = ref.envelope)
        if (unlocked is VaultUnlockResult.Failed) return VaultDecryptResult.Failed(unlocked.failure, first.tried)
        return operations.decrypt(ref.location, purpose)
    }

    /** Unlocks [label] of [root] when it is locked; the failure, or null when it is unlocked now. */
    private suspend fun ensureUnlocked(root: AnsibleRoot, label: String): VaultFailure? {
        val identity = readAction { VaultStatusService.getInstance(project).config(root).identities.firstOrNull { it.label == label } }
            ?: return VaultFailure.NO_IDENTITY
        if (identity.lockState == VaultLockState.UNLOCKED) return null
        return when (val result = VaultOperations.getInstance(project).unlock(root, label)) {
            is VaultUnlockResult.Unlocked -> if (label in result.identities) null else VaultFailure.LOCKED
            is VaultUnlockResult.Failed -> result.failure
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** The ids of [config] that may encrypt, once per label, in Ansible's order. */
    private fun choices(config: VaultRootConfig): List<VaultIdentityChoice> =
        config.identities.distinctBy { it.label }.map { VaultIdentityChoice(it.label, it.lockState) }

    /** Ansible's encrypt-id rule with the env → id mapping and the neighbouring values' labels (F7.9). */
    internal fun encryptChoice(root: AnsibleRoot, environments: List<String>, file: VirtualFile, config: VaultRootConfig, labels: List<String>): EncryptIdentity.Choice {
        val mapped = environments.takeIf { it.isNotEmpty() }?.let {
            val rootKey = VaultIdentityRegistry.getInstance(project).discovery(root).rootKey
            VaultProjectSettings.getInstance(project).rootSettings(rootKey).identityForEnvironments(it)
        }
        val neighbours = runReadActionBlocking {
            val psi = PsiManager.getInstance(project).findFile(file) ?: return@runReadActionBlocking emptyList()
            VaultValuePsi.vaultScalars(psi).mapNotNull { VaultValuePsi.header(it)?.labelOrDefault(config.defaultIdentity) }
        }
        return EncryptIdentity.choose(config.encryptIdentity, mapped, labels, neighbours)
    }

    /** F7.3's warnings, phrased without the value. */
    private fun encryptWarnings(ref: PlainValueRef): List<String> {
        val warnings = ArrayList<String>()
        if (ref.isPlainStyle) {
            when (Yaml11Resolver.resolvePlain(ref.value)) {
                is Resolved.Int -> "int"
                is Resolved.Float -> "float"
                is Resolved.Bool -> "bool"
                is Resolved.Null -> "null"
                is Resolved.Timestamp -> "timestamp"
                else -> null
            }?.let { warnings += message("encrypt.warning.type", it) }
        }
        if (VaultValueText.isTemplated(ref.value)) warnings += message("encrypt.warning.jinja")
        if (ref.environments.size > 1) {
            val rootKey = VaultIdentityRegistry.getInstance(project).discovery(ref.root).rootKey
            if (VaultProjectSettings.getInstance(project).rootSettings(rootKey).mappingsDiffer(ref.environments)) {
                warnings += message("encrypt.warning.environments", ref.environments.joinToString(", "))
            }
        }
        return warnings
    }

    /** The label `ansible-vault edit` writes back for [ref]'s envelope in its root, or null for a malformed envelope. */
    private suspend fun editLabel(ref: VaultValueRef): String? {
        val original = ref.envelope ?: return null
        val defaultIdentity = readAction { VaultStatusService.getInstance(project).config(ref.root).defaultIdentity }
        return EncryptIdentity.editLabel(original, defaultIdentity)
    }

    private fun refAt(location: SourceLocation): VaultValueRef? {
        val psi = PsiManager.getInstance(project).findFile(location.file) ?: return null
        val element = psi.findElementAt(location.offset) ?: return null
        return VaultValuePsi.vaultScalarAt(element)?.let(VaultValueRef::of)
    }

    private fun report(editor: Editor?, outcome: VaultWriteOutcome, written: (String) -> String) {
        when (outcome) {
            is VaultWriteOutcome.Written -> VaultUiFeedback.info(project, editor, written(outcome.identity))
            is VaultWriteOutcome.Failed -> if (outcome.failure != VaultFailure.CANCELLED) VaultUiFeedback.failure(project, editor, outcome.failure)
            VaultWriteOutcome.Changed -> VaultUiFeedback.error(project, editor, message("action.changed"))
            VaultWriteOutcome.Gone -> VaultUiFeedback.error(project, editor, message("action.not.found"))
        }
    }

    /** Runs [block] in the background with progress; unexpected errors are logged by class and reported. */
    private fun launch(title: String, ref: VaultValueRef?, editor: Editor?, block: suspend CoroutineScope.() -> Unit) {
        lastJob = scope.launch {
            try {
                withBackgroundProgress(project, title) { block() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: VaultCorpusGuardException) {
                throw e
            } catch (e: Exception) {
                VaultLog.failure(VaultLog.Operation.REVEAL, e, ref?.file?.path, ref?.keyName)
                onEdt { VaultUiFeedback.failure(project, editor, VaultFailure.SOURCE_UNAVAILABLE) }
            }
        }
    }

    private suspend fun <T> onEdt(block: () -> T): T = withContext(Dispatchers.EDT) { block() }

    companion object {
        fun getInstance(project: Project): VaultValueActions = project.service()

        private fun message(key: String, vararg params: Any): String = AnsibilityVaultUiBundle.message(key, *params)
    }
}
