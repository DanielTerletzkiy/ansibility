package de.terletzkiy.ansibility.run.molecule

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.RoleRef
import de.terletzkiy.ansibility.api.WorkspaceScope
import de.terletzkiy.ansibility.toolwindow.model.AnsibleTreeNode
import de.terletzkiy.ansibility.toolwindow.model.RoleFileNode
import de.terletzkiy.ansibility.toolwindow.model.RoleNameNode
import de.terletzkiy.ansibility.toolwindow.model.RoleNode
import de.terletzkiy.ansibility.toolwindow.model.RolesNode
import de.terletzkiy.ansibility.toolwindow.model.RootNode
import de.terletzkiy.ansibility.toolwindow.model.RootSnapshot
import de.terletzkiy.ansibility.toolwindow.model.WorkspaceSnapshot

/**
 * Which roles a Molecule test run of a selection runs (plan amendment R16, R17): only roles with scenarios, each once.
 * In the Ansibility tool window a role-name row stands for all its copies (every repo's copy of a shared role; expand
 * the row to pick some); a copy, a repo or its "Roles" folder, and a role's folder or scenario stand for themselves.
 * With nothing selected, the toolbar runs every role of the roots in the workspace scope ([inScope]).
 */
object MoleculeTargets {
    /** The targets of the tool window rows [nodes]. */
    fun ofNodes(nodes: List<AnsibleTreeNode>): List<MoleculeTarget> = distinct(
        nodes.flatMap { node ->
            when (node) {
                is RoleNameNode -> node.copies.mapNotNull { (root, role) -> target(root, role) }
                is RoleNode -> listOfNotNull(target(node.root, node.role))
                is RolesNode -> node.root.ownRoles.sortedBy { it.name }.mapNotNull { target(node.root, it) }
                is RootNode -> node.root.ownRoles.sortedBy { it.name }.mapNotNull { target(node.root, it) }
                is RoleFileNode -> listOfNotNull(ofRoleFile(node))
                else -> emptyList()
            }
        },
    )

    /** Every role with scenarios of the roots of [snapshot] inside [scope], by root, then by name. */
    fun inScope(snapshot: WorkspaceSnapshot, scope: WorkspaceScope): List<MoleculeTarget> = distinct(
        snapshot.roots.filter { root -> scope.roots.any { it.dir == root.dir } }
            .flatMap { root -> root.ownRoles.sortedBy { it.name }.mapNotNull { target(root, it) } },
    )

    /** The targets of folders selected in the Project view: a role, its `molecule` folder or a scenario. Read action. */
    fun ofFiles(project: Project, files: List<VirtualFile>): List<MoleculeTarget> = distinct(
        files.filter { it.isDirectory }.mapNotNull { file ->
            val roleDir = MoleculeRunContext.roleDirOf(file) ?: return@mapNotNull null
            if (MoleculeRunContext.scenariosOf(roleDir).isEmpty()) return@mapNotNull null
            val label = AnsibleWorkspace.getInstance(project).rootFor(roleDir)?.displayName ?: roleDir.parent?.name.orEmpty()
            MoleculeTarget(MoleculeSpec(roleDir.path, scenarioOf(roleDir, file)), roleDir.name, label)
        },
    )

    private fun target(root: RootSnapshot, role: RoleRef): MoleculeTarget? =
        if (MoleculeRunContext.scenariosOf(role.dir).isEmpty()) null else MoleculeTarget(MoleculeSpec(role.dir.path), role.name, root.root.displayName)

    /** A folder inside a role: the role, or a scenario of it. */
    private fun ofRoleFile(node: RoleFileNode): MoleculeTarget? {
        val owner = generateSequence(node.parent) { it.parent }.filterIsInstance<RoleNode>().firstOrNull() ?: return null
        if (!node.file.isDirectory) return null
        val base = target(owner.root, owner.role) ?: return null
        return base.copy(spec = base.spec.copy(scenario = scenarioOf(owner.role.dir, node.file)))
    }

    /** The scenario [file] lies in when it has a `molecule.yml`, else "" (every scenario). */
    private fun scenarioOf(roleDir: VirtualFile, file: VirtualFile): String =
        MoleculeRunContext.scenarioOf(file)?.takeIf { it in MoleculeRunContext.scenariosOf(roleDir) }.orEmpty()

    /** Each role and scenario once; a whole role covers its scenarios. */
    private fun distinct(targets: List<MoleculeTarget>): List<MoleculeTarget> {
        val whole = targets.filter { it.spec.scenario.isEmpty() }.map { it.spec.roleDir }.toSet()
        return targets.filter { it.spec.scenario.isEmpty() || it.spec.roleDir !in whole }.distinctBy { it.spec.roleDir to it.spec.scenario }
    }
}
