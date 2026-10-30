package de.terletzkiy.ansibility.vault.secrets

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ConcurrentHashMap

/**
 * A consent to read one password source (D25): the source's [locator] (a file's real path, or `env:<NAME>`), its
 * [size] and a salted [fingerprint] of its content when the consent was given. A source whose size or content
 * changed needs a new consent. Not secret: the fingerprint is a salted PBKDF2 digest ([ConsentFingerprint]).
 */
data class ConsentRecord(val locator: String, val size: Long, val fingerprint: String)

/**
 * A previous vault password Rotate Vault Password… kept in the IDE password store (plan amendment R29, D212): the
 * root's real path, the id and when it was rotated (milliseconds). The password itself is only in the password store.
 * Every rotation keeps its own entry, named with its [date] ([dated]); none is ever overwritten (review round 2,
 * decision 2). A record of the first build, which kept one undated entry per root and id, has [dated] false. No secret.
 */
data class PreviousPasswordRecord(val root: String, val label: String, val date: Long, val dated: Boolean = true)

/**
 * A rotation whose password source may not have the new password yet (plan amendment R29 review, decision 9; round 2,
 * decisions 1 and 6): Rotate Vault Password… stored the new password of id [label] of the root at [root] (its real
 * path) in the root's own IDE password store entry, and the source in chain slot [slot] (a password manager, an
 * environment variable, a password file the IDE could not or may not write) may still give the old one. Both are
 * unlocked; encrypting prefers the stored new password until the source gives it, or another one. [state] says how far
 * the rotation got: it is recorded before the first vault file is written, so an interrupted rotation leaves a trace.
 * [date] is when it was rotated. No secret.
 */
data class PendingRotationRecord(val root: String, val label: String, val slot: String?, val date: Long, val state: PendingState = PendingState.PENDING)

/** How far a rotation got (plan amendment R29 review round 2, decision 6). */
enum class PendingState {
    /** The new password is stored; the vault files are being written (some may hold new envelopes). */
    WRITING_FILES,

    /** The vault files are written; the password sources and the secrets in memory are being updated. */
    WRITING_SOURCES,

    /** The rotation ended, and its source does not give the new password yet. */
    PENDING,
}

/**
 * Per-user vault state at application level, in `ansibility-vault-user.xml` of the IDE configuration directory with
 * roaming disabled (DEV.md rule 12, secret rule 5): the consents to read discovered password sources, the labels
 * whose typed password is remembered in PasswordSafe, the idle auto-lock, and whether commits are checked for
 * plaintext keys and broken vaults (plan amendment R21, D167; per user, so a repository cannot turn the check off).
 *
 * Nothing here ever comes from a project file: a cloned repository can ship `.idea/workspace.xml`, but it cannot
 * write the IDE configuration directory, so entries it plants have no effect. No secret is stored here.
 *
 * Since plan amendment R29 also the records of previous vault passwords that Rotate Vault Password… kept in the IDE
 * password store (D212): root, id and date, never the password, one per rotation; the rotations whose source still
 * has to be updated or that did not finish ([PendingRotationRecord]); and which root and id opened envelopes before
 * (X129's teammate hint), all without secrets.
 *
 * Remembered labels come in two lifetimes: persistent (the password is in the password safe) and for this IDE
 * session (the password is in PasswordSafe's memory-only store); only the persistent ones are saved.
 */
@Service(Service.Level.APP)
@State(name = "AnsibilityVaultUser", storages = [Storage(value = "ansibility-vault-user.xml", roamingType = RoamingType.DISABLED)])
class VaultUserState : PersistentStateComponent<VaultUserState.StateBean> {
    private val tracker = SimpleModificationTracker()
    private val consents = ConcurrentHashMap<String, ConsentRecord>()
    private val persistentLabels = ConcurrentHashMap<String, List<String>>()
    private val sessionLabels = ConcurrentHashMap<String, List<String>>()
    private val previous = ConcurrentHashMap<String, PreviousPasswordRecord>()
    private val pending = ConcurrentHashMap<String, PendingRotationRecord>()
    private val opened: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var autoLock: Int = DEFAULT_AUTO_LOCK_MINUTES

    /** Bumped whenever a consent or a remembered label changes. */
    val modificationTracker: ModificationTracker get() = tracker

    /** Minutes without vault activity after which every id locks (D25: 30); 0 turns the idle lock off. */
    var autoLockMinutes: Int
        get() = autoLock
        set(value) {
            autoLock = value.coerceIn(0, MAX_AUTO_LOCK_MINUTES)
        }

    /**
     * Whether the commit check of plan amendment R21 (D167) runs: before a commit from the IDE, the committed content is
     * checked for plaintext private keys, vault password files and broken or decrypted vaults. On by default.
     */
    @Volatile
    var checkCommits: Boolean = true

    /** The consent stored for [locator], or null. */
    fun consent(locator: String): ConsentRecord? = consents[locator]

    /** Stores [record], replacing an earlier consent for the same source. */
    fun grant(record: ConsentRecord) {
        consents[record.locator] = record
        tracker.incModificationCount()
    }

    /** Removes the consent for [locator]. */
    fun revoke(locator: String) {
        if (consents.remove(locator) != null) tracker.incModificationCount()
    }

    /** Whether [label]'s password of [canonicalRootPath] is remembered for this IDE session only (PasswordSafe's memory store). */
    fun isRememberedForSession(canonicalRootPath: String, label: String): Boolean =
        sessionLabels[canonicalRootPath].orEmpty().contains(label) && !persistentLabels[canonicalRootPath].orEmpty().contains(label)

    /** The labels whose password is remembered for the root at [canonicalRootPath]: persistent ones first. */
    fun rememberedLabels(canonicalRootPath: String): List<String> =
        (persistentLabels[canonicalRootPath].orEmpty() + sessionLabels[canonicalRootPath].orEmpty()).distinct()

    /** Records that [label]'s password of [canonicalRootPath] is in PasswordSafe, [persistent]ly or for this session. */
    fun remember(canonicalRootPath: String, label: String, persistent: Boolean) {
        forgetQuietly(canonicalRootPath, label)
        val map = if (persistent) persistentLabels else sessionLabels
        map.merge(canonicalRootPath, listOf(label)) { old, new -> (old + new).distinct() }
        tracker.incModificationCount()
    }

    /** Forgets that [label]'s password of [canonicalRootPath] is remembered. */
    fun forget(canonicalRootPath: String, label: String) {
        if (forgetQuietly(canonicalRootPath, label)) tracker.incModificationCount()
    }

    private fun forgetQuietly(root: String, label: String): Boolean {
        var removed = false
        for (map in listOf(persistentLabels, sessionLabels)) {
            map.computeIfPresent(root) { _, labels ->
                if (label in labels) removed = true
                (labels - label).takeIf { it.isNotEmpty() }
            }
        }
        return removed
    }

    /** The previous passwords Rotate Vault Password… kept (plan amendment R29, D212), by root and id, the newest first per id. */
    fun previousPasswords(): List<PreviousPasswordRecord> =
        previous.values.sortedWith(compareBy<PreviousPasswordRecord>({ it.root }, { it.label }).thenByDescending { it.date })

    /** The previous passwords of [label] of [canonicalRootPath], the newest first (review round 2, decision 2). */
    fun previousPasswords(canonicalRootPath: String, label: String): List<PreviousPasswordRecord> =
        previous.values.filter { it.root == canonicalRootPath && it.label == label }.sortedByDescending { it.date }

    /** Records that a previous password of [record]'s id is kept; every rotation adds its own (review round 2, decision 2). */
    fun recordPrevious(record: PreviousPasswordRecord) {
        previous[previousRecordKey(record)] = record
        tracker.incModificationCount()
    }

    /** Forgets [record] (the caller removes its IDE password store entry). */
    fun forgetPrevious(record: PreviousPasswordRecord) {
        if (previous.remove(previousRecordKey(record)) != null) tracker.incModificationCount()
    }

    private fun previousKey(root: String, label: String): String = "$root\u0000$label"

    private fun previousRecordKey(record: PreviousPasswordRecord): String = previousKey(record.root, record.label) + "\u0000${record.date}\u0000${record.dated}"

    /** The rotation of [label] of [canonicalRootPath] whose source may not have the new password yet (decision 9 of the R29 review), or null. */
    fun pendingRotation(canonicalRootPath: String, label: String): PendingRotationRecord? = pending[previousKey(canonicalRootPath, label)]

    /** Every pending rotation, by root and id. */
    fun pendingRotations(): List<PendingRotationRecord> = pending.values.sortedWith(compareBy({ it.root }, { it.label }))

    /**
     * Records [record] (also each step of a running rotation, review round 2, decision 6); a later rotation of the same
     * root and id replaces it. Discovery does not depend on it, so the [modificationTracker] is not bumped (nor for
     * [forgetPendingRotation] and [recordOpened]).
     */
    fun recordPendingRotation(record: PendingRotationRecord) {
        pending[previousKey(record.root, record.label)] = record
    }

    /**
     * The rotation of [label] of [canonicalRootPath] is settled: its source gives the new password (an unlock read it,
     * or Check said so) or another one (rotated elsewhere), or you replaced or removed the stored new password.
     */
    fun forgetPendingRotation(canonicalRootPath: String, label: String) {
        pending.remove(previousKey(canonicalRootPath, label))
    }

    /**
     * Whether id [label] of the root at [canonicalRootPath] opened an envelope in this IDE before, in any session (X129,
     * plan amendment R29 review, decision 13): a wrong secret after a restart or the idle lock then still suggests a
     * rotation elsewhere. Not secret.
     */
    fun openedBefore(canonicalRootPath: String, label: String): Boolean = previousKey(canonicalRootPath, label) in opened

    /** Records that [label] of [canonicalRootPath] opened an envelope ([openedBefore]); cheap when it is known already. */
    fun recordOpened(canonicalRootPath: String, label: String) {
        opened.add(previousKey(canonicalRootPath, label))
    }

    override fun getState(): StateBean = StateBean().apply {
        autoLockMinutes = autoLock
        checkCommits = this@VaultUserState.checkCommits
        consents = this@VaultUserState.consents.values.sortedBy { it.locator }
            .map { ConsentBean().apply { locator = it.locator; size = it.size; fingerprint = it.fingerprint } }
            .toMutableList()
        remembered = persistentLabels.entries.sortedBy { it.key }
            .flatMap { (root, labels) -> labels.map { label -> RememberedBean().apply { this.root = root; this.label = label } } }
            .toMutableList()
        previous = previousPasswords()
            .map { record -> PreviousBean().apply { root = record.root; label = record.label; date = record.date; dated = record.dated } }
            .toMutableList()
        pendingRotations = pendingRotations()
            .map { record -> PendingBean().apply { root = record.root; label = record.label; slot = record.slot; date = record.date; state = record.state.name } }
            .toMutableList()
        opened = this@VaultUserState.opened.sorted()
            .map { key -> RememberedBean().apply { root = key.substringBefore('\u0000'); label = key.substringAfter('\u0000') } }
            .toMutableList()
    }

    override fun loadState(state: StateBean) {
        autoLockMinutes = state.autoLockMinutes
        checkCommits = state.checkCommits
        consents.clear()
        for (bean in state.consents) {
            val locator = bean.locator ?: continue
            val fingerprint = bean.fingerprint ?: continue
            consents[locator] = ConsentRecord(locator, bean.size, fingerprint)
        }
        persistentLabels.clear()
        for (bean in state.remembered) {
            val root = bean.root ?: continue
            val label = bean.label ?: continue
            persistentLabels.merge(root, listOf(label)) { old, new -> (old + new).distinct() }
        }
        previous.clear()
        for (bean in state.previous) {
            val root = bean.root ?: continue
            val label = bean.label ?: continue
            val record = PreviousPasswordRecord(root, label, bean.date, bean.dated)
            previous[previousRecordKey(record)] = record
        }
        pending.clear()
        for (bean in state.pendingRotations) {
            val root = bean.root ?: continue
            val label = bean.label ?: continue
            val step = PendingState.entries.firstOrNull { it.name == bean.state } ?: PendingState.PENDING
            pending[previousKey(root, label)] = PendingRotationRecord(root, label, bean.slot, bean.date, step)
        }
        opened.clear()
        for (bean in state.opened) {
            val root = bean.root ?: continue
            val label = bean.label ?: continue
            opened += previousKey(root, label)
        }
        tracker.incModificationCount()
    }

    /** Forgets every consent, remembered label and preference (tests share one application). */
    @TestOnly
    fun resetForTests() {
        loadState(StateBean())
        sessionLabels.clear()
    }

    /** XML form of the state. */
    class StateBean {
        @get:Attribute("autoLockMinutes")
        var autoLockMinutes: Int = DEFAULT_AUTO_LOCK_MINUTES

        @get:Attribute("checkCommits")
        var checkCommits: Boolean = true

        @get:XCollection(propertyElementName = "consents", elementName = "consent")
        var consents: MutableList<ConsentBean> = ArrayList()

        @get:XCollection(propertyElementName = "remembered", elementName = "entry")
        var remembered: MutableList<RememberedBean> = ArrayList()

        @get:XCollection(propertyElementName = "previousPasswords", elementName = "previous")
        var previous: MutableList<PreviousBean> = ArrayList()

        @get:XCollection(propertyElementName = "pendingRotations", elementName = "pending")
        var pendingRotations: MutableList<PendingBean> = ArrayList()

        @get:XCollection(propertyElementName = "opened", elementName = "entry")
        var opened: MutableList<RememberedBean> = ArrayList()
    }

    /** XML form of a [ConsentRecord]. */
    @Tag("consent")
    class ConsentBean {
        @get:Attribute("locator")
        var locator: String? = null

        @get:Attribute("size")
        var size: Long = 0

        @get:Attribute("fingerprint")
        var fingerprint: String? = null
    }

    /** XML form of one persistently remembered label. */
    @Tag("entry")
    class RememberedBean {
        @get:Attribute("root")
        var root: String? = null

        @get:Attribute("label")
        var label: String? = null
    }

    /** XML form of a [PreviousPasswordRecord] (plan amendment R29, D212); without `dated` it is the first build's undated entry. */
    @Tag("previous")
    class PreviousBean {
        @get:Attribute("root")
        var root: String? = null

        @get:Attribute("label")
        var label: String? = null

        @get:Attribute("date")
        var date: Long = 0

        @get:Attribute("dated")
        var dated: Boolean = false
    }

    /** XML form of a [PendingRotationRecord] (plan amendment R29 review, decision 9). */
    @Tag("pending")
    class PendingBean {
        @get:Attribute("root")
        var root: String? = null

        @get:Attribute("label")
        var label: String? = null

        @get:Attribute("slot")
        var slot: String? = null

        @get:Attribute("date")
        var date: Long = 0

        /** A [PendingState] name; records of the first build have none and are [PendingState.PENDING]. */
        @get:Attribute("state")
        var state: String? = null
    }

    companion object {
        const val DEFAULT_AUTO_LOCK_MINUTES: Int = 30
        private const val MAX_AUTO_LOCK_MINUTES = 24 * 60

        fun getInstance(): VaultUserState = service()
    }
}
