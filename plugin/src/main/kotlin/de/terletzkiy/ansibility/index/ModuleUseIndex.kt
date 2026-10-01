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

/**
 * `ansible.module.use`: module name as written (`ansible.builtin.template`, `template`) → offsets of the action key
 * (plan A.7). Input: YAML files whose top level is a sequence (task files, handlers, playbooks). Routing to the
 * canonical module happens at query time.
 */
class ModuleUseIndex : FileBasedIndexExtension<String, List<Int>>() {
    override fun getName(): ID<String, List<Int>> = NAME

    override fun getIndexer(): DataIndexer<String, List<Int>, FileContent> =
        DataIndexer { content -> ModuleUseIndexer.index(IndexInput.of(content)) }

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<List<Int>> = EXTERNALIZER

    override fun getVersion(): Int = VERSION

    override fun getInputFilter(): FileBasedIndex.InputFilter = AnsibleIndexInputFilter(templates = false)

    override fun dependsOnFileContent(): Boolean = true

    companion object {
        @JvmField
        val NAME: ID<String, List<Int>> = ID.create("ansible.module.use")

        const val VERSION: Int = 1
        private const val FORMAT: Int = 1

        internal val EXTERNALIZER: DataExternalizer<List<Int>> =
            VersionedListExternalizer(NAME.name, FORMAT, { out, offset -> IndexIO.writeInt(out, offset) }, IndexIO::readInt)
    }
}

/** The `ansible.module.use` indexer, usable without the index for tests and measurements. */
object ModuleUseIndexer {
    /** A module name: an identifier or a dotted FQCN. */
    private val MODULE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")

    fun index(input: IndexInput): Map<String, List<Int>> {
        val document = input.document as? YSeq ?: return emptyMap()
        val result = HashMap<String, MutableList<Int>>()
        fun add(name: String, offset: Int?) {
            if (offset != null && MODULE_NAME.matches(name)) result.getOrPut(name) { ArrayList(1) } += offset
        }
        TaskWalker(
            object : TaskVisitor {
                override fun task(task: YMap, handlers: Boolean, play: YMap?) {
                    val action = TaskKeywords.action(task) ?: return
                    add(action.name, action.nameNode.range?.start)
                }

                override fun playbookImport(entry: YMap, key: YScalar, target: YValue) = add(key.text, key.range?.start)
            },
            handlerFile = input.facts.hint == PathHint.HANDLERS,
        ).walk(document)
        return result
    }
}
