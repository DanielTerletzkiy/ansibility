package de.terletzkiy.ansibility.resolve.loop

import de.terletzkiy.ansibility.model.task.BlockNode
import de.terletzkiy.ansibility.model.task.TaskFileModel
import de.terletzkiy.ansibility.model.task.TaskItem
import de.terletzkiy.ansibility.model.task.TaskNode
import de.terletzkiy.ansibility.semantics.yaml.YMap

/** Positions in the task model: the chain of blocks around an offset, and the `vars:` they define. */
object TaskChains {
    /**
     * The items whose mappings contain [offset], from the outermost block down to the innermost task or block (the
     * sections of a playbook's plays included). Empty when the offset is in no task.
     */
    fun chainAt(model: TaskFileModel, offset: Int): List<TaskItem> {
        val chain = ArrayList<TaskItem>()
        fun descend(items: List<TaskItem>): Boolean {
            val item = items.firstOrNull { it.range.containsOffset(offset) } ?: return false
            chain += item
            if (item is BlockNode) descend(item.block) || descend(item.rescue) || descend(item.always)
            return true
        }
        if (!descend(model.items)) {
            val play = model.playAt(offset) ?: return emptyList()
            play.sections().firstOrNull { descend(it) }
        }
        return chain
    }

    /** The innermost task of [chain], when the chain ends in a task (not in a block's own keys). */
    fun taskOf(chain: List<TaskItem>): TaskNode? = chain.lastOrNull() as? TaskNode

    /** The `vars:` mapping of [item], if it is a literal mapping. */
    fun varsOf(item: TaskItem): YMap? = item.keywords["vars"]?.value as? YMap
}
