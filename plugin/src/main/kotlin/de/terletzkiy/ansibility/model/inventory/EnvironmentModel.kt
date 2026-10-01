package de.terletzkiy.ansibility.model.inventory

import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.Inventory
import de.terletzkiy.ansibility.api.InventoryGroup
import de.terletzkiy.ansibility.api.InventoryHost
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VarFile
import de.terletzkiy.ansibility.semantics.inventory.InventoryGraph
import de.terletzkiy.ansibility.semantics.inventory.InventoryLocation
import de.terletzkiy.ansibility.semantics.yaml.SourceRange
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YValue

/**
 * One environment of a root: `environments/<name>/hosts.yml` parsed the way ansible-core's YAML inventory plugin
 * parses it, plus the API view of it. Immutable; built inside a read action and cached by [InventoryModels].
 */
class EnvironmentModel(
    val name: String,
    /** `environments/<name>`, the inventory directory whose `group_vars`/`host_vars` are inventory-level. */
    val dir: VirtualFile,
    val hostsFile: VirtualFile,
    /** The parsed inventory, with the inline variables and their key ranges (offsets in [hostsFile]). */
    val graph: InventoryGraph,
    val inventory: Inventory,
)

/** Conversions from the PSI-free inventory model to the frozen API DTOs. */
object InventoryDtos {
    /**
     * The API view of [graph]. [files] maps an inventory source index to the file its ranges point into (a
     * `hosts.yml` has one source; a molecule scenario may have several); [documents] are the loaded sources, used
     * to prefer a group's top-level key and a host's `all.hosts` entry as their locations.
     */
    fun inventory(
        rootDir: VirtualFile,
        environment: String,
        hostsFile: VirtualFile,
        graph: InventoryGraph,
        files: List<VirtualFile>,
        documents: List<YValue?>,
        varFiles: List<VarFile>,
    ): Inventory {
        val topLevelKeys = documents.flatMap { (it as? YMap)?.entries.orEmpty() }.mapNotNullTo(HashSet()) { it.key.range }
        val allHostKeys = documents.flatMap { hostKeysOfAll(it) }.toHashSet()
        val groups = graph.groups.values.associateTo(LinkedHashMap()) { g ->
            g.name to InventoryGroup(
                name = g.name,
                parents = g.parents,
                children = g.children,
                hosts = g.hosts,
                inlineVarKeys = g.vars.keys.toList(),
                location = location(g.definitions, files, topLevelKeys),
                depth = g.depth,
                priority = g.priority,
            )
        }
        val hosts = graph.hosts.values.associateTo(LinkedHashMap()) { h ->
            h.name to InventoryHost(
                name = h.name,
                ansibleHost = (h.ansibleHost as? YScalar)?.text,
                groups = graph.sortedGroupsOf(h.name, includeAll = true).map { it.name },
                location = location(h.definitions, files, allHostKeys),
                inlineVarKeys = h.vars.keys.toList(),
            )
        }
        return Inventory(rootDir, environment, hostsFile, groups, hosts, varFiles)
    }

    /** The preferred definition (one whose range is in [preferred]), else the first; null for implicit entities. */
    private fun location(definitions: List<InventoryLocation>, files: List<VirtualFile>, preferred: Set<SourceRange>): SourceLocation? {
        val located = definitions.filter { it.range != null && files.getOrNull(it.sourceIndex) != null }
        val chosen = located.firstOrNull { it.range in preferred } ?: located.firstOrNull() ?: return null
        return SourceLocation(files[chosen.sourceIndex], chosen.range!!.start)
    }

    private fun hostKeysOfAll(document: YValue?): List<SourceRange> {
        val hosts = ((document as? YMap)?.get(InventoryGraph.ALL) as? YMap)?.get("hosts") as? YMap ?: return emptyList()
        return hosts.entries.mapNotNull { it.key.range }
    }
}
