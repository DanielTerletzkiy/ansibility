package de.terletzkiy.ansibility.index

import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaRefs
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.yaml.YamlPaths
import de.terletzkiy.ansibility.yaml.YamlPsi
import java.io.DataInput
import java.io.DataOutput

/** How a template is named (plan A.7 `srcKind`; stable [code]s). */
enum class SrcKind(val code: Int) {
    /** A literal path: `src: templates/haproxy.cfg.j2`. */
    STATIC(0),

    /** A literal prefix followed by Jinja: `src: "templates/nginx/{{ item.floating.template }}"`. */
    DYNAMIC_PREFIX(1),

    /** One whole-value expression: `src: "{{ iptables_rules_file_ipv4 }}"`. */
    WHOLE_VAR(2),

    /** `src: "{{ item }}"` over a `fileglob` loop (`with_fileglob`, `lookup('fileglob', …)`). */
    FILEGLOB(3),

    /** `lookup('template', 'x.j2', …)` (also `query`/`q`), in YAML or in a template. */
    LOOKUP(4),

    /** `{% include %}`, `{% import %}`, `{% from … import %}` or `{% extends %}` in a template. */
    INCLUDE(5),
    ;

    companion object {
        fun ofCode(code: Int): SrcKind = entries.firstOrNull { it.code == code } ?: error("Unknown SrcKind code $code")
    }
}

/**
 * One place that renders a template (an `ansible.template.use` value, keyed by the literal `src` text as written).
 * Resolution against `templates/` directories (Ansible's `_find_needle`) happens at query time.
 */
data class RenderEntry(
    /** The task mapping, or the scalar or template tag holding a lookup or include. */
    val taskOffset: Int,
    /** The `src` value or the path literal. */
    val srcOffset: Int,
    val srcKind: SrcKind,
    /** The loop expression of the rendering task (`loop:` or `with_<lookup>:` value as written), when it loops. */
    val loopExprText: String? = null,
    /** `loop_control.loop_var`, or `item` when the task loops; null otherwise. */
    val loopVar: String? = null,
    /** The variable path of the first expression in a dynamic `src` (`["item", "floating", "template"]`). */
    val dynamicVarPath: List<String> = emptyList(),
    /** The glob of a [SrcKind.FILEGLOB] render (`/templates/config-*.alloy.j2`), when it is a literal. */
    val globPattern: String? = null,
)

/** `ansible.template.use`: literal template name → render sites (plan A.7). */
class TemplateUseIndex : FileBasedIndexExtension<String, List<RenderEntry>>() {
    override fun getName(): ID<String, List<RenderEntry>> = NAME

    override fun getIndexer(): DataIndexer<String, List<RenderEntry>, FileContent> =
        DataIndexer { content -> TemplateUseIndexer.index(IndexInput.of(content)) }

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<List<RenderEntry>> = EXTERNALIZER

    override fun getVersion(): Int = VERSION

    override fun getInputFilter(): FileBasedIndex.InputFilter = AnsibleIndexInputFilter(templates = true)

    override fun dependsOnFileContent(): Boolean = true

    companion object {
        @JvmField
        val NAME: ID<String, List<RenderEntry>> = ID.create("ansible.template.use")

        const val VERSION: Int = 1
        private const val FORMAT: Int = 1

        internal val EXTERNALIZER: DataExternalizer<List<RenderEntry>> =
            VersionedListExternalizer(NAME.name, FORMAT, ::writeEntry, ::readEntry)

        private fun writeEntry(out: DataOutput, e: RenderEntry) {
            IndexIO.writeInt(out, e.taskOffset)
            IndexIO.writeInt(out, e.srcOffset)
            out.writeByte(e.srcKind.code)
            IndexIO.writeNullable(out, e.loopExprText)
            IndexIO.writeNullable(out, e.loopVar)
            IndexIO.writeStrings(out, e.dynamicVarPath)
            IndexIO.writeNullable(out, e.globPattern)
        }

        private fun readEntry(input: DataInput): RenderEntry = RenderEntry(
            taskOffset = IndexIO.readInt(input),
            srcOffset = IndexIO.readInt(input),
            srcKind = SrcKind.ofCode(input.readUnsignedByte()),
            loopExprText = IndexIO.readNullable(input),
            loopVar = IndexIO.readNullable(input),
            dynamicVarPath = IndexIO.readStrings(input),
            globPattern = IndexIO.readNullable(input),
        )
    }
}

/** The `ansible.template.use` indexer, usable without the index for tests and measurements. */
object TemplateUseIndexer {
    /** `lookup('template', 'path'…)`, `query(…)`, `q(…)`; quotes may be backslash-escaped inside double-quoted YAML. */
    private val LOOKUP = Regex(
        """\b(?:lookup|query|q)\s*\(\s*\\?(['"])(?:ansible\.builtin\.|ansible\.legacy\.)?template\\?\1\s*,\s*\\?(['"])([^'"\\]+)\\?\2""",
    )

    /** `{% include 'x' %}`, `{% import 'x' as m %}`, `{% from 'x' import y %}`, `{% extends 'x' %}`. */
    private val INCLUDE = Regex("""\{%[-+]?\s*(?:include|import|from|extends)\s+(['"])([^'"]+)\1""")

    /** A literal glob inside a fileglob loop. */
    private val GLOB_LITERAL = Regex("""(['"])([^'"]*[*?][^'"]*)\1""")

    fun index(input: IndexInput): Map<String, List<RenderEntry>> {
        val result = HashMap<String, MutableList<RenderEntry>>()
        if (input.isTemplate) {
            lookups(input.text, 0, 0, result)
            for (match in INCLUDE.findAll(input.text)) {
                val path = match.groups[2]!!
                add(result, path.value, RenderEntry(match.range.first, path.range.first, SrcKind.INCLUDE))
            }
            return result
        }
        if (input.facts.hint == PathHint.FILES) return result
        val yaml = input.yaml ?: return result
        YamlPaths.topLevelValue(yaml)?.let { top ->
            YamlScalars.forEach(top) { scalar, path ->
                val text = scalar.text
                if (!text.contains("template") || !JinjaBearing.hasTemplateMarkers(text)) return@forEach
                if (!JinjaBearing.isJinjaBearingScalar(input.facts, path, YamlPsi.tagOf(scalar))) return@forEach
                val start = scalar.textRange.startOffset
                lookups(text, start, start, result)
            }
        }
        TaskWalker(TemplateTasks(input.text, result), handlerFile = input.facts.hint == PathHint.HANDLERS).walk(input.document)
        return result
    }

    private fun lookups(text: CharSequence, base: Int, taskOffset: Int, result: HashMap<String, MutableList<RenderEntry>>) {
        if (!text.contains("template")) return
        for (match in LOOKUP.findAll(text)) {
            val path = match.groups[3]!!
            add(result, path.value, RenderEntry(taskOffset, base + path.range.first, SrcKind.LOOKUP))
        }
    }

    private class TemplateTasks(
        private val text: CharSequence,
        private val result: HashMap<String, MutableList<RenderEntry>>,
    ) : TaskVisitor {
        override fun task(task: YMap, handlers: Boolean, play: YMap?) {
            val action = TaskKeywords.action(task) ?: return
            if (action.name !in TaskKeywords.TEMPLATE) return
            val srcNode = action.argsMap(task)?.get("src") as? YScalar
            val src = srcNode?.text ?: action.freeForm()?.let { parseKeyValueArgs(it)["src"] } ?: return
            if (src.isBlank()) return
            val srcOffset = srcNode?.range?.start ?: action.args.range?.start ?: return
            val taskOffset = task.range?.start ?: srcOffset
            val loop = loopOf(task)
            val loopVar = loop?.let { ((task["loop_control"] as? YMap)?.get("loop_var") as? YScalar)?.text?.trim() ?: "item" }
            val refs = if (JinjaBearing.hasTemplateMarkers(src)) JinjaRefs.analyze(src).references else emptyList()
            val firstPath = refs.firstOrNull()?.let { listOf(it.name) + it.attrPath }.orEmpty()
            val wholeVar = isWholeExpression(src)
            val kind = when {
                refs.isEmpty() && !JinjaBearing.hasTemplateMarkers(src) -> SrcKind.STATIC
                wholeVar && loop != null && loop.isFileglob && refs.singleOrNull()?.name == loopVar -> SrcKind.FILEGLOB
                wholeVar -> SrcKind.WHOLE_VAR
                else -> SrcKind.DYNAMIC_PREFIX
            }
            val glob = if (kind == SrcKind.FILEGLOB) loop?.text?.let { GLOB_LITERAL.find(it)?.groups?.get(2)?.value } else null
            add(result, src, RenderEntry(taskOffset, srcOffset, kind, loop?.text, loopVar, firstPath, glob))
        }

        /** The task's loop: `loop:` or the first `with_<lookup>:` key. */
        private fun loopOf(task: YMap): Loop? {
            task.entries.lastOrNull { it.key.text == "loop" }?.let { return Loop(textOf(it.value), false) }
            val with = task.entries.firstOrNull { it.key.text.startsWith("with_") } ?: return null
            return Loop(textOf(with.value), with.key.text == "with_fileglob")
        }

        private fun textOf(value: YValue): String = when (value) {
            is YScalar -> value.text
            else -> value.range?.let { text.subSequence(it.start, it.end).toString() }.orEmpty()
        }

        private class Loop(val text: String, withFileglob: Boolean) {
            val isFileglob: Boolean = withFileglob || text.contains("fileglob")
        }
    }

    /** `{{ expr }}` and nothing else (surrounding blanks allowed). */
    private fun isWholeExpression(src: String): Boolean {
        val trimmed = src.trim()
        return trimmed.startsWith("{{") && trimmed.endsWith("}}") && trimmed.indexOf("{{", 2) < 0 && !trimmed.contains("{%")
    }

    private fun add(result: HashMap<String, MutableList<RenderEntry>>, key: String, entry: RenderEntry) {
        result.getOrPut(key) { ArrayList(1) } += entry
    }
}
