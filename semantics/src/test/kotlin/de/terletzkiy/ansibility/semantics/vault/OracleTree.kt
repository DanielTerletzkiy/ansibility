package de.terletzkiy.ansibility.semantics.vault

/**
 * The probe tree of a `config-*.json` table: the synthetic files every configuration row saw (under `{T}`), plus a
 * stand-in for the two synthetic scripts, so a row's configuration can be loaded through [VaultSecretChain] exactly as
 * ansible-core loaded it. No process is started: the scripts' behaviour is restated here and checked against the
 * secrets the oracle recorded.
 */
internal class OracleTree(table: Map<String, Any?>) {
    private val files: Map<String, Pair<ByteArray, Boolean>> = table.obj("tree").map { (rel, v) ->
        @Suppress("UNCHECKED_CAST")
        val m = v as Map<String, Any?>
        "${VaultTestData.TREE}/$rel" to (unhex(m.str("hex")) to (m["executable"] as Boolean))
    }.toMap()

    /** The script runs, as `[args]` strings like the synthetic scripts log them. */
    val scriptRuns = mutableListOf<String>()

    fun exists(path: String): Boolean = path in files

    fun isExecutable(path: String): Boolean = files[path]?.second == true

    /** Resolves a row's configuration the way the probe ran it. */
    fun config(row: Map<String, Any?>): VaultConfig {
        @Suppress("UNCHECKED_CAST")
        val cfg = (row["cfg"] as Map<String, String>).mapValues { it.value.inTree() }
        @Suppress("UNCHECKED_CAST")
        val env = (row["env"] as Map<String, String>).mapValues { it.value.inTree() }
        return VaultConfig.resolve(cfg, "${VaultTestData.TREE}/cfgdir", mapOf("HOME" to "${VaultTestData.TREE}/home") + env,
            "${VaultTestData.TREE}/${row.str("cwd")}")
    }

    /** Loads [config]'s slots: files through the strip rules, scripts through the protocol, prompts from [promptHex]. */
    fun load(config: VaultConfig, promptHex: String?): VaultSecretChain.Result =
        VaultSecretChain.load(config) { slot, decryptVaulted ->
            when (val kind = kindOf(slot)) {
                null -> null
                PasswordSourceKind.PROMPT -> SecretBytes.fromPrompt(String(unhex(checkNotNull(promptHex)), Charsets.UTF_8).toCharArray())
                PasswordSourceKind.FILE -> SecretBytes.fromFile(files.getValue(slot.path!!).first, decryptVaulted)
                PasswordSourceKind.SCRIPT, PasswordSourceKind.CLIENT_SCRIPT -> run(VaultScriptProtocol.command(slot, kind), kind)
            }
        }

    /** The source kind of [slot], or null when its file is missing (or a directory). */
    fun kindOf(slot: VaultSecretSlot): PasswordSourceKind? = when {
        slot.isPrompt -> PasswordSourceKind.PROMPT
        !exists(slot.path!!) -> null
        else -> VaultScriptProtocol.kindOf(slot, isExecutable(slot.path))
    }

    private fun run(command: List<String>, kind: PasswordSourceKind): SecretLoad {
        val args = command.drop(1)
        scriptRuns += args.joinToString(" ", "[", "]")
        val (exit, stdout) = when (command.first().substringAfterLast('/')) {
            // multi-client.sh: case "$2" in dev|prod|default) printf <password>;; *) exit 2
            "multi-client.sh" -> when (args.getOrNull(1)) {
                "dev" -> 0 to source("dev")
                "prod" -> 0 to source("prod")
                "default" -> 0 to source("pw1")
                else -> 2 to ByteArray(0)
            }
            // plain-script.sh: printf <script password with CRLF>
            "plain-script.sh" -> 0 to source("script")
            else -> error("no stand-in for ${command.first()}")
        }
        return VaultScriptProtocol.result(kind, exit, stdout)
    }

    private fun source(passwordId: String): ByteArray = VaultTestData.passwords.getValue(passwordId).sourceBytes

    companion object {
        /** The secret class ansible-core instantiates for a source kind. */
        fun ansibleType(kind: PasswordSourceKind): String = when (kind) {
            PasswordSourceKind.FILE -> "FileVaultSecret"
            PasswordSourceKind.SCRIPT -> "ScriptVaultSecret"
            PasswordSourceKind.CLIENT_SCRIPT -> "ClientScriptVaultSecret"
            PasswordSourceKind.PROMPT -> "PromptVaultSecret"
        }
    }
}
