package de.terletzkiy.ansibility.vault.rotate

import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.VaultSourceKind
import de.terletzkiy.ansibility.index.vault.VaultCounts
import de.terletzkiy.ansibility.semantics.vault.GeneratorKind
import de.terletzkiy.ansibility.semantics.vault.SecretBytes
import de.terletzkiy.ansibility.semantics.vault.SecretGenerator
import de.terletzkiy.ansibility.semantics.vault.SecretLoad
import de.terletzkiy.ansibility.vault.identity.ConsentTarget
import de.terletzkiy.ansibility.vault.identity.DiscoveredIdentity
import org.jetbrains.annotations.Nls
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom

/** Where the new password of one password source goes (plan amendment R29, D211). */
enum class Destination {
    /** A password file (also the one `.env.local` names): replaced atomically with mode 0600 after the vault files, when you leave it checked. */
    PASSWORD_FILE,

    /** An IDE password store entry (a remembered prompt or a configured entry): updated before the first vault file. */
    PASSWORD_SAFE,

    /** Typed when asked: there is nothing to update; the IDE password store keeps the new password for the next unlock. */
    PROMPT,

    /** A password manager, an environment variable, a script, or a password file that is a vault itself: never written (Copy, then Check). */
    YOURSELF,
}

/**
 * One password source of a vault id (plan amendment R29, D206): [key] says which roots share it (a password file by
 * real path, `.env.local` by the file it names, a password manager by kind and reference, a password store entry by
 * service name, an environment variable by name, a script by real path; a prompt is never shared), [display] names it
 * as you know it, [destination] says where the new password goes. Nothing here is secret.
 */
class RotationSource internal constructor(
    val key: String,
    val kind: VaultSourceKind,
    @Nls val display: String,
    val destination: Destination,
    /** For [Destination.PASSWORD_FILE]: the file the new password is written to (its real path), with its consent locator. */
    internal val file: ConsentTarget?,
    /** The chain entry this source is (null for a typed password the chain has no entry for), for Check. */
    internal val identity: DiscoveredIdentity?,
    /**
     * For a password file named by a `.env.local` that was not read yet (no consent): that `.env.local`. The key is then
     * the file the discovery expects, which may not be the one it names, so the file is not written (review, decision 5).
     */
    internal val unresolved: String? = null,
    /** The password file lies outside the root's repository: said explicitly before it is written (review, decision 12). */
    val outside: Boolean = false,
) {
    override fun toString(): String = "RotationSource($key, $destination)"
}

/**
 * One root holding vaults of a vault id, or reading the same password source as one that does (review, decision 5),
 * with that id's counts in it and its password source (D205 part 2).
 */
class RotationRoot internal constructor(
    /** The vault root (a nested playbook root counts as its parent's). */
    val root: AnsibleRoot,
    val rootKey: String,
    @Nls val displayName: String,
    /** The id's envelopes in this root, by their label (unlabelled ones count as the root's default id). */
    val counts: VaultCounts,
    val source: RotationSource,
    /** Whether the selection the action started from touches this root. */
    val preselected: Boolean,
) {
    override fun toString(): String = "RotationRoot($displayName)"
}

/**
 * The roots that read one password source (D206): they are rotated together or not at all, since giving the source a
 * new password for one of them would lock the others out. Every root whose source of the id has the same key is in the
 * group, whatever envelopes it holds (review, decision 5). [write] is the "Write the new password to …" checkbox of a
 * password file (D211).
 */
class RotationGroup internal constructor(val source: RotationSource, val roots: List<RotationRoot>) {
    var checked: Boolean = roots.any { it.preselected }

    /** Roots whose `.env.local` was not read yet: which file they name is unknown, so the password file is not written. */
    val unresolved: List<RotationRoot> get() = roots.filter { it.source.unresolved != null }

    /** Whether the new password may be written to the group's password file at all (a password file, every root resolved). */
    val writable: Boolean get() = source.destination == Destination.PASSWORD_FILE && unresolved.isEmpty()

    /** For a password file: write the new password there (checked by default). Ignored for other sources. */
    var write: Boolean = writable

    override fun toString(): String = "RotationGroup(${source.key}, ${roots.size} roots, checked=$checked)"
}

/** A vault id the dialog offers (D205 part 1): its label, its counts over the roots the action started from, and its groups. */
class RotationIdentity internal constructor(val label: String, val counts: VaultCounts, val groups: List<RotationGroup>) {
    override fun toString(): String = "RotationIdentity($label)"
}

/**
 * The rotation dialog's model (plan amendment R29, D205): the ids, the chosen one, and per id the roots grouped by
 * password source. The dialog (or a test) edits [identity], [RotationGroup.checked] and [RotationGroup.write]. Holds
 * no secret: the new password is the dialog's answer ([NewPassword]).
 */
class RotationRequest internal constructor(val identities: List<RotationIdentity>, preselected: String?) {
    init {
        require(identities.isNotEmpty()) { "no vault id to rotate" }
    }

    /** The chosen vault id. */
    var identity: RotationIdentity = identities.firstOrNull { it.label == preselected } ?: identities.first()

    /** The groups of the chosen id that are checked. */
    val chosen: List<RotationGroup> get() = identity.groups.filter { it.checked }

    /** The id with [label], or null. */
    fun identity(label: String): RotationIdentity? = identities.firstOrNull { it.label == label }

    override fun toString(): String = "RotationRequest(${identities.map { it.label }}, ${identity.label})"
}

/**
 * The new password the dialog answers with (D205 part 3): generated or typed, as a [CharArray] the rotation owns and
 * zeroes ([close]). Never a `String` here, never printed.
 */
class NewPassword(chars: CharArray, val generated: Boolean) : AutoCloseable {
    private var chars: CharArray? = chars

    /** A copy of the characters (for the IDE password store and the clipboard); the caller zeroes it. */
    fun chars(): CharArray = checkNotNull(chars) { "the new password was closed" }.copyOf()

    /** The password as a vault secret (UTF-8), or null when it is empty or no valid text. The caller closes it. */
    fun secret(): SecretBytes? {
        val copy = chars()
        try {
            val bytes = utf8(copy) ?: return null
            try {
                return if (bytes.isEmpty()) null else SecretBytes.of(bytes)
            } finally {
                bytes.fill(0)
            }
        } finally {
            copy.fill('\u0000')
        }
    }

    override fun close() {
        chars?.fill('\u0000')
        chars = null
    }

    override fun toString(): String = "NewPassword(***)"

    companion object {
        /** D205: a generated password is 64 alphanumeric characters. */
        val GENERATOR: SecretGenerator = SecretGenerator(GeneratorKind.ALPHANUMERIC, 64)

        /** Shorter typed passwords get a warning (they are allowed). */
        const val SHORT: Int = 16

        /** A new generated password from [random] (step A's generator, `SecureRandom`). */
        fun generate(random: SecureRandom): NewPassword {
            val bytes = GENERATOR.generate(random)
            try {
                return NewPassword(CharArray(bytes.size) { bytes[it].toInt().toChar() }, generated = true)
            } finally {
                bytes.fill(0)
            }
        }

        /** [chars] as UTF-8 bytes (the caller zeroes them), or null when they are no valid text. */
        internal fun utf8(chars: CharArray): ByteArray? {
            val encoder = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val buffer: ByteBuffer = try {
                encoder.encode(CharBuffer.wrap(chars))
            } catch (_: CharacterCodingException) {
                return null
            }
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            if (buffer.hasArray()) buffer.array().fill(0)
            return bytes
        }

        /** [bytes] as characters (UTF-8; the caller zeroes them), or null when they are no valid UTF-8. */
        internal fun chars(bytes: ByteArray): CharArray? {
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val buffer: CharBuffer = try {
                decoder.decode(ByteBuffer.wrap(bytes))
            } catch (_: CharacterCodingException) {
                return null
            }
            val chars = CharArray(buffer.remaining()).also { buffer.get(it) }
            if (buffer.hasArray()) buffer.array().fill('\u0000')
            return chars
        }

        /** Why a typed password cannot be used (bundle key), or null: empty, not equal, spaces at either end (a password file loses them). */
        internal fun problem(first: CharArray, second: CharArray): String? = when {
            first.isEmpty() -> "rotate.dialog.password.empty"
            !first.contentEquals(second) -> "rotate.dialog.password.mismatch"
            first.first().isWhitespace() || first.last().isWhitespace() -> "rotate.dialog.password.spaces"
            (SecretBytes.fromPrompt(first) as? SecretLoad.Loaded)?.secret?.also { it.zero() } == null -> "rotate.dialog.password.empty"
            else -> null
        }
    }
}

/** One root in the review (D209): what will be rewritten in it. Not secret. */
class ReviewRoot internal constructor(
    @Nls val name: String,
    /** Inline `!vault` values re-encrypted. */
    val values: Int,
    /** Whole-file vaults re-encrypted. */
    val vaultFiles: Int,
    /** The files rewritten, root-relative. */
    val files: List<String>,
) {
    override fun toString(): String = "ReviewRoot($name, $values, $vaultFiles)"
}

/**
 * The review after the preflight (plan amendment R29, D209, D214): what will be rewritten per root, what of it opened
 * only with a password kept from before an earlier rotation, what is left alone and why, the ids of other roots that
 * turned out to have the same password, where the new password goes; and the "Also regenerate secrets…" choice
 * ([alsoRegenerate], unchecked). When the new password would have no durable home (review round 2, decision 3) it
 * offers Copy ([copyNewPassword]) and Rotate needs [storedSafely]. Texts only, never a value.
 */
class RotationReview internal constructor(
    val identity: String,
    val roots: List<ReviewRoot>,
    val leftAlone: List<@Nls String>,
    val equalPasswords: List<@Nls String>,
    val destinations: List<@Nls String>,
    /** Whether there are inline values whose secrets could be regenerated in the same pass (D214). */
    val canRegenerate: Boolean,
    /**
     * What opened only with a password kept from before an earlier rotation of the id and is rotated too (review round
     * 2, decision 2): "path: key (opened with the password from before the rotation of <date>)".
     */
    val fromPrevious: List<@Nls String> = emptyList(),
    /**
     * Copies the new password through the vault clipboard when it would have no durable home (the IDE password store
     * keeps passwords in memory only and no password file is written; review round 2, decision 3); null otherwise.
     */
    val copyNewPassword: (() -> Unit)? = null,
) {
    /** D214: open Regenerate Secrets…'s selection for the rotated values after Rotate. */
    var alsoRegenerate: Boolean = false

    /** Whether Rotate needs "I have stored the new password somewhere safe" (decision 3). */
    val needsSafeCopy: Boolean get() = copyNewPassword != null

    /** "I have stored the new password somewhere safe" (decision 3): required before Rotate when [needsSafeCopy]. */
    var storedSafely: Boolean = false

    override fun toString(): String = "RotationReview($identity, ${roots.size} roots, ${leftAlone.size} left alone)"
}
