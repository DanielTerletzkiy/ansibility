package de.terletzkiy.ansibility.vars

import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarDefinition
import de.terletzkiy.ansibility.api.VarsLayer
import org.jetbrains.annotations.Nls

/** Localised names of precedence layers and definition kinds. */
internal object VarLabels {
    /** `inventory group_vars/all`, `inventory host_vars/prod-prod1`, `role defaults`; null without a layer. */
    @Nls
    fun layer(definition: VarDefinition): String? {
        val layer = definition.layer ?: return null
        val owner = definition.group ?: definition.host ?: ""
        return AnsibilityVarsBundle.message("layer.${layer.name}", owner)
    }

    /** `level 4`; null for layers outside ansible-core's numbered list (molecule's pseudo-inventory). */
    @Nls
    fun level(definition: VarDefinition): String? {
        val layer = definition.layer ?: return null
        if (layer == VarsLayer.MOLECULE_INVENTORY) return null
        return AnsibilityVarsBundle.message("card.this.level", layer.level)
    }

    /** `inventory group_vars/all · level 4`, or the kind (`register`) for definitions without a layer. */
    @Nls
    fun layerWithLevel(definition: VarDefinition): String =
        listOfNotNull(layer(definition) ?: kind(definition.kind), level(definition)).joinToString(SEPARATOR)

    @Nls
    fun kind(kind: VarDefKind): String = AnsibilityVarsBundle.message("kind.${kind.name}")

    const val SEPARATOR: String = " · "
}
