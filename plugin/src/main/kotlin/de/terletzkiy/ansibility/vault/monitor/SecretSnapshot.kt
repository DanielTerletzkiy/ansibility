package de.terletzkiy.ansibility.vault.monitor

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.Topic
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.semantics.diagnostics.Level
import de.terletzkiy.ansibility.vault.vcs.TrackedStatus

/** The kinds of the Vault tab (plan amendment R21, D165), in the order the tab lists them. */
enum class SecretCategory(
    val key: String,
    /** An ANS-V108 category: the plaintext key allowlist applies ("Exclude Path…"). */
    val plaintextKeyCheck: Boolean,
) {
    /** ANS-V107 (an envelope Ansible does not see), and whole-file vaults whose envelope is malformed (ANS-V101–V103). */
    BROKEN_VAULT_FILES("broken.vault.files", false),

    /** Inline `!vault` values whose envelope is malformed (ANS-V101–V103). */
    BROKEN_VAULT_VALUES("broken.vault.values", false),

    /** ANS-V108: private keys in plaintext (also an unprotected PKCS#12 key bag or an unencrypted DER key). */
    PLAINTEXT_KEYS("plaintext.keys", true),

    /** ANS-V108: a root's vault password file under version control (D161). */
    VAULT_PASSWORD_FILES("vault.password.files", true),

    /** ANS-V108, a weaker signal (D161): passphrase-protected keys and keystores. */
    PROTECTED_KEYS("protected.keys", true),

    /** ANS-V108: key-like names that are neither vaulted nor hold a key the signatures read. */
    KEY_LIKE_FILES("key.like.files", true),
}

/** What the tab and the notification may do about a finding. */
enum class SecretFix {
    /** "Convert to Whole-File Vault" (ANS-V107 with a wrapped envelope; no password). */
    CONVERT,

    /** "Encrypt Files…" (Ansibility Vault's Encrypt File, for files Ansible reads as they are). */
    ENCRYPT,
}

/**
 * The repository a finding is listed under: an Ansible root ([isRoot]), or the content root of a file outside every root
 * ("Other files in …"). [id] is its settings key (`settings.RootKeys`), stable across clones.
 */
data class SecretGroup(val id: String, val title: String, val dir: VirtualFile, val isRoot: Boolean)

/**
 * One file of one [SecretCategory] (plan amendment R21, D165): where it is, how bad it is, its VCS status and what was
 * found, in words that name kinds and line numbers only. Never any content of the file.
 */
data class SecretFinding(
    val file: VirtualFile,
    val group: SecretGroup,
    /** The path relative to [group]'s directory (`/`-separated). */
    val path: String,
    val category: SecretCategory,
    /** The diagnostic code whose severity [level] is (the most severe one of the file in this category). */
    val code: DiagnosticCode,
    /** ERROR or WARNING (or a weaker level a preset gives); never OFF. */
    val level: Level,
    val status: TrackedStatus,
    /** What was found: bundle texts with kinds and line numbers. */
    val details: List<String>,
    /** The 0-based line to open the file at, or -1. */
    val line: Int,
    /** The offset to open the file at (malformed values), or -1. */
    val offset: Int,
    val fix: SecretFix?,
) {
    /**
     * The identity notifications remember (D166): the repository, the path below it and the category; not the line,
     * so an edit above a key does not make it new.
     */
    val key: String get() = "${group.id}|$path|${category.name}"

    val isError: Boolean get() = level == Level.ERROR

    override fun toString(): String = "SecretFinding($key, $code, $level, $status)"
}

/**
 * Every vault and secret finding of the workspace at one time (plan amendment R21, D165), grouped by repository and
 * category in the order the tab shows them. [rootCount] is the number of (non-detached) Ansible roots; [computed] is
 * false until the first snapshot was built; [statusesKnown] is false while the VCS has not reported the file statuses
 * yet (`TrackedStatuses.whenReady`): the ANS-V108 findings wait for them, so a git-ignored key is never listed as
 * committed.
 */
class SecretSnapshot(val findings: List<SecretFinding>, val rootCount: Int, val computed: Boolean, val statusesKnown: Boolean = true) {
    /** The repositories with findings, in order. */
    val groups: List<SecretGroup> get() = findings.map { it.group }.distinct()

    val errorCount: Int get() = findings.count { it.isError }

    val warningCount: Int get() = findings.size - errorCount

    /** True while an ERROR finding exists (the tool window's red badge). */
    val hasErrors: Boolean get() = findings.any { it.isError }

    /** The findings of [group], in order. */
    fun findingsOf(group: SecretGroup): List<SecretFinding> = findings.filter { it.group == group }

    override fun equals(other: Any?): Boolean =
        other is SecretSnapshot && other.findings == findings && other.rootCount == rootCount && other.computed == computed &&
            other.statusesKnown == statusesKnown

    override fun hashCode(): Int = findings.hashCode() * 31 + rootCount

    override fun toString(): String = "SecretSnapshot(${findings.size} findings, $rootCount roots${if (computed) "" else ", not computed"})"

    companion object {
        /** Before the first build. */
        val NOT_COMPUTED = SecretSnapshot(emptyList(), 0, computed = false)
    }
}

/**
 * Published on the project bus by [SecretHealthService] after each new snapshot (plan amendment R21, D165), on a
 * background thread: listeners hop to the EDT themselves.
 */
fun interface SecretHealthListener {
    fun snapshotChanged(old: SecretSnapshot, new: SecretSnapshot)

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<SecretHealthListener> = Topic(SecretHealthListener::class.java, Topic.BroadcastDirection.NONE)
    }
}
