package de.terletzkiy.ansibility.context

import com.intellij.openapi.project.Project
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.ProjectLayoutService

/**
 * What the context UI needs to know about a root's inventories (plan amendment R10): whether it has any, and whether
 * it has exactly one from a non-convention layout, whose environment dimension the UI hides (D58).
 */
data class InventoryShape(val hasInventory: Boolean, val single: Boolean) {
    companion object {
        /** The shape known from the root alone: `environments/` present or not. */
        fun of(root: AnsibleRoot): InventoryShape = InventoryShape(root.environmentsDir != null, single = false)

        /** The shape from the root's resolved layout. */
        fun of(project: Project, root: AnsibleRoot): InventoryShape {
            if (root.environmentsDir != null) return of(root)
            val layout = ProjectLayoutService.getInstance(project).layout(root)
            return InventoryShape(layout.hasInventory, layout.isSingleInventory)
        }
    }
}
