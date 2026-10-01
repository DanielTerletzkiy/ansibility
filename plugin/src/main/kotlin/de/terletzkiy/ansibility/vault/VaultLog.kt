package de.terletzkiy.ansibility.vault

import com.intellij.openapi.diagnostic.Logger
import de.terletzkiy.ansibility.api.VaultFailure
import de.terletzkiy.ansibility.semantics.vault.FormatReason
import de.terletzkiy.ansibility.semantics.vault.SecretLoadFailure

/**
 * The one logger of the vault area (DEV.md rule 11). Every line carries an [Operation], an error class or an [Event]
 * constant, and optionally a file and a YAML key; the signatures accept nothing else, so no password, plaintext,
 * ciphertext body, password file content or `.env.local` line can reach the log.
 *
 * Throwables are logged by class name only: the message of an I/O or crypto exception is never written, and the
 * throwable itself is never handed to the platform logger (its message and cause chain would be).
 */
internal object VaultLog {
    /** The category of every vault log line: `#de.terletzkiy.ansibility.vault`. */
    const val CATEGORY: String = "#de.terletzkiy.ansibility.vault"

    private val LOG: Logger get() = Logger.getInstance(CATEGORY)

    /** What the vault code was doing. */
    enum class Operation { DISCOVER, CONSENT, LOAD_SOURCE, PASSWORD_SAFE, PROMPT, UNLOCK, LOCK, DECRYPT, ENCRYPT, STATUS, REVEAL, GUARD }

    /** Normal events, logged at debug level. */
    enum class Event {
        CONSENT_ASKED, CONSENT_GRANTED, CONSENT_DECLINED, CONSENT_CHANGED,
        SOURCE_LOADED, SOURCE_SKIPPED, SCRIPT_NOT_RUN,
        PASSWORD_PROMPTED, PASSWORD_REMEMBERED, PASSWORD_FORGOTTEN,
        UNLOCKED, LOCKED, IDLE_LOCK, PROJECT_CLOSE_LOCK,
        DECRYPTED, ENCRYPTED, NO_PRESENTER,
    }

    /** A vault operation failed for a reason Ansible would report too (wrong secret, malformed envelope, …). */
    fun failure(operation: Operation, failure: VaultFailure, file: String? = null, key: String? = null) =
        LOG.info(line(operation, failure.name, file, key))

    /** A password source gave no secret. */
    fun failure(operation: Operation, failure: SecretLoadFailure, file: String? = null, key: String? = null) =
        LOG.info(line(operation, failure.name, file, key))

    /** A malformed envelope. */
    fun failure(operation: Operation, reason: FormatReason, file: String? = null, key: String? = null) =
        LOG.info(line(operation, reason.name, file, key))

    /** An unexpected exception: logged as a warning by class name only. */
    fun failure(operation: Operation, error: Throwable, file: String? = null, key: String? = null) =
        LOG.warn(line(operation, error.javaClass.name, file, key))

    /** The corpus guard refused to touch [file] ([VaultCorpusGuard]). */
    fun refused(file: String) = LOG.warn(line(Operation.GUARD, "CORPUS_GUARD", file, null))

    /** A normal event (debug level). */
    fun event(operation: Operation, event: Event, file: String? = null, key: String? = null) {
        if (LOG.isDebugEnabled) LOG.debug(line(operation, event.name, file, key))
    }

    private fun line(operation: Operation, what: String, file: String?, key: String?): String = buildString {
        append("vault ").append(operation.name.lowercase()).append(": ").append(what)
        if (file != null) append(" file=").append(file)
        if (key != null) append(" key=").append(key)
    }
}
