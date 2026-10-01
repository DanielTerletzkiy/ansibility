package de.terletzkiy.ansibility.inspections.vault

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.SeverityPolicy
import de.terletzkiy.ansibility.settings.toProblemHighlightType

/**
 * One password-free vault check (plan amendment R7/R8, "R7 diagnostics"): reports the [VaultEnvelopeChecks] findings
 * of its [code], on inline `!vault` values of YAML files and on whole-file vaults of any file type. The severity comes
 * from [SeverityPolicy] for the file's root (the defaults outside any root); ignored paths are skipped. Nothing is
 * decrypted and no secret is read.
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
            val fixes = finding.fix?.let { arrayOf(VaultFixes.of(it)) } ?: LocalQuickFix.EMPTY_ARRAY
            manager.createProblemDescriptor(finding.element, finding.rangeInElement, finding.message, highlight, isOnTheFly, *fixes)
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
