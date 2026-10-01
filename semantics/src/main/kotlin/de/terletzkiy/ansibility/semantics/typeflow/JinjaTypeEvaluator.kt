package de.terletzkiy.ansibility.semantics.typeflow

import de.terletzkiy.ansibility.semantics.typeflow.TemplatingRules.Mode
import de.terletzkiy.ansibility.semantics.validate.SpecValidator
import de.terletzkiy.ansibility.semantics.value.PyRepr
import de.terletzkiy.ansibility.semantics.value.PyValue
import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.YEmpty
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.semantics.yaml.YVault

/**
 * One reachable definition of a variable, as a [VariableResolver] reads it.
 *
 * @property label where it is written, for messages (`defaults/main.yml:12`)
 * @property value the value as written, or null when it is assigned at runtime (`register`, `set_fact` free-form,
 *   loop variables, prompts) or cannot be read: the variable's type is then unknown
 * @property secret the value must never be shown (vault files, `vault_*` names): its type still counts, its text
 *   never reaches a message
 */
class VariableDefinition(val label: String, val value: YValue?, val secret: Boolean = false)

/** Where the evaluator finds the definitions a `{{ name }}` chain follows. */
fun interface VariableResolver {
    /**
     * Every reachable definition of [name] (in the plugin: all definitions in the root, spec declarations excluded),
     * or null when the name cannot be followed (not defined in the root, a fact, a magic variable).
     */
    fun definitions(name: String): List<VariableDefinition>?
}

/**
 * The types of one template (plan A.5):
 *
 * @property logical what the author means: a bare `{{ name }}` has its definitions' types; a trailing typed filter
 *   its declared type; a multi-node template is `str`; vault values are [AValue.VAULT]; anything else is unknown
 * @property runtime what the target ansible-core hands to the argument-spec validation ([TemplatingRules])
 * @property origin why the logical type is what it is (the messages name it)
 */
class TemplateTypes(val template: JinjaTemplate, val logical: AValue, val runtime: AValue, val origin: TypeOrigin) {
    override fun toString(): String = "TemplateTypes(logical=$logical, runtime=$runtime, origin=$origin)"
}

/**
 * The `JinjaTypeEvaluator` of plan A.5: computes the [TemplateTypes] of templated values, following bare variable
 * references through every definition a [VariableResolver] returns (cycle guard, at most [maxDepth] hops), on the
 * expression model of [JinjaTemplate]/[JinjaExpr].
 *
 * Logical types:
 * - a variable has the join of its definitions' types (a literal's YAML type, a container's shape, a template's own
 *   logical type); `vault_*` names and `!vault` values are vault;
 * - filters: `int`, `float`, `bool`; `string`, `lower`, `upper`, `trim`, `join`, `to_json`, `to_nice_json`,
 *   `to_yaml`, `to_nice_yaml`, `b64encode`, `b64decode` → `str`; `length`, `count` → `int`; `list`, `dict2items`
 *   → `list`; `items2dict` → `dict`; `default(x)`/`d(x)` → the join of the value and `x`;
 * - literals have their type (a tuple is [BaseType.OTHER], which 2.19+ hands over as a list); `~` gives `str`;
 *   comparisons, tests and `not` give `bool`; an inline `if` and `and`/`or` join their operands; constant attribute
 *   and item access reads known literal containers;
 * - multi-node templates are `str`; templates with statements, calls and arithmetic are unknown.
 *
 * Runtime types follow [TemplatingRules.Mode] (see there). Results are memoised per variable name for the lifetime
 * of the evaluator, so one instance serves one consistent snapshot (one inspection pass).
 *
 * Not thread-safe.
 */
class JinjaTypeEvaluator(
    val rules: TemplatingRules,
    private val tokenizer: JinjaTokenizer,
    private val resolver: VariableResolver,
    val maxDepth: Int = DEFAULT_MAX_DEPTH,
    /** Called once per evaluated template or definition, e.g. `ProgressManager::checkCanceled`. */
    private val checkCanceled: () -> Unit = {},
) {
    private val templates = HashMap<String, JinjaTemplate>()
    private val names = HashMap<String, NameResult>()
    private val visiting = HashSet<String>()

    /** The types of [text], a Jinja-bearing scalar's loaded value. */
    fun evaluate(text: String): TemplateTypes = evaluate(template(text))

    /** The types of [template]. */
    fun evaluate(template: JinjaTemplate): TemplateTypes = templateTypes(template, 0).types

    /** [text] parsed (memoised). */
    fun template(text: String): JinjaTemplate = templates.getOrPut(text) { JinjaTemplate.parse(text, tokenizer) }

    // ------------------------------------------------------------------------------------------------ results

    /** A value's logical type and its runtime value before output concatenation. */
    private class Eval(val logical: AValue, val runtime: AValue, val origin: TypeOrigin, val limited: Boolean = false)

    /** A template's types; [limited] when a cycle or the depth limit cut the evaluation (not memoised then). */
    private class Evaluated(val types: TemplateTypes, val limited: Boolean)

    /**
     * Everything about one variable: [logical]; [lookup], what a reference inside an expression receives; [bare], what
     * a template that is exactly `{{ name }}` renders to; the [trace] for messages.
     */
    private class NameResult(val logical: AValue, val lookup: AValue, val bare: AValue, val trace: ChainTrace, val limited: Boolean)

    private class DefinitionResult(val logical: AValue, val lookup: AValue, val bare: AValue, val trace: DefinitionTrace, val limited: Boolean)

    // ------------------------------------------------------------------------------------------------ templates

    private fun templateTypes(template: JinjaTemplate, depth: Int): Evaluated {
        checkCanceled()
        return when (template.shape) {
            TemplateShape.MALFORMED, TemplateShape.STATEMENTS ->
                Evaluated(TemplateTypes(template, AValue.UNKNOWN, AValue.UNKNOWN, TypeOrigin.Other), false)
            TemplateShape.TEXT_ONLY -> {
                val text = PyValue.Str(template.nodes.filterIsInstance<TemplateNode.Text>().joinToString("") { it.text })
                val runtime = when (rules.mode) {
                    Mode.CLASSIC -> classicConcat(AValue.known(text))
                    Mode.LEGACY_NATIVE -> AValue.UNKNOWN
                    Mode.NATIVE -> AValue.known(text)
                }
                Evaluated(TemplateTypes(template, AValue.known(text), runtime, TypeOrigin.Literal), false)
            }
            TemplateShape.SINGLE_EXPRESSION -> {
                val expression = template.singleExpression ?: return unknownTemplate(template)
                val bare = template.bareName
                if (bare != null) {
                    val name = name(bare, depth + 1)
                    val runtime = when (rules.mode) {
                        Mode.NATIVE -> name.lookup
                        Mode.CLASSIC, Mode.LEGACY_NATIVE -> name.bare
                    }
                    Evaluated(TemplateTypes(template, name.logical, runtime, TypeOrigin.Chain(name.trace)), name.limited)
                } else {
                    val value = expression(expression, depth)
                    val runtime = when (rules.mode) {
                        Mode.CLASSIC -> classicConcat(value.runtime)
                        Mode.LEGACY_NATIVE -> legacyNativeConcat(value.runtime)
                        Mode.NATIVE -> value.runtime
                    }
                    Evaluated(TemplateTypes(template, value.logical, runtime, value.origin), value.limited)
                }
            }
            TemplateShape.MULTI_NODE -> {
                val runtime = when (rules.mode) {
                    Mode.CLASSIC -> if (multiNodeMayLookLiteral(template, depth)) LITERAL_EVAL_RESULT else RENDERED_STR
                    Mode.LEGACY_NATIVE -> AValue.UNKNOWN
                    Mode.NATIVE -> RENDERED_STR
                }
                Evaluated(TemplateTypes(template, RENDERED_STR, runtime, TypeOrigin.MultiNode), false)
            }
        }
    }

    private fun unknownTemplate(template: JinjaTemplate) =
        Evaluated(TemplateTypes(template, AValue.UNKNOWN, AValue.UNKNOWN, TypeOrigin.Other), false)

    /**
     * Whether the joined text of a multi-node template might start with `[`/`{` or be exactly `True`/`False`, so
     * that ansible-core ≤ 2.18 `literal_eval`s it.
     */
    private fun multiNodeMayLookLiteral(template: JinjaTemplate, depth: Int): Boolean {
        val values = template.outputs.map { output -> output.expression?.let { expression(it, depth).runtime } ?: AValue.UNKNOWN }
        val literalText = template.nodes.filterIsInstance<TemplateNode.Text>().joinToString("") { it.text }
        // `True`/`False` needs letters from some expression: numbers, dates, lists and dicts never render them.
        val mayBeTrueOrFalse = literalText.length <= "False".length && literalText.all { it in "TrueFals" } &&
            values.any { it.unknown || it.vault || it.types.any { type -> type == BaseType.STR || type == BaseType.BOOL || type == BaseType.OTHER } }
        if (mayBeTrueOrFalse) return true
        return when (val first = template.nodes.first()) {
            is TemplateNode.Text -> first.text.startsWith("[") || first.text.startsWith("{")
            is TemplateNode.Output -> renderedMayStartWithBracket(values.first())
            is TemplateNode.Statement -> true
        }
    }

    /** Whether `str(value)` might start with `[` or `{` (or be empty, so that the next node decides). */
    private fun renderedMayStartWithBracket(value: AValue): Boolean {
        if (value.unknown || value.vault) return true
        value.known?.let { known ->
            val text = if (known == PyValue.None) "" else PyRepr.str(known)
            return text.isEmpty() || text.startsWith("[") || text.startsWith("{")
        }
        return value.types.any { it == BaseType.STR || it == BaseType.LIST || it == BaseType.DICT || it == BaseType.OTHER || it == BaseType.NONE }
    }

    // ------------------------------------------------------------------------------------------------ concatenation

    /**
     * `ansible_eval_concat` over one node (≤ 2.18 without `jinja2_native`): `str(value)` (None renders as `''`), then
     * `literal_eval` when the text starts with `[`/`{` or is `True`/`False`. The result's strings never look literal
     * again (they already went through this step).
     */
    private fun classicConcat(value: AValue): AValue {
        if (value.unknown) return AValue.UNKNOWN
        if (value.vault) return AValue.VAULT
        value.known?.let { return classicConcatKnown(it) }
        var out = AValue.NOTHING
        for (type in value.types) {
            out = out.join(
                when (type) {
                    BaseType.NONE -> AValue.known(PyValue.Str(""))
                    BaseType.BOOL -> AValue.of(BaseType.BOOL)
                    BaseType.INT, BaseType.FLOAT, BaseType.DATE -> RENDERED_STR
                    BaseType.STR -> if (value.strMayLookLiteral) LITERAL_EVAL_RESULT else RENDERED_STR
                    BaseType.LIST, BaseType.DICT -> if (value.containerReprUnsafe) {
                        AValue.of(type, BaseType.STR, strMayLookLiteral = false, containerReprUnsafe = false)
                    } else {
                        AValue.of(type, containerReprUnsafe = false)
                    }
                    // A tuple renders as `(…)` (a string); a set as `{…}`, which literal_evals back to a set.
                    BaseType.OTHER -> AValue.of(BaseType.STR, BaseType.OTHER, strMayLookLiteral = false)
                },
            )
        }
        return out
    }

    private fun classicConcatKnown(value: PyValue): AValue = when (value) {
        is PyValue.Str -> when {
            value.value == "True" -> AValue.known(PyValue.Bool(true))
            value.value == "False" -> AValue.known(PyValue.Bool(false))
            value.value.startsWith("[") || value.value.startsWith("{") -> LITERAL_EVAL_RESULT
            else -> AValue.known(value)
        }
        PyValue.None -> AValue.known(PyValue.Str(""))
        is PyValue.Bool -> AValue.known(value)
        is PyValue.Int, is PyValue.Float, is PyValue.Date -> AValue.known(PyValue.Str(PyRepr.str(value)))
        is PyValue.List, is PyValue.Dict -> if (AValue.reprRoundTrips(value)) AValue.known(value) else AValue.known(PyValue.Str(PyRepr.repr(value)))
        PyValue.Vault -> AValue.VAULT
        is PyValue.Templated, PyValue.Unloadable -> AValue.UNKNOWN
    }

    /** `ansible_native_concat` over one node (≤ 2.18 with `jinja2_native`): strings are `literal_eval`'d into anything. */
    private fun legacyNativeConcat(value: AValue): AValue =
        if (value.unknown || value.vault || BaseType.STR in value.types) AValue.UNKNOWN else value

    // ------------------------------------------------------------------------------------------------ variables

    private fun name(name: String, depth: Int): NameResult {
        if (name.startsWith(VAULT_PREFIX)) return NameResult(AValue.VAULT, AValue.VAULT, AValue.VAULT, ChainTrace(name, emptyList()), false)
        names[name]?.let { return it }
        if (depth > maxDepth || name in visiting) return unknownName(name, limited = true)
        val definitions = resolver.definitions(name)
        if (definitions.isNullOrEmpty()) return unknownName(name, limited = false).also { names[name] = it }
        visiting += name
        val result = try {
            var logical = AValue.NOTHING
            var lookup = AValue.NOTHING
            var bare = AValue.NOTHING
            var limited = false
            val traces = ArrayList<DefinitionTrace>(definitions.size)
            for (definition in definitions) {
                val result = definition(definition, depth)
                logical = logical.join(result.logical)
                lookup = lookup.join(result.lookup)
                bare = bare.join(result.bare)
                limited = limited || result.limited
                traces += result.trace
            }
            NameResult(logical, lookup, bare, ChainTrace(name, traces), limited)
        } finally {
            visiting -= name
        }
        if (!result.limited) names[name] = result
        return result
    }

    private fun unknownName(name: String, limited: Boolean) =
        NameResult(AValue.UNKNOWN, AValue.UNKNOWN, AValue.UNKNOWN, ChainTrace(name, emptyList()), limited)

    private fun definition(definition: VariableDefinition, depth: Int): DefinitionResult {
        checkCanceled()
        val value = definition.value
            ?: return DefinitionResult(AValue.UNKNOWN, AValue.UNKNOWN, AValue.UNKNOWN, DefinitionTrace(definition.label, emptySet(), null, null), false)
        fun result(logical: AValue, lookup: AValue, bare: AValue, shown: String?, next: ChainTrace? = null, limited: Boolean = false) =
            DefinitionResult(
                if (definition.secret) logical.withoutKnown() else logical,
                if (definition.secret) lookup.withoutKnown() else lookup,
                if (definition.secret) bare.withoutKnown() else bare,
                DefinitionTrace(definition.label, logical.types, shown.takeUnless { definition.secret }, next),
                limited,
            )
        return when (value) {
            is YVault -> result(AValue.VAULT, AValue.VAULT, AValue.VAULT, null)
            is YEmpty -> AValue.known(PyValue.None).let { result(it, it, it, "null") }
            is YScalar -> if (isTemplated(value)) {
                val inner = templateTypes(template(value.text), depth)
                val types = inner.types
                val bare = when (rules.mode) {
                    Mode.CLASSIC -> classicConcat(types.runtime)
                    Mode.LEGACY_NATIVE -> legacyNativeConcat(types.runtime)
                    Mode.NATIVE -> types.runtime
                }
                val next = (types.origin as? TypeOrigin.Chain)?.takeIf { it.accessors.isEmpty() && types.template.bareName != null }?.chain
                result(types.logical, types.runtime, bare, preview(value.text), next, inner.limited)
            } else {
                val literal = PyValue.fromYValue(value)
                result(AValue.known(literal), AValue.known(literal), bareLiteral(literal), PyRepr.display(literal, PREVIEW_LIMIT))
            }
            is YSeq, is YMap -> container(value, depth, definition)
        }
    }

    /** What a template that is exactly `{{ name }}` renders to when the variable holds the literal [value]. */
    private fun bareLiteral(value: PyValue): AValue = when (rules.mode) {
        Mode.NATIVE -> AValue.known(value)
        // `Templar.template` returns bool, int, float and None values directly (NON_TEMPLATED_TYPES).
        Mode.CLASSIC -> when (value) {
            PyValue.None, is PyValue.Bool, is PyValue.Int, is PyValue.Float -> AValue.known(value)
            else -> classicConcat(AValue.known(value))
        }
        Mode.LEGACY_NATIVE -> when (value) {
            is PyValue.Str -> AValue.UNKNOWN
            else -> AValue.known(value)
        }
    }

    private fun container(value: YValue, depth: Int, definition: VariableDefinition): DefinitionResult {
        val type = if (value is YSeq) BaseType.LIST else BaseType.DICT
        val literal = PyValue.fromYValue(value, ::isTemplated)
        if (!containsTemplated(literal)) {
            val known = AValue.known(literal)
            return DefinitionResult(
                if (definition.secret) known.withoutKnown() else known,
                if (definition.secret) known.withoutKnown() else known,
                bareLiteral(literal).let { if (definition.secret) it.withoutKnown() else it },
                DefinitionTrace(definition.label, setOf(type), null, null),
                false,
            )
        }
        val logical = AValue.of(type, containerReprUnsafe = false)
        val (lookup, limited) = when (rules.mode) {
            Mode.CLASSIC -> {
                val budget = intArrayOf(MAX_EVALUATED_LEAVES)
                var cut = false
                val safe = leavesRoundTrip(value, depth, budget) { cut = true }
                AValue.of(type, containerReprUnsafe = !safe) to cut
            }
            Mode.LEGACY_NATIVE, Mode.NATIVE -> AValue.of(type, containerReprUnsafe = false) to false
        }
        val bare = if (rules.mode == Mode.CLASSIC) classicConcat(lookup) else lookup
        return DefinitionResult(logical, lookup, bare, DefinitionTrace(definition.label, setOf(type), null, null), limited)
    }

    /**
     * Whether `repr()` of the rendered container [value] `literal_eval`s back: every literal leaf round-trips and every
     * templated leaf renders to a value that does. At most [budget] templated leaves are evaluated; beyond that, and
     * when a leaf's evaluation was cut ([onLimited]), the answer is "no".
     */
    private fun leavesRoundTrip(value: YValue, depth: Int, budget: IntArray, onLimited: () -> Unit): Boolean = when (value) {
        is YVault, is YEmpty -> true
        is YScalar -> if (isTemplated(value)) {
            if (--budget[0] < 0) {
                false
            } else {
                val evaluated = templateTypes(template(value.text), depth)
                if (evaluated.limited) onLimited()
                reprSafe(evaluated.types.runtime)
            }
        } else {
            AValue.reprRoundTrips(PyValue.fromYValue(value))
        }
        is YSeq -> value.items.all { leavesRoundTrip(it, depth, budget, onLimited) }
        is YMap -> value.entries.all { AValue.reprRoundTrips(PyValue.fromYValue(it.key)) && leavesRoundTrip(it.value, depth, budget, onLimited) }
    }

    /** Whether every value [value] allows has a `repr()` that `literal_eval` reads back. */
    private fun reprSafe(value: AValue): Boolean {
        if (value.unknown) return false
        if (value.vault) return true
        value.known?.let { return AValue.reprRoundTrips(it) }
        return value.types.all {
            when (it) {
                BaseType.NONE, BaseType.BOOL, BaseType.INT, BaseType.STR -> true
                BaseType.LIST, BaseType.DICT -> !value.containerReprUnsafe
                BaseType.FLOAT, BaseType.DATE, BaseType.OTHER -> false
            }
        }
    }

    private fun containsTemplated(value: PyValue): Boolean = when (value) {
        is PyValue.Templated -> true
        is PyValue.List -> value.items.any(::containsTemplated)
        is PyValue.Dict -> value.entries.any { containsTemplated(it.second) }
        else -> false
    }

    // ------------------------------------------------------------------------------------------------ expressions

    private fun expression(expr: JinjaExpr, depth: Int): Eval {
        checkCanceled()
        return when (expr) {
            is JinjaExpr.Name -> name(expr.name, depth + 1).let { Eval(it.logical, it.lookup, TypeOrigin.Chain(it.trace), it.limited) }
            is JinjaExpr.Const -> AValue.known(expr.value).let { Eval(it, it, TypeOrigin.Literal) }
            is JinjaExpr.ListLiteral -> collection(BaseType.LIST, expr.items, depth) { PyValue.List(it) }
            is JinjaExpr.DictLiteral -> dictLiteral(expr, depth)
            // 2.19+ hands a tuple over as a list (measured with 2.21.4: `{{ (1, 2) }}` is `[1, 2]`); ≤ 2.18 keeps the tuple.
            is JinjaExpr.TupleLiteral -> Eval(
                AValue.of(BaseType.OTHER),
                AValue.of(if (rules.mode == Mode.NATIVE) BaseType.LIST else BaseType.OTHER),
                TypeOrigin.Literal,
            )
            is JinjaExpr.Attribute -> access(expr.target, depth, attribute = expr.attribute, key = null)
            is JinjaExpr.Subscript -> {
                val key = expr.key?.let(::constant)
                if (key == null) unknown() else access(expr.target, depth, attribute = null, key = key)
            }
            is JinjaExpr.Filter -> filter(expr, depth)
            is JinjaExpr.Test -> BOOL.let { Eval(it, it, TypeOrigin.Test) }
            is JinjaExpr.Call -> unknown()
            is JinjaExpr.Conditional -> {
                val otherwise = expr.otherwise ?: return unknown()
                val then = expression(expr.then, depth)
                val other = expression(otherwise, depth)
                Eval(then.logical.join(other.logical), then.runtime.join(other.runtime), TypeOrigin.Conditional, then.limited || other.limited)
            }
            is JinjaExpr.Concat -> concat(expr, depth)
            is JinjaExpr.Binary -> when {
                expr.isComparison -> BOOL.let { Eval(it, it, TypeOrigin.Test) }
                // `a or b` / `a and b` return one of their operands.
                expr.operator == "and" || expr.operator == "or" -> {
                    val left = expression(expr.left, depth)
                    val right = expression(expr.right, depth)
                    Eval(left.logical.join(right.logical), left.runtime.join(right.runtime), TypeOrigin.Other, left.limited || right.limited)
                }
                else -> unknown()
            }
            is JinjaExpr.Unary -> if (expr.operator == "not") BOOL.let { Eval(it, it, TypeOrigin.Test) } else unknown()
        }
    }

    private fun unknown() = Eval(AValue.UNKNOWN, AValue.UNKNOWN, TypeOrigin.Other)

    /** The value of a constant key, folding a negated number as Jinja's optimizer does (`x[-1]`). */
    private fun constant(expr: JinjaExpr): PyValue? = when (expr) {
        is JinjaExpr.Const -> expr.value
        is JinjaExpr.Unary -> when (val operand = (expr.operand as? JinjaExpr.Const)?.value) {
            is PyValue.Int -> if (expr.operator == "-") PyValue.Int(operand.value.negate()) else operand.takeIf { expr.operator == "+" }
            else -> null
        }
        else -> null
    }

    private fun collection(type: BaseType, items: List<JinjaExpr>, depth: Int, build: (List<PyValue>) -> PyValue): Eval {
        val values = items.map { expression(it, depth) }
        val limited = values.any { it.limited }
        val logicalKnown = values.map { it.logical.known }
        val runtimeKnown = values.map { it.runtime.known }
        val logical = if (logicalKnown.all { it != null }) AValue.known(build(logicalKnown.map { it!! })) else AValue.of(type, containerReprUnsafe = false)
        val runtime = if (runtimeKnown.all { it != null }) {
            AValue.known(build(runtimeKnown.map { it!! }))
        } else {
            AValue.of(type, containerReprUnsafe = values.any { !reprSafe(it.runtime) })
        }
        return Eval(logical, runtime, TypeOrigin.Literal, limited)
    }

    private fun dictLiteral(expr: JinjaExpr.DictLiteral, depth: Int): Eval {
        val keys = expr.entries.map { (it.first as? JinjaExpr.Const)?.value }
        if (keys.any { it == null }) {
            val values = expr.entries.map { expression(it.second, depth) }
            return Eval(
                AValue.of(BaseType.DICT, containerReprUnsafe = false),
                AValue.of(BaseType.DICT, containerReprUnsafe = values.any { !reprSafe(it.runtime) }),
                TypeOrigin.Literal,
                values.any { it.limited },
            )
        }
        return collection(BaseType.DICT, expr.entries.map { it.second }, depth) { values -> PyValue.Dict.of(keys.map { it!! }.zip(values)) }
    }

    /**
     * Constant attribute or item access (`x.port`, `x['port']`, `x[0]`, `x.0`) on a value whose literal is known in
     * every definition. Jinja's `x.attr` tries the Python attribute first, so dict and list method names stay unknown.
     */
    private fun access(targetExpr: JinjaExpr, depth: Int, attribute: String?, key: PyValue?): Eval {
        val target = expression(targetExpr, depth)
        val origin = (target.origin as? TypeOrigin.Chain)?.let { chain ->
            TypeOrigin.Chain(chain.chain, chain.accessors + (attribute ?: key?.let { PyRepr.display(it) }.orEmpty()))
        } ?: TypeOrigin.Other
        val logical = element(target.logical.known, attribute, key) ?: return Eval(AValue.UNKNOWN, AValue.UNKNOWN, origin, target.limited)
        val runtime = element(target.runtime.known, attribute, key) ?: return Eval(AValue.UNKNOWN, AValue.UNKNOWN, origin, target.limited)
        return Eval(AValue.known(logical), AValue.known(runtime), origin, target.limited)
    }

    private fun element(container: PyValue?, attribute: String?, key: PyValue?): PyValue? {
        val element = when (container) {
            is PyValue.Dict -> when {
                attribute != null -> if (attribute in DICT_ATTRIBUTES) null else container[attribute]
                key != null -> container[key]
                else -> null
            }
            is PyValue.List -> {
                val index = (key as? PyValue.Int)?.value?.toInt() ?: return null
                container.items.getOrNull(if (index < 0) container.items.size + index else index)
            }
            else -> null
        }
        return element?.takeUnless { containsTemplated(it) }
    }

    private fun filter(expr: JinjaExpr.Filter, depth: Int): Eval {
        val prefix = expr.name.substringBeforeLast('.', "")
        if (prefix.isNotEmpty() && prefix !in BUILTIN_COLLECTIONS) return unknown()
        val origin = TypeOrigin.Filter(expr.shortName)
        val typed = TYPED_FILTERS[expr.shortName]
        if (typed != null) return Eval(typed, typed, origin)
        return when (expr.shortName) {
            "default", "d" -> {
                val target = expression(expr.target, depth)
                val fallback = expr.args.firstOrNull() ?: expr.kwargs["default_value"]
                val other = fallback?.let { expression(it, depth) } ?: Eval(AValue.known(PyValue.Str("")), AValue.known(PyValue.Str("")), TypeOrigin.Literal)
                Eval(target.logical.join(other.logical), target.runtime.join(other.runtime), origin, target.limited || other.limited)
            }
            else -> unknown()
        }
    }

    /** `a ~ b`: a string; it may look literal unless a known first part shows it cannot. */
    private fun concat(expr: JinjaExpr.Concat, depth: Int): Eval {
        val first = expression(expr.parts.first(), depth).runtime.known
        val prefix = when (first) {
            null -> null
            PyValue.None -> "None"
            else -> PyRepr.str(first)
        }
        val mayLookLiteral = prefix == null || prefix.isEmpty() || prefix.startsWith("[") || prefix.startsWith("{") ||
            "True".startsWith(prefix) || "False".startsWith(prefix)
        val value = AValue.of(BaseType.STR, strMayLookLiteral = mayLookLiteral)
        return Eval(value, value, TypeOrigin.Concat)
    }

    private fun isTemplated(scalar: YScalar): Boolean = scalar.resolved is Resolved.Str && SpecValidator.containsJinja(scalar)

    private fun preview(text: String): String = if (text.length <= PREVIEW_LIMIT) text else text.take(PREVIEW_LIMIT - 1) + "…"

    companion object {
        /** The default for the root setting "type chain depth" (plan A.5). */
        const val DEFAULT_MAX_DEPTH: Int = 8

        /** At most this many templated leaves of one container are evaluated to decide whether its repr round-trips. */
        const val MAX_EVALUATED_LEAVES: Int = 64

        private const val PREVIEW_LIMIT = 40
        private const val VAULT_PREFIX = "vault_"

        private val BOOL = AValue.of(BaseType.BOOL)

        /** A string that went through rendering: ≤ 2.18 already `literal_eval`'d it, so it never looks literal again. */
        private val RENDERED_STR = AValue.of(BaseType.STR, strMayLookLiteral = false)

        /** What `literal_eval` may make of a string that looks literal (a set or tuple is [BaseType.OTHER]). */
        private val LITERAL_EVAL_RESULT = AValue.of(
            BaseType.STR, BaseType.LIST, BaseType.DICT, BaseType.BOOL, BaseType.OTHER, strMayLookLiteral = false, containerReprUnsafe = false,
        )

        /** Collections whose filters are the builtins below (Jinja's own filters have no collection). */
        private val BUILTIN_COLLECTIONS = setOf("ansible.builtin", "ansible.legacy")

        /** Python attributes of `dict` that Jinja's `x.attr` finds before the key. */
        private val DICT_ATTRIBUTES = setOf("clear", "copy", "fromkeys", "get", "items", "keys", "pop", "popitem", "setdefault", "update", "values")

        /**
         * Filters with a declared result type (plan A.5). Strings of unknown text may look literal, except
         * `NativeJinjaText` results (`string`, `to_json`, `to_nice_json`, `to_yaml`, `to_nice_yaml`) and base64 text.
         */
        private val TYPED_FILTERS: Map<String, AValue> = buildMap {
            put("int", AValue.of(BaseType.INT))
            put("float", AValue.of(BaseType.FLOAT))
            put("bool", AValue.of(BaseType.BOOL))
            put("length", AValue.of(BaseType.INT))
            put("count", AValue.of(BaseType.INT))
            for (name in listOf("lower", "upper", "trim", "join", "b64decode")) put(name, AValue.of(BaseType.STR, strMayLookLiteral = true))
            for (name in listOf("string", "to_json", "to_nice_json", "to_yaml", "to_nice_yaml", "b64encode")) {
                put(name, AValue.of(BaseType.STR, strMayLookLiteral = false))
            }
            put("list", AValue.of(BaseType.LIST, containerReprUnsafe = true))
            put("dict2items", AValue.of(BaseType.LIST, containerReprUnsafe = true))
            put("items2dict", AValue.of(BaseType.DICT, containerReprUnsafe = true))
        }

        /** The filter names [JinjaTypeEvaluator] types. */
        val TYPED_FILTER_NAMES: Set<String> get() = TYPED_FILTERS.keys + setOf("default", "d")
    }
}
