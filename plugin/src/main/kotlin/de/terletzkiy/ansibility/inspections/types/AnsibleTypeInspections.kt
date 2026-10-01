package de.terletzkiy.ansibility.inspections.types

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.TypeCheckService
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.settings.FindingContext
import de.terletzkiy.ansibility.settings.SeverityPolicy
import de.terletzkiy.ansibility.settings.toProblemHighlightType
import org.jetbrains.yaml.psi.YAMLFile

/**
 * The type inspections of role-variable values (plan A.6, F3.2, F4.4; Editor › Inspections › Ansible › Types): one
 * inspection per diagnostic code, so each can be tuned on its own. All of them read the findings that
 * [TypeCheckService] computes once per file version and report those of their [code].
 *
 * The severity of every problem comes from [SeverityPolicy] for the file's root: the preset level (Documented types:
 * red for every documented-type mismatch; Runtime-faithful: coercions yellow), and with "Require a reachable play for
 * red" on, findings whose roles no play applies drop to warnings (D6). Problems are registered on the offending
 * value (a key for unsupported keys, the first line for mappings and lists) with the quick fixes of [TypeFixes].
 * `# noinspection <ShortName>` above a key, or `#file: noinspection <ShortName>`, silences them through the YAML
 * plugin's own suppressor (plan F3.6).
 */
abstract class AnsibleTypeInspection(private val code: DiagnosticCode) : LocalInspectionTool() {

    /** The findings depend on the whole file (and on other files); a partial re-run would not be cheaper. */
    override fun runForWholeFile(): Boolean = true

    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        if (file !is YAMLFile) return null
        val project = file.project
        if (InjectedLanguageManager.getInstance(project).isInjectedFragment(file)) return null
        val viewProvider = file.viewProvider
        if (viewProvider.getPsi(viewProvider.baseLanguage) != file) return null
        val findings = TypeCheckService.getInstance(project).findings(file).filter { it.code == code }
        if (findings.isEmpty()) return null
        val virtualFile = file.originalFile.virtualFile ?: viewProvider.virtualFile
        val root = AnsibleWorkspace.getInstance(project).contextOf(virtualFile)?.root ?: return null
        val policy = SeverityPolicy.getInstance(project)
        return findings.mapNotNull { finding ->
            ProgressManager.checkCanceled()
            val highlight = policy.level(code, root, FindingContext(reachable = finding.reachable)).toProblemHighlightType() ?: return@mapNotNull null
            val (element, range) = TypeAnchors.anchor(file, finding.range) ?: return@mapNotNull null
            manager.createProblemDescriptor(element, range, finding.message, highlight, isOnTheFly, *TypeFixes.of(finding, element, root))
        }.toTypedArray().takeIf { it.isNotEmpty() }
    }
}

/** ANS-T001: ansible-core rejects the value (`check_type_*` raises, or validation crashes). */
class AnsibleValueRejectedInspection : AnsibleTypeInspection(DiagnosticCode.T001_VALUE_REJECTED)

/** ANS-T002: an unknown key inside a dict (or list of dicts) whose spec has `options`. */
class AnsibleUnsupportedSubOptionInspection : AnsibleTypeInspection(DiagnosticCode.T002_UNSUPPORTED_SUB_OPTION)

/** ANS-T003: a nested `required` key is missing while its parent is present. */
class AnsibleMissingRequiredSubOptionInspection : AnsibleTypeInspection(DiagnosticCode.T003_MISSING_REQUIRED_SUB_OPTION)

/** ANS-T004: the value (after coercion) is not among the documented `choices`. */
class AnsibleChoiceMismatchInspection : AnsibleTypeInspection(DiagnosticCode.T004_CHOICE_MISMATCH)

/** ANS-T005: `null` for an option that is required or has a spec default. */
class AnsibleNullForTypedOptionInspection : AnsibleTypeInspection(DiagnosticCode.T005_NULL_FOR_TYPED_OPTION)

/** ANS-T010: a list or dict where `str`/`path` (or `elements: str`) is documented. */
class AnsibleSpecShapeContradictionInspection : AnsibleTypeInspection(DiagnosticCode.T010_SHAPE_CONTRADICTION)

/** ANS-T011: a YAML number, bool or date where `str`/`path` is documented. */
class AnsibleCoercedScalarInspection : AnsibleTypeInspection(DiagnosticCode.T011_COERCED_SCALAR_TO_STR)

/** ANS-T013: a YAML scalar of another type for an `int`, `float` or `bool` option. */
class AnsibleScalarTypeMismatchInspection : AnsibleTypeInspection(DiagnosticCode.T013_SCALAR_TYPE_MISMATCH)

/** ANS-T014: a comma string or a scalar for `list`, a JSON or `k=v` string for `dict`. */
class AnsibleLegacyCoercionInspection : AnsibleTypeInspection(DiagnosticCode.T014_LEGACY_COERCION)

/** ANS-T015: `null` for an optional option without a spec default (ansible-core skips it). */
class AnsibleNullForOptionalInspection : AnsibleTypeInspection(DiagnosticCode.T015_NULL_FOR_OPTIONAL)

/** ANS-T016: a quoted string for an `int`, `float` or `bool` option. */
class AnsibleStringForNumberOrBoolInspection : AnsibleTypeInspection(DiagnosticCode.T016_STRING_FOR_NUMBER_OR_BOOL)
