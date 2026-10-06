package de.terletzkiy.ansibility.resolve

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarDefinition
import de.terletzkiy.ansibility.api.VarsLayer
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.index.ValueSummary
import de.terletzkiy.ansibility.model.inventory.EnvironmentModel
import de.terletzkiy.ansibility.model.inventory.InventoryModels
import de.terletzkiy.ansibility.semantics.inventory.InlineVars

/**
 * Inline inventory variables the `ansible.var.def` index does not see (plan amendment R10, D65): those of INI
 * inventories and of YAML sources not named `hosts.y{a,}ml`. They come from the parsed [EnvironmentModel] graphs at
 * query time, with the key ranges the parsers record. `hosts.y{a,}ml` files are indexed and left out here.
 */
internal object InlineInventoryDefinitions {
    /** The model-derived definitions of [name] in [root]'s inventories. */
    fun of(project: Project, root: AnsibleRoot, name: String): List<VarDefinition> {
        val out = ArrayList<VarDefinition>()
        visit(project, root) { env, owner, isHost, section ->
            val value = section.entries[name] ?: return@visit
            val range = section.keyRanges[name] ?: return@visit
            val file = env.fileOf(section.location) ?: return@visit
            out += VarDefinition(
                name = name,
                kind = VarDefKind.INVENTORY_INLINE,
                location = SourceLocation(file, range.start),
                layer = if (isHost) VarsLayer.INVENTORY_FILE_HOST else VarsLayer.INVENTORY_FILE_GROUP,
                environment = env.name,
                group = owner.takeIf { !isHost },
                host = owner.takeIf { isHost },
                valueShape = ValueSummary.shape(value),
                preview = ValueSummary.preview(name, value, file.name),
                literalType = VarDefinitions.literalTypeName(ValueSummary.literalType(value)),
            )
        }
        return out.distinctBy { it.location }
    }

    /** Every model-derived definition written in [file]. */
    fun inFile(project: Project, root: AnsibleRoot, file: VirtualFile): List<VarDefinition> {
        val names = LinkedHashSet<String>()
        visit(project, root) { env, _, _, section -> if (env.fileOf(section.location) == file) names += section.entries.keys }
        return names.flatMap { of(project, root, it) }.filter { it.location.file == file }
    }

    /** Every name with a model-derived definition in [root]. */
    fun names(project: Project, root: AnsibleRoot): Set<String> {
        val names = HashSet<String>()
        visit(project, root) { _, _, _, section -> names += section.entries.keys }
        return names
    }

    private fun visit(project: Project, root: AnsibleRoot, action: (EnvironmentModel, String, Boolean, InlineVars) -> Unit) {
        for (env in InventoryModels.getInstance(project).environments(root)) {
            ProgressManager.checkCanceled()
            fun unindexed(section: InlineVars): Boolean {
                val file = env.fileOf(section.location) ?: return false
                return !AnsibleLayout.isHostsFileName(file.name)
            }
            for (group in env.graph.groups.values) {
                for (section in group.varSections) if (unindexed(section)) action(env, group.name, false, section)
            }
            for (host in env.graph.hosts.values) {
                for (section in host.varSections) if (unindexed(section)) action(env, host.name, true, section)
            }
        }
    }
}
