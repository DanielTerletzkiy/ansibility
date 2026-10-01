package de.terletzkiy.ansibility.semantics.precedence

import de.terletzkiy.ansibility.semantics.inventory.DirectoryLister
import de.terletzkiy.ansibility.semantics.inventory.InventoryGraph
import de.terletzkiy.ansibility.semantics.inventory.VarsFileLayout
import de.terletzkiy.ansibility.semantics.yaml.YValue

/**
 * Builds the inventory-level [VarSource]s the way ansible-core finds them: the inline variables of a parsed
 * inventory, and the `group_vars`/`host_vars` files next to an inventory source or a playbook directory.
 */
object InventoryVarSources {
    /**
     * One source per inline variable block of [graph] (layers 3 and 8). [originId] maps a source index
     * (position in `-i` order) to the id of that inventory file.
     */
    fun inline(graph: InventoryGraph, originId: (sourceIndex: Int) -> String): List<VarSource> {
        val out = ArrayList<VarSource>()
        for (group in graph.groups.values) {
            group.varSections.forEachIndexed { order, section ->
                out += VarSource(
                    layer = VarLayer.INVENTORY_FILE_GROUP,
                    owner = VarOwner.group(group.name),
                    originId = originId(section.location.sourceIndex),
                    order = order,
                    entries = section.entries,
                    keyRanges = section.keyRanges,
                    sourceIndex = section.location.sourceIndex,
                )
            }
        }
        for (host in graph.hosts.values) {
            host.varSections.forEachIndexed { order, section ->
                out += VarSource(
                    layer = VarLayer.INVENTORY_FILE_HOST,
                    owner = VarOwner.Host(host.name),
                    originId = originId(section.location.sourceIndex),
                    order = order,
                    entries = section.entries,
                    keyRanges = section.keyRanges,
                    sourceIndex = section.location.sourceIndex,
                )
            }
        }
        return out
    }

    /**
     * The `group_vars`/`host_vars` sources under [base] for every group and host of [graph] (the vars plugin only
     * loads files for entities that exist, so orphan files are ignored).
     *
     * [playbookAdjacent] selects the playbook layers (5, 7, 10) instead of the inventory layers (4, 6, 9).
     * [load] returns the loaded YAML of a file given its path relative to [base] (null when unreadable);
     * [originId] turns that path into the source id.
     */
    fun adjacent(
        graph: InventoryGraph,
        base: DirectoryLister,
        playbookAdjacent: Boolean,
        load: (relativePath: String) -> YValue?,
        originId: (relativePath: String) -> String = { it },
        sourceIndex: Int = 0,
        extensions: List<String> = VarsFileLayout.DEFAULT_EXTENSIONS,
    ): List<VarSource> {
        val out = ArrayList<VarSource>()
        for (group in graph.groups.keys) {
            val files = VarsFileLayout.findVarsFiles(base, VarsFileLayout.GROUP_VARS, group, extensions)
            files.forEachIndexed { order, path ->
                VarSource.fromDocument(
                    VarLayer.groupVars(group, playbookAdjacent), VarOwner.group(group), originId(path), order, load(path), sourceIndex,
                )?.let { out += it }
            }
        }
        for (host in graph.hosts.keys) {
            val files = VarsFileLayout.findVarsFiles(base, VarsFileLayout.HOST_VARS, host, extensions)
            files.forEachIndexed { order, path ->
                VarSource.fromDocument(
                    VarLayer.hostVars(playbookAdjacent), VarOwner.Host(host), originId(path), order, load(path), sourceIndex,
                )?.let { out += it }
            }
        }
        return out
    }
}
