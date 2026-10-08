package de.terletzkiy.ansibility.context

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * What one navigation or search request sees of Molecule content (plan amendment R20, D153–D156), fixed when the request
 * starts and passed down to the data layer (`index.RootFamily`, `resolve.VarViews`, `resolve.VarUsageQuery`):
 * [INCLUDE] sees production and Molecule content, [EXCLUDE] production only, i.e. no Molecule file
 * ([MoleculeVisibility.isMoleculeFile]), no Molecule inventory and no `include_vars` key only Molecule playbooks load
 * ([MoleculeVisibility.isMolecule]).
 *
 * Rename, inspections, type checks and the model always use [INCLUDE] (D155).
 */
enum class MoleculeView {
    /** Production and Molecule content (a request from a Molecule file, or "Show Molecule in navigation and search" on). */
    INCLUDE,

    /** Production content only. */
    EXCLUDE,
    ;

    /** True for [INCLUDE]. */
    val includesMolecule: Boolean get() = this == INCLUDE

    companion object {
        /**
         * The view of a request that starts in [origin] ([MoleculeVisibility.shows]): [INCLUDE] when the setting is on or
         * [origin] is a Molecule file, else [EXCLUDE]. A null origin (Search Everywhere, a tool-window panel) counts as
         * production, so the setting alone decides. Reads the setting per call.
         */
        fun of(project: Project, origin: VirtualFile?): MoleculeView = if (MoleculeVisibility.shows(project, origin)) INCLUDE else EXCLUDE

        /**
         * The view analysis of [origin] uses (D157, [MoleculeVisibility.analysisSeesMolecule]): [INCLUDE] only inside a
         * Molecule file, whatever the setting. A path property, so results computed with it may be cached per file.
         */
        fun forAnalysis(project: Project, origin: VirtualFile?): MoleculeView =
            if (MoleculeVisibility.analysisSeesMolecule(project, origin)) INCLUDE else EXCLUDE
    }
}
