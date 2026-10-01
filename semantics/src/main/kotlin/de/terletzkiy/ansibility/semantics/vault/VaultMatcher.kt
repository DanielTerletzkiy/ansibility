package de.terletzkiy.ansibility.semantics.vault

/**
 * ansible-core's `VaultLib.decrypt_and_get_vault_id`: which secrets are tried for an envelope, in which order, and
 * which one decrypted it.
 *
 * - The envelope's label is the header's fourth field, or `vault_identity` (`default`) when it has none.
 * - With `vault_id_match` off (the default) **every** secret is a candidate, whatever the label says; the label only
 *   decides whether its own secret is among them. Candidates are tried in the order of the secret list, not "label
 *   first" (verified with `-vvvvv`: secrets `[prod, dev, default]` on a `dev` envelope try prod, then dev).
 * - With `vault_id_match` on, only secrets whose label equals the envelope's label are tried; a `1.1` envelope
 *   matches only `vault_identity`.
 * - A wrong secret (HMAC mismatch) moves on to the next candidate; a format error stops at once.
 */
object VaultMatcher {
    /** The order candidates are tried in. */
    enum class TryOrder {
        /** Ansible's order: the order of the secret list. Reports the same id `ansible-playbook` would. */
        ANSIBLE,

        /**
         * The secrets labelled like the envelope first, then the others in list order. Only a speed-up: it reports a
         * different id than Ansible only when two ids share the same password.
         */
        LABEL_FIRST,
    }

    /**
     * The indices into [labels] (the labels of the secret list, in Ansible's order) of the secrets Ansible tries for
     * [envelope], in [order].
     */
    fun candidates(
        envelope: VaultEnvelope,
        labels: List<String>,
        idMatch: Boolean,
        defaultIdentity: String = VaultEnvelope.DEFAULT_IDENTITY,
        order: TryOrder = TryOrder.ANSIBLE,
    ): List<Int> {
        val label = envelope.labelOrDefault(defaultIdentity)
        // decrypt_and_get_vault_id: the label (when non-empty), plus every other label unless vault_id_match is on.
        val targets = HashSet<String>()
        if (label.isNotEmpty()) targets += label
        if (!idMatch) labels.filterTo(targets) { it != label }
        val indices = labels.indices.filter { labels[it] in targets }
        return when (order) {
            TryOrder.ANSIBLE -> indices
            TryOrder.LABEL_FIRST -> indices.sortedBy { if (labels[it] == label) 0 else 1 }
        }
    }

    /** Decrypts [envelope] with the first candidate of [secrets] whose HMAC matches; see [candidates]. */
    fun decrypt(
        envelope: VaultEnvelope,
        secrets: List<LabelledSecret>,
        idMatch: Boolean,
        defaultIdentity: String = VaultEnvelope.DEFAULT_IDENTITY,
        order: TryOrder = TryOrder.ANSIBLE,
    ): DecryptOutcome {
        val expected = envelope.labelOrDefault(defaultIdentity)
        val tried = ArrayList<String>()
        for (index in candidates(envelope, secrets.map { it.label }, idMatch, defaultIdentity, order)) {
            val candidate = secrets[index]
            tried += candidate.label
            val plaintext = try {
                VaultAes256.decrypt(envelope, candidate.secret)
            } catch (e: VaultFormatException) {
                return DecryptOutcome.FormatError(e.reason, tried.toList())
            } ?: continue
            return DecryptOutcome.Decrypted(candidate.label, index, tried.toList(), candidate.label == expected, plaintext)
        }
        return DecryptOutcome.NoSecretWorked(tried.toList())
    }

    /**
     * The decryptor for a vaulted password file ([SecretBytes.fromFile]): Ansible decrypts it with the secrets loaded
     * **before** it, under the configured matching rules, so `default@pw1, prod@vaulted` works and the reverse order
     * does not. [loaded] is read when the decryptor runs.
     */
    fun passwordFileDecryptor(
        loaded: List<LabelledSecret>,
        idMatch: Boolean,
        defaultIdentity: String = VaultEnvelope.DEFAULT_IDENTITY,
    ): (VaultEnvelope) -> ByteArray? = { envelope ->
        when (val outcome = decrypt(envelope, loaded, idMatch, defaultIdentity)) {
            is DecryptOutcome.Decrypted -> outcome.release()
            else -> null
        }
    }
}

/** The result of [VaultMatcher.decrypt]. */
sealed interface DecryptOutcome {
    /**
     * [label] (the secret at [index] of the list) decrypted the envelope. Owns the plaintext: read it with [read],
     * take it with [release], and [close] to zero it. Never a `data class`; [toString] hides the plaintext.
     */
    class Decrypted internal constructor(
        val label: String,
        val index: Int,
        /** The labels tried, in order, ending with [label]. */
        val tried: List<String>,
        /**
         * True when [label] is the envelope's own label (`vault_identity` for `1.1`). False means a mislabelled value
         * (vectors v13, v14): it decrypts only because `vault_id_match` is off, and would fail with it on (ANS-V105).
         */
        val labelMatches: Boolean,
        plaintext: ByteArray,
    ) : DecryptOutcome, AutoCloseable {
        private var plaintext: ByteArray? = plaintext

        /** The plaintext length in bytes. */
        val size: Int @Synchronized get() = checkNotNull(plaintext) { "the plaintext was released" }.size

        /** Runs [block] on the plaintext, which must not be kept. */
        @Synchronized
        fun <T> read(block: (ByteArray) -> T): T = block(checkNotNull(plaintext) { "the plaintext was released" })

        /** Hands the plaintext array over to the caller, who zeroes it; afterwards [read] throws. */
        @Synchronized
        fun release(): ByteArray = checkNotNull(plaintext) { "the plaintext was released" }.also { plaintext = null }

        /** Zeroes the plaintext unless it was released; idempotent. */
        @Synchronized
        override fun close() {
            plaintext?.fill(0)
            plaintext = null
        }

        override fun toString(): String = "Decrypted($label, tried=$tried, ***)"
    }

    /** No candidate's HMAC matched; [tried] is empty when no secret was a candidate at all (none, or none matching under `vault_id_match`). */
    data class NoSecretWorked(val tried: List<String>) : DecryptOutcome

    /** The envelope is malformed ([FormatReason.PADDING] after a valid HMAC); no further secret was tried. */
    data class FormatError(val reason: FormatReason, val tried: List<String>) : DecryptOutcome
}
