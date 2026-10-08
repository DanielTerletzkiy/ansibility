package de.terletzkiy.ansibility.completion.keys

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.indexing.FileBasedIndex
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.InventoryService
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.index.DefSite
import de.terletzkiy.ansibility.index.VarDefIndex
import de.terletzkiy.ansibility.model.role.RoleDefaults
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YVault
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import de.terletzkiy.ansibility.yaml.YamlPaths
import de.terletzkiy.ansibility.yaml.YamlPsi
import org.jetbrains.yaml.psi.YAMLAlias
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import java.util.concurrent.ConcurrentHashMap

/** One role's declaration of a top-level variable: a spec option, or a key of the role's `defaults/`. */
internal class KeyDeclaration(
    val role: String,
    /** The argument-spec option; null for a key that only the role's defaults define. */
    val option: OptionSpec?,
    /** The entry point of [option]. */
    val entryPoint: String?,
    /** Required by the spec with neither a spec default nor a key in the role's defaults: inventory must set it. */
    val requiredWithoutDefault: Boolean,
    /** For a defaults-only key: the YAML type of the default value (`str`, `dict`, …). */
    val defaultType: String?,
)

/** The top-level variable names the roles of one root declare, each with its declarations in role-name order. */
internal class TopLevelKeys(val byName: Map<String, List<KeyDeclaration>>)

/**
 * The per-root data of vars-file key completion (plan F4.2), cached so that typing in a vars file does not rebuild it.
 * The platform's caches of roles, plays and inventories depend on every YAML change, so after each keystroke they are
 * rebuilt; these caches depend only on the files they read (their document or file stamps, [FilesTracker]), the Ansible
 * structure and the project roots:
 *
 * - [topLevel]: every argument-spec option (all entry points, `main` first) and every defaults-only key of the root's
 *   roles ([RoleRegistry]), each role's part cached on its spec and defaults files, so typing in a role's defaults
 *   rebuilds that role only;
 * - [rolesForHosts]: the result of a computation over the root's playbooks, role `meta/main.yml` files and
 *   inventories ([AppliedRoles]);
 * - [inventoryNames]: the variable names written in the root's inventory (`hosts.yml` group vars, inventory and
 *   playbook `group_vars`/`host_vars` files); the file list is cached, the names are read from the `ansible.var.def`
 *   index on every call (no PSI).
 *
 * Names only: no value is read beyond its YAML type, so no vault value is ever touched. Call in a read action, in smart
 * mode (the index).
 */
@Service(Service.Level.PROJECT)
internal class KeyCatalogs(private val project: Project) {
    private val catalogs = ConcurrentHashMap<AnsibleRoot, CachedValue<TopLevelKeys>>()
    private val roleKeys = ConcurrentHashMap<Pair<AnsibleRoot, VirtualFile>, CachedValue<RoleKeys>>()
    private val applied = ConcurrentHashMap<HostsKey, CachedValue<Set<String>>>()
    private val inventorySources = ConcurrentHashMap<AnsibleRoot, CachedValue<InventorySources>>()

    /** A group or host of one environment of a root (null environment: every environment). */
    data class HostsKey(val root: AnsibleRoot, val environment: String?, val group: String?, val host: String?)

    /** A computed value and the files it was computed from. */
    class Tracked<T>(val value: T, val files: Collection<VirtualFile>)

    /** Inline group variables of the root's `hosts.yml` files and the var files of its inventory. */
    private class InventorySources(val inlineNames: Set<String>, val varFiles: List<VirtualFile>)

    private class NamedDeclaration(val name: String, val declaration: KeyDeclaration)

    /** One role's declarations and the files they were read from. */
    private class RoleKeys(val declarations: List<NamedDeclaration>, val files: List<VirtualFile>)

    /** The top-level variables the roles of [root] declare. */
    fun topLevel(root: AnsibleRoot): TopLevelKeys = cached(catalogs, root) { tracked(buildTopLevel(root)) }

    /** The roles [compute] finds for [key], cached until one of the files it read changes. */
    fun rolesForHosts(key: HostsKey, compute: () -> Tracked<Set<String>>): Set<String> = cached(applied, key) { tracked(compute()) }

    /** The variable names the inventories of [root] set, in inventory order. */
    fun inventoryNames(root: AnsibleRoot): Set<String> {
        val sources = cached(inventorySources, root) { tracked(buildInventorySources(root)) }
        val names = LinkedHashSet(sources.inlineNames)
        val index = FileBasedIndex.getInstance()
        for (file in sources.varFiles) {
            ProgressManager.checkCanceled()
            if (!file.isValid) continue
            for ((name, entries) in index.getFileData(VarDefIndex.NAME, file, project)) {
                if (entries.any { it.site == DefSite.INVENTORY_KEY }) names += name
            }
        }
        return names
    }

    /** Merges the roles' declarations; each role's part is cached on its own, so an edit rebuilds one role only. */
    private fun buildTopLevel(root: AnsibleRoot): Tracked<TopLevelKeys> {
        val files = ArrayList<VirtualFile>()
        val byName = LinkedHashMap<String, MutableList<KeyDeclaration>>()
        for (ref in RoleRegistry.getInstance(project).roles(root)) {
            ProgressManager.checkCanceled()
            val keys = cached(roleKeys, root to ref.dir) { tracked(buildRole(root, ref.name)) }
            files += keys.files
            for (declaration in keys.declarations) byName.getOrPut(declaration.name) { ArrayList() } += declaration.declaration
        }
        return Tracked(TopLevelKeys(byName), files)
    }

    /** The spec options (all entry points, `main` first) and the defaults-only keys of role [name] of [root]. */
    private fun buildRole(root: AnsibleRoot, name: String): Tracked<RoleKeys> {
        val info = RoleRegistry.getInstance(project).role(root, name) ?: return Tracked(RoleKeys(emptyList(), emptyList()), emptyList())
        val files = listOfNotNull(info.specFile) + info.defaultsFiles
        val defaults = LinkedHashMap<String, String?>()
        for (file in info.defaultsFiles) topLevelKeys(file).forEach { (key, type) -> defaults.putIfAbsent(key, type) }
        // A role default exists only in the files ansible-core loads (`defaults/<x>.yml` needs `defaults_from`); the spec's
        // `default:` never sets the variable (plan amendment R23, D174).
        val roleDefaults = RoleDefaults.loadedFiles(info.ref.dir).flatMapTo(HashSet()) { topLevelKeys(it).keys }
        val declarations = ArrayList<NamedDeclaration>()
        val declared = HashSet<String>()
        for ((entryPoint, spec) in info.argumentSpecs.entries.sortedBy { if (it.key == MAIN) 0 else 1 }) {
            for (option in spec.options.values) {
                if (!declared.add(option.name)) continue
                val required = option.required && option.name !in roleDefaults
                declarations += NamedDeclaration(option.name, KeyDeclaration(name, option, entryPoint, required, null))
            }
        }
        for ((key, type) in defaults) {
            if (key !in declared) declarations += NamedDeclaration(key, KeyDeclaration(name, null, null, false, type))
        }
        return Tracked(RoleKeys(declarations, files), files)
    }

    private fun buildInventorySources(root: AnsibleRoot): Tracked<InventorySources> {
        val inventory = InventoryService.getInstance(project)
        val inline = LinkedHashSet<String>()
        val varFiles = LinkedHashSet<VirtualFile>()
        val hostsFiles = ArrayList<VirtualFile>()
        for (environment in inventory.inventories(root)) {
            ProgressManager.checkCanceled()
            hostsFiles += environment.hostsFile
            environment.groups.values.forEach { inline += it.inlineVarKeys }
            environment.varFiles.mapTo(varFiles) { it.file }
        }
        inventory.playbookVarFiles(root).mapTo(varFiles) { it.file }
        return Tracked(InventorySources(inline, varFiles.toList()), hostsFiles)
    }

    /** The top-level keys of [file] with the YAML type of each value; cached on the file's PSI until it changes. */
    private fun topLevelKeys(file: VirtualFile): Map<String, String?> {
        val psi = (if (file.isValid) PsiManager.getInstance(project).findFile(file) else null) ?: return emptyMap()
        return CachedValuesManager.getCachedValue(psi, TOP_LEVEL_KEYS) {
            val keys = LinkedHashMap<String, String?>()
            val yaml = YamlFiles.yamlFile(project, file)
            if (yaml != null) {
                for (keyValue in YamlPaths.topLevelKeyValues(yaml)) {
                    if (YamlPsi.isMergeKey(keyValue)) continue
                    keys.putIfAbsent(PsiYValueAdapter.keyOf(keyValue).text, typeName(keyValue))
                }
            }
            CachedValueProvider.Result.create(keys, psi)
        }
    }

    private fun <T> tracked(value: Tracked<T>): CachedValueProvider.Result<T> = CachedValueProvider.Result.create(
        value.value,
        AnsibleWorkspace.getInstance(project).structureTracker,
        ProjectRootManager.getInstance(project),
        FilesTracker(value.files),
    )

    private fun <K : Any, V> cached(cache: ConcurrentHashMap<K, CachedValue<V>>, key: K, provider: () -> CachedValueProvider.Result<V>): V {
        if (cache.size > MAX_KEYS) cache.clear()
        val value = cache.computeIfAbsent(key) { CachedValuesManager.getManager(project).createCachedValue({ provider() }, false) }
        return value.value
    }

    companion object {
        private val TOP_LEVEL_KEYS = Key.create<CachedValue<Map<String, String?>>>("ansibility.completion.keys.topLevelKeys")
        private const val MAIN = "main"

        /** Above the real repo's roles (354) and hosts keys; stale keys (after roots changed shape) are dropped wholesale past this size. */
        private const val MAX_KEYS = 4096

        fun getInstance(project: Project): KeyCatalogs = project.service()

        /** The YAML 1.1 type of [keyValue]'s value as ansible-core loads it; null for aliases and unloadable scalars. */
        fun typeName(keyValue: YAMLKeyValue): String? = when (keyValue.value) {
            null -> "null"
            is YAMLMapping -> "dict"
            is YAMLSequence -> "list"
            is YAMLAlias -> null
            is YAMLScalar -> when (val value = PsiYValueAdapter.valueOf(keyValue)) {
                is YVault -> "str"
                is YScalar -> when (value.resolved) {
                    Resolved.Null -> "null"
                    is Resolved.Bool -> "bool"
                    is Resolved.Int -> "int"
                    is Resolved.Float -> "float"
                    is Resolved.Str -> "str"
                    is Resolved.Timestamp -> "timestamp"
                    Resolved.Unloadable -> null
                }
                else -> null
            }
            else -> null
        }
    }
}

/**
 * Changes whenever one of [files] changes: combines the stamp of each file's loaded document (so unsaved edits count)
 * or, without one, of the file itself. Holds no PSI, so cached values keyed on it keep no syntax trees alive.
 */
internal class FilesTracker(files: Collection<VirtualFile>) : ModificationTracker {
    private val files: List<VirtualFile> = files.distinct()

    override fun getModificationCount(): Long {
        val documents = FileDocumentManager.getInstance()
        var hash = files.size.toLong()
        for (file in files) {
            val stamp = if (file.isValid) documents.getCachedDocument(file)?.modificationStamp ?: file.modificationStamp else INVALID
            hash = hash * 31 + stamp
        }
        return hash
    }

    private companion object {
        const val INVALID = -1L
    }
}
