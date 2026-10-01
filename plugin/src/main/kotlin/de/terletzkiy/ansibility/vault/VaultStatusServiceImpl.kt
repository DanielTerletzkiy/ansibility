package de.terletzkiy.ansibility.vault

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.psi.PsiElement
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VaultIdentity
import de.terletzkiy.ansibility.api.VaultLockState
import de.terletzkiy.ansibility.api.VaultRootConfig
import de.terletzkiy.ansibility.api.VaultSecretSource
import de.terletzkiy.ansibility.api.VaultSourceKind
import de.terletzkiy.ansibility.api.VaultSourceOrigin
import de.terletzkiy.ansibility.api.VaultStatus
import de.terletzkiy.ansibility.api.VaultStatusService
import de.terletzkiy.ansibility.vault.crypto.VaultCrypto
import de.terletzkiy.ansibility.vault.envelope.LocatedEnvelope
import de.terletzkiy.ansibility.vault.envelope.VaultEnvelopes
import de.terletzkiy.ansibility.vault.identity.VaultDiscovery
import de.terletzkiy.ansibility.vault.identity.VaultIdentityRegistry
import de.terletzkiy.ansibility.vault.secrets.VaultSecretsService

/**
 * [VaultStatusService]: what the card, the banner, the status bar and the tool window may say about vault values.
 * It never decrypts, never runs anything and never reads a secret: the header comes from the envelope, the ids and
 * their lock state from [VaultIdentityRegistry] (existence checks only) and [VaultSecretsService], and "decrypts with"
 * from the verification cache of [VaultCrypto], which only explicit actions fill.
 *
 * A root's candidate ids for an envelope follow Ansible's matching (`vault_id_match` and its quirk included); the
 * reported [VaultStatus.identity] is the candidate labelled like the envelope, else the first candidate.
 */
class VaultStatusServiceImpl(private val project: Project) : VaultStatusService {
    override fun status(element: PsiElement): VaultStatus? = VaultEnvelopes.at(element)?.let(::statusOf)

    override fun status(location: SourceLocation): VaultStatus? = VaultEnvelopes.at(project, location)?.let(::statusOf)

    override fun config(root: AnsibleRoot): VaultRootConfig {
        val registry = VaultIdentityRegistry.getInstance(project)
        val discovery = registry.discovery(root)
        val inheritedFrom = discovery.rootKey.takeIf { registry.vaultRoot(root).dir != root.dir }
        return VaultRootConfig(identities(discovery, inheritedFrom), discovery.config.defaultIdentity, discovery.config.idMatchRaw, discovery.config.encryptIdentity)
    }

    override val lockTracker: ModificationTracker get() = VaultSecretsService.getInstance(project).lockTracker

    /** The status of [located], judged against the ids of its root. */
    internal fun statusOf(located: LocatedEnvelope): VaultStatus {
        val plaintextLength = located.envelope?.plaintextLength?.takeUnless { it.isEmpty() }
        val root = AnsibleWorkspace.getInstance(project).rootFor(located.file)
            ?: return VaultStatus(located.header, located.kind, null, VaultLockState.NO_IDENTITY, null, false, plaintextLength)
        val discovery = VaultIdentityRegistry.getInstance(project).discovery(root)
        val identities = identities(discovery, inheritedFrom = null)
        val defaultIdentity = discovery.config.defaultIdentity
        val label = located.header.labelOrDefault(defaultIdentity)
        val candidates = candidates(label, identities.map { it.label }, discovery.config.idMatch).map(identities::get)
        val identity = candidates.firstOrNull { it.label == label } ?: candidates.firstOrNull()
        val lockState = when {
            candidates.isEmpty() -> VaultLockState.NO_IDENTITY
            candidates.any { it.lockState == VaultLockState.UNLOCKED } -> VaultLockState.UNLOCKED
            else -> VaultLockState.LOCKED
        }
        val verification = located.envelope?.let { envelope ->
            val crypto = VaultCrypto.getInstance(project)
            crypto.verification(discovery.rootKey, crypto.fingerprint(envelope))
        }
        return VaultStatus(located.header, located.kind, identity, lockState, verification?.decryptsWith, verification != null, plaintextLength)
    }

    /** The chain as API identities, plus prompted secrets the chain has no entry for. */
    private fun identities(discovery: VaultDiscovery, inheritedFrom: String?): List<VaultIdentity> {
        val secrets = VaultSecretsService.getInstance(project)
        val chained = discovery.identities.map { VaultIdentity(it.label, it.source, secrets.lockStateOf(discovery, it), inheritedFrom) }
        val extra = secrets.extraInteractiveLabels(discovery).map { label ->
            VaultIdentity(label, VaultSecretSource(VaultSourceKind.PROMPT, null, VaultSourceOrigin.PROMPT), VaultLockState.UNLOCKED, inheritedFrom)
        }
        return chained + extra
    }

    companion object {
        /**
         * The indices into [labels] that Ansible tries for an envelope labelled [label] (`decrypt_and_get_vault_id`,
         * as [de.terletzkiy.ansibility.semantics.vault.VaultMatcher.candidates]): the label's own ids, plus every
         * other id unless [idMatch]; in list order. Works on labels so a malformed envelope gets a status too.
         */
        internal fun candidates(label: String, labels: List<String>, idMatch: Boolean): List<Int> =
            labels.indices.filter { (labels[it] == label && label.isNotEmpty()) || (!idMatch && labels[it] != label) }
    }
}
