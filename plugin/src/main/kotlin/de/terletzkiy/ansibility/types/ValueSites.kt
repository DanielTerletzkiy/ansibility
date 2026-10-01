package de.terletzkiy.ansibility.types

import com.intellij.openapi.progress.ProgressManager
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.index.effectiveEntries
import de.terletzkiy.ansibility.model.task.BlockNode
import de.terletzkiy.ansibility.model.task.TaskFileModel
import de.terletzkiy.ansibility.model.task.TaskFileModels
import de.terletzkiy.ansibility.model.task.TaskItem
import de.terletzkiy.ansibility.model.task.TaskNode
import de.terletzkiy.ansibility.semantics.yaml.YEntry
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import org.jetbrains.yaml.psi.YAMLFile

/**
 * Where a file assigns values to variables (plan F3.2 "Where it runs", F4.1): the key-value pairs [TypeCheckServiceImpl]
 * checks against argument specs, read the way ansible-core loads them (a repeated key counts once, the last wins).
 *
 * - role `defaults/` and `vars/`, `group_vars`/`host_vars` files and `molecule/vars` files: the top-level keys;
 * - YAML inventories: `<group>.vars` and `<group>.hosts.<host>` of every group, through `children` at any depth;
 * - molecule `molecule.yml`: `provisioner.inventory.group_vars`/`host_vars` and its inline `hosts` inventory;
 * - playbooks and task files: play, block and task `vars:` (including `include_role`/`import_role` vars), the
 *   `vars:` and parameters of `roles:` entries, and the `vars:` of `import_playbook` entries.
 *
 * Call inside a read action.
 */
internal object ValueSites {
    /** The file kinds that hold variable values. */
    val CHECKED_KINDS: Set<FileKind> = setOf(
        FileKind.ROLE_DEFAULTS, FileKind.ROLE_VARS, FileKind.GROUP_VARS, FileKind.HOST_VARS, FileKind.MOLECULE_VARS,
        FileKind.INVENTORY, FileKind.MOLECULE_CONFIG,
        FileKind.PLAYBOOK, FileKind.MOLECULE_PLAYBOOK, FileKind.ROLE_TASKS, FileKind.ROLE_HANDLERS, FileKind.MOLECULE_TASKS,
    )

    /** Kinds whose top-level keys are the variables. */
    private val TOP_LEVEL_KINDS = setOf(FileKind.ROLE_DEFAULTS, FileKind.ROLE_VARS, FileKind.GROUP_VARS, FileKind.HOST_VARS, FileKind.MOLECULE_VARS)

    /** Kinds read through the task model. */
    private val TASK_KINDS = setOf(FileKind.PLAYBOOK, FileKind.MOLECULE_PLAYBOOK, FileKind.ROLE_TASKS, FileKind.ROLE_HANDLERS, FileKind.MOLECULE_TASKS)

    /** Inventories nest groups through `children`; deeper trees are not followed (the indexer's limit). */
    private const val MAX_INVENTORY_DEPTH = 32

    /** The assignments of [file], a file of [kind], in file order. */
    fun of(file: YAMLFile, kind: FileKind): List<YEntry> = when (kind) {
        in TOP_LEVEL_KINDS -> mapping(PsiYValueAdapter.documentValue(file))
        FileKind.INVENTORY -> ArrayList<YEntry>().also { out -> inventory(PsiYValueAdapter.documentValue(file), out) }
        FileKind.MOLECULE_CONFIG -> molecule(PsiYValueAdapter.documentValue(file))
        in TASK_KINDS -> tasks(TaskFileModels.of(file))
        else -> emptyList()
    }.sortedBy { it.key.range?.start ?: 0 }

    private fun mapping(value: YValue?): List<YEntry> = (value as? YMap)?.let(::effectiveEntries)?.toList().orEmpty()

    private fun inventory(document: YValue?, out: MutableList<YEntry>) {
        val groups = document as? YMap ?: return
        for (group in effectiveEntries(groups)) inventoryGroup(group.value, out, 0)
    }

    private fun inventoryGroup(value: YValue, out: MutableList<YEntry>, depth: Int) {
        if (depth > MAX_INVENTORY_DEPTH) return
        val group = value as? YMap ?: return
        ProgressManager.checkCanceled()
        out += mapping(group["vars"])
        (group["hosts"] as? YMap)?.let { hosts -> effectiveEntries(hosts).forEach { out += mapping(it.value) } }
        (group["children"] as? YMap)?.let { children -> effectiveEntries(children).forEach { inventoryGroup(it.value, out, depth + 1) } }
    }

    private fun molecule(document: YValue?): List<YEntry> {
        val inventory = ((document as? YMap)?.get("provisioner") as? YMap)?.get("inventory") as? YMap ?: return emptyList()
        val out = ArrayList<YEntry>()
        for (section in listOf("group_vars", "host_vars")) {
            val owners = inventory[section] as? YMap ?: continue
            for (owner in effectiveEntries(owners)) out += mapping(owner.value)
        }
        inventory(inventory["hosts"], out)
        return out
    }

    private fun tasks(model: TaskFileModel): List<YEntry> {
        val out = ArrayList<YEntry>()
        fun visit(items: List<TaskItem>) {
            for (item in items) {
                ProgressManager.checkCanceled()
                out += mapping(item.keywords["vars"]?.value)
                when (item) {
                    is BlockNode -> {
                        visit(item.block)
                        visit(item.rescue)
                        visit(item.always)
                    }
                    is TaskNode -> Unit
                }
            }
        }
        visit(model.items)
        for (play in model.plays) {
            out += mapping(play.keywords["vars"]?.value)
            for (role in play.roles) {
                out += mapping(role.keywords["vars"]?.value)
                out += lastWins(role.params)
            }
            play.sections().forEach(::visit)
        }
        for (import in model.imports) out += mapping(import.keywords["vars"]?.value)
        return out
    }

    private fun lastWins(entries: List<YEntry>): Collection<YEntry> {
        val byKey = LinkedHashMap<String, YEntry>()
        for (entry in entries) byKey[entry.key.text] = entry
        return byKey.values
    }
}
