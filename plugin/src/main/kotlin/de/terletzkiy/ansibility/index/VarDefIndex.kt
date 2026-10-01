package de.terletzkiy.ansibility.index

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import de.terletzkiy.ansibility.api.ValueShape
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.yaml.YamlPaths
import org.jetbrains.yaml.psi.YAMLKeyValue
import java.io.DataInput
import java.io.DataOutput

/**
 * Where, structurally, a variable gets its value in one file (plan A.7 `defSite`). Derived from the file's path hint
 * and the key's position only; the query-time [de.terletzkiy.ansibility.api.VarDefKind] also depends on the file
 * kind. Codes are stored in the index.
 */
enum class DefSite(val code: Int) {
    /** A top-level option of an `argument_specs` entry point. */
    SPEC_OPTION(1),

    /** A top-level key of a file below `defaults/`. */
    DEFAULTS(2),

    /** A top-level key of a file below `vars/`. */
    VARS(3),

    /** A top-level key of a `group_vars`/`host_vars` file. */
    INVENTORY_KEY(4),

    /** An inline var of a YAML inventory (`<group>.vars.<key>`, `<group>.hosts.<host>.<key>`). */
    INVENTORY_INLINE(5),

    /** A var of a molecule `provisioner.inventory` (`group_vars`, `host_vars` or inline `hosts`). */
    MOLECULE_INVENTORY(6),

    /** A key of a play's `vars:`. */
    PLAY_VARS(7),

    /** The `name` of a play's `vars_prompt` entry. */
    VARS_PROMPT(8),

    /** A key of a block's `vars:`. */
    BLOCK_VARS(9),

    /** A key of a task's `vars:`. */
    TASK_VARS(10),

    /** A key of the `vars:` of an `include_role`/`import_role` task. */
    INCLUDE_PARAMS(11),

    /** A role parameter or `vars:` key of a play's `roles:` entry. */
    ROLE_PARAMS(12),

    /** A key set by `set_fact`. */
    SET_FACT(13),

    /** A `register:` name. */
    REGISTER(14),

    /** A `loop_control.loop_var` name. */
    LOOP_VAR(15),

    /** A `loop_control.index_var` name. */
    INDEX_VAR(16),
    ;

    companion object {
        private val BY_CODE = entries.associateBy { it.code }

        fun ofCode(code: Int): DefSite = BY_CODE[code] ?: error("Unknown DefSite code $code")
    }
}

/**
 * One definition of a variable in one file (an `ansible.var.def` value). [offset] is where the name is written: the
 * key, or the value of `register:`/`loop_var:`/`vars_prompt.name`. [preview] follows [ValueSummary.preview]: never set
 * for `vault_*` names, vault files and `!vault` values, nested secrets masked. [contentHash] changes whenever query-time data derived from the file (the doc comment,
 * the option spec) changes, so the index stamp is a sound cache dependency.
 */
data class DefEntry(
    val site: DefSite,
    val hint: PathHint,
    val offset: Int,
    val shape: ValueShape,
    val literalType: LiteralType,
    val preview: String? = null,
    /** The inventory group or host that owns an inline or molecule inventory var. */
    val owner: String? = null,
    val ownerIsHost: Boolean = false,
    /** The entry point of a [DefSite.SPEC_OPTION]. */
    val entryPoint: String? = null,
    val hasDocComment: Boolean = false,
    val contentHash: Int = 0,
)

/**
 * `ansible.var.def`: variable name → its definitions in the file (plan A.7). Pure per file: only the path hint and
 * the YAML content are read.
 */
class VarDefIndex : FileBasedIndexExtension<String, List<DefEntry>>() {
    override fun getName(): ID<String, List<DefEntry>> = NAME

    override fun getIndexer(): DataIndexer<String, List<DefEntry>, FileContent> =
        DataIndexer { content -> VarDefIndexer.index(IndexInput.of(content)) }

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<List<DefEntry>> = EXTERNALIZER

    override fun getVersion(): Int = VERSION

    override fun getInputFilter(): FileBasedIndex.InputFilter = AnsibleIndexInputFilter(templates = false)

    override fun dependsOnFileContent(): Boolean = true

    companion object {
        @JvmField
        val NAME: ID<String, List<DefEntry>> = ID.create("ansible.var.def")

        /**
         * Bump on any change of the extracted data or of [EXTERNALIZER]'s layout. 2: previews follow the shared
         * [ValueSummary.preview] rule (nested secrets masked, every `vault…` file name hidden).
         */
        const val VERSION: Int = 2
        private const val FORMAT: Int = 1

        internal val EXTERNALIZER: DataExternalizer<List<DefEntry>> =
            VersionedListExternalizer(NAME.name, FORMAT, ::writeEntry, ::readEntry)

        private const val HAS_DOC = 1
        private const val OWNER_IS_HOST = 2

        private fun writeEntry(out: DataOutput, e: DefEntry) {
            out.writeByte(e.site.code)
            out.writeByte(e.hint.code)
            IndexIO.writeInt(out, e.offset)
            out.writeByte(shapeCode(e.shape))
            out.writeByte(e.literalType.code)
            out.writeByte((if (e.hasDocComment) HAS_DOC else 0) or (if (e.ownerIsHost) OWNER_IS_HOST else 0))
            IndexIO.writeNullable(out, e.preview)
            IndexIO.writeNullable(out, e.owner)
            IndexIO.writeNullable(out, e.entryPoint)
            out.writeInt(e.contentHash)
        }

        private fun readEntry(input: DataInput): DefEntry {
            val site = DefSite.ofCode(input.readUnsignedByte())
            val hint = PathHint.ofCode(input.readUnsignedByte())
            val offset = IndexIO.readInt(input)
            val shape = shapeOf(input.readUnsignedByte())
            val literal = LiteralType.ofCode(input.readUnsignedByte())
            val flags = input.readUnsignedByte()
            return DefEntry(
                site, hint, offset, shape, literal,
                preview = IndexIO.readNullable(input),
                owner = IndexIO.readNullable(input),
                ownerIsHost = flags and OWNER_IS_HOST != 0,
                entryPoint = IndexIO.readNullable(input),
                hasDocComment = flags and HAS_DOC != 0,
                contentHash = input.readInt(),
            )
        }

        // Stable codes, independent of the api enum's declaration order.
        private fun shapeCode(shape: ValueShape): Int = when (shape) {
            ValueShape.LITERAL -> 0
            ValueShape.JINJA -> 1
            ValueShape.VAULT -> 2
            ValueShape.NULL -> 3
            ValueShape.CONTAINER -> 4
        }

        private fun shapeOf(code: Int): ValueShape = when (code) {
            0 -> ValueShape.LITERAL
            1 -> ValueShape.JINJA
            2 -> ValueShape.VAULT
            3 -> ValueShape.NULL
            4 -> ValueShape.CONTAINER
            else -> error("Unknown ValueShape code $code")
        }
    }
}

/** The `ansible.var.def` indexer, usable without the index for tests and measurements. */
object VarDefIndexer {
    private const val MAX_INVENTORY_DEPTH = 32

    /** Role keywords of a play's `roles:` entry; every other key is a role parameter. */
    private val ROLE_ENTRY_KEYWORDS: Set<String> = setOf(
        "any_errors_fatal", "become", "become_exe", "become_flags", "become_method", "become_user", "check_mode",
        "collections", "connection", "debugger", "delegate_facts", "delegate_to", "diff", "environment",
        "ignore_errors", "ignore_unreachable", "module_defaults", "name", "no_log", "port", "remote_user", "role",
        "run_once", "tags", "throttle", "timeout", "vars", "when",
    )

    fun index(input: IndexInput): Map<String, List<DefEntry>> {
        if (input.isTemplate || input.facts.hint == PathHint.FILES) return emptyMap()
        val document = input.document ?: return emptyMap()
        val sink = Sink(input)
        when (input.facts.hint) {
            PathHint.ARGUMENT_SPECS, PathHint.ROLE_META -> specOptions(document, sink)
            PathHint.DEFAULTS -> sink.topLevel(document, DefSite.DEFAULTS)
            PathHint.VARS -> sink.topLevel(document, DefSite.VARS)
            PathHint.GROUP_VARS, PathHint.HOST_VARS -> sink.topLevel(document, DefSite.INVENTORY_KEY)
            PathHint.INVENTORY -> (document as? YMap)?.let { inventory(it, DefSite.INVENTORY_INLINE, sink) }
            PathHint.MOLECULE_CONFIG -> moleculeInventory(document, sink)
            PathHint.TASKS, PathHint.HANDLERS, PathHint.OTHER ->
                TaskWalker(TaskDefs(sink), handlerFile = input.facts.hint == PathHint.HANDLERS).walk(document)
            PathHint.TEMPLATE, PathHint.FILES -> Unit
        }
        return sink.result
    }

    private fun specOptions(document: YValue, sink: Sink) {
        val entryPoints = (document as? YMap)?.get("argument_specs") as? YMap ?: return
        for (entryPoint in effectiveEntries(entryPoints)) {
            val options = (entryPoint.value as? YMap)?.get("options") as? YMap ?: continue
            for (option in effectiveEntries(options)) {
                ProgressManager.checkCanceled()
                val range = option.value.range
                val specHash = if (range != null) sink.input.text.subSequence(range.start, range.end).toString().hashCode() else 0
                sink.key(
                    option.key, option.value, DefSite.SPEC_OPTION,
                    entryPoint = entryPoint.key.text, extraHash = specHash, withValue = false,
                )
            }
        }
    }

    /** A YAML inventory: every top-level key is a group; `vars`, `hosts` and `children` nest to any depth. */
    private fun inventory(groups: YMap, site: DefSite, sink: Sink) {
        for (group in effectiveEntries(groups)) inventoryGroup(group.key.text, group.value, site, sink, 0)
    }

    private fun inventoryGroup(group: String, value: YValue, site: DefSite, sink: Sink, depth: Int) {
        if (depth > MAX_INVENTORY_DEPTH) return
        val map = value as? YMap ?: return
        ProgressManager.checkCanceled()
        (map["vars"] as? YMap)?.let { vars ->
            effectiveEntries(vars).forEach { sink.key(it.key, it.value, site, owner = group) }
        }
        (map["hosts"] as? YMap)?.let { hosts ->
            for (host in effectiveEntries(hosts)) {
                val vars = host.value as? YMap ?: continue
                effectiveEntries(vars).forEach { sink.key(it.key, it.value, site, owner = host.key.text, ownerIsHost = true) }
            }
        }
        (map["children"] as? YMap)?.let { children ->
            effectiveEntries(children).forEach { inventoryGroup(it.key.text, it.value, site, sink, depth + 1) }
        }
    }

    private fun moleculeInventory(document: YValue, sink: Sink) {
        val inventory = ((document as? YMap)?.get("provisioner") as? YMap)?.get("inventory") as? YMap ?: return
        for ((section, isHost) in listOf("group_vars" to false, "host_vars" to true)) {
            val owners = inventory[section] as? YMap ?: continue
            for (owner in effectiveEntries(owners)) {
                val vars = owner.value as? YMap ?: continue
                effectiveEntries(vars).forEach {
                    sink.key(it.key, it.value, DefSite.MOLECULE_INVENTORY, owner = owner.key.text, ownerIsHost = isHost)
                }
            }
        }
        (inventory["hosts"] as? YMap)?.let { inventory(it, DefSite.MOLECULE_INVENTORY, sink) }
    }

    /** Definitions made by plays, blocks and tasks. */
    private class TaskDefs(private val sink: Sink) : TaskVisitor {
        override fun play(play: YMap) {
            sink.mapping(play["vars"], DefSite.PLAY_VARS)
            (play["vars_prompt"] as? YSeq)?.items?.forEach { prompt ->
                val name = (prompt as? YMap)?.get("name") as? YScalar ?: return@forEach
                sink.name(name, DefSite.VARS_PROMPT, ValueShape.LITERAL, LiteralType.STR)
            }
        }

        override fun roleEntry(entry: YValue, play: YMap) {
            val map = entry as? YMap ?: return
            sink.mapping(map["vars"], DefSite.ROLE_PARAMS)
            effectiveEntries(map).filter { it.key.text !in ROLE_ENTRY_KEYWORDS }
                .forEach { sink.key(it.key, it.value, DefSite.ROLE_PARAMS) }
        }

        override fun block(block: YMap, handlers: Boolean, play: YMap?) {
            sink.mapping(block["vars"], DefSite.BLOCK_VARS)
        }

        override fun task(task: YMap, handlers: Boolean, play: YMap?) {
            val action = TaskKeywords.action(task)
            val includesRole = action != null && (action.name in TaskKeywords.INCLUDE_ROLE || action.name in TaskKeywords.IMPORT_ROLE)
            sink.mapping(task["vars"], if (includesRole) DefSite.INCLUDE_PARAMS else DefSite.TASK_VARS)
            if (action != null && action.name in TaskKeywords.SET_FACT) setFact(task, action)
            (task["register"] as? YScalar)?.let { sink.name(it, DefSite.REGISTER, ValueShape.CONTAINER, LiteralType.NONE) }
            (task["loop_control"] as? YMap)?.let { control ->
                (control["loop_var"] as? YScalar)?.let { sink.name(it, DefSite.LOOP_VAR, ValueShape.JINJA, LiteralType.NONE) }
                (control["index_var"] as? YScalar)?.let { sink.name(it, DefSite.INDEX_VAR, ValueShape.LITERAL, LiteralType.INT) }
            }
        }

        private fun setFact(task: YMap, action: TaskAction) {
            action.argsMap(task)?.let { args ->
                effectiveEntries(args).filter { it.key.text != "cacheable" }.forEach { sink.key(it.key, it.value, DefSite.SET_FACT) }
            }
            val freeForm = action.freeForm() ?: return
            val node = action.args as? YScalar ?: return
            for (name in parseKeyValueArgs(freeForm).keys) {
                if (name == "cacheable") continue
                sink.nameAt(name, node, DefSite.SET_FACT)
            }
        }
    }

    /** Collects entries; owns the preview, doc comment and hashing rules. */
    private class Sink(val input: IndexInput) {
        val result = HashMap<String, MutableList<DefEntry>>()

        fun topLevel(document: YValue, site: DefSite) {
            val map = document as? YMap ?: return
            for (entry in effectiveEntries(map)) {
                ProgressManager.checkCanceled()
                key(entry.key, entry.value, site)
            }
        }

        fun mapping(value: YValue?, site: DefSite) {
            val map = value as? YMap ?: return
            effectiveEntries(map).forEach { key(it.key, it.value, site) }
        }

        fun key(
            key: YScalar,
            value: YValue,
            site: DefSite,
            owner: String? = null,
            ownerIsHost: Boolean = false,
            entryPoint: String? = null,
            extraHash: Int = 0,
            withValue: Boolean = true,
        ) {
            val name = key.text
            val offset = key.range?.start ?: return
            if (name.isEmpty()) return
            val doc = docCommentAt(offset)
            add(
                name,
                DefEntry(
                    site = site,
                    hint = input.facts.hint,
                    offset = offset,
                    shape = if (withValue) ValueSummary.shape(value) else ValueShape.CONTAINER,
                    literalType = if (withValue) ValueSummary.literalType(value) else LiteralType.NONE,
                    preview = if (withValue) ValueSummary.preview(name, value, input.facts.fileName) else null,
                    owner = owner,
                    ownerIsHost = ownerIsHost,
                    entryPoint = entryPoint,
                    hasDocComment = doc != null,
                    contentHash = 31 * (doc?.hashCode() ?: 0) + extraHash,
                ),
            )
        }

        /** A name written as a scalar value (`register: result`). */
        fun name(node: YScalar, site: DefSite, shape: ValueShape, literalType: LiteralType) {
            val name = node.text.trim()
            val offset = node.range?.start ?: return
            if (name.isEmpty() || JinjaBearing.hasTemplateMarkers(name)) return
            add(name, DefEntry(site, input.facts.hint, offset, shape, literalType))
        }

        /** A name inside a free-form argument string; [node] is the string, the offset points at the name if found. */
        fun nameAt(name: String, node: YScalar, site: DefSite) {
            val range = node.range ?: return
            val inSource = node.sourceText.indexOf("$name=")
            val offset = range.end - node.sourceText.length + maxOf(inSource, 0)
            add(name, DefEntry(site, input.facts.hint, offset, ValueShape.LITERAL, LiteralType.NONE))
        }

        private fun add(name: String, entry: DefEntry) {
            result.getOrPut(name) { ArrayList(1) } += entry
        }

        /** The comment block above the key written at [offset], found through the YAML PSI. */
        private fun docCommentAt(offset: Int): String? {
            val file = input.yaml ?: return null
            val keyValue = PsiTreeUtil.getParentOfType(file.findElementAt(offset), YAMLKeyValue::class.java, false) ?: return null
            if (keyValue.key?.textRange?.startOffset != offset) return null
            return YamlPaths.docCommentAbove(keyValue)
        }
    }
}
