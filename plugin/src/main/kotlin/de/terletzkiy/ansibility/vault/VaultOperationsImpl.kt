package de.terletzkiy.ansibility.vault

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VaultDecryptResult
import de.terletzkiy.ansibility.api.VaultEncryptResult
import de.terletzkiy.ansibility.api.VaultFailure
import de.terletzkiy.ansibility.api.VaultHeaderInfo
import de.terletzkiy.ansibility.api.VaultOperations
import de.terletzkiy.ansibility.api.VaultPurpose
import de.terletzkiy.ansibility.api.VaultUnlockResult
import de.terletzkiy.ansibility.semantics.vault.DecryptOutcome
import de.terletzkiy.ansibility.semantics.vault.EncryptIdentity
import de.terletzkiy.ansibility.semantics.vault.EnvelopeParse
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.semantics.vault.VaultMatcher
import de.terletzkiy.ansibility.vault.crypto.OwnedPlaintext
import de.terletzkiy.ansibility.vault.crypto.VaultCrypto
import de.terletzkiy.ansibility.vault.crypto.Verification
import de.terletzkiy.ansibility.vault.envelope.LocatedEnvelope
import de.terletzkiy.ansibility.vault.envelope.VaultEnvelopes
import de.terletzkiy.ansibility.vault.identity.VaultDiscovery
import de.terletzkiy.ansibility.vault.identity.VaultIdentityRegistry
import de.terletzkiy.ansibility.vault.identity.VaultProjectSettings
import de.terletzkiy.ansibility.vault.secrets.VaultLockReason
import de.terletzkiy.ansibility.vault.secrets.VaultSecretsService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [VaultOperations] on the codec (plan amendment R7/R8, A.13 `VaultCrypto`): the explicit vault actions.
 *
 * - **decrypt** never prompts: the root's unlocked secrets are tried in Ansible's order with its matching rules
 *   (`vault_id_match` and its quirk); a locked candidate gives [VaultFailure.LOCKED]. The cipher runs on
 *   `Dispatchers.Default`. The result's [de.terletzkiy.ansibility.api.VaultPlaintext] belongs to the caller. Every
 *   outcome is recorded in the verification cache; plaintexts in the plaintext cache (5 minutes idle).
 * - **encrypt** picks the id as Ansible does ([EncryptIdentity.choose]: `vault_encrypt_identity`, the env → id
 *   mapping, the only id) and writes `1.1` for `default`, `1.2;AES256;<label>` otherwise, with `vault_encrypt_salt`
 *   when configured.
 * - **reveal** unlocks lazily (consent, PasswordSafe, prompt) and hands the plaintext to a [VaultRevealPresenter].
 * - [VaultPurpose.ANALYSIS] is refused unless the root's D31 opt-in is on.
 *
 * Failures are logged with the file, the key and the failure class only ([VaultLog]).
 */
class VaultOperationsImpl(private val project: Project, private val scope: CoroutineScope) : VaultOperations {
    /** An envelope and the root whose ids decrypt it. */
    private class Target(val located: LocatedEnvelope, val root: AnsibleRoot?)

    override suspend fun unlock(root: AnsibleRoot, identity: String?): VaultUnlockResult = secrets().unlock(root, identity)

    override fun lockAll() = secrets().lockAll(VaultLockReason.MANUAL)

    override suspend fun decrypt(location: SourceLocation, purpose: VaultPurpose): VaultDecryptResult {
        val target = readAction { resolve(location) } ?: return VaultDecryptResult.Failed(VaultFailure.FORMAT, emptyList())
        return decrypt(target, purpose)
    }

    private suspend fun decrypt(target: Target, purpose: VaultPurpose): VaultDecryptResult {
        val located = target.located
        VaultCorpusGuard.check(located.file.path)
        val root = target.root ?: return failed(located, VaultFailure.NO_IDENTITY)
        val discovery = registry().discovery(root)
        if (purpose == VaultPurpose.ANALYSIS && !VaultProjectSettings.getInstance(project).rootSettings(discovery.rootKey).analyzeDecryptedValues) {
            return failed(located, VaultFailure.ANALYSIS_DISABLED)
        }
        val envelope = when (val parse = located.parse) {
            is EnvelopeParse.Ok -> parse.envelope
            is EnvelopeParse.UnknownCipher -> return failed(located, VaultFailure.UNKNOWN_CIPHER)
            else -> return failed(located, VaultFailure.FORMAT)
        }
        val secrets = secrets()
        val crypto = VaultCrypto.getInstance(project)
        val config = discovery.config
        val chainLabels = discovery.labels + secrets.extraInteractiveLabels(discovery)
        val candidates = VaultMatcher.candidates(envelope, chainLabels, config.idMatch, config.defaultIdentity)
        if (candidates.isEmpty()) return failed(located, VaultFailure.NO_IDENTITY)

        val fingerprint = crypto.fingerprint(envelope)
        val unlockedLabels = secrets.unlockedLabels(discovery)
        crypto.verification(discovery.rootKey, fingerprint)?.decryptsWith?.takeIf { it in unlockedLabels }?.let { label ->
            crypto.cachedPlaintext(discovery.rootKey, fingerprint, label, secrets.now())?.let { cached ->
                secrets.touch()
                return VaultDecryptResult.Decrypted(label, cached)
            }
        }

        secrets.lease(discovery).use { lease ->
            if (lease.secrets.isEmpty()) return failed(located, VaultFailure.LOCKED)
            val outcome = withContext(Dispatchers.Default) { crypto.decrypt(envelope, lease.secrets, config.idMatch, config.defaultIdentity) }
            secrets.touch()
            return when (outcome) {
                is DecryptOutcome.Decrypted -> {
                    val plaintext = outcome.release()
                    record(discovery, fingerprint, Verification(outcome.label, outcome.tried, outcome.labelMatches))
                    crypto.cachePlaintext(discovery.rootKey, fingerprint, outcome.label, plaintext, secrets.now())
                    VaultLog.event(VaultLog.Operation.DECRYPT, VaultLog.Event.DECRYPTED, located.file.path, located.keyName)
                    VaultDecryptResult.Decrypted(outcome.label, OwnedPlaintext(plaintext))
                }
                is DecryptOutcome.NoSecretWorked -> {
                    if (outcome.tried.isNotEmpty()) record(discovery, fingerprint, Verification(null, outcome.tried, labelMatches = false))
                    // A locked candidate might still decrypt it: that is LOCKED, not a wrong secret.
                    val lockedCandidate = candidates.any { index ->
                        index < discovery.identities.size && !secrets.isUnlocked(discovery, discovery.identities[index].slot)
                    }
                    failed(located, if (lockedCandidate) VaultFailure.LOCKED else VaultFailure.WRONG_SECRET, outcome.tried)
                }
                is DecryptOutcome.FormatError -> {
                    VaultLog.failure(VaultLog.Operation.DECRYPT, outcome.reason, located.file.path, located.keyName)
                    VaultDecryptResult.Failed(VaultFailure.FORMAT, outcome.tried)
                }
            }
        }
    }

    override suspend fun encrypt(root: AnsibleRoot, plaintext: ByteArray, identity: String?, target: VirtualFile?): VaultEncryptResult {
        val registry = registry()
        val discovery = registry.discovery(root)
        VaultCorpusGuard.check(discovery.rootPath?.toString() ?: discovery.root.dir.path)
        val secrets = secrets()
        val config = discovery.config
        val chainLabels = discovery.labels + secrets.extraInteractiveLabels(discovery)
        val label = identity ?: when (val choice = chooseIdentity(discovery, chainLabels, target)) {
            is EncryptIdentity.Choice.Chosen -> choice.label
            is EncryptIdentity.Choice.Ambiguous -> return encryptFailed(discovery, VaultFailure.ENCRYPT_IDENTITY_REQUIRED)
            is EncryptIdentity.Choice.NotFound, EncryptIdentity.Choice.NoIdentity -> return encryptFailed(discovery, VaultFailure.NO_IDENTITY)
        }
        if (label !in chainLabels) return encryptFailed(discovery, VaultFailure.NO_IDENTITY)
        if (VaultEnvelope.isEncrypted(plaintext)) return encryptFailed(discovery, VaultFailure.FORMAT)
        val crypto = VaultCrypto.getInstance(project)
        secrets.lease(discovery).use { lease ->
            val secret = lease.secrets.firstOrNull { it.label == label }?.secret ?: return encryptFailed(discovery, VaultFailure.LOCKED)
            val envelope = withContext(Dispatchers.Default) { crypto.encrypt(plaintext, secret, label, config.encryptSaltBytes()) }
            secrets.touch()
            record(discovery, crypto.fingerprint(envelope), Verification(label, listOf(label), envelope.labelOrDefault(config.defaultIdentity) == label))
            VaultLog.event(VaultLog.Operation.ENCRYPT, VaultLog.Event.ENCRYPTED, target?.path ?: discovery.rootKey)
            return VaultEncryptResult.Encrypted(envelope.format(), VaultHeaderInfo(envelope.version, envelope.cipher, envelope.label), label)
        }
    }

    /** Ansible's encrypt-id rule with the IDE's env → id mapping for [target] (F7.9). */
    private suspend fun chooseIdentity(discovery: VaultDiscovery, labels: List<String>, target: VirtualFile?): EncryptIdentity.Choice {
        val environments = target?.let { file -> readAction { AnsibleWorkspace.getInstance(project).contextOf(file)?.environments } }
        val mapped = environments?.takeIf { it.isNotEmpty() }
            ?.let { VaultProjectSettings.getInstance(project).rootSettings(discovery.rootKey).identityForEnvironments(it) }
        return EncryptIdentity.choose(discovery.config.encryptIdentity, mapped, labels)
    }

    override fun reveal(location: SourceLocation, editor: Editor?) {
        val presenter = VaultRevealPresenter.EP_NAME.extensionList.firstOrNull()
        if (presenter == null) {
            VaultLog.event(VaultLog.Operation.REVEAL, VaultLog.Event.NO_PRESENTER, location.file.path)
            return
        }
        scope.launch {
            val target = readAction { resolve(location) } ?: return@launch
            val result = try {
                withBackgroundProgress(project, AnsibilityVaultBundle.message("progress.reveal")) { revealDecrypt(target) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: VaultCorpusGuardException) {
                throw e
            } catch (e: Exception) {
                VaultLog.failure(VaultLog.Operation.REVEAL, e, location.file.path, target.located.keyName)
                VaultDecryptResult.Failed(VaultFailure.SOURCE_UNAVAILABLE, emptyList())
            }
            withContext(Dispatchers.EDT) {
                when (result) {
                    is VaultDecryptResult.Decrypted -> try {
                        presenter.present(project, location, editor?.takeUnless { it.isDisposed }, result.identity, result.plaintext)
                    } catch (e: Exception) {
                        result.plaintext.close()
                        throw e
                    }
                    is VaultDecryptResult.Failed -> presenter.failed(project, location, editor?.takeUnless { it.isDisposed }, result.failure)
                }
            }
        }
    }

    /** Decrypts for Reveal, unlocking the root first when its candidates are locked (the lazy unlock of D25). */
    private suspend fun revealDecrypt(target: Target): VaultDecryptResult {
        val first = decrypt(target, VaultPurpose.REVEAL)
        if (first !is VaultDecryptResult.Failed || first.failure != VaultFailure.LOCKED) return first
        val root = target.root ?: return first
        val unlocked = secrets().unlock(root, label = null, verify = target.located.envelope)
        if (unlocked is VaultUnlockResult.Failed) return VaultDecryptResult.Failed(unlocked.failure, first.tried)
        return decrypt(target, VaultPurpose.REVEAL)
    }

    private fun resolve(location: SourceLocation): Target? {
        val located = VaultEnvelopes.at(project, location) ?: return null
        return Target(located, AnsibleWorkspace.getInstance(project).rootFor(located.file))
    }

    private fun record(discovery: VaultDiscovery, fingerprint: String, verification: Verification) {
        if (VaultCrypto.getInstance(project).record(discovery.rootKey, fingerprint, verification)) secrets().notifyChanged()
    }

    private fun failed(located: LocatedEnvelope, failure: VaultFailure, tried: List<String> = emptyList()): VaultDecryptResult.Failed {
        VaultLog.failure(VaultLog.Operation.DECRYPT, failure, located.file.path, located.keyName)
        return VaultDecryptResult.Failed(failure, tried)
    }

    private fun encryptFailed(discovery: VaultDiscovery, failure: VaultFailure): VaultEncryptResult.Failed {
        VaultLog.failure(VaultLog.Operation.ENCRYPT, failure, discovery.rootKey)
        return VaultEncryptResult.Failed(failure)
    }

    private fun secrets(): VaultSecretsService = VaultSecretsService.getInstance(project)

    private fun registry(): VaultIdentityRegistry = VaultIdentityRegistry.getInstance(project)
}
