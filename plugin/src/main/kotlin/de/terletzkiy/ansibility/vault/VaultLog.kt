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
    enum class Operation { DISCOVER, CONSENT, LOAD_SOURCE, PASSWORD_SAFE, PROMPT, UNLOCK, LOCK, DECRYPT, ENCRYPT, STATUS, REVEAL, GUARD, COMMIT_CHECK }

    /** Normal events: those in [NOTABLE] at INFO, the others at debug level. */
    enum class Event {
        CONSENT_ASKED, CONSENT_GRANTED, CONSENT_DECLINED, CONSENT_CHANGED,
        SOURCE_LOADED, SOURCE_SKIPPED, SCRIPT_NOT_RUN,
        PASSWORD_PROMPTED, PASSWORD_REMEMBERED, PASSWORD_FORGOTTEN,
        UNLOCKED, LOCKED, IDLE_LOCK, PROJECT_CLOSE_LOCK,
        DECRYPTED, ENCRYPTED, NO_PRESENTER,

        /** A password manager's own master password (Bitwarden, a KeePassXC database) was asked for. */
        MASTER_PASSWORD_PROMPTED,

        /**
         * A password manager read started ([managerRead] logs its end), for a vault id (its label is the key), a
         * become password or the settings page's Test.
         */
        MANAGER_READ_STARTED,

        /** A password manager was not read because its root's own unlock was answered "Not now" in this session. */
        MANAGER_READ_SKIPPED,

        /**
         * The VCS had not reported the file statuses a minute after the Vault tab's monitoring started (plan amendment
         * R21): plaintext key findings and notifications keep waiting for them.
         */
        STATUSES_LATE,
    }

    /** How a password manager read ended. */
    enum class ManagerReadOutcome {
        /** The manager returned a value. */
        OK,

        /** The CLI is missing, refused, timed out or returned nothing usable. */
        NO_VALUE,

        /** The caller was cancelled (Stop, Cancel) and the CLI was ended. */
        CANCELLED,

        /** The read threw. */
        FAILED,
    }

    /**
     * The events logged at INFO (plan amendment R19, D139): every dialog vault code shows and every password manager
     * read, so a report of an unexpected prompt can be traced in idea.log. They carry names and labels only.
     */
    private val NOTABLE = setOf(
        Event.CONSENT_ASKED, Event.PASSWORD_PROMPTED, Event.MASTER_PASSWORD_PROMPTED, Event.MANAGER_READ_STARTED, Event.MANAGER_READ_SKIPPED,
        Event.STATUSES_LATE,
    )

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

    /** A normal event: at INFO when it is [NOTABLE], else at debug level. */
    fun event(operation: Operation, event: Event, file: String? = null, key: String? = null) {
        if (event in NOTABLE) LOG.info(line(operation, event.name, file, key))
        else if (LOG.isDebugEnabled) LOG.debug(line(operation, event.name, file, key))
    }

    /** The end of a password manager read of id [label] (INFO): the manager's display name, the time it took and how it ended. */
    fun managerRead(manager: String, label: String?, millis: Long, outcome: ManagerReadOutcome) =
        LOG.info(line(Operation.LOAD_SOURCE, "MANAGER_READ_ENDED", manager, label) + " ms=" + millis + " outcome=" + outcome.name)

    private fun line(operation: Operation, what: String, file: String?, key: String?): String = buildString {
        append("vault ").append(operation.name.lowercase()).append(": ").append(what)
        if (file != null) append(" file=").append(file)
        if (key != null) append(" key=").append(key)
    }
}
