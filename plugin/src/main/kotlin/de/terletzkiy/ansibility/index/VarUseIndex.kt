package de.terletzkiy.ansibility.index

import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import de.terletzkiy.ansibility.api.JinjaContainer
import de.terletzkiy.ansibility.lang.jinja.lexer.JinjaLexMode
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaRefs
import de.terletzkiy.ansibility.yaml.YamlPaths
import de.terletzkiy.ansibility.yaml.YamlPsi
import org.jetbrains.yaml.psi.YAMLScalar
import java.io.DataInput
import java.io.DataOutput

/** Where a variable use sits (stable [code]s, mapped to the api's [JinjaContainer]). */
enum class UseContainer(val code: Int, val container: JinjaContainer) {
    TEMPLATE_FILE(0, JinjaContainer.TEMPLATE_FILE),
    YAML_TEMPLATE(1, JinjaContainer.YAML_TEMPLATE),
    YAML_EXPRESSION(2, JinjaContainer.YAML_EXPRESSION),
    ;

    companion object {
        fun ofCode(code: Int): UseContainer = entries.firstOrNull { it.code == code } ?: error("Unknown UseContainer code $code")
    }
}

/**
 * One free variable reference in Jinja (an `ansible.var.use` value): [offset] is the file offset of the root name,
 * whose length is the key's length.
 */
data class UseEntry(
    val offset: Int,
    val container: UseContainer,
    /** Directly followed by `is defined`/`is undefined` or `| default`/`| d`. */
    val guarded: Boolean,
    /** Inside a condition that asserts the name is defined (`x is defined and x.y`, `{% if x is defined %}`). */
    val guardedByCondition: Boolean,
    /** Called directly (`lookup(…)`, `range(…)`): a Jinja global rather than a variable, in most cases. */
    val called: Boolean,
    /** Constant accessors after the root name (`item.floating.ssl` → `["floating", "ssl"]`). */
    val attrPath: List<String>,
)

/**
 * `ansible.var.use`: root variable name → its free references (plan A.7). Input: template files (lexed whole, in
 * TEMPLATE mode) and YAML scalars that pass [JinjaBearing.isJinjaBearingScalar], lexed directly with the Jinja lexer
 * (TEMPLATE mode for `{{`/`{%`, EXPRESSION mode for implicit-expression keys), with offsets mapped back through the
 * scalar's own escaper.
 */
class VarUseIndex : FileBasedIndexExtension<String, List<UseEntry>>() {
    override fun getName(): ID<String, List<UseEntry>> = NAME

    override fun getIndexer(): DataIndexer<String, List<UseEntry>, FileContent> =
        DataIndexer { content -> VarUseIndexer.index(IndexInput.of(content)) }

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<List<UseEntry>> = EXTERNALIZER

    override fun getVersion(): Int = VERSION

    override fun getInputFilter(): FileBasedIndex.InputFilter = AnsibleIndexInputFilter(templates = true)

    override fun dependsOnFileContent(): Boolean = true

    companion object {
        @JvmField
        val NAME: ID<String, List<UseEntry>> = ID.create("ansible.var.use")

        const val VERSION: Int = 1
        private const val FORMAT: Int = 1

        private const val GUARDED = 1
        private const val GUARDED_BY_CONDITION = 2
        private const val CALLED = 4

        internal val EXTERNALIZER: DataExternalizer<List<UseEntry>> =
            VersionedListExternalizer(NAME.name, FORMAT, ::writeEntry, ::readEntry)

        private fun writeEntry(out: DataOutput, e: UseEntry) {
            IndexIO.writeInt(out, e.offset)
            out.writeByte(e.container.code)
            val flags = (if (e.guarded) GUARDED else 0) or (if (e.guardedByCondition) GUARDED_BY_CONDITION else 0) or
                (if (e.called) CALLED else 0)
            out.writeByte(flags)
            IndexIO.writeStrings(out, e.attrPath)
        }

        private fun readEntry(input: DataInput): UseEntry {
            val offset = IndexIO.readInt(input)
            val container = UseContainer.ofCode(input.readUnsignedByte())
            val flags = input.readUnsignedByte()
            return UseEntry(
                offset, container,
                guarded = flags and GUARDED != 0,
                guardedByCondition = flags and GUARDED_BY_CONDITION != 0,
                called = flags and CALLED != 0,
                attrPath = IndexIO.readStrings(input),
            )
        }
    }
}

/** The `ansible.var.use` indexer, usable without the index for tests and measurements. */
object VarUseIndexer {
    fun index(input: IndexInput): Map<String, List<UseEntry>> {
        val result = HashMap<String, MutableList<UseEntry>>()
        if (input.isTemplate) {
            collect(input.text, JinjaLexMode.TEMPLATE, UseContainer.TEMPLATE_FILE, result) { it }
            return result
        }
        if (input.facts.hint == PathHint.FILES) return result
        val yaml = input.yaml ?: return result
        val top = YamlPaths.topLevelValue(yaml) ?: return result
        val expressions = JinjaBearing.implicitExpressionOffsets(input.document, input.facts)
        YamlScalars.forEach(top) { scalar, path -> scalar(scalar, path, input.facts, expressions, result) }
        return result
    }

    /** Analyses one scalar value when Ansible templates it; [expressions] are the implicit-expression scalars. */
    private fun scalar(
        scalar: YAMLScalar,
        path: List<String>,
        facts: PathFacts,
        expressions: Set<Int>,
        result: HashMap<String, MutableList<UseEntry>>,
    ) {
        val start = scalar.textRange.startOffset
        val expression = start in expressions
        if (!expression && !JinjaBearing.hasTemplateMarkers(scalar.text)) return
        if (!JinjaBearing.isJinjaBearingScalar(facts, path, YamlPsi.tagOf(scalar))) return
        val escaper = scalar.createLiteralTextEscaper()
        val range = escaper.relevantTextRange
        val decoded = StringBuilder()
        escaper.decode(range, decoded)
        val template = JinjaBearing.hasTemplateMarkers(decoded)
        if (!expression && !template) return
        val mode = if (template) JinjaLexMode.TEMPLATE else JinjaLexMode.EXPRESSION
        val container = if (template) UseContainer.YAML_TEMPLATE else UseContainer.YAML_EXPRESSION
        collect(decoded, mode, container, result) { offset ->
            val inHost = escaper.getOffsetInHost(offset, range)
            if (inHost < 0) -1 else start + inHost
        }
    }

    /** Analyses [text] and adds its free references, mapping text offsets to file offsets with [toFile] (-1 drops). */
    private inline fun collect(
        text: CharSequence,
        mode: JinjaLexMode,
        container: UseContainer,
        result: HashMap<String, MutableList<UseEntry>>,
        toFile: (Int) -> Int,
    ) {
        for (ref in JinjaRefs.analyze(text, mode).references) {
            val offset = toFile(ref.nameRange.startOffset)
            if (offset < 0) continue
            result.getOrPut(ref.name) { ArrayList(2) } +=
                UseEntry(offset, container, ref.guarded, ref.guardedByCondition, ref.called, ref.attrPath)
        }
    }
}
