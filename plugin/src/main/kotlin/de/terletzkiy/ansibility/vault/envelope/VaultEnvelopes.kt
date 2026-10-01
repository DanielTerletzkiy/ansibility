package de.terletzkiy.ansibility.vault.envelope

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VaultEnvelopeKind
import de.terletzkiy.ansibility.api.VaultHeaderInfo
import de.terletzkiy.ansibility.semantics.vault.EnvelopeParse
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.yaml.YamlPsi
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import java.io.IOException

/**
 * One vault envelope in a file: where it is, its header as written and the parse result. Not secret: the envelope is
 * ciphertext, and [toString] prints the header only.
 */
class LocatedEnvelope(
    val kind: VaultEnvelopeKind,
    val file: VirtualFile,
    /** The start offset of the `!vault` scalar (inline), or 0 (whole-file vault). */
    val offset: Int,
    /** The YAML key holding an inline value, or null (a whole file, or a sequence item). */
    val keyName: String?,
    /** The header as written, also for a malformed body. */
    val header: VaultHeaderInfo,
    /** The reader's verdict (the codec's rules, exactly Ansible's). */
    val parse: EnvelopeParse,
) {
    /** The envelope when it parsed, else null (ANS-V101–V103 territory). */
    val envelope: VaultEnvelope? get() = (parse as? EnvelopeParse.Ok)?.envelope

    override fun toString(): String = "LocatedEnvelope($kind, ${file.name}, ${header.version};${header.cipher}${header.label?.let { ";$it" }.orEmpty()})"
}

/**
 * Finds vault envelopes without decrypting anything (call in a read action):
 * - **inline**: a YAML scalar tagged `!vault` (or the deprecated `!vault-encrypted`), from the scalar, its key, its
 *   tag or any element inside; the envelope text is the scalar's YAML value (indentation removed, chomping applied),
 *   exactly what Ansible's loader hands the vault reader;
 * - **whole file**: a file whose first 14 raw bytes are `$ANSIBLE_VAULT`, judged on the bytes so a binary vault is
 *   never decoded as text (F7.7).
 */
object VaultEnvelopes {
    /** The YAML tags Ansible's loader turns into vault values. */
    val VAULT_TAGS: Set<String> = setOf("!vault", "!vault-encrypted")

    /** The largest whole-file vault parsed; a larger one reports its header only. */
    private const val MAX_FILE_BYTES = 16 * 1024 * 1024
    private const val HEADER_BYTES = 512

    /** The envelope at [element]: a `!vault` scalar, its key or tag, or anything in a whole-file vault; else null. */
    fun at(element: PsiElement): LocatedEnvelope? {
        val file = element.containingFile ?: return null
        file.virtualFile?.let { virtualFile -> if (isWholeFileVault(virtualFile)) return ofFile(virtualFile) }
        if (element is PsiFile) return null
        return inline(element)
    }

    /** The envelope at [location] (a key or value offset of an inline value, or any offset of a whole-file vault). */
    fun at(project: Project, location: SourceLocation): LocatedEnvelope? {
        val file = location.file
        if (!file.isValid || file.isDirectory) return null
        if (isWholeFileVault(file)) return ofFile(file)
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        val element = psi.findElementAt(location.offset) ?: location.offset.takeIf { it > 0 }?.let { psi.findElementAt(it - 1) } ?: return null
        return inline(element)
    }

    /** Whether [file] is a whole-file vault: its first 14 bytes are `$ANSIBLE_VAULT`. */
    fun isWholeFileVault(file: VirtualFile): Boolean {
        if (file.isDirectory || !file.isValid || file.length < VaultHeaderInfo.MAGIC.length) return false
        val head = try {
            VfsUtilCore.loadNBytes(file, VaultHeaderInfo.MAGIC.length)
        } catch (_: IOException) {
            return false
        }
        return VaultEnvelope.isEncryptedFile(head)
    }

    /**
     * The whole-file vault [file] (check [isWholeFileVault] first), or null when it cannot be read or is larger than
     * 16 MiB (nothing that large is inspected on a passive path).
     */
    fun ofFile(file: VirtualFile): LocatedEnvelope? {
        if (file.length > MAX_FILE_BYTES) return null
        val bytes = try {
            file.contentsToByteArray()
        } catch (_: IOException) {
            return null
        }
        val parse = VaultEnvelope.parse(bytes)
        val header = headerOf(String(bytes, 0, minOf(bytes.size, HEADER_BYTES), Charsets.ISO_8859_1), parse) ?: return null
        return LocatedEnvelope(VaultEnvelopeKind.FILE, file, 0, null, header, parse)
    }

    private fun inline(element: PsiElement): LocatedEnvelope? {
        var current: PsiElement? = element
        while (current != null && current !is PsiFile) {
            ProgressManager.checkCanceled()
            when (current) {
                is YAMLKeyValue -> {
                    val value = current.value as? YAMLScalar ?: return null
                    return if (isVault(value, current)) located(value, current) else null
                }
                is YAMLScalar -> {
                    val keyValue = current.parent as? YAMLKeyValue
                    return if (isVault(current, keyValue)) located(current, keyValue) else null
                }
            }
            current = current.parent
        }
        return null
    }

    private fun isVault(scalar: YAMLScalar, keyValue: YAMLKeyValue?): Boolean {
        val tag = YamlPsi.tagOf(scalar) ?: keyValue?.let(YamlPsi::tagOf)
        return tag in VAULT_TAGS
    }

    private fun located(scalar: YAMLScalar, keyValue: YAMLKeyValue?): LocatedEnvelope? {
        val file = scalar.containingFile?.virtualFile ?: return null
        val text = scalar.textValue
        val parse = VaultEnvelope.parse(text)
        val header = headerOf(text, parse) ?: return null
        return LocatedEnvelope(VaultEnvelopeKind.INLINE, file, scalar.textRange.startOffset, keyValue?.keyText, header, parse)
    }

    /**
     * The header facts of [text]: from the parsed envelope when it parsed, else from its first line when that starts
     * with the magic (a malformed body still has a header); null for text that is no vault data at all.
     */
    internal fun headerOf(text: CharSequence, parse: EnvelopeParse): VaultHeaderInfo? {
        if (parse is EnvelopeParse.Ok) return VaultHeaderInfo(parse.envelope.version, parse.envelope.cipher, parse.envelope.label)
        if (parse is EnvelopeParse.NotVault && !text.startsWith(VaultHeaderInfo.MAGIC)) return null
        val firstLine = text.lineSequence().firstOrNull()?.trim() ?: return null
        if (!firstLine.startsWith(VaultHeaderInfo.MAGIC)) return null
        val fields = firstLine.split(';').map { it.trim() }
        val cipher = fields.getOrNull(2).orEmpty().takeWhile { !it.isWhitespace() }
        return VaultHeaderInfo(fields.getOrNull(1).orEmpty(), cipher, fields.getOrNull(3))
    }
}
