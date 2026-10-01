package de.terletzkiy.ansibility.semantics.vault

/**
 * Ansible's secret-loading loop (`CLI.setup_vault_secrets`) over the slots of a [VaultConfig], without any I/O: the
 * caller's `source` reads a file, runs a script or prompts for one slot.
 *
 * - Slots load in order; each loaded secret is available at once to decrypt a **later** vaulted password file
 *   ([VaultMatcher.passwordFileDecryptor] with the configured `vault_id_match` and `vault_identity`).
 * - A slot that fails (missing file, empty secret, script error, undecryptable vaulted file) is skipped; Ansible warns
 *   and goes on, and fails only when no slot gave a secret ([Result.failedEntirely]).
 * - Duplicates are kept: two slots with the same label are two secrets.
 */
object VaultSecretChain {
    /** What happened to one slot: the load result, or null when its source does not exist. */
    class Step(val slot: VaultSecretSlot, val load: SecretLoad?) {
        override fun toString(): String = "Step($slot, $load)"
    }

    /** The loaded secrets in Ansible's order, and every step. The caller owns the secrets and zeroes them on lock. */
    class Result(val secrets: List<LabelledSecret>, val steps: List<Step>) {
        /** True when no slot gave a secret although some slot was tried: Ansible raises the last error. */
        val failedEntirely: Boolean get() = secrets.isEmpty() && steps.isNotEmpty()

        override fun toString(): String = "Result(${secrets.map { it.label }}, ${steps.size} steps)"
    }

    /**
     * Loads every slot of [config] through [source], which gets the slot and the decryptor for a vaulted password
     * file and returns the [SecretLoad] (null: the source does not exist).
     */
    fun load(
        config: VaultConfig,
        source: (slot: VaultSecretSlot, decryptVaulted: (VaultEnvelope) -> ByteArray?) -> SecretLoad?,
    ): Result {
        val secrets = ArrayList<LabelledSecret>()
        val steps = ArrayList<Step>()
        val decryptor = VaultMatcher.passwordFileDecryptor(secrets, config.idMatch, config.defaultIdentity)
        for (slot in config.slots) {
            val load = source(slot, decryptor)
            steps += Step(slot, load)
            if (load is SecretLoad.Loaded) secrets += LabelledSecret(slot.label, load.secret)
        }
        return Result(secrets.toList(), steps)
    }
}
