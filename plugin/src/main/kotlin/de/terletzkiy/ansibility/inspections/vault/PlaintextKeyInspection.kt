package de.terletzkiy.ansibility.inspections.vault

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.BatchQuickFix
import com.intellij.codeInspection.CommonProblemDescriptor
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.lang.jinja.template.OuterTextFindings
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.settings.FindingContext
import de.terletzkiy.ansibility.settings.SeverityPolicy
import de.terletzkiy.ansibility.settings.toProblemHighlightType
import de.terletzkiy.ansibility.vault.actions.PlainValueRef
import de.terletzkiy.ansibility.vault.actions.VaultFileOperations
import de.terletzkiy.ansibility.vault.actions.VaultValueActions
import de.terletzkiy.ansibility.vault.keys.PlaintextKeyExclusions
import de.terletzkiy.ansibility.vault.vcs.TrackedStatus
import de.terletzkiy.ansibility.vault.vcs.TrackedStatuses
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * ANS-V108 "Plaintext private key" (plan amendment R21, D160–D162), on text files of any type in projects with an
 * Ansible root ([PlaintextKeyChecks]). The severity comes from [SeverityPolicy] per finding: a plaintext key or a
 * committed vault password file is ERROR; a protected key, a key-like name or a file that is not committed yet is
 * WARNING; an ignored file, an excluded path (`vault.keys.PlaintextKeyExclusions`) and a password source outside
 * version control get nothing. The VCS status comes from `vault.vcs.TrackedStatuses` (every file counts as not under
 * version control without the VCS module); a status change re-highlights the file.
 *
 * Fixes: "Encrypt file" (the files Ansible reads as they are, inside a root) and "Encrypt value" (a key in a YAML value
 * Ansible loads); none for a password source, which must leave version control instead. The highlighted range is a
 * key's BEGIN marker, or empty for the file-level signals. Nothing is decrypted, read beyond the checks' window, or
 * put into a message.
 */
class AnsiblePlaintextPrivateKeyInspection : LocalInspectionTool() {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        if (!PlaintextKeyChecks.isBaseFile(file)) return null
        val project = file.project
        val virtualFile = file.viewProvider.virtualFile
        val verdicts = PlaintextKeyChecks.of(file)
        val statuses = TrackedStatuses.getInstance(project)
        if (verdicts.isEmpty() || PlaintextKeyExclusions.isExcluded(project, virtualFile)) {
            statuses.dependsOnStatus(virtualFile, null)
            return null
        }
        val status = statuses.status(virtualFile)
        statuses.dependsOnStatus(virtualFile, status)
        if (status == TrackedStatus.IGNORED) return null
        val root = AnsibleWorkspace.getInstance(project).rootFor(virtualFile)
        val policy = SeverityPolicy.getInstance(project)
        val encryptsFile = PlaintextKeyChecks.encryptsFile(project, virtualFile, root)
        val problems = verdicts.mapNotNull { verdict ->
            ProgressManager.checkCanceled()
            val message = PlaintextKeyChecks.message(verdict, status, insideRoot = root != null && !root.detached) ?: return@mapNotNull null
            val context = FindingContext(weakSecretSignal = verdict.isWeak, uncommitted = status == TrackedStatus.UNTRACKED)
            val highlight = policy.level(DiagnosticCode.V108_PLAINTEXT_PRIVATE_KEY, root, context).toProblemHighlightType()
                ?: return@mapNotNull null
            val value = verdict.value?.takeIf { it.isValid && it.textRange.contains(verdict.range) }
            val fix: LocalQuickFix? = when {
                verdict.signal == KeySignal.PASSWORD_SOURCE -> null
                value != null -> EncryptValueFix()
                encryptsFile -> EncryptFileFix()
                else -> null
            }
            val fixes = fix?.let { arrayOf(it) } ?: LocalQuickFix.EMPTY_ARRAY
            val descriptor = if (value != null) {
                manager.createProblemDescriptor(value, verdict.range.shiftLeft(value.textRange.startOffset), message, highlight, isOnTheFly, *fixes)
            } else {
                manager.createProblemDescriptor(file, verdict.range, message, highlight, isOnTheFly, *fixes)
            }
            // A key in a template is about its text as delivered: kept by the template's outer-text filter.
            descriptor.also { it.problemGroup = OuterTextFindings }
        }
        return problems.toTypedArray().takeIf { it.isNotEmpty() }
    }
}

/**
 * ANS-V108 "Encrypt file" (family "Ansibility Vault"): encrypts the file in place with Ansibility Vault's Encrypt File
 * (`VaultFileOperations.encrypt`: the id question, mode 0600, one undo step; it skips password sources and files
 * outside a root by itself). "Fix all" in Inspect Code encrypts every selected file with one confirmation. The preview
 * describes the action and never runs it.
 */
class EncryptFileFix : LocalQuickFix, BatchQuickFix {
    override fun getName(): String = AnsibilityVaultChecksBundle.message("fix.v108.encrypt.file")

    override fun getFamilyName(): String = AnsibilityVaultChecksBundle.message("fix.v108.family")

    override fun startInWriteAction(): Boolean = false

    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo {
        val name = previewDescriptor.psiElement?.containingFile?.name.orEmpty()
        return IntentionPreviewInfo.Html(HtmlChunk.text(AnsibilityVaultChecksBundle.message("fix.v108.encrypt.file.preview", name)))
    }

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val file = fileOf(descriptor.psiElement) ?: return
        VaultFileOperations.getInstance(project).encrypt(listOf(file))
    }

    override fun applyFix(project: Project, descriptors: Array<CommonProblemDescriptor>, psiElementsToIgnore: List<PsiElement>, refreshViews: Runnable?) {
        val files = descriptors.asSequence()
            .filterIsInstance<ProblemDescriptor>()
            .filter { descriptor -> descriptor.fixes.orEmpty().any { it is EncryptFileFix } }
            .mapNotNull { fileOf(it.psiElement) }
            .distinct()
            .toList()
        if (files.isNotEmpty()) VaultFileOperations.getInstance(project).encrypt(files)
        refreshViews?.run()
    }

    private fun fileOf(element: PsiElement?): VirtualFile? = element?.containingFile?.originalFile?.viewProvider?.virtualFile
}

/**
 * ANS-V108 "Encrypt value": a key held in a YAML value Ansible loads becomes a `!vault |` block (F7.3's Encrypt value,
 * `VaultValueActions.encrypt`, with its id question). Its own family, so it never joins a batch of file encryptions.
 * The preview describes the action and never runs it or shows the value.
 */
class EncryptValueFix : LocalQuickFix {
    override fun getName(): String = AnsibilityVaultChecksBundle.message("fix.v108.encrypt.value")

    override fun getFamilyName(): String = name

    override fun startInWriteAction(): Boolean = false

    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo {
        val key = ((previewDescriptor.psiElement as? YAMLScalar)?.parent as? YAMLKeyValue)?.keyText.orEmpty()
        return IntentionPreviewInfo.Html(HtmlChunk.text(AnsibilityVaultChecksBundle.message("fix.v108.encrypt.value.preview", key)))
    }

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val scalar = descriptor.psiElement as? YAMLScalar ?: return
        val ref = PlainValueRef.of(scalar) ?: return
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
            ?.takeIf { FileDocumentManager.getInstance().getFile(it.document) == ref.file }
        VaultValueActions.getInstance(project).encrypt(ref, editor)
    }
}
