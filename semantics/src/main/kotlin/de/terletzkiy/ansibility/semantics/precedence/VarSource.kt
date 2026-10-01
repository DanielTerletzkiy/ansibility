package de.terletzkiy.ansibility.semantics.precedence

import de.terletzkiy.ansibility.semantics.inventory.InventoryGraph
import de.terletzkiy.ansibility.semantics.inventory.Py
import de.terletzkiy.ansibility.semantics.yaml.SourceRange
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YValue

/**
 * Variable precedence layers, lowest to highest. Mirrors the plugin's `api.VarsLayer` constant for constant
 * (same names and levels), so the plugin maps between them with `VarLayer.valueOf(layer.name)`.
 *
 * [family] says where the variables come from; for the inventory families the [VarOwner] of a source then picks
 * the `VARIABLE_PRECEDENCE` slot (`all` vs. a group) or the host slot.
 */
enum class VarLayer(val level: Int, val label: String, val family: Family) {
    ROLE_DEFAULTS(2, "role defaults", Family.EXECUTION),
    INVENTORY_FILE_GROUP(3, "inventory file group vars", Family.INVENTORY_FILE),
    INVENTORY_GROUP_VARS_ALL(4, "inventory group_vars/all", Family.INVENTORY_ADJACENT),
    PLAYBOOK_GROUP_VARS_ALL(5, "playbook group_vars/all", Family.PLAYBOOK_ADJACENT),
    INVENTORY_GROUP_VARS(6, "inventory group_vars/<group>", Family.INVENTORY_ADJACENT),
    PLAYBOOK_GROUP_VARS(7, "playbook group_vars/<group>", Family.PLAYBOOK_ADJACENT),
    INVENTORY_FILE_HOST(8, "inventory file host vars", Family.INVENTORY_FILE),
    INVENTORY_HOST_VARS(9, "inventory host_vars", Family.INVENTORY_ADJACENT),
    PLAYBOOK_HOST_VARS(10, "playbook host_vars", Family.PLAYBOOK_ADJACENT),
    PLAY_VARS(12, "play vars", Family.EXECUTION),
    VARS_FILES(13, "play vars_files", Family.EXECUTION),
    ROLE_VARS(14, "role vars", Family.EXECUTION),
    BLOCK_TASK_VARS(15, "block/task vars", Family.EXECUTION),
    INCLUDE_VARS(16, "include_vars", Family.EXECUTION),
    SET_FACT_REGISTER(17, "set_fact/register", Family.EXECUTION),
    ROLE_PARAMS(18, "role params", Family.EXECUTION),
    EXTRA_VARS(19, "extra vars", Family.EXECUTION),

    /** Molecule `provisioner.inventory` group_vars/host_vars (written next to molecule's generated inventory). */
    MOLECULE_INVENTORY(6, "molecule inventory", Family.INVENTORY_ADJACENT),
    ;

    /** Where a layer's variables come from. */
    enum class Family {
        /** Inline `vars` of the inventory source itself (`all_inventory`, `groups_inventory`, host vars). */
        INVENTORY_FILE,

        /** `group_vars`/`host_vars` next to the inventory source (`*_plugins_inventory`). */
        INVENTORY_ADJACENT,

        /** `group_vars`/`host_vars` next to the playbook (`*_plugins_play`). */
        PLAYBOOK_ADJACENT,

        /** Play, role, task and command-line inputs layered on top of the inventory (`ExecutionView`). */
        EXECUTION,
    }

    val isInventoryLevel: Boolean get() = family != Family.EXECUTION

    companion object {
        /** The layer of a `group_vars` file for [group], next to the inventory or next to the playbook. */
        fun groupVars(group: String, playbookAdjacent: Boolean): VarLayer = when {
            group == InventoryGraph.ALL && playbookAdjacent -> PLAYBOOK_GROUP_VARS_ALL
            group == InventoryGraph.ALL -> INVENTORY_GROUP_VARS_ALL
            playbookAdjacent -> PLAYBOOK_GROUP_VARS
            else -> INVENTORY_GROUP_VARS
        }

        /** The layer of a `host_vars` file, next to the inventory or next to the playbook. */
        fun hostVars(playbookAdjacent: Boolean): VarLayer = if (playbookAdjacent) PLAYBOOK_HOST_VARS else INVENTORY_HOST_VARS
    }
}

/** Which inventory entity a source's variables are attached to. */
sealed interface VarOwner {
    /** The `all` group (and, for execution layers, every host of the play). */
    data object All : VarOwner

    data class Group(val name: String) : VarOwner

    data class Host(val name: String) : VarOwner

    companion object {
        /** `Group("all")` is normalised to [All]. */
        fun group(name: String): VarOwner = if (name == InventoryGraph.ALL) All else Group(name)
    }
}

/**
 * One set of variables from one place: a vars file, an inline `vars:` block, a play's `vars:`, the extra vars …
 *
 * [originId] is opaque to the engine (the plugin uses a file URL or a PSI pointer key). [order] is the load order
 * within the same layer, owner and [sourceIndex]: the index of the file in [de.terletzkiy.ansibility.semantics.inventory.VarsFileLayout]'s
 * result, the position of an inline block, or the position of a role in the play. [sourceIndex] tells apart
 * several inventory sources (`-i a -i b`) or playbook base directories; it is 0 in the common case.
 */
data class VarSource(
    val layer: VarLayer,
    val owner: VarOwner,
    val originId: String,
    val order: Int,
    val entries: Map<String, YValue>,
    /** The range of each key in its file, for navigation (may be empty). */
    val keyRanges: Map<String, SourceRange> = emptyMap(),
    val sourceIndex: Int = 0,
) {
    override fun toString(): String = "VarSource(${layer.name} $owner $originId#$order, ${entries.size} vars)"

    companion object {
        /**
         * A source from a loaded vars document, or null when the document contributes nothing: ansible-core skips
         * empty files, and a document that is not a mapping is an error it reports ("Could not process …").
         */
        fun fromDocument(
            layer: VarLayer,
            owner: VarOwner,
            originId: String,
            order: Int,
            document: YValue?,
            sourceIndex: Int = 0,
        ): VarSource? {
            if (document !is YMap || document.entries.isEmpty()) return null
            val dict = Py.dict(document)
            return VarSource(
                layer = layer,
                owner = owner,
                originId = originId,
                order = order,
                entries = dict.mapValuesTo(LinkedHashMap()) { it.value.value },
                keyRanges = dict.entries.mapNotNull { (k, e) -> e.key.range?.let { k to it } }.toMap(),
                sourceIndex = sourceIndex,
            )
        }
    }
}
