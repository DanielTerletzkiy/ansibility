package de.terletzkiy.ansibility.inspections.templated

import com.intellij.modcommand.ModPsiUpdater
import com.intellij.modcommand.PsiUpdateModCommandQuickFix
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import de.terletzkiy.ansibility.semantics.schema.OptionType
import de.terletzkiy.ansibility.semantics.typeflow.BaseType
import de.terletzkiy.ansibility.semantics.typeflow.JinjaExpr
import de.terletzkiy.ansibility.semantics.typeflow.TemplateShape
import de.terletzkiy.ansibility.semantics.typeflow.TemplatedMismatch
import de.terletzkiy.ansibility.typeflow.AnsibilityTypeflowBundle

/**
 * ANS-T020 quick fix "Append '| string'" (or `| int`, `| float`, `| bool`; plan F3.4): makes the documented type
 * explicit in a single-expression template, `'{{ x }}'` → `'{{ x | string }}'`. When the expression's last operator
 * binds looser than a filter (inline `if`, `~`, arithmetic, `and`/`or`, `not`), it is parenthesised first:
 * `'{{ a ~ b }}'` → `'{{ (a ~ b) | string }}'`.
 *
 * Text is only inserted, never rewritten, through the scalar's manipulator, so quoting and escapes of the expression
 * stay as written. [insertions] are offsets in the scalar element's text, applied from the last to the first.
 */
class AppendFilterFix(private val filter: String, private val insertions: List<Pair<Int, String>>) : PsiUpdateModCommandQuickFix() {
    override fun getName(): String = AnsibilityTypeflowBundle.message("fix.append.filter", filter)

    override fun getFamilyName(): String = AnsibilityTypeflowBundle.message("fix.append.filter.family")

    override fun applyFix(project: Project, element: PsiElement, updater: ModPsiUpdater) {
        var current: PsiElement = element
        for ((offset, text) in insertions.sortedByDescending { it.first }) {
            val manipulator = ElementManipulators.getManipulator(current) ?: return
            if (offset > current.textLength) return
            current = manipulator.handleContentChange(current, TextRange(offset, offset), text) ?: return
        }
    }

    companion object {
        /**
         * The fix for [mismatch] against [documented] in a scalar whose text in the file is [scalarText], or null when
         * none applies: only single-expression templates, only to `str`/`path` (`| string`, for any type) and to
         * `int`/`float`/`bool` from scalar types (converting a list or dict with `| int` is never what was meant).
         */
        fun of(documented: OptionType, mismatch: TemplatedMismatch, scalarText: String): AppendFilterFix? {
            val template = mismatch.types.template
            if (template.shape != TemplateShape.SINGLE_EXPRESSION) return null
            val expression = template.singleExpression ?: return null
            val logical = mismatch.logical.types
            val filter = when (documented) {
                OptionType.Str, OptionType.Path -> "string"
                OptionType.Int -> "int".takeIf { logical.all { it in SCALARS } }
                OptionType.Float -> "float".takeIf { logical.all { it in SCALARS } }
                OptionType.Bool -> "bool".takeIf { logical.all { it in SCALARS } }
                else -> null
            } ?: return null
            val open = scalarText.indexOf("{{").takeIf { it >= 0 } ?: return null
            val close = scalarText.lastIndexOf("}}").takeIf { it > open } ?: return null
            var exprStart = open + 2
            if (exprStart < scalarText.length && scalarText[exprStart] in "-+") exprStart++
            while (exprStart < close && scalarText[exprStart].isWhitespace()) exprStart++
            var exprEnd = close
            if (exprEnd > exprStart && scalarText[exprEnd - 1] == '-') exprEnd--
            while (exprEnd > exprStart && scalarText[exprEnd - 1].isWhitespace()) exprEnd--
            if (exprEnd <= exprStart) return null
            val insertions = if (needsParentheses(expression)) {
                listOf(exprStart to "(", exprEnd to ") | $filter")
            } else {
                listOf(exprEnd to " | $filter")
            }
            return AppendFilterFix(filter, insertions)
        }

        private val SCALARS = setOf(BaseType.STR, BaseType.INT, BaseType.FLOAT, BaseType.BOOL)

        /** Whether a filter appended to [expression] would bind to a part of it only. */
        private fun needsParentheses(expression: JinjaExpr): Boolean = when (expression) {
            is JinjaExpr.Conditional, is JinjaExpr.Concat, is JinjaExpr.Binary, is JinjaExpr.TupleLiteral -> true
            is JinjaExpr.Unary -> expression.operator == "not"
            else -> false
        }
    }
}
