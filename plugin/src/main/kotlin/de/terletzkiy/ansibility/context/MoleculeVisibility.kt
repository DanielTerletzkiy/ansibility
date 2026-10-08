package de.terletzkiy.ansibility.context

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.PlayRef
import de.terletzkiy.ansibility.api.RenderContext
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarDefinition
import de.terletzkiy.ansibility.api.VarSymbol
import de.terletzkiy.ansibility.resolve.IncludedVarsDefinitions
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.MoleculeSettings

/**
 * The one rule for what Molecule content is and who sees it (plan amendment R20, D153–D157). Molecule scenarios are
 * test fixtures: they are always analysed (code insight inside them is always on), but a production run never sees
 * them.
 *
 * - **A Molecule file** ([isMoleculeFile]) lies below the `molecule` directory right inside its role directory (an
 *   ancestor whose parent is one of its root's roles directories), or, outside roles, below any `molecule` directory
 *   between its root and itself: the classifier's rule. So `roles/web/molecule/default/converge.yml`,
 *   `roles/web/molecule/requirements.yml`, a scenario's `Dockerfile.j2` and `<root>/molecule/default/molecule.yml` are
 *   Molecule files, while `roles/web/tasks/molecule/setup.yml` (the role's own tasks) and the tasks of a role that is
 *   itself named `molecule` are not. Pure path rule over the workspace's roots; the file kind is not needed.
 * - **A Molecule definition** ([isMolecule]): a Molecule inventory variable, any definition located in a Molecule file
 *   (converge play vars, `set_fact`, `register`, scenario vars), and, judged by cause rather than location, the keys of
 *   a file that only Molecule playbooks load with `include_vars` (the file itself may be a production file).
 * - **Navigation and search** ([shows], [visible]): requests that start in a Molecule file see everything (D154); the
 *   others see Molecule only with "Show Molecule in navigation and search" on ([MoleculeSettings.showInNavigation]).
 *   A request without a file (Search Everywhere, a tool-window panel) counts as production. The setting is read per
 *   call, so callers that cache a filtered result must depend on `AnsibilityProjectSettings.modificationTracker`.
 * - **Analysis** ([forAnalysis], D157): inspections, witnesses and runtime markers of a production file never count
 *   Molecule definitions, whatever the setting; analysis of a Molecule file sees everything.
 *
 * Never filters the model itself (VarService, PlayGraph, InventoryService), rename or the override gutter's own
 * Molecule-only view.
 *
 * Threading: the file rules ([isMoleculeFile], [shows], [analysisSeesMolecule], [playsInView]) and [showInNavigation]
 * work on any thread (the workspace takes a read lock when the caller holds none). The definition and symbol rules
 * ([isMolecule], [withoutMolecule], [visible], [inView], [forAnalysis]) may compute the `include_vars` causes from PSI,
 * so call them in a read action in smart mode.
 */
object MoleculeVisibility {
    /** The directory Molecule keeps its scenarios in. */
    const val MOLECULE: String = AnsibleLayout.MOLECULE

    /** Whether [file] is a Molecule file of its (innermost) root; false outside every root. */
    fun isMoleculeFile(project: Project, file: VirtualFile): Boolean {
        val root = AnsibleWorkspace.getInstance(project).rootFor(file) ?: return false
        return isMoleculeFile(root, file)
    }

    /**
     * Whether [file] is a Molecule file of [root], by the classifier's own rule (`AnsibleFileClassifier`): inside a role,
     * the directory right below the role directory is `molecule` (`roles/web/molecule/…`; a `molecule` directory deeper
     * down, such as `roles/web/tasks/molecule/x.yml` or `roles/web/templates/molecule/x.j2`, holds the role's own
     * production files); outside roles, any `molecule` directory between [root] and the file. False when [file] is not
     * below [root]'s directory.
     */
    fun isMoleculeFile(root: AnsibleRoot, file: VirtualFile): Boolean {
        var molecule = false
        var below: VirtualFile? = null
        var dir = file.parent
        while (dir != null && dir != root.dir) {
            // The role directory itself never counts: a role may be named `molecule`.
            if (dir.parent in root.rolesDirs) return below?.name == MOLECULE
            if (dir.name == MOLECULE) molecule = true
            below = dir
            dir = dir.parent
        }
        return molecule && dir != null
    }

    /**
     * Whether [definition] (from a symbol of [root]) is Molecule content: a Molecule inventory variable, a definition
     * located in a Molecule file, or an `include_vars` key of a file only Molecule playbooks of [root] load.
     */
    fun isMolecule(project: Project, root: AnsibleRoot, definition: VarDefinition): Boolean =
        definition.kind == VarDefKind.MOLECULE_INVENTORY ||
            isMoleculeFile(project, definition.location.file) ||
            (definition.kind == VarDefKind.INCLUDE_VARS && IncludedVarsDefinitions.loadedOnlyByMolecule(project, root, definition.location.file))

    /** The setting "Show Molecule in navigation and search" ([MoleculeSettings.showInNavigation]). */
    fun showInNavigation(project: Project): Boolean = AnsibilityProjectSettings.getInstance(project).settings.molecule.showInNavigation

    /**
     * Whether navigation and search started in [origin] (null: no file, which counts as production) show Molecule
     * content: the setting is on, or [origin] is a Molecule file (D153, D154).
     */
    fun shows(project: Project, origin: VirtualFile?): Boolean =
        showInNavigation(project) || (origin != null && isMoleculeFile(project, origin))

    /** Whether analysis of [origin] (null: no file, production) counts Molecule definitions: only inside Molecule (D157). */
    fun analysisSeesMolecule(project: Project, origin: VirtualFile?): Boolean = origin != null && isMoleculeFile(project, origin)

    /** [symbol] without its Molecule definitions ([isMolecule]); spec bindings are kept (they never come from Molecule). */
    fun withoutMolecule(project: Project, root: AnsibleRoot, symbol: VarSymbol): VarSymbol {
        if (symbol.definitions.isEmpty()) return symbol
        val kept = symbol.definitions.filter { definition ->
            ProgressManager.checkCanceled()
            !isMolecule(project, root, definition)
        }
        return if (kept.size == symbol.definitions.size) symbol else symbol.copy(definitions = kept)
    }

    /** [symbol] as navigation and search started in [origin] see it ([shows]). */
    fun visible(project: Project, root: AnsibleRoot, origin: VirtualFile?, symbol: VarSymbol): VarSymbol =
        if (shows(project, origin)) symbol else withoutMolecule(project, root, symbol)

    /** [symbol] as a request with [view] sees it: unchanged for [MoleculeView.INCLUDE], else [withoutMolecule]. */
    fun inView(project: Project, root: AnsibleRoot, view: MoleculeView, symbol: VarSymbol): VarSymbol =
        if (view.includesMolecule) symbol else withoutMolecule(project, root, symbol)

    /**
     * [plays] as a request with [view] sees them: with [MoleculeView.EXCLUDE] without the plays of Molecule files
     * (converge, verify and prepare plays never run in a production play).
     */
    fun playsInView(project: Project, view: MoleculeView, plays: List<PlayRef>): List<PlayRef> =
        if (view.includesMolecule) plays else plays.filter { !isMoleculeFile(project, it.file) }

    /**
     * [contexts] (render contexts of one template) as a request with [view] sees them: with [MoleculeView.EXCLUDE]
     * without those whose rendering task lies in a Molecule file (a converge or verify task that renders a role
     * template never runs in a production play).
     */
    fun contextsInView(project: Project, view: MoleculeView, contexts: List<RenderContext>): List<RenderContext> =
        if (view.includesMolecule) contexts else contexts.filter { !isMoleculeFile(project, it.taskSite.file) }

    /** [symbol] as analysis of [origin] sees it ([analysisSeesMolecule], D157). */
    fun forAnalysis(project: Project, root: AnsibleRoot, origin: VirtualFile?, symbol: VarSymbol): VarSymbol =
        if (analysisSeesMolecule(project, origin)) symbol else withoutMolecule(project, root, symbol)
}
