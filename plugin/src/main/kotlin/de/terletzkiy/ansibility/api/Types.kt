package de.terletzkiy.ansibility.api

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.semantics.yaml.YValue

/**
 * One documented-type finding on a variable value (plan A.6, F3.2, F4.4): a value assigned to a variable that a role's
 * `meta/argument_specs.yml` declares, checked with ansible-core's own rules for the root's target version.
 *
 * Severity is not part of the finding: consumers map [code] (and [reachable]) through `settings.SeverityPolicy`.
 */
data class TypeFinding(
    val code: DiagnosticCode,
    /** What is wrong and what the target ansible-core would do with the value (never shows a secret). */
    val message: String,
    /** The range to highlight, in the checked file. */
    val range: TextRange,
    /** The variable name, then mapping keys and list indices down to the offending value. */
    val path: List<String>,
    /** The spec options whose check produced the finding: one per role whose spec the value violates, in path order. */
    val bindings: List<SpecBinding>,
    /**
     * Machine-readable quick-fix hints of the semantics layer: `quote`, `unquote`, `replace=<literal>`,
     * `nearest-choice=<value>`, `remove-key`, `nearest-key=<key>`, `add-key=<key>`, `to-sequence`, `to-mapping`.
     */
    val fixHints: List<String>,
    /**
     * Whether a play of the root applies one of the declaring roles (plan D6): true or false for values set outside
     * the role (inventory, playbooks), null where reachability does not apply (the role's own defaults and vars,
     * molecule scenarios).
     */
    val reachable: Boolean?,
    /** The offending value, when the finding has one (a missing required key has the mapping that lacks it). */
    val value: YValue?,
    /**
     * 🟣 CLAUDE X79: for a mapping where the spec documents `elements: str`, the item attributes that the tasks looping
     * over the variable read (`name`, `secret_file_src`); empty otherwise.
     */
    val usageAttributes: List<String> = emptyList(),
)

/**
 * Checks role-variable values against their documented types (plan F3.1–F3.2, F4.4; codes ANS-T001–T005, T010,
 * T011, T013–T016), implemented in `types`.
 *
 * Checked: role `defaults/` and `vars/` (against the role's own spec), `group_vars`/`host_vars` files, inline
 * inventory vars, play/block/task `vars:`, `include_role` vars and role parameters, molecule inventories and
 * `molecule/vars` (against every same-root spec that declares the name). Templated values (ANS-T020's domain) and
 * vault values are skipped.
 */
interface TypeCheckService {
    /**
     * The findings of [file] in file order; empty outside Ansible roots, for file kinds that hold no variable values,
     * and in dumb mode. Cached until the file, any YAML file, the Ansible structure, the settings or a target version
     * changes. Call in a read action.
     */
    fun findings(file: PsiFile): List<TypeFinding>

    companion object {
        fun getInstance(project: Project): TypeCheckService = project.service()
    }
}
