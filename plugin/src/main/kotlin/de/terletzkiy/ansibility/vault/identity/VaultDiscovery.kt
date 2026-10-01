package de.terletzkiy.ansibility.vault.identity

import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.VaultSecretSource
import de.terletzkiy.ansibility.api.VaultSourceKind
import de.terletzkiy.ansibility.semantics.vault.PasswordSourceKind
import de.terletzkiy.ansibility.semantics.vault.VaultConfig
import java.nio.file.Path

/**
 * Something the user consents to before vault code reads it (D25): a file, or an environment variable of the IDE.
 * [locator] is the key of the stored consent: the file's real path, or `env:<NAME>`.
 */
class ConsentTarget private constructor(
    val locator: String,
    /** The file (its real path when it exists), or null for an environment variable. */
    val path: Path?,
    /** The file as configured, absolute and normalised but with symbolic links kept (the form VFS events use). */
    val declared: Path?,
    /** The environment variable, or null for a file. */
    val environmentVariable: String?,
    /** How the dialog names it: root-relative, `~/…` or absolute, or the variable name. */
    val display: String,
) {
    override fun equals(other: Any?): Boolean = other is ConsentTarget && locator == other.locator

    override fun hashCode(): Int = locator.hashCode()

    override fun toString(): String = "ConsentTarget($display)"

    companion object {
        private const val ENVIRONMENT_PREFIX = "env:"

        /** A file; [canonical] is its real path when it exists, else [declared]. */
        fun file(canonical: Path, declared: Path, display: String): ConsentTarget =
            ConsentTarget(canonical.toString(), canonical, declared, null, display)

        fun environment(name: String): ConsentTarget = ConsentTarget(ENVIRONMENT_PREFIX + name, null, null, name, name)
    }
}

/** How a discovered id's secret is loaded at unlock time. Describes sources only; never holds a secret. */
sealed interface SecretPlan {
    /** What must be consented to before this plan reads anything (empty for PasswordSafe and prompts). */
    val consentTargets: List<ConsentTarget>

    /** A password file (Ansible's strip rules; it may itself be vaulted). */
    class PasswordFile(val target: ConsentTarget) : SecretPlan {
        val path: Path get() = checkNotNull(target.path)
        override val consentTargets: List<ConsentTarget> get() = listOf(target)
    }

    /**
     * The `.env.local` convention: read only the password-file key of [envLocal], then the file it names. [expected] is
     * the file the consent dialog names before anything was read (the `.env.local.skel` default or a conventional
     * name); when `.env.local` names another file, that file needs a consent of its own.
     */
    class EnvLocal(val envLocal: ConsentTarget, val expected: ConsentTarget?) : SecretPlan {
        override val consentTargets: List<ConsentTarget> get() = listOfNotNull(envLocal, expected)
    }

    /** A password script or `-client` script. Scripts run only in M4.6, after script trust (D28); never here. */
    class Script(val path: Path, val kind: PasswordSourceKind) : SecretPlan {
        override val consentTargets: List<ConsentTarget> get() = emptyList()
    }

    /** An environment variable of the IDE holding the password itself (configured explicitly only). */
    class Environment(val target: ConsentTarget) : SecretPlan {
        override val consentTargets: List<ConsentTarget> get() = listOf(target)
    }

    /** A PasswordSafe entry under [serviceName] (a remembered prompt, or an explicitly configured entry). */
    class PasswordSafeEntry(val serviceName: String, val isDefaultEntry: Boolean) : SecretPlan {
        override val consentTargets: List<ConsentTarget> get() = emptyList()
    }

    /** Asked for interactively (after the root's PasswordSafe entry for the label, when one is remembered). */
    data object Prompt : SecretPlan {
        override val consentTargets: List<ConsentTarget> get() = emptyList()
    }
}

/** One id of a root's discovery chain: the label Ansible gives the secret, and where it comes from. */
class DiscoveredIdentity(
    val label: String,
    val source: VaultSecretSource,
    val plan: SecretPlan,
) {
    /**
     * The unlock slot: the key under which the unlocked secret is held. Prompts and the root's default PasswordSafe
     * entry of a label share the slot `interactive:<label>`, because a remembered prompt is the same secret.
     */
    val slot: String = when (plan) {
        is SecretPlan.PasswordFile -> "file:$label:${plan.target.locator}"
        is SecretPlan.EnvLocal -> "env-local:$label:${plan.envLocal.locator}"
        is SecretPlan.Script -> "script:$label:${plan.path}"
        is SecretPlan.Environment -> "env:$label:${plan.target.locator}"
        is SecretPlan.PasswordSafeEntry -> if (plan.isDefaultEntry) interactiveSlot(label) else "safe:$label:${plan.serviceName}"
        SecretPlan.Prompt -> interactiveSlot(label)
    }

    /** True for a prompt or the root's default PasswordSafe entry: the slot a password prompt fills. */
    val isInteractive: Boolean get() = slot == interactiveSlot(label)

    override fun toString(): String = "DiscoveredIdentity($label, ${source.kind}, ${source.origin}, ${source.location})"

    companion object {
        fun interactiveSlot(label: String): String = "interactive:$label"
    }
}

/**
 * The vault ids of one root, discovered without reading any secret (F7.9): Ansible's configuration, the
 * repository's conventions, PasswordSafe entries remembered earlier, and a prompt. Immutable.
 */
class VaultDiscovery(
    /** The root whose ids these are: a nested playbook root reports its parent's discovery. */
    val root: AnsibleRoot,
    /** The `settings.RootKeys` key of [root]. */
    val rootKey: String,
    /** [root]'s directory on the local file system, or null for a root elsewhere (no file sources then). */
    val rootPath: Path?,
    /** [root]'s real path (or its VFS path), the key of PasswordSafe entries and remembered labels. */
    val canonicalRootPath: String,
    /** Ansible's vault configuration of [root] (`vault_identity`, `vault_id_match`, `vault_encrypt_identity`, slots). */
    val config: VaultConfig,
    /** The chain, in the order the secrets are tried. */
    val identities: List<DiscoveredIdentity>,
) {
    /** The labels of [identities], in order (duplicates kept, as Ansible keeps them). */
    val labels: List<String> get() = identities.map { it.label }

    /** The first identity in [slot], or null. */
    fun identityInSlot(slot: String): DiscoveredIdentity? = identities.firstOrNull { it.slot == slot }

    /** Every consent target of the chain, de-duplicated. */
    val consentTargets: List<ConsentTarget> get() = identities.flatMap { it.plan.consentTargets }.distinct()

    override fun toString(): String = "VaultDiscovery($rootKey, ${identities.size} ids)"
}

/** The source kind a plan presents as. */
internal val SecretPlan.sourceKind: VaultSourceKind
    get() = when (this) {
        is SecretPlan.PasswordFile, is SecretPlan.EnvLocal -> VaultSourceKind.PASSWORD_FILE
        is SecretPlan.Script -> if (kind == PasswordSourceKind.CLIENT_SCRIPT) VaultSourceKind.CLIENT_SCRIPT else VaultSourceKind.SCRIPT
        is SecretPlan.Environment -> VaultSourceKind.ENVIRONMENT
        is SecretPlan.PasswordSafeEntry -> VaultSourceKind.PASSWORD_SAFE
        SecretPlan.Prompt -> VaultSourceKind.PROMPT
    }
