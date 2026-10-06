package de.terletzkiy.ansibility.api

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.util.messages.Topic

// Ansible Vault contracts (plan amendment R7/R8, A.13; implemented in the `vault` area, `ansibility-vault-core.xml`).
//
// Everything in this file except [VaultPlaintext] is free of secrets: headers, labels, source descriptions, lock
// states and lengths. The secret-handling rules of the new D13 apply to every implementation and every caller:
// - nothing is decrypted on a passive path (highlighting, folding, completion, documentation): those read
//   [VaultStatusService], which only reports header facts and the verification cache;
// - plaintext lives in memory only; it never reaches an index, a log, an exception message, persistent state,
//   documentation HTML, a folding placeholder, an inlay, an intention preview or a completion item;
// - vault code logs only file, key and error class ([VaultFailure]), never data.

/**
 * The header of a vault envelope: `$ANSIBLE_VAULT;1.1;AES256` or `$ANSIBLE_VAULT;1.2;AES256;<label>`. Not secret, and
 * not authenticated: the label is only a hint about which secret encrypted the body.
 */
data class VaultHeaderInfo(
    /** `1.1` or `1.2`, as written. */
    val version: String,
    /** The cipher name as written; ansible-vault knows only `AES256` (case-sensitive). */
    val cipher: String,
    /** The raw label: the fourth header field whenever there is one (also on a `1.1` header), else null. */
    val label: String?,
) {
    /**
     * The id Ansible matches the envelope against: the label, or [defaultIdentity] (`vault_identity` of the root's
     * configuration, `default` unless renamed) for an unlabelled envelope.
     */
    fun labelOrDefault(defaultIdentity: String = DEFAULT_IDENTITY): String = label ?: defaultIdentity

    companion object {
        /** The magic that starts every envelope (and every whole-file vault's first bytes). */
        const val MAGIC: String = "\$ANSIBLE_VAULT"

        /** Ansible's default vault id label (`vault_identity`). */
        const val DEFAULT_IDENTITY: String = "default"
    }
}

/** Where an envelope sits. */
enum class VaultEnvelopeKind {
    /** A `!vault` YAML scalar: an inline value. */
    INLINE,

    /** A whole file whose first bytes are `$ANSIBLE_VAULT` (keys, YAML, binary). */
    FILE,
}

/** Non-secret facts about one envelope, as far as they can be read without a secret (the `ansible.vault` index's view). */
data class VaultEnvelopeInfo(
    val kind: VaultEnvelopeKind,
    /** The `!vault` value (inline), or offset 0 of the file (whole-file vault). */
    val location: SourceLocation,
    val header: VaultHeaderInfo,
    /** The ciphertext length in bytes when the body parses, else null (a malformed envelope, ANS-V101–V103). */
    val ciphertextLength: Int?,
    /** For inline values: the YAML key that holds the value. */
    val keyName: String?,
) {
    /** The plaintext lengths PKCS7 padding allows (`[ct - 16, ct - 1]`), the card's "plaintext 16–31 bytes". */
    val plaintextLength: IntRange? get() = plaintextLengthOf(ciphertextLength)

    companion object {
        private const val BLOCK = 16

        /** `[ct - 16, ct - 1]` for a whole number of AES blocks, else null. */
        fun plaintextLengthOf(ciphertextLength: Int?): IntRange? {
            val length = ciphertextLength ?: return null
            if (length < BLOCK || length % BLOCK != 0) return null
            return (length - BLOCK)..(length - 1)
        }
    }
}

/** Whether a secret can be used without asking. */
enum class VaultLockState {
    /** The secret is in memory: actions (and the opted-in analysis, D31) decrypt without asking. */
    UNLOCKED,

    /** A source is configured or discovered, but the secret is not loaded (never used, idle lock, Lock all, project close). */
    LOCKED,

    /** No id of the root can be tried: none is configured or discovered, or `vault_id_match` excludes all of them. */
    NO_IDENTITY,
}

/** The kind of a secret source (F7.9). */
enum class VaultSourceKind {
    /** A password file (Ansible's strip rules; itself possibly vaulted). */
    PASSWORD_FILE,

    /** An executable script, run without arguments. */
    SCRIPT,

    /** A `*-client` script, run with `--vault-id <label>` (exit code 2: unknown id). */
    CLIENT_SCRIPT,

    /** A PasswordSafe entry. */
    PASSWORD_SAFE,

    /** A prompt, for this session or remembered (D26). */
    PROMPT,

    /** The environment variable `ANSIBLE_LOCAL_VAULT_PASSWORD` of the IDE process. */
    ENVIRONMENT,

    /** A 1Password secret reference (`op://vault/item/field`), read with the 1Password CLI (`op read`). */
    ONE_PASSWORD,

    /** A Bitwarden item (name or id), read with the Bitwarden CLI (`bw get password`, or the `bwbio` Touch ID wrapper). */
    BITWARDEN,

    /** A KeePassXC entry (`database.kdbx#group/entry`), read with `keepassxc-cli show`. */
    KEEPASSXC,

    /** A Proton Pass secret reference (`pass://vault/item/field`), read with the Proton Pass CLI (`pass-cli item view`). */
    PROTON_PASS,
}

/** How a source was found, in the order of the discovery chain (F7.9). */
enum class VaultSourceOrigin {
    /** Explicit ids from the project settings. */
    SETTINGS,

    /** `vault_identity_list` / `vault_password_file` of `ansible.cfg`. */
    ANSIBLE_CFG,

    /** `ANSIBLE_VAULT_IDENTITY_LIST` / `ANSIBLE_VAULT_PASSWORD_FILE` / `ANSIBLE_LOCAL_VAULT_PASSWORD` of the IDE environment. */
    ENVIRONMENT,

    /** The `ANSIBLE_LOCAL_VAULT_PASSWORD_FILE` line of `<root>/.env.local`. */
    ENV_LOCAL,

    /** The default of that key in `<root>/.env.local.skel`, when `.env.local` is missing. */
    ENV_LOCAL_SKEL,

    /** A conventional file name found by existence (`.vault-pass`, `.vault_pass`, `.vault-password`). */
    CONVENTIONAL_NAME,

    /** A PasswordSafe entry of an earlier unlock. */
    PASSWORD_SAFE,

    /** A prompt on first use. */
    PROMPT,
}

/** Where an identity's secret comes from, described without the secret. */
data class VaultSecretSource(
    val kind: VaultSourceKind,
    /**
     * The password file or script as configured (root-relative when inside the root), the PasswordSafe service name,
     * or the environment variable name; null for a prompt. Never the content of anything.
     */
    val location: String?,
    val origin: VaultSourceOrigin,
)

/** One vault id of a root, in the order Ansible tries the secrets. */
data class VaultIdentity(
    /** The id label (`default`, `dev`, `prod`); `vault_identity` renames `default`. */
    val label: String,
    val source: VaultSecretSource,
    /** [VaultLockState.UNLOCKED] or [VaultLockState.LOCKED]. */
    val lockState: VaultLockState,
    /** For a nested playbook root: the `settings.RootKeys` key of the parent root whose ids it inherits, else null. */
    val inheritedFrom: String? = null,
)

/** The vault configuration of one root as Ansible reads it, without secrets (the manager's header line, F7.9). */
data class VaultRootConfig(
    /** The ids in the order of the discovery chain (F7.9), which follows Ansible's secret order; Ansible tries them in this order. */
    val identities: List<VaultIdentity>,
    /** `vault_identity`: the label of unlabelled (`1.1`) envelopes, `default` unless renamed. */
    val defaultIdentity: String,
    /** `vault_id_match` as written (`ansible.cfg` or the environment), or null when unset. */
    val idMatchRaw: String?,
    /** `vault_encrypt_identity`, or null. */
    val encryptIdentity: String?,
) {
    /** Ansible's rule: any non-empty `vault_id_match` turns strict matching on, `false` included. */
    val idMatch: Boolean get() = !idMatchRaw.isNullOrEmpty()

    /** True when some id of the root is unlocked. */
    val anyUnlocked: Boolean get() = identities.any { it.lockState == VaultLockState.UNLOCKED }
}

/** What the card, the banner, the status bar and the tool window may say about one envelope (F7.14). */
data class VaultStatus(
    val header: VaultHeaderInfo,
    val kind: VaultEnvelopeKind,
    /** The configured id Ansible tries for the envelope's label first, or null when the root has none. */
    val identity: VaultIdentity?,
    /** The lock state of the ids that may decrypt the envelope: UNLOCKED when at least one candidate is unlocked. */
    val lockState: VaultLockState,
    /** The id whose secret actually decrypted this exact envelope, once an explicit action, the manager's Test or the D31 analysis verified it. */
    val decryptsWith: String?,
    /** True when the verification cache has an entry: [decryptsWith] null then means no unlocked id decrypts it (ANS-V104). */
    val verified: Boolean,
    /** The plaintext lengths the ciphertext allows, or null for a malformed body. */
    val plaintextLength: IntRange?,
)

/**
 * Read-only, non-secret vault state (implemented in `vault`): header facts, ids and their lock state, and the
 * verification cache. Never decrypts, never runs a script, never reads a password file or `.env.local` (before
 * consent, discovery only checks that files exist).
 *
 * Call in a read action; safe on highlighting, folding, completion and documentation paths.
 */
interface VaultStatusService {
    /** The status of the envelope at [element]: a `!vault` scalar or its key, or a whole-file vault's PSI file; null for anything else. */
    fun status(element: PsiElement): VaultStatus?

    /**
     * The status of the envelope at [location]: the key or the value of an inline `!vault` value (as in [VarSourceRef]
     * and [VarDefinition]), or any offset of a whole-file vault; null when there is no envelope there.
     */
    fun status(location: SourceLocation): VaultStatus?

    /** The vault configuration and ids of [root]; a nested playbook root reports its parent's ids. */
    fun config(root: AnsibleRoot): VaultRootConfig

    /** Bumped on every unlock and lock (manual, idle, project close) and whenever the verification cache changes. */
    val lockTracker: ModificationTracker

    companion object {
        fun getInstance(project: Project): VaultStatusService = project.service()
    }
}

/** Notified on the project message bus after an id was unlocked or locked, or the verification cache changed. */
fun interface VaultStatusListener {
    /** Called on any thread; implementations only schedule their own refresh (banners, the widget, tree rows). */
    fun vaultStatusChanged()

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<VaultStatusListener> = Topic(VaultStatusListener::class.java, Topic.BroadcastDirection.NONE)
    }
}

/**
 * Decrypted bytes owned by the caller. Never a `data class`; `toString()` returns `***`. The bytes are valid only
 * inside [read] and are zeroed by [close], which callers must call as soon as the plaintext is no longer shown or
 * written (`use { }`).
 */
interface VaultPlaintext : AutoCloseable {
    /** The plaintext length in bytes. */
    val size: Int

    /**
     * Runs [block] on the plaintext. The array must not be kept, and it may be turned into a `String` only by a
     * surface that shows or writes the value on explicit request (Reveal, Edit, the decrypted tab, Decrypt to plain, the
     * preview's "Render vault values").
     *
     * @throws IllegalStateException after [close]
     */
    fun <T> read(block: (ByteArray) -> T): T

    /** Zeroes the plaintext; idempotent. */
    override fun close()
}

/** Why a vault operation needs a secret; implementations refuse purposes the settings do not allow. */
enum class VaultPurpose {
    REVEAL, COPY, EDIT, REKEY, CHANGE_ID, OPEN_DECRYPTED, DECRYPT_TO_PLAIN,

    /** The manager's Test run over a root. */
    TEST,

    /** The template preview of one tab, after you turned on "Render vault values" there (off on every open). */
    RENDER_PREVIEW,

    /** The opt-in analysis of decrypted values (D31); refused with [VaultFailure.ANALYSIS_DISABLED] while it is off. */
    ANALYSIS,
}

/** Why a vault operation failed. Carries no data, so it can be logged and shown as it is. */
enum class VaultFailure {
    /** Every candidate id is locked; unlock first ([VaultOperations.unlock]). */
    LOCKED,

    /** The root has no id to try (or, with `vault_id_match`, none with the envelope's label). */
    NO_IDENTITY,

    /** Every candidate secret failed the HMAC check. */
    WRONG_SECRET,

    /** The envelope is malformed; Ansible stops at once without trying further secrets. */
    FORMAT,

    /** The header names a cipher other than `AES256`. */
    UNKNOWN_CIPHER,

    /** The password file, script or PasswordSafe entry could not be read or run. */
    SOURCE_UNAVAILABLE,

    /** A script source needs a trusted project and per-script consent (D28). */
    NOT_TRUSTED,

    /** Encrypting with two or more loaded ids needs an explicit id (Ansible refuses too). */
    ENCRYPT_IDENTITY_REQUIRED,

    /** [VaultPurpose.ANALYSIS] while the root's D31 opt-in is off. */
    ANALYSIS_DISABLED,

    /** You cancelled a consent, trust or password prompt. */
    CANCELLED,
}

/** The result of [VaultOperations.unlock]. */
sealed interface VaultUnlockResult {
    /** The ids that are unlocked now, in Ansible's order. */
    data class Unlocked(val identities: List<String>) : VaultUnlockResult

    data class Failed(val failure: VaultFailure) : VaultUnlockResult
}

/** The result of [VaultOperations.decrypt]. Not a data class: [Decrypted] owns secret bytes. */
sealed interface VaultDecryptResult {
    /** [identity] decrypted the envelope; the caller owns and closes [plaintext]. */
    class Decrypted(val identity: String, val plaintext: VaultPlaintext) : VaultDecryptResult {
        override fun toString(): String = "Decrypted($identity, ***)"
    }

    /** No plaintext; [tried] lists the ids whose secrets were tried, in order. */
    data class Failed(val failure: VaultFailure, val tried: List<String>) : VaultDecryptResult
}

/** The result of [VaultOperations.encrypt]. */
sealed interface VaultEncryptResult {
    /** The envelope text as ansible-vault formats it (80 columns, lowercase hex, trailing LF); not secret. */
    data class Encrypted(val envelope: String, val header: VaultHeaderInfo, val identity: String) : VaultEncryptResult

    data class Failed(val failure: VaultFailure) : VaultEncryptResult
}

/**
 * The explicit vault operations other areas may trigger (implemented in `vault`), for example a Reveal action on an
 * Effective-vars row (HA9) or a "Move secret to vault.yml" fix. Never call them from highlighting, folding,
 * completion or documentation.
 *
 * Threading: the suspend functions run their crypto on `Dispatchers.Default`, password sources on `Dispatchers.IO`,
 * and switch to the EDT themselves for consent and password prompts; never call them under a read lock. [lockAll] and
 * [reveal] run on the EDT.
 */
interface VaultOperations {
    /**
     * Unlocks [identity] of [root], or every id the root can try when null, through the discovery chain (F7.9):
     * consent before the first read of a discovered source (D25, stored per user at application level), password
     * files read again on each unlock, PasswordSafe or a prompt, scripts only in trusted projects after per-script
     * consent (D28).
     */
    suspend fun unlock(root: AnsibleRoot, identity: String? = null): VaultUnlockResult

    /** Locks every id of every root: closes decrypted tabs (asking about unsaved edits), clears the plaintext and verification caches. */
    fun lockAll()

    /**
     * Decrypts the envelope at [location] (as for [VaultStatusService.status]) with the unlocked ids of its root, in
     * Ansible's matching order, for [purpose]. Never prompts: a locked root gives [VaultFailure.LOCKED]. A success is
     * recorded in the verification cache.
     */
    suspend fun decrypt(location: SourceLocation, purpose: VaultPurpose): VaultDecryptResult

    /**
     * Encrypts [plaintext] for [root] with [identity], or with the id Ansible's rules choose when null
     * (`vault_encrypt_identity`, the env → id mapping for [target], the only loaded id). `default` writes a `1.1`
     * header, any other label `1.2;AES256;<label>`. The service neither keeps nor zeroes [plaintext].
     */
    suspend fun encrypt(root: AnsibleRoot, plaintext: ByteArray, identity: String?, target: VirtualFile?): VaultEncryptResult

    /** Shows the timed, masked Reveal popup (F7.1) for the inline value at [location], next to the caret of [editor] when given. */
    fun reveal(location: SourceLocation, editor: Editor?)

    companion object {
        fun getInstance(project: Project): VaultOperations = project.service()
    }
}
