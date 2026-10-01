package de.terletzkiy.ansibility.typeflow

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.lang.jinja.injection.JinjaInjectionMode
import de.terletzkiy.ansibility.lang.jinja.injection.JinjaYamlInjections
import de.terletzkiy.ansibility.lang.jinja.psi.AnsibleJinjaFile
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaExpression
import de.terletzkiy.ansibility.semantics.typeflow.AValue
import de.terletzkiy.ansibility.semantics.typeflow.JinjaTemplate
import de.terletzkiy.ansibility.semantics.typeflow.JinjaToken
import de.terletzkiy.ansibility.semantics.typeflow.JinjaTokenKind
import de.terletzkiy.ansibility.semantics.typeflow.JinjaTypeEvaluator
import de.terletzkiy.ansibility.semantics.typeflow.TemplateTypes
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * The PSI entry of the Jinja type evaluation (plan A.5 `JinjaTypeEvaluator` on PSI, F3.4 T020, WU C5): the
 * `:semantics` evaluator reads its template model from the leaves of the Ansible Jinja PSI instead of re-lexing the
 * text, whenever that PSI exists (a template file, a fragment injected into a YAML scalar, any expression in them).
 * Where there is none, callers keep the text path (`JinjaTypeEvaluator.evaluate(text)` through [LexerJinjaTokenizer]).
 *
 * The leaves are the lexer's tokens (the PSI is built from them), mapped to token kinds by the same rule as
 * [LexerJinjaTokenizer]; so for the same text both paths give the same types. Call in a read action.
 */
object PsiTemplateTypes {
    /** The template the Jinja tree [file] reads: a template file's base tree, or an injected fragment. */
    fun templateOf(file: AnsibleJinjaFile): JinjaTemplate = JinjaTemplate.parse(file.text, tokens(file, 0))

    /**
     * The template of the fragment injected into [scalar] in template mode, when the platform has already built that
     * fragment ([JinjaYamlInjections.cachedInjectedFile]: never computed here, so a check over a whole vars file costs
     * no more than its text path) and it reads exactly [source] (the scalar's loaded value as the caller evaluates it).
     * Null when there is no such fragment (not injected yet, an expression-mode value, or a value the YAML escaper and
     * the loader read differently, such as the final line break of a block scalar): the caller then evaluates the text.
     */
    fun templateOf(scalar: YAMLScalar, source: String): JinjaTemplate? {
        val fragment = JinjaYamlInjections.cachedInjectedFile(scalar) ?: return null
        if (JinjaYamlInjections.modeOf(fragment) != JinjaInjectionMode.TEMPLATE) return null
        if (!StringUtil.equals(fragment.viewProvider.contents, source)) return null
        return templateOf(fragment)
    }

    /** The logical and runtime types `{{ expression }}` has, [expression] being any Jinja PSI expression. */
    fun typesOf(expression: JinjaExpression, evaluator: JinjaTypeEvaluator): TemplateTypes {
        val text = expression.text
        val source = "$OPEN $text $CLOSE"
        val tokens = ArrayList<JinjaToken>()
        tokens += JinjaToken(JinjaTokenKind.VAR_START, OPEN, 0)
        tokens += tokens(expression, OPEN.length + 1 - expression.textRange.startOffset)
        tokens += JinjaToken(JinjaTokenKind.VAR_END, CLOSE, OPEN.length + 1 + text.length + 1)
        return evaluator.evaluate(JinjaTemplate.parse(source, tokens))
    }

    /**
     * The logical type of [expression] (plan A.5: what the author means): a bare name follows its definitions, a
     * trailing typed filter gives its declared type, literals their own type; anything else is unknown.
     */
    fun logicalType(expression: JinjaExpression, evaluator: JinjaTypeEvaluator): AValue = typesOf(expression, evaluator).logical

    /** The tokens of [element]'s leaves (whitespace inside tags dropped), with offsets moved by [shift]. */
    fun tokens(element: PsiElement, shift: Int): List<JinjaToken> {
        val end = element.textRange.endOffset
        val tokens = ArrayList<JinjaToken>()
        var leaf: PsiElement? = PsiTreeUtil.firstChild(element)
        var steps = 0
        while (leaf != null && leaf.textRange.startOffset < end) {
            if (++steps and 0xFF == 0) ProgressManager.checkCanceled()
            val current: PsiElement = leaf
            val kind = if (current.firstChild == null && current.textLength > 0) LexerJinjaTokenizer.kindOf(current.node.elementType) else null
            if (kind != null) tokens += JinjaToken(kind, current.text, current.textRange.startOffset + shift)
            leaf = PsiTreeUtil.nextLeaf(current)
        }
        return tokens
    }

    private const val OPEN = "{{"
    private const val CLOSE = "}}"
}
