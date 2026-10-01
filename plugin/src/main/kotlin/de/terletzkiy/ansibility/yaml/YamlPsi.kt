package de.terletzkiy.ansibility.yaml

import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.semantics.yaml.SourceRange
import org.jetbrains.yaml.YAMLTokenTypes
import org.jetbrains.yaml.psi.YAMLAlias
import org.jetbrains.yaml.psi.YAMLAnchor
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequenceItem
import org.jetbrains.yaml.psi.YAMLValue

/**
 * Low-level facts about IntelliJ's YAML PSI shared by [PsiYValueAdapter] and [YamlPaths].
 *
 * Anchors and tags are children of the node they belong to (`&a "x"` is one `YAMLQuotedText` whose first child is
 * the anchor), except on an empty value, where they hang off the key-value. `YAMLValue.getTag()` misses a tag that
 * follows an anchor, so tags are read from the children here.
 */
internal object YamlPsi {
    private val SCALAR_CONTENT = mapOf<IElementType, ScalarStyle>(
        YAMLTokenTypes.TEXT to ScalarStyle.PLAIN,
        YAMLTokenTypes.SCALAR_STRING to ScalarStyle.SINGLE_QUOTED,
        YAMLTokenTypes.SCALAR_DSTRING to ScalarStyle.DOUBLE_QUOTED,
        YAMLTokenTypes.SCALAR_LIST to ScalarStyle.LITERAL,
        YAMLTokenTypes.SCALAR_TEXT to ScalarStyle.FOLDED,
    )
    private const val YAML_ORG = "tag:yaml.org,2002:"

    /** The first content token of a scalar (after its anchor and tag), or null for an anchor- or tag-only node. */
    fun contentStart(scalar: YAMLScalar): PsiElement? =
        generateSequence(scalar.firstChild) { it.nextSibling }.firstOrNull { it.node.elementType in SCALAR_CONTENT }

    /** The presentation style given by a scalar's first content token. */
    fun styleOf(content: PsiElement): ScalarStyle = SCALAR_CONTENT.getValue(content.node.elementType)

    /** The explicit tag among [element]'s direct children, normalised (see [normalizeTag]); null when untagged. */
    fun tagOf(element: PsiElement): String? =
        generateSequence(element.firstChild) { it.nextSibling }
            .firstOrNull { it.node.elementType == YAMLTokenTypes.TAG }
            ?.let { normalizeTag(it.text) }

    /**
     * Writes a tag the way the semantics model expects: `!<tag:yaml.org,2002:str>` and `!!str` both become `!!str`,
     * other verbatim tags lose their brackets, local tags (`!vault`, `!unsafe`) stay as written.
     */
    fun normalizeTag(raw: String): String {
        val tag = if (raw.startsWith("!<") && raw.endsWith(">")) raw.substring(2, raw.length - 1) else raw
        return if (tag.startsWith(YAML_ORG)) "!!" + tag.removePrefix(YAML_ORG) else tag
    }

    /** True for `<<`, the plain merge key (a quoted `"<<"` is an ordinary key). */
    fun isMergeKey(keyValue: YAMLKeyValue): Boolean {
        val key = keyValue.key ?: return false
        return key !is YAMLValue && key.text == "<<"
    }

    /**
     * The key-values written directly inside a sequence item: `[a: 1]` is a single-pair mapping without a mapping
     * node. Empty for ordinary items, whose mapping (if any) is the item's value.
     */
    fun flowPairs(item: YAMLSequenceItem): List<YAMLKeyValue> = item.children.filterIsInstance<YAMLKeyValue>()

    fun range(element: PsiElement): SourceRange = element.textRange.let { SourceRange(it.startOffset, it.endOffset) }

    /** The column [element] starts at: the length of the text between the previous line break and the element. */
    fun columnOf(element: PsiElement): Int {
        var column = 0
        var leaf = PsiTreeUtil.prevLeaf(element)
        while (leaf != null) {
            val chars = leaf.node.chars
            val lineBreak = chars.lastIndexOf('\n')
            if (lineBreak >= 0) return column + chars.length - lineBreak - 1
            column += chars.length
            leaf = PsiTreeUtil.prevLeaf(leaf)
        }
        return column
    }

    /**
     * The anchor an alias refers to: the last anchor of that name before the alias in the same document, which is
     * how PyYAML's composer resolves it. Null for an undefined alias (Ansible cannot load such a file).
     */
    fun anchorOf(alias: YAMLAlias): YAMLAnchor? {
        val document = PsiTreeUtil.getParentOfType(alias, YAMLDocument::class.java) ?: return null
        val offset = alias.textRange.startOffset
        return anchorsByName(document)[alias.aliasName]?.lastOrNull { it.textRange.startOffset < offset }
    }

    private fun anchorsByName(document: YAMLDocument): Map<String, List<YAMLAnchor>> =
        CachedValuesManager.getCachedValue(document) {
            val anchors = PsiTreeUtil.findChildrenOfType(document, YAMLAnchor::class.java)
                .sortedBy { it.textRange.startOffset }
                .groupBy { it.name }
            CachedValueProvider.Result.create(anchors, document.containingFile)
        }
}
