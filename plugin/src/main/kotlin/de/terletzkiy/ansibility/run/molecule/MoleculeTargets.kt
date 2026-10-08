package de.terletzkiy.ansibility.run.molecule

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.RoleRef
import de.terletzkiy.ansibility.api.RoleTests
import de.terletzkiy.ansibility.api.WorkspaceScope
import de.terletzkiy.ansibility.toolwindow.model.AnsibleTreeNode
import de.terletzkiy.ansibility.toolwindow.model.RoleFileNode
import de.terletzkiy.ansibility.toolwindow.model.RoleNameNode
import de.terletzkiy.ansibility.toolwindow.model.RoleNode
import de.terletzkiy.ansibility.toolwindow.model.RolesNode
import de.terletzkiy.ansibility.toolwindow.model.RootNode
import de.terletzkiy.ansibility.toolwindow.model.RootSnapshot
import de.terletzkiy.ansibility.toolwindow.model.WorkspaceSnapshot
import de.terletzkiy.ansibility.toolwindow.model.rootsKnown

/**
 * Which roles a Molecule test run of a selection runs (plan amendments R16, R19): only roles with tests
 * ([RootSnapshot.testedRoles]), each once.
 *
 * - In the Ansibility tool window a role-name row stands for its copies in the roots the scope picker covers
 *   ([RoleNameNode.copiesIn], R19/D143; All roots: every copy; none in scope: nothing). Explicit picks stand for
 *   themselves whatever the scope: an expanded copy, a repo or its "Roles" folder, a role's folder or a scenario.
 * - With nothing selected, the toolbar runs every role of the roots in the scope ([inScope]); it shows only when
 *   that is not nothing ([anyInScope], D140). Both pick roots by the one rule [RootSnapshot.isIn], and a role by the
 *   root it belongs to ([WorkspaceSnapshot.roleCopies]), like the role-name rows.
 * - The Project view has no scope picker: the selected role, `molecule` folder or scenario ([ofFiles]).
 */
object MoleculeTargets {
    /** The targets of the tool window rows [nodes]; [scope] is read only for role-name rows. */
    fun ofNodes(nodes: List<AnsibleTreeNode>, scope: WorkspaceScope): List<MoleculeTarget> = distinct(
        nodes.flatMap { node ->
            when (node) {
                is RoleNameNode -> node.copiesIn(scope).mapNotNull { (root, role) -> target(root, role) }
                is RoleNode -> listOfNotNull(target(node.root, node.role))
                is RolesNode -> node.root.ownRoles.sortedBy { it.name }.mapNotNull { target(node.root, it) }
                is RootNode -> node.root.ownRoles.sortedBy { it.name }.mapNotNull { target(node.root, it) }
                is RoleFileNode -> listOfNotNull(ofRoleFile(node))
                else -> emptyList()
            }
        },
    )

    /** The roots of [snapshot] that [scope] covers ([RootSnapshot.isIn]), in display order. */
    fun rootsInScope(snapshot: WorkspaceSnapshot, scope: WorkspaceScope): List<RootSnapshot> = snapshot.roots.filter { it.isIn(scope) }

    /**
     * Every role with tests that belongs to a root of [snapshot] inside [scope] ([WorkspaceSnapshot.ownedRoles]), by
     * root, then by name.
     */
    fun inScope(snapshot: WorkspaceSnapshot, scope: WorkspaceScope): List<MoleculeTarget> = distinct(
        rootsInScope(snapshot, scope).flatMap { root -> snapshot.ownedRoles(root).sortedBy { it.name }.mapNotNull { target(root, it) } },
    )

    /**
     * Whether [inScope] has a target (the toolbar button shows, D140): set lookups in the snapshot, no VFS. While a named
     * scope's roots are still being worked out in the background ([rootsKnown]), every root counts rather than walking
     * here; a click then finds the real answer.
     */
    fun anyInScope(snapshot: WorkspaceSnapshot, scope: WorkspaceScope): Boolean =
        (if (scope.rootsKnown()) rootsInScope(snapshot, scope) else snapshot.roots).any { snapshot.hasTestedRoles(it) }

    /** Whether a copy of the role-name row [node] has tests, in the scope or not. */
    fun hasTests(node: RoleNameNode): Boolean = node.copies.any { (root, role) -> role.dir in root.testedRoles }

    /** The targets of folders selected in the Project view: a role, its `molecule` folder or a scenario. Read action. */
    fun ofFiles(project: Project, files: List<VirtualFile>): List<MoleculeTarget> {
        val tests = RoleTests.getInstance(project)
        return distinct(
            files.filter { it.isDirectory }.mapNotNull { file ->
                val roleDir = MoleculeRunContext.roleDirOf(file) ?: return@mapNotNull null
                if (!tests.hasTests(roleDir)) return@mapNotNull null
                val label = AnsibleWorkspace.getInstance(project).rootFor(roleDir)?.displayName ?: roleDir.parent?.name.orEmpty()
                MoleculeTarget(MoleculeSpec(roleDir.path, scenarioOf(roleDir, file)), roleDir.name, label)
            },
        )
    }

    private fun target(root: RootSnapshot, role: RoleRef): MoleculeTarget? =
        if (role.dir !in root.testedRoles) null else MoleculeTarget(MoleculeSpec(role.dir.path), role.name, root.root.displayName)

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
