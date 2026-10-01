package de.terletzkiy.ansibility.vault.identity

import com.intellij.credentialStore.generateServiceName
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.VaultSecretSource
import de.terletzkiy.ansibility.api.VaultSourceKind
import de.terletzkiy.ansibility.api.VaultSourceOrigin
import de.terletzkiy.ansibility.semantics.vault.ConfigOrigin
import de.terletzkiy.ansibility.semantics.vault.VaultConfig
import de.terletzkiy.ansibility.semantics.vault.VaultScriptProtocol
import java.nio.file.Path

/**
 * The PasswordSafe entries of vault ids (D26): one per (root, id), under the root's real path. Only entries in
 * Ansibility's own namespace are ever read: an explicit id in (shareable) project settings cannot point vault code at
 * another plugin's credentials.
 */
object VaultPasswordSafeKeys {
    /** The PasswordSafe subsystem shown in Keychain Access: "… Ansibility Vault — <root>#<id>". */
    const val SUBSYSTEM: String = "Ansibility Vault"

    private val PREFIX: String = generateServiceName(SUBSYSTEM, "")

    /** The service name of id [label] of the root at [canonicalRootPath]. */
    fun serviceName(canonicalRootPath: String, label: String): String = generateServiceName(SUBSYSTEM, "$canonicalRootPath#$label")

    /** The service name for an entry key of Ansibility's namespace (an explicit id's shared entry, e.g. `team#prod`). */
    fun ownServiceName(key: String): String = generateServiceName(SUBSYSTEM, key)

    /** True when [serviceName] belongs to Ansibility's vault namespace. */
    fun isOwn(serviceName: String): Boolean = serviceName.startsWith(PREFIX)
}

/**
 * Builds the discovery chain of one root (F7.9, research vault-core §6.4) from what may be read before consent: the
 * explicit settings, `ansible.cfg` and the environment (through the codec's [VaultConfig], so slot order, labels and
 * path resolution are Ansible's), `.env.local.skel`, the existence and executable bit of files, and the labels
 * remembered in PasswordSafe. It never opens `.env.local` or a password file and never touches PasswordSafe.
 *
 * The chain, in order:
 * 1. explicit ids of the settings (listed even when their file is missing, so the failure shows at unlock);
 * 2. `vault_identity_list`, then `vault_password_file` (environment over `ansible.cfg`), as Ansible loads them; a
 *    missing file or a directory is skipped, as Ansible skips it, which also drops container-only paths; then a
 *    prompt for `vault_identity` when `ask_vault_pass` is on;
 * 3. `<root>/.env.local` (its `ANSIBLE_LOCAL_VAULT_PASSWORD_FILE` key, named before reading it by the
 *    `.env.local.skel` default or a conventional name), or, without `.env.local`, the `.env.local.skel` default file;
 * 4. the conventional names `.vault-pass`, `.vault_pass`, `.vault-password` in the root, then `.vault-pass` of the
 *    repository (the nearest directory with `.git` up to the project directory);
 * 5. the labels remembered in PasswordSafe for this root;
 * 6. a prompt for `vault_identity` (`default`) when nothing else was found.
 *
 * Steps 3 to 6 use the label `vault_identity` for file sources. The chain is de-duplicated by (label, source): a
 * conventional `.vault-pass` that `.env.local` is expected to name is listed once. An executable file is a script (or
 * a `-client` script), as Ansible decides by the executable bit.
 */
class VaultDiscoverer(private val access: VaultSourceAccess) {
    /** Everything discovery reads; built by [VaultIdentityRegistry]. */
    class Input(
        val root: AnsibleRoot,
        val rootKey: String,
        /** The root directory on the local file system, or null (then only settings, prompts and PasswordSafe count). */
        val rootPath: Path?,
        val canonicalRootPath: String,
        /** The `[defaults]` section of the root's `ansible.cfg` (keys lower-case). */
        val cfgDefaults: Map<String, String>,
        /** The directory of that `ansible.cfg`, or null without one. */
        val cfgDir: String?,
        val environment: Map<String, String>,
        val explicit: List<ExplicitIdentity>,
        /** Labels remembered in PasswordSafe for this root, in the order they were remembered. */
        val remembered: List<String>,
        /** The repository directory (nearest `.git` at or above the root), or null. */
        val repoDir: Path?,
    )

    fun discover(input: Input): VaultDiscovery {
        val cwd = input.rootPath?.toString() ?: input.root.dir.path
        val home = input.environment["HOME"]
        val config = VaultConfig.resolve(input.cfgDefaults, input.cfgDir, input.environment, cwd, home)
        val chain = Chain(input.rootPath, home)
        explicit(input, chain)
        configured(config, chain)
        askVaultPass(input)?.let { origin -> chain.add(config.defaultIdentity, SecretPlan.Prompt, origin, null) }
        if (input.rootPath != null) {
            envLocal(input, input.rootPath, config.defaultIdentity, chain)
            conventional(input, input.rootPath, config.defaultIdentity, chain)
        }
        for (label in input.remembered.distinct()) {
            val serviceName = VaultPasswordSafeKeys.serviceName(input.canonicalRootPath, label)
            chain.add(label, SecretPlan.PasswordSafeEntry(serviceName, isDefaultEntry = true), VaultSourceOrigin.PASSWORD_SAFE, serviceName)
        }
        if (chain.isEmpty()) chain.add(config.defaultIdentity, SecretPlan.Prompt, VaultSourceOrigin.PROMPT, null)
        return VaultDiscovery(input.root, input.rootKey, input.rootPath, input.canonicalRootPath, config, chain.identities.toList())
    }

    private fun explicit(input: Input, chain: Chain) {
        for (identity in input.explicit) {
            val origin = VaultSourceOrigin.SETTINGS
            when (identity.kind) {
                VaultSourceKind.PASSWORD_FILE, VaultSourceKind.SCRIPT, VaultSourceKind.CLIENT_SCRIPT -> {
                    val rootPath = input.rootPath ?: continue
                    val path = VaultPaths.resolve(identity.location ?: continue, rootPath, input.environment["HOME"]) ?: continue
                    chain.addFile(identity.label, path, origin)
                }
                VaultSourceKind.PASSWORD_SAFE -> {
                    val default = VaultPasswordSafeKeys.serviceName(input.canonicalRootPath, identity.label)
                    val serviceName = identity.location?.let { if (VaultPasswordSafeKeys.isOwn(it)) it else VaultPasswordSafeKeys.ownServiceName(it) } ?: default
                    chain.add(identity.label, SecretPlan.PasswordSafeEntry(serviceName, serviceName == default), origin, serviceName)
                }
                VaultSourceKind.PROMPT -> chain.add(identity.label, SecretPlan.Prompt, origin, null)
                VaultSourceKind.ENVIRONMENT -> {
                    val name = identity.location?.takeIf { it.isNotBlank() } ?: continue
                    chain.add(identity.label, SecretPlan.Environment(ConsentTarget.environment(name)), origin, name)
                }
            }
        }
    }

    private fun configured(config: VaultConfig, chain: Chain) {
        for (slot in config.slots) {
            val origin = if (slot.origin == ConfigOrigin.ENVIRONMENT) VaultSourceOrigin.ENVIRONMENT else VaultSourceOrigin.ANSIBLE_CFG
            if (slot.isPrompt) {
                chain.add(slot.label, SecretPlan.Prompt, origin, null)
                continue
            }
            val path = slot.path?.let(VaultPaths::parse) ?: continue
            if (!access.isRegularFile(path)) continue
            chain.addFile(slot.label, path, origin)
        }
    }

    /**
     * `ask_vault_pass` / `ANSIBLE_ASK_VAULT_PASS` (environment over `ansible.cfg`): Ansible then prompts for
     * `vault_identity` after the configured secrets. Returns where the setting came from when it is on, else null.
     */
    private fun askVaultPass(input: Input): VaultSourceOrigin? {
        input.environment[ASK_VAULT_PASS_ENV]?.let { return if (isTrue(it)) VaultSourceOrigin.ENVIRONMENT else null }
        return input.cfgDefaults[ASK_VAULT_PASS_INI]?.takeIf(::isTrue)?.let { VaultSourceOrigin.ANSIBLE_CFG }
    }

    /** ansible-core's `boolean()` for configuration values. */
    private fun isTrue(value: String): Boolean = value.trim().trim('"', '\'').lowercase() in TRUE_VALUES

    private fun envLocal(input: Input, rootPath: Path, label: String, chain: Chain) {
        val home = input.environment["HOME"]
        val skelValue = access.readNonSecret(rootPath.resolve(ENV_LOCAL_SKEL), VaultSourceAccess.NON_SECRET_LIMIT)?.let { bytes ->
            try {
                EnvLocalFile.passwordFile(bytes, input.environment)
            } finally {
                bytes.fill(0)
            }
        }
        val skelTarget = skelValue?.let { VaultPaths.resolve(it, rootPath, home) }
        val envLocal = rootPath.resolve(ENV_LOCAL)
        if (access.isRegularFile(envLocal)) {
            val expectedPath = skelTarget?.takeIf(access::isRegularFile)
                ?: CONVENTIONAL_NAMES.map(rootPath::resolve).firstOrNull(access::isRegularFile)
            val expected = expectedPath?.let(chain::target)
            val plan = SecretPlan.EnvLocal(chain.target(envLocal), expected)
            chain.add(label, plan, VaultSourceOrigin.ENV_LOCAL, expected?.display ?: ENV_LOCAL, also = expected?.locator)
        } else if (skelTarget != null && access.isRegularFile(skelTarget)) {
            chain.addFile(label, skelTarget, VaultSourceOrigin.ENV_LOCAL_SKEL)
        }
    }

    private fun conventional(input: Input, rootPath: Path, label: String, chain: Chain) {
        val candidates = CONVENTIONAL_NAMES.map(rootPath::resolve) + listOfNotNull(input.repoDir?.resolve(CONVENTIONAL_NAMES.first()))
        for (path in candidates) {
            if (access.isRegularFile(path)) chain.addFile(label, path, VaultSourceOrigin.CONVENTIONAL_NAME)
        }
    }

    /** The ordered, de-duplicated chain under construction. */
    private inner class Chain(rootPath: Path?, home: String?) {
        val identities = ArrayList<DiscoveredIdentity>()
        private val seen = HashSet<String>()
        private val targets = ConsentTargets(rootPath, home, access)

        fun isEmpty(): Boolean = identities.isEmpty()

        /** A consent target for the file at [path]: keyed by its real path when it exists. */
        fun target(path: Path): ConsentTarget = targets.file(path)

        /** A password file, or a script when it is executable. */
        fun addFile(label: String, path: Path, origin: VaultSourceOrigin) {
            val target = target(path)
            val plan = if (access.isExecutable(path)) {
                SecretPlan.Script(target.path ?: path, VaultScriptProtocol.kindOf(path.toString(), isExecutable = true))
            } else {
                SecretPlan.PasswordFile(target)
            }
            add(label, plan, origin, target.display)
        }

        /** Adds an identity unless (label, source) is already in the chain; [also] marks a further source as seen. */
        fun add(label: String, plan: SecretPlan, origin: VaultSourceOrigin, location: String?, also: String? = null) {
            val identity = DiscoveredIdentity(label, VaultSecretSource(plan.sourceKind, location, origin), plan)
            val key = "$label|${sourceKey(plan)}"
            if (!seen.add(key)) return
            also?.let { seen += "$label|$it" }
            identities += identity
        }

        private fun sourceKey(plan: SecretPlan): String = when (plan) {
            is SecretPlan.PasswordFile -> plan.target.locator
            is SecretPlan.Script -> (access.canonical(plan.path) ?: plan.path).toString()
            is SecretPlan.EnvLocal -> "env-local:${plan.envLocal.locator}"
            is SecretPlan.Environment -> plan.target.locator
            is SecretPlan.PasswordSafeEntry -> if (plan.isDefaultEntry) INTERACTIVE else "safe:${plan.serviceName}"
            SecretPlan.Prompt -> INTERACTIVE
        }
    }

    companion object {
        /** The repository's convention file (F7.9 step 3). */
        const val ENV_LOCAL: String = ".env.local"

        /** Its committed template, read before consent for the default file name only. */
        const val ENV_LOCAL_SKEL: String = ".env.local.skel"

        /** Conventional password file names, by existence only (F7.9 step 4). */
        val CONVENTIONAL_NAMES: List<String> = listOf(".vault-pass", ".vault_pass", ".vault-password")

        private const val INTERACTIVE = "interactive"
        private const val ASK_VAULT_PASS_INI = "ask_vault_pass"
        private const val ASK_VAULT_PASS_ENV = "ANSIBLE_ASK_VAULT_PASS"
        private val TRUE_VALUES = setOf("y", "yes", "on", "1", "true", "t")
    }
}
