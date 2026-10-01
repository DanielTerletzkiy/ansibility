package de.terletzkiy.ansibility.inspections.modules

import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode

/**
 * ANS-M001 (plan A.6, F5.6): an option the module's documentation does not know, which ansible-core rejects
 * ("Unsupported parameters", "Invalid options"). Exempt: free-form modules, modules that accept arbitrary keys,
 * templated arguments, action plugins that ignore or hand on unknown arguments, unknown modules. The fix
 * 🟣 X80 moves a task keyword written among the options (`vars:` inside `ansible.builtin.assert:`) to the task;
 * a misspelt option can be renamed to the nearest documented one.
 */
class AnsibleUnknownModuleOptionInspection : TaskProblemInspection() {
    override fun select(analysis: TaskFileChecks.Analysis, file: PsiFile): List<TaskProblem> =
        analysis.modules.filter { it.code == DiagnosticCode.M001_UNKNOWN_MODULE_OPTION }
}

/**
 * ANS-M002 (plan A.6, F5.6): a documented required option is missing (aliases, `args:` and `k=v` words count;
 * free-form text satisfies the free-form pseudo-option). Not reported when the arguments are templated, or while any
 * file of the project uses `module_defaults`, which may supply the option.
 */
class AnsibleMissingModuleOptionInspection : TaskProblemInspection() {
    override fun select(analysis: TaskFileChecks.Analysis, file: PsiFile): List<TaskProblem> {
        val missing = analysis.modules.filter { it.code == DiagnosticCode.M002_MISSING_MODULE_OPTION }
        if (missing.isEmpty() || TaskFileChecks.moduleDefaultsInUse(file.project)) return emptyList()
        return missing
    }
}

/**
 * Module option values ansible-core rejects (plan F3.3): ANS-T001 (the type check fails: `force: maybe` for a
 * `bool`), T002 (an unknown key inside an option with sub-options), T003 (a missing required sub-option), T004 (a
 * value outside `choices`, after coercion and the boolean rescue: `state: absentx`) and T005 (`null` for a required
 * or defaulted option where the type check rejects it). Always red under every preset.
 */
class AnsibleModuleOptionValueInspection : TaskProblemInspection() {
    override fun select(analysis: TaskFileChecks.Analysis, file: PsiFile): List<TaskProblem> =
        analysis.modules.filter { it.code in ModuleOptionChecks.VALUE_REJECTIONS }
}

/**
 * Module option values whose YAML type differs from the documented type although ansible-core accepts them by
 * coercion (T010, T011, T013, T014, T016) or skips them (T015): `owner: 1000` for a `str` option. Modules receive
 * the coerced value by contract, so these are silent unless the root enables "Module-option scalar coercions" or uses
 * the Strict preset (D5); `SeverityPolicy` decides.
 */
class AnsibleModuleOptionCoercionInspection : TaskProblemInspection() {
    override fun select(analysis: TaskFileChecks.Analysis, file: PsiFile): List<TaskProblem> =
        analysis.modules.filter { it.code in ModuleOptionChecks.VALUE_COERCIONS }
}
