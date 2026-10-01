package de.terletzkiy.ansibility.toolwindow.model

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.Inventory
import de.terletzkiy.ansibility.api.InventoryGroup
import de.terletzkiy.ansibility.api.InventoryHost
import de.terletzkiy.ansibility.api.VarFile
import de.terletzkiy.ansibility.api.VarsLayer
import de.terletzkiy.ansibility.semantics.precedence.PrecedenceEntry

/**
 * One source of inventory-level values for a group or host, in load order (plan A.5 levels 1–10): a var file, the
 * inline vars of a group or a host in `hosts.yml`, or the `ansible.cfg` connection settings.
 */
sealed interface LayerSource {
    /** The precedence level, 1–10. */
    val level: Int

    /** Where the source is written. */
    val target: NavigationTarget

    /** Stable identity among its siblings in the tree. */
    val id: String

    /** A `group_vars`/`host_vars` file (levels 4–7, 9, 10). */
    data class File(val varFile: VarFile) : LayerSource {
        val layer: VarsLayer get() = varFile.layer
        override val level: Int get() = layer.level
        override val target: NavigationTarget get() = NavigationTarget(varFile.file)
        override val id: String get() = "file:${varFile.file.path}"

        /** The group or host the file is for. */
        val owner: String? get() = varFile.group ?: varFile.host

        /** Whether the file holds vault data by name (`vault.yml`, `vault_*.yml`); values are never read. */
        val isVault: Boolean get() = varFile.file.nameWithoutExtension.startsWith(VAULT)
    }

    /** The inline `vars:` of [group] in [hostsFile] (level 3). */
    data class Inline(val group: InventoryGroup, val hostsFile: VirtualFile) : LayerSource {
        override val level: Int get() = VarsLayer.INVENTORY_FILE_GROUP.level
        override val target: NavigationTarget get() = NavigationTarget.of(group.location) ?: NavigationTarget(hostsFile)
        override val id: String get() = "inline:${group.name}"
    }

    /** The inline variables of [host] in [hostsFile] (level 8): its entries under `hosts:`. */
    data class HostInline(val host: InventoryHost, val hostsFile: VirtualFile) : LayerSource {
        override val level: Int get() = VarsLayer.INVENTORY_FILE_HOST.level
        override val target: NavigationTarget get() = NavigationTarget.of(host.location) ?: NavigationTarget(hostsFile)
        override val id: String get() = "host-inline:${host.name}"
    }

    /** `remote_user`/`remote_port` of `ansible.cfg` (level 1, not variables). */
    data class Connection(val settings: ConnectionSettings) : LayerSource {
        override val level: Int get() = 1
        override val target: NavigationTarget get() = NavigationTarget(settings.file, settings.offset)
        override val id: String get() = "cfg:${settings.file.path}"
    }

    private companion object {
        const val VAULT = "vault"
    }
}

/**
 * One environment of a root (`environments/<env>/hosts.yml`) with the derived views the tree and the details pane
 * need: the group tree, the `sort_groups` order, members per group and the load order of every group's and
 * host's sources. Pure and immutable; computed lazily from the [Inventory] DTO and the root's playbook-level files.
 */
class EnvironmentView(val root: RootSnapshot, val inventory: Inventory) {
    val name: String get() = inventory.environment

    val hostsFile: VirtualFile get() = inventory.hostsFile

    /** `environments/prod/hosts.yml`: the hosts file relative to the root that owns the inventory. */
    val hostsFileLabel: String
        get() = VfsUtilCore.getRelativePath(hostsFile, inventory.rootDir) ?: hostsFile.name

    val all: InventoryGroup? get() = inventory.groups[ALL]

    /**
     * The groups the tree shows: every group but an implicit `ungrouped` without hosts, in inventory order.
     */
    val visibleGroups: List<InventoryGroup> by lazy {
        inventory.groups.values.filter { it.name != UNGROUPED || it.hosts.isNotEmpty() || hasFiles(it.name) }
    }

    /** The groups directly below `all`, in inventory order (top-level keys next to `all:` count as its children). */
    val topGroups: List<InventoryGroup> by lazy {
        visibleGroups.filter { it.name != ALL && (ALL in it.parents || it.parents.isEmpty()) }
    }

    /**
     * The order ansible-core applies group variables in: `all`, then `sort_groups` (depth, then
     * `ansible_group_priority`, then name) over the other groups.
     */
    val applyOrder: List<InventoryGroup> by lazy {
        val (all, others) = visibleGroups.partition { it.name == ALL }
        all + others.sortedWith(SORT_GROUPS)
    }

    fun group(name: String): InventoryGroup? = inventory.groups[name]

    fun host(name: String): InventoryHost? = inventory.hosts[name]

    /** The child groups of [group] that exist, in the order written. */
    fun childrenOf(group: InventoryGroup): List<InventoryGroup> =
        if (group.name == ALL) topGroups else group.children.mapNotNull { inventory.groups[it] }

    /** Every host that is a member of [group], directly or through a child group, in inventory order. */
    fun membersOf(group: InventoryGroup): List<InventoryHost> =
        inventory.hosts.values.filter { group.name == ALL || group.name in it.groups }

    /** The groups below [group] (children, grandchildren …), cycles cut. */
    fun descendantsOf(group: InventoryGroup): Set<String> {
        val seen = LinkedHashSet<String>()
        val queue = ArrayDeque(group.children)
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (next == group.name || !seen.add(next)) continue
            inventory.groups[next]?.children?.let(queue::addAll)
        }
        return seen
    }

    /**
     * The groups through which [host] belongs to [group]: empty when it is listed under [group] itself, else the
     * descendants of [group] that list it directly (`analytics` for `prod-mlflow1` in `contracting`).
     */
    fun viaGroups(group: InventoryGroup, host: InventoryHost): List<String> {
        if (group.name == ALL || host.name in group.hosts) return emptyList()
        val descendants = descendantsOf(group)
        return host.groups.filter { it in descendants && host.name in inventory.groups[it]?.hosts.orEmpty() }
    }

    /** Position of [group] in [applyOrder] (0-based), or -1. */
    fun applyIndex(group: InventoryGroup): Int = applyOrder.indexOfFirst { it.name == group.name }

    /** The var files of this environment for [group] (levels 4, 6) in load order. */
    fun inventoryFilesOf(group: String): List<VarFile> = inventory.varFiles.filter { it.group == group }

    /** The playbook-level var files for [group] (levels 5, 7) in load order. */
    fun playbookFilesOf(group: String): List<VarFile> = root.playbookVarFiles.filter { it.group == group }

    /** Whether [group] has a group_vars file next to the inventory or the playbook. */
    fun hasFiles(group: String): Boolean = inventory.varFiles.any { it.group == group } || root.playbookVarFiles.any { it.group == group }

    /**
     * What [group] contributes, in load order: for `all` the `ansible.cfg` connection settings (L1), then the
     * group-level entries of the root's `VARIABLE_PRECEDENCE` that concern this group (inline L3, env files L4/L6,
     * playbook files L5/L7).
     */
    fun sourcesOf(group: InventoryGroup): List<LayerSource> {
        val out = ArrayList<LayerSource>()
        val isAll = group.name == ALL
        if (isAll) root.connection?.let { out += LayerSource.Connection(it) }
        for (entry in root.precedence) {
            when (entry) {
                PrecedenceEntry.ALL_INVENTORY -> if (isAll) inline(group)?.let(out::add)
                PrecedenceEntry.GROUPS_INVENTORY -> if (!isAll) inline(group)?.let(out::add)
                PrecedenceEntry.ALL_PLUGINS_INVENTORY -> if (isAll) inventoryFilesOf(group.name).mapTo(out, LayerSource::File)
                PrecedenceEntry.ALL_PLUGINS_PLAY -> if (isAll) playbookFilesOf(group.name).mapTo(out, LayerSource::File)
                PrecedenceEntry.GROUPS_PLUGINS_INVENTORY -> if (!isAll) inventoryFilesOf(group.name).mapTo(out, LayerSource::File)
                PrecedenceEntry.GROUPS_PLUGINS_PLAY -> if (!isAll) playbookFilesOf(group.name).mapTo(out, LayerSource::File)
            }
        }
        return out.distinct()
    }

    /**
     * The sources that apply to [host], in the order ansible-core loads them (lowest first): the group levels
     * 3–7 as `VARIABLE_PRECEDENCE` orders them, groups in the host's `sort_groups` order, then the host's inline
     * vars in `hosts.yml` (L8, `host.get_vars()`, which `VARIABLE_PRECEDENCE` does not reorder) and its own files
     * (L9 environment host_vars, L10 playbook host_vars). Later entries win.
     */
    fun sourcesOf(host: InventoryHost): List<LayerSource> {
        val groups = host.groups.mapNotNull { inventory.groups[it] }
        val all = groups.firstOrNull { it.name == ALL } ?: this.all
        val others = groups.filter { it.name != ALL }
        val out = ArrayList<LayerSource>()
        for (entry in root.precedence) {
            when (entry) {
                PrecedenceEntry.ALL_INVENTORY -> all?.let(::inline)?.let(out::add)
                PrecedenceEntry.GROUPS_INVENTORY -> others.mapNotNullTo(out, ::inline)
                PrecedenceEntry.ALL_PLUGINS_INVENTORY -> inventoryFilesOf(ALL).mapTo(out, LayerSource::File)
                PrecedenceEntry.ALL_PLUGINS_PLAY -> playbookFilesOf(ALL).mapTo(out, LayerSource::File)
                PrecedenceEntry.GROUPS_PLUGINS_INVENTORY -> others.forEach { g -> inventoryFilesOf(g.name).mapTo(out, LayerSource::File) }
                PrecedenceEntry.GROUPS_PLUGINS_PLAY -> others.forEach { g -> playbookFilesOf(g.name).mapTo(out, LayerSource::File) }
            }
        }
        if (host.inlineVarKeys.isNotEmpty()) out += LayerSource.HostInline(host, inventory.hostsFile)
        inventory.varFiles.filter { it.host == host.name }.mapTo(out, LayerSource::File)
        root.playbookVarFiles.filter { it.host == host.name }.mapTo(out, LayerSource::File)
        return out.distinct()
    }

    /** The hosts [varFile] applies to in this environment, in inventory order. */
    fun hostsFor(varFile: VarFile): List<InventoryHost> = when {
        varFile.host != null -> listOfNotNull(inventory.hosts[varFile.host])
        varFile.group != null -> inventory.groups[varFile.group]?.let(::membersOf).orEmpty()
        else -> emptyList()
    }

    private fun inline(group: InventoryGroup): LayerSource? =
        if (group.inlineVarKeys.isEmpty()) null else LayerSource.Inline(group, inventory.hostsFile)

    companion object {
        const val ALL: String = "all"
        const val UNGROUPED: String = "ungrouped"

        /** ansible-core's `sort_groups`: depth, then `ansible_group_priority`, then name. */
        val SORT_GROUPS: Comparator<InventoryGroup> = compareBy<InventoryGroup>({ it.depth }, { it.priority }, { it.name })
    }
}
