package de.terletzkiy.ansibility.model.inventory

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.VarFile
import de.terletzkiy.ansibility.api.VarsLayer
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.semantics.inventory.DirEntry
import de.terletzkiy.ansibility.semantics.inventory.DirectoryLister
import de.terletzkiy.ansibility.semantics.inventory.InventoryGraph
import de.terletzkiy.ansibility.semantics.inventory.SortGroups
import de.terletzkiy.ansibility.semantics.inventory.VarsFileLayout

/**
 * `group_vars`/`host_vars` discovery on the VFS, following ansible-core's `host_group_vars` plugin through
 * [VarsFileLayout]: which files load for a group or a host below an inventory directory or a playbook directory,
 * in load order.
 */
object VarsDirectories {
    /** A [DirectoryLister] over the VFS below [base] (an inventory directory or a playbook directory). */
    fun lister(base: VirtualFile): DirectoryLister = DirectoryLister { relativePath ->
        val dir = if (relativePath.isEmpty()) base else base.findFileByRelativePath(relativePath)
        dir?.takeIf { it.isValid && it.isDirectory }?.children?.map { DirEntry(it.name, it.isDirectory) }
    }

    /** The files of the vars directory [varsDir] (a `group_vars` or `host_vars` directory) that load for [entity], in load order. */
    fun files(varsDir: VirtualFile, entity: String, extensions: List<String>): List<VirtualFile> {
        val base = varsDir.parent ?: return emptyList()
        return VarsFileLayout.findVarsFiles(lister(base), varsDir.name, entity, extensions)
            .mapNotNull { base.findFileByRelativePath(it)?.takeIf { f -> f.isValid && !f.isDirectory } }
    }

    /**
     * The entities that have something in the vars directory [varsDir] (a file named after them, or a directory),
     * whether or not an inventory defines them. [groups] selects the naming rules of `group_vars` (a file needs a
     * vars extension or none) over those of `host_vars` (host names may contain dots).
     */
    fun entities(varsDir: VirtualFile?, groups: Boolean): List<String> {
        val dir = varsDir?.takeIf { it.isValid && it.isDirectory } ?: return emptyList()
        return dir.children.orEmpty().mapNotNull { child ->
            when {
                AnsibleLayout.isIgnoredVarsEntry(child.name) -> null
                child.isDirectory -> child.name
                groups -> AnsibleLayout.groupFromFileName(child.name)
                else -> AnsibleLayout.hostFromFileName(child.name)
            }
        }.distinct()
    }

    /**
     * The inventory-level var files of an environment directory (layers 4, 6, 9) in load order:
     * `group_vars/all`, then the groups of [graph] in `sort_groups` order (depth, priority, name), then its hosts
     * in inventory order. Files for groups or hosts the inventory does not define (orphans, which ansible-core
     * never loads) follow at the end of their section, sorted by name.
     */
    fun inventoryVarFiles(envDir: VirtualFile, environment: String, graph: InventoryGraph, extensions: List<String>): List<VarFile> {
        val groupVars = envDir.findChild(VarsFileLayout.GROUP_VARS)
        val hostVars = envDir.findChild(VarsFileLayout.HOST_VARS)
        val defined = graph.groups.values.filter { it.name != InventoryGraph.ALL }.sortedWith(SortGroups.ORDER).map { it.name }
        val groups = listOf(InventoryGraph.ALL) + defined + entities(groupVars, groups = true).filter { it !in graph.groups }.sorted()
        val hosts = graph.hosts.keys.toList() + entities(hostVars, groups = false).filter { it !in graph.hosts }.sorted()
        return varFiles(groupVars, hostVars, environment, groups, hosts, extensions) { group, isAll ->
            when {
                group == null -> VarsLayer.INVENTORY_HOST_VARS
                isAll -> VarsLayer.INVENTORY_GROUP_VARS_ALL
                else -> VarsLayer.INVENTORY_GROUP_VARS
            }
        }
    }

    /**
     * The playbook-level var files of [playbookDir] (layers 5, 7, 10): `group_vars/all`, the other groups by
     * name, then the hosts by name. Without an inventory there is no `sort_groups` order; for groups of equal
     * depth and priority (the usual case) it is the name order.
     */
    fun playbookVarFiles(playbookDir: VirtualFile, extensions: List<String>): List<VarFile> {
        val groupVars = playbookDir.findChild(VarsFileLayout.GROUP_VARS)
        val hostVars = playbookDir.findChild(VarsFileLayout.HOST_VARS)
        val groups = listOf(InventoryGraph.ALL) + entities(groupVars, groups = true).filter { it != InventoryGraph.ALL }.sorted()
        return varFiles(groupVars, hostVars, null, groups, entities(hostVars, groups = false).sorted(), extensions) { group, isAll ->
            when {
                group == null -> VarsLayer.PLAYBOOK_HOST_VARS
                isAll -> VarsLayer.PLAYBOOK_GROUP_VARS_ALL
                else -> VarsLayer.PLAYBOOK_GROUP_VARS
            }
        }
    }

    /** Var files of [groups] then [hosts] from the given directories; [layerOf] gets the group (null for hosts). */
    fun varFiles(
        groupVars: VirtualFile?,
        hostVars: VirtualFile?,
        environment: String?,
        groups: List<String>,
        hosts: List<String>,
        extensions: List<String>,
        layerOf: (group: String?, isAll: Boolean) -> VarsLayer,
    ): List<VarFile> {
        val out = ArrayList<VarFile>()
        if (groupVars != null && groupVars.isDirectory) {
            for (group in groups.distinct()) {
                ProgressManager.checkCanceled()
                val layer = layerOf(group, group == InventoryGraph.ALL)
                files(groupVars, group, extensions).mapTo(out) { VarFile(it, layer, environment, group, null) }
            }
        }
        if (hostVars != null && hostVars.isDirectory) {
            val layer = layerOf(null, false)
            for (host in hosts.distinct()) {
                ProgressManager.checkCanceled()
                files(hostVars, host, extensions).mapTo(out) { VarFile(it, layer, environment, null, host) }
            }
        }
        return out
    }
}
