package de.terletzkiy.ansibility.vault.secrets

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.VaultFailure
import de.terletzkiy.ansibility.api.VaultLockState
import de.terletzkiy.ansibility.api.VaultStatusListener
import de.terletzkiy.ansibility.api.VaultUnlockResult
import de.terletzkiy.ansibility.context.AnsibleStructureListener
import de.terletzkiy.ansibility.run.become.BecomePasswords
import de.terletzkiy.ansibility.semantics.vault.LabelledSecret
import de.terletzkiy.ansibility.semantics.vault.SecretBytes
import de.terletzkiy.ansibility.semantics.vault.SecretLoad
import de.terletzkiy.ansibility.semantics.vault.VaultAes256
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.semantics.vault.VaultFormatException
import de.terletzkiy.ansibility.semantics.vault.VaultMatcher
import de.terletzkiy.ansibility.vault.AnsibilityVaultBundle.message
import de.terletzkiy.ansibility.vault.VaultClock
import de.terletzkiy.ansibility.vault.VaultCorpusGuard
import de.terletzkiy.ansibility.vault.VaultLog
import de.terletzkiy.ansibility.vault.crypto.VaultCrypto
import de.terletzkiy.ansibility.vault.identity.ConsentTarget
import de.terletzkiy.ansibility.vault.identity.ConsentTargets
import de.terletzkiy.ansibility.vault.identity.DiscoveredIdentity
import de.terletzkiy.ansibility.vault.identity.EnvLocalFile
import de.terletzkiy.ansibility.vault.identity.MasterPasswordPrompt
import de.terletzkiy.ansibility.vault.identity.PasswordManagerReader
import de.terletzkiy.ansibility.vault.identity.PasswordManagers
import de.terletzkiy.ansibility.api.VaultSourceOrigin
import de.terletzkiy.ansibility.vault.identity.SecretPlan
import de.terletzkiy.ansibility.vault.identity.VaultDiscovery
import de.terletzkiy.ansibility.vault.identity.VaultIdentityRegistry
import de.terletzkiy.ansibility.vault.identity.VaultPasswordSafeKeys
import de.terletzkiy.ansibility.vault.identity.VaultPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import de.terletzkiy.ansibility.vault.rotate.RotationTempFiles
import org.jetbrains.annotations.TestOnly
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

/** Why every id was locked. */
enum class VaultLockReason {
    /** Lock all, or a lock of one root. */
    MANUAL,

    /** No vault activity for the auto-lock time (D25, 30 minutes). */
    IDLE,

    /** The project closes. */
    PROJECT_CLOSE,
}

/**
 * Copies of unlocked secrets for one operation, in Ansible's order. The caller closes the lease, which zeroes the
 * copies; a lock meanwhile zeroes only the service's own secrets, so an operation in flight completes.
 */
class SecretLease internal constructor(val secrets: List<LabelledSecret>) : AutoCloseable {
    override fun close() = secrets.forEach { it.secret.zero() }

    override fun toString(): String = "SecretLease($secrets)"
}

/**
 * A copy of the current secret of one id and the chain entry it came from (plan amendment R29,
 * [VaultSecretsService.currentSecret]). The caller closes it, which zeroes the copy.
 */
class CurrentSecret internal constructor(val identity: DiscoveredIdentity?, val secret: SecretBytes) : AutoCloseable {
    override fun close() = secret.zero()

    override fun toString(): String = "CurrentSecret(${identity?.label}, ***)"
}

/**
 * What the IDE password store held for one id before Rotate Vault Password… wrote it (plan amendment R29,
 * [VaultSecretsService.snapshotStore]): the entry the rotation writes, whether its label was remembered and for how
 * long, the "before rotation" entry and its record. Holds passwords: [close] zeroes them, [toString] names the id only.
 */
class StoreSnapshot internal constructor(
    val root: AnsibleRoot,
    val label: String,
    internal val serviceName: String,
    internal val entry: CharArray?,
    internal val remembered: Boolean,
    /** The label was remembered for this IDE session only (PasswordSafe's memory store): it goes back there (review, decision 14). */
    internal val sessionOnly: Boolean,
    internal val previous: CharArray?,
    internal val previousRecord: PreviousPasswordRecord?,
) : AutoCloseable {
    override fun close() {
        entry?.fill('\u0000')
        previous?.fill('\u0000')
    }

    override fun toString(): String = "StoreSnapshot(${root.displayName}, $label, ***)"
}

/**
 * The unlocked vault secrets of the project (plan amendment R7/R8, A.13 `VaultSecretsService`; D25, D26).
 *
 * **Unlock** is lazy (the first vault action calls [unlock]) and follows the root's discovery chain
 * ([VaultIdentityRegistry]):
 * 1. **consent** (D25): if a file source of the root has no consent yet, one dialog lists every root with such
 *    sources and exactly what will be read ([Use for all listed] [Choose…] [Not now]). Nothing is read before the
 *    answer. Consents are stored per user at application level ([VaultUserState]), keyed by the source's real path,
 *    size and a salted fingerprint; a changed source, or a `.env.local` that names another file, asks again.
 *    [Not now] declines the listed roots for the session: none of their files is read. The root being unlocked loses
 *    its password managers too (D139; the dialog says so), so only PasswordSafe and the prompt remain for it; other
 *    listed roots keep theirs. An explicit Unlock… of a root ([forgetDeclined]) asks again;
 * 2. **load**, in chain order on `Dispatchers.IO`: password files are read again on every unlock and never copied
 *    (Ansible's strip rules, a vaulted password file decrypted with the secrets before it); `.env.local` yields only
 *    its key; scripts never run here (M4.6, D28); PasswordSafe is read for labels remembered earlier; a password
 *    manager's CLI runs interruptibly, so cancelling the unlock ends it;
 * 3. **prompt** (D26): an interactive id, or the root when nothing else gave a secret, asks for the password on the
 *    EDT, remembered in PasswordSafe, for this session only, or not at all.
 *
 * **Lock**: [lock], [lockAll], the idle lock after [VaultUserState.autoLockMinutes] without vault activity, and
 * project close zero every secret and clear the caches of [VaultCrypto]. A root change (structure event) clears the
 * plaintext cache. Every change bumps [lockTracker] and publishes [VaultStatusListener.TOPIC].
 *
 * **Rotation** (plan amendment R29, Part 1): [secretsOf] / [currentSecret], [rotationEntry], [entryState] and
 * [snapshotStore] / [storeRotation] / [restoreStore] for the IDE password store (the new password under the entry of
 * the group's source or the root's own entry, the old one under a separate "before rotation" entry), [switchRotated] /
 * [addRotated] for the secrets in memory, [readRotatedPassword] and [loadSourceAgain] for Copy and Check. While a
 * rotation's source is not updated yet ([VaultUserState.pendingRotation]), unlocks skip a source that gives the
 * "before rotation" password and [lease] lists the rotated entry first.
 *
 * Secrets live only here, in [SecretBytes] (zeroed on lock; `toString()` is `***`), keyed by the root and the
 * identity's slot ([DiscoveredIdentity.slot]). The corpus guard ([VaultCorpusGuard]) makes [unlock] throw for roots
 * of the real infra repository in tests. Every dialog shown and every password manager read is logged at INFO with
 * names and labels only ([VaultLog]; the reads by [PasswordManagers] itself).
 */
@Service(Service.Level.PROJECT)
class VaultSecretsService(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private class Unlocked(val label: String, val secret: SecretBytes) {
        override fun toString(): String = "$label=***"
    }

    /** What loading one source gave. */
    private sealed interface Load {
        class Loaded(val secret: SecretBytes) : Load {
            override fun toString(): String = "Loaded(***)"
        }

        /** Not read: no consent (declined, or not interactive). */
        data object Declined : Load

        /** A script: needs script trust (M4.6). */
        data object NotTrusted : Load

        /** Missing, unreadable, empty after the strip rules, or a vaulted password file nothing decrypts. */
        data object Unavailable : Load
    }

    private val lock = Any()
    private val tickerLock = Any()
    private val secrets = HashMap<String, LinkedHashMap<String, Unlocked>>()

    /** Roots declined with [Not now] (or left out with [Choose…]) in this session: their files are not read. */
    private val declined = ConcurrentHashMap.newKeySet<String>()

    /** Roots whose own unlock was answered with [Not now] in this session: their password managers are not run either. */
    private val managersDeclined = ConcurrentHashMap.newKeySet<String>()

    private val tracker = SimpleModificationTracker()
    private val unlockMutex = Mutex()

    @Volatile
    private var clock: VaultClock = VaultClock.SYSTEM

    @Volatile
    private var credentials: VaultCredentialStore = PasswordSafeCredentialStore

    @Volatile
    private var managers: PasswordManagerReader = PasswordManagers

    @Volatile
    private var lastActivity: Long = clock.millis()

    private var ticker: Job? = null

    @Volatile
    private var disposed = false

    init {
        project.messageBus.connect(this).subscribe(AnsibleStructureListener.TOPIC, AnsibleStructureListener { cryptoIfCreated()?.clearPlaintexts() })
    }

    /** Bumped on every unlock and lock and whenever the verification cache changes. */
    val lockTracker: ModificationTracker get() = tracker

    // ------------------------------------------------------------------ state

    /** Whether the secret of [identity] (of [discovery]) is in memory. */
    fun lockStateOf(discovery: VaultDiscovery, identity: DiscoveredIdentity): VaultLockState =
        if (isUnlocked(discovery, identity.slot)) VaultLockState.UNLOCKED else VaultLockState.LOCKED

    /** Whether [slot] of [discovery]'s root is unlocked. */
    fun isUnlocked(discovery: VaultDiscovery, slot: String): Boolean = synchronized(lock) { secrets[discovery.rootKey]?.containsKey(slot) == true }

    /** The labels of [discovery]'s root that are unlocked, in chain order (interactive ones without a chain entry last). */
    fun unlockedLabels(discovery: VaultDiscovery): List<String> = synchronized(lock) {
        val slots = secrets[discovery.rootKey] ?: return emptyList()
        val chained = discovery.identities.filter { it.slot in slots }.map { it.label }
        val extra = slots.filterKeys { slot -> discovery.identityInSlot(slot) == null }.values.map { it.label }
        (chained + extra).distinct()
    }

    /** Interactive labels unlocked through the prompt fallback that have no identity in [discovery]'s chain. */
    fun extraInteractiveLabels(discovery: VaultDiscovery): List<String> = synchronized(lock) {
        val slots = secrets[discovery.rootKey] ?: return emptyList()
        slots.filterKeys { slot -> discovery.identityInSlot(slot) == null }.values.map { it.label }.distinct()
    }

    /** True when any id of any root is unlocked. */
    fun anyUnlocked(): Boolean = synchronized(lock) { secrets.values.any { it.isNotEmpty() } }

    /**
     * Copies of the unlocked secrets of [discovery]'s root in Ansible's order: the chain order, each slot once, then
     * interactive secrets the chain has no entry for. The caller closes the lease.
     */
    fun lease(discovery: VaultDiscovery): SecretLease = synchronized(lock) {
        val slots = secrets[discovery.rootKey] ?: return SecretLease(emptyList())
        SecretLease(ordered(discovery, slots).map { (_, unlocked) -> LabelledSecret(unlocked.label, unlocked.secret.copy()) })
    }

    /**
     * The unlocked slots of [discovery]'s root in Ansible's order (the chain, each slot once, then interactive slots the
     * chain has no entry for), except that for an id with a pending rotation ([VaultUserState.pendingRotation]) the
     * slot of the root's own IDE password store entry, which holds the rotated password, comes first among that id's
     * slots (plan amendment R29 review, decision 9): encrypting then uses the new password while the source still gives
     * the old one. Under [lock].
     */
    private fun ordered(discovery: VaultDiscovery, slots: Map<String, Unlocked>): List<Pair<String, Unlocked>> {
        val used = HashSet<String>()
        val list = ArrayList<Pair<String, Unlocked>>()
        for (identity in discovery.identities) {
            val unlocked = slots[identity.slot] ?: continue
            if (used.add(identity.slot)) list += identity.slot to unlocked
        }
        for ((slot, unlocked) in slots) {
            if (used.add(slot)) list += slot to unlocked
        }
        val state = VaultUserState.getInstance()
        for (label in list.map { it.second.label }.distinct()) {
            if (state.pendingRotation(discovery.canonicalRootPath, label) == null) continue
            val stored = list.indexOfFirst { it.first == interactiveSlot(label) }
            val first = list.indexOfFirst { it.second.label == label }
            if (first in 0 until stored) list.add(first, list.removeAt(stored))
        }
        return list
    }

    /** Records vault activity: the idle lock counts from the last one. */
    fun touch() {
        lastActivity = clock.millis()
        ensureTicker()
    }

    /** The current time of the vault clock. */
    fun now(): Long = clock.millis()

    /** Bumps [lockTracker] and notifies [VaultStatusListener]s (after a verification-cache change, for example). */
    fun notifyChanged() {
        tracker.incModificationCount()
        if (!disposed && !project.isDisposed) project.messageBus.syncPublisher(VaultStatusListener.TOPIC).vaultStatusChanged()
    }

    // ------------------------------------------------------------------ unlock

    /**
     * Unlocks [label] of [root] (every id of the root when null) as described in the class comment. [verify] is the
     * envelope the triggering action works on: a prompted password that cannot decrypt it is refused and asked again.
     * With [interactive] false nothing is asked: sources without consent are skipped and no prompt is shown.
     * Cancelling the calling coroutine ends a password manager read that is still waiting.
     *
     * Never call it on the EDT or under a read lock; it switches to the EDT itself for dialogs.
     *
     * @throws de.terletzkiy.ansibility.vault.VaultCorpusGuardException for a root of the real infra repo in tests
     */
    suspend fun unlock(root: AnsibleRoot, label: String? = null, verify: VaultEnvelope? = null, interactive: Boolean = true): VaultUnlockResult =
        unlockMutex.withLock {
            val discovery = registry().discovery(root)
            VaultCorpusGuard.check(discovery.rootPath?.toString() ?: discovery.root.dir.path)
            val wanted = discovery.identities.filter { label == null || it.label == label }
            if (label != null && wanted.isEmpty()) return@withLock VaultUnlockResult.Failed(VaultFailure.NO_IDENTITY)
            val pending = wanted.filter { !isUnlocked(discovery, it.slot) }.distinctBy { it.slot }
            if (pending.isEmpty()) return@withLock VaultUnlockResult.Unlocked(unlockedLabels(discovery))
            UnlockRun(discovery, interactive).use { it.unlock(pending, label, verify) }
        }

    /**
     * One unlock of one root: the consent store, the environment and the sources read at consent time, which the load
     * uses instead of reading them a second time ([close] zeroes whatever was not used).
     */
    private inner class UnlockRun(val discovery: VaultDiscovery, val interactive: Boolean) : AutoCloseable {
        val environment: Map<String, String> = registry().access.environment()
        val consents = VaultConsents(registry().access, VaultUserState.getInstance())
        private val prefetched = HashMap<String, ByteArray>()

        /** The "before rotation" and rotated entries of ids with a pending rotation, read once per run (decision 9). */
        private val rotated = HashMap<String, Pair<SecretBytes?, SecretBytes?>>()

        override fun close() {
            prefetched.values.forEach { it.fill(0) }
            prefetched.clear()
            for ((previous, stored) in rotated.values) {
                previous?.zero()
                stored?.zero()
            }
            rotated.clear()
        }

        override fun toString(): String = "UnlockRun(${discovery.rootKey})"

        /** [identity]'s secret as an unlock would load it, without storing it (Check, plan amendment R29); null when it gives none. */
        suspend fun read(identity: DiscoveredIdentity): SecretBytes? = (load(identity) as? Load.Loaded)?.secret

        suspend fun unlock(pending: List<DiscoveredIdentity>, label: String?, verify: VaultEnvelope?): VaultUnlockResult {
            var changed = false
            var notTrusted = false
            var unavailable = false
            var cancelled = false
            var wrong = false
            try {
                if (interactive) askFirstUseConsent(pending)
                withContext(Dispatchers.IO) { RotationTempFiles.removeLeftovers(discovery) }

                for (identity in pending.filter { !it.isInteractive }) {
                    when (val load = load(identity)) {
                        is Load.Loaded -> if (!skipRotated(identity, load.secret)) changed = store(discovery, identity.slot, identity.label, load.secret) || changed
                        Load.NotTrusted -> notTrusted = true
                        Load.Unavailable -> unavailable = true
                        Load.Declined -> Unit
                    }
                }

                val interactiveLabels = pending.filter { it.isInteractive }.map { it.label }.distinct()
                for (interactiveLabel in interactiveLabels) {
                    val configured = pending.any { it.label == interactiveLabel && it.plan is SecretPlan.PasswordSafeEntry }
                    fromPasswordSafe(discovery, interactiveLabel, configured)?.let {
                        changed = store(discovery, interactiveSlot(interactiveLabel), interactiveLabel, it) || changed
                    }
                }
                val toPrompt = interactiveLabels.filter { !isUnlocked(discovery, interactiveSlot(it)) }.toMutableList()
                val satisfied = if (label != null) label in unlockedLabels(discovery) else unlockedLabels(discovery).isNotEmpty()
                if (toPrompt.isEmpty() && !satisfied) toPrompt += label ?: discovery.config.defaultIdentity

                if (interactive) {
                    for (promptLabel in toPrompt) {
                        when (val prompted = prompt(discovery, promptLabel, verify)) {
                            is PromptResult.Loaded -> changed = store(discovery, interactiveSlot(promptLabel), promptLabel, prompted.secret) || changed
                            PromptResult.Cancelled -> cancelled = true
                            PromptResult.Wrong -> wrong = true
                        }
                        if (cancelled) break
                    }
                }
            } finally {
                // Also when the unlock is cancelled midway (Stop, or Cancel while a password manager is read): the
                // secrets stored by then are unlocked like any other, so the gutter and widgets learn of them and the
                // idle lock covers them.
                if (changed) {
                    cryptoIfCreated()?.clearNegativeVerifications()
                    touch()
                    VaultLog.event(VaultLog.Operation.UNLOCK, VaultLog.Event.UNLOCKED, discovery.rootKey)
                    notifyChanged()
                }
            }
            val labels = unlockedLabels(discovery)
            val done = if (label != null) label in labels else labels.isNotEmpty()
            val result = when {
                done -> VaultUnlockResult.Unlocked(labels)
                cancelled -> VaultUnlockResult.Failed(VaultFailure.CANCELLED)
                wrong -> VaultUnlockResult.Failed(VaultFailure.WRONG_SECRET)
                notTrusted -> VaultUnlockResult.Failed(VaultFailure.NOT_TRUSTED)
                unavailable -> VaultUnlockResult.Failed(VaultFailure.SOURCE_UNAVAILABLE)
                else -> VaultUnlockResult.Failed(if (interactive) VaultFailure.CANCELLED else VaultFailure.LOCKED)
            }
            if (result is VaultUnlockResult.Failed) VaultLog.failure(VaultLog.Operation.UNLOCK, result.failure, discovery.rootKey)
            return result
        }

        /**
         * Plan amendment R29 review, decision 9: while a rotation of [identity]'s id is pending (its source was not
         * updated yet), a source that gives the "before rotation" password is skipped ([secret] is zeroed, true), so the
         * rotated password in the IDE password store is the id's secret; a source that gives the rotated password ends
         * the pending state. When the rotated entry is gone (you removed it), the pending state ends and nothing is
         * skipped: the source may be the only password left. Compared in constant time; the two store entries are read
         * once per run.
         */
        private suspend fun skipRotated(identity: DiscoveredIdentity, secret: SecretBytes): Boolean {
            val state = VaultUserState.getInstance()
            val canonical = discovery.canonicalRootPath
            state.pendingRotation(canonical, identity.label) ?: return false
            val (previous, stored) = rotated.getOrPut(identity.label) {
                withContext(Dispatchers.IO) { readPrevious(canonical, identity.label) to readPasswordSafe(VaultPasswordSafeKeys.serviceName(canonical, identity.label), identity.label) }
            }
            if (stored == null) {
                state.forgetPendingRotation(canonical, identity.label)
                return false
            }
            if (previous != null && sameSecret(previous, secret)) {
                secret.zero()
                VaultLog.event(VaultLog.Operation.LOAD_SOURCE, VaultLog.Event.SOURCE_SKIPPED, identity.source.location ?: identity.slot, identity.label)
                return true
            }
            if (sameSecret(stored, secret)) state.forgetPendingRotation(canonical, identity.label)
            return false
        }

        /**
         * The D25 dialog for every root whose sources need a consent, before anything is read. A consent covers
         * exactly the sources the dialog listed for that root; they are read once afterwards to record their
         * fingerprints, and this root's are kept for the load.
         */
        private suspend fun askFirstUseConsent(pending: List<DiscoveredIdentity>) {
            if (discovery.rootKey in declined) return
            val requested = listed(discovery, pending) ?: return
            val others = registry().discoveries()
                .filter { it.rootKey != discovery.rootKey && it.rootKey !in declined && !isGuarded(it) }
                .mapNotNull { other -> listed(other, other.identities.filter { !isUnlocked(other, it.slot) }) }
            val all = listOf(requested) + others
            val request = VaultConsentRequest(ConsentReason.FIRST_USE, all.map { it.root })
            for (listed in all) VaultLog.event(VaultLog.Operation.CONSENT, VaultLog.Event.CONSENT_ASKED, listed.root.rootKey)
            val decision = onEdt { VaultPrompter.getInstance().askConsent(project, request) }
            val chosen = when (decision) {
                VaultConsentDecision.UseAll -> all.map { it.root.rootKey }.toSet()
                is VaultConsentDecision.Choose -> decision.rootKeys
                VaultConsentDecision.NotNow -> emptySet()
            }
            withContext(Dispatchers.IO) {
                for (listed in all) {
                    if (listed.root.rootKey !in chosen) {
                        decline(listed.root.rootKey, ownUnlock = listed === requested)
                        VaultLog.event(VaultLog.Operation.CONSENT, VaultLog.Event.CONSENT_DECLINED, listed.root.rootKey)
                        continue
                    }
                    for (target in listed.targets) {
                        val content = consents.grant(target, environment) ?: continue
                        if (listed === requested) prefetched.put(target.locator, content)?.fill(0) else content.fill(0)
                    }
                }
            }
        }

        /** The dialog row of [identities] of [rootDiscovery]: the sources that need a consent, or null when none does. */
        private fun listed(rootDiscovery: VaultDiscovery, identities: List<DiscoveredIdentity>): Listed? {
            val targets = LinkedHashSet<ConsentTarget>()
            val items = identities.mapNotNull { identity ->
                val plan = identity.plan
                val needed = plan.consentTargets.filter { consents.needsConsent(it, environment) }
                if (needed.isEmpty()) return@mapNotNull null
                val changed = needed.all(consents::hasConsent)
                val item = when (plan) {
                    is SecretPlan.EnvLocal -> ConsentItem(identity.label, ConsentItem.Kind.ENV_LOCAL_KEY, plan.envLocal.display, plan.expected?.display, changed)
                    is SecretPlan.PasswordFile -> ConsentItem(identity.label, ConsentItem.Kind.PASSWORD_FILE, plan.target.display, changed = changed)
                    is SecretPlan.Environment -> ConsentItem(identity.label, ConsentItem.Kind.ENVIRONMENT, plan.target.display, changed = changed)
                    else -> return@mapNotNull null
                }
                targets += needed
                item
            }.distinctBy { it.kind to it.display }
            if (items.isEmpty()) return null
            val managers = if (rootDiscovery.rootKey == discovery.rootKey) skippedManagers() else emptyList()
            return Listed(ConsentRoot(rootDiscovery.rootKey, rootDiscovery.root.displayName, items, managers), targets.toList())
        }

        /** The password managers of the root being unlocked that a [Not now] would leave unread (D139), for the dialog. */
        private fun skippedManagers(): List<String> =
            discovery.identities.filter { it.plan is SecretPlan.External && !isUnlocked(discovery, it.slot) }
                .map { (it.plan as SecretPlan.External).manager.displayName }
                .distinct()

        /** Loads one non-interactive source on `Dispatchers.IO`. */
        private suspend fun load(identity: DiscoveredIdentity): Load = onIo({ (it as? Load.Loaded)?.secret?.zero() }) {
            when (val plan = identity.plan) {
                is SecretPlan.PasswordFile -> fromFile(identity, readConsented(plan.target, ConsentReason.CHANGED), plan.target.display)
                is SecretPlan.Environment -> fromFile(identity, readConsented(plan.target, ConsentReason.CHANGED), plan.target.display)
                is SecretPlan.EnvLocal -> loadEnvLocal(identity, plan)
                is SecretPlan.Script -> {
                    VaultLog.event(VaultLog.Operation.LOAD_SOURCE, VaultLog.Event.SCRIPT_NOT_RUN, plan.path.toString())
                    Load.NotTrusted
                }
                is SecretPlan.PasswordSafeEntry -> readPasswordSafe(plan.serviceName, identity.label)?.let { Load.Loaded(it) } ?: Load.Unavailable
                is SecretPlan.External -> fromManager(identity, plan)
                SecretPlan.Prompt -> Load.Declined
            }
        }

        /**
         * [identity]'s secret from its password manager, unless the root's own unlock was answered with [Not now] in
         * this session: that covers the root's password managers as well as its files (D139), so nothing runs a CLI
         * that could show its own approval sheet (1Password's Touch ID, the OS asking for access to another app's data)
         * right after you said no. Checked per source, so a [Not now] given during this unlock counts from then on.
         */
        private suspend fun fromManager(identity: DiscoveredIdentity, plan: SecretPlan.External): Load {
            if (discovery.rootKey in managersDeclined) {
                VaultLog.event(VaultLog.Operation.LOAD_SOURCE, VaultLog.Event.MANAGER_READ_SKIPPED, plan.manager.displayName, identity.label)
                return Load.Declined
            }
            return readManager(identity.label, plan, masterPasswordPrompt())
        }

        /**
         * Asks for a password manager's master password on the EDT, only in an interactive unlock of a root that was not
         * declined (a root only listed beside a declined one still reads its managers, but asks nothing for them).
         * [PasswordManagers] logs each dialog.
         */
        private fun masterPasswordPrompt(): MasterPasswordPrompt =
            if (!interactive || discovery.rootKey in declined) MasterPasswordPrompt.NONE
            else MasterPasswordPrompt { manager, target, retry ->
                val request = MasterPasswordRequest(manager.displayName, target, retry)
                runBlocking { onEdt { VaultPrompter.getInstance().askMasterPassword(project, request) } }
            }

        /** `.env.local`: its key only, then the password file it names (asking again when that is not the file shown). */
        private suspend fun loadEnvLocal(identity: DiscoveredIdentity, plan: SecretPlan.EnvLocal): Load {
            val content = when (val read = readConsented(plan.envLocal, ConsentReason.CHANGED)) {
                is Read.Content -> read.bytes
                Read.Declined -> return Load.Declined
                Read.Unavailable -> return Load.Unavailable
            }
            val value = try {
                EnvLocalFile.passwordFile(content, environment)
            } finally {
                content.fill(0)
            }
            val rootPath = discovery.rootPath
            val target = value?.let { VaultPaths.resolve(it, rootPath, environment["HOME"]) }
            if (target == null || rootPath == null) {
                VaultLog.event(VaultLog.Operation.LOAD_SOURCE, VaultLog.Event.SOURCE_SKIPPED, plan.envLocal.display, EnvLocalFile.LOCAL_PASSWORD_FILE_KEY)
                return Load.Unavailable
            }
            val access = registry().access
            if (access.isExecutable(target)) {
                VaultLog.event(VaultLog.Operation.LOAD_SOURCE, VaultLog.Event.SCRIPT_NOT_RUN, target.toString())
                return Load.NotTrusted
            }
            val named = ConsentTargets(rootPath, environment["HOME"], access).file(target)
            val reason = if (plan.expected?.locator == named.locator) ConsentReason.CHANGED else ConsentReason.NAMED_BY_ENV_LOCAL
            return fromFile(identity, readConsented(named, reason), named.display)
        }

        /** Reads [target] under its consent, asking for one (with [reason]) when it is missing or the source changed. */
        private suspend fun readConsented(target: ConsentTarget, reason: ConsentReason): Read {
            prefetched.remove(target.locator)?.let { return Read.Content(it) }
            when (val check = consents.check(target, environment)) {
                is VaultConsents.Check.Granted -> return Read.Content(check.content)
                VaultConsents.Check.Unavailable -> {
                    VaultLog.failure(VaultLog.Operation.LOAD_SOURCE, VaultFailure.SOURCE_UNAVAILABLE, target.display)
                    return Read.Unavailable
                }
                is VaultConsents.Check.Needed -> {
                    if (!interactive || discovery.rootKey in declined) return Read.Declined
                    if (check.changed) VaultLog.event(VaultLog.Operation.CONSENT, VaultLog.Event.CONSENT_CHANGED, target.display)
                    val kind = if (target.environmentVariable != null) ConsentItem.Kind.ENVIRONMENT else ConsentItem.Kind.PASSWORD_FILE
                    val item = ConsentItem(identityLabelFor(target), kind, target.display, changed = check.changed)
                    VaultLog.event(VaultLog.Operation.CONSENT, VaultLog.Event.CONSENT_ASKED, target.display, item.label)
                    val request = VaultConsentRequest(
                        if (check.changed) ConsentReason.CHANGED else reason,
                        listOf(ConsentRoot(discovery.rootKey, discovery.root.displayName, listOf(item), skippedManagers())),
                    )
                    val decision = onEdt { VaultPrompter.getInstance().askConsent(project, request) }
                    val accepted = decision == VaultConsentDecision.UseAll ||
                        (decision is VaultConsentDecision.Choose && discovery.rootKey in decision.rootKeys)
                    if (!accepted) {
                        decline(discovery.rootKey, ownUnlock = true)
                        VaultLog.event(VaultLog.Operation.CONSENT, VaultLog.Event.CONSENT_DECLINED, target.display)
                        return Read.Declined
                    }
                    return onIo({ it?.fill(0) }) { consents.grant(target, environment) }?.let { Read.Content(it) } ?: Read.Unavailable
                }
            }
        }

        private fun identityLabelFor(target: ConsentTarget): String =
            discovery.identities.firstOrNull { target in it.plan.consentTargets }?.label ?: discovery.config.defaultIdentity

        /** Ansible's password-file rules over [read], decrypting a vaulted file with the secrets before [identity] in the chain. */
        private fun fromFile(identity: DiscoveredIdentity, read: Read, display: String): Load {
            val content = when (read) {
                is Read.Content -> read.bytes
                Read.Declined -> return Load.Declined
                Read.Unavailable -> return Load.Unavailable
            }
            try {
                leaseBefore(discovery, identity).use { before ->
                    val decryptor = VaultMatcher.passwordFileDecryptor(before.secrets, discovery.config.idMatch, discovery.config.defaultIdentity)
                    return when (val load = SecretBytes.fromFile(content, decryptor)) {
                        is SecretLoad.Loaded -> {
                            VaultLog.event(VaultLog.Operation.LOAD_SOURCE, VaultLog.Event.SOURCE_LOADED, display)
                            Load.Loaded(load.secret)
                        }
                        is SecretLoad.Failed -> {
                            VaultLog.failure(VaultLog.Operation.LOAD_SOURCE, load.reason, display)
                            Load.Unavailable
                        }
                    }
                }
            } finally {
                content.fill(0)
            }
        }
    }

    /** One root of the D25 dialog: what is listed, and the sources a consent for it covers. */
    private class Listed(val root: ConsentRoot, val targets: List<ConsentTarget>)

    /** The outcome of reading one source under its consent. */
    private sealed interface Read {
        class Content(val bytes: ByteArray) : Read {
            override fun toString(): String = "Content(***)"
        }

        data object Declined : Read

        data object Unavailable : Read
    }

    /** Copies of the unlocked secrets of the chain entries before [identity]. */
    private fun leaseBefore(discovery: VaultDiscovery, identity: DiscoveredIdentity): SecretLease = synchronized(lock) {
        val slots = secrets[discovery.rootKey] ?: return SecretLease(emptyList())
        val before = discovery.identities.takeWhile { it !== identity }
        SecretLease(before.mapNotNull { slots[it.slot] }.distinct().map { LabelledSecret(it.label, it.secret.copy()) })
    }

    /** The root's PasswordSafe entry of [label], when one is [configured] in the chain or was remembered. */
    private suspend fun fromPasswordSafe(discovery: VaultDiscovery, label: String, configured: Boolean): SecretBytes? {
        if (!configured && label !in VaultUserState.getInstance().rememberedLabels(discovery.canonicalRootPath)) return null
        val serviceName = VaultPasswordSafeKeys.serviceName(discovery.canonicalRootPath, label)
        val secret = onIo({ it?.zero() }) { readPasswordSafe(serviceName, label) }
        if (secret == null) VaultUserState.getInstance().forget(discovery.canonicalRootPath, label)
        return secret
    }

    /**
     * The password manager's read of the reference for id [label]; the CLI output never leaves this method except as
     * [SecretBytes]. The read is interruptible: cancelling the calling coroutine (Stop, Cancel) interrupts the wait,
     * and the reader ends the CLI (D139). [PasswordManagers] logs its start and end at INFO with the time it took.
     */
    private suspend fun readManager(label: String, plan: SecretPlan.External, prompt: MasterPasswordPrompt): Load {
        val display = plan.manager.displayName
        val answered = AtomicReference<ByteArray?>()
        val read = try {
            runInterruptible { managers.read(plan.manager, plan.reference, prompt, label).also(answered::set) }
        } catch (e: CancellationException) {
            // Cancelled just after the manager answered: nobody takes the answer, so it is zeroed here.
            answered.getAndSet(null)?.fill(0)
            throw e
        }
        val bytes = read ?: run {
            VaultLog.failure(VaultLog.Operation.LOAD_SOURCE, VaultFailure.SOURCE_UNAVAILABLE, display)
            return Load.Unavailable
        }
        try {
            return when (val load = SecretBytes.fromFile(bytes) { null }) {
                is SecretLoad.Loaded -> {
                    VaultLog.event(VaultLog.Operation.LOAD_SOURCE, VaultLog.Event.SOURCE_LOADED, display)
                    Load.Loaded(load.secret)
                }
                is SecretLoad.Failed -> {
                    VaultLog.failure(VaultLog.Operation.LOAD_SOURCE, load.reason, display)
                    Load.Unavailable
                }
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun readPasswordSafe(serviceName: String, label: String): SecretBytes? {
        val chars = try {
            credentials.read(serviceName, label)
        } catch (e: Exception) {
            VaultLog.failure(VaultLog.Operation.PASSWORD_SAFE, e, key = label)
            null
        } ?: return null
        try {
            return (SecretBytes.fromPrompt(chars) as? SecretLoad.Loaded)?.secret
        } finally {
            chars.fill('\u0000')
        }
    }

    private sealed interface PromptResult {
        class Loaded(val secret: SecretBytes) : PromptResult {
            override fun toString(): String = "Loaded(***)"
        }

        data object Cancelled : PromptResult

        data object Wrong : PromptResult
    }

    /** Asks for [label]'s password (D26), verifying it against [verify] when given, and remembers it as chosen. */
    private suspend fun prompt(discovery: VaultDiscovery, label: String, verify: VaultEnvelope?): PromptResult {
        var error: String? = null
        repeat(MAX_PROMPTS) {
            val request = VaultPasswordRequest(discovery.root.displayName, label, error, credentials.isMemoryOnly)
            VaultLog.event(VaultLog.Operation.PROMPT, VaultLog.Event.PASSWORD_PROMPTED, discovery.rootKey, label)
            val answer = onEdt { VaultPrompter.getInstance().askPassword(project, request) } ?: return PromptResult.Cancelled
            val chars = answer.use { it.take() }
            try {
                val secret = (SecretBytes.fromPrompt(chars) as? SecretLoad.Loaded)?.secret
                if (secret == null) {
                    error = message("password.empty")
                    return@repeat
                }
                try {
                    if (verify != null && !withContext(Dispatchers.Default) { decrypts(discovery, verify, label, secret) }) {
                        secret.zero()
                        error = message("password.wrong", label)
                        VaultLog.failure(VaultLog.Operation.PROMPT, VaultFailure.WRONG_SECRET, discovery.rootKey, label)
                        return@repeat
                    }
                    withContext(Dispatchers.IO) { remember(discovery, label, chars, answer.remember) }
                } catch (e: Throwable) {
                    // Cancelled while checking or remembering it: the typed secret is nobody's now.
                    secret.zero()
                    throw e
                }
                return PromptResult.Loaded(secret)
            } finally {
                chars.fill('\u0000')
            }
        }
        return PromptResult.Wrong
    }

    /** Whether [secret] decrypts [envelope]; true when [label] is no candidate for it (nothing to check against). */
    private fun decrypts(discovery: VaultDiscovery, envelope: VaultEnvelope, label: String, secret: SecretBytes): Boolean {
        val candidates = VaultMatcher.candidates(envelope, listOf(label), discovery.config.idMatch, discovery.config.defaultIdentity)
        if (candidates.isEmpty()) return true
        return try {
            VaultAes256.decrypt(envelope, secret)?.also { it.fill(0) } != null
        } catch (_: VaultFormatException) {
            true
        }
    }

    private fun remember(discovery: VaultDiscovery, label: String, chars: CharArray, choice: RememberChoice) {
        val root = discovery.canonicalRootPath
        val serviceName = VaultPasswordSafeKeys.serviceName(root, label)
        val state = VaultUserState.getInstance()
        try {
            when (choice) {
                RememberChoice.KEYCHAIN -> {
                    credentials.write(serviceName, label, chars, memoryOnly = false)
                    state.remember(root, label, persistent = true)
                }
                RememberChoice.SESSION -> {
                    credentials.write(serviceName, label, chars, memoryOnly = true)
                    state.remember(root, label, persistent = false)
                }
                RememberChoice.NONE -> {
                    credentials.write(serviceName, label, null, memoryOnly = false)
                    state.forget(root, label)
                }
            }
            VaultLog.event(
                VaultLog.Operation.PASSWORD_SAFE,
                if (choice == RememberChoice.NONE) VaultLog.Event.PASSWORD_FORGOTTEN else VaultLog.Event.PASSWORD_REMEMBERED,
                discovery.rootKey,
                label,
            )
        } catch (e: Exception) {
            VaultLog.failure(VaultLog.Operation.PASSWORD_SAFE, e, discovery.rootKey, label)
        }
    }

    /** Stores [secret] in [slot]; true when the slot was locked before. */
    private fun store(discovery: VaultDiscovery, slot: String, label: String, secret: SecretBytes): Boolean = synchronized(lock) {
        if (disposed) {
            secret.zero()
            return false
        }
        val old = secrets.getOrPut(discovery.rootKey) { LinkedHashMap() }.put(slot, Unlocked(label, secret))
        old?.secret?.zero()
        old == null
    }

    // ------------------------------------------------------------------ stored passwords (settings page)

    /**
     * Stores [password] as the IDE password store entry of id [label] of [root] (null removes it) and locks the root,
     * so the next vault action reads the new value. The entry is the one the id's explicit setting names, else the one
     * a remembered prompt uses, so discovery lists it even without an explicit id. Blocking (the Keychain); call off
     * the EDT. The caller zeroes [password].
     */
    fun storePassword(root: AnsibleRoot, label: String, password: CharArray?) {
        val discovery = registry().discovery(root)
        VaultCorpusGuard.check(discovery.rootPath?.toString() ?: discovery.root.dir.path)
        val canonical = discovery.canonicalRootPath
        val serviceName = storedEntry(discovery, label)
        val state = VaultUserState.getInstance()
        try {
            credentials.write(serviceName, label, password?.takeIf { it.isNotEmpty() }, memoryOnly = false)
            if (serviceName == VaultPasswordSafeKeys.serviceName(canonical, label)) {
                if (password != null && password.isNotEmpty()) state.remember(canonical, label, persistent = true) else state.forget(canonical, label)
            }
            VaultLog.event(
                VaultLog.Operation.PASSWORD_SAFE,
                if (password == null) VaultLog.Event.PASSWORD_FORGOTTEN else VaultLog.Event.PASSWORD_REMEMBERED,
                discovery.rootKey,
                label,
            )
        } catch (e: Exception) {
            VaultLog.failure(VaultLog.Operation.PASSWORD_SAFE, e, discovery.rootKey, label)
            throw e
        }
        lock(root)
    }

    /** Whether the IDE password store has an entry for id [label] of [root]. Blocking; call off the EDT. */
    fun hasStoredPassword(root: AnsibleRoot, label: String): Boolean {
        val discovery = registry().discovery(root)
        val chars = try {
            credentials.read(storedEntry(discovery, label), label)
        } catch (e: Exception) {
            VaultLog.failure(VaultLog.Operation.PASSWORD_SAFE, e, discovery.rootKey, label)
            null
        } ?: return false
        chars.fill('\u0000')
        return true
    }

    private fun storedEntry(discovery: VaultDiscovery, label: String): String =
        discovery.identities.firstNotNullOfOrNull { identity ->
            (identity.plan as? SecretPlan.PasswordSafeEntry)?.serviceName?.takeIf { identity.label == label && identity.source.origin == VaultSourceOrigin.SETTINGS }
        } ?: VaultPasswordSafeKeys.serviceName(discovery.canonicalRootPath, label)

    // ------------------------------------------------------------------ Rotate Vault Password… (plan amendment R29, Part 1)

    /**
     * A copy of the current secret of [label] in [root] (of its parent for a nested root): the first unlocked secret
     * with that label in the order [lease] lists them (Ansible's, with a pending rotation's stored entry first), the one
     * encrypting picks, with the identity whose slot holds it (null for an interactive label the chain has no entry
     * for). Null while [label] is locked. The caller closes it.
     */
    fun currentSecret(root: AnsibleRoot, label: String): CurrentSecret? = secretsOf(root, label).let { all ->
        all.drop(1).forEach(CurrentSecret::close)
        all.firstOrNull()
    }

    /**
     * Copies of the distinct unlocked secrets of [label] in [root] (plan amendment R29 review, decision 1: the rotation
     * proves which of them opens the id's envelopes), in the order [lease] lists them, each with the chain entry whose
     * slot holds it first; secrets equal to an earlier one (compared in constant time) are left out. The caller closes
     * every one.
     */
    fun secretsOf(root: AnsibleRoot, label: String): List<CurrentSecret> {
        val discovery = registry().discovery(root)
        return synchronized(lock) {
            val slots = secrets[discovery.rootKey] ?: return emptyList()
            val result = ArrayList<CurrentSecret>()
            for ((slot, unlocked) in ordered(discovery, slots)) {
                if (unlocked.label != label || result.any { sameSecret(it.secret, unlocked.secret) }) continue
                result += CurrentSecret(discovery.identityInSlot(slot)?.takeIf { it.label == label }, unlocked.secret.copy())
            }
            result
        }
    }

    /**
     * The chain entry whose secret is [label]'s current one in [discovery]'s root (as [currentSecret], without copying
     * it): null while [label] is locked or when an interactive slot outside the chain holds it.
     */
    fun currentIdentity(discovery: VaultDiscovery, label: String): DiscoveredIdentity? = synchronized(lock) {
        val slots = secrets[discovery.rootKey] ?: return null
        val slot = ordered(discovery, slots).firstOrNull { it.second.label == label }?.first ?: return null
        discovery.identityInSlot(slot)?.takeIf { it.label == label }
    }

    /**
     * The IDE password store entry a rotation of [label] in [discovery]'s root writes (plan amendment R29, D211; review,
     * decision 10): the entry of [source] when the group's source is one (a remembered prompt or a configured entry),
     * else the root's own remembered entry, never a configured entry that is not the source (it may be shared with roots
     * that are not rotated).
     */
    fun rotationEntry(discovery: VaultDiscovery, label: String, source: DiscoveredIdentity?): String =
        (source?.plan as? SecretPlan.PasswordSafeEntry)?.serviceName?.takeIf { source.label == label }
            ?: VaultPasswordSafeKeys.serviceName(discovery.canonicalRootPath, label)

    /** What an IDE password store entry holds compared with a rotation's old password (decision 1). */
    enum class EntryState { EMPTY, OLD, OTHER }

    /**
     * Whether the entry [serviceName] of [label] holds nothing, [old] (compared in constant time, as the unlock reads
     * the entry) or something else: a rotation overwrites only an empty entry or the old password (plan amendment R29
     * review, decision 1). Blocking (the Keychain); throws when the store cannot be read.
     */
    fun entryState(serviceName: String, label: String, old: SecretBytes): EntryState {
        val chars = credentials.read(serviceName, label) ?: return EntryState.EMPTY
        try {
            val held = (SecretBytes.fromPrompt(chars) as? SecretLoad.Loaded)?.secret ?: return EntryState.OTHER
            return held.use { if (sameSecret(it, old)) EntryState.OLD else EntryState.OTHER }
        } finally {
            chars.fill('\u0000')
        }
    }

    /**
     * What the IDE password store holds for id [label] of [root] before a rotation writes the entry [serviceName]
     * (D211, D212): that entry, whether the label was remembered and for how long, the "before rotation" entry and its
     * record, so a rotation that ends up writing no vault file can put everything back ([restoreStore]). Blocking (the
     * Keychain); call off the EDT. The caller closes it.
     */
    fun snapshotStore(root: AnsibleRoot, label: String, serviceName: String): StoreSnapshot {
        val discovery = registry().discovery(root)
        val canonical = discovery.canonicalRootPath
        val state = VaultUserState.getInstance()
        return StoreSnapshot(
            root, label, serviceName,
            entry = credentials.read(serviceName, label),
            remembered = label in state.rememberedLabels(canonical),
            sessionOnly = state.isRememberedForSession(canonical, label),
            previous = credentials.read(VaultPasswordSafeKeys.previousServiceName(canonical, label), label),
            previousRecord = state.previousPassword(canonical, label),
        )
    }

    /**
     * Step 1 of a rotation (D211, D212): [password] becomes the entry [serviceName] in the IDE password store
     * ([rotationEntry]; the root's own entry is remembered, so discovery lists it), and [previous] (the old password as
     * it opened the files) goes to the separate "before rotation" entry, recorded with [date] in [VaultUserState]; a
     * later rotation of the id replaces both. An old password that is no plain text (raw bytes, spaces at either end)
     * is kept losslessly, encoded with a marker ([encodePrevious], review decision 15). Unlike [storePassword] the root
     * stays unlocked. Blocking; call off the EDT; throws when the store refuses. The caller zeroes the arrays.
     */
    fun storeRotation(root: AnsibleRoot, label: String, serviceName: String, password: CharArray, previous: ByteArray, date: Long) {
        val discovery = registry().discovery(root)
        VaultCorpusGuard.check(discovery.rootPath?.toString() ?: discovery.root.dir.path)
        val canonical = discovery.canonicalRootPath
        val state = VaultUserState.getInstance()
        val kept = encodePrevious(previous)
        try {
            credentials.write(VaultPasswordSafeKeys.previousServiceName(canonical, label), label, kept, memoryOnly = false)
            state.recordPrevious(PreviousPasswordRecord(canonical, label, date))
            credentials.write(serviceName, label, password, memoryOnly = false)
            if (serviceName == VaultPasswordSafeKeys.serviceName(canonical, label)) state.remember(canonical, label, persistent = true)
            VaultLog.event(VaultLog.Operation.PASSWORD_SAFE, VaultLog.Event.PASSWORD_REMEMBERED, discovery.rootKey, label)
        } catch (e: Exception) {
            VaultLog.failure(VaultLog.Operation.PASSWORD_SAFE, e, discovery.rootKey, label)
            throw e
        } finally {
            kept.fill('\u0000')
        }
    }

    /**
     * Puts back what [snapshot] saw before [storeRotation] (a rotation that wrote no vault file): the entry, with the
     * lifetime its label was remembered for (a password remembered for this session only goes back to the memory store,
     * never to the Keychain; review decision 14), and the previous password. Blocking; call off the EDT.
     */
    fun restoreStore(snapshot: StoreSnapshot) {
        val discovery = registry().discovery(snapshot.root)
        val canonical = discovery.canonicalRootPath
        val state = VaultUserState.getInstance()
        val own = snapshot.serviceName == VaultPasswordSafeKeys.serviceName(canonical, snapshot.label)
        try {
            credentials.write(snapshot.serviceName, snapshot.label, snapshot.entry, memoryOnly = own && snapshot.sessionOnly && snapshot.entry != null)
            if (own) {
                if (!snapshot.remembered) state.forget(canonical, snapshot.label) else state.remember(canonical, snapshot.label, persistent = !snapshot.sessionOnly)
            }
            credentials.write(VaultPasswordSafeKeys.previousServiceName(canonical, snapshot.label), snapshot.label, snapshot.previous, memoryOnly = false)
            snapshot.previousRecord?.let(state::recordPrevious) ?: state.forgetPrevious(canonical, snapshot.label)
        } catch (e: Exception) {
            VaultLog.failure(VaultLog.Operation.PASSWORD_SAFE, e, discovery.rootKey, snapshot.label)
        }
    }

    /**
     * Step 4 of a rotation (D211, D213): in [root], every unlocked slot of [label] that holds [old] (compared in
     * constant time) holds [new] now, and the slot of [entry] (the IDE password store entry the rotation just wrote)
     * gets [new] when it is empty; no other slot is touched (review, decision 1: a slot holding another password keeps
     * it). The plaintext and verification caches are cleared (every envelope of the id changed) and
     * [VaultStatusListener]s are told. The caller keeps and zeroes [old] and [new].
     */
    fun switchRotated(root: AnsibleRoot, label: String, old: SecretBytes, new: SecretBytes, entry: String) {
        val discovery = registry().discovery(root)
        synchronized(lock) {
            if (disposed) return
            val slots = secrets.getOrPut(discovery.rootKey) { LinkedHashMap() }
            for ((slot, unlocked) in slots.entries.toList()) {
                if (unlocked.label != label || !sameSecret(unlocked.secret, old)) continue
                slots[slot] = Unlocked(label, new.copy())
                unlocked.secret.zero()
            }
            for (identity in discovery.identities) {
                val plan = identity.plan as? SecretPlan.PasswordSafeEntry ?: continue
                if (identity.label == label && plan.serviceName == entry && identity.slot !in slots) slots[identity.slot] = Unlocked(label, new.copy())
            }
        }
        cryptoIfCreated()?.clearAll()
        touch()
        VaultLog.event(VaultLog.Operation.UNLOCK, VaultLog.Event.UNLOCKED, discovery.rootKey, label)
        notifyChanged()
    }

    /**
     * After a rotation whose vault files could not all be put back (some hold new envelopes, some old ones): [new] is
     * unlocked too, in the root's own entry's slot when that is free, and the old secrets stay, so both kinds of
     * envelopes open in this session (review, decision 6). The caller keeps and zeroes [new].
     */
    fun addRotated(root: AnsibleRoot, label: String, new: SecretBytes) {
        val discovery = registry().discovery(root)
        synchronized(lock) {
            if (disposed) return
            val slots = secrets.getOrPut(discovery.rootKey) { LinkedHashMap() }
            if (slots.values.none { it.label == label && sameSecret(it.secret, new) }) slots.putIfAbsent(interactiveSlot(label), Unlocked(label, new.copy()))
        }
        cryptoIfCreated()?.clearAll()
        touch()
        notifyChanged()
    }

    /**
     * The root's own IDE password store entry of [label] of [root] (where a rotation whose source was not written keeps
     * the new password: Copy New Password and Check read it, never the chain; review decision 9), or null. Blocking.
     */
    fun readRotatedPassword(root: AnsibleRoot, label: String): SecretBytes? =
        readPasswordSafe(VaultPasswordSafeKeys.serviceName(registry().discovery(root).canonicalRootPath, label), label)

    /**
     * The characters of the IDE password store entry [serviceName] of [label] (Copy New Password, which copies them as
     * stored), or null; the caller zeroes them. Blocking.
     */
    fun readEntry(serviceName: String, label: String): CharArray? = try {
        credentials.read(serviceName, label)
    } catch (e: Exception) {
        VaultLog.failure(VaultLog.Operation.PASSWORD_SAFE, e, key = label)
        null
    }

    /** The "before rotation" entry of [label] of [root] (D212), decoded when it was kept as bytes, or null. Blocking. */
    fun readPreviousPassword(root: AnsibleRoot, label: String): SecretBytes? = readPrevious(registry().discovery(root).canonicalRootPath, label)

    private fun readPrevious(canonicalRootPath: String, label: String): SecretBytes? {
        val chars = try {
            credentials.read(VaultPasswordSafeKeys.previousServiceName(canonicalRootPath, label), label)
        } catch (e: Exception) {
            VaultLog.failure(VaultLog.Operation.PASSWORD_SAFE, e, key = label)
            null
        } ?: return null
        try {
            decodePrevious(chars)?.let { bytes ->
                try {
                    return if (bytes.isEmpty()) null else SecretBytes.of(bytes)
                } finally {
                    bytes.fill(0)
                }
            }
            return (SecretBytes.fromPrompt(chars) as? SecretLoad.Loaded)?.secret
        } finally {
            chars.fill('\u0000')
        }
    }

    /** Forgets the previous password of [label] of the root at [canonicalRootPath] (D212): its entry and its record. Blocking. */
    fun forgetPreviousPassword(canonicalRootPath: String, label: String) {
        try {
            credentials.write(VaultPasswordSafeKeys.previousServiceName(canonicalRootPath, label), label, null, memoryOnly = false)
            VaultLog.event(VaultLog.Operation.PASSWORD_SAFE, VaultLog.Event.PASSWORD_FORGOTTEN, canonicalRootPath, label)
        } catch (e: Exception) {
            VaultLog.failure(VaultLog.Operation.PASSWORD_SAFE, e, canonicalRootPath, label)
        }
        VaultUserState.getInstance().forgetPrevious(canonicalRootPath, label)
    }

    /**
     * Reads the source of [identity] (of [root]'s chain) again through the unlock's load path (its consent, Ansible's
     * rules, a password manager's CLI) without storing what it gives: Check after a rotation (D211). The identity is
     * looked up again by its slot in the root's current discovery, which may have been rebuilt since [identity] was
     * taken (a vaulted password file is then still decrypted with the secrets before it only; review U4). Never
     * prompts for a vault password; a consent or a manager's master password may be asked. Null when the source gives
     * nothing or is no longer in the chain.
     */
    suspend fun loadSourceAgain(root: AnsibleRoot, identity: DiscoveredIdentity): SecretBytes? {
        val discovery = registry().discovery(root)
        VaultCorpusGuard.check(discovery.rootPath?.toString() ?: discovery.root.dir.path)
        val current = discovery.identities.firstOrNull { it.slot == identity.slot && it.label == identity.label } ?: return null
        return UnlockRun(discovery, interactive = true).use { it.read(current) }
    }

    private fun sameSecret(a: SecretBytes, b: SecretBytes): Boolean = a.read { left -> b.read { right -> MessageDigest.isEqual(left, right) } }

    // ------------------------------------------------------------------ lock

    /** Locks every id of [root] (of its parent for a nested root) and clears the caches. */
    fun lock(root: AnsibleRoot) {
        val key = registry().rootKey(registry().vaultRoot(root))
        val removed = synchronized(lock) { secrets.remove(key) }
        removed?.values?.forEach { it.secret.zero() }
        cryptoIfCreated()?.clearAll()
        VaultLog.event(VaultLog.Operation.LOCK, VaultLog.Event.LOCKED, key)
        notifyChanged()
    }

    /** Locks every id of every root and clears the plaintext and verification caches. */
    fun lockAll(reason: VaultLockReason = VaultLockReason.MANUAL) {
        val removed = synchronized(lock) { secrets.values.toList().also { secrets.clear() } }
        removed.forEach { slots -> slots.values.forEach { it.secret.zero() } }
        managers.forgetUnlocks()
        // Become passwords remembered "for this session only" follow the vault's lock (plan amendment R12, D86).
        project.serviceIfCreated<BecomePasswords>()?.forgetSession()
        cryptoIfCreated()?.clearAll()
        stopTicker()
        VaultLog.event(
            VaultLog.Operation.LOCK,
            when (reason) {
                VaultLockReason.MANUAL -> VaultLog.Event.LOCKED
                VaultLockReason.IDLE -> VaultLog.Event.IDLE_LOCK
                VaultLockReason.PROJECT_CLOSE -> VaultLog.Event.PROJECT_CLOSE_LOCK
            },
        )
        if (reason != VaultLockReason.PROJECT_CLOSE) notifyChanged()
    }

    /**
     * The idle check (run every [TICK_MILLIS] while anything is unlocked or cached): drops plaintexts unused for
     * 5 minutes and locks everything after [VaultUserState.autoLockMinutes] without vault activity.
     */
    fun checkIdle() {
        val now = clock.millis()
        val crypto = cryptoIfCreated()
        crypto?.dropIdlePlaintexts(now)
        val minutes = VaultUserState.getInstance().autoLockMinutes
        if (minutes > 0 && anyUnlocked() && now - lastActivity >= minutes * MILLIS_PER_MINUTE) lockAll(VaultLockReason.IDLE)
        if (!anyUnlocked() && crypto?.isEmpty != false) stopTicker()
    }

    /** Session decisions are forgotten: roots declined with [Not now] are asked again on their next unlock. */
    fun forgetDeclinedConsents() {
        declined.clear()
        managersDeclined.clear()
    }

    /**
     * Forgets the [Not now] of [root] (of its parent for a nested root) in this session: its next unlock asks for the
     * consent again and runs its password managers. The explicit unlock actions (Unlock vault ids…, the banner's
     * Unlock…) call it, so a [Not now] does not last until the project closes.
     */
    fun forgetDeclined(root: AnsibleRoot) {
        val key = registry().rootKey(registry().vaultRoot(root))
        declined -= key
        managersDeclined -= key
    }

    /** Records a [Not now] for [rootKey]; for the root being unlocked ([ownUnlock]) it covers its password managers too. */
    private fun decline(rootKey: String, ownUnlock: Boolean) {
        declined += rootKey
        if (ownUnlock) managersDeclined += rootKey
    }

    override fun dispose() {
        lockAll(VaultLockReason.PROJECT_CLOSE)
        disposed = true
    }

    private fun ensureTicker() {
        synchronized(tickerLock) {
            if (ticker?.isActive == true || disposed) return
            ticker = scope.launch {
                while (isActive) {
                    delay(TICK_MILLIS)
                    checkIdle()
                }
            }
        }
    }

    private fun stopTicker() {
        synchronized(tickerLock) {
            ticker?.cancel()
            ticker = null
        }
    }

    private fun isGuarded(discovery: VaultDiscovery): Boolean = VaultCorpusGuard.isProtected(discovery.rootPath?.toString() ?: discovery.root.dir.path)

    private suspend fun <T> onEdt(block: () -> T): T = withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) { block() }

    /**
     * [block] on `Dispatchers.IO`. A cancellation can end the switch back after [block] returned a secret, which then
     * reaches nobody: [wipe] zeroes it.
     */
    private suspend fun <T> onIo(wipe: (T) -> Unit, block: suspend () -> T): T {
        val made = AtomicReference<Any?>()
        try {
            return withContext(Dispatchers.IO) { block().also { made.set(it) } }
        } catch (e: CancellationException) {
            @Suppress("UNCHECKED_CAST")
            (made.getAndSet(null) as T?)?.let(wipe)
            throw e
        }
    }

    private fun registry(): VaultIdentityRegistry = VaultIdentityRegistry.getInstance(project)

    /** The caches, unless nothing ever created them (and never during disposal, when services cannot be created). */
    private fun cryptoIfCreated(): VaultCrypto? = project.serviceIfCreated<VaultCrypto>()

    // ------------------------------------------------------------------ test seams

    /** Replaces the clock of the idle lock and the plaintext cache. */
    @TestOnly
    fun setClockForTests(clock: VaultClock) {
        this.clock = clock
        lastActivity = clock.millis()
    }

    /** Replaces the password manager CLIs. */
    @TestOnly
    fun setPasswordManagersForTests(reader: PasswordManagerReader) {
        managers = reader
    }

    /** Replaces the credential store (PasswordSafe). */
    @TestOnly
    fun setCredentialsForTests(store: VaultCredentialStore) {
        credentials = store
    }

    companion object {
        /** How often the idle lock is checked. */
        const val TICK_MILLIS: Long = 30_000L
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val MAX_PROMPTS = 3

        /** The slot of a prompt or remembered password of [label]. */
        fun interactiveSlot(label: String): String = DiscoveredIdentity.interactiveSlot(label)

        /**
         * Marks a "before rotation" entry kept as bytes: a NUL character, which neither a typed password nor a password
         * file's text carries, then a name, then the bytes in base64.
         */
        private const val BYTES_MARKER = "\u0000ansibility-bytes:"

        /**
         * [previous] (an old vault password as it opened the files) as the characters of its "before rotation" entry
         * (plan amendment R29 review, decision 15): its UTF-8 text when it reads back unchanged (valid UTF-8 without
         * ASCII whitespace at either end, which reading an entry strips), else [BYTES_MARKER] and its base64. The caller
         * zeroes the result; no `String` holds the password.
         */
        internal fun encodePrevious(previous: ByteArray): CharArray {
            val plain = previous.isNotEmpty() && !isAsciiSpace(previous.first()) && !isAsciiSpace(previous.last())
            if (plain) utf8Chars(previous)?.let { return it }
            val encoded = Base64.getEncoder().encode(previous)
            try {
                val chars = CharArray(BYTES_MARKER.length + encoded.size)
                BYTES_MARKER.toCharArray(chars)
                for (i in encoded.indices) chars[BYTES_MARKER.length + i] = encoded[i].toInt().toChar()
                return chars
            } finally {
                encoded.fill(0)
            }
        }

        /** The bytes [chars] encode when they start with [BYTES_MARKER] (the caller zeroes them), else null. */
        internal fun decodePrevious(chars: CharArray): ByteArray? {
            if (chars.size < BYTES_MARKER.length || BYTES_MARKER.indices.any { chars[it] != BYTES_MARKER[it] }) return null
            val ascii = ByteArray(chars.size - BYTES_MARKER.length) { chars[BYTES_MARKER.length + it].code.toByte() }
            return try {
                Base64.getDecoder().decode(ascii)
            } catch (_: IllegalArgumentException) {
                null
            } finally {
                ascii.fill(0)
            }
        }

        private fun isAsciiSpace(b: Byte): Boolean = b == ' '.code.toByte() || b in 0x09..0x0D

        /** [bytes] as characters when they are valid UTF-8 (the caller zeroes them), else null. */
        private fun utf8Chars(bytes: ByteArray): CharArray? {
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val buffer = try {
                decoder.decode(ByteBuffer.wrap(bytes))
            } catch (_: CharacterCodingException) {
                return null
            }
            val chars = CharArray(buffer.remaining()).also { buffer.get(it) }
            if (buffer.hasArray()) buffer.array().fill('\u0000')
            return chars
        }

        fun getInstance(project: Project): VaultSecretsService = project.service()
    }
}
