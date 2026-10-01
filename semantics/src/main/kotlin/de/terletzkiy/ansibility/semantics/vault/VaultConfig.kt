package de.terletzkiy.ansibility.semantics.vault

/** The vault keys of ansible-core's configuration, with their `[defaults]` ini key and environment variable. */
enum class VaultSetting(val iniKey: String, val envVar: String) {
    IDENTITY_LIST("vault_identity_list", "ANSIBLE_VAULT_IDENTITY_LIST"),
    PASSWORD_FILE("vault_password_file", "ANSIBLE_VAULT_PASSWORD_FILE"),
    IDENTITY("vault_identity", "ANSIBLE_VAULT_IDENTITY"),
    ID_MATCH("vault_id_match", "ANSIBLE_VAULT_ID_MATCH"),
    ENCRYPT_IDENTITY("vault_encrypt_identity", "ANSIBLE_VAULT_ENCRYPT_IDENTITY"),
    ENCRYPT_SALT("vault_encrypt_salt", "ANSIBLE_VAULT_ENCRYPT_SALT"),
}

/** Where a configuration value came from. */
enum class ConfigOrigin {
    /** The environment variable (it wins over `ansible.cfg`, even when empty). */
    ENVIRONMENT,

    /** The `[defaults]` section of `ansible.cfg`. */
    ANSIBLE_CFG,

    /** Neither: Ansible's default. */
    DEFAULT,
}

/**
 * One `--vault-id` style slug, `label@source` or `source`, split as `CLI.split_vault_id` does: at the first `@`.
 * [name] is null without an `@` and may be empty (`@source`).
 */
class VaultIdSlug(val name: String?, val value: String) {
    /** `prompt` or `prompt_ask_vault_pass`: the secret is asked for instead of read. */
    val isPrompt: Boolean get() = value == PROMPT || value == PROMPT_ASK_VAULT_PASS

    /** The label the loaded secret gets: [name] when non-empty, else [defaultIdentity]. */
    fun label(defaultIdentity: String): String = name?.takeIf { it.isNotEmpty() } ?: defaultIdentity

    override fun toString(): String = if (name == null) value else "$name@$value"

    override fun equals(other: Any?): Boolean = other is VaultIdSlug && name == other.name && value == other.value

    override fun hashCode(): Int = 31 * (name?.hashCode() ?: 0) + value.hashCode()

    companion object {
        const val PROMPT: String = "prompt"
        const val PROMPT_ASK_VAULT_PASS: String = "prompt_ask_vault_pass"

        fun parse(slug: String): VaultIdSlug {
            val at = slug.indexOf('@')
            return if (at < 0) VaultIdSlug(null, slug) else VaultIdSlug(slug.substring(0, at), slug.substring(at + 1))
        }
    }
}

/** Which configuration key a [VaultSecretSlot] comes from. */
enum class SlotSource {
    /** An entry of `vault_identity_list` / `ANSIBLE_VAULT_IDENTITY_LIST`. */
    IDENTITY_LIST,

    /** `vault_password_file` / `ANSIBLE_VAULT_PASSWORD_FILE`, loaded as `<vault_identity>@<file>`. */
    PASSWORD_FILE,
}

/**
 * One secret Ansible loads, in the order it loads them, before anything is read: a prompt, or a path whose content
 * decides the kind ([VaultScriptProtocol.kindOf]). Ansible skips a slot whose file is missing (with a warning) and
 * fails only when no slot gives a secret.
 */
class VaultSecretSlot(
    /** The label of the loaded secret. */
    val label: String,
    /** The slug as Ansible builds it (for the password file: `<vault_identity>@<resolved path>`). */
    val slug: VaultIdSlug,
    val source: SlotSource,
    val origin: ConfigOrigin,
    /**
     * The absolute path Ansible opens (`unfrackpath`: environment variables and `~` expanded, relative to the cwd,
     * normalised, symlinks not followed); null for a prompt.
     */
    val path: String?,
) {
    val isPrompt: Boolean get() = slug.isPrompt

    /**
     * The id passed to a `*-client` script as `--vault-id <id>`: the slug's label as written, so none for a slug
     * without a label, and `vault_identity` for the password file. Null means the script gets no arguments.
     */
    val clientVaultId: String? get() = slug.name?.takeIf { it.isNotEmpty() }

    override fun toString(): String = "VaultSecretSlot($label, $source, ${path ?: "prompt"})"
}

/**
 * The vault configuration of a root as ansible-core reads it (`setup_vault_secrets` and the `DEFAULT_VAULT_*`
 * settings), without reading any password. The IDE passes no command line options, so `--vault-id`,
 * `--vault-password-file` and `--ask-vault-pass` never apply.
 *
 * Every rule here is checked against ansible-core 2.18.8 and 2.21.4 (`config-2.18.json`, `config-2.21.json`).
 */
class VaultConfig private constructor(
    /** `DEFAULT_VAULT_IDENTITY_LIST`: split at `,`, each entry stripped and unquoted; empty entries are kept. */
    val identityList: List<String>,
    /** `DEFAULT_VAULT_PASSWORD_FILE`, resolved to an absolute path, or null when unset or empty. */
    val passwordFile: String?,
    /** `DEFAULT_VAULT_IDENTITY`: the label of unlabelled secrets and of `1.1` envelopes (`default`). */
    val defaultIdentity: String,
    /** `DEFAULT_VAULT_ID_MATCH` as written (ini values unquoted), or null when unset. */
    val idMatchRaw: String?,
    /** `DEFAULT_VAULT_ENCRYPT_IDENTITY` as written, or null. Empty counts as unset when encrypting. */
    val encryptIdentity: String?,
    /** `VAULT_ENCRYPT_SALT` as written, or null. */
    val encryptSalt: String?,
    /** The secrets Ansible loads, in its order: the identity list, then the password file. */
    val slots: List<VaultSecretSlot>,
    private val origins: Map<VaultSetting, ConfigOrigin>,
) {
    /**
     * Strict matching ([VaultMatcher]). Ansible's quirk: the setting has no type, so **any** non-empty value turns it
     * on, `false`, `0` and `no` included; only an unset or empty value (also `vault_id_match = ""`) leaves it off.
     */
    val idMatch: Boolean get() = !idMatchRaw.isNullOrEmpty()

    /** True when strict matching is on through a value that reads as false (ANS-V109's case). */
    val idMatchLooksFalse: Boolean
        get() = idMatch && idMatchRaw!!.trim().lowercase() in FALSE_LOOKING

    /** Where [setting]'s value came from. */
    fun originOf(setting: VaultSetting): ConfigOrigin = origins.getValue(setting)

    /** The UTF-8 bytes of [encryptSalt] (Ansible's deterministic salt), or null when unset or empty: a random salt. */
    fun encryptSaltBytes(): ByteArray? = encryptSalt?.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)

    override fun toString(): String =
        "VaultConfig(slots=$slots, defaultIdentity=$defaultIdentity, idMatch=$idMatch, encryptIdentity=$encryptIdentity)"

    companion object {
        private val FALSE_LOOKING = setOf("false", "0", "no", "off", "n", "f")

        /**
         * Resolves the configuration as Ansible does when run from [cwd] (the root) with [env] and the `[defaults]`
         * section [cfg] (keys lower-case, values as written after the `=`) of the `ansible.cfg` in [cfgDir]:
         * - each key: the environment variable wins when it is set, even to an empty value; ini values are unquoted
         *   (`"x"` → `x`, so `vault_id_match = ""` is empty), environment values are not;
         * - `vault_identity_list` entries resolve against [cwd], even when they come from `ansible.cfg`;
         * - `vault_password_file` from `ansible.cfg` resolves against [cfgDir] (null: no `ansible.cfg`, then [cwd]),
         *   from the environment against [cwd]; `{{CWD}}` in it is replaced by [cwd];
         * - `$VAR`, `${VAR}` (from [env]; unknown variables stay as written) and `~` / `~/` ([home], default `HOME`
         *   of [env]) are expanded; `~user` is left as written.
         *
         * Paths are POSIX strings with `/` separators (the form of IntelliJ's `VirtualFile.path`).
         */
        fun resolve(
            cfg: Map<String, String>,
            cfgDir: String?,
            env: Map<String, String>,
            cwd: String,
            home: String? = env["HOME"],
        ): VaultConfig {
            val origins = HashMap<VaultSetting, ConfigOrigin>()
            fun raw(setting: VaultSetting): String? {
                env[setting.envVar]?.let { origins[setting] = ConfigOrigin.ENVIRONMENT; return it }
                cfg[setting.iniKey]?.let { origins[setting] = ConfigOrigin.ANSIBLE_CFG; return it }
                origins[setting] = ConfigOrigin.DEFAULT
                return null
            }
            fun untyped(setting: VaultSetting): String? =
                raw(setting)?.let { if (origins[setting] == ConfigOrigin.ANSIBLE_CFG) PyText.unquote(it) else it }

            val paths = PyPaths(env, home)
            val defaultIdentity = untyped(VaultSetting.IDENTITY) ?: VaultEnvelope.DEFAULT_IDENTITY
            val identityList = raw(VaultSetting.IDENTITY_LIST)
                ?.split(',')?.map { PyText.unquote(PyText.strip(it)) }
                .orEmpty()
            val passwordFile = raw(VaultSetting.PASSWORD_FILE)?.takeIf { it.isNotEmpty() }?.let { value ->
                val base = if (origins[VaultSetting.PASSWORD_FILE] == ConfigOrigin.ANSIBLE_CFG) cfgDir ?: cwd else cwd
                paths.unfrack(value.replace("{{CWD}}", cwd), base)
            }
            val idMatchRaw = untyped(VaultSetting.ID_MATCH)
            val encryptIdentity = untyped(VaultSetting.ENCRYPT_IDENTITY)
            val encryptSalt = untyped(VaultSetting.ENCRYPT_SALT)

            val slots = ArrayList<VaultSecretSlot>()
            for (entry in identityList) {
                // An empty entry resolves to the cwd, a directory, and never yields a secret.
                if (entry.isEmpty()) continue
                val slug = VaultIdSlug.parse(entry)
                val path = if (slug.isPrompt) null else paths.unfrack(slug.value, cwd)
                slots += VaultSecretSlot(slug.label(defaultIdentity), slug, SlotSource.IDENTITY_LIST,
                    origins.getValue(VaultSetting.IDENTITY_LIST), path)
            }
            if (passwordFile != null) {
                val slug = VaultIdSlug.parse("$defaultIdentity@$passwordFile")
                val path = if (slug.isPrompt) null else paths.unfrack(slug.value, cwd)
                slots += VaultSecretSlot(slug.label(defaultIdentity), slug, SlotSource.PASSWORD_FILE,
                    origins.getValue(VaultSetting.PASSWORD_FILE), path)
            }
            return VaultConfig(identityList, passwordFile, defaultIdentity, idMatchRaw, encryptIdentity, encryptSalt,
                slots, origins)
        }
    }
}

/** Python's text rules that ansible-core's configuration applies. */
internal object PyText {
    /** `str.isspace()` characters (Unicode whitespace as Python defines it). */
    private val WHITESPACE: Set<Char> = buildSet {
        addAll(listOf('\t', '\n', '\u000B', '\u000C', '\r', '\u001C', '\u001D', '\u001E', '\u001F', ' ', '\u0085',
            '\u00A0', '\u1680', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'))
        for (c in '\u2000'..'\u200A') add(c)
    }

    /** `str.strip()`. */
    fun strip(text: String): String = text.trim { it in WHITESPACE }

    /** `ansible.parsing.quoting.unquote`: drops one pair of matching surrounding quotes unless the last is escaped. */
    fun unquote(text: String): String {
        val quoted = text.length > 1 && text.first() == text.last() && (text.first() == '"' || text.first() == '\'') &&
            text[text.length - 2] != '\\'
        return if (quoted) text.substring(1, text.length - 1) else text
    }
}

/** `posixpath` and `ansible.utils.path.unfrackpath(follow=False)` over an explicit environment. */
internal class PyPaths(private val env: Map<String, String>, private val home: String?) {
    /** `unfrackpath(path, follow=False, basedir)`: expandvars, then expanduser, then join with [basedir], then normpath. */
    fun unfrack(path: String, basedir: String): String {
        val expanded = expandUser(expandVars(path))
        return normPath(if (expanded.startsWith('/')) expanded else join(basedir, expanded))
    }

    /** `posixpath.expandvars`: `$name` (`[A-Za-z0-9_]+`) and `${name}`; unknown names stay as written. */
    fun expandVars(path: String): String {
        if ('$' !in path) return path
        var result = path
        var i = 0
        while (true) {
            val m = VAR.find(result, i) ?: break
            var name = m.groupValues[1]
            if (name.startsWith('{') && name.endsWith('}')) name = name.substring(1, name.length - 1)
            val value = env[name]
            if (value == null) {
                i = m.range.last + 1
            } else {
                val tail = result.substring(m.range.last + 1)
                result = result.substring(0, m.range.first) + value
                i = result.length
                result += tail
            }
        }
        return result
    }

    /** `posixpath.expanduser` for `~` and `~/…`; `~user` is left as written (no user database here). */
    fun expandUser(path: String): String {
        if (!path.startsWith('~')) return path
        val slash = path.indexOf('/', 1).let { if (it < 0) path.length else it }
        if (slash != 1) return path
        val userHome = (home ?: return path).trimEnd('/')
        return (userHome + path.substring(slash)).ifEmpty { "/" }
    }

    private fun join(base: String, path: String): String = when {
        base.isEmpty() -> path
        base.endsWith('/') -> base + path
        else -> "$base/$path"
    }

    companion object {
        private val VAR = Regex("""\$([A-Za-z0-9_]+|\{[^}]*\})""")

        /** `posixpath.normpath`. */
        fun normPath(path: String): String {
            if (path.isEmpty()) return "."
            var initialSlashes = if (path.startsWith('/')) 1 else 0
            if (path.startsWith("//") && !path.startsWith("///")) initialSlashes = 2
            val parts = ArrayList<String>()
            for (part in path.split('/')) {
                if (part.isEmpty() || part == ".") continue
                if (part != ".." || (initialSlashes == 0 && parts.isEmpty()) || (parts.isNotEmpty() && parts.last() == "..")) {
                    parts += part
                } else if (parts.isNotEmpty()) {
                    parts.removeAt(parts.size - 1)
                }
            }
            val joined = "/".repeat(initialSlashes) + parts.joinToString("/")
            return joined.ifEmpty { "." }
        }
    }
}
