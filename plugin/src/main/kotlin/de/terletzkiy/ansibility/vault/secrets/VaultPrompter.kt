package de.terletzkiy.ansibility.vault.secrets

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/** Why a consent is asked for. */
enum class ConsentReason {
    /** The first vault action in a session found sources nobody consented to yet (the D25 dialog). */
    FIRST_USE,

    /** `.env.local` names another password file than the one the first dialog showed. */
    NAMED_BY_ENV_LOCAL,

    /** A consented source changed (size or content) since the consent. */
    CHANGED,
}

/** One source a root's consent covers, described for the dialog (never its content). */
class ConsentItem(
    /** The vault id the source gives a secret for. */
    val label: String,
    val kind: Kind,
    /** The file (root-relative, `~/…` or absolute) or the environment variable name. */
    val display: String,
    /** For [Kind.ENV_LOCAL_KEY]: the password file `.env.local` is expected to name, or null when unknown. */
    val expected: String? = null,
    /** True when the user allowed this source before and it changed since (size or content). */
    val changed: Boolean = false,
) {
    enum class Kind {
        /** The `ANSIBLE_LOCAL_VAULT_PASSWORD_FILE` line of `.env.local`, then the file it names. */
        ENV_LOCAL_KEY,

        /** A password file. */
        PASSWORD_FILE,

        /** An environment variable of the IDE holding the password. */
        ENVIRONMENT,
    }

    override fun toString(): String = "ConsentItem($label, $kind, $display${if (changed) ", changed" else ""})"
}

/** One root of a consent request with the sources the consent covers. */
class ConsentRoot(
    /** The `settings.RootKeys` key of the root. */
    val rootKey: String,
    /** The root's display name (`falcon`, `pelican › danger_zone/database`). */
    val displayName: String,
    val items: List<ConsentItem>,
    /**
     * The password managers (display names) whose ids a [VaultConsentDecision.NotNow] for this root leaves unread in
     * this session too (D139): set for the root being unlocked, empty for the roots listed beside it.
     */
    val managers: List<String> = emptyList(),
) {
    override fun toString(): String = "ConsentRoot($rootKey, $items${if (managers.isEmpty()) "" else ", managers=$managers"})"
}

/** A consent request: the requested root first, then every other root with discovered sources (D25). */
class VaultConsentRequest(val reason: ConsentReason, val roots: List<ConsentRoot>) {
    override fun toString(): String = "VaultConsentRequest($reason, $roots)"
}

/** The answer to a [VaultConsentRequest]. */
sealed interface VaultConsentDecision {
    /** [Use for all listed]. */
    data object UseAll : VaultConsentDecision

    /** [Choose…]: only the roots in [rootKeys]. */
    data class Choose(val rootKeys: Set<String>) : VaultConsentDecision

    /**
     * [Not now] (or the dialog was closed): no listed file is read in this session, and the root being unlocked runs no
     * password manager either; its unlock falls back to PasswordSafe and the prompt. An explicit Unlock… asks again.
     */
    data object NotNow : VaultConsentDecision
}

/** How a typed password is remembered (D26). */
enum class RememberChoice {
    /** In the password safe (the macOS Keychain), until removed. */
    KEYCHAIN,

    /** In PasswordSafe's memory-only store until the IDE exits: a re-unlock after a lock does not ask again. */
    SESSION,

    /** Nowhere: the next unlock after a lock asks again; an entry remembered earlier is removed. */
    NONE,
}

/** A password prompt for one id of one root. */
class VaultPasswordRequest(
    val rootDisplayName: String,
    val label: String,
    /** Why the previous answer was refused (it did not decrypt the value acted on), or null on the first ask. */
    val error: String? = null,
    /** True when PasswordSafe keeps passwords in memory only (Settings › Passwords), so [RememberChoice.KEYCHAIN] is not persistent. */
    val passwordSafeMemoryOnly: Boolean = false,
) {
    override fun toString(): String = "VaultPasswordRequest($rootDisplayName, $label)"
}

/**
 * A typed password. Never a `data class`; [toString] is `***`. The characters are handed over once with [take]
 * (the caller zeroes them); [close] zeroes them if nobody took them.
 */
class VaultPasswordAnswer(password: CharArray, val remember: RememberChoice) : AutoCloseable {
    private var password: CharArray? = password

    /** Hands the characters over to the caller, who zeroes them. */
    @Synchronized
    fun take(): CharArray = checkNotNull(password) { "the password was taken" }.also { password = null }

    @Synchronized
    override fun close() {
        password?.fill('\u0000')
        password = null
    }

    override fun toString(): String = "VaultPasswordAnswer(***, $remember)"
}

/** A password manager's own unlock secret: Bitwarden's master password, or the password of KeePassXC database [target]. */
class MasterPasswordRequest(val managerName: String, val target: String, val retry: Boolean) {
    override fun toString(): String = "MasterPasswordRequest($managerName, $target, retry=$retry)"
}

/**
 * The dialogs of the unlock flow: the D25 consent dialog and the D26 password prompt. Called on the EDT. The
 * application service is [VaultDialogPrompter]; tests replace it.
 */
interface VaultPrompter {
    /** Asks whether the sources of [request] may be read. */
    fun askConsent(project: Project, request: VaultConsentRequest): VaultConsentDecision

    /** Asks for a password; null when cancelled. */
    fun askPassword(project: Project, request: VaultPasswordRequest): VaultPasswordAnswer?

    /** Asks for a password manager's master password, kept in memory until the vault locks; null when cancelled. */
    fun askMasterPassword(project: Project, request: MasterPasswordRequest): CharArray? = null

    companion object {
        fun getInstance(): VaultPrompter = service()
    }
}
