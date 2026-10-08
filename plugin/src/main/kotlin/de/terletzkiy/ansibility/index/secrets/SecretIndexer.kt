package de.terletzkiy.ansibility.index.secrets

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.indexing.FileContent
import de.terletzkiy.ansibility.semantics.secrets.KeyHit
import de.terletzkiy.ansibility.semantics.secrets.PasswordHashes
import de.terletzkiy.ansibility.semantics.secrets.PrivateKeySignatures
import de.terletzkiy.ansibility.semantics.vault.ShapeContext
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.semantics.vault.VaultFileShape
import de.terletzkiy.ansibility.semantics.vault.VaultShapeKind
import de.terletzkiy.ansibility.vault.envelope.WholeFileShapes
import java.io.IOException

/**
 * The `ansibility.secrets` indexer ([SecretIndex]; plan amendment R21, D164). Pure per file (DEV.md rule 5): the file's
 * own bytes, its decoded text, its type, its byte order mark and its path; never configuration, another file or a root.
 *
 * - A whole-file vault (`$ANSIBLE_VAULT` first, no byte order mark) gets nothing: `ansible.vault` judges its envelope.
 * - [SecretIndexKind.NOT_WHOLE_FILE]: `VaultFileShape.classify` on the first [SHAPE_HEAD_CHARS] characters (bytes of a
 *   binary type), verdict only.
 * - [SecretIndexKind.TEXT_KEY]: `PrivateKeySignatures.scanText` on the decoded text (it reads its 64 KiB window).
 * - [SecretIndexKind.BINARY_KEY]: `PrivateKeySignatures.scanBinary` on binary types with keystore-like names (it reads
 *   up to 1 MiB).
 * - [SecretIndexKind.KEY_LIKE_NAME]: a `*.key` or `*.password` file below a `files` directory with none of the above,
 *   a non-blank head and more than password hashes (`PasswordHashes.isHashOnly`, which reads at most 16 KiB: the
 *   decoded text, or the bytes of a binary type, so the Vault tab agrees with ANS-V108 also on a file the IDE reads as
 *   binary).
 *
 * For text types the platform hands an indexer the decoded text re-encoded (`FileContent.getContent`: LF line
 * separators, and a byte order mark only as `VirtualFile.getBOM` says). That flag is session state: set when some
 * reader decoded the file and not cleared when a pull removes the mark, so the same bytes would index differently.
 * When the mark matters (the text holds an envelope near its top) the file's own bytes are read instead
 * (`VirtualFile.contentsToByteArray`; `getInputStream` skips the mark). The verdicts hold kinds, lines and protection
 * only: no byte of the file is kept.
 */
object SecretIndexer {
    /** The characters the envelope shape is read from (as `WholeFileShapes.ofHead` reads bytes). */
    const val SHAPE_HEAD_CHARS: Int = WholeFileShapes.HEAD_BYTES

    /** What a header starts with (`$ANSIBLE_VAULT;`), as `VaultFileShape.mayHoldEnvelope` looks for it. */
    private val MAGIC: ByteArray = (VaultEnvelope.MAGIC + ";").toByteArray(Charsets.US_ASCII)

    /** A UTF-8 byte order mark may come before the magic. */
    private const val BOM_LENGTH = 3

    private const val FILES = "files"
    private const val KEY_SUFFIX = ".key"
    private const val PASSWORD_SUFFIX = ".password"

    /** The verdicts of one indexed [content], keyed by [SecretIndexKind] name. */
    fun index(content: FileContent): Map<String, List<SecretVerdict>> {
        val file = content.file
        if (content.fileType.isBinary) {
            return index(file.name, SecretIndex.ancestorNames(file), WholeFileShapes.pathContextOf(file), content.content, null)
        }
        val text = content.contentAsText
        // The byte order mark matters only before an envelope: then the file's own bytes decide, not the session's flag.
        val raw = if (text.startsWith(VaultEnvelope.MAGIC) || VaultFileShape.mayHoldEnvelope(text)) rawBytes(file) else null
        val bom = raw != null && startsWithBom(raw)
        val bytes = if (bom) raw.copyOfRange(BOM_LENGTH, raw.size) else raw ?: content.content
        val context = WholeFileShapes.pathContextOf(file).copy(byteOrderMark = bom)
        return index(file.name, SecretIndex.ancestorNames(file), context, bytes, text)
    }

    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    private fun startsWithBom(bytes: ByteArray): Boolean = bytes.size >= BOM_LENGTH && BOM.indices.all { bytes[it] == BOM[it] }

    /** The file's own bytes (its byte order mark included), or null when they cannot be read. */
    private fun rawBytes(file: VirtualFile): ByteArray? = try {
        file.contentsToByteArray()
    } catch (_: IOException) {
        null
    }

    /**
     * The verdicts of a file named [name] in the directories [ancestors] (innermost first), read as [context] (its byte
     * order mark is the one [text] lost), with its [bytes] and, for text types, its decoded [text] (null for binary
     * types). Pure; for the index and for tests.
     */
    fun index(name: String, ancestors: List<String>, context: ShapeContext, bytes: ByteArray, text: CharSequence?): Map<String, List<SecretVerdict>> {
        if (bytes.isEmpty() || (VaultEnvelope.isEncryptedFile(bytes) && !context.byteOrderMark)) return emptyMap()
        val verdicts = LinkedHashMap<String, List<SecretVerdict>>()
        val shape = if (text != null) shapeOf(text, context) else shapeOf(bytes, context)
        if (shape != null) verdicts[SecretIndexKind.NOT_WHOLE_FILE.name] = listOf(shape)
        ProgressManager.checkCanceled()
        if (text == null) {
            PrivateKeySignatures.scanBinary(bytes, name, complete = true)?.let { hit ->
                verdicts[SecretIndexKind.BINARY_KEY.name] = listOf(SecretVerdict(SecretIndexKind.BINARY_KEY, format = hit.format, protection = hit.protection))
            }
        } else {
            val hits = PrivateKeySignatures.scanText(text)
            if (hits.isNotEmpty()) verdicts[SecretIndexKind.TEXT_KEY.name] = hits.map(::textKey)
        }
        if (verdicts.isEmpty() && isKeyLikeCandidate(name, ancestors) && !mayHoldEnvelope(text, bytes) && !isBlankHead(bytes, text) &&
            !isHashOnly(bytes, text)
        ) {
            verdicts[SecretIndexKind.KEY_LIKE_NAME.name] = listOf(SecretVerdict(SecretIndexKind.KEY_LIKE_NAME))
        }
        return verdicts
    }

    /** The ANS-V107 verdict of a decoded [text], or null. */
    private fun shapeOf(text: CharSequence, context: ShapeContext): SecretVerdict? {
        if (!VaultFileShape.mayHoldEnvelope(text)) return null
        val head = if (text.length > SHAPE_HEAD_CHARS) text.subSequence(0, SHAPE_HEAD_CHARS) else text
        return verdictOf(VaultFileShape.classify(head, context, complete = head.length == text.length, withEnvelope = false))
    }

    /** The ANS-V107 verdict of raw [bytes] (binary types; a byte order mark is read from them), or null. */
    private fun shapeOf(bytes: ByteArray, context: ShapeContext): SecretVerdict? {
        if (!mayHoldEnvelope(bytes)) return null
        val head = if (bytes.size > SHAPE_HEAD_CHARS) bytes.copyOf(SHAPE_HEAD_CHARS) else bytes
        return verdictOf(VaultFileShape.classify(head, context, complete = head.size == bytes.size, withEnvelope = false))
    }

    /** The verdict of [shape]; null for none and for a whole-file vault. */
    private fun verdictOf(shape: VaultFileShape?): SecretVerdict? {
        if (shape == null || shape.kind == VaultShapeKind.VAULT) return null
        val line = if (shape.tagLine >= 0) shape.tagLine else shape.headerLine
        return SecretVerdict(SecretIndexKind.NOT_WHOLE_FILE, shape = shape.kind, line = line)
    }

    private fun mayHoldEnvelope(text: CharSequence?, bytes: ByteArray): Boolean =
        if (text != null) VaultFileShape.mayHoldEnvelope(text) else mayHoldEnvelope(bytes)

    /** Whether `$ANSIBLE_VAULT;` starts within the classifier's head (`VaultFileShape.HEAD_CHARS`, after a byte order mark). */
    private fun mayHoldEnvelope(bytes: ByteArray): Boolean {
        val last = minOf(bytes.size - MAGIC.size, VaultFileShape.HEAD_CHARS + BOM_LENGTH)
        var at = 0
        while (at <= last) {
            if (bytes[at] == MAGIC[0] && matchesAt(bytes, at)) return true
            at++
        }
        return false
    }

    private fun matchesAt(bytes: ByteArray, at: Int): Boolean {
        for (i in MAGIC.indices) if (bytes[at + i] != MAGIC[i]) return false
        return true
    }

    private fun textKey(hit: KeyHit) =
        SecretVerdict(SecretIndexKind.TEXT_KEY, format = hit.format, protection = hit.protection, line = hit.line, escaped = hit.escaped)

    /** A `*.key` or `*.password` name below a `files` directory (any depth; the content root is applied at query time). */
    private fun isKeyLikeCandidate(name: String, ancestors: List<String>): Boolean {
        val keyLike = (name.length > KEY_SUFFIX.length && name.endsWith(KEY_SUFFIX)) ||
            (name.length > PASSWORD_SUFFIX.length && name.endsWith(PASSWORD_SUFFIX))
        return keyLike && FILES in ancestors
    }

    /** True when the file holds password hashes only: its decoded [text], or the [bytes] of a binary type. */
    private fun isHashOnly(bytes: ByteArray, text: CharSequence?): Boolean =
        if (text != null) PasswordHashes.isHashOnly(text) else PasswordHashes.isHashOnly(bytes)

    /** True when the head the signatures read is blank: the decoded text's window, or every byte of a binary file. */
    private fun isBlankHead(bytes: ByteArray, text: CharSequence?): Boolean {
        if (text == null) return bytes.all { it == ' '.code.toByte() || it == '\t'.code.toByte() || it == '\n'.code.toByte() || it == '\r'.code.toByte() }
        return text.subSequence(0, minOf(text.length, PrivateKeySignatures.TEXT_WINDOW)).isBlank()
    }

    /** True when [file] may get verdicts: the input rule of [SecretIndex] (path only). */
    fun isCandidate(file: VirtualFile): Boolean = SecretIndex.InputFilter.acceptInput(file)
}
