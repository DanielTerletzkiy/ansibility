package de.terletzkiy.ansibility.inspections.vault

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.lang.jinja.template.OuterTextFindings
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.SeverityPolicy
import de.terletzkiy.ansibility.settings.toProblemHighlightType

/**
 * One password-free vault check (plan amendment R7/R8, "R7 diagnostics"; R21 ANS-V107 and ANS-V114): reports the
 * [VaultEnvelopeChecks] findings of its [code], on inline `!vault` values of YAML files and on files of any type. The
 * severity comes from [SeverityPolicy] for the file's root (the defaults outside any root); ignored paths are skipped.
 * Nothing is decrypted and no secret is read.
 */
abstract class VaultEnvelopeInspection(private val code: DiagnosticCode) : LocalInspectionTool() {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val findings = VaultEnvelopeChecks.of(file).filter { it.code == code }
        if (findings.isEmpty()) return null
        val project = file.project
        val virtualFile = file.viewProvider.virtualFile
        if (AnsibilityProjectSettings.getInstance(project).isIgnored(virtualFile)) return null
        val root = AnsibleWorkspace.getInstance(project).rootFor(virtualFile)
        val highlight = SeverityPolicy.getInstance(project).level(code, root).toProblemHighlightType() ?: return null
        return findings.map { finding ->
            ProgressManager.checkCanceled()
            val fixes = VaultFixes.of(finding)?.let { arrayOf(it) } ?: LocalQuickFix.EMPTY_ARRAY
            manager.createProblemDescriptor(finding.element, finding.rangeInElement, finding.message, highlight, isOnTheFly, *fixes).also {
                // A pasted envelope in a template is about its text as delivered: kept by the template's outer-text filter.
                if (code == DiagnosticCode.V107_NOT_WHOLE_FILE_VAULT) it.problemGroup = OuterTextFindings
            }
        }.toTypedArray()
    }
}

/**
 * ANS-V101: a malformed envelope that Ansible refuses when it reads the value: no leading `$ANSIBLE_VAULT` (whitespace,
 * a byte order mark, an empty value), fewer than three header fields, an unknown cipher (`aes256`), a non-hex digit,
 * an odd number of digits, a TAB, missing salt and HMAC separators. No quick fix: the envelope needs its author.
 */
class AnsibleVaultMalformedEnvelopeInspection : VaultEnvelopeInspection(DiagnosticCode.V101_MALFORMED_ENVELOPE)

/**
 * ANS-V102: a `!vault` value that YAML folds (`>`) or flattens (a plain scalar, or a quoted one on one line), so the
 * envelope's lines reach Ansible joined by spaces. Fix: "Convert to literal block".
 */
class AnsibleVaultFoldedValueInspection : VaultEnvelopeInspection(DiagnosticCode.V102_FOLDED_VAULT_VALUE)

/** ANS-V103: trailing spaces or TABs on a payload line (`Odd-length string`). Fix: "Strip trailing whitespace". */
class AnsibleVaultTrailingWhitespaceInspection : VaultEnvelopeInspection(DiagnosticCode.V103_TRAILING_WHITESPACE)

/**
 * ANS-V107 (plan amendment R21, D159): not a whole-file vault. A file of any type whose envelope Ansible does not see,
 * because something comes before `$ANSIBLE_VAULT`: a `!vault |` or `key: !vault |` line (`encrypt_string` output saved
 * as a file), comments or blank lines, indentation, a byte order mark, quotes or other text (the last two only in
 * files Ansible reads as they are). Ansible then uses the file as it is: copy, template and lookups deliver the
 * envelope text, and as a vars file it does not load. Fix: "Convert to whole-file vault" when the envelope is well
 * formed. A normal `key: !vault |` value in YAML is not reported.
 */
class AnsibleVaultNotWholeFileInspection : VaultEnvelopeInspection(DiagnosticCode.V107_NOT_WHOLE_FILE_VAULT)

/**
 * ANS-V114 (plan amendment R21, D163): a YAML value holding an envelope without the `!vault` tag, which Ansible passes
 * on as a string. Fix: "Add !vault tag".
 */
class AnsibleVaultUntaggedEnvelopeInspection : VaultEnvelopeInspection(DiagnosticCode.V114_UNTAGGED_VAULT_VALUE)
