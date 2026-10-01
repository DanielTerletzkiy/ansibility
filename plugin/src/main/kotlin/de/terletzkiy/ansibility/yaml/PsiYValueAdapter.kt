package de.terletzkiy.ansibility.yaml

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.semantics.yaml.SourceRange
import de.terletzkiy.ansibility.semantics.yaml.YEmpty
import de.terletzkiy.ansibility.semantics.yaml.YEntry
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.semantics.yaml.YVault
import org.jetbrains.yaml.YAMLElementTypes
import org.jetbrains.yaml.YAMLTokenTypes
import org.jetbrains.yaml.psi.YAMLAlias
import org.jetbrains.yaml.psi.YAMLAnchor
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLSequenceItem
import org.jetbrains.yaml.psi.YAMLValue

/**
 * Converts IntelliJ YAML PSI into the PSI-free [YValue] model: the value Ansible's loader (PyYAML, YAML 1.1) builds.
 *
 * - **Scalars** keep their unescaped, folded text ([YScalar.text]), their style, their explicit tag (normalised,
 *   see [YamlPsi.normalizeTag]) and their spelling in the file ([YScalar.sourceText], without anchor and tag), so
 *   [YScalar.resolved] applies the YAML 1.1 implicit types. `!vault` becomes [YVault]; a plain scalar with no text
 *   and no tag (`key:`) becomes [YEmpty].
 * - **Aliases** are replaced by the anchored value (the last anchor of that name before the alias, as PyYAML
 *   resolves them). A recursive alias (`&a [*a]`) becomes an empty collection of the anchored kind; an undefined
 *   alias becomes [YEmpty] (Ansible cannot load such a file, and the YAML plugin flags it).
 * - **Merge keys** (`<<: *a`, `<<: [*a, *b]`, `<<: {inline}`) are applied with PyYAML's rule: explicit keys win;
 *   within one sequence the earlier mapping wins; of two separate `<<` keys the later wins. Entries keep the
 *   mapping's explicit keys in source order (duplicates included; [YMap.get] returns the last, as PyYAML does),
 *   followed by the merged keys that no explicit key overrides.
 * - **Ranges** are the PSI text range of the node that wrote the value, anchor and tag included. Values reached
 *   through an alias or a merge key keep the ranges of their definition, so a range always covers the text that
 *   produced the value. An empty value has a zero-length range just after its `:` or `-`.
 *
 * Call inside a read action. Loops check for cancellation.
 */
object PsiYValueAdapter {
    private val DOCUMENT_VALUE = Key.create<CachedValue<YValue?>>("ansibility.yaml.documentValue")
    private val VAULT_TAGS = setOf("!vault", "!vault-encrypted")

    /** The value of [value]; null (a missing value) is [YEmpty] without a range. */
    fun toYValue(value: YAMLValue?): YValue = Converter().value(value)

    /** The value of a key-value, with a proper range for an empty value (`key:`). */
    fun valueOf(keyValue: YAMLKeyValue): YValue = Converter().valueOf(keyValue)

    /** The value of a sequence item, with a proper range for an empty item (`-`). */
    fun valueOf(item: YAMLSequenceItem): YValue = Converter().valueOf(item)

    /** The key of a key-value as a scalar: unquoted and unescaped text, with the key's own style and range. */
    fun keyOf(keyValue: YAMLKeyValue): YScalar = Converter().key(keyValue)

    /**
     * The first document's value, or null when the file has no content (empty, or only comments). Ansible loads
     * single-document files only; later documents are ignored here. Cached until the file changes.
     */
    fun documentValue(file: YAMLFile): YValue? =
        CachedValuesManager.getCachedValue(file, DOCUMENT_VALUE) {
            val value = file.documents.firstOrNull()?.topLevelValue?.let { Converter().value(it) }
            CachedValueProvider.Result.create(value, file)
        }

    /** One conversion; [active] holds the collections being converted, which breaks recursive aliases. */
    private class Converter {
        private val active = HashSet<PsiElement>()

        fun value(value: YAMLValue?): YValue = when (value) {
            null -> YEmpty()
            is YAMLScalar -> scalar(value)
            is YAMLMapping -> guarded(value, { YMap(emptyList(), YamlPsi.range(value)) }) { mapping(value) }
            is YAMLSequence -> guarded(value, { YSeq(emptyList(), YamlPsi.range(value)) }) { sequence(value) }
            is YAMLAlias -> alias(value)
            // A YAMLCompoundValue that is neither: IntelliJ's error recovery for malformed YAML, which PyYAML rejects.
            else -> YEmpty(YamlPsi.range(value))
        }

        fun valueOf(keyValue: YAMLKeyValue): YValue {
            keyValue.value?.let { return value(it) }
            val colon = generateSequence(keyValue.firstChild) { it.nextSibling }
                .lastOrNull { it.node.elementType == YAMLTokenTypes.COLON }
            val end = (colon ?: keyValue.key ?: keyValue).textRange.endOffset
            // `key: !!str` has a tag but no value node; the tag then hangs off the key-value, after the colon.
            val tag = colon?.let { tagAfter(it) }
            return emptyValue(tag, SourceRange(end, end))
        }

        fun valueOf(item: YAMLSequenceItem): YValue {
            // `[a: 1]`: a single-pair mapping inside a flow sequence has no mapping node (and getValue() would
            // return the pair's value).
            val pairs = YamlPsi.flowPairs(item)
            if (pairs.isNotEmpty()) return YMap(pairs.map { YEntry(key(it), valueOf(it)) }, YamlPsi.range(item))
            item.value?.let { return value(it) }
            val marker = item.firstChild?.takeIf { it.node.elementType == YAMLTokenTypes.SEQUENCE_MARKER }
            val end = marker?.textRange?.endOffset ?: item.textRange.startOffset
            return emptyValue(marker?.let { tagAfter(it) }, SourceRange(end, end))
        }

        fun key(keyValue: YAMLKeyValue): YScalar {
            val key = keyValue.key
            val start = keyValue.textRange.startOffset
            return when {
                key == null -> YScalar("", ScalarStyle.PLAIN, range = SourceRange(start, start))
                // `? key` (explicit key) gives a scalar node; complex keys do not occur in Ansible content.
                key is YAMLScalar -> scalarKey(key)
                key is YAMLValue -> YScalar(key.text, ScalarStyle.PLAIN, range = YamlPsi.range(key))
                else -> keyToken(key, YamlPsi.tagOf(keyValue).takeIf { tagBefore(keyValue, key) })
            }
        }

        /** A key written as a scalar node; an empty key (`? ` alone) is the empty string. */
        private fun scalarKey(key: YAMLScalar): YScalar =
            scalar(key) as? YScalar ?: YScalar("", ScalarStyle.PLAIN, range = YamlPsi.range(key))

        private fun keyToken(token: PsiElement, tag: String?): YScalar {
            val text = token.text
            val range = YamlPsi.range(token)
            return when (text.firstOrNull()) {
                '"' -> YScalar(YamlScalarDecoder.decodeFlow(text), ScalarStyle.DOUBLE_QUOTED, tag, text, range)
                '\'' -> YScalar(YamlScalarDecoder.decodeFlow(text), ScalarStyle.SINGLE_QUOTED, tag, text, range)
                else -> YScalar(YamlScalarDecoder.decodePlain(text), ScalarStyle.PLAIN, tag, text, range)
            }
        }

        private fun mapping(mapping: YAMLMapping): YMap {
            val explicit = ArrayList<YEntry>()
            // Merge sources in the order PyYAML's flatten_mapping collects them: later groups win.
            val mergeGroups = ArrayList<List<YEntry>>()
            val flow = mapping.node.elementType == YAMLElementTypes.HASH
            for (child in generateSequence(mapping.firstChild) { it.nextSibling }) {
                ProgressManager.checkCanceled()
                when {
                    child is YAMLKeyValue && YamlPsi.isMergeKey(child) -> collectMergeSources(child, mergeGroups)
                    child is YAMLKeyValue -> explicit += YEntry(key(child), valueOf(child))
                    // `{a, b: 1}`: a flow mapping key without a value.
                    flow && child is YAMLScalar -> {
                        val end = child.textRange.endOffset
                        explicit += YEntry(scalarKey(child), YEmpty(SourceRange(end, end)))
                    }
                }
            }
            return YMap(applyMerges(explicit, mergeGroups), YamlPsi.range(mapping))
        }

        private fun collectMergeSources(mergeKey: YAMLKeyValue, groups: MutableList<List<YEntry>>) {
            when (val source = valueOf(mergeKey)) {
                is YMap -> groups += source.entries
                // PyYAML reverses a sequence of merge sources, so that the earlier mapping ends up winning.
                is YSeq -> source.items.asReversed().forEach { if (it is YMap) groups += it.entries }
                // Anything else makes PyYAML raise a ConstructorError; the merge key contributes nothing.
                else -> Unit
            }
        }

        private fun applyMerges(explicit: List<YEntry>, groups: List<List<YEntry>>): List<YEntry> {
            if (groups.isEmpty()) return explicit
            val winners = LinkedHashMap<String, YEntry>()
            for (group in groups.asReversed()) {
                // Within one source the last duplicate wins, at the position of the first (Python dict semantics).
                val byKey = LinkedHashMap<String, YEntry>()
                group.forEach { byKey[it.key.text] = it }
                byKey.forEach { (key, entry) -> winners.putIfAbsent(key, entry) }
            }
            val explicitKeys = explicit.mapTo(HashSet()) { it.key.text }
            return explicit + winners.values.filter { it.key.text !in explicitKeys }
        }

        private fun sequence(sequence: YAMLSequence): YSeq =
            YSeq(sequence.items.map { ProgressManager.checkCanceled(); valueOf(it) }, YamlPsi.range(sequence))

        private fun alias(alias: YAMLAlias): YValue {
            val anchor = YamlPsi.anchorOf(alias) ?: return YEmpty(YamlPsi.range(alias))
            return anchoredValue(anchor)
        }

        /** The node an anchor marks. On an empty value the anchor is a child of the key-value or sequence item. */
        private fun anchoredValue(anchor: YAMLAnchor): YValue = when (val owner = anchor.parent) {
            is YAMLValue -> value(owner)
            is YAMLKeyValue -> {
                val key = owner.key
                val onKey = key != null && anchor.textRange.startOffset < key.textRange.startOffset
                if (onKey) key(owner) else valueOf(owner)
            }
            is YAMLSequenceItem -> valueOf(owner)
            else -> YEmpty(YamlPsi.range(anchor))
        }

        private fun scalar(scalar: YAMLScalar): YValue {
            val range = YamlPsi.range(scalar)
            val tag = YamlPsi.tagOf(scalar)
            if (tag in VAULT_TAGS) return YVault(range)
            val content = YamlPsi.contentStart(scalar) ?: return emptyValue(tag, range)
            val style = YamlPsi.styleOf(content)
            val offset = content.textRange.startOffset - scalar.textRange.startOffset
            val source = scalar.text.substring(offset)
            val text = when (style) {
                ScalarStyle.PLAIN -> YamlScalarDecoder.decodePlain(source)
                ScalarStyle.SINGLE_QUOTED, ScalarStyle.DOUBLE_QUOTED -> YamlScalarDecoder.decodeFlow(source)
                ScalarStyle.LITERAL, ScalarStyle.FOLDED ->
                    YamlScalarDecoder.decodeBlock(source + trailingBlankText(scalar), blockParentIndent(scalar))
            }
            if (style == ScalarStyle.PLAIN && text.isEmpty() && tag == null) return YEmpty(range)
            return YScalar(text, style, tag, source, range)
        }

        private fun emptyValue(tag: String?, range: SourceRange): YValue = when (tag) {
            null -> YEmpty(range)
            in VAULT_TAGS -> YVault(range)
            else -> YScalar("", ScalarStyle.PLAIN, tag, "", range)
        }

        /** A tag on the same line after [marker] (a `:` or `-`) of an empty value: `key: !!str`, `key: &a !!str`. */
        private fun tagAfter(marker: PsiElement): String? =
            generateSequence(marker.nextSibling) { it.nextSibling }
                .takeWhile { it.node.elementType == YAMLTokenTypes.TAG || it is YAMLAnchor || it.isSameLineBlank() }
                .firstOrNull { it.node.elementType == YAMLTokenTypes.TAG }
                ?.let { YamlPsi.normalizeTag(it.text) }

        private fun PsiElement.isSameLineBlank(): Boolean = text.isBlank() && '\n' !in text

        private fun tagBefore(keyValue: YAMLKeyValue, key: PsiElement): Boolean =
            generateSequence(keyValue.firstChild) { it.nextSibling }
                .takeWhile { it != key }
                .any { it.node.elementType == YAMLTokenTypes.TAG }

        /**
         * The line breaks and indentation after a block scalar. The lexer does not always put the scalar's last
         * line break inside the scalar node, and `|+` keeps the blank lines that follow.
         */
        private fun trailingBlankText(scalar: YAMLScalar): String {
            val text = StringBuilder()
            var leaf = PsiTreeUtil.nextLeaf(scalar)
            while (leaf != null && leaf.text.isBlank()) {
                text.append(leaf.text)
                leaf = PsiTreeUtil.nextLeaf(leaf)
            }
            return text.toString()
        }

        /** PyYAML's `self.indent` for a block scalar: the column of its key or `-`, or -1 at document level. */
        private fun blockParentIndent(scalar: YAMLScalar): Int = when (val parent = scalar.parent) {
            is YAMLKeyValue, is YAMLSequenceItem -> YamlPsi.columnOf(parent)
            else -> -1
        }

        private inline fun <T : YValue> guarded(element: PsiElement, cyclic: () -> T, convert: () -> T): T {
            if (!active.add(element)) return cyclic()
            try {
                return convert()
            } finally {
                active.remove(element)
            }
        }
    }
}
