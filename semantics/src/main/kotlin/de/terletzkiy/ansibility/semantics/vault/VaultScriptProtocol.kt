package de.terletzkiy.ansibility.semantics.vault

/** What a password source is, once its file is known (`get_file_vault_secret`). */
enum class PasswordSourceKind {
    /** A plain password file (not executable). */
    FILE,

    /** An executable script, run without arguments; stdout is the secret. */
    SCRIPT,

    /** An executable whose name without extension ends in `-client`, run with `--vault-id <label>`. */
    CLIENT_SCRIPT,

    /** `label@prompt`: asked for interactively. */
    PROMPT,
}

/**
 * Ansible's password-script protocol, without running anything (process execution belongs to the plugin's
 * `VaultSecretProcess`, after the script-trust consent of D28):
 * - an executable password file is a script; a script whose name without extension ends in `-client` is a client
 *   script (`script_is_client`);
 * - a plain script is run with no arguments and its stderr is not captured (it may prompt), a non-zero exit fails;
 * - a client script is run with `--vault-id <label>` when the slot has a label ([VaultSecretSlot.clientVaultId]),
 *   its stderr is captured, exit code 2 means "no secret for this vault id";
 * - stdout keeps everything but surrounding CR and LF ([SecretBytes.fromScript]).
 */
object VaultScriptProtocol {
    /** The exit code with which a client script reports an unknown vault id (`VAULT_ID_UNKNOWN_RC`). */
    const val UNKNOWN_VAULT_ID_EXIT_CODE: Int = 2

    /** The option a client script receives the label with. */
    const val VAULT_ID_OPTION: String = "--vault-id"

    /** `script_is_client`: the name without its extension (Python `os.path.splitext`) ends in `-client`. */
    fun isClientScriptName(path: String): Boolean = splitExt(path).endsWith("-client")

    /** The kind of a password file at [path], given whether it is executable. */
    fun kindOf(path: String, isExecutable: Boolean): PasswordSourceKind = when {
        !isExecutable -> PasswordSourceKind.FILE
        isClientScriptName(path) -> PasswordSourceKind.CLIENT_SCRIPT
        else -> PasswordSourceKind.SCRIPT
    }

    /** The kind of [slot], given whether its file is executable (ignored for a prompt). */
    fun kindOf(slot: VaultSecretSlot, isExecutable: Boolean): PasswordSourceKind =
        if (slot.isPrompt) PasswordSourceKind.PROMPT else kindOf(checkNotNull(slot.path), isExecutable)

    /**
     * The command line Ansible runs for a script at [path]: the path alone, plus `--vault-id <clientVaultId>` for a
     * client script with a non-empty id.
     */
    fun command(path: String, kind: PasswordSourceKind, clientVaultId: String?): List<String> {
        require(kind == PasswordSourceKind.SCRIPT || kind == PasswordSourceKind.CLIENT_SCRIPT) { "$kind is not a script" }
        return if (kind == PasswordSourceKind.CLIENT_SCRIPT && !clientVaultId.isNullOrEmpty()) {
            listOf(path, VAULT_ID_OPTION, clientVaultId)
        } else {
            listOf(path)
        }
    }

    /** [command] for [slot]. */
    fun command(slot: VaultSecretSlot, kind: PasswordSourceKind): List<String> =
        command(checkNotNull(slot.path) { "a prompt runs no command" }, kind, slot.clientVaultId)

    /** Whether Ansible captures the script's stderr (client scripts) or leaves it to the terminal (plain scripts). */
    fun capturesStderr(kind: PasswordSourceKind): Boolean = kind == PasswordSourceKind.CLIENT_SCRIPT

    /** Interprets a finished script run: the secret from [stdout] on exit 0, else why there is none. */
    fun result(kind: PasswordSourceKind, exitCode: Int, stdout: ByteArray): SecretLoad = when {
        exitCode == 0 -> SecretBytes.fromScript(stdout)
        kind == PasswordSourceKind.CLIENT_SCRIPT && exitCode == UNKNOWN_VAULT_ID_EXIT_CODE ->
            SecretLoad.Failed(SecretLoadFailure.UNKNOWN_VAULT_ID, exitCode)
        else -> SecretLoad.Failed(SecretLoadFailure.SCRIPT_FAILED, exitCode)
    }

    /** `posixpath.splitext(p)[0]`: drops the last extension of the last segment; leading dots are not extensions. */
    internal fun splitExt(path: String): String {
        val sep = path.lastIndexOf('/')
        val dot = path.lastIndexOf('.')
        if (dot > sep) {
            for (i in sep + 1 until dot) if (path[i] != '.') return path.substring(0, dot)
        }
        return path
    }
}
