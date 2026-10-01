package de.terletzkiy.ansibility.inspections.undefined

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes
import de.terletzkiy.ansibility.lang.jinja.psi.AnsibleJinjaFile
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaArgumentList
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaBinaryExpression
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaBlockStatementElement
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaCallBlock
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaCallExpression
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaConditionalExpression
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaCompareExpression
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaDictLiteral
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaExpression
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaFilterCall
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaForStatement
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaForTag
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaIfStatement
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaIfTag
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaLiteral
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaListLiteral
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaMacro
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaBody
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaMemberAccess
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaOutputTag
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaParenthesizedExpression
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaSingleTagStatement
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaSubscription
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaTag
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaTestExpr
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaUnaryExpression
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaVariableReference
import de.terletzkiy.ansibility.lang.jinja.scopes.JinjaScopes

/**
 * The free variable uses of an Ansible Jinja tree (a template file or a fragment injected into YAML) and whether each
 * is guarded, on the PSI (plan amendment R7/R8, F8.12, guard analysis (c)). Locals come from [JinjaScopes] (`set`,
 * `for` targets and `loop`, macro and call parameters, `with`, imports), so only names Ansible resolves are returned;
 * called names (`lookup(…)`, `range(…)`) are Jinja globals, not variables.
 *
 * A use is **guarded** when evaluating it cannot fail on an undefined root name:
 * - it is the operand of `is defined`/`is undefined` or of `| default(…)`/`| d(…)` (with its constant accessors:
 *   `x.y | default('')`); from 2.19 also when a `default` follows a chain of filters ([GuardRules.undefinedPassesFilters]);
 * - it is inside the argument list of a `default` filter (evaluated only when the operand is undefined, a fallback);
 * - an enclosing condition implies the root is defined: the right side of `x is defined and …`, of
 *   `x is not defined or …`, the value of `… if x is defined else …`, the branch of an `{% if %}`/`{% elif %}` whose
 *   condition implies it, or a later branch or `{% else %}` of conditions implying it when false
 *   (`{% if x is not defined %}…{% else %}{{ x }}`); macro bodies do not inherit conditions around the definition.
 *
 * Truthiness is **no** guard: `{% if x %}` fails on an undefined `x` on 2.18.8 and on 2.21.4, and so does
 * `{% if x is not none %}{{ x }}`. A condition that is constantly false while `x` is undefined is one:
 * `{% if x | default(false) %}{{ x }}{% endif %}` renders nothing for an undefined `x` (probed on both versions). The
 * facts of a condition come from [ConditionFacts] over the tree [JinjaConditions] builds, the same tree the text-level
 * fallback [TextJinjaUses] builds from tokens, so both report the same uses. Call in a read action.
 */
internal object PsiJinjaUses {
    /** The uses of [file], in source order. */
    fun collect(file: AnsibleJinjaFile, rules: GuardRules): List<RawUse> {
        val scopes = JinjaScopes.of(file)
        val uses = ArrayList<RawUse>()
        for (reference in PsiTreeUtil.findChildrenOfType(file, JinjaVariableReference::class.java)) {
            ProgressManager.checkCanceled()
            if (scopes.resolve(reference) != null) continue
            analyse(reference, rules)?.let(uses::add)
        }
        return uses
    }

    private fun analyse(reference: JinjaVariableReference, rules: GuardRules): RawUse? {
        val name = reference.name
        if ((reference.parent as? JinjaCallExpression)?.callee == reference) return null
        // the postfix chain: constant accessors extend the path, a dynamic subscript or a call ends it
        var top: PsiElement = reference
        var pathEnd = reference.textRange.endOffset
        var pathOpen = true
        while (true) {
            val parent = top.parent
            when {
                parent is JinjaMemberAccess && parent.qualifier == top -> {
                    val methodCall = (parent.parent as? JinjaCallExpression)?.callee == parent
                    if (pathOpen && !methodCall && parent.memberNameElement != null) pathEnd = parent.textRange.endOffset else pathOpen = false
                }
                parent is JinjaSubscription && parent.qualifier == top -> {
                    if (pathOpen && isConstantIndex(parent.index)) pathEnd = parent.textRange.endOffset else pathOpen = false
                }
                parent is JinjaCallExpression && parent.callee == top -> pathOpen = false
                else -> break
            }
            top = parent
        }
        var operand: PsiElement = top
        var context = top.parent
        while (context is JinjaParenthesizedExpression) {
            operand = context
            context = context.parent
        }
        val test = (context as? JinjaTestExpr)?.takeIf { it.operand == operand }
        val filter = (context as? JinjaFilterCall)?.takeIf { it.operand == operand }
        if (test != null && rules.typeTestsTolerateUndefined && unqualifiedName(test) in GuardRules.TOLERANT_TESTS) return null
        val directGuard = test != null && unqualifiedName(test) in GuardRules.DEFINED_TESTS ||
            filter != null && filter.filterNameElement?.isQualified == false && filter.filterName in GuardRules.DEFAULT_FILTERS
        val guarded = directGuard ||
            rules.undefinedPassesFilters && filter != null && defaultLater(filter) ||
            inDefaultArgument(reference) ||
            name in conditionFacts(top, rules)
        return RawUse(
            name = name,
            nameRange = reference.textRange,
            pathRange = TextRange(reference.textRange.startOffset, pathEnd),
            chainEnd = top.textRange.endOffset,
            guarded = guarded,
            mandatory = filter?.filterName in GuardRules.MANDATORY_FILTERS,
            iterable = (context as? JinjaForTag)?.iterable == operand,
            conditional = isConditional(top),
            statement = statementOf(reference)?.textRange,
        )
    }

    private fun isConstantIndex(index: JinjaExpression?): Boolean =
        index is JinjaLiteral && (index.kind == JinjaLiteral.Kind.STRING || index.kind == JinjaLiteral.Kind.INTEGER)

    private fun unqualifiedName(test: JinjaTestExpr): String? = test.testNameElement?.takeIf { !it.isQualified }?.name

    /** `x | f | g | default(…)`: a `default` somewhere along the filter chain that starts at [first]. */
    private fun defaultLater(first: JinjaFilterCall): Boolean {
        var filter: JinjaFilterCall = first
        while (true) {
            if (filter.filterNameElement?.isQualified == false && filter.filterName in GuardRules.DEFAULT_FILTERS) return true
            filter = (filter.parent as? JinjaFilterCall)?.takeIf { it.operand == filter } ?: return false
        }
    }

    /** Whether [element] is inside the arguments of a `default`/`d` filter of the same tag. */
    private fun inDefaultArgument(element: PsiElement): Boolean {
        var current: PsiElement? = element.parent
        while (current != null && current !is JinjaTag && current !is JinjaOutputTag && current !is PsiFile) {
            if (current is JinjaArgumentList) {
                val filter = current.parent as? JinjaFilterCall
                if (filter != null && filter.filterNameElement?.isQualified == false && filter.filterName in GuardRules.DEFAULT_FILTERS) return true
            }
            current = current.parent
        }
        return false
    }

    /** The root names the enclosing conditions of [element] imply to be defined. */
    private fun conditionFacts(element: PsiElement, rules: GuardRules): Set<String> {
        val known = HashSet<String>()
        var child = element
        var node = element.parent
        while (node != null && node !is PsiFile) {
            when (node) {
                is JinjaBinaryExpression -> if (child == node.right) {
                    when (node.operatorType) {
                        AnsibleJinjaTokenTypes.AND_KEYWORD -> known += JinjaConditions.whenTrue(node.left, rules)
                        AnsibleJinjaTokenTypes.OR_KEYWORD -> known += JinjaConditions.whenFalse(node.left, rules)
                    }
                }
                is JinjaConditionalExpression -> when (child) {
                    node.thenExpression -> known += JinjaConditions.whenTrue(node.condition, rules)
                    node.elseExpression -> known += JinjaConditions.whenFalse(node.condition, rules)
                }
                is JinjaIfStatement -> known += branchFacts(node, child, rules)
                is JinjaMacro -> if (child is JinjaBody) return known
            }
            child = node
            node = node.parent
        }
        return known
    }

    /** Whether [element] is evaluated only when some other condition holds (see [RawUse.conditional]). */
    private fun isConditional(element: PsiElement): Boolean {
        var child = element
        var node = element.parent
        while (node != null && node !is PsiFile) {
            val conditional = when (node) {
                is JinjaBinaryExpression -> child == node.right &&
                    (node.operatorType == AnsibleJinjaTokenTypes.AND_KEYWORD || node.operatorType == AnsibleJinjaTokenTypes.OR_KEYWORD)
                is JinjaConditionalExpression -> child == node.thenExpression || child == node.elseExpression
                is JinjaIfStatement -> child is JinjaBody || child is JinjaIfTag && child.isElif
                is JinjaForStatement, is JinjaMacro, is JinjaCallBlock -> child is JinjaBody
                else -> false
            }
            if (conditional) return true
            child = node
            node = node.parent
        }
        return false
    }

    /** What reaching [child] (a branch tag or body) of [statement] implies: earlier conditions false, its own true. */
    private fun branchFacts(statement: JinjaIfStatement, child: PsiElement, rules: GuardRules): Set<String> {
        val branches = statement.branches
        val index = branches.indexOfFirst { it.body == child || it.tag == child }
        if (index < 0) return emptySet()
        val facts = HashSet<String>()
        for (earlier in branches.subList(0, index)) facts += JinjaConditions.whenFalse(earlier.condition, rules)
        val branch = branches[index]
        if (branch.body == child) facts += JinjaConditions.whenTrue(branch.condition, rules)
        return facts
    }

    /** The output tag or statement to wrap for a use at [reference]. */
    private fun statementOf(reference: PsiElement): PsiElement? {
        var current: PsiElement? = reference.parent
        while (current != null && current !is PsiFile) {
            when (current) {
                is JinjaOutputTag, is JinjaSingleTagStatement -> return current
                is JinjaTag -> return current.parent as? JinjaBlockStatementElement
            }
            current = current.parent
        }
        return null
    }
}

/**
 * The PSI front end of [ConditionFacts]: a Jinja expression as a [Cond] (only `is [not] defined|undefined` tests and
 * evaluable value terms carry facts, combined through `and`, `or`, `not` and parentheses; an inline `if` proves
 * nothing). [JinjaTokenExpressions] builds the same tree from tokens.
 */
internal object JinjaConditions {
    fun whenTrue(condition: JinjaExpression?, rules: GuardRules): Set<String> = facts(condition, rules).whenTrue

    fun whenFalse(condition: JinjaExpression?, rules: GuardRules): Set<String> = facts(condition, rules).whenFalse

    fun facts(condition: JinjaExpression?, rules: GuardRules): DefinednessFacts =
        if (condition == null) DefinednessFacts.NONE else ConditionFacts.of(cond(condition), rules)

    fun cond(expression: JinjaExpression?): Cond = when (expression) {
        null -> Cond.Opaque
        is JinjaParenthesizedExpression -> cond(expression.expression)
        is JinjaBinaryExpression -> when (expression.operatorType) {
            AnsibleJinjaTokenTypes.AND_KEYWORD -> Cond.And(cond(expression.left), cond(expression.right))
            AnsibleJinjaTokenTypes.OR_KEYWORD -> Cond.Or(cond(expression.left), cond(expression.right))
            else -> Cond.Value(term(expression))
        }
        is JinjaUnaryExpression -> if (expression.isNot) Cond.Not(cond(expression.operand)) else Cond.Value(term(expression))
        is JinjaTestExpr -> definedTest(expression) ?: Cond.Value(term(expression))
        is JinjaConditionalExpression -> Cond.Opaque
        else -> Cond.Value(term(expression))
    }

    private fun term(expression: JinjaExpression?): Term = when (expression) {
        null -> Term.Unknown
        is JinjaParenthesizedExpression -> term(expression.expression)
        is JinjaLiteral -> literal(expression)
        is JinjaListLiteral -> {
            val elements = expression.elements.map { term(it) }
            if (elements.all { it is Term.Literal }) Term.Literal(elements.map { (it as Term.Literal).value }) else Term.Unknown
        }
        is JinjaDictLiteral -> if (expression.entries.isEmpty()) Term.Literal(emptyMap<Any?, Any?>()) else Term.Unknown
        is JinjaVariableReference, is JinjaMemberAccess, is JinjaSubscription -> accessRoot(expression)?.let(Term::Variable) ?: Term.Unknown
        is JinjaFilterCall -> {
            val operand = expression.operand
            if (operand == null) {
                Term.Unknown
            } else {
                val name = expression.filterNameElement?.takeIf { !it.isQualified }?.let { expression.filterName }
                Term.Filter(term(operand), name, expression.arguments.map { term(it) })
            }
        }
        is JinjaCompareExpression -> Term.Compare(expression.operands.map { term(it) }, expression.operators)
        is JinjaTestExpr -> {
            val operand = expression.operand
            if (operand == null || expression.arguments.isNotEmpty()) {
                Term.Unknown
            } else {
                Term.Test(term(operand), expression.testNameElement?.takeIf { !it.isQualified }?.name, expression.isNegated)
            }
        }
        else -> Term.Unknown
    }

    /** A literal as a constant [Term.Literal] (strings decoded; `none` is null), or unknown when it cannot be read. */
    private fun literal(literal: JinjaLiteral): Term = when (literal.kind) {
        JinjaLiteral.Kind.STRING -> literal.stringValue?.let(Term::Literal)
        JinjaLiteral.Kind.INTEGER -> JinjaTokenExpressions.integer(literal.text)?.let(Term::Literal)
        JinjaLiteral.Kind.FLOAT -> JinjaTokenExpressions.float(literal.text)?.let(Term::Literal)
        JinjaLiteral.Kind.BOOLEAN -> literal.booleanValue?.let(Term::Literal)
        JinjaLiteral.Kind.NONE -> Term.Literal(null)
    } ?: Term.Unknown

    private fun definedTest(test: JinjaTestExpr): Cond? {
        val name = test.testNameElement?.takeIf { !it.isQualified }?.name ?: return null
        val positive = when (name) {
            "defined" -> !test.isNegated
            "undefined" -> test.isNegated
            else -> return null
        }
        val root = rootName(test.operand) ?: return Cond.Opaque
        return Cond.Defined(root, positive)
    }

    /** The root of an access chain without calls (`x` of `x.a['b']`), or null. */
    private fun accessRoot(expression: JinjaExpression?): String? {
        var current: JinjaExpression? = expression
        while (true) {
            current = when (current) {
                is JinjaVariableReference -> return current.name
                is JinjaMemberAccess -> current.qualifier
                is JinjaSubscription -> current.qualifier
                else -> return null
            }
        }
    }

    /** The root variable of an accessor chain (`x` of `x.y['z'].w()`), or null when the chain starts elsewhere. */
    fun rootName(expression: JinjaExpression?): String? {
        var current: JinjaExpression? = expression
        while (true) {
            current = when (current) {
                is JinjaVariableReference -> return current.name
                is JinjaMemberAccess -> current.qualifier
                is JinjaSubscription -> current.qualifier
                is JinjaCallExpression -> current.callee
                else -> return null
            }
        }
    }
}
