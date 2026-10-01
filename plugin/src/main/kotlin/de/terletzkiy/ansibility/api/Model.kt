package de.terletzkiy.ansibility.api

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.semantics.schema.ArgumentSpec
import de.terletzkiy.ansibility.semantics.schema.OptionSpec

/** A position in a file. Offsets are resolved to PSI lazily by consumers. */
data class SourceLocation(val file: VirtualFile, val offset: Int)

// ---------------------------------------------------------------- roles

data class RoleRef(val rootDir: VirtualFile, val name: String, val dir: VirtualFile)

/** The full context of one role (plan F1.1). */
data class RoleInfo(
    val ref: RoleRef,
    /** Entry points of `meta/argument_specs.yml`, keyed by name (only `main` exists in the target repo). */
    val argumentSpecs: Map<String, ArgumentSpec>,
    val specFile: VirtualFile?,
    val defaultsFiles: List<VirtualFile>,
    val varsFiles: List<VirtualFile>,
    val taskFiles: List<VirtualFile>,
    val handlerFiles: List<VirtualFile>,
    val templatesDir: VirtualFile?,
    val filesDir: VirtualFile?,
    val metaDependencies: List<String>,
)

/** Project service (implemented in `model`). All lookups are scoped to one root. */
interface RoleRegistry {
    fun roles(root: AnsibleRoot): List<RoleRef>
    fun role(root: AnsibleRoot, name: String): RoleInfo?
    fun roleOf(file: VirtualFile): RoleInfo?

    companion object {
        fun getInstance(project: Project): RoleRegistry = project.service()
    }
}

// ---------------------------------------------------------------- variables

enum class VarDefKind {
    SPEC_OPTION, ROLE_DEFAULT, ROLE_VAR,
    INVENTORY_INLINE, GROUP_VARS, HOST_VARS, MOLECULE_INVENTORY,
    PLAY_VARS, VARS_FILES, BLOCK_VARS, TASK_VARS, INCLUDE_PARAMS,
    SET_FACT, REGISTER, LOOP_VAR, INDEX_VAR,
    TEMPLATE_VARS, JINJA_LOCAL,
    ROLE_PARAMS, VARS_PROMPT,
}

enum class ValueShape { LITERAL, JINJA, VAULT, NULL, CONTAINER }

/** One place where a variable gets a value (or, for SPEC_OPTION, is declared). */
data class VarDefinition(
    val name: String,
    val kind: VarDefKind,
    val location: SourceLocation,
    val layer: VarsLayer? = null,
    val roleName: String? = null,
    val environment: String? = null,
    val group: String? = null,
    val host: String? = null,
    val valueShape: ValueShape = ValueShape.LITERAL,
    /** A short value preview, never present for vault files, `vault_*` keys or `!vault` values. */
    val preview: String? = null,
    /** The comment block directly above the key (fallback docs, plan X07). */
    val docComment: String? = null,
    /** For LITERAL values: the YAML 1.1 type name of the literal (`str`, `int`, `float`, `bool`, `null`, `timestamp`). */
    val literalType: String? = null,
)

/** A role spec that declares a variable. */
data class SpecBinding(
    val role: RoleRef,
    val entryPoint: String,
    val option: OptionSpec,
    val location: SourceLocation,
)

/** Everything known about one variable name inside one root. */
data class VarSymbol(
    val rootDir: VirtualFile,
    val name: String,
    val definitions: List<VarDefinition>,
    val specBindings: List<SpecBinding>,
)

/** Project service (implemented in `index`/`resolve`). */
interface VarService {
    fun symbol(root: AnsibleRoot, name: String): VarSymbol

    /** All variable names defined or declared anywhere in [root] (for completion and unused checks). */
    fun allNames(root: AnsibleRoot): Collection<String>

    companion object {
        fun getInstance(project: Project): VarService = project.service()
    }
}

// ---------------------------------------------------------------- inventory

data class InventoryHost(
    val name: String,
    /** `ansible_host` as written (may be a Jinja template). */
    val ansibleHost: String?,
    val groups: List<String>,
    val location: SourceLocation?,
    /**
     * The keys of the host's inline variables in the inventory file (`hosts:` entries in `hosts.yml`, level 8),
     * merged over every entry of the host in file order; values are never part of the DTO.
     */
    val inlineVarKeys: List<String> = emptyList(),
)

data class InventoryGroup(
    val name: String,
    val parents: List<String>,
    val children: List<String>,
    /** Hosts listed directly under this group. */
    val hosts: List<String>,
    val inlineVarKeys: List<String>,
    val location: SourceLocation?,
    /** Depth in the group tree (`all` is 0), as used by ansible-core's sort_groups. */
    val depth: Int = 0,
    /** `ansible_group_priority` (default 1). */
    val priority: Int = 1,
)

data class VarFile(
    val file: VirtualFile,
    val layer: VarsLayer,
    val environment: String?,
    val group: String?,
    val host: String?,
)

data class Inventory(
    val rootDir: VirtualFile,
    val environment: String,
    val hostsFile: VirtualFile,
    val groups: Map<String, InventoryGroup>,
    val hosts: Map<String, InventoryHost>,
    /** Inventory-level group_vars/host_vars files (layers 4, 6, 9), in load order. */
    val varFiles: List<VarFile>,
)

/**
 * The pseudo-inventory of one molecule scenario (plan A.5 `MOLECULE_SCENARIO`): the inventory molecule generates
 * from `platforms` (each platform is a host in its `groups`, or in `ungrouped`; `children` nest below those
 * groups), merged with `provisioner.inventory.hosts` and a linked hosts file, and the variables molecule writes
 * next to it (layer [VarsLayer.MOLECULE_INVENTORY]).
 *
 * Groups defined here count as defined, labelled "molecule-only" by consumers (`keepalived_master`).
 */
data class MoleculeInventory(
    /** The role the scenario belongs to, or null for a root-level `molecule/` directory. */
    val roleName: String?,
    val scenarioDir: VirtualFile,
    /** `molecule.yml`, which holds the platforms and the inline inventory. */
    val configFile: VirtualFile,
    /**
     * Groups and hosts of the generated inventory ([Inventory.environment] is the scenario name and
     * [Inventory.hostsFile] is [configFile]); [Inventory.varFiles] are the files of
     * `provisioner.inventory.links.group_vars`/`host_vars`.
     */
    val inventory: Inventory,
    /** The inline `provisioner.inventory.group_vars`/`host_vars` keys, each with its location in [configFile]. */
    val inlineVars: List<VarDefinition>,
)

/** Project service (implemented in `model`). */
interface InventoryService {
    fun inventories(root: AnsibleRoot): List<Inventory>

    /** Playbook-level `<root>/group_vars` and `<root>/host_vars` files (layers 5, 7, 10), in load order. */
    fun playbookVarFiles(root: AnsibleRoot): List<VarFile>

    /**
     * The molecule pseudo-inventories of [root]'s roles and of `<root>/molecule`, by role and scenario name. Empty when
     * molecule support is switched off in the project settings; scenarios in ignored paths or in ghost role
     * directories are left out.
     */
    fun moleculeInventories(root: AnsibleRoot): List<MoleculeInventory>

    companion object {
        fun getInstance(project: Project): InventoryService = project.service()
    }
}
