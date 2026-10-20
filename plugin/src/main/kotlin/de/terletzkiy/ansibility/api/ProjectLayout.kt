package de.terletzkiy.ansibility.api

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/** Where a layout fact comes from, strongest first (plan amendment R10, D51). */
enum class LayoutOrigin { PROJECT_SETTINGS, ANSIBLE_CFG, CONVENTION, DETECTED }

/** One layout fact's provenance: its [origin], and for `ansible.cfg` facts the [file] and the value as written. */
data class LayoutSource(val origin: LayoutOrigin, val file: VirtualFile? = null, val text: String? = null)

/**
 * One inventory source of an environment: a file or directory at [path]. [file] is null when the path does not
 * exist; its [varsDir] (the directory whose `group_vars`/`host_vars` are inventory-level) still loads when present.
 */
data class InventorySource(
    val path: String,
    val file: VirtualFile?,
    val isDirectory: Boolean,
    val varsDir: VirtualFile?,
)

/**
 * Which playbook-level vars (`group_vars`/`host_vars` next to the playbooks) an environment loads (plan amendment R26).
 * [entries] are paths relative to the playbook directory, `/`-separated: a directory (`group_vars/alias`) takes every
 * file below it, a file takes itself, with or without its extension (`group_vars/all` is `group_vars/all.yml` too).
 * No entries: the environment loads no playbook-level vars. An environment without a scope loads all of them (Ansible).
 */
data class PlaybookVarsScope(val entries: List<String>) {
    private val normalized = entries.map { it.trim().trim('/') }.filter(String::isNotEmpty)

    /** Whether the playbook-level vars file at [relativePath] (relative to the playbook directory) is in scope. */
    fun includes(relativePath: String): Boolean {
        val path = relativePath.trim('/')
        val bare = path.substringBeforeLast('.').takeIf { path.substringAfterLast('/').contains('.') }
        return normalized.any { e -> path == e || path.startsWith("$e/") || bare == e || (bare != null && bare.startsWith("$e/")) }
    }

    override fun toString(): String = normalized.joinToString(", ")
}

/**
 * One environment: the inventory sources a run without `-i` (or with `-i <file>`) loads, in order. [id] is unique
 * within its root and is what [FileContext.environment], the context switcher and the views use. [isConvention]
 * marks `environments/<env>/hosts.y{a,}ml`, whose model is built exactly as before R10.
 */
data class InventoryDef(
    val id: String,
    val label: String,
    val sources: List<InventorySource>,
    val source: LayoutSource,
    val isDefault: Boolean = false,
    val isConvention: Boolean = false,
    /** The playbook-level vars this environment loads; null loads all of them (plan amendment R26). */
    val playbookVars: PlaybookVarsScope? = null,
) {
    /** Whether this environment loads the playbook-level vars file at [relativePath] below its playbook directory. */
    fun loadsPlaybookVars(relativePath: String): Boolean = playbookVars?.includes(relativePath) ?: true

    /** The directories whose `group_vars`/`host_vars` are inventory-level for this environment. */
    val varsDirs: List<VirtualFile> get() = sources.mapNotNull { it.varsDir }.distinct()
}

/**
 * The resolved layout of one root: its inventories and where they came from. Immutable; [fingerprint] changes
 * whenever anything a consumer would see changes.
 */
class RootLayout(
    val root: AnsibleRoot,
    val cfg: VirtualFile?,
    val inventories: List<InventoryDef>,
    /** `[defaults] inventory` entries that are not followed (host lists, machine-dependent paths), as written. */
    val notFollowed: List<String> = emptyList(),
) {
    val hasInventory: Boolean get() = inventories.isNotEmpty()

    /** True when the root has exactly one inventory and it is not an `environments/` one (D58: no env dimension). */
    val isSingleInventory: Boolean get() = inventories.size == 1 && !inventories[0].isConvention

    val fingerprint: String by lazy {
        inventories.joinToString("|") { def ->
            def.id + "=" + def.sources.joinToString(",") { "${it.path}:${it.file != null}:${it.varsDir?.path}" } +
                (if (def.isDefault) "*" else "") + (def.playbookVars?.let { "[$it]" } ?: "")
        } + "#" + notFollowed.joinToString(",")
    }

    fun inventory(id: String): InventoryDef? = inventories.firstOrNull { it.id == id }

    /** The environments [file] is an inventory source of (the file itself, or a file inside a directory source). */
    fun inventoriesOf(file: VirtualFile): List<InventoryDef> = inventories.filter { def ->
        def.sources.any { s -> val f = s.file; f != null && (f == file || (s.isDirectory && VfsUtilCore.isAncestor(f, file, true))) }
    }

    /**
     * Whether environment [id] loads the playbook-level vars [file] of [playbookDir] (R26); true for files outside
     * [playbookDir] and for unknown environments.
     */
    fun loadsPlaybookVars(id: String, playbookDir: VirtualFile, file: VirtualFile): Boolean {
        val def = inventory(id) ?: return true
        val relative = VfsUtilCore.getRelativePath(file, playbookDir, '/') ?: return true
        return def.loadsPlaybookVars(relative)
    }

    /**
     * Whether environment [id] loads the playbook-level vars [file], whose playbook directory is the parent of its
     * `group_vars`/`host_vars` directory (R26); true for a file outside such a directory.
     */
    fun loadsPlaybookVars(id: String, file: VirtualFile): Boolean {
        val playbookDir = generateSequence(file.parent) { it.parent }
            .firstOrNull { it.name == "group_vars" || it.name == "host_vars" }?.parent ?: return true
        return loadsPlaybookVars(id, playbookDir, file)
    }

    /** The environments whose inventory-level vars directory is [dir]. */
    fun environmentsWithVarsDir(dir: VirtualFile): List<InventoryDef> = inventories.filter { dir in it.varsDirs }

    companion object {
        fun empty(root: AnsibleRoot): RootLayout = RootLayout(root, null, emptyList())
    }
}

/** Resolves the [RootLayout] of each root (implemented in `context.layout`). */
interface ProjectLayoutService {
    /** The layout of [root]; cached. */
    fun layout(root: AnsibleRoot): RootLayout

    /** Bumped whenever some root's layout fingerprint changes. */
    val modificationTracker: ModificationTracker

    companion object {
        fun getInstance(project: Project): ProjectLayoutService = project.service()
    }
}
