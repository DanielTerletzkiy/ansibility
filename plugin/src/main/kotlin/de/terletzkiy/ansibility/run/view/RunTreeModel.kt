package de.terletzkiy.ansibility.run.view

import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import de.terletzkiy.ansibility.run.AnsibilityRunBundle.message
import de.terletzkiy.ansibility.run.events.HostRun
import de.terletzkiy.ansibility.run.events.HostStatus
import de.terletzkiy.ansibility.run.events.ItemRun
import de.terletzkiy.ansibility.run.events.PlayRun
import de.terletzkiy.ansibility.run.events.RunModel
import de.terletzkiy.ansibility.run.events.StageRun
import de.terletzkiy.ansibility.run.events.TaskRun
import de.terletzkiy.ansibility.run.events.UnitRun
import java.util.IdentityHashMap
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/** The recap node at the end of the tree, once the run reported its `PLAY RECAP`. */
object RecapNode

/**
 * The tree of a run: plays → tasks → hosts → loop items, then the recap. [sync] updates it in place from the
 * [RunModel] (the model's objects are the nodes' user objects, so selection and expansion survive), adding what is new,
 * dropping what the filters hide and repainting the rest. EDT.
 */
class RunTreeModel(private val model: RunModel) {
    val root = DefaultMutableTreeNode()
    val treeModel = DefaultTreeModel(root)
    private val nodes = IdentityHashMap<Any, DefaultMutableTreeNode>()

    var showOk: Boolean = true
    var showSkipped: Boolean = true

    fun node(value: Any): DefaultMutableTreeNode? = nodes[value]

    /** Brings the tree up to the model; returns the nodes it created, in order. */
    fun sync(): List<DefaultMutableTreeNode> {
        val created = ArrayList<DefaultMutableTreeNode>()
        if (model.units.isNotEmpty()) {
            // A batch: its units (roles), each with the plays outside a stage and its stages with their plays.
            children(root, model.units, created)
            for (unit in model.units) {
                val unitNode = nodes[unit] ?: continue
                children(unitNode, unit.plays + unit.stages, created)
                for (stage in unit.stages) children(nodes[stage]?.takeIf { it.parent === unitNode } ?: continue, stage.plays, created)
            }
        } else if (model.stages.isNotEmpty()) {
            // Molecule: the stages with their plays (each stage's playbook has its own recap, so the run shows none).
            val unstaged = model.plays.filter { play -> model.stages.none { stage -> stage.plays.any { it === play } } }
            children(root, unstaged + model.stages, created)
            for (stage in model.stages) children(nodes[stage] ?: continue, stage.plays, created)
        } else {
            children(root, model.plays + if (model.stats.isNotEmpty()) listOf(RecapNode) else emptyList(), created)
        }
        for (play in model.plays) {
            val playNode = nodes[play] ?: continue
            children(playNode, play.tasks.filter(::visible), created)
            for (task in play.tasks) {
                val taskNode = nodes[task]?.takeIf { it.parent === playNode } ?: continue
                children(taskNode, task.hosts.values.filter { passes(it.status) }, created)
                for (host in task.hosts.values) {
                    val hostNode = nodes[host]?.takeIf { it.parent === taskNode } ?: continue
                    children(hostNode, host.items.filter { passes(it.status) }, created)
                }
            }
        }
        return created
    }

    /** The task of a host or item node. */
    fun taskOf(node: DefaultMutableTreeNode): TaskRun? = generateSequence(node) { it.parent as? DefaultMutableTreeNode }
        .map { it.userObject }.filterIsInstance<TaskRun>().firstOrNull()

    fun playOf(node: DefaultMutableTreeNode): PlayRun? = generateSequence(node) { it.parent as? DefaultMutableTreeNode }
        .map { it.userObject }.filterIsInstance<PlayRun>().firstOrNull()

    fun hostOf(node: DefaultMutableTreeNode): HostRun? = generateSequence(node) { it.parent as? DefaultMutableTreeNode }
        .map { it.userObject }.filterIsInstance<HostRun>().firstOrNull()

    /** Whether hosts and items of [status] are shown. */
    fun passes(status: HostStatus): Boolean = !(status == HostStatus.OK && !showOk) && !(status == HostStatus.SKIPPED && !showSkipped)

    /** A task stays while it runs or until its hosts report, and when a host of it passes the filters. */
    private fun visible(task: TaskRun): Boolean =
        task.hosts.isEmpty() || task.status == HostStatus.RUNNING || task.hosts.values.any { passes(it.status) }

    private fun children(parent: DefaultMutableTreeNode, wanted: List<Any>, created: MutableList<DefaultMutableTreeNode>) {
        val keep = java.util.Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()).apply { addAll(wanted) }
        for (index in parent.childCount - 1 downTo 0) {
            val child = parent.getChildAt(index) as DefaultMutableTreeNode
            if (child.userObject !in keep) {
                treeModel.removeNodeFromParent(child)
                forget(child)
            }
        }
        wanted.forEachIndexed { index, value ->
            val existing = nodes[value]
            if (existing != null && existing.parent === parent) {
                if (parent.getIndex(existing) != index) {
                    treeModel.removeNodeFromParent(existing)
                    treeModel.insertNodeInto(existing, parent, index)
                }
                treeModel.nodeChanged(existing)
            } else {
                val node = DefaultMutableTreeNode(value)
                nodes[value] = node
                treeModel.insertNodeInto(node, parent, index)
                created += node
            }
        }
    }

    private fun forget(node: DefaultMutableTreeNode) {
        nodes.remove(node.userObject)
        for (index in 0 until node.childCount) forget(node.getChildAt(index) as DefaultMutableTreeNode)
    }
}

/** Status icon, name and a grey summary for every node of the run tree. */
class RunTreeRenderer(private val now: () -> Double, private val finished: () -> Boolean = { false }) : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
        val node = value as? DefaultMutableTreeNode ?: return
        when (val item = node.userObject) {
            is UnitRun -> {
                icon = RunViewTexts.unitIcon(item, finished())
                append(item.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append("  " + item.label, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                val started = item.started
                val details = listOfNotNull(
                    RunViewTexts.unitStatus(item, finished()).takeIf { RunViewTexts.showsUnitStatus(item) },
                    RunViewTexts.tally(item.counts).takeIf { it.isNotEmpty() },
                    started?.let { RunViewTexts.duration((item.ended ?: now()) - it) }?.takeIf { it.isNotEmpty() },
                )
                if (details.isNotEmpty()) append("   " + details.joinToString(" \u00b7 "), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            is StageRun -> {
                icon = RunViewTexts.icon(item.status)
                append(message("run.view.stage", item.scenario, item.action), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                val details = listOfNotNull(
                    item.result?.takeUnless { it.startsWith("Successful", ignoreCase = true) },
                    RunViewTexts.tally(item.counts).takeIf { it.isNotEmpty() },
                    RunViewTexts.duration((item.ended ?: now()) - item.started).takeIf { it.isNotEmpty() },
                )
                if (details.isNotEmpty()) append("   " + details.joinToString(" \u00b7 "), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            is PlayRun -> {
                icon = RunViewTexts.icon(item.status)
                append(item.name.ifEmpty { message("run.view.play.unnamed") }, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                val details = listOfNotNull(
                    RunViewTexts.shares(item.counts).takeIf { it.isNotEmpty() },
                    RunViewTexts.duration(item.duration(now())).takeIf { it.isNotEmpty() },
                    message("run.view.play.batches", item.batches).takeIf { item.batches > 1 },
                    message("run.view.play.no.hosts").takeIf { item.noHostsMatched },
                )
                if (details.isNotEmpty()) append("   " + details.joinToString(" · "), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            is TaskRun -> {
                icon = RunViewTexts.icon(item.status)
                if (item.role != null) append(item.role + " › ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append(item.shortName)
                if (item.handler) append("  " + message("run.view.task.handler"), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                val duration = item.ended?.let { RunViewTexts.duration(it - item.started) }.orEmpty()
                val summary = listOf(RunViewTexts.tally(item.counts), duration).filter { it.isNotEmpty() }.joinToString(" · ")
                if (summary.isNotEmpty()) append("   $summary", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            is HostRun -> {
                icon = RunViewTexts.icon(item.status)
                append(item.host)
                val details = listOfNotNull(
                    item.delegatedTo?.let { "→ $it" },
                    message("run.view.host.retries", item.retries).takeIf { item.retries > 0 },
                    message("run.view.host.items", item.items.size).takeIf { item.items.isNotEmpty() },
                    RunViewTexts.duration(item.duration).takeIf { it.isNotEmpty() },
                    RunViewTexts.firstLine(item.message).takeIf { it.isNotEmpty() },
                )
                if (details.isNotEmpty()) append("   " + details.joinToString(" · "), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            is ItemRun -> {
                icon = RunViewTexts.icon(item.status)
                append(item.label)
                RunViewTexts.firstLine(item.message).takeIf { it.isNotEmpty() }?.let { append("   $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            }
            RecapNode -> {
                icon = com.intellij.icons.AllIcons.General.Information
                append(message("run.view.recap"), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            }
        }
    }
}
