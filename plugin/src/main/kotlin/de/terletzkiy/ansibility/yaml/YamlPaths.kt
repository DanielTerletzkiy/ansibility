package de.terletzkiy.ansibility.yaml

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLAlias
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLSequenceItem
import org.jetbrains.yaml.psi.YAMLValue

/**
 * Structural helpers over IntelliJ's YAML PSI: key paths, path lookup, top-level shape and the comment block above
 * a key. Key texts are the loaded key strings (unquoted and unescaped, see [PsiYValueAdapter.keyOf]).
 *
 * Call inside a read action.
 */
object YamlPaths {
    /**
     * The path from the document root to [element]: mapping keys and sequence indices as strings, outermost first,
     * e.g. `["haproxy_servers", "0", "port"]` for anything inside the `port` key-value of the first server.
     * A key-value (or anything inside its key) contributes its own key. The path is syntactic: it names where
     * [element] is written, not where an alias or merge key copies it to.
     */
    fun keyPath(element: PsiElement): List<String> {
        val path = ArrayList<String>()
        var current: PsiElement? = element
        while (current != null && current !is YAMLDocument && current !is PsiFile) {
            when (current) {
                is YAMLKeyValue -> path += PsiYValueAdapter.keyOf(current).text
                is YAMLSequenceItem -> (current.parent as? YAMLSequence)?.let { path += "${it.items.indexOf(current)}" }
            }
            current = current.parent
        }
        return path.asReversed()
    }

    /**
     * The element [path] names below [root]: the [YAMLKeyValue] of the last mapping key, or the [YAMLSequenceItem]
     * of the last index; [root] itself for an empty path. Lookups see the value Ansible loads: aliases are followed,
     * a duplicate key finds the last occurrence, and a key missing from a mapping is looked up in its merge sources
     * (in PyYAML's priority order), so the result may lie in an anchored definition. Null when nothing matches.
     */
    fun find(root: YAMLValue, path: List<String>): PsiElement? {
        var current: PsiElement = root
        for (segment in path) {
            ProgressManager.checkCanceled()
            val pairs = (current as? YAMLSequenceItem)?.let(YamlPsi::flowPairs).orEmpty()
            if (pairs.isNotEmpty()) {
                current = pairs.lastOrNull { PsiYValueAdapter.keyOf(it).text == segment } ?: return null
                continue
            }
            val value = when (current) {
                is YAMLKeyValue -> current.value
                is YAMLSequenceItem -> current.value
                is YAMLValue -> current
                else -> null
            }
            current = when (val container = dealias(value)) {
                is YAMLMapping -> lookupKey(container, segment, HashSet())
                is YAMLSequence -> segment.toIntOrNull()?.let { container.items.getOrNull(it) }
                else -> null
            } ?: return null
        }
        return current
    }

    /** The key-values of the first document's top-level mapping; empty when the file is not a mapping. */
    fun topLevelKeyValues(file: YAMLFile): List<YAMLKeyValue> =
        (topLevelValue(file) as? YAMLMapping)?.keyValues?.toList().orEmpty()

    /** The first document's top-level value, or null for an empty file. */
    fun topLevelValue(file: YAMLFile): YAMLValue? = file.documents.firstOrNull()?.topLevelValue

    /** True when the first document is a sequence, like task files and playbooks. */
    fun isTopLevelSequence(file: YAMLFile): Boolean = topLevelValue(file) is YAMLSequence

    /** True when the first document is a mapping, like vars, defaults and inventory files. */
    fun isTopLevelMapping(file: YAMLFile): Boolean = topLevelValue(file) is YAMLMapping

    /**
     * The comment block directly above [keyValue], used as fallback documentation: consecutive full-line comments
     * at the key's column, the last one on the line right before the key. A blank line, a trailing comment of a
     * code line or a comment at another column ends the block. Each line loses its leading `#` characters and
     * one following space; blank lines at either end are dropped. Null when there is no such block.
     */
    fun docCommentAbove(keyValue: YAMLKeyValue): String? {
        val column = YamlPsi.columnOf(keyValue)
        val lines = ArrayList<String>()
        var lineBreaks = 0
        var leaf = PsiTreeUtil.prevLeaf(keyValue)
        while (leaf != null) {
            ProgressManager.checkCanceled()
            if (leaf is PsiComment) {
                if (lineBreaks != 1 || !startsLine(leaf) || YamlPsi.columnOf(leaf) != column) break
                lines += stripCommentMarker(leaf.text)
                lineBreaks = 0
            } else if (leaf.text.isBlank()) {
                lineBreaks += leaf.text.count { it == '\n' }
                if (lineBreaks > 1) break
            } else {
                break
            }
            leaf = PsiTreeUtil.prevLeaf(leaf)
        }
        val block = lines.asReversed().dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        return block.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun startsLine(comment: PsiElement): Boolean {
        var leaf = PsiTreeUtil.prevLeaf(comment)
        while (leaf != null) {
            val text = leaf.text
            if ('\n' in text) return true
            if (text.isNotBlank()) return false
            leaf = PsiTreeUtil.prevLeaf(leaf)
        }
        return true
    }

    private fun stripCommentMarker(comment: String): String =
        comment.trimStart('#').removePrefix(" ").trimEnd()

    private fun dealias(value: YAMLValue?): YAMLValue? {
        if (value !is YAMLAlias) return value
        val owner = YamlPsi.anchorOf(value)?.parent
        return owner as? YAMLValue
    }

    private fun lookupKey(mapping: YAMLMapping, key: String, visited: MutableSet<YAMLMapping>): YAMLKeyValue? {
        if (!visited.add(mapping)) return null
        val keyValues = mapping.keyValues
        keyValues.lastOrNull { !YamlPsi.isMergeKey(it) && PsiYValueAdapter.keyOf(it).text == key }?.let { return it }
        // PyYAML priority: of separate merge keys the later wins; within a sequence of sources the earlier wins.
        for (mergeKey in keyValues.filter(YamlPsi::isMergeKey).asReversed()) {
            val sources = when (val source = dealias(mergeKey.value)) {
                is YAMLMapping -> listOf(source)
                is YAMLSequence -> source.items.mapNotNull { dealias(it.value) as? YAMLMapping }
                else -> emptyList()
            }
            for (source in sources) lookupKey(source, key, visited)?.let { return it }
        }
        return null
    }
}
