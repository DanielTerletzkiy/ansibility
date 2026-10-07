package de.terletzkiy.ansibility.context.layout

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.InventoryDef
import de.terletzkiy.ansibility.api.InventorySource
import de.terletzkiy.ansibility.api.LayoutOrigin
import de.terletzkiy.ansibility.api.LayoutSource
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.api.RootLayout
import de.terletzkiy.ansibility.context.AnsibleCfg
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.semantics.coerce.PathEnvironment
import de.terletzkiy.ansibility.semantics.inventory.IniInventoryParser
import de.terletzkiy.ansibility.semantics.inventory.InventoryDirectoryWalk
import de.terletzkiy.ansibility.semantics.inventory.InventoryTree
import de.terletzkiy.ansibility.semantics.layout.CfgPathContext
import de.terletzkiy.ansibility.semantics.layout.CfgPathList
import de.terletzkiy.ansibility.semantics.layout.EntryHint
import de.terletzkiy.ansibility.semantics.layout.EnvironmentIds
import de.terletzkiy.ansibility.settings.layout.EffectiveLayoutSettings
import de.terletzkiy.ansibility.settings.layout.LayoutInventory

/**
 * Resolves the inventories of one root (plan amendment R10, F10.2). Custom inventories of the Layout settings
 * (F10.6) replace everything else; otherwise, in this order:
 * 1. the convention: `environments/<env>/hosts.y{a,}ml` exactly as before; only for an env dir with neither, its
 *    `hosts.ini` or INI `hosts`; plus `inventories/<env>/` as directory sources;
 * 2. `[defaults] inventory` of the cfg as one environment, which only marks a convention environment default when
 *    every entry lies inside it (D51);
 * 3. only when both give nothing, detection: the root's `hosts`/`inventory` files, root `*.ini` inventories next to
 *    a vars dir or a playbook, and the files of `inventory/` (one environment per file, D50).
 *
 * Reads the VFS and, for INI candidates, the files' text ([readText]); every file read is reported to [onRead] so the
 * caller can invalidate on its change.
 */
class LayoutResolver(
    private val readText: (VirtualFile) -> String?,
    private val isPlaybook: (VirtualFile) -> Boolean,
    private val onRead: (VirtualFile) -> Unit = {},
) {
    private class Found(val candidate: EnvironmentIds.Candidate, val sources: List<InventorySource>, val source: LayoutSource, val isConvention: Boolean)

    fun resolve(
        root: AnsibleRoot,
        cfg: AnsibleCfg?,
        cfgFile: VirtualFile?,
        settings: EffectiveLayoutSettings = EffectiveLayoutSettings.AUTO,
    ): RootLayout {
        if (root.kind == RootKind.ROLE_LIBRARY) return RootLayout.empty(root)
        val base = if (root.kind == RootKind.NESTED_PLAYBOOK) root.parentDir ?: root.dir else root.dir
        val found = ArrayList<Found>()
        val notFollowed = ArrayList<String>()
        val defaults = HashSet<Int>()
        val custom = settings.override.inventories
        if (custom != null) {
            custom(base, custom, found, defaults)
        } else {
            convention(base, root.environmentsDir, found)
            if (cfg != null && cfgFile != null) {
                cfgInventory(base, cfg, cfgFile, settings, found, defaults, notFollowed)
            }
            if (found.isEmpty() && root.environmentsDir == null) detect(base, found)
        }
        val ids = EnvironmentIds.assign(found.map { it.candidate })
        val inventories = found.mapIndexed { i, f ->
            InventoryDef(ids[i].id, ids[i].label, f.sources, f.source, isDefault = i in defaults, isConvention = f.isConvention)
        }
        return RootLayout(root, cfgFile, inventories, notFollowed)
    }

    private fun custom(base: VirtualFile, inventories: List<LayoutInventory>, found: MutableList<Found>, defaults: MutableSet<Int>) {
        val origin = LayoutSource(LayoutOrigin.PROJECT_SETTINGS)
        for (inventory in inventories) {
            val sources = inventory.sources.map { relative ->
                val path = resolvePath(base.path, relative)
                val file = base.fileSystem.findFileByPath(path)?.takeIf { it.isValid }
                val varsDir = if (file?.isDirectory == true) file else file?.parent ?: base.fileSystem.findFileByPath(path.substringBeforeLast('/', ""))
                InventorySource(path, file, file?.isDirectory == true, varsDir?.takeIf { it.isDirectory })
            }
            val idSources = sources.map { EnvironmentIds.Source(it.path, it.isDirectory) }
            if (inventory.isDefault) defaults += found.size
            found += Found(EnvironmentIds.Candidate.settings(inventory.name, idSources), sources, origin, false)
        }
    }

    private fun convention(base: VirtualFile, environmentsDir: VirtualFile?, found: MutableList<Found>) {
        val source = LayoutSource(LayoutOrigin.CONVENTION)
        val envs = environmentsDir?.takeIf { it.isValid && it.isDirectory }
        for (dir in envs?.children.orEmpty().filter(::isEnvDir).sortedBy { it.name }) {
            val yaml = HOSTS_YAML.firstNotNullOfOrNull { dir.findChild(it)?.takeIf { f -> !f.isDirectory } }
            val file = yaml ?: HOSTS_INI.firstNotNullOfOrNull { name -> dir.findChild(name)?.takeIf { !it.isDirectory && isIniInventory(it) } }
                ?: continue
            found += Found(
                EnvironmentIds.Candidate.convention(dir.path),
                listOf(InventorySource(file.path, file, false, dir)),
                source,
                isConvention = yaml != null,
            )
        }
        val inventories = base.findChild(INVENTORIES)?.takeIf { it.isDirectory && it != envs } ?: return
        for (dir in inventories.children.filter(::isEnvDir).sortedBy { it.name }) {
            if (entries(dir).none { !it.isDirectory }) continue
            found += Found(EnvironmentIds.Candidate.convention(dir.path), listOf(InventorySource(dir.path, dir, true, dir)), source, false)
        }
    }

    private fun cfgInventory(
        base: VirtualFile,
        cfg: AnsibleCfg,
        cfgFile: VirtualFile,
        settings: EffectiveLayoutSettings,
        found: MutableList<Found>,
        defaults: MutableSet<Int>,
        notFollowed: MutableList<String>,
    ) {
        val value = cfg.value("defaults", "inventory") ?: return
        val cfgDir = cfgFile.parent ?: return
        val follow = if (settings.follow) PathEnvironment(System.getenv()) else null
        val entries = CfgPathList.inventory(value, CfgPathContext(cfgDir.path, base.path, follow))
        val sources = ArrayList<InventorySource>()
        for (entry in entries) {
            val path = entry.path
            if (path == null || !entry.isFollowed || EntryHint.CFG_DIR in entry.hints) {
                notFollowed += entry.text
                continue
            }
            val file = cfgDir.fileSystem.findFileByPath(path)?.takeIf { it.isValid }
            val parentPath = path.substringBeforeLast('/', "")
            val varsDir = if (file?.isDirectory == true) file else file?.parent ?: cfgDir.fileSystem.findFileByPath(parentPath)
            if (file == null && entry.hints.contains(EntryHint.HOST_LIST)) {
                notFollowed += entry.text
                continue
            }
            sources += InventorySource(path, file, file?.isDirectory == true, varsDir?.takeIf { it.isDirectory })
        }
        if (sources.isEmpty()) return
        val convention = found.indices.filter { found[it].isConvention || found[it].source.origin == LayoutOrigin.CONVENTION }
        val inside = convention.firstOrNull { i ->
            val envDir = found[i].sources.first().varsDir ?: return@firstOrNull false
            sources.all { s -> s.file != null && VfsUtilCore.isAncestor(envDir, s.file, false) }
        }
        if (inside != null) {
            defaults += inside
            return
        }
        val idSources = sources.map { EnvironmentIds.Source(it.path, it.isDirectory, files = it.file?.let(::fileEntries).orEmpty()) }
        val origin = LayoutSource(LayoutOrigin.ANSIBLE_CFG, cfgFile, value)
        val candidates = EnvironmentIds.fromCfg(idSources, onePerFile = settings.override.onePerFile == true)
        if (candidates.size == 1) {
            defaults += found.size
            found += Found(candidates.single(), sources, origin, false)
            return
        }
        val dir = sources.single().file!!
        for (candidate in candidates) {
            val file = cfgDir.fileSystem.findFileByPath(candidate.sources.single().path) ?: continue
            found += Found(candidate, listOf(InventorySource(file.path, file, false, dir)), origin, false)
        }
    }

    private fun detect(base: VirtualFile, found: MutableList<Found>) {
        val source = LayoutSource(LayoutOrigin.DETECTED)
        val files = ArrayList<VirtualFile>()
        for (name in ROOT_NAMES) {
            val file = base.findChild(name)?.takeIf { !it.isDirectory } ?: continue
            if (AnsibleLayout.isYamlName(name) || isIniInventory(file)) files += file
        }
        val sideBySide = base.findChild(AnsibleLayout.GROUP_VARS) != null || base.findChild(AnsibleLayout.HOST_VARS) != null ||
            base.children.any { !it.isDirectory && AnsibleLayout.isYamlName(it.name) && isPlaybook(it) }
        if (sideBySide) {
            base.children
                .filter { !it.isDirectory && it.name.endsWith(".ini") && it.name !in ROOT_NAMES && it.name !in NOT_INVENTORIES }
                .sortedBy { it.name }
                .filterTo(files, ::isIniInventory)
        }
        for (file in files) {
            found += Found(EnvironmentIds.Candidate.detected(EnvironmentIds.Source(file.path)), listOf(InventorySource(file.path, file, false, base)), source, false)
        }
        val dir = base.findChild(INVENTORY)?.takeIf { it.isDirectory } ?: return
        for (file in fileEntries(dir).mapNotNull { dir.fileSystem.findFileByPath(it) }) {
            found += Found(EnvironmentIds.Candidate.detected(EnvironmentIds.Source(file.path)), listOf(InventorySource(file.path, file, false, dir)), source, false)
        }
    }

    /** [relative] (root-relative, `/`-separated, `..` allowed) as an absolute normalised path under [basePath]. */
    private fun resolvePath(basePath: String, relative: String): String {
        val trimmed = relative.trim().trimEnd('/')
        if (trimmed.isEmpty() || trimmed == ".") return basePath
        return if (trimmed.startsWith("/")) trimmed else FileUtil.toCanonicalPath("$basePath/$trimmed")
    }

    /** The top-level files a directory source reads, in read order (vars dirs and ignored names skipped). */
    private fun fileEntries(dir: VirtualFile): List<String> =
        if (!dir.isDirectory) emptyList() else entries(dir).filter { !it.isDirectory }.map { it.path }

    private fun entries(dir: VirtualFile): List<VirtualFile> =
        InventoryDirectoryWalk.entries(dir, VfsTree, InventoryDirectoryWalk.Options())
            .filter { it.name != AnsibleLayout.GROUP_VARS && it.name != AnsibleLayout.HOST_VARS }

    /** Whether [file] parses as an INI inventory with at least one host and no `=` in a host name. */
    private fun isIniInventory(file: VirtualFile): Boolean {
        onRead(file)
        val text = readText(file) ?: return false
        if (text.startsWith(VAULT_HEADER)) return true
        val graph = try {
            IniInventoryParser.parse(text)
        } catch (_: RuntimeException) {
            return false
        }
        return graph.hosts.isNotEmpty() && graph.hosts.keys.none { '=' in it }
    }

    private fun isEnvDir(dir: VirtualFile): Boolean = dir.isDirectory && !AnsibleLayout.isIgnoredVarsEntry(dir.name)

    object VfsTree : InventoryTree<VirtualFile> {
        override fun name(file: VirtualFile): String = file.name
        override fun isDirectory(file: VirtualFile): Boolean = file.isDirectory
        override fun children(dir: VirtualFile): List<VirtualFile> = dir.children.orEmpty().toList()
    }

    companion object {
        const val INVENTORY = "inventory"
        const val INVENTORIES = "inventories"
        private const val VAULT_HEADER = "\$ANSIBLE_VAULT"
        private val HOSTS_YAML = listOf("hosts.yml", "hosts.yaml")
        private val HOSTS_INI = listOf("hosts.ini", "hosts")

        /** Root files detection looks at, in this order. */
        val ROOT_NAMES = listOf("hosts", "hosts.ini", "hosts.yml", "hosts.yaml", "inventory", "inventory.ini", "inventory.yml", "inventory.yaml")

        /** Tool configs that are INI files but never inventories. */
        private val NOT_INVENTORIES = setOf("tox.ini", "pytest.ini", "mypy.ini", "setup.ini", "alembic.ini", "uwsgi.ini", "php.ini")
    }
}
