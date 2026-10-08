package de.terletzkiy.ansibility.vault.monitor

import com.intellij.openapi.util.text.StringUtil
import de.terletzkiy.ansibility.index.secrets.SecretVerdict
import de.terletzkiy.ansibility.inspections.vault.PlaintextKeyChecks
import de.terletzkiy.ansibility.semantics.secrets.KeyFormat
import de.terletzkiy.ansibility.semantics.secrets.KeyProtection
import de.terletzkiy.ansibility.semantics.vault.VaultShapeKind
import de.terletzkiy.ansibility.vault.ui.AnsibilityVaultUiBundle.message
import de.terletzkiy.ansibility.vault.vcs.TrackedStatus
import org.jetbrains.annotations.Nls

/**
 * The words of the Vault tab and its notifications (plan amendment R21, D165/D166): kinds, line numbers, file names,
 * counts and VCS statuses; never a byte of a file.
 */
object SecretTexts {
    /** File names a notification lists at most before "and N more". */
    const val MAX_NAMES: Int = 3

    /** Formats that name a container, so an unprotected key in it is said so ("PKCS#12 keystore, unprotected key"). */
    private val KEYSTORES = setOf(KeyFormat.PKCS12, KeyFormat.JAVA_KEYSTORE)

    /** The name of [category] ("Plaintext private keys"). */
    @Nls
    fun category(category: SecretCategory): String = message("monitor.category.${category.key}")

    /** "1 plaintext private key", "2 broken vault files". */
    @Nls
    fun count(category: SecretCategory, count: Int): String = message("monitor.count.${category.key}", count)

    /** An ANS-V107 shape and its 0-based [line] ("a !vault line before the envelope, line 1"). */
    @Nls
    fun shape(shape: VaultShapeKind, line: Int): String {
        val text = message("monitor.detail.shape." + shape.name.lowercase())
        return if (line >= 0) message("monitor.detail.line", text, line + 1) else text
    }

    /**
     * A key's format and protection ("PKCS#8, line 3", "RSA, PKCS#1, passphrase-protected, line 1", "PKCS#12 keystore,
     * unprotected key", "Java keystore, may hold a key").
     */
    @Nls
    fun key(verdict: SecretVerdict): String {
        val format = verdict.format?.let(PlaintextKeyChecks::formatText) ?: message("monitor.detail.key.unknown")
        val text = when (verdict.protection) {
            KeyProtection.PASSPHRASE -> message("monitor.detail.key.protected", format)
            KeyProtection.UNKNOWN -> message("monitor.detail.key.unreadable", format)
            KeyProtection.NONE, null ->
                if (verdict.format in KEYSTORES) message("monitor.detail.key.unprotected", format) else format
        }
        return if (verdict.line >= 0) message("monitor.detail.line", text, verdict.line + 1) else text
    }

    /** The VCS status as the tab shows it, or null when it says nothing ([TrackedStatus.NO_VCS]). */
    @Nls
    fun status(status: TrackedStatus): String? = when (status) {
        TrackedStatus.TRACKED -> message("monitor.status.tracked")
        TrackedStatus.ADDED -> message("monitor.status.added")
        TrackedStatus.UNTRACKED -> message("monitor.status.untracked")
        TrackedStatus.IGNORED -> message("monitor.status.ignored")
        TrackedStatus.NO_VCS -> null
    }

    /** "1 error, 2 warnings" (either part only when it is not 0). */
    @Nls
    fun counts(errors: Int, warnings: Int): String = listOfNotNull(
        message("monitor.count.errors", errors).takeIf { errors > 0 },
        message("monitor.count.warnings", warnings).takeIf { warnings > 0 },
    ).joinToString(", ")

    /** The first [MAX_NAMES] [names], then "and N more". */
    @Nls
    fun names(names: List<String>): String {
        val shown = names.take(MAX_NAMES).joinToString(", ")
        return if (names.size > MAX_NAMES) message("monitor.names.more", shown, names.size - MAX_NAMES) else shown
    }

    /**
     * One line of a notification: "falcon: 1 broken vault file (web.key), 2 plaintext private keys (db.key, web.pem)".
     * HTML-escaped when [html].
     */
    @Nls
    fun notificationLine(group: SecretGroup, findings: List<SecretFinding>, html: Boolean): String {
        val parts = findings.groupBy { it.category }.toSortedMap().map { (category, ofCategory) ->
            message("monitor.notification.part", count(category, ofCategory.size), names(ofCategory.map { it.file.name }.distinct()))
        }
        val line = message("monitor.notification.line", group.title, parts.joinToString(", "))
        return if (html) StringUtil.escapeXmlEntities(line) else line
    }
}
