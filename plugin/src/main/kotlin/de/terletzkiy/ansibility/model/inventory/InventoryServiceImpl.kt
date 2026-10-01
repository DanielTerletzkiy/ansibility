package de.terletzkiy.ansibility.model.inventory

import com.intellij.openapi.project.Project
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.Inventory
import de.terletzkiy.ansibility.api.InventoryService
import de.terletzkiy.ansibility.api.MoleculeInventory
import de.terletzkiy.ansibility.api.VarFile

/**
 * The project's [InventoryService] (plan A.5, F4.1, R6), backed by the cached [InventoryModels]:
 * - [inventories]: one [Inventory] per `environments/<env>/hosts.y{a,}ml` of a PROJECT root, sorted by environment.
 *   Groups carry parents, children, direct hosts, inline var keys, depth and priority; hosts carry `ansible_host`
 *   as written and their groups in the order variables apply (`all`, then `sort_groups`). Each inventory lists its
 *   `group_vars`/`host_vars` files in load order, with orphans (groups or hosts it does not define) last.
 *   A NESTED_PLAYBOOK root returns its parent's inventories (same [Inventory.rootDir]); a ROLE_LIBRARY root none.
 * - [playbookVarFiles]: `<root>/group_vars` and `<root>/host_vars`. A nested root only has its own, so plays
 *   defined there never load the parent's playbook group_vars.
 * - [moleculeInventories]: the molecule pseudo-inventories of the root's roles. Only scenarios whose `molecule.yml`
 *   the workspace classifies as [FileKind.MOLECULE_CONFIG] count, so molecule support switched off, ignored paths and
 *   ghost role directories hide them the same way they hide the files themselves.
 *
 * Every method may be called from any thread; it takes a read lock when the caller holds none.
 */
class InventoryServiceImpl(private val project: Project) : InventoryService {
    private val models: InventoryModels get() = InventoryModels.getInstance(project)

    override fun inventories(root: AnsibleRoot): List<Inventory> = models.environments(root).map { it.inventory }

    override fun playbookVarFiles(root: AnsibleRoot): List<VarFile> = models.playbookVarFiles(root, root.dir)

    override fun moleculeInventories(root: AnsibleRoot): List<MoleculeInventory> = readLocked {
        val workspace = AnsibleWorkspace.getInstance(project)
        models.moleculeInventories(root).filter { workspace.contextOf(it.configFile)?.kind == FileKind.MOLECULE_CONFIG }
    }
}
