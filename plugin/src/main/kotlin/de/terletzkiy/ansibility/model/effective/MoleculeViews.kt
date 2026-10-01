package de.terletzkiy.ansibility.model.effective

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.api.MoleculeInventory
import de.terletzkiy.ansibility.model.inventory.InventoryModels
import de.terletzkiy.ansibility.model.inventory.ModelCache
import de.terletzkiy.ansibility.model.inventory.VarsConfig
import de.terletzkiy.ansibility.model.inventory.VarsDocuments
import de.terletzkiy.ansibility.model.inventory.readLocked
import de.terletzkiy.ansibility.semantics.inventory.InventoryGraph
import de.terletzkiy.ansibility.semantics.precedence.InventoryVarSources
import de.terletzkiy.ansibility.semantics.precedence.PrecedenceEngine
import de.terletzkiy.ansibility.semantics.precedence.VarLayer
import de.terletzkiy.ansibility.semantics.precedence.VarOwner
import de.terletzkiy.ansibility.semantics.precedence.VarSource

/**
 * Inventory views of molecule scenario hosts (plan amendment R7/R8, F8.10): the pseudo-inventory molecule generates
 * from `platforms` and `provisioner.inventory.hosts`, with the variables molecule writes next to it, evaluated by the
 * same [PrecedenceEngine] as an environment:
 * - inline variables of `provisioner.inventory.hosts` and of a linked hosts file (levels 3 and 8);
 * - `provisioner.inventory.group_vars`/`host_vars` of `molecule.yml` and the files of
 *   `provisioner.inventory.links.group_vars`/`host_vars`, as inventory-adjacent sources
 *   ([VarLayer.MOLECULE_INVENTORY]), the inline ones first.
 *
 * The scenario's graph and inline sections come from [InventoryModels.moleculeModel] (a
 * [de.terletzkiy.ansibility.model.inventory.MoleculeModel]), parsed once per `molecule.yml`
 * version. Host names are kept as written (`postfix_deb13-${MOLECULE_RUN_ID:-local}`). One [ModelCache] entry per
 * scenario holds the views of all its hosts; it depends on the scenario's model and the linked vars files it loaded.
 * Call from any thread; a read lock is taken when the caller holds none.
 */
@Service(Service.Level.PROJECT)
class MoleculeViews(private val project: Project) {
    private data class Key(val rootDir: VirtualFile, val roleName: String?, val configFile: VirtualFile)

    private val cache = ModelCache<Key, Map<String, HostView>>(project, "views.molecule")

    /** The view of [host] in [molecule] (a scenario of [root]), or null when the scenario has no such host. */
    fun view(root: AnsibleRoot, molecule: MoleculeInventory, host: String): HostView? = readLocked {
        if (host !in molecule.inventory.hosts) null else cache.get(Key(root.dir, molecule.roleName, molecule.configFile)) { compute(root, molecule) }[host]
    }

    private fun compute(root: AnsibleRoot, molecule: MoleculeInventory): Map<String, HostView> {
        val model = InventoryModels.getInstance(project).moleculeModel(root, molecule)
        val config = VarsConfig.of(root)
        val origins = HashMap<String, SourceOrigin>()
        val configFile = model.inventory.configFile
        val sources = ArrayList<VarSource>()

        sources += InventoryVarSources.inline(model.graph) { index ->
            val file = model.sourceFiles.getOrElse(index) { configFile }
            file.url.also { origins[it] = SourceOrigin(file) }
        }
        var order = 0
        for ((section, isGroup) in listOf(model.groupVars to true, model.hostVars to false)) {
            for (entry in section?.entries.orEmpty()) {
                ProgressManager.checkCanceled()
                val id = "${configFile.url}#${if (isGroup) GROUP_VARS else HOST_VARS}/${entry.key.text}"
                val owner = if (isGroup) VarOwner.group(entry.key.text) else VarOwner.Host(entry.key.text)
                VarSource.fromDocument(VarLayer.MOLECULE_INVENTORY, owner, id, order++, entry.value)?.let {
                    sources += it
                    origins[id] = SourceOrigin(configFile)
                }
            }
        }
        for (varFile in model.inventory.inventory.varFiles) {
            ProgressManager.checkCanceled()
            val owner = varFile.host?.let { VarOwner.Host(it) } ?: VarOwner.group(varFile.group ?: InventoryGraph.ALL)
            val id = varFile.file.url
            VarSource.fromDocument(VarLayer.MOLECULE_INVENTORY, owner, id, order++, VarsDocuments.load(project, varFile.file))?.let {
                sources += it
                origins[id] = SourceOrigin(varFile.file)
            }
        }

        val engine = PrecedenceEngine(config.hashBehaviour, config.precedence)
        val environment = HostKey.moleculeEnvironment(model.inventory.roleName, model.inventory.scenarioDir.name)
        val views = LinkedHashMap<String, HostView>()
        for (host in model.graph.hosts.keys) {
            ProgressManager.checkCanceled()
            engine.inventoryView(model.graph, host, sources)?.let { views[host] = HostView(engine, it, origins, environment) }
        }
        return views
    }

    companion object {
        private const val GROUP_VARS = "group_vars"
        private const val HOST_VARS = "host_vars"

        fun getInstance(project: Project): MoleculeViews = project.service()
    }
}
