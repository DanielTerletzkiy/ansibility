package de.terletzkiy.ansibility.semantics.typeflow

import de.terletzkiy.ansibility.semantics.value.PyValue

/**
 * A Jinja expression, as `jinja2/parser.py` 3.1 builds it, for the subset the type evaluator needs. Every node knows
 * its source range ([start] inclusive, [end] exclusive) in the template text.
 *
 * Precedence follows Jinja: inline `if` < `or` < `and` < `not` < comparisons < `+`/`-` < `~` < `*`/`/`/`//`/`%` <
 * `**` < unary `-`/`+` < postfix (`.attr`, `[key]`, calls) and filters/tests. So `a ~ b | int` is
 * `a ~ (b | int)`, and a filter after an inline `if` applies to its last operand only.
 */
sealed interface JinjaExpr {
    val start: Int
    val end: Int

    /** A variable reference. */
    data class Name(val name: String, override val start: Int, override val end: Int) : JinjaExpr

    /** A constant: string, integer, float, `true`/`false`, `none`. */
    data class Const(val value: PyValue, override val start: Int, override val end: Int) : JinjaExpr

    /** `[a, b]`. */
    data class ListLiteral(val items: List<JinjaExpr>, override val start: Int, override val end: Int) : JinjaExpr

    /** `(a, b)` or `a, b`. */
    data class TupleLiteral(val items: List<JinjaExpr>, override val start: Int, override val end: Int) : JinjaExpr

    /** `{k: v}`. */
    data class DictLiteral(val entries: List<Pair<JinjaExpr, JinjaExpr>>, override val start: Int, override val end: Int) : JinjaExpr

    /** `target.attribute` (Jinja tries the attribute, then the item). */
    data class Attribute(val target: JinjaExpr, val attribute: String, override val start: Int, override val end: Int) : JinjaExpr

    /** `target[key]` (Jinja tries the item, then the attribute); `.0` is `[0]`. [key] is null for a slice. */
    data class Subscript(val target: JinjaExpr, val key: JinjaExpr?, override val start: Int, override val end: Int) : JinjaExpr

    /** `target | name(args)`; [name] has its dotted segments joined (`ansible.builtin.to_json`). */
    data class Filter(
        val target: JinjaExpr,
        val name: String,
        val args: List<JinjaExpr>,
        val kwargs: Map<String, JinjaExpr>,
        override val start: Int,
        override val end: Int,
    ) : JinjaExpr {
        /** The name without a collection prefix (`ansible.builtin.int` → `int`). */
        val shortName: String get() = name.substringAfterLast('.')
    }

    /** `target is [not] name(args)`, always a bool. */
    data class Test(val target: JinjaExpr, val name: String, val negated: Boolean, override val start: Int, override val end: Int) : JinjaExpr

    /** `callee(args)`. */
    data class Call(
        val callee: JinjaExpr,
        val args: List<JinjaExpr>,
        val kwargs: Map<String, JinjaExpr>,
        override val start: Int,
        override val end: Int,
    ) : JinjaExpr

    /** `then if condition else otherwise`; [otherwise] is null without `else`. */
    data class Conditional(
        val then: JinjaExpr,
        val condition: JinjaExpr,
        val otherwise: JinjaExpr?,
        override val start: Int,
        override val end: Int,
    ) : JinjaExpr

    /** `a ~ b ~ c`, string concatenation. */
    data class Concat(val parts: List<JinjaExpr>, override val start: Int, override val end: Int) : JinjaExpr

    /**
     * A binary operator: arithmetic (`+ - * / // % **`), comparisons (`== != < > <= >=`, `in`, `not in`) and the
     * boolean `and`/`or` (which return an operand).
     */
    data class Binary(val operator: String, val left: JinjaExpr, val right: JinjaExpr, override val start: Int, override val end: Int) : JinjaExpr {
        /** Comparisons always give a bool. */
        val isComparison: Boolean get() = operator in COMPARISONS

        companion object {
            /** Operators whose result is a bool whatever the operands. */
            val COMPARISONS: Set<String> = setOf("==", "!=", "<", ">", "<=", ">=", "in", "not in")
        }
    }

    /** `not x`, `-x`, `+x`. */
    data class Unary(val operator: String, val operand: JinjaExpr, override val start: Int, override val end: Int) : JinjaExpr
}
