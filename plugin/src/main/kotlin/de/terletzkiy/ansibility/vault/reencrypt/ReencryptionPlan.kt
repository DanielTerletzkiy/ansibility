package de.terletzkiy.ansibility.vault.reencrypt

import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.VaultFailure
import de.terletzkiy.ansibility.semantics.vault.SecretBytes
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.vault.actions.PlainValueRef
import de.terletzkiy.ansibility.vault.actions.VaultValueRef

/**
 * Where one item of a re-encryption plan lives (plan amendment R29, shared engine): a whole-file vault, an inline
 * `!vault` value held by its smart pointer ([VaultValueRef], D131), or a plaintext value that gets its first envelope
 * (a `vault_*` placeholder, D216). Nothing here is secret.
 */
internal sealed interface ReencryptTarget {
    val file: VirtualFile

    /** A whole-file vault: its envelope is read from the file at the stamp the preflight saw. */
    class WholeFile(override val file: VirtualFile) : ReencryptTarget {
        override fun toString(): String = "WholeFile(${file.name})"
    }

    /** An inline `!vault` value. */
    class Value(val ref: VaultValueRef) : ReencryptTarget {
        override val file: VirtualFile get() = ref.file

        override fun toString(): String = "Value($ref)"
    }

    /** A plaintext value that becomes a `!vault |` block; it has no envelope to decrypt. */
    class PlainValue(val ref: PlainValueRef) : ReencryptTarget {
        override val file: VirtualFile get() = ref.file

        override fun toString(): String = "PlainValue($ref)"
    }
}

/** How the old envelope of an item is decrypted (plan amendment R29). */
internal sealed interface Decryption {
    /**
     * With the root's unlocked secrets in Ansible's order ([de.terletzkiy.ansibility.api.VaultOperations.decrypt]); a
     * locked root is unlocked once per run, and an inline value is found again through its pointer right before the
     * decrypt (D131). R18's Rekey and Change Id, and Part 2.
     */
    data object RootSecrets : Decryption

    /**
     * With one explicit [secret] only, not the whole chain (Part 1: "the id's current password"). The caller owns the
     * secret and zeroes it after the run. The envelope is the one the item captured, so no offset is resolved.
     */
    class Explicit(val secret: SecretBytes) : Decryption {
        override fun toString(): String = "Explicit(***)"
    }

    /** Nothing to decrypt: a [ReencryptTarget.PlainValue]. */
    data object None : Decryption
}

/**
 * The plaintext an item is encrypted with: the decrypted one ([Keep]) or one supplied by the caller ([Replace], a
 * generated value). Plaintext exists only while the item's crypto runs.
 */
internal sealed interface NewPlaintext {
    /** The old plaintext, as decrypted. */
    data object Keep : NewPlaintext

    /** A new value from [source], taken right before the item is encrypted; the engine zeroes it after encrypting. */
    class Replace(val source: ReplacementSource) : NewPlaintext {
        override fun toString(): String = "Replace(***)"
    }
}

/**
 * Hands out the bytes of one new value, once per item that uses it (an equal-value group of D219 shares one value).
 * Every [take] returns a fresh array the engine owns and zeroes; the source zeroes its own copy after the last [take]
 * or on [close], whichever comes first.
 */
internal interface ReplacementSource : AutoCloseable {
    /** A fresh copy of the value, or null when the source was closed. */
    fun take(): ByteArray?
}

/**
 * A value shared by [users] items: [make] runs on the first [take] (so a value exists only once some item's crypto
 * needs it), each [take] hands out a copy, and the value is zeroed after the last one. Never prints its bytes.
 */
internal class SharedValue(private var users: Int, private val make: () -> ByteArray) : ReplacementSource {
    private var bytes: ByteArray? = null
    private var closed = false

    @Synchronized
    override fun take(): ByteArray? {
        if (closed || users <= 0) return null
        val value = bytes ?: make().also { bytes = it }
        val copy = value.copyOf()
        users--
        if (users == 0) close()
        return copy
    }

    @Synchronized
    override fun close() {
        bytes?.fill(0)
        bytes = null
        closed = true
    }

    override fun toString(): String = "SharedValue(***)"
}

/** How the new envelope of an item is made (plan amendment R29). */
internal sealed interface Encryption {
    /** With the secret of the configured id [label] and that id's header (R18's Rekey; Part 2's placeholders, D220). */
    class ConfiguredId(val label: String) : Encryption {
        override fun toString(): String = "ConfiguredId($label)"
    }

    /**
     * R18's Change Id: only the header when the id that decrypted the old envelope is [label] (verified by that
     * decrypt: labels are not covered by the HMAC); otherwise with [label]'s secret after [confirm] (root, decrypting
     * id) agreed, which the caller asks once per root and id.
     */
    class ChangeId(val label: String, val confirm: suspend (AnsibleRoot, String) -> Boolean) : Encryption {
        override fun toString(): String = "ChangeId($label)"
    }

    /**
     * With the secret of the id that decrypted the old envelope, keeping the old envelope's header (Part 2, D220:
     * `1.1` stays an unlabelled `1.1`, `1.2;AES256;new` keeps `new`).
     */
    data object SameAsBefore : Encryption

    /**
     * With an explicit [secret] that need not be configured anywhere (Part 1's new password), under [label]'s header
     * ([VaultEnvelope.versionFor]); [keepHeader] takes the old envelope's label instead (D208). The caller owns the
     * secret.
     */
    class Explicit(val secret: SecretBytes, val label: String?, val keepHeader: Boolean) : Encryption {
        override fun toString(): String = "Explicit(***, $label, keepHeader=$keepHeader)"
    }
}

/**
 * One item of a re-encryption plan: an envelope (or plaintext value) in a file, how it is decrypted, what its plaintext
 * becomes and how it is encrypted. [tag] is the caller's handle (a dialog row), never a value.
 */
internal class ReencryptItem(
    val root: AnsibleRoot,
    val target: ReencryptTarget,
    val decryption: Decryption,
    val plaintext: NewPlaintext,
    val encryption: Encryption,
    val tag: Any? = null,
) {
    val file: VirtualFile get() = target.file

    override fun toString(): String = "ReencryptItem($target, $decryption, $plaintext, $encryption)"
}

/** How the preflight of one item ended. Carries no data beyond an id label and a failure class. */
internal sealed interface ItemOutcome {
    /** A new envelope is ready and verified; [identity] is the id whose secret encrypted it (null for an explicit secret). */
    class Ready(val envelope: VaultEnvelope, val identity: String?) : ItemOutcome {
        override fun toString(): String = "Ready($identity)"
    }

    /** Nothing to write: Change Id found the header already right. */
    data object Unchanged : ItemOutcome

    /** The value changed or is gone (D131), or its file changed meanwhile. */
    data object Changed : ItemOutcome

    /** You declined a question about it (Change Id's re-encrypt question). */
    data object Declined : ItemOutcome

    /** The new envelope did not decrypt again with the secret it was encrypted with: never written. */
    data object NotVerified : ItemOutcome

    /** Ready, but the caller left it out after the preflight (Part 2: another value of its equal group failed, D219). */
    data object LeftOut : ItemOutcome

    /** A vault operation failed (locked, wrong secret, format, a cancelled prompt …). */
    class Failed(val failure: VaultFailure) : ItemOutcome {
        override fun toString(): String = "Failed($failure)"
    }
}

/** One item after the preflight: its [outcome], and for a [ReencryptTarget.WholeFile] the stamp its envelope was read at. */
internal class PreparedItem(val item: ReencryptItem, val outcome: ItemOutcome, val stamp: Long? = null) {
    val ready: ItemOutcome.Ready? get() = outcome as? ItemOutcome.Ready

    override fun toString(): String = "PreparedItem(${item.target}, $outcome)"
}

/**
 * The result of a preflight: per item what will be written (new envelopes, ciphertext only) or why it is left alone,
 * plus the documents' modification sequences the preflight saw (by file), which the all-or-nothing writes compare
 * before the first change. Holds no plaintext.
 */
internal class PreparedPlan(val items: List<PreparedItem>, val sequences: Map<VirtualFile, Int>) {
    /** The items with a new envelope, by file, in item order. */
    val readyByFile: Map<VirtualFile, List<PreparedItem>>
        get() = items.filter { it.ready != null }.groupByTo(LinkedHashMap()) { it.item.file }

    /** The items left alone, with their outcome. */
    val leftAlone: List<PreparedItem> get() = items.filter { it.ready == null && it.outcome != ItemOutcome.Unchanged }

    /** This plan without [drop] (items the caller leaves out after the preflight, e.g. the rest of an equal group). */
    fun without(drop: Set<ReencryptItem>, outcome: ItemOutcome): PreparedPlan =
        PreparedPlan(items.map { if (it.item in drop && it.ready != null) PreparedItem(it.item, outcome, it.stamp) else it }, sequences)

    override fun toString(): String = "PreparedPlan(${items.size} items)"
}

/** How writing one file ended (R18's file actions, mode 1). Carries no data beyond a failure class. */
internal sealed interface FileOutcome {
    data object Written : FileOutcome

    /** Nothing to write: no vault in it, or already labelled with the target id. */
    data object Unchanged : FileOutcome

    /** The file (or its document) changed while the crypto ran. */
    data object Changed : FileOutcome

    /** Its decrypted tab has unsaved edits. */
    data object TabModified : FileOutcome

    data object ReadOnly : FileOutcome

    /** Its mode could not be set to 0600 (D29), so nothing was written. */
    data object NotPrivate : FileOutcome

    /** Writing failed; its document has the old text again. */
    data object NotWritten : FileOutcome

    /** A new envelope did not verify (plan amendment R29), so nothing was written. */
    data object NotVerified : FileOutcome

    /** You declined a question about it. */
    data object Cancelled : FileOutcome

    data class Failed(val failure: VaultFailure) : FileOutcome

    companion object {
        /** The file outcome of an item that is left alone (R18: a file is written whole or not at all). */
        fun of(outcome: ItemOutcome): FileOutcome = when (outcome) {
            is ItemOutcome.Ready, ItemOutcome.Unchanged, ItemOutcome.LeftOut -> Unchanged
            ItemOutcome.Changed -> Changed
            ItemOutcome.Declined -> Cancelled
            ItemOutcome.NotVerified -> NotVerified
            is ItemOutcome.Failed -> Failed(outcome.failure)
        }
    }
}
