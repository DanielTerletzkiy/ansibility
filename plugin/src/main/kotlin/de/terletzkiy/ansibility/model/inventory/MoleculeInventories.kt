package de.terletzkiy.ansibility.model.inventory

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.MoleculeInventory
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarDefinition
import de.terletzkiy.ansibility.api.VarsLayer
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.semantics.inventory.InventoryGraph
import de.terletzkiy.ansibility.semantics.inventory.YamlInventoryParser
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.semantics.yaml.YEmpty
import de.terletzkiy.ansibility.semantics.yaml.YEntry
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue

/**
 * The parsed pseudo-inventory of one molecule scenario: the API [inventory] plus what evaluating it needs, so no
 * consumer parses `molecule.yml` again (plan amendment R7/R8, F8.10):
 * - [graph], the generated inventory merged with `provisioner.inventory.hosts` and a linked hosts file, with the inline
 *   variable sections of both (an inline section's `sourceIndex` points into [sourceFiles]);
 * - [groupVars] and [hostVars], the inline `provisioner.inventory.group_vars`/`host_vars` mappings of `molecule.yml`
 *   (owner name to variables), as loaded; `!vault` values are value-free markers.
 *
 * Immutable and PSI-free; built inside a read action and cached by [InventoryModels].
 */
class MoleculeModel(
    val inventory: MoleculeInventory,
    val graph: InventoryGraph,
    /** The file each inventory source of [graph] was read from (`molecule.yml` for the generated and inline parts). */
    val sourceFiles: List<VirtualFile>,
    val groupVars: YMap?,
    val hostVars: YMap?,
)

/** Builds [MoleculeInventory]s from `molecule.yml` files. Call inside a read action. */
object MoleculeInventories {
    private const val UNGROUPED = "ungrouped"

    /** The scenario directories of [rootDir]: `roles/<r>/molecule/<s>` of [rolesDirs] inside the root, and `<root>/molecule/<s>`. */
    fun scenarioConfigs(rootDir: VirtualFile, rolesDirs: List<VirtualFile>): List<Pair<String?, VirtualFile>> {
        val out = ArrayList<Pair<String?, VirtualFile>>()
        for (rolesDir in rolesDirs) {
            if (!rolesDir.isValid || !VfsUtilCore.isAncestor(rootDir, rolesDir, false)) continue
            for (role in rolesDir.children.orEmpty().filter { it.isDirectory }.sortedBy { it.name }) {
                ProgressManager.checkCanceled()
                configsIn(role.findChild(AnsibleLayout.MOLECULE)).forEach { out += role.name to it }
            }
        }
        configsIn(rootDir.findChild(AnsibleLayout.MOLECULE)).forEach { out += null to it }
        return out
    }

    private fun configsIn(moleculeDir: VirtualFile?): List<VirtualFile> =
        moleculeDir?.takeIf { it.isDirectory }?.children.orEmpty()
            .filter { it.isDirectory }
            .sortedBy { it.name }
            .mapNotNull { scenario -> scenario.children.orEmpty().firstOrNull { !it.isDirectory && AnsibleLayout.isMoleculeConfigName(it.name) } }

    /** The pseudo-inventory of the scenario whose `molecule.yml` is [configFile]. */
    fun parse(project: Project, rootDir: VirtualFile, roleName: String?, configFile: VirtualFile, extensions: List<String>): MoleculeModel {
        val scenarioDir = configFile.parent
        val config = VarsDocuments.load(project, configFile) as? YMap
        val inventoryNode = ((config?.get("provisioner") as? YMap)?.get("inventory") as? YMap)
        val links = inventoryNode?.get("links") as? YMap
        val linkedHosts = linkTarget(scenarioDir, links, "hosts")?.takeIf { !it.isDirectory }

        val files = ArrayList<VirtualFile>()
        val documents = ArrayList<YValue?>()
        files += configFile
        documents += generated((config?.get("platforms") as? YSeq)?.items.orEmpty().filterIsInstance<YMap>())
        (inventoryNode?.get("hosts") as? YMap)?.let {
            files += configFile
            documents += it
        }
        if (linkedHosts != null) {
            files += linkedHosts
            documents += VarsDocuments.load(project, linkedHosts)
        }
        val graph = YamlInventoryParser.parseAll(documents)

        val linkedGroupVars = linkTarget(scenarioDir, links, "group_vars")?.takeIf { it.isDirectory }
        val linkedHostVars = linkTarget(scenarioDir, links, "host_vars")?.takeIf { it.isDirectory }
        val groups = listOf(InventoryGraph.ALL) + graph.groups.keys.filter { it != InventoryGraph.ALL } +
            VarsDirectories.entities(linkedGroupVars, groups = true).filter { it !in graph.groups }.sorted()
        val hosts = graph.hosts.keys.toList() + VarsDirectories.entities(linkedHostVars, groups = false).filter { it !in graph.hosts }.sorted()
        val varFiles = VarsDirectories.varFiles(linkedGroupVars, linkedHostVars, scenarioDir.name, groups, hosts, extensions) { _, _ ->
            VarsLayer.MOLECULE_INVENTORY
        }

        val inventory = InventoryDtos.inventory(rootDir, scenarioDir.name, configFile, graph, files, documents, varFiles)
        val groupVars = inventoryNode?.get("group_vars") as? YMap
        val hostVars = inventoryNode?.get("host_vars") as? YMap
        val inlineVars = inlineVars(groupVars, configFile, roleName, isGroup = true) + inlineVars(hostVars, configFile, roleName, isGroup = false)
        return MoleculeModel(MoleculeInventory(roleName, scenarioDir, configFile, inventory, inlineVars), graph, files.toList(), groupVars, hostVars)
    }

    /** The inventory molecule writes for [platforms] (`molecule.provisioner.ansible.Ansible.inventory`). */
    private fun generated(platforms: List<YMap>): YMap {
        val allHosts = ArrayList<YEntry>()
        val groupHosts = LinkedHashMap<String, Pair<YScalar, MutableList<YEntry>>>()
        val groupChildren = LinkedHashMap<String, LinkedHashMap<String, Pair<YScalar, MutableList<YEntry>>>>()
        for (platform in platforms) {
            val name = platform["name"] as? YScalar ?: continue
            if (name.text.isEmpty()) continue
            fun hostEntry() = YEntry(YScalar(name.text, ScalarStyle.DOUBLE_QUOTED, range = name.range), YEmpty(name.range))
            allHosts += hostEntry()
            val groups = (platform["groups"] as? YSeq)?.items?.filterIsInstance<YScalar>()?.filter { it.text.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }
                ?: listOf(YScalar(UNGROUPED, ScalarStyle.PLAIN))
            val children = (platform["children"] as? YSeq)?.items?.filterIsInstance<YScalar>().orEmpty().filter { it.text.isNotEmpty() }
            for (group in groups) {
                groupHosts.getOrPut(group.text) { groupKey(group) to ArrayList() }.second += hostEntry()
                for (child in children) {
                    groupChildren.getOrPut(group.text) { LinkedHashMap() }
                        .getOrPut(child.text) { groupKey(child) to ArrayList() }.second += hostEntry()
                }
            }
        }
        val entries = ArrayList<YEntry>()
        entries += YEntry(plain(InventoryGraph.ALL), YMap(listOf(YEntry(plain("hosts"), YMap(allHosts)))))
        for ((group, keyAndHosts) in groupHosts) {
            val sections = ArrayList<YEntry>()
            sections += YEntry(plain("hosts"), YMap(keyAndHosts.second))
            groupChildren[group]?.let { children ->
                sections += YEntry(
                    plain("children"),
                    YMap(children.values.map { (key, hosts) -> YEntry(key, YMap(listOf(YEntry(plain("hosts"), YMap(hosts))))) }),
                )
            }
            entries += YEntry(keyAndHosts.first, YMap(sections))
        }
        return YMap(entries)
    }

    private fun inlineVars(section: YMap?, configFile: VirtualFile, roleName: String?, isGroup: Boolean): List<VarDefinition> {
        val out = ArrayList<VarDefinition>()
        for (owner in section?.entries.orEmpty()) {
            val vars = owner.value as? YMap ?: continue
            for (entry in vars.entries) {
                val range = entry.key.range ?: continue
                out += VarDefinition(
                    name = entry.key.text,
                    kind = VarDefKind.MOLECULE_INVENTORY,
                    location = SourceLocation(configFile, range.start),
                    layer = VarsLayer.MOLECULE_INVENTORY,
                    roleName = roleName,
                    group = owner.key.text.takeIf { isGroup },
                    host = owner.key.text.takeIf { !isGroup },
                    valueShape = ValuePreview.shape(entry.value),
                    preview = ValuePreview.of(entry.key.text, entry.value, configFile),
                )
            }
        }
        return out
    }

    /** `provisioner.inventory.links.<kind>`: a path relative to the scenario directory. */
    private fun linkTarget(scenarioDir: VirtualFile, links: YMap?, kind: String): VirtualFile? {
        val path = (links?.get(kind) as? YScalar)?.text?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        if (path.startsWith("/") || path.startsWith("~") || path.startsWith("$")) return null
        return scenarioDir.findFileByRelativePath(path)?.takeIf { it.isValid }
    }

    private fun groupKey(scalar: YScalar) = YScalar(scalar.text, ScalarStyle.DOUBLE_QUOTED, range = scalar.range)

    private fun plain(text: String) = YScalar(text, ScalarStyle.PLAIN)
}
