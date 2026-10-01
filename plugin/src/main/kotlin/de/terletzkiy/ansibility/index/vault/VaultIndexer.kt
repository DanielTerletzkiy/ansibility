package de.terletzkiy.ansibility.index.vault

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.indexing.FileContent
import de.terletzkiy.ansibility.api.VaultEnvelopeKind
import de.terletzkiy.ansibility.index.IndexInput
import de.terletzkiy.ansibility.index.PathFacts
import de.terletzkiy.ansibility.index.PathHint
import de.terletzkiy.ansibility.semantics.vault.EnvelopeParse
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.vault.envelope.VaultEnvelopes
import de.terletzkiy.ansibility.yaml.YamlPaths
import de.terletzkiy.ansibility.yaml.YamlPsi
import org.jetbrains.yaml.YAMLTokenTypes
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * The `ansible.vault` indexer ([VaultIndex]), usable without the index for tests and structural corpus runs. Pure per
 * file: it reads only the file's own bytes and PSI, never configuration, never another file and never a secret.
 */
object VaultIndexer {
    /** The first bytes the header facts of a whole-file vault are read from. */
    private const val HEADER_BYTES = 512

    /** The entries of one indexed [content], keyed by raw label. */
    fun index(content: FileContent): Map<String, List<VaultIndexEntry>> {
        val bytes = content.content
        val size = bytes.size.toLong()
        val timestamp = content.file.timeStamp
        if (VaultEnvelope.isEncryptedFile(bytes)) return group(listOf(wholeFile(bytes, size, timestamp)))
        if (content.fileType.isBinary || !VaultIndex.isYamlInput(PathFacts.of(content.file))) return emptyMap()
        val yaml = IndexInput.of(content).yaml ?: return emptyMap()
        return group(inline(yaml, size, timestamp))
    }

    /** The entries of a file with [path] (`/`-separated) and [bytes], its YAML parsed by [parse] (tests, corpus runs). */
    fun index(path: String, bytes: ByteArray, timestamp: Long, parse: (CharSequence) -> YAMLFile?): Map<String, List<VaultIndexEntry>> {
        val size = bytes.size.toLong()
        if (VaultEnvelope.isEncryptedFile(bytes)) return group(listOf(wholeFile(bytes, size, timestamp)))
        val facts = PathFacts.of(path)
        // Templates are rendered before Ansible loads them, as IndexInput.yaml skips them for the index itself.
        if (!VaultIndex.isYamlInput(facts) || facts.hint == PathHint.TEMPLATE) return emptyMap()
        val yaml = parse(String(bytes, Charsets.UTF_8)) ?: return emptyMap()
        return group(inline(yaml, size, timestamp))
    }

    /** The entry of a whole-file vault from its raw [bytes]; the bytes are parsed but none of the payload is kept. */
    fun wholeFile(bytes: ByteArray, size: Long, timestamp: Long): VaultIndexEntry {
        val parse = VaultEnvelope.parse(bytes)
        val head = String(bytes, 0, minOf(bytes.size, HEADER_BYTES), Charsets.ISO_8859_1)
        val header = VaultEnvelopes.headerOf(head, parse)
        return VaultIndexEntry(
            kind = VaultEnvelopeKind.FILE,
            offset = 0,
            keyPath = emptyList(),
            keyName = null,
            version = header?.version.orEmpty(),
            cipher = header?.cipher.orEmpty(),
            label = header?.label,
            ciphertextLength = (parse as? EnvelopeParse.Ok)?.envelope?.ciphertextLength,
            hexLines = payloadLines(String(bytes, Charsets.ISO_8859_1)),
            problem = VaultEnvelopeProblem.of(parse),
            hint = VaultEnvelopeProblem.hintOf(parse),
            style = null,
            fileSize = size,
            timestamp = timestamp,
        )
    }

    /**
     * Every inline `!vault` (or `!vault-encrypted`) value of [file] in document order: tagged scalars, and tagged keys
     * without a value (Ansible reads them as an empty, malformed envelope).
     */
    fun inline(file: PsiFile, size: Long, timestamp: Long): List<VaultIndexEntry> {
        val entries = ArrayList<VaultIndexEntry>()
        PsiTreeUtil.processElements(file) { element ->
            ProgressManager.checkCanceled()
            when (element) {
                is YAMLScalar -> if (isVaultScalar(element)) entries += inlineEntry(element, size, timestamp)
                is YAMLKeyValue -> if (element.value == null) {
                    vaultTagOf(element)?.let { tag -> entries += emptyEntry(element, tag, size, timestamp) }
                }
            }
            true
        }
        return entries
    }

    /** True when [scalar] carries a vault tag, on itself or (for a value written after a tagged key) on its key-value. */
    fun isVaultScalar(scalar: YAMLScalar): Boolean =
        (YamlPsi.tagOf(scalar) ?: (scalar.parent as? YAMLKeyValue)?.let(YamlPsi::tagOf)) in VaultEnvelopes.VAULT_TAGS

    /** The vault tag element written directly on [element] (a key-value whose value is empty), or null. */
    fun vaultTagOf(element: PsiElement): PsiElement? =
        generateSequence(element.firstChild) { it.nextSibling }
            .firstOrNull { it.node.elementType == YAMLTokenTypes.TAG && YamlPsi.normalizeTag(it.text) in VaultEnvelopes.VAULT_TAGS }

    private fun inlineEntry(scalar: YAMLScalar, size: Long, timestamp: Long): VaultIndexEntry {
        val text = scalar.textValue
        val parse = VaultEnvelope.parse(text)
        val header = VaultEnvelopes.headerOf(text, parse)
        return VaultIndexEntry(
            kind = VaultEnvelopeKind.INLINE,
            offset = scalar.textRange.startOffset,
            keyPath = YamlPaths.keyPath(scalar),
            keyName = (scalar.parent as? YAMLKeyValue)?.keyText,
            version = header?.version.orEmpty(),
            cipher = header?.cipher.orEmpty(),
            label = header?.label,
            ciphertextLength = (parse as? EnvelopeParse.Ok)?.envelope?.ciphertextLength,
            hexLines = payloadLines(text),
            problem = VaultEnvelopeProblem.of(parse),
            hint = VaultEnvelopeProblem.hintOf(parse),
            style = YamlPsi.contentStart(scalar)?.let(YamlPsi::styleOf),
            fileSize = size,
            timestamp = timestamp,
        )
    }

    private fun emptyEntry(keyValue: YAMLKeyValue, tag: PsiElement, size: Long, timestamp: Long) = VaultIndexEntry(
        kind = VaultEnvelopeKind.INLINE,
        offset = tag.textRange.startOffset,
        keyPath = YamlPaths.keyPath(keyValue),
        keyName = keyValue.keyText,
        version = "",
        cipher = "",
        label = null,
        ciphertextLength = null,
        hexLines = 0,
        problem = VaultEnvelopeProblem.EMPTY,
        hint = null,
        style = null,
        fileSize = size,
        timestamp = timestamp,
    )

    /** The non-blank lines after the first non-blank line of [text] (the payload lines of a well-formed envelope). */
    internal fun payloadLines(text: CharSequence): Int {
        var count = 0
        var seenFirst = false
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            if (seenFirst) count++ else seenFirst = true
        }
        return count
    }

    private fun group(entries: List<VaultIndexEntry>): Map<String, List<VaultIndexEntry>> =
        if (entries.isEmpty()) emptyMap() else entries.groupBy { it.label.orEmpty() }
}
