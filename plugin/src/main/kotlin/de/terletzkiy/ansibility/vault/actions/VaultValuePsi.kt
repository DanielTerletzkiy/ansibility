package de.terletzkiy.ansibility.vault.actions

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import de.terletzkiy.ansibility.api.VaultHeaderInfo
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.vault.envelope.VaultEnvelopes
import de.terletzkiy.ansibility.yaml.YamlPsi
import org.jetbrains.yaml.YAMLTokenTypes
import org.jetbrains.yaml.psi.YAMLAnchor
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLScalarList
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * The body of a literal `!vault |` block: the envelope lines below the `key: !vault |` line. Offsets are document
 * offsets; nothing here is secret (the body is ciphertext).
 */
internal class VaultBlockBody(
    /** The offset of the line break that ends the `!vault |` line: X16 folds from here. */
    val headerLineEnd: Int,
    /** The start of the first body line, its indentation included: an edit replaces from here. */
    val start: Int,
    /** The offset after the block's last non-blank character. */
    val end: Int,
    /** The indentation of the first non-blank body line, which re-encrypted lines keep. */
    val indent: Int,
    /** The body lines, blank ones included. */
    val lines: Int,
) {
    /** The range a re-encrypted envelope replaces. */
    val range: TextRange get() = TextRange(start, end)

    /** The range X16 folds: from the end of the `!vault |` line to the end of the block. */
    val foldRange: TextRange get() = TextRange(headerLineEnd, end)
}

/**
 * PSI facts about inline vault values that the vault actions rewrite and the passive vault UI (folding, gutter) shows.
 * Pure PSI and text: nothing here decrypts, reads a secret or touches a vault service. Call in a read action.
 */
internal object VaultValuePsi {
    /**
     * The `!vault` (or `!vault-encrypted`) scalar at [element]: the scalar itself, any token inside it (the tag, a
     * body line), or its key-value (the key). Null for anything else, and for an element inside a nested collection.
     */
    fun vaultScalarAt(element: PsiElement): YAMLScalar? {
        var current: PsiElement? = element
        while (current != null && current !is PsiFile) {
            when (current) {
                is YAMLScalar -> return current.takeIf(::isVault)
                is YAMLKeyValue -> return (current.value as? YAMLScalar)?.takeIf(::isVault)
                is YAMLSequenceItem, is YAMLMapping, is YAMLSequence, is YAMLDocument -> return null
            }
            current = current.parent
        }
        return null
    }

    /** True when [scalar] carries a vault tag (on the scalar, or on its key-value as some YAML layouts put it). */
    fun isVault(scalar: YAMLScalar): Boolean = tagOf(scalar) in VaultEnvelopes.VAULT_TAGS

    /** The explicit tag of [scalar] (or of its key-value), normalised; null when untagged. */
    fun tagOf(scalar: YAMLScalar): String? = YamlPsi.tagOf(scalar) ?: (scalar.parent as? YAMLKeyValue)?.let(YamlPsi::tagOf)

    /** Every vault scalar of [file], in document order. */
    fun vaultScalars(file: PsiFile): List<YAMLScalar> {
        val result = ArrayList<YAMLScalar>()
        for (scalar in PsiTreeUtil.findChildrenOfType(file, YAMLScalar::class.java)) {
            ProgressManager.checkCanceled()
            if (isVault(scalar)) result += scalar
        }
        return result
    }

    /** The YAML key holding [scalar], or null for a sequence item. */
    fun keyName(scalar: YAMLScalar): String? = (scalar.parent as? YAMLKeyValue)?.keyText

    /** The key-value or sequence item that holds [scalar]: what its indentation is measured against. */
    fun owner(scalar: YAMLScalar): PsiElement? = when (val parent = scalar.parent) {
        is YAMLKeyValue, is YAMLSequenceItem -> parent
        else -> null
    }

    /** The column of [scalar]'s key (or of its sequence dash) in [text]. */
    fun ownerColumn(scalar: YAMLScalar, text: CharSequence): Int {
        val start = owner(scalar)?.textRange?.startOffset ?: scalar.textRange.startOffset
        var lineStart = start
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        return start - lineStart
    }

    /** The start of [scalar]'s value as written: its tag when it has one (as a child or just before it), else the scalar. */
    fun valueStart(scalar: YAMLScalar): Int {
        tagLeaf(scalar)?.let { return it.textRange.startOffset }
        return scalar.textRange.startOffset
    }

    /** The end of [scalar]'s value: after its last non-blank character in [text]. */
    fun valueEnd(scalar: YAMLScalar, text: CharSequence): Int {
        val start = scalar.textRange.startOffset
        var end = minOf(scalar.textRange.endOffset, text.length)
        while (end > start && text[end - 1].isWhitespace()) end--
        return end
    }

    /** The TAG token of [scalar]: a direct child, or the key-value's tag written before the scalar. */
    private fun tagLeaf(scalar: YAMLScalar): PsiElement? {
        generateSequence(scalar.firstChild) { it.nextSibling }.firstOrNull { it.elementType == YAMLTokenTypes.TAG }?.let { return it }
        val keyValue = scalar.parent as? YAMLKeyValue ?: return null
        return generateSequence(scalar.prevSibling) { it.prevSibling }.firstOrNull { it.elementType == YAMLTokenTypes.TAG }
            ?.takeIf { keyValue.textRange.contains(it.textRange) }
    }

    /** True when [scalar] (or its key-value) carries an anchor. */
    fun hasAnchor(scalar: YAMLScalar): Boolean {
        if (PsiTreeUtil.getChildOfType(scalar, YAMLAnchor::class.java) != null) return true
        if (generateSequence(scalar.firstChild) { it.nextSibling }.any { it.elementType == YAMLTokenTypes.ANCHOR }) return true
        val keyValue = scalar.parent as? YAMLKeyValue ?: return false
        return generateSequence(keyValue.firstChild) { it.nextSibling }.any { it is YAMLAnchor || it.elementType == YAMLTokenTypes.ANCHOR }
    }

    /**
     * The comment that belongs to [scalar]'s first line: after a one-line value (`password: s3cret  # rotate`, outside
     * the scalar) or after a block indicator (`cert: |  # PEM`, inside it). Returns the range to replace together with
     * the value (null when the comment lies inside the value's own range) and the comment text.
     */
    fun lineComment(scalar: YAMLScalar, text: CharSequence): Pair<TextRange?, String>? {
        generateSequence(scalar.firstChild) { it.nextSibling }
            .takeWhile { it.elementType != YAMLTokenTypes.SCALAR_EOL }
            .firstOrNull { it.elementType == YAMLTokenTypes.COMMENT }
            ?.let { return null to it.text.trim() }
        val end = valueEnd(scalar, text)
        var lineEnd = end
        while (lineEnd < text.length && text[lineEnd] != '\n' && text[lineEnd] != '\r') lineEnd++
        val rest = text.subSequence(end, lineEnd)
        if (rest.isEmpty() || !rest[0].isWhitespace()) return null
        val comment = rest.trim()
        if (!comment.startsWith("#")) return null
        return TextRange(end, end + rest.trimEnd().length) to comment.toString()
    }

    /** The body of [scalar] when it is a literal block (`|`, `|-`, `|+`) with at least one non-blank line, else null. */
    fun literalBody(scalar: YAMLScalar, text: CharSequence): VaultBlockBody? {
        if (scalar !is YAMLScalarList) return null
        val eol = generateSequence(scalar.firstChild) { it.nextSibling }.firstOrNull { it.elementType == YAMLTokenTypes.SCALAR_EOL } ?: return null
        val start = eol.textRange.endOffset
        val end = valueEnd(scalar, text)
        if (end <= start) return null
        var firstContent = start
        while (firstContent < end && text[firstContent].isWhitespace()) firstContent++
        var lineStart = firstContent
        while (lineStart > start && text[lineStart - 1] != '\n') lineStart--
        val lines = 1 + (start until end).count { text[it] == '\n' }
        return VaultBlockBody(eol.textRange.startOffset, start, end, firstContent - lineStart, lines)
    }

    /**
     * The envelope header written in [scalar] (the first line of its value), without decoding the body: what folding,
     * the previews and the encrypt-id choice show. Null when the scalar holds no `$ANSIBLE_VAULT` header. Only the
     * header line goes through the codec's reader, so a file with many values folds in time linear in its headers.
     */
    fun header(scalar: YAMLScalar): VaultHeaderInfo? {
        val firstLine = scalar.textValue.substringBefore('\n')
        return VaultEnvelopes.headerOf(firstLine, VaultEnvelope.parse(firstLine))
    }
}
