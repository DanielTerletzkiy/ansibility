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
 * Per-user vault state at application level, in `ansibility-vault-user.xml` of the IDE configuration directory with
 * roaming disabled (DEV.md rule 12, secret rule 5): the consents to read discovered password sources, the labels
 * whose typed password is remembered in PasswordSafe, the idle auto-lock, and whether commits are checked for
 * plaintext keys and broken vaults (plan amendment R21, D167; per user, so a repository cannot turn the check off).
 *
 * Nothing here ever comes from a project file: a cloned repository can ship `.idea/workspace.xml`, but it cannot
 * write the IDE configuration directory, so entries it plants have no effect. No secret is stored here.
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

    override fun getState(): StateBean = StateBean().apply {
        autoLockMinutes = autoLock
        checkCommits = this@VaultUserState.checkCommits
        consents = this@VaultUserState.consents.values.sortedBy { it.locator }
            .map { ConsentBean().apply { locator = it.locator; size = it.size; fingerprint = it.fingerprint } }
            .toMutableList()
        remembered = persistentLabels.entries.sortedBy { it.key }
            .flatMap { (root, labels) -> labels.map { label -> RememberedBean().apply { this.root = root; this.label = label } } }
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

    companion object {
        const val DEFAULT_AUTO_LOCK_MINUTES: Int = 30
        private const val MAX_AUTO_LOCK_MINUTES = 24 * 60

        fun getInstance(): VaultUserState = service()
    }
}
