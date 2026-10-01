package de.terletzkiy.ansibility.model.effective

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.EffectiveVars
import de.terletzkiy.ansibility.api.EffectiveVarsService
import de.terletzkiy.ansibility.model.inventory.InventoryModels

/**
 * The project's [EffectiveVarsService] (plan A.5 InventoryView, F6.5, X13): levels 3–10 for one host, computed by
 * the shared [HostViews] from
 * - the inline vars of `hosts.yml` (`all.vars`, group `vars`, host entries),
 * - `group_vars`/`host_vars` next to the inventory (`environments/<env>`),
 * - `group_vars`/`host_vars` of the playbook directory, when one is given,
 *
 * under the root's `ansible.cfg` `precedence`, `hash_behaviour` and `yaml_valid_extensions`. `playbook_vars_root` has
 * no effect on an inventory view: without a task ansible-core always uses the playbook directory.
 *
 * Root scoping: a [inventoryView] `playbookDir` outside the root (and, for a nested root, outside its parent) is
 * ignored, so no definition from another root is ever returned. Views are cached per (environment, playbook dir) and
 * depend only on the files they were built from (plan amendment R7/R8, A.9).
 *
 * Every method may be called from any thread; it takes a read lock when the caller holds none.
 */
class EffectiveVarsServiceImpl(private val project: Project) : EffectiveVarsService {
    private val models: InventoryModels get() = InventoryModels.getInstance(project)

    override fun hostsOf(root: AnsibleRoot, environment: String): List<String> =
        models.environment(root, environment)?.graph?.hosts?.keys?.toList().orEmpty()

    override fun inventoryView(root: AnsibleRoot, environment: String, host: String, playbookDir: VirtualFile?): EffectiveVars? =
        HostViews.getInstance(project).view(root, environment, host, playbookDir)?.vars
}
