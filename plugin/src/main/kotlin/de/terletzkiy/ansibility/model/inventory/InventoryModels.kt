package de.terletzkiy.ansibility.model.inventory

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.InventoryDef
import de.terletzkiy.ansibility.api.MoleculeInventory
import de.terletzkiy.ansibility.api.ProjectLayoutService
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.api.RootLayout
import de.terletzkiy.ansibility.api.VarFile
import de.terletzkiy.ansibility.context.AnsibleCfg
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.inventory.FileStatus
import de.terletzkiy.ansibility.semantics.inventory.InventoryDirectoryWalk
import de.terletzkiy.ansibility.semantics.inventory.InventoryFileFormat
import de.terletzkiy.ansibility.semantics.inventory.InventoryParse
import de.terletzkiy.ansibility.semantics.inventory.InventoryParseOptions
import de.terletzkiy.ansibility.semantics.inventory.InventorySources
import de.terletzkiy.ansibility.semantics.inventory.YamlInventoryParser

/**
 * The cached inventory model of every root (plan A.5; plan amendment R7/R8, A.9), shared by [InventoryServiceImpl], the
 * effective-vars views and the host-context service:
 * - the environments `environments/<env>/hosts.y{a,}ml` of a PROJECT root (a NESTED_PLAYBOOK root shares its
 *   parent's; a ROLE_LIBRARY root has none), each parsed with [YamlInventoryParser] and listed with its
 *   inventory-level var files;
 * - the playbook-level var files of a directory (`<dir>/group_vars`, `<dir>/host_vars`);
 * - the molecule pseudo-inventories of a root's roles, with their parsed graphs ([MoleculeModel]).
 *
 * Every value is a [ModelCache] entry that depends only on what it read: one environment on its `hosts.yml` and the
 * root's `ansible.cfg`, one scenario on its `molecule.yml` and linked hosts file, plus the layout stamp (files created,
 * deleted or renamed, structural files saved). Typing in a vars file or a task file invalidates none of them; typing in
 * `environments/prod/hosts.yml` invalidates the prod model and the lists that contain it.
 *
 * Methods take a read lock themselves when the caller holds none; loops check for cancellation.
 */
@Service(Service.Level.PROJECT)
class InventoryModels(private val project: Project) {
    private data class EnvironmentsKey(val environmentsDir: VirtualFile, val cfgFile: VirtualFile?)

    private data class LayoutKey(val rootDir: VirtualFile, val fingerprint: String, val cfgFile: VirtualFile?)

    private data class LayoutEnvironmentKey(val rootDir: VirtualFile, val def: InventoryDef, val cfgFile: VirtualFile?, val core: CoreVersion?)

    private data class EnvironmentKey(val dir: VirtualFile, val cfgFile: VirtualFile?)

    private data class PlaybookKey(val dir: VirtualFile, val cfgFile: VirtualFile?)

    private data class MoleculesKey(val rootDir: VirtualFile, val rolesDirs: List<VirtualFile>, val cfgFile: VirtualFile?)

    private data class ScenarioKey(val rootDir: VirtualFile, val roleName: String?, val configFile: VirtualFile, val cfgFile: VirtualFile?)

    private val environmentLists = ModelCache<EnvironmentsKey, List<EnvironmentModel>>(project, "inventory.environments")
    private val environmentModels = ModelCache<EnvironmentKey, EnvironmentModel?>(project, "inventory.environment")
    private val layoutLists = ModelCache<LayoutKey, List<EnvironmentModel>>(project, "inventory.layoutEnvironments")
    private val layoutModels = ModelCache<LayoutEnvironmentKey, EnvironmentModel?>(project, "inventory.layoutEnvironment")
    private val playbookVarFiles = ModelCache<PlaybookKey, List<VarFile>>(project, "inventory.playbookVarFiles")
    private val moleculeLists = ModelCache<MoleculesKey, List<MoleculeModel>>(project, "inventory.molecules")
    private val moleculeModels = ModelCache<ScenarioKey, MoleculeModel>(project, "inventory.molecule")

    /**
     * The environments of [root]: those of `environments/` sorted by name, as before, then the other inventories of
     * its layout in layout order; empty for ROLE_LIBRARY roots.
     */
    fun environments(root: AnsibleRoot): List<EnvironmentModel> = readLocked {
        val layout = layout(root)
        val environmentsDir = environmentsDir(root)
        if (layout != null && layout.inventories.any { !it.isConvention }) {
            val cfgFile = VarsConfig.cfgFile(root)
            layoutLists.get(LayoutKey(root.dir, layout.fingerprint, cfgFile)) {
                layout.inventories.mapNotNull { def ->
                    ProgressManager.checkCanceled()
                    environmentOf(root, def, cfgFile)
                }
            }
        } else if (environmentsDir == null) {
            emptyList()
        } else {
            val cfgFile = VarsConfig.cfgFile(root)
            environmentLists.get(EnvironmentsKey(environmentsDir, cfgFile)) {
                environmentsDir.children.orEmpty()
                    .filter { it.isDirectory && !AnsibleLayout.isIgnoredVarsEntry(it.name) }
                    .sortedBy { it.name }
                    .mapNotNull { dir ->
                        ProgressManager.checkCanceled()
                        environmentAt(dir, cfgFile)
                    }
            }
        }
    }

    /**
     * The environment [name] of [root], or null. Reads only that environment's model, so a cache built from it does not
     * depend on the other environments.
     */
    fun environment(root: AnsibleRoot, name: String): EnvironmentModel? = readLocked {
        val def = layout(root)?.inventory(name)
        if (def != null) {
            environmentOf(root, def, VarsConfig.cfgFile(root))
        } else {
            val dir = environmentsDir(root)?.findChild(name)?.takeIf { it.isDirectory && !AnsibleLayout.isIgnoredVarsEntry(it.name) }
            if (dir == null) null else environmentAt(dir, VarsConfig.cfgFile(root))
        }
    }

    private fun layout(root: AnsibleRoot): RootLayout? {
        if (root.kind == RootKind.ROLE_LIBRARY) return null
        val service = ProjectLayoutService.getInstance(project)
        val layout = service.layout(root)
        ModelInputs.external(layout.fingerprint) { service.layout(root).fingerprint }
        return layout
    }

    /** A convention environment exactly as before R10; any other through the inventory sources. */
    private fun environmentOf(root: AnsibleRoot, def: InventoryDef, cfgFile: VirtualFile?): EnvironmentModel? {
        if (def.isConvention) {
            val dir = def.sources.single().varsDir ?: return null
            return environmentAt(dir, cfgFile)
        }
        val rootDir = root.parentDir.takeIf { root.kind == RootKind.NESTED_PLAYBOOK } ?: root.dir
        val core = TargetVersionDetector.getInstance(project).targetVersion(root).version
        return layoutModels.get(LayoutEnvironmentKey(rootDir, def, cfgFile, core)) { computeLayoutEnvironment(rootDir, def, cfgFile, core) }
    }

    private fun computeLayoutEnvironment(rootDir: VirtualFile, def: InventoryDef, cfgFile: VirtualFile?, core: CoreVersion?): EnvironmentModel? {
        val existing = def.sources.mapNotNull { it.file?.takeIf { f -> f.isValid } }
        val varsDirs = def.varsDirs.filter { it.isValid }
        val firstDir = varsDirs.firstOrNull() ?: return null
        val config = VarsConfig.load(cfgFile)
        val fs = VfsInventoryFiles(project)
        val parse = InventorySources.parseFiles(existing, fs, parseOptions(cfgFile, core))
        val files = parse.files.map { it.file }
        val hostsFile = files.firstOrNull { it != null } ?: existing.firstOrNull() ?: return null
        val documents = parse.files.map { f ->
            val file = f.file
            if (file != null && f.format == InventoryFileFormat.YAML) VarsDocuments.load(project, file) else null
        }
        val varFiles = varsDirs.flatMap { VarsDirectories.inventoryVarFiles(it, def.id, parse.graph, config.extensions) }
        val inventory = InventoryDtos.inventory(
            rootDir, def.id, hostsFile, parse.graph, files.map { it ?: hostsFile }, documents, varFiles,
        )
        return EnvironmentModel(def.id, firstDir, hostsFile, parse.graph, inventory, varsDirs, files, problems(parse))
    }

    /** The `[inventory]` settings of the cfg that steer the parse: `enable_plugins`, `ignore_extensions`, `ignore_patterns`. */
    private fun parseOptions(cfgFile: VirtualFile?, core: CoreVersion?): InventoryParseOptions {
        val cfg = cfgFile?.let { ModelInputs.savedFile(it); runCatching { AnsibleCfg.parse(VfsUtilCore.loadText(it)) }.getOrNull() }
        fun list(key: String) = cfg?.value("inventory", key)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        return InventoryParseOptions(
            core = core,
            enabledPlugins = list("enable_plugins") ?: InventoryParseOptions.DEFAULT_ENABLED_PLUGINS,
            walk = InventoryDirectoryWalk.Options(core, list("ignore_extensions"), list("ignore_patterns").orEmpty()),
        )
    }

    private fun problems(parse: InventoryParse<VirtualFile>): List<SourceProblem> {
        val problems = ArrayList<SourceProblem>()
        for (parsed in parse.files) {
            val file = parsed.file ?: continue
            when (parsed.status) {
                FileStatus.FAILED -> {
                    val failure = parsed.failures.lastOrNull()
                    problems += SourceProblem(file, SourceProblem.Kind.FAILED, failure?.message, failure?.range?.start)
                }
                FileStatus.DYNAMIC -> problems += SourceProblem(file, SourceProblem.Kind.DYNAMIC, parsed.format?.name)
                else -> Unit
            }
        }
        for (source in parse.sources) {
            for (skipped in source.skipped) {
                if (skipped.reason == InventoryDirectoryWalk.SkipReason.EXTENSION && skipped.file.name.endsWith(".ini")) {
                    problems += SourceProblem(skipped.file, SourceProblem.Kind.SKIPPED_BY_EXTENSION, skipped.rule)
                }
            }
        }
        return problems
    }

    /** The playbook-level var files of [dir] (`<dir>/group_vars`, `<dir>/host_vars`) under the settings of [root]. */
    fun playbookVarFiles(root: AnsibleRoot, dir: VirtualFile): List<VarFile> = readLocked {
        if (!dir.isValid || !dir.isDirectory) {
            emptyList()
        } else {
            val key = PlaybookKey(dir, VarsConfig.cfgFile(root))
            playbookVarFiles.get(key) { VarsDirectories.playbookVarFiles(key.dir, VarsConfig.load(key.cfgFile).extensions) }
        }
    }

    /** The molecule scenarios of [root]'s own roles (and of `<root>/molecule`), by role and scenario name. */
    fun moleculeInventories(root: AnsibleRoot): List<MoleculeInventory> = moleculeModels(root).map { it.inventory }

    /** The parsed molecule scenarios of [root] ([moleculeInventories] with their graphs and inline variable sections). */
    fun moleculeModels(root: AnsibleRoot): List<MoleculeModel> = readLocked {
        val key = MoleculesKey(root.dir, root.rolesDirs, VarsConfig.cfgFile(root))
        moleculeLists.get(key) {
            MoleculeInventories.scenarioConfigs(key.rootDir, key.rolesDirs).map { (role, config) ->
                ProgressManager.checkCanceled()
                scenario(ScenarioKey(key.rootDir, role, config, key.cfgFile))
            }
        }
    }

    /**
     * The parsed scenario of [molecule] (one of [root]'s [moleculeInventories]). Reads only that scenario's model, so a
     * cache built from it does not depend on the root's other scenarios.
     */
    fun moleculeModel(root: AnsibleRoot, molecule: MoleculeInventory): MoleculeModel = readLocked {
        scenario(ScenarioKey(root.dir, molecule.roleName, molecule.configFile, VarsConfig.cfgFile(root)))
    }

    private fun scenario(key: ScenarioKey): MoleculeModel = moleculeModels.get(key) {
        MoleculeInventories.parse(project, key.rootDir, key.roleName, key.configFile, VarsConfig.load(key.cfgFile).extensions)
    }

    private fun environmentsDir(root: AnsibleRoot): VirtualFile? =
        root.environmentsDir?.takeIf { root.kind != RootKind.ROLE_LIBRARY && it.isValid && it.isDirectory }

    /** The model of the environment directory [dir], or null when it has no `hosts.y{a,}ml`. */
    private fun environmentAt(dir: VirtualFile, cfgFile: VirtualFile?): EnvironmentModel? =
        environmentModels.get(EnvironmentKey(dir, cfgFile)) { computeEnvironment(dir, cfgFile) }

    private fun computeEnvironment(dir: VirtualFile, cfgFile: VirtualFile?): EnvironmentModel? {
        val hostsFile = HOSTS_FILE_NAMES.firstNotNullOfOrNull { name -> dir.findChild(name)?.takeIf { !it.isDirectory } } ?: return null
        val extensions = VarsConfig.load(cfgFile).extensions
        val rootDir = dir.parent?.parent ?: return null
        val document = VarsDocuments.load(project, hostsFile)
        val graph = YamlInventoryParser.parse(document)
        val varFiles = VarsDirectories.inventoryVarFiles(dir, dir.name, graph, extensions)
        val inventory = InventoryDtos.inventory(rootDir, dir.name, hostsFile, graph, listOf(hostsFile), listOf(document), varFiles)
        return EnvironmentModel(dir.name, dir, hostsFile, graph, inventory)
    }

    companion object {
        /** `hosts.yml` wins over `hosts.yaml` when both exist (the run scripts pass `hosts.yml`). */
        private val HOSTS_FILE_NAMES = listOf("hosts.yml", "hosts.yaml")

        fun getInstance(project: Project): InventoryModels = project.service()
    }
}

/** Runs [action] under a read lock, taking one only when the caller holds none. */
internal fun <T> readLocked(action: () -> T): T =
    if (ApplicationManager.getApplication().isReadAccessAllowed) action() else runReadActionBlocking(action)
