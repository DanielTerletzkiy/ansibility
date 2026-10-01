package de.terletzkiy.ansibility.vault.crypto

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import de.terletzkiy.ansibility.api.VaultPlaintext
import de.terletzkiy.ansibility.semantics.vault.DecryptOutcome
import de.terletzkiy.ansibility.semantics.vault.LabelledSecret
import de.terletzkiy.ansibility.semantics.vault.SecretBytes
import de.terletzkiy.ansibility.semantics.vault.VaultAes256
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.semantics.vault.VaultMatcher
import org.jetbrains.annotations.TestOnly
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * What an explicit action (or the manager's Test, or the D31 analysis) learnt about one envelope of one root: which
 * id's secret decrypted it ([decryptsWith], null when every unlocked candidate failed: ANS-V104), the ids tried, and
 * whether the decrypting id carries the envelope's own label ([labelMatches], false for a mislabelled value: ANS-V105).
 * Not secret.
 */
class Verification(val decryptsWith: String?, val tried: List<String>, val labelMatches: Boolean) {
    override fun toString(): String = "Verification($decryptsWith, tried=$tried, labelMatches=$labelMatches)"
}

/**
 * Plaintext owned by the caller of a vault operation ([VaultPlaintext]): never a `data class`, [toString] is `***`,
 * the bytes are reachable only inside [read], and [close] zeroes them.
 */
internal class OwnedPlaintext(bytes: ByteArray) : VaultPlaintext {
    private var bytes: ByteArray? = bytes

    override val size: Int @Synchronized get() = bytes?.size ?: 0

    @Synchronized
    override fun <T> read(block: (ByteArray) -> T): T = block(checkNotNull(bytes) { "the plaintext was closed" })

    @Synchronized
    override fun close() {
        bytes?.fill(0)
        bytes = null
    }

    override fun toString(): String = "***"
}

/**
 * The vault cipher work and its two in-memory caches (plan amendment R7/R8, A.13 `VaultCrypto`, F7.13 rule 3):
 *
 * - the **verification cache**: (root, envelope fingerprint) → [Verification]. Passive paths (status, card, banner)
 *   read it and never decrypt; only explicit actions fill it. It is cleared on every lock; negative entries also on
 *   every unlock, because a newly unlocked id may decrypt what failed before;
 * - the **plaintext cache**: (root, envelope fingerprint, decrypting id) → plaintext bytes, at most [MAX_PLAINTEXTS]
 *   entries, each dropped (and zeroed) after [PLAINTEXT_IDLE_MILLIS] without use, on lock, on a root change and on
 *   project close. Callers always get a copy they own ([OwnedPlaintext]).
 *
 * The fingerprint is the SHA-256 of the envelope as ansible-vault formats it ([VaultEnvelope.formatBytes]): the
 * header is part of it, because the label decides which secrets `vault_id_match` lets Ansible try. Nothing here is
 * ever persisted, indexed or logged. The cipher calls are CPU work: callers run them on `Dispatchers.Default`.
 */
@Service(Service.Level.PROJECT)
class VaultCrypto : Disposable {
    private class Key(val root: String, val fingerprint: String) {
        override fun equals(other: Any?): Boolean = other is Key && root == other.root && fingerprint == other.fingerprint
        override fun hashCode(): Int = 31 * root.hashCode() + fingerprint.hashCode()
    }

    private class CachedPlaintext(val bytes: ByteArray, @Volatile var lastUse: Long) {
        override fun toString(): String = "***"
    }

    private val verifications = ConcurrentHashMap<Key, Verification>()
    private val plaintexts = object : LinkedHashMap<String, CachedPlaintext>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedPlaintext>): Boolean =
            (size > MAX_PLAINTEXTS).also { if (it) eldest.value.bytes.fill(0) }
    }
    private val decrypts = AtomicInteger()

    /** The number of secrets tried on envelopes so far: tests assert it stays put on passive paths. */
    val decryptAttempts: Int get() = decrypts.get()

    /** The cache key of [envelope]: hex SHA-256 of its formatted bytes. */
    fun fingerprint(envelope: VaultEnvelope): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(envelope.formatBytes())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** What is known about [fingerprint] in [rootKey], or null. Safe on passive paths. */
    fun verification(rootKey: String, fingerprint: String): Verification? = verifications[Key(rootKey, fingerprint)]

    /** Records [verification]; true when the cache changed. */
    fun record(rootKey: String, fingerprint: String, verification: Verification): Boolean {
        val old = verifications.put(Key(rootKey, fingerprint), verification)
        return old == null || old.decryptsWith != verification.decryptsWith || old.labelMatches != verification.labelMatches
    }

    /**
     * Ansible's matching over [secrets] (in the order of the root's chain): see [VaultMatcher.decrypt]. Tries the
     * secrets in Ansible's order, so the reported id is the one `ansible-playbook` would use.
     */
    fun decrypt(envelope: VaultEnvelope, secrets: List<LabelledSecret>, idMatch: Boolean, defaultIdentity: String): DecryptOutcome {
        val candidates = VaultMatcher.candidates(envelope, secrets.map { it.label }, idMatch, defaultIdentity)
        decrypts.addAndGet(candidates.size)
        return VaultMatcher.decrypt(envelope, secrets, idMatch, defaultIdentity)
    }

    /** Encrypts [plaintext] with [secret] under [label] (`default` writes 1.1); [salt] null picks a random one. */
    fun encrypt(plaintext: ByteArray, secret: SecretBytes, label: String, salt: ByteArray?): VaultEnvelope =
        if (salt == null) VaultAes256.encrypt(plaintext, secret, label) else VaultAes256.encrypt(plaintext, secret, label, salt)

    /** A copy of the cached plaintext of [fingerprint] as decrypted by [label] in [rootKey], refreshing its idle time. */
    fun cachedPlaintext(rootKey: String, fingerprint: String, label: String, now: Long): VaultPlaintext? = synchronized(plaintexts) {
        val entry = plaintexts[plaintextKey(rootKey, fingerprint, label)] ?: return null
        entry.lastUse = now
        OwnedPlaintext(entry.bytes.copyOf())
    }

    /** Caches a copy of [plaintext]; the caller keeps ownership of its array. */
    fun cachePlaintext(rootKey: String, fingerprint: String, label: String, plaintext: ByteArray, now: Long) {
        synchronized(plaintexts) {
            plaintexts.put(plaintextKey(rootKey, fingerprint, label), CachedPlaintext(plaintext.copyOf(), now))?.bytes?.fill(0)
        }
    }

    /** Zeroes and drops the plaintexts unused for [PLAINTEXT_IDLE_MILLIS]; true when something was dropped. */
    fun dropIdlePlaintexts(now: Long): Boolean = synchronized(plaintexts) {
        val idle = plaintexts.entries.filter { now - it.value.lastUse >= PLAINTEXT_IDLE_MILLIS }
        for (entry in idle) {
            entry.value.bytes.fill(0)
            plaintexts.remove(entry.key)
        }
        idle.isNotEmpty()
    }

    /** Zeroes and drops every cached plaintext (root change, lock, project close). */
    fun clearPlaintexts() {
        synchronized(plaintexts) {
            plaintexts.values.forEach { it.bytes.fill(0) }
            plaintexts.clear()
        }
    }

    /** Drops the entries saying that no unlocked id decrypts an envelope (after an unlock). */
    fun clearNegativeVerifications(): Boolean = verifications.values.removeIf { it.decryptsWith == null }

    /** Clears both caches (every lock). */
    fun clearAll() {
        verifications.clear()
        clearPlaintexts()
    }

    /** True when neither cache holds anything. */
    val isEmpty: Boolean get() = verifications.isEmpty() && synchronized(plaintexts) { plaintexts.isEmpty() }

    /** The number of cached plaintexts. */
    @get:TestOnly
    val plaintextCount: Int get() = synchronized(plaintexts) { plaintexts.size }

    /** The number of verification entries. */
    @get:TestOnly
    val verificationCount: Int get() = verifications.size

    /** Project close: the plaintexts are zeroed, nothing outlives the project. */
    override fun dispose() = clearAll()

    private fun plaintextKey(rootKey: String, fingerprint: String, label: String) = "$rootKey\u0000$fingerprint\u0000$label"

    companion object {
        /** A cached plaintext expires after 5 minutes without use (secret rule 2). */
        const val PLAINTEXT_IDLE_MILLIS: Long = 5 * 60 * 1000L

        /** The most plaintexts cached at once. */
        const val MAX_PLAINTEXTS: Int = 64

        fun getInstance(project: Project): VaultCrypto = project.service()
    }
}
