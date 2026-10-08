package de.terletzkiy.ansibility.index

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.ProjectLayoutService
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.context.MoleculeView
import de.terletzkiy.ansibility.context.MoleculeVisibility

/**
 * The files one [AnsibleRoot] resolves against (plan A.7 "Scope", DEV.md rule 6): the root directory minus every other
 * root nested in it (a NESTED_PLAYBOOK root inside a PROJECT root belongs to itself, a detached worktree to itself) and
 * minus a `.claude/worktrees` directory at its top, plus its explicit family: the parent's inventories for a
 * NESTED_PLAYBOOK root, and role directories configured outside the root (`roles_path`).
 *
 * Index lookups run with [scope]; each hit is then confirmed with [admits], because a file inside the scope may still
 * belong to no root (a skipped `.ansible` cache) or to a root the scope cannot express.
 *
 * [view] (plan amendment R20, D153): with [MoleculeView.EXCLUDE], [admits] also refuses Molecule files
 * ([MoleculeVisibility.isMoleculeFile]), so indexed definitions, uses and in-file definitions of navigation and search
 * started outside Molecule leave them out. The model, rename and inspections use [MoleculeView.INCLUDE].
 */
class RootFamily internal constructor(
    val root: AnsibleRoot,
    val scope: GlobalSearchScope,
    /** Directories outside the root that belong to its family. */
    val familyDirs: List<VirtualFile>,
    /** Whether Molecule files belong to the results ([MoleculeView.INCLUDE]) or not. */
    val view: MoleculeView = MoleculeView.INCLUDE,
) {
    /** True when [file], classified as [context], belongs to [root]'s results (in [view]). */
    fun admits(file: VirtualFile, context: FileContext): Boolean =
        (context.root.dir == root.dir || familyDirs.any { VfsUtilCore.isAncestor(it, file, false) }) &&
            (view.includesMolecule || !MoleculeVisibility.isMoleculeFile(context.root, file))

    companion object {
        private const val WORKTREES = ".claude/worktrees"

        /** The family of [root], computed from the current roots of [workspace], as a request with [view] sees it. */
        fun of(
            project: Project,
            root: AnsibleRoot,
            workspace: AnsibleWorkspace = AnsibleWorkspace.getInstance(project),
            view: MoleculeView = MoleculeView.INCLUDE,
        ): RootFamily {
            val roots = workspace.roots()
            val excluded = roots.filter { it.dir != root.dir && VfsUtilCore.isAncestor(root.dir, it.dir, true) }.map { it.dir } +
                listOfNotNull(root.dir.findFileByRelativePath(WORKTREES)?.takeIf { it.isDirectory })
            val family = buildList {
                if (root.kind == RootKind.NESTED_PLAYBOOK) {
                    root.environmentsDir?.let(::add)
                    for (def in ProjectLayoutService.getInstance(project).layout(root).inventories) {
                        addAll(def.varsDirs)
                        def.sources.mapNotNullTo(this) { it.file }
                    }
                }
                addAll(root.rolesDirs)
            }.filter { dir ->
                dir.isValid && !VfsUtilCore.isAncestor(root.dir, dir, false) &&
                    // A non-detached root never reaches into a detached worktree, whatever its configuration says.
                    (root.detached || workspace.rootFor(dir)?.detached != true)
            }.distinct()
            var scope = GlobalSearchScopesCore.directoryScope(project, root.dir, true)
            if (excluded.isNotEmpty()) {
                scope = scope.intersectWith(
                    GlobalSearchScope.notScope(GlobalSearchScopesCore.directoriesScope(project, true, *excluded.toTypedArray())),
                )
            }
            if (family.isNotEmpty()) {
                scope = scope.uniteWith(GlobalSearchScopesCore.directoriesScope(project, true, *family.toTypedArray()))
            }
            return RootFamily(root, scope, family, view)
        }
    }
}
