package de.terletzkiy.ansibility.api

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.VirtualFile

/** How a directory takes part in Ansible resolution (plan A.5). */
enum class RootKind {
    /** A directory containing `ansible.cfg` (e.g. `repos/falcon/ansible`). */
    PROJECT,

    /** Roles and playbooks without `ansible.cfg` or inventory (e.g. `golden/`). */
    ROLE_LIBRARY,

    /** A playbook dir with its own `roles/` inside a PROJECT root (e.g. `repos/pelican/ansible/danger_zone/database`). */
    NESTED_PLAYBOOK,
}

/**
 * An Ansible root. Every lookup resolves inside one root; the same role name in another root is never a target.
 *
 * [detached] roots (a git worktree whose `.git` file points into `…/.git/worktrees/…`, or anything under
 * `.claude/worktrees/`) work on their own but never appear in other roots' results.
 */
data class AnsibleRoot(
    val dir: VirtualFile,
    val kind: RootKind,
    val detached: Boolean,
    /** For NESTED_PLAYBOOK: the enclosing PROJECT root dir, whose inventories it shares. */
    val parentDir: VirtualFile?,
    /** Where roles resolve from, nearest first (`<root>/roles`, or `golden/roles` for `golden/playbooks`). */
    val rolesDirs: List<VirtualFile>,
    /** `<root>/environments`, when present; for NESTED_PLAYBOOK roots without their own, the parent root's (they share its inventories). */
    val environmentsDir: VirtualFile?,
    /** Short label for UI, e.g. "falcon", "golden", "pelican › danger_zone/database". */
    val displayName: String,
)

/** The kind of an Ansible file, computed at query time from its path and the root structure (never in indexers). */
enum class FileKind {
    ROLE_TASKS, ROLE_HANDLERS, ROLE_DEFAULTS, ROLE_VARS, ROLE_ARGSPEC, ROLE_META, ROLE_TEMPLATE, ROLE_FILE,
    PLAYBOOK, INVENTORY, GROUP_VARS, HOST_VARS,
    MOLECULE_CONFIG, MOLECULE_PLAYBOOK, MOLECULE_TASKS, MOLECULE_VARS,
    ANSIBLE_CFG, LINT_CONFIG, REQUIREMENTS,
    OTHER,
}

/**
 * Variable precedence layers, lowest to highest (plan A.5, numbering from the research).
 * Levels 3–7 follow ansible-core's default VARIABLE_PRECEDENCE.
 */
enum class VarsLayer(val level: Int, val label: String) {
    ROLE_DEFAULTS(2, "role defaults"),
    INVENTORY_FILE_GROUP(3, "inventory file group vars"),
    INVENTORY_GROUP_VARS_ALL(4, "inventory group_vars/all"),
    PLAYBOOK_GROUP_VARS_ALL(5, "playbook group_vars/all"),
    INVENTORY_GROUP_VARS(6, "inventory group_vars/<group>"),
    PLAYBOOK_GROUP_VARS(7, "playbook group_vars/<group>"),
    INVENTORY_FILE_HOST(8, "inventory file host vars"),
    INVENTORY_HOST_VARS(9, "inventory host_vars"),
    PLAYBOOK_HOST_VARS(10, "playbook host_vars"),
    PLAY_VARS(12, "play vars"),
    VARS_FILES(13, "play vars_files"),
    ROLE_VARS(14, "role vars"),
    BLOCK_TASK_VARS(15, "block/task vars"),
    INCLUDE_VARS(16, "include_vars"),
    SET_FACT_REGISTER(17, "set_fact/register"),
    ROLE_PARAMS(18, "role params"),
    EXTRA_VARS(19, "extra vars"),

    /** Molecule `provisioner.inventory` group_vars/host_vars and `molecule/vars` files (a pseudo-inventory). */
    MOLECULE_INVENTORY(6, "molecule inventory"),
}

/** Everything the plugin knows about one file's place in Ansible. */
data class FileContext(
    val root: AnsibleRoot,
    val kind: FileKind,
    val roleName: String? = null,
    val roleDir: VirtualFile? = null,
    /** For inventory-layer files: the environment name (`environments/<env>`), null for playbook-level vars. */
    val environment: String? = null,
    /** For group_vars files: the group; for host_vars: null. */
    val group: String? = null,
    /** For host_vars files: the host. */
    val host: String? = null,
    val layer: VarsLayer? = null,
    /** For molecule files: the scenario directory. */
    val moleculeScenarioDir: VirtualFile? = null,
)

/** Project service owning root detection and file classification (implemented in `context`). */
interface AnsibleWorkspace {
    /** All roots, detached ones included. */
    fun roots(): List<AnsibleRoot>

    /** The innermost root containing [file], or null when the file is outside any Ansible root. */
    fun rootFor(file: VirtualFile): AnsibleRoot?

    /** Query-time classification. Cached; invalidated by [structureTracker]. */
    fun contextOf(file: VirtualFile): FileContext?

    /** Bumped whenever roots or their structure change (ansible.cfg, roles/, environments/, group_vars …). */
    val structureTracker: ModificationTracker

    /** Forces a rescan (e.g. after settings that affect detection changed); bumps [structureTracker]. */
    fun refreshStructure()

    companion object {
        fun getInstance(project: Project): AnsibleWorkspace = project.service()
    }
}
