package de.terletzkiy.ansibility.inspections.undefined

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.semantics.diagnostics.Level
import de.terletzkiy.ansibility.settings.FindingContext
import de.terletzkiy.ansibility.settings.SeverityPolicy
import de.terletzkiy.ansibility.settings.toProblemHighlightType

/**
 * ANS-V003 "possibly undefined variable" (plan amendment R7/R8, F8.12; your request): an unguarded Jinja use of a
 * variable that has no runtime default, such as `bearer_token = "{{ alloy_tenant_api_key }}"` without the
 * `{% if alloy_tenant_api_key is defined and alloy_tenant_api_key %}` guard of
 * `repos/falcon/ansible/roles/alloy/templates/config-base.alloy.j2:15-17`. The analysis is [PossiblyUndefined].
 *
 * No language: role templates have any file type, task and vars files are YAML; the inspection returns at once outside
 * Ansible roots, for other file kinds and for injected fragments (their host file reports them). It never depends on the
 * selected env, host or play (D32).
 *
 * Severity: only [SeverityPolicy] for ANS-V003 with `FindingContext(certainFailure = witness exists)`: ERROR when a
 * reachable host lacks the variable (the message names the environment and the hosts), else the preset level
 * (WARNING), ERROR with the root's "Unguarded optional variables without a default are always errors".
 */
class AnsiblePossiblyUndefinedInspection : LocalInspectionTool() {

    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val project = file.project
        if (DumbService.isDumb(project) || InjectedLanguageManager.getInstance(project).isInjectedFragment(file)) return null
        val viewProvider = file.viewProvider
        if (viewProvider.getPsi(viewProvider.baseLanguage) != file) return null
        val context = AnsibleWorkspace.getInstance(project).contextOf(viewProvider.virtualFile) ?: return null
        val findings = PossiblyUndefined(project, file, context).findings()
        if (findings.isEmpty()) return null
        return findings.mapNotNull { finding ->
            ProgressManager.checkCanceled()
            val highlight = level(project, context.root, finding).toProblemHighlightType() ?: return@mapNotNull null
            val fixes = UndefinedFixes.of(file, context, finding)
            manager.createProblemDescriptor(file, finding.use.nameRange, finding.message, highlight, isOnTheFly, *fixes)
        }.toTypedArray()
    }

    companion object {
        /** The inspection's short name (its id in profiles and `# noinspection` comments). */
        const val SHORT_NAME: String = "AnsiblePossiblyUndefined"

        /** The level of [finding] in [root], from [SeverityPolicy] only. */
        fun level(project: Project, root: AnsibleRoot, finding: UndefinedFinding): Level =
            SeverityPolicy.getInstance(project).level(
                DiagnosticCode.V003_POSSIBLY_UNDEFINED,
                root,
                FindingContext(certainFailure = finding.certainFailure, reachable = finding.kind != UndefinedKind.NOT_REACHED),
            )
    }
}
