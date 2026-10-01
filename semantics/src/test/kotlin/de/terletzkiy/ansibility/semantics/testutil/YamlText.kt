package de.terletzkiy.ansibility.semantics.testutil

import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.semantics.yaml.SourceRange
import de.terletzkiy.ansibility.semantics.yaml.YEmpty
import de.terletzkiy.ansibility.semantics.yaml.YEntry
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.semantics.yaml.YVault
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag
import java.io.StringReader

/**
 * Test helper: parses YAML text into the PSI-free [YValue] model, the way `PsiYValueAdapter` does from PSI.
 * Merge keys (`<<`) are applied with PyYAML's rule (explicit keys win; earlier merged maps win).
 *
 * Limitation: SnakeYAML's composed nodes do not say whether a standard tag was explicit, so `!!str 3.2`
 * is treated like plain `3.2`. Use quoted scalars in tests instead. Custom tags (`!vault`, `!unsafe`) are kept.
 */
object YamlText {
    fun parse(text: String): YValue {
        val options = LoaderOptions().apply { isProcessComments = false }
        val node = Yaml(options).compose(StringReader(text)) ?: return YEmpty()
        return convert(node)
    }

    fun map(text: String): YMap = parse(text) as YMap

    private fun range(node: Node) = SourceRange(node.startMark.index, node.endMark.index)

    private fun convert(node: Node): YValue = when (node) {
        is ScalarNode -> scalar(node)
        is SequenceNode -> YSeq(node.value.map(::convert), range(node))
        is MappingNode -> mapping(node)
        else -> YEmpty(range(node))
    }

    private fun scalar(node: ScalarNode): YValue {
        val explicitTag = node.tag.takeIf { !node.isImplicitTag() }?.let { tagName(it) }
        if (explicitTag == "!vault") return YVault(range(node))
        if (node.value.isEmpty() && node.scalarStyle == DumperOptions.ScalarStyle.PLAIN && explicitTag == null) {
            return YEmpty(range(node))
        }
        val style = when (node.scalarStyle) {
            DumperOptions.ScalarStyle.SINGLE_QUOTED -> ScalarStyle.SINGLE_QUOTED
            DumperOptions.ScalarStyle.DOUBLE_QUOTED -> ScalarStyle.DOUBLE_QUOTED
            DumperOptions.ScalarStyle.LITERAL -> ScalarStyle.LITERAL
            DumperOptions.ScalarStyle.FOLDED -> ScalarStyle.FOLDED
            else -> ScalarStyle.PLAIN
        }
        return YScalar(node.value, style, explicitTag, node.value, range(node))
    }

    private fun ScalarNode.isImplicitTag(): Boolean =
        tag == Tag.STR || tag == Tag.INT || tag == Tag.FLOAT || tag == Tag.BOOL || tag == Tag.NULL ||
            tag == Tag.TIMESTAMP || tag == Tag.MERGE || tag.value.startsWith("tag:yaml.org,2002:value")

    private fun tagName(tag: Tag): String = when {
        tag.value.startsWith("tag:yaml.org,2002:") -> "!!" + tag.value.removePrefix("tag:yaml.org,2002:")
        else -> tag.value
    }

    private fun mapping(node: MappingNode): YMap {
        val explicit = mutableListOf<YEntry>()
        val merged = mutableListOf<YEntry>()
        for (tuple in node.value) {
            val keyNode = tuple.keyNode
            if (keyNode is ScalarNode && keyNode.tag == Tag.MERGE) {
                val sources = when (val v = tuple.valueNode) {
                    is MappingNode -> listOf(v)
                    is SequenceNode -> v.value.filterIsInstance<MappingNode>()
                    else -> emptyList()
                }
                for (source in sources) {
                    for (entry in mapping(source).entries) {
                        if (merged.none { it.key.text == entry.key.text }) merged += entry
                    }
                }
                continue
            }
            val key = (convert(keyNode) as? YScalar) ?: YScalar(keyNode.toString(), ScalarStyle.PLAIN)
            explicit += YEntry(key, convert(tuple.valueNode))
        }
        val result = explicit.toMutableList()
        for (entry in merged) if (result.none { it.key.text == entry.key.text }) result += entry
        return YMap(result, range(node))
    }
}
