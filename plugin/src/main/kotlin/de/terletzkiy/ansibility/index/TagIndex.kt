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
 * `ansible.tag`: tag name → offsets of the scalar that carries it, in the `tags:` of plays, role entries, blocks and
 * tasks (plan X70). `tags: a, b` yields both names at the scalar's offset; templated tags are skipped.
 */
class TagIndex : FileBasedIndexExtension<String, List<Int>>() {
    override fun getName(): ID<String, List<Int>> = NAME

    override fun getIndexer(): DataIndexer<String, List<Int>, FileContent> =
        DataIndexer { content -> TagIndexer.index(IndexInput.of(content)) }

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<List<Int>> = EXTERNALIZER

    override fun getVersion(): Int = VERSION

    override fun getInputFilter(): FileBasedIndex.InputFilter = AnsibleIndexInputFilter(templates = false)

    override fun dependsOnFileContent(): Boolean = true

    companion object {
        @JvmField
        val NAME: ID<String, List<Int>> = ID.create("ansible.tag")

        const val VERSION: Int = 1
        private const val FORMAT: Int = 1

        internal val EXTERNALIZER: DataExternalizer<List<Int>> =
            VersionedListExternalizer(NAME.name, FORMAT, { out, offset -> IndexIO.writeInt(out, offset) }, IndexIO::readInt)
    }
}

/** The `ansible.tag` indexer, usable without the index for tests. */
object TagIndexer {
    fun index(input: IndexInput): Map<String, List<Int>> {
        val document = input.document as? YSeq ?: return emptyMap()
        val result = HashMap<String, MutableList<Int>>()
        fun add(owner: YMap) {
            val tags = owner["tags"] ?: return
            val scalars = when (tags) {
                is YScalar -> listOf(tags)
                is YSeq -> tags.items.filterIsInstance<YScalar>()
                else -> emptyList()
            }
            for (scalar in scalars) {
                val offset = scalar.range?.start ?: continue
                for (name in names(scalar.text)) result.getOrPut(name) { ArrayList(1) } += offset
            }
        }
        TaskWalker(
            object : TaskVisitor {
                override fun play(play: YMap) = add(play)

                override fun roleEntry(entry: YValue, play: YMap) {
                    (entry as? YMap)?.let(::add)
                }

                override fun block(block: YMap, handlers: Boolean, play: YMap?) = add(block)

                override fun task(task: YMap, handlers: Boolean, play: YMap?) = add(task)
            },
            handlerFile = input.facts.hint == PathHint.HANDLERS,
        ).walk(document)
        return result
    }

    /** The tag names of one `tags:` scalar: comma-separated, trimmed, without templated parts. */
    fun names(text: String): List<String> =
        text.split(',').map { it.trim() }.filter { it.isNotEmpty() && "{{" !in it && "{%" !in it }
}
