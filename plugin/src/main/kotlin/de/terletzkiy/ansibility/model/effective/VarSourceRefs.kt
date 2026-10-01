package de.terletzkiy.ansibility.model.effective

import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.EffectiveVarEntry
import de.terletzkiy.ansibility.api.PlayRef
import de.terletzkiy.ansibility.api.VarSourceRef
import de.terletzkiy.ansibility.api.VarsLayer
import de.terletzkiy.ansibility.model.inventory.ValuePreview
import de.terletzkiy.ansibility.semantics.inventory.InventoryGraph
import de.terletzkiy.ansibility.semantics.precedence.EffectiveVar
import de.terletzkiy.ansibility.semantics.precedence.SourcedValue
import de.terletzkiy.ansibility.semantics.precedence.VarLayer
import de.terletzkiy.ansibility.semantics.precedence.VarOwner
import de.terletzkiy.ansibility.semantics.yaml.YVault

/**
 * Where the variables of one engine [de.terletzkiy.ansibility.semantics.precedence.VarSource] are written: the
 * file its key ranges point into and, for execution-layer sources, the role and play it belongs to
 * ([VarSourceRef.role], [VarSourceRef.play]).
 */
class SourceOrigin(val file: VirtualFile, val role: String? = null, val play: PlayRef? = null)

/** Conversions from engine results to the API's [VarSourceRef] / [EffectiveVarEntry] (plan A.5, amendment R7/R8 A.14). */
object VarSourceRefs {
    /**
     * The API view of [definition], or null when its source has no known origin. The preview follows the one vault-safe
     * rule ([ValuePreview]); vault values are flagged and never previewed. Inventory layers carry their owning group or
     * host; execution layers carry their role and play instead.
     */
    fun ref(definition: SourcedValue, origins: Map<String, SourceOrigin>): VarSourceRef? {
        val origin = origins[definition.source.originId] ?: return null
        val owner = definition.source.owner
        val inventoryLevel = definition.source.layer.isInventoryLevel
        return VarSourceRef(
            file = origin.file,
            offset = definition.keyRange?.start ?: 0,
            layer = layerOf(definition.source.layer),
            group = if (!inventoryLevel) null else when (owner) {
                VarOwner.All -> InventoryGraph.ALL
                is VarOwner.Group -> owner.name
                is VarOwner.Host -> null
            },
            host = (owner as? VarOwner.Host)?.name?.takeIf { inventoryLevel },
            preview = ValuePreview.of(definition.name, definition.value, origin.file),
            isVault = definition.value is YVault,
            role = origin.role,
            play = origin.play,
        )
    }

    /** The API entry of [effective], or null when its winner has no known origin. */
    fun entry(effective: EffectiveVar, origins: Map<String, SourceOrigin>): EffectiveVarEntry? {
        val winner = ref(effective.winner, origins) ?: return null
        return EffectiveVarEntry(
            name = effective.name,
            winner = winner,
            shadowed = effective.shadowed.mapNotNull { ref(it, origins) },
            mergedFrom = effective.mergedFrom.mapNotNull { ref(it, origins) },
        )
    }

    /** The API layer of an engine layer (the two enums mirror each other constant for constant). */
    fun layerOf(layer: VarLayer): VarsLayer = VarsLayer.valueOf(layer.name)
}
