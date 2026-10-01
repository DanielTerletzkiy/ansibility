package de.terletzkiy.ansibility.api

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * One definition of a variable as the precedence engine saw it: where its key is written and which layer it
 * belongs to (plan A.5, F6.5).
 */
data class VarSourceRef(
    val file: VirtualFile,
    /** Offset of the key in [file] (for `hosts.yml` inline vars: the key inside the `vars:` or host mapping). */
    val offset: Int,
    val layer: VarsLayer,
    /** The owning group for group-level layers (`all` for `all.vars` and `group_vars/all`), else null. */
    val group: String?,
    /** The owning host for host-level layers, else null. */
    val host: String?,
    /** A short, single-line value preview; never present for vault files, `vault_*` keys or `!vault` values. */
    val preview: String?,
    /** True when the value is `!vault`-encrypted. */
    val isVault: Boolean,
    /**
     * For execution-layer sources (plan amendment R7/R8, A.14): the role whose `defaults/`, `vars/` or role params the
     * definition is (L2, L14, L18). Null for inventory layers and for play-level sources.
     */
    val role: String? = null,
    /**
     * For execution-layer sources: the play that loads the definition (its `vars:`, `vars_files`, or the play whose
     * roles bring role defaults, role vars and role params). Null for inventory layers, which no play owns.
     */
    val play: PlayRef? = null,
)

/** The effective definition of one variable and the definitions it beats, the runner-up first. */
data class EffectiveVarEntry(
    val name: String,
    val winner: VarSourceRef,
    val shadowed: List<VarSourceRef>,
    /**
     * Under `hash_behaviour = merge`: the [shadowed] definitions whose dictionaries still contribute keys to the
     * effective value (highest first). Always empty under the default `replace`.
     */
    val mergedFrom: List<VarSourceRef> = emptyList(),
)

/**
 * The inventory-level variables of one host in one environment (precedence levels 3–10), comparable with
 * `ansible-inventory -i <env>/hosts.yml --playbook-dir <dir> --list`. [vars] are sorted by name.
 */
data class EffectiveVars(
    val host: String,
    val environment: String,
    val vars: List<EffectiveVarEntry>,
) {
    /** The entry of [name], or null when no inventory source defines it for this host. */
    operator fun get(name: String): EffectiveVarEntry? = vars.firstOrNull { it.name == name }
}

/** Project service (implemented in `model.effective`). All lookups are scoped to one root. */
interface EffectiveVarsService {
    /**
     * The inventory view of [host] in [environment] of [root], or null when the root has no such environment or
     * host. Playbook-level var files come from `<playbookDir>/group_vars` and `<playbookDir>/host_vars`; with
     * [playbookDir] null only the inventory's own sources count (like `ansible-inventory` without `--playbook-dir`).
     */
    fun inventoryView(root: AnsibleRoot, environment: String, host: String, playbookDir: VirtualFile?): EffectiveVars?

    /** Host names of [environment] of [root], in inventory order (empty when the environment is unknown). */
    fun hostsOf(root: AnsibleRoot, environment: String): List<String>

    companion object {
        fun getInstance(project: Project): EffectiveVarsService = project.service()
    }
}
