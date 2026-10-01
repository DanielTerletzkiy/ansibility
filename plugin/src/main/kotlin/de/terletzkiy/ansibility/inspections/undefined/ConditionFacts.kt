package de.terletzkiy.ansibility.inspections.undefined

/**
 * A Jinja condition reduced to what ANS-V003 needs to know about definedness (plan amendment R7/R8, F8.12 (c)). The
 * PSI path ([JinjaConditions]) and the token path ([JinjaTokenExpressions]) both build this tree, so the two cannot
 * disagree about a guard; [ConditionFacts] evaluates it.
 */
internal sealed interface Cond {
    class And(val left: Cond, val right: Cond) : Cond

    class Or(val left: Cond, val right: Cond) : Cond

    class Not(val operand: Cond) : Cond

    /** `x is defined` ([positive]), `x is undefined` or `x is not defined`, for the root name of the tested access. */
    class Defined(val root: String, val positive: Boolean) : Cond

    /** Any other expression, used for its truth value (`x | default(false) | bool`, `x | default([]) | length > 0`). */
    class Value(val term: Term) : Cond

    /** An expression that proves nothing (an inline `if`, a parse the tree does not model). */
    data object Opaque : Cond
}

/** The value part of a condition, as far as [ConditionFacts] can evaluate it. */
internal sealed interface Term {
    /** A constant: `Boolean`, `Long`, `Double`, `String`, null (Jinja `none`), or a `List`/`Map` of constants. */
    class Literal(val value: Any?) : Term

    /** An access chain without calls rooted at the free name [root]: `x`, `x.a`, `x['a'][0]`. */
    class Variable(val root: String) : Term

    /** `operand | name(arguments)`; [name] is null for a qualified filter (`ansible.builtin.bool`), never evaluated. */
    class Filter(val operand: Term, val name: String?, val arguments: List<Term>) : Term

    /** `a == b`, `a > b < c`: [operators] between consecutive [operands]. */
    class Compare(val operands: List<Term>, val operators: List<String>) : Term

    /** `operand is [not] name`; [name] is null for a qualified test. */
    class Test(val operand: Term, val name: String?, val negated: Boolean) : Term

    data object Unknown : Term
}

/** What a condition proves: the root names defined whenever it is true, and whenever it is false. */
internal class DefinednessFacts(val whenTrue: Set<String>, val whenFalse: Set<String>) {
    fun swapped(): DefinednessFacts = DefinednessFacts(whenFalse, whenTrue)

    companion object {
        val NONE: DefinednessFacts = DefinednessFacts(emptySet(), emptySet())
    }
}

/**
 * The definedness facts of a [Cond] (verified on ansible-core 2.18.8 and 2.21.4, `docs/research/host-awareness.md`,
 * "V003 guard semantics"):
 * - `x is defined` is true only when `x` is defined, `x is not defined` only when it is not; `and`, `or` and `not`
 *   combine the facts (`a and b` true: both; false: what both sides prove when false, and so on);
 * - any other term is evaluated **as if `x` were undefined**: when it then has a constant falsy value
 *   (`x | default(false)`, `x | default('') | trim | length > 0`, `x | d([]) | length`), the condition being true proves
 *   `x` defined; a constant truthy value (`x | default(true) | bool`) proves it when the condition is false. A term
 *   that fails on an undefined `x` (`{% if x %}`, `x | length > 0`, `x is not none`), or whose value is not known,
 *   proves nothing: truthiness is no guard.
 *
 * Up to 2.18 any filter but `default`/`d` fails on an undefined operand; from 2.19 the undefined value passes through
 * filters until a `default` ([GuardRules.undefinedPassesFilters]). Only the filters whose result on a constant is fixed
 * are evaluated: `bool`, `length`/`count`, `trim`, `string`, `int`, `float`, `lower`, `upper` and `list`; of the tests
 * `defined`, `undefined`, `none`, `true`, `false` and `string` (`x | default(none) is none`).
 */
internal object ConditionFacts {
    fun of(condition: Cond, rules: GuardRules): DefinednessFacts = when (condition) {
        is Cond.And -> {
            val left = of(condition.left, rules)
            val right = of(condition.right, rules)
            DefinednessFacts(left.whenTrue + right.whenTrue, left.whenFalse intersect right.whenFalse)
        }
        is Cond.Or -> {
            val left = of(condition.left, rules)
            val right = of(condition.right, rules)
            DefinednessFacts(left.whenTrue intersect right.whenTrue, left.whenFalse + right.whenFalse)
        }
        is Cond.Not -> of(condition.operand, rules).swapped()
        is Cond.Defined ->
            if (condition.positive) DefinednessFacts(setOf(condition.root), emptySet()) else DefinednessFacts(emptySet(), setOf(condition.root))
        is Cond.Value -> valueFacts(condition.term, rules)
        Cond.Opaque -> DefinednessFacts.NONE
    }

    private fun valueFacts(term: Term, rules: GuardRules): DefinednessFacts {
        val roots = LinkedHashSet<String>().also { collectRoots(term, it) }
        if (roots.isEmpty()) return DefinednessFacts.NONE
        val whenTrue = HashSet<String>()
        val whenFalse = HashSet<String>()
        for (root in roots) {
            val outcome = evaluate(term, root, rules) as? Outcome.Known ?: continue
            if (truthy(outcome.value)) whenFalse += root else whenTrue += root
        }
        return DefinednessFacts(whenTrue, whenFalse)
    }

    private fun collectRoots(term: Term, into: MutableSet<String>) {
        when (term) {
            is Term.Variable -> into += term.root
            is Term.Filter -> {
                collectRoots(term.operand, into)
                term.arguments.forEach { collectRoots(it, into) }
            }
            is Term.Compare -> term.operands.forEach { collectRoots(it, into) }
            is Term.Test -> collectRoots(term.operand, into)
            is Term.Literal, Term.Unknown -> Unit
        }
    }

    /** The outcome of evaluating [term] with [root] undefined and every other name unknown. */
    private sealed interface Outcome {
        class Known(val value: Any?) : Outcome

        data object Undefined : Outcome

        /** Evaluation raises: the condition cannot be true or false. */
        data object Fails : Outcome

        data object Unknown : Outcome
    }

    private fun evaluate(term: Term, root: String, rules: GuardRules): Outcome = when (term) {
        is Term.Literal -> Outcome.Known(term.value)
        is Term.Variable -> if (term.root == root) Outcome.Undefined else Outcome.Unknown
        is Term.Filter -> filter(term, root, rules)
        is Term.Compare -> compare(term, root, rules)
        is Term.Test -> test(term, root, rules)
        Term.Unknown -> Outcome.Unknown
    }

    /**
     * `defined`/`undefined` on any operand; `none`, `true`, `false` and `string` on a constant. Other tests, and those
     * on an undefined operand (which 2.18 answers and 2.19 rejects), are not modelled.
     */
    private fun test(term: Term.Test, root: String, rules: GuardRules): Outcome {
        val result = when (val operand = evaluate(term.operand, root, rules)) {
            Outcome.Undefined -> when (term.name) {
                "defined" -> false
                "undefined" -> true
                else -> return Outcome.Unknown
            }
            is Outcome.Known -> when (term.name) {
                "defined" -> true
                "undefined" -> false
                "none" -> operand.value == null
                "true" -> operand.value == true
                "false" -> operand.value == false
                "string" -> operand.value is String
                else -> return Outcome.Unknown
            }
            else -> return operand
        }
        return Outcome.Known(result != term.negated)
    }

    private fun filter(term: Term.Filter, root: String, rules: GuardRules): Outcome {
        val operand = evaluate(term.operand, root, rules)
        val name = term.name
        if (name != null && name in GuardRules.DEFAULT_FILTERS) {
            return when (operand) {
                Outcome.Undefined -> term.arguments.firstOrNull()?.let { evaluate(it, root, rules) } ?: Outcome.Known("")
                is Outcome.Known -> {
                    val replacesFalsy = (term.arguments.getOrNull(1) as? Term.Literal)?.value == true
                    if (replacesFalsy && !truthy(operand.value)) term.arguments.first().let { evaluate(it, root, rules) } else operand
                }
                else -> operand
            }
        }
        return when (operand) {
            Outcome.Undefined -> if (rules.undefinedPassesFilters) Outcome.Undefined else Outcome.Fails
            is Outcome.Known -> name?.let { apply(it, operand.value) } ?: Outcome.Unknown
            else -> operand
        }
    }

    private fun compare(term: Term.Compare, root: String, rules: GuardRules): Outcome {
        val values = ArrayList<Any?>()
        var unknown = false
        for (operand in term.operands) {
            when (val outcome = evaluate(operand, root, rules)) {
                is Outcome.Known -> values += outcome.value
                // comparing an undefined value fails on 2.18 and on 2.21
                Outcome.Undefined, Outcome.Fails -> return Outcome.Fails
                Outcome.Unknown -> unknown = true
            }
        }
        if (unknown || values.size != term.operators.size + 1) return Outcome.Unknown
        for ((index, operator) in term.operators.withIndex()) {
            val result = compareValues(values[index], operator, values[index + 1]) ?: return Outcome.Unknown
            if (!result) return Outcome.Known(false)
        }
        return Outcome.Known(true)
    }

    /** Python's `a <operator> b` on constants; null when it is not modelled. */
    private fun compareValues(left: Any?, operator: String, right: Any?): Boolean? {
        val a = number(left)
        val b = number(right)
        return when (operator) {
            "==" -> equal(left, right)
            "!=" -> equal(left, right)?.not()
            "<", "<=", ">", ">=" -> {
                val order = when {
                    a != null && b != null -> a.compareTo(b)
                    left is String && right is String -> left.compareTo(right)
                    else -> return null
                }
                when (operator) {
                    "<" -> order < 0
                    "<=" -> order <= 0
                    ">" -> order > 0
                    else -> order >= 0
                }
            }
            "in", "not in" -> {
                val contained = when (right) {
                    is String -> (left as? String)?.let { right.contains(it) } ?: return null
                    is List<*> -> right.any { equal(left, it) == true }
                    is Map<*, *> -> right.keys.any { equal(left, it) == true }
                    else -> return null
                }
                if (operator == "in") contained else !contained
            }
            else -> null
        }
    }

    private fun equal(left: Any?, right: Any?): Boolean? {
        val a = number(left)
        val b = number(right)
        return when {
            a != null && b != null -> a.compareTo(b) == 0
            left == null || right == null -> left == right
            left is String || right is String -> left == right
            left is List<*> && right is List<*> -> left == right
            left is Map<*, *> && right is Map<*, *> -> left == right
            else -> false
        }
    }

    /** Booleans count as 0 and 1, as in Python. */
    private fun number(value: Any?): Double? = when (value) {
        is Boolean -> if (value) 1.0 else 0.0
        is Long -> value.toDouble()
        is Double -> value
        else -> null
    }

    /** A filter on a constant, or [Outcome.Unknown] for filters and values that are not modelled. */
    private fun apply(name: String, value: Any?): Outcome {
        val result: Any? = when (name) {
            "bool" -> when (value) {
                null -> false
                is Boolean -> value
                is String -> value.lowercase() in TRUE_STRINGS
                is Long -> value == 1L
                is Double -> value == 1.0
                else -> return Outcome.Unknown
            }
            "length", "count" -> when (value) {
                is String -> value.length.toLong()
                is List<*> -> value.size.toLong()
                is Map<*, *> -> value.size.toLong()
                else -> return Outcome.Unknown
            }
            "trim" -> (value as? String)?.trim() ?: return Outcome.Unknown
            "lower" -> (value as? String)?.lowercase() ?: return Outcome.Unknown
            "upper" -> (value as? String)?.uppercase() ?: return Outcome.Unknown
            "string" -> when (value) {
                null -> "None"
                is Boolean -> if (value) "True" else "False"
                is Long, is String -> value.toString()
                else -> return Outcome.Unknown
            }
            "int" -> when (value) {
                null -> 0L
                is Boolean -> if (value) 1L else 0L
                is Long -> value
                is Double -> value.toLong()
                is String -> value.trim().toLongOrNull() ?: value.trim().toDoubleOrNull()?.toLong() ?: 0L
                else -> 0L
            }
            "float" -> when (value) {
                null -> 0.0
                is Boolean -> if (value) 1.0 else 0.0
                is Long -> value.toDouble()
                is Double -> value
                is String -> value.trim().toDoubleOrNull() ?: 0.0
                else -> 0.0
            }
            "list" -> when (value) {
                is String -> value.map { it.toString() }
                is List<*> -> value
                is Map<*, *> -> value.keys.toList()
                else -> return Outcome.Unknown
            }
            else -> return Outcome.Unknown
        }
        return Outcome.Known(result)
    }

    /** Python truthiness of a constant. */
    fun truthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is Long -> value != 0L
        is Double -> value != 0.0
        is String -> value.isNotEmpty()
        is List<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        else -> true
    }

    /** The strings ansible-core's `bool` filter reads as true (lower case). */
    private val TRUE_STRINGS = setOf("yes", "on", "1", "true")
}
