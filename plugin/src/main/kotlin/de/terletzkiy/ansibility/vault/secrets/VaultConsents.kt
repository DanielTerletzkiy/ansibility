package de.terletzkiy.ansibility.vault.secrets

import de.terletzkiy.ansibility.vault.VaultLog
import de.terletzkiy.ansibility.vault.identity.ConsentTarget
import de.terletzkiy.ansibility.vault.identity.VaultSourceAccess
import java.io.IOException

/**
 * The consent rules of D25 over [VaultUserState]: a source may be read only while a consent for its locator exists
 * and its size and content still match the consent's. Nothing is read to answer [needsConsent]; [check] reads a
 * source only when a consent for it exists and the size matches (to compare the fingerprint, and then hands the bytes
 * on, so the source is read once per unlock).
 */
internal class VaultConsents(private val access: VaultSourceAccess, private val state: VaultUserState) {
    /** The outcome of [check]. */
    sealed interface Check {
        /** Consented and unchanged: the caller owns and zeroes [content]. */
        class Granted(val content: ByteArray) : Check {
            override fun toString(): String = "Granted(***)"
        }

        /** No consent, or [changed] since the consent. Nothing of the content is kept. */
        data class Needed(val changed: Boolean) : Check

        /** The source does not exist or cannot be read. */
        data object Unavailable : Check
    }

    /** Whether reading [target] needs a consent first: no consent, or the size changed. Reads nothing. */
    fun needsConsent(target: ConsentTarget, environment: Map<String, String>): Boolean {
        val size = sizeOf(target, environment) ?: return false
        val record = state.consent(target.locator) ?: return true
        return record.size != size
    }

    /** Whether a consent for [target] was given before (it may have changed since). */
    fun hasConsent(target: ConsentTarget): Boolean = state.consent(target.locator) != null

    /** Reads [target] if the consent for it still holds. */
    fun check(target: ConsentTarget, environment: Map<String, String>): Check {
        val size = sizeOf(target, environment) ?: return Check.Unavailable
        val record = state.consent(target.locator) ?: return Check.Needed(changed = false)
        if (record.size != size) return Check.Needed(changed = true)
        val content = read(target, environment) ?: return Check.Unavailable
        if (!ConsentFingerprint.matches(content, record.fingerprint)) {
            content.fill(0)
            return Check.Needed(changed = true)
        }
        return Check.Granted(content)
    }

    /** Records the consent the user just gave for [target] and returns its content (the caller zeroes it), or null when it cannot be read. */
    fun grant(target: ConsentTarget, environment: Map<String, String>): ByteArray? {
        val content = read(target, environment) ?: return null
        state.grant(ConsentRecord(target.locator, content.size.toLong(), ConsentFingerprint.of(content)))
        VaultLog.event(VaultLog.Operation.CONSENT, VaultLog.Event.CONSENT_GRANTED, target.display)
        return content
    }

    private fun sizeOf(target: ConsentTarget, environment: Map<String, String>): Long? {
        target.environmentVariable?.let { name -> return environment[name]?.toByteArray(Charsets.UTF_8)?.let { bytes -> bytes.fill(0); bytes.size.toLong() } }
        val path = target.path ?: return null
        if (!access.isRegularFile(path)) return null
        return access.size(path)
    }

    private fun read(target: ConsentTarget, environment: Map<String, String>): ByteArray? {
        target.environmentVariable?.let { name -> return environment[name]?.toByteArray(Charsets.UTF_8) }
        val path = target.path ?: return null
        return try {
            access.readSecret(path, VaultSourceAccess.SECRET_LIMIT)
        } catch (e: IOException) {
            VaultLog.failure(VaultLog.Operation.LOAD_SOURCE, e, target.display)
            null
        }
    }
}
