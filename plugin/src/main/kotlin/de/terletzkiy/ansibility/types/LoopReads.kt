package de.terletzkiy.ansibility.types

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.util.indexing.FileBasedIndex
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.index.VarUseIndex
import de.terletzkiy.ansibility.model.task.TaskFileModels
import de.terletzkiy.ansibility.resolve.VarUsageQuery
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import org.jetbrains.yaml.psi.YAMLFile

/**
 * 🟣 CLAUDE X79: what the tasks that loop over a variable read from its items.
 *
 * @property attributes the first attribute after the loop variable (`name` for `item.name`), in first-seen order
 * @property files the files of the looping tasks that read an attribute, in path order
 */
internal data class LoopReads(val attributes: List<String>, val files: List<VirtualFile>) {
    /** These reads with the attributes that are keys of [item] first, in its key order, then the others by name. */
    fun orderedLike(item: YMap): LoopReads {
        val keys = item.keys
        return copy(attributes = attributes.sortedWith(compareBy<String>({ if (it in keys) keys.indexOf(it) else keys.size }, { it })))
    }

    companion object {
        val NONE = LoopReads(emptyList(), emptyList())

        /**
         * The attributes that tasks of [root] read from the items of [name]: every task whose `loop:`/`with_<lookup>:`
         * is the bare `{{ name }}` (optionally through element-preserving filters such as `| list` or `| default([])`),
         * and every use of its loop variable (`item`, or `loop_control.loop_var`) inside that task, from the
         * `ansible.var.use` index. Templates rendered by those tasks are not followed. Call in a read action in smart mode.
         */
        fun of(project: Project, root: AnsibleRoot, name: String): LoopReads {
            val usages = VarUsageQuery.getInstance(project).usages(root, name).filter { it.attrPath.isEmpty() && !it.called }
            if (usages.isEmpty()) return NONE
            val source = Regex("""^\s*\{\{-?\s*${Regex.escape(name)}\s*(\|\s*(list|sort|unique|reverse|default\([^)]*\)|d\([^)]*\))\s*)*-?}}\s*$""")
            val attributes = LinkedHashSet<String>()
            val files = LinkedHashSet<VirtualFile>()
            val psiManager = PsiManager.getInstance(project)
            for ((file, inFile) in usages.groupBy { it.location.file }) {
                ProgressManager.checkCanceled()
                val yaml = psiManager.findFile(file) as? YAMLFile ?: continue
                val loopingTasks = TaskFileModels.of(yaml).tasks().filter { task ->
                    val loop = task.loop?.value as? YScalar ?: return@filter false
                    val range = loop.range ?: return@filter false
                    source.matches(loop.text) && inFile.any { it.location.offset in range.start until range.end }
                }
                if (loopingTasks.isEmpty()) continue
                val uses = FileBasedIndex.getInstance().getFileData(VarUseIndex.NAME, file, project)
                for (task in loopingTasks) {
                    val loopVar = task.loopVar ?: continue
                    val read = uses[loopVar].orEmpty()
                        .filter { task.range.containsOffset(it.offset) }
                        .mapNotNull { it.attrPath.firstOrNull() }
                    if (read.isNotEmpty()) {
                        attributes += read
                        files += file
                    }
                }
            }
            return LoopReads(attributes.toList(), files.sortedBy { it.path })
        }
    }
}
