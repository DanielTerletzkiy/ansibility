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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ConcurrentHashMap

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
 * The unlocked vault secrets of the project (plan amendment R7/R8, A.13 `VaultSecretsService`; D25, D26).
 *
 * **Unlock** is lazy (the first vault action calls [unlock]) and follows the root's discovery chain
 * ([VaultIdentityRegistry]):
 * 1. **consent** (D25): if a file source of the root has no consent yet, one dialog lists every root with such
 *    sources and exactly what will be read ([Use for all listed] [Choose…] [Not now]). Nothing is read before the
 *    answer. Consents are stored per user at application level ([VaultUserState]), keyed by the source's real path,
 *    size and a salted fingerprint; a changed source, or a `.env.local` that names another file, asks again;
 * 2. **load**, in chain order on `Dispatchers.IO`: password files are read again on every unlock and never copied
 *    (Ansible's strip rules, a vaulted password file decrypted with the secrets before it); `.env.local` yields only
 *    its key; scripts never run here (M4.6, D28); PasswordSafe is read for labels remembered earlier;
 * 3. **prompt** (D26): an interactive id, or the root when nothing else gave a secret, asks for the password on the
 *    EDT, remembered in PasswordSafe, for this session only, or not at all.
 *
 * **Lock**: [lock], [lockAll], the idle lock after [VaultUserState.autoLockMinutes] without vault activity, and
 * project close zero every secret and clear the caches of [VaultCrypto]. A root change (structure event) clears the
 * plaintext cache. Every change bumps [lockTracker] and publishes [VaultStatusListener.TOPIC].
 *
 * Secrets live only here, in [SecretBytes] (zeroed on lock; `toString()` is `***`), keyed by the root and the
 * identity's slot ([DiscoveredIdentity.slot]). The corpus guard ([VaultCorpusGuard]) makes [unlock] throw for roots
 * of the real infra repository in tests.
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
    private val declined = ConcurrentHashMap.newKeySet<String>()
    private val tracker = SimpleModificationTracker()
    private val unlockMutex = Mutex()

    @Volatile
    private var clock: VaultClock = VaultClock.SYSTEM

    @Volatile
    private var credentials: VaultCredentialStore = PasswordSafeCredentialStore

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
        val used = HashSet<String>()
        val list = ArrayList<LabelledSecret>()
        for (identity in discovery.identities) {
            val unlocked = slots[identity.slot] ?: continue
            if (used.add(identity.slot)) list += LabelledSecret(unlocked.label, unlocked.secret.copy())
        }
        for ((slot, unlocked) in slots) {
            if (used.add(slot)) list += LabelledSecret(unlocked.label, unlocked.secret.copy())
        }
        SecretLease(list)
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

        override fun close() {
            prefetched.values.forEach { it.fill(0) }
            prefetched.clear()
        }

        override fun toString(): String = "UnlockRun(${discovery.rootKey})"

        suspend fun unlock(pending: List<DiscoveredIdentity>, label: String?, verify: VaultEnvelope?): VaultUnlockResult {
            if (interactive) askFirstUseConsent(pending)

            var changed = false
            var notTrusted = false
            var unavailable = false
            for (identity in pending.filter { !it.isInteractive }) {
                when (val load = load(identity)) {
                    is Load.Loaded -> changed = store(discovery, identity.slot, identity.label, load.secret) || changed
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

            var cancelled = false
            var wrong = false
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

            if (changed) {
                cryptoIfCreated()?.clearNegativeVerifications()
                touch()
                VaultLog.event(VaultLog.Operation.UNLOCK, VaultLog.Event.UNLOCKED, discovery.rootKey)
                notifyChanged()
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
            VaultLog.event(VaultLog.Operation.CONSENT, VaultLog.Event.CONSENT_ASKED, discovery.rootKey)
            val decision = onEdt { VaultPrompter.getInstance().askConsent(project, request) }
            val chosen = when (decision) {
                VaultConsentDecision.UseAll -> all.map { it.root.rootKey }.toSet()
                is VaultConsentDecision.Choose -> decision.rootKeys
                VaultConsentDecision.NotNow -> emptySet()
            }
            withContext(Dispatchers.IO) {
                for (listed in all) {
                    if (listed.root.rootKey !in chosen) {
                        declined += listed.root.rootKey
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
            return Listed(ConsentRoot(rootDiscovery.rootKey, rootDiscovery.root.displayName, items), targets.toList())
        }

        /** Loads one non-interactive source on `Dispatchers.IO`. */
        private suspend fun load(identity: DiscoveredIdentity): Load = withContext(Dispatchers.IO) {
            when (val plan = identity.plan) {
                is SecretPlan.PasswordFile -> fromFile(identity, readConsented(plan.target, ConsentReason.CHANGED), plan.target.display)
                is SecretPlan.Environment -> fromFile(identity, readConsented(plan.target, ConsentReason.CHANGED), plan.target.display)
                is SecretPlan.EnvLocal -> loadEnvLocal(identity, plan)
                is SecretPlan.Script -> {
                    VaultLog.event(VaultLog.Operation.LOAD_SOURCE, VaultLog.Event.SCRIPT_NOT_RUN, plan.path.toString())
                    Load.NotTrusted
                }
                is SecretPlan.PasswordSafeEntry -> readPasswordSafe(plan.serviceName, identity.label)?.let { Load.Loaded(it) } ?: Load.Unavailable
                SecretPlan.Prompt -> Load.Declined
            }
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
                    val request = VaultConsentRequest(
                        if (check.changed) ConsentReason.CHANGED else reason,
                        listOf(ConsentRoot(discovery.rootKey, discovery.root.displayName, listOf(item))),
                    )
                    val decision = onEdt { VaultPrompter.getInstance().askConsent(project, request) }
                    val accepted = decision == VaultConsentDecision.UseAll ||
                        (decision is VaultConsentDecision.Choose && discovery.rootKey in decision.rootKeys)
                    if (!accepted) {
                        declined += discovery.rootKey
                        VaultLog.event(VaultLog.Operation.CONSENT, VaultLog.Event.CONSENT_DECLINED, target.display)
                        return Read.Declined
                    }
                    return withContext(Dispatchers.IO) { consents.grant(target, environment) }?.let { Read.Content(it) } ?: Read.Unavailable
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
        val secret = withContext(Dispatchers.IO) { readPasswordSafe(serviceName, label) }
        if (secret == null) VaultUserState.getInstance().forget(discovery.canonicalRootPath, label)
        return secret
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
                if (verify != null && !withContext(Dispatchers.Default) { decrypts(discovery, verify, label, secret) }) {
                    secret.zero()
                    error = message("password.wrong", label)
                    VaultLog.failure(VaultLog.Operation.PROMPT, VaultFailure.WRONG_SECRET, discovery.rootKey, label)
                    return@repeat
                }
                withContext(Dispatchers.IO) { remember(discovery, label, chars, answer.remember) }
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

        fun getInstance(project: Project): VaultSecretsService = project.service()
    }
}
