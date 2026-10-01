package de.terletzkiy.ansibility.inspections.types

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.YAMLTokenTypes
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLPsiElement
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * Maps a finding's file range to the PSI element a problem is registered on, and the part of it to highlight:
 * - a scalar value: the scalar;
 * - a key (ANS-T002): the key of its key-value, registered on the key-value (so "Remove unsupported key" gets it);
 * - a mapping or sequence value (ANS-T003, ANS-T010): the container, highlighted on its first line only;
 * - an empty value (`key:`, `-`): the key, or the item marker.
 */
internal object TypeAnchors {
    /** The element and the range inside it, or null when [range] does not lie in [file]. */
    fun anchor(file: PsiFile, range: TextRange): Pair<PsiElement, TextRange>? {
        if (range.endOffset > file.textLength) return null
        if (range.isEmpty) return emptyValue(file, range.startOffset)
        val start = file.findElementAt(range.startOffset) ?: return null
        var element: PsiElement = start
        while (!(element is YAMLPsiElement && element.textRange.contains(range))) {
            element = element.parent ?: return null
            if (element is PsiFile) return null
        }
        if (element is YAMLScalar && (element.parent as? YAMLKeyValue)?.key == element) element = element.parent
        val elementRange = element.textRange
        val inElement = range.shiftLeft(elementRange.startOffset)
        if ((element is YAMLMapping || element is YAMLSequence) && inElement == TextRange(0, elementRange.length)) {
            return element to firstLine(element.text)
        }
        return element to inElement
    }

    /** The first line of [text] without trailing blanks (the whole text when it is one line). */
    private fun firstLine(text: String): TextRange {
        val lineEnd = text.indexOf('\n').let { if (it < 0) text.length else it }
        return TextRange(0, text.substring(0, lineEnd).trimEnd().length.coerceAtLeast(1))
    }

    /** `key:` → the key; `-` → the marker. [offset] is just after the `:` or `-`. */
    private fun emptyValue(file: PsiFile, offset: Int): Pair<PsiElement, TextRange>? {
        val before = file.findElementAt((offset - 1).coerceAtLeast(0)) ?: return null
        val owner = PsiTreeUtil.getParentOfType<YAMLPsiElement>(before, YAMLKeyValue::class.java, YAMLSequenceItem::class.java) ?: return null
        val marker = when (owner) {
            is YAMLKeyValue -> owner.key
            else -> owner.firstChild?.takeIf { it.node.elementType == YAMLTokenTypes.SEQUENCE_MARKER }
        } ?: return null
        return owner to marker.textRange.shiftLeft(owner.textRange.startOffset)
    }
}
