package de.terletzkiy.ansibility.completion.jinja

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.indexing.FileBasedIndex
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.InventoryService
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.ValueShape
import de.terletzkiy.ansibility.index.DefEntry
import de.terletzkiy.ansibility.index.DefSite
import de.terletzkiy.ansibility.index.LiteralType
import de.terletzkiy.ansibility.index.VarDefIndex
import de.terletzkiy.ansibility.model.role.RoleLayout
import org.jetbrains.yaml.YAMLLanguage
import java.util.concurrent.ConcurrentHashMap

/** Where a catalog name is written: a role's spec, defaults or vars, or an inventory source. */
internal enum class CatalogSite { SPEC, DEFAULTS, VARS, INVENTORY }

/**
 * One variable name of the [RootNameCatalog], as `ansible.var.def` stores it: never a value of a vault file, a `vault_*`
 * key or a `!vault` scalar ([preview] follows the index's vault-safe rule).
 */
internal class CatalogEntry(
    val name: String,
    val site: CatalogSite,
    /** The declaring role for role sites. */
    val role: String?,
    /** The environment of an inventory-layer file (`environments/<env>`); null for playbook-level vars. */
    val environment: String?,
    val location: SourceLocation,
    val preview: String?,
    val literalType: LiteralType,
    val shape: ValueShape,
    /** The argument-spec entry point of a spec option. */
    val entryPoint: String?,
)

/**
 * Variable names of one root for completion (plan A.5 tiers T3–T5 and the root-wide fallback): every role's spec
 * options, `defaults/` and `vars/` keys, and every inventory name (inventory `group_vars`/`host_vars`, `hosts.yml`
 * inline vars, playbook `group_vars`/`host_vars`). Built from the `ansible.var.def` data of those files only.
 */
internal class RootNameCatalog(
    /** Role name → variable name → its entries in that role (spec first, then defaults, then vars). */
    val roles: Map<String, Map<String, List<CatalogEntry>>>,
    /** Variable name → inventory entries, sorted by path. */
    val inventory: Map<String, List<CatalogEntry>>,
    /** Molecule scenario directory → variable name → the scenario's inventory entries (inline and linked vars). */
    val molecule: Map<VirtualFile, Map<String, List<CatalogEntry>>> = emptyMap(),
) {
    /** Every name some role of the root declares (spec, defaults or vars). */
    val roleDeclared: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) { roles.values.flatMapTo(HashSet()) { it.keys } }

    /** The entries of [role], by name. */
    fun entriesOf(role: String): Map<String, List<CatalogEntry>> = roles[role].orEmpty()
}

/**
 * Project service holding one [RootNameCatalog] per root. Each file's entries are read with
 * `FileBasedIndex.getFileData` and kept until the file (or its unsaved document) changes; the catalog is re-assembled
 * from those per-file lists when the Ansible structure, the `ansible.var.def` index, YAML PSI or the project roots
 * change. Call in a read action in smart mode.
 */
@Service(Service.Level.PROJECT)
internal class JinjaNameCatalogs(private val project: Project) {
    private val catalogs = ConcurrentHashMap<VirtualFile, CachedValue<Pair<AnsibleRoot, RootNameCatalog>>>()
    private val files = ConcurrentHashMap<VirtualFile, FileEntries>()

    private val indexStamp = ModificationTracker { FileBasedIndex.getInstance().getIndexModificationStamp(VarDefIndex.NAME, project) }

    private class FileEntries(val stamp: Long, val defs: Map<String, List<DefEntry>>)

    fun catalog(root: AnsibleRoot): RootNameCatalog {
        if (catalogs.size > MAX_ROOTS) catalogs.clear()
        if (files.size > MAX_FILES) files.clear()
        val cached = catalogs.computeIfAbsent(root.dir) {
            CachedValuesManager.getManager(project).createCachedValue(
                {
                    CachedValueProvider.Result.create(
                        root to build(root),
                        AnsibleWorkspace.getInstance(project).structureTracker,
                        indexStamp,
                        PsiModificationTracker.getInstance(project).forLanguage(YAMLLanguage.INSTANCE),
                        ProjectRootManager.getInstance(project),
                    )
                },
                false,
            )
        }
        val (builtFor, catalog) = cached.value
        return if (builtFor == root) catalog else build(root)
    }

    private fun build(root: AnsibleRoot): RootNameCatalog {
        val roles = LinkedHashMap<String, Map<String, List<CatalogEntry>>>()
        for (ref in RoleRegistry.getInstance(project).roles(root)) {
            ProgressManager.checkCanceled()
            val names = LinkedHashMap<String, MutableList<CatalogEntry>>()
            val sources = listOfNotNull(RoleLayout.specFile(ref.dir)?.let { it to CatalogSite.SPEC }) +
                RoleLayout.defaultsFiles(ref.dir).map { it to CatalogSite.DEFAULTS } +
                RoleLayout.varsFiles(ref.dir).map { it to CatalogSite.VARS }
            for ((file, site) in sources) {
                for ((name, entry) in entriesOf(file)) {
                    val wanted = when (site) {
                        CatalogSite.SPEC -> entry.site == DefSite.SPEC_OPTION
                        CatalogSite.DEFAULTS -> entry.site == DefSite.DEFAULTS
                        CatalogSite.VARS -> entry.site == DefSite.VARS
                        CatalogSite.INVENTORY -> false
                    }
                    if (wanted) names.getOrPut(name) { ArrayList(2) } += catalogEntry(name, site, ref.name, null, file, entry)
                }
            }
            roles[ref.name] = names
        }
        val inventory = HashMap<String, MutableList<CatalogEntry>>()
        val service = InventoryService.getInstance(project)
        val sources = LinkedHashMap<VirtualFile, String?>()
        for (inv in service.inventories(root)) {
            sources.putIfAbsent(inv.hostsFile, inv.environment)
            inv.varFiles.forEach { sources.putIfAbsent(it.file, it.environment) }
        }
        service.playbookVarFiles(root).forEach { sources.putIfAbsent(it.file, null) }
        // `group_vars`/`host_vars` of environments whose inventory file is missing still hold names worth offering.
        for (env in root.environmentsDir?.children.orEmpty()) {
            if (!env.isDirectory) continue
            for (dir in listOfNotNull(env.findChild(GROUP_VARS), env.findChild(HOST_VARS))) {
                VfsUtilCore.iterateChildrenRecursively(dir, null) { file ->
                    ProgressManager.checkCanceled()
                    if (!file.isDirectory) sources.putIfAbsent(file, env.name)
                    true
                }
            }
        }
        for ((file, environment) in sources) {
            for ((name, entry) in entriesOf(file)) {
                if (entry.site != DefSite.INVENTORY_KEY && entry.site != DefSite.INVENTORY_INLINE) continue
                inventory.getOrPut(name) { ArrayList(2) } += catalogEntry(name, CatalogSite.INVENTORY, null, environment, file, entry)
            }
        }
        inventory.values.forEach { list -> list.sortWith(compareBy({ it.location.file.path }, { it.location.offset })) }
        return RootNameCatalog(roles, inventory, molecule(root))
    }

    /** The inline `provisioner.inventory` vars and the linked var files of every molecule scenario of [root]. */
    private fun molecule(root: AnsibleRoot): Map<VirtualFile, Map<String, List<CatalogEntry>>> {
        val result = LinkedHashMap<VirtualFile, Map<String, List<CatalogEntry>>>()
        for (scenario in InventoryService.getInstance(project).moleculeInventories(root)) {
            ProgressManager.checkCanceled()
            val names = LinkedHashMap<String, MutableList<CatalogEntry>>()
            for (definition in scenario.inlineVars) {
                names.getOrPut(definition.name) { ArrayList(1) } += CatalogEntry(
                    definition.name, CatalogSite.INVENTORY, scenario.roleName, MOLECULE, definition.location,
                    definition.preview, LiteralType.NONE, definition.valueShape, null,
                )
            }
            for (varFile in scenario.inventory.varFiles) {
                for ((name, entry) in entriesOf(varFile.file)) {
                    names.getOrPut(name) { ArrayList(1) } += catalogEntry(name, CatalogSite.INVENTORY, scenario.roleName, MOLECULE, varFile.file, entry)
                }
            }
            result[scenario.scenarioDir] = names
        }
        return result
    }

    /** The `ansible.var.def` entries of [file], as (name, entry) pairs, re-read only when the file changed. */
    private fun entriesOf(file: VirtualFile): List<Pair<String, DefEntry>> {
        if (!file.isValid || file.isDirectory) return emptyList()
        val stamp = FileDocumentManager.getInstance().getCachedDocument(file)?.modificationStamp ?: file.modificationStamp
        val cached = files[file]
        val defs = if (cached != null && cached.stamp == stamp) {
            cached.defs
        } else {
            FileBasedIndex.getInstance().getFileData(VarDefIndex.NAME, file, project).also { files[file] = FileEntries(stamp, it) }
        }
        return defs.flatMap { (name, entries) -> entries.map { name to it } }
    }

    private fun catalogEntry(
        name: String,
        site: CatalogSite,
        role: String?,
        environment: String?,
        file: VirtualFile,
        entry: DefEntry,
    ) = CatalogEntry(name, site, role, environment, SourceLocation(file, entry.offset), entry.preview, entry.literalType, entry.shape, entry.entryPoint)

    companion object {
        private const val MAX_ROOTS = 64
        private const val MAX_FILES = 20_000
        private const val GROUP_VARS = "group_vars"
        private const val HOST_VARS = "host_vars"

        /** The environment label of molecule scenario entries. */
        const val MOLECULE = "molecule"

        fun getInstance(project: Project): JinjaNameCatalogs = project.service()
    }
}
