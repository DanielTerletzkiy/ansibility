package de.terletzkiy.ansibility.inspections.keywords

import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.inspections.modules.TaskFileChecks
import de.terletzkiy.ansibility.inspections.modules.TaskProblem
import de.terletzkiy.ansibility.inspections.modules.TaskProblemInspection
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode

/**
 * ANS-K001 (plan A.6, F5.10): a play, block, role entry, task, handler, `import_playbook` or `loop_control` keyword
 * value ansible-core rejects when it loads or runs the object (`retries: abc`, `loop_control.pause: x`,
 * `serial: [a]`). Scalars for list keywords are wrapped by ansible-core, so `tags: haproxy`, `notify: x`, `when: x`
 * and `serial: 1` stay clean; templated values are not judged.
 */
class AnsibleKeywordValueInspection : TaskProblemInspection() {
    override fun select(analysis: TaskFileChecks.Analysis, file: PsiFile): List<TaskProblem> =
        analysis.keywords.filter { it.code == DiagnosticCode.K001_KEYWORD_VALUE_REJECTED }
}

/**
 * ANS-K002 (plan A.6, F5.10): a key ansible-core fails on: no keyword of the play, block, `import_playbook` entry or
 * `loop_control` it sits on, a second action key in a task (`become_usr: root` next to `ansible.builtin.file:`), or a
 * keyword a dynamic include does not accept. The fix renames a misspelt key to the nearest keyword.
 */
class AnsibleUnknownKeywordInspection : TaskProblemInspection() {
    override fun select(analysis: TaskFileChecks.Analysis, file: PsiFile): List<TaskProblem> =
        analysis.keywords.filter { it.code == DiagnosticCode.K002_UNKNOWN_KEYWORD }
}
