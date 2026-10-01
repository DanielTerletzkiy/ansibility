package de.terletzkiy.ansibility.semantics.vault

/**
 * Which vault id a new or re-encrypted value is written with, and the header that follows from it.
 *
 * The label decides the header: `default` (the literal, even when `vault_identity` renames the default) and an empty
 * label write `1.1`, any other label `1.2;AES256;<label>` ([VaultEnvelope.versionFor]).
 */
object EncryptIdentity {
    /** The outcome of an encrypt-id choice. */
    sealed interface Choice {
        /** Encrypt with the first secret labelled [label] (Ansible's `match_best_secret`). */
        data class Chosen(val label: String, val rule: Rule) : Choice

        /**
         * More than one id could encrypt and nothing chose one. ansible-vault refuses ("The vault-ids dev,prod are
         * available to encrypt"); the IDE asks, pre-selecting [preselected] when the neighbouring values agree.
         */
        data class Ambiguous(val candidates: List<String>, val preselected: String?) : Choice

        /** [label] was chosen by configuration but no loaded id has it; Ansible refuses and never falls back. */
        data class NotFound(val label: String, val known: List<String>) : Choice

        /** No id is configured or discovered. */
        data object NoIdentity : Choice
    }

    /** Why [Choice.Chosen] chose its label. */
    enum class Rule {
        /** `vault_encrypt_identity` / `ANSIBLE_VAULT_ENCRYPT_IDENTITY`. */
        ENCRYPT_IDENTITY,

        /** The IDE's env → id mapping for a file under `environments/<env>/`. */
        ENVIRONMENT_MAPPING,

        /** The only id there is. */
        ONLY_IDENTITY,
    }

    /**
     * ansible-vault's rule for `encrypt`, `encrypt_string` and `create`, over the labels of the loaded secrets in
     * order (duplicates included: two secrets with the same label still count as two):
     * a non-empty [encryptIdentity] picks the first secret with that label or fails; otherwise one secret is used and
     * two or more are refused.
     */
    fun ansible(encryptIdentity: String?, loaded: List<String>): Choice {
        if (loaded.isEmpty()) return Choice.NoIdentity
        if (!encryptIdentity.isNullOrEmpty()) return byName(encryptIdentity, loaded, Rule.ENCRYPT_IDENTITY)
        if (loaded.size > 1) return Choice.Ambiguous(loaded, null)
        return Choice.Chosen(loaded.single(), Rule.ONLY_IDENTITY)
    }

    /**
     * The IDE's rule (F7.9), first match wins:
     * 1. [encryptIdentity] (`vault_encrypt_identity`), as Ansible;
     * 2. [environmentDefault], the env → id mapping for the target file;
     * 3. the only id (distinct labels: the same label twice is not a choice);
     * 4. otherwise [Choice.Ambiguous], pre-selecting the label most of [neighbours] (the labels of the other vault
     *    values in the file, [VaultEnvelope.labelOrDefault]) use, when it is a candidate.
     *
     * [loaded] are the labels of the root's ids in Ansible's order.
     */
    fun choose(
        encryptIdentity: String?,
        environmentDefault: String?,
        loaded: List<String>,
        neighbours: List<String> = emptyList(),
    ): Choice {
        if (loaded.isEmpty()) return Choice.NoIdentity
        if (!encryptIdentity.isNullOrEmpty()) return byName(encryptIdentity, loaded, Rule.ENCRYPT_IDENTITY)
        if (!environmentDefault.isNullOrEmpty()) return byName(environmentDefault, loaded, Rule.ENVIRONMENT_MAPPING)
        val distinct = loaded.distinct()
        if (distinct.size == 1) return Choice.Chosen(distinct.single(), Rule.ONLY_IDENTITY)
        val counts = neighbours.filter { it in distinct }.groupingBy { it }.eachCount()
        val best = counts.maxOfOrNull { it.value }
        val preselected = best?.let { max -> neighbours.first { counts[it] == max } }
        return Choice.Ambiguous(distinct, preselected)
    }

    /**
     * The label `ansible-vault edit` writes back: the envelope's header label, or [defaultIdentity] when it has none.
     * The version follows from it, not from the old header: a `1.1` envelope under `vault_identity = team` becomes
     * `1.2;AES256;team`, `1.1;AES256;dev` becomes `1.2;AES256;dev`, and `1.2;AES256` becomes `1.1`. Edit re-encrypts
     * with the secret that **decrypted** the value, so a mislabelled value stays mislabelled.
     */
    fun editLabel(envelope: VaultEnvelope, defaultIdentity: String = VaultEnvelope.DEFAULT_IDENTITY): String =
        envelope.labelOrDefault(defaultIdentity)

    private fun byName(label: String, loaded: List<String>, rule: Rule): Choice =
        if (label in loaded) Choice.Chosen(label, rule) else Choice.NotFound(label, loaded)
}
