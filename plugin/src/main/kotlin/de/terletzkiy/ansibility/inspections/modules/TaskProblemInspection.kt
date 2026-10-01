package de.terletzkiy.ansibility.inspections.modules

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.settings.SeverityPolicy
import de.terletzkiy.ansibility.settings.atMost
import de.terletzkiy.ansibility.settings.toProblemHighlightType
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Base of the module option and keyword inspections: reports the [TaskProblem]s of [TaskFileChecks] that [select]
 * picks, each with the severity `SeverityPolicy` gives its code, root and context (capped by
 * [TaskProblem.maxLevel]), and with its quick fixes. Problems whose level is OFF are not reported.
 */
abstract class TaskProblemInspection : LocalInspectionTool() {

    /** The problems of [analysis] this inspection reports. */
    protected abstract fun select(analysis: TaskFileChecks.Analysis, file: PsiFile): List<TaskProblem>

    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val analysis = TaskFileChecks.of(file) ?: return null
        val problems = select(analysis, file)
        if (problems.isEmpty()) return null
        val policy = SeverityPolicy.getInstance(file.project)
        return problems.mapNotNull { problem ->
            ProgressManager.checkCanceled()
            val level = policy.level(problem.code, analysis.root, problem.context).let { level -> problem.maxLevel?.let(level::atMost) ?: level }
            val highlight = level.toProblemHighlightType() ?: return@mapNotNull null
            val (element, range) = anchor(file, problem.range) ?: return@mapNotNull null
            val fixes = problem.fixes.mapNotNull { fix(it, element) }.toTypedArray<LocalQuickFix>()
            manager.createProblemDescriptor(element, range, problem.message, highlight, isOnTheFly, *fixes)
        }.toTypedArray()
    }

    private fun fix(fix: ProblemFix, element: PsiElement): LocalQuickFix? = when (fix) {
        is ProblemFix.MoveToTaskLevel -> MoveToTaskLevelFix(fix.key).takeIf { MoveToTaskLevelFix.isApplicable(element, fix.key) }
        is ProblemFix.RenameKey -> RenameKeyFix(fix.newName).takeIf { keyValueOf(element) != null }
        is ProblemFix.ReplaceValue -> ReplaceValueFix(fix.newValue).takeIf { element is YAMLScalar }
        is ProblemFix.AddOption -> AddOptionFix(fix.name).takeIf { AddOptionFix.isApplicable(element) }
    }

    companion object {
        /**
         * The element to report [range] on and the range inside it: the YAML scalar containing it, else the smallest
         * element spanning it (a key token, a mapping, a sequence).
         */
        fun anchor(file: PsiFile, range: TextRange): Pair<PsiElement, TextRange>? {
            if (range.endOffset > file.textLength) return null
            val start = file.findElementAt(range.startOffset) ?: return null
            val element = PsiTreeUtil.getParentOfType(start, YAMLScalar::class.java, false)?.takeIf { it.textRange.contains(range) }
                ?: run {
                    val end = file.findElementAt((range.endOffset - 1).coerceAtLeast(range.startOffset)) ?: start
                    PsiTreeUtil.findCommonParent(start, end)
                }
                ?: return null
            val elementRange = element.textRange
            if (!elementRange.contains(range)) return null
            return element to range.shiftLeft(elementRange.startOffset)
        }

        /** The key-value whose key is [element] (a key token or the key-value itself). */
        fun keyValueOf(element: PsiElement): YAMLKeyValue? =
            (element as? YAMLKeyValue) ?: (element.parent as? YAMLKeyValue)?.takeIf { it.key == element }
    }
}
