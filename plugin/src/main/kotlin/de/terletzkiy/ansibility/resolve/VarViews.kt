package de.terletzkiy.ansibility.resolve

import com.intellij.openapi.project.Project
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.api.VarSymbol
import de.terletzkiy.ansibility.context.MoleculeView
import de.terletzkiy.ansibility.context.MoleculeVisibility

/**
 * [VarService] lookups with a [MoleculeView] (plan amendment R20, D153): what navigation, search, completion and cards
 * use, with the view of the request ([MoleculeView.of] its origin). The frozen [VarService] contract keeps its
 * view-less methods, which are [MoleculeView.INCLUDE] (the model, rename and inspections). Cached per (root, view) by
 * [VarServiceImpl]; another implementation gets its symbol filtered. Call in a read action in smart mode.
 */
object VarViews {
    /** The symbol of [name] in [root] as a request with [view] sees it. */
    fun symbol(project: Project, root: AnsibleRoot, name: String, view: MoleculeView): VarSymbol {
        VarServiceImpl.getInstance(project)?.let { return it.symbol(root, name, view) }
        return MoleculeVisibility.inView(project, root, view, VarService.getInstance(project).symbol(root, name))
    }

    /**
     * The names of [root] as a request with [view] sees them. Another [VarService] implementation gives all names (a
     * superset; [symbol] still filters each one).
     */
    fun allNames(project: Project, root: AnsibleRoot, view: MoleculeView): Collection<String> =
        VarServiceImpl.getInstance(project)?.allNames(root, view) ?: VarService.getInstance(project).allNames(root)
}
