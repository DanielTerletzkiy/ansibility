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
import java.io.DataInput
import java.io.DataOutput

/** A handler that answers to a name (an `ansible.handler` value). */
data class HandlerEntry(
    /** The `name:` value, or the `listen:` topic. */
    val offset: Int,
    /** True when the key is a `listen` topic rather than the handler's name. */
    val listen: Boolean,
    /** The handler's task mapping. */
    val taskOffset: Int,
)

/**
 * `ansible.handler`: handler name or `listen` topic → handlers (plan A.7). Input: YAML task lists below a `handlers/`
 * directory, plus the `handlers:` sections of plays, since `notify` resolves those too.
 */
class HandlerIndex : FileBasedIndexExtension<String, List<HandlerEntry>>() {
    override fun getName(): ID<String, List<HandlerEntry>> = NAME

    override fun getIndexer(): DataIndexer<String, List<HandlerEntry>, FileContent> =
        DataIndexer { content -> HandlerIndexer.index(IndexInput.of(content)) }

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<List<HandlerEntry>> = EXTERNALIZER

    override fun getVersion(): Int = VERSION

    override fun getInputFilter(): FileBasedIndex.InputFilter = AnsibleIndexInputFilter(templates = false)

    override fun dependsOnFileContent(): Boolean = true

    companion object {
        @JvmField
        val NAME: ID<String, List<HandlerEntry>> = ID.create("ansible.handler")

        const val VERSION: Int = 1
        private const val FORMAT: Int = 1

        internal val EXTERNALIZER: DataExternalizer<List<HandlerEntry>> =
            VersionedListExternalizer(NAME.name, FORMAT, ::writeEntry, ::readEntry)

        private fun writeEntry(out: DataOutput, e: HandlerEntry) {
            IndexIO.writeInt(out, e.offset)
            out.writeBoolean(e.listen)
            IndexIO.writeInt(out, e.taskOffset)
        }

        private fun readEntry(input: DataInput) =
            HandlerEntry(IndexIO.readInt(input), input.readBoolean(), IndexIO.readInt(input))
    }
}

/** The `ansible.handler` indexer, usable without the index for tests and measurements. */
object HandlerIndexer {
    fun index(input: IndexInput): Map<String, List<HandlerEntry>> {
        val document = input.document as? YSeq ?: return emptyMap()
        val result = HashMap<String, MutableList<HandlerEntry>>()
        fun add(node: YScalar, listen: Boolean, taskOffset: Int) {
            val name = node.text.trim()
            val offset = node.range?.start ?: return
            if (name.isNotEmpty()) result.getOrPut(name) { ArrayList(1) } += HandlerEntry(offset, listen, taskOffset)
        }
        TaskWalker(
            object : TaskVisitor {
                override fun task(task: YMap, handlers: Boolean, play: YMap?) {
                    if (!handlers) return
                    val taskOffset = task.range?.start ?: return
                    (task["name"] as? YScalar)?.let { add(it, false, taskOffset) }
                    when (val listen = task["listen"]) {
                        is YScalar -> add(listen, true, taskOffset)
                        is YSeq -> listen.items.forEach { (it as? YScalar)?.let { topic -> add(topic, true, taskOffset) } }
                        else -> Unit
                    }
                }
            },
            handlerFile = input.facts.hint == PathHint.HANDLERS,
        ).walk(document)
        return result
    }
}
