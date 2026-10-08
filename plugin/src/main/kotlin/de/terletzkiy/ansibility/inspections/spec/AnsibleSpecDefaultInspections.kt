package de.terletzkiy.ansibility.inspections.spec

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.semantics.diagnostics.Level
import de.terletzkiy.ansibility.settings.SeverityPolicy
import de.terletzkiy.ansibility.settings.atMost
import de.terletzkiy.ansibility.settings.toProblemHighlightType

/**
 * ANS-S003 "Documented default differs from the role default" (plan amendment R23, D169–D171): an argument_specs
 * `default:` (every entry point, and dict sub-options whose key the role default's literal dict holds) that differs from
 * the role default Ansible uses ([SpecDefaultChecks]). ansible-core never applies the documented default, so the spec
 * is the wrong side: the error sits on the spec's `default:` value and names the defaults file and line.
 *
 * On the role default's key in the defaults file a quiet twin (at most INFO: no highlight, Alt+Enter offers the same
 * fixes for every differing entry point) points to the spec; it is reported only on the fly, so batch inspection counts
 * each mismatch once. Fixes: "Set the documented default to …" (first) and "Remove the documented default"; none
 * changes `defaults/`.
 *
 * Severity only through [SeverityPolicy] (ERROR in every preset), lowered per finding to its cap (WARNING for a YAML
 * typing difference and a `{{ name }}` chain, WEAK_WARNING for `null` against an empty value).
 */
class AnsibleSpecDefaultMismatchInspection : LocalInspectionTool() {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val (context, _, analysis) = SpecDefaultChecks.forFile(file) ?: return null
        val level = SeverityPolicy.getInstance(file.project).level(DiagnosticCode.S003_SPEC_DEFAULT_MISMATCH, context.root)
        val virtualFile = file.viewProvider.virtualFile
        if (virtualFile == analysis.specFile) {
            return analysis.findings(DiagnosticCode.S003_SPEC_DEFAULT_MISMATCH).mapNotNull { finding ->
                ProgressManager.checkCanceled()
                val highlight = level.atMost(finding.cap).toProblemHighlightType() ?: return@mapNotNull null
                val targets = listOf(finding.target)
                manager.createProblemDescriptor(file, finding.range, finding.message, highlight, isOnTheFly, *fixes(analysis, targets, finding.source, finding.fixValue))
            }.toTypedArray()
        }
        if (!isOnTheFly) return null
        val highlight = level.atMost(Level.INFO).toProblemHighlightType() ?: return null
        return analysis.twins.filter { it.file == virtualFile }.map { twin ->
            manager.createProblemDescriptor(file, twin.range, twin.message, highlight, true, *fixes(analysis, twin.targets, twin.source, twin.fixValue))
        }.toTypedArray()
    }

    private fun fixes(analysis: SpecDefaultAnalysis, targets: List<SpecTarget>, source: RoleValue?, value: String?): Array<LocalQuickFix> {
        val remove = RemoveDocumentedDefaultFix(analysis.specFile, targets)
        if (source == null || value == null) return arrayOf(remove)
        return arrayOf(SetDocumentedDefaultFix(analysis.specFile, analysis.role.ref.name, targets, source, value), remove)
    }

    companion object {
        const val SHORT_NAME: String = "AnsibleSpecDefaultMismatch"
    }
}

/**
 * 🟣 CLAUDE ANS-S004 "Documented default never applied" (plan amendment R23, D172): an argument_specs `default:` while
 * nothing sets the variable for the role (no role default of its own or of a dependency, no `vars/` key, not set by the
 * role's tasks). Ansible never applies the documented value, so the variable is undefined unless the caller or the
 * inventory sets it. Fixes: "Add 'name: value' to defaults/main.yml" (the file the role loads its defaults from) and
 * "Remove the documented default". WARNING in every preset, only through [SeverityPolicy].
 */
class AnsibleSpecDefaultNotAppliedInspection : LocalInspectionTool() {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val (context, role, analysis) = SpecDefaultChecks.forFile(file) ?: return null
        if (file.viewProvider.virtualFile != analysis.specFile) return null
        val highlight = SeverityPolicy.getInstance(file.project).level(DiagnosticCode.S004_SPEC_DEFAULT_NOT_APPLIED, context.root)
            .toProblemHighlightType() ?: return null
        return analysis.findings(DiagnosticCode.S004_SPEC_DEFAULT_NOT_APPLIED).map { finding ->
            val remove = RemoveDocumentedDefaultFix(analysis.specFile, listOf(finding.target))
            val value = finding.fixValue
            val label = finding.fixTarget
            val fixes: Array<LocalQuickFix> = if (value != null && label != null) {
                arrayOf(AddDocumentedDefaultToDefaultsFix(analysis.specFile, role.ref.dir, finding.target, value, label), remove)
            } else {
                arrayOf(remove)
            }
            manager.createProblemDescriptor(file, finding.range, finding.message, highlight, isOnTheFly, *fixes)
        }.toTypedArray()
    }

    companion object {
        const val SHORT_NAME: String = "AnsibleSpecDefaultNotApplied"
    }
}

/**
 * 🟣 CLAUDE ANS-S005 "Role default not documented" (plan amendment R23, D172): a role default that an optional,
 * non-secret argument_specs option documents no `default:` for, reported on the option's key. Fix: "Document the default
 * in argument_specs (default: …)", the role default copied as written. INFO (no highlight) in every preset, only
 * through [SeverityPolicy].
 */
class AnsibleSpecDefaultUndocumentedInspection : LocalInspectionTool() {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val (context, _, analysis) = SpecDefaultChecks.forFile(file) ?: return null
        if (file.viewProvider.virtualFile != analysis.specFile) return null
        val highlight = SeverityPolicy.getInstance(file.project).level(DiagnosticCode.S005_SPEC_DEFAULT_UNDOCUMENTED, context.root)
            .toProblemHighlightType() ?: return null
        return analysis.findings(DiagnosticCode.S005_SPEC_DEFAULT_UNDOCUMENTED).map { finding ->
            val source = finding.source
            val value = finding.fixValue
            val fixes: Array<LocalQuickFix> = if (source != null && value != null) {
                arrayOf(DocumentRoleDefaultFix(analysis.specFile, listOf(finding.target), source, value))
            } else {
                emptyArray()
            }
            manager.createProblemDescriptor(file, finding.range, finding.message, highlight, isOnTheFly, *fixes)
        }.toTypedArray()
    }

    companion object {
        const val SHORT_NAME: String = "AnsibleSpecDefaultUndocumented"
    }
}
