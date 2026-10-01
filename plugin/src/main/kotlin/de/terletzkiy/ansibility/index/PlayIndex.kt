package de.terletzkiy.ansibility.index

import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import java.io.DataInput
import java.io.DataOutput

/** How a play applies a role (stable [code]s). */
enum class RoleUseKind(val code: Int) {
    /** An entry of the play's `roles:` list (entry point `main`). */
    ROLES(0),

    /** An `include_role` task in the play's own task sections. */
    INCLUDE_ROLE(1),

    /** An `import_role` task in the play's own task sections. */
    IMPORT_ROLE(2),
    ;

    companion object {
        fun ofCode(code: Int): RoleUseKind = entries.firstOrNull { it.code == code } ?: error("Unknown RoleUseKind code $code")
    }
}

/** A role applied by a play: its name as written, the `tasks_from` entry point (null for `main`) and where. */
data class PlayRoleUse(val name: String, val entryPoint: String?, val offset: Int, val kind: RoleUseKind)

/**
 * One play, or one `import_playbook` entry, of a playbook (an `ansible.play` value). The playbook dir of a play is the
 * directory of the file that defines it (plan A.5), known at query time from the file.
 */
data class PlayEntry(
    val offset: Int,
    val name: String?,
    /** The `hosts:` pattern as written; a list is joined with `,`. Null for an import. */
    val hosts: String?,
    val roles: List<PlayRoleUse>,
    val varsKeys: List<String>,
    val varsFiles: List<String>,
    /** The `import_playbook` target as written, for an import entry. */
    val importPlaybook: String?,
)

/**
 * `ansible.play`: [KEY] → the plays and playbook imports of one file, in file order (plan A.7). Input: YAML files
 * whose top level is a sequence with `hosts:` or `import_playbook` entries.
 */
class PlayIndex : FileBasedIndexExtension<String, List<PlayEntry>>() {
    override fun getName(): ID<String, List<PlayEntry>> = NAME

    override fun getIndexer(): DataIndexer<String, List<PlayEntry>, FileContent> =
        DataIndexer { content -> PlayIndexer.index(IndexInput.of(content)) }

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<List<PlayEntry>> = EXTERNALIZER

    override fun getVersion(): Int = VERSION

    override fun getInputFilter(): FileBasedIndex.InputFilter = AnsibleIndexInputFilter(templates = false)

    override fun dependsOnFileContent(): Boolean = true

    companion object {
        @JvmField
        val NAME: ID<String, List<PlayEntry>> = ID.create("ansible.play")

        /** The single key: every file with plays stores all of them under it. */
        const val KEY: String = "plays"

        const val VERSION: Int = 1
        private const val FORMAT: Int = 1

        internal val EXTERNALIZER: DataExternalizer<List<PlayEntry>> =
            VersionedListExternalizer(NAME.name, FORMAT, ::writeEntry, ::readEntry)

        private fun writeEntry(out: DataOutput, e: PlayEntry) {
            IndexIO.writeInt(out, e.offset)
            IndexIO.writeNullable(out, e.name)
            IndexIO.writeNullable(out, e.hosts)
            IndexIO.writeList(out, e.roles) { o, role ->
                IndexIO.writeString(o, role.name)
                IndexIO.writeNullable(o, role.entryPoint)
                IndexIO.writeInt(o, role.offset)
                o.writeByte(role.kind.code)
            }
            IndexIO.writeStrings(out, e.varsKeys)
            IndexIO.writeStrings(out, e.varsFiles)
            IndexIO.writeNullable(out, e.importPlaybook)
        }

        private fun readEntry(input: DataInput): PlayEntry = PlayEntry(
            offset = IndexIO.readInt(input),
            name = IndexIO.readNullable(input),
            hosts = IndexIO.readNullable(input),
            roles = IndexIO.readList(input) { i ->
                PlayRoleUse(IndexIO.readString(i), IndexIO.readNullable(i), IndexIO.readInt(i), RoleUseKind.ofCode(i.readUnsignedByte()))
            },
            varsKeys = IndexIO.readStrings(input),
            varsFiles = IndexIO.readStrings(input),
            importPlaybook = IndexIO.readNullable(input),
        )
    }
}

/** The `ansible.play` indexer, usable without the index for tests and measurements. */
object PlayIndexer {
    fun index(input: IndexInput): Map<String, List<PlayEntry>> {
        val document = input.document as? YSeq ?: return emptyMap()
        val plays = ArrayList<Builder>()
        TaskWalker(
            object : TaskVisitor {
                override fun play(play: YMap) {
                    plays += Builder(play)
                }

                override fun playbookImport(entry: YMap, key: YScalar, target: YValue) {
                    val offset = entry.range?.start ?: return
                    plays += Builder(null, offset, (entry["name"] as? YScalar)?.text, (target as? YScalar)?.text?.trim())
                }

                override fun roleEntry(entry: YValue, play: YMap) {
                    val node = when (entry) {
                        is YScalar -> entry
                        is YMap -> (entry["role"] ?: entry["name"]) as? YScalar
                        else -> null
                    } ?: return
                    builderOf(play)?.role(node, null, RoleUseKind.ROLES)
                }

                override fun task(task: YMap, handlers: Boolean, play: YMap?) {
                    if (play == null) return
                    val action = TaskKeywords.action(task) ?: return
                    val kind = when (action.name) {
                        in TaskKeywords.INCLUDE_ROLE -> RoleUseKind.INCLUDE_ROLE
                        in TaskKeywords.IMPORT_ROLE -> RoleUseKind.IMPORT_ROLE
                        else -> return
                    }
                    val args = action.argsMap(task)
                    val name = args?.get("name") as? YScalar
                    if (name != null) {
                        builderOf(play)?.role(name, (args["tasks_from"] as? YScalar)?.text?.trim(), kind)
                    }
                }

                private fun builderOf(play: YMap): Builder? = plays.lastOrNull { it.play === play }
            },
        ).walk(document)
        if (plays.isEmpty()) return emptyMap()
        return mapOf(PlayIndex.KEY to plays.mapNotNull { it.build() })
    }

    private class Builder(
        val play: YMap?,
        private val offset: Int? = play?.range?.start,
        private val name: String? = (play?.get("name") as? YScalar)?.text,
        private val importPlaybook: String? = null,
    ) {
        private val roles = ArrayList<PlayRoleUse>()

        fun role(node: YScalar, entryPoint: String?, kind: RoleUseKind) {
            val name = node.text.trim()
            val at = node.range?.start ?: return
            if (name.isNotEmpty()) roles += PlayRoleUse(name, entryPoint, at, kind)
        }

        fun build(): PlayEntry? {
            val at = offset ?: return null
            val hosts = when (val value = play?.get("hosts")) {
                is YScalar -> value.text.trim()
                is YSeq -> value.items.filterIsInstance<YScalar>().joinToString(",") { it.text.trim() }
                else -> null
            }
            val varsKeys = (play?.get("vars") as? YMap)?.let { effectiveEntries(it).map { e -> e.key.text } }.orEmpty()
            val varsFiles = when (val files = play?.get("vars_files")) {
                is YSeq -> files.items.flatMap { item ->
                    when (item) {
                        is YScalar -> listOf(item.text)
                        is YSeq -> item.items.filterIsInstance<YScalar>().map { it.text }
                        else -> emptyList()
                    }
                }
                is YScalar -> listOf(files.text)
                else -> emptyList()
            }
            return PlayEntry(at, name, hosts, roles.toList(), varsKeys, varsFiles, importPlaybook)
        }
    }
}
