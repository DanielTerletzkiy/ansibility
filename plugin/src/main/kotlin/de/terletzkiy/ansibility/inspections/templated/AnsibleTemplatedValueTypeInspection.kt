package de.terletzkiy.ansibility.inspections.templated

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.semantics.diagnostics.Level
import de.terletzkiy.ansibility.settings.FindingContext
import de.terletzkiy.ansibility.settings.SeverityPolicy
import de.terletzkiy.ansibility.settings.isMoreSevereThan
import de.terletzkiy.ansibility.settings.toProblemHighlightType
import de.terletzkiy.ansibility.typeflow.TemplatedFinding
import de.terletzkiy.ansibility.typeflow.TemplatedValueTypes
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * ANS-T020 (plan A.5, A.6, F3.4): a templated value of a spec'd variable whose type is certainly outside the
 * documented type, e.g. `haproxy_settings_kernel_somaxconn: '{{ haproxy_settings_maximum_connections }}'` (an int
 * 65535 into a `str` option) at `golden/roles/haproxy/defaults/main.yml:15`.
 *
 * The must-rule: a finding only when the logical type (the definitions' type for a bare `{{ name }}`, a trailing
 * typed filter, `str` for a multi-node template) **and** the runtime type under the root's target ansible-core are
 * both outside the documented type. So `'{{ x | int }}'` into a `str` option is silent on 2.18 (it arrives as `'5'`)
 * and red on 2.19+ (it arrives as `5`). The analysis is [TemplatedValueTypes].
 *
 * Severity: [SeverityPolicy] for ANS-T020 (ERROR under "Documented types", WARNING under "Runtime-faithful"); when
 * ansible-core certainly rejects the value, the level of the equivalent literal rejection (ANS-T001) applies if it
 * is more severe, so a list templated into an `int` option is red in every preset. Reachability (D6) is passed as
 * context. The quick fix appends `| string` (or `| int`, `| float`, `| bool`), see [AppendFilterFix].
 */
class AnsibleTemplatedValueTypeInspection : LocalInspectionTool() {

    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val project = file.project
        if (file !is YAMLFile || DumbService.isDumb(project)) return null
        if (InjectedLanguageManager.getInstance(project).isInjectedFragment(file)) return null
        val virtualFile = file.viewProvider.virtualFile
        val context = AnsibleWorkspace.getInstance(project).contextOf(virtualFile) ?: return null
        if (context.kind !in TemplatedValueTypes.CHECKED_FILE_KINDS) return null
        val findings = TemplatedValueTypes(project, file, context).findings()
        if (findings.isEmpty()) return null
        return findings.mapNotNull { finding ->
            ProgressManager.checkCanceled()
            val highlight = level(project, context.root, finding).toProblemHighlightType() ?: return@mapNotNull null
            val scalar = scalarAt(file, finding) ?: return@mapNotNull null
            val fixes = AppendFilterFix.of(finding.documented, finding.mismatch, scalar.text)
                ?.let { arrayOf<LocalQuickFix>(it) } ?: LocalQuickFix.EMPTY_ARRAY
            manager.createProblemDescriptor(scalar, finding.message, isOnTheFly, fixes, highlight)
        }.toTypedArray()
    }

    /** The YAML scalar that wrote the finding's value (its range may include an anchor or tag before the text). */
    private fun scalarAt(file: PsiFile, finding: TemplatedFinding): YAMLScalar? {
        val range = finding.range
        val leaf = file.findElementAt((range.endOffset - 1).coerceAtLeast(range.startOffset)) ?: return null
        return PsiTreeUtil.getParentOfType(leaf, YAMLScalar::class.java, false)?.takeIf { range.contains(it.textRange) || it.textRange.contains(range) }
    }

    companion object {
        /** The inspection's short name (its id in profiles and `# noinspection` comments). */
        const val SHORT_NAME: String = "AnsibleTemplatedValueType"

        /**
         * The level of [finding] in [root]: ANS-T020's, raised to ANS-T001's for a certain rejection (the equivalent
         * literal code, plan A.6); OFF stays OFF.
         */
        fun level(project: Project, root: AnsibleRoot, finding: TemplatedFinding): Level {
            val policy = SeverityPolicy.getInstance(project)
            val context = FindingContext(reachable = finding.reachable)
            val level = policy.level(DiagnosticCode.T020_TEMPLATED_VALUE_TYPE, root, context)
            if (level == Level.OFF || !finding.mismatch.certainRejection) return level
            val rejection = policy.level(DiagnosticCode.T001_VALUE_REJECTED, root, context)
            return if (rejection.isMoreSevereThan(level) && rejection != Level.OFF) rejection else level
        }
    }
}
