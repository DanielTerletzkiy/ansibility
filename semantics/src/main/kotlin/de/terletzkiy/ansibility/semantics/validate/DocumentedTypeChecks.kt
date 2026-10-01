package de.terletzkiy.ansibility.semantics.validate

import de.terletzkiy.ansibility.semantics.coerce.Booleans
import de.terletzkiy.ansibility.semantics.coerce.CheckResult
import de.terletzkiy.ansibility.semantics.coerce.CheckType
import de.terletzkiy.ansibility.semantics.coerce.CoreSemantics
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.semantics.diagnostics.Finding
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.schema.OptionType
import de.terletzkiy.ansibility.semantics.value.PyKey
import de.terletzkiy.ansibility.semantics.value.PyRepr
import de.terletzkiy.ansibility.semantics.value.PyValue
import de.terletzkiy.ansibility.semantics.value.isHashable
import de.terletzkiy.ansibility.semantics.value.pyEquals
import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.semantics.yaml.Yaml11Resolver
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue

/**
 * The documented-type checks of plan A.6: a value whose YAML type differs from the documented type is a finding
 * whether or not ansible-core would accept it, and the message says what ansible-core would do.
 *
 * - T010: a list or dict where `str`/`path` (or `elements: str`/`path`) is documented (stringified);
 * - T011: a YAML int, float, bool or date for `str`/`path` (coerced to its `str()`);
 * - T013: another scalar type for `int`/`float`/`bool` (an int for `float` is exempt: it only widens);
 * - T014: a string or number for `list` (comma split / wrap), a string for `dict` (JSON or `k=v`);
 * - T016: a string for `int`/`float`/`bool`;
 * - T015: `null` for an optional option without a spec default (ansible-core skips it).
 *
 * Opaque values (vault, templates, unloadable scalars) are never judged. Recursion follows `options` and
 * `elements` up to [maxDepth] levels below the top.
 */
internal class DocumentedTypeChecks(
    private val semantics: CoreSemantics,
    private val checkType: CheckType,
    private val kind: SpecKind,
    private val isTemplated: (YScalar) -> Boolean,
    private val maxDepth: Int,
    private val text: FindingText,
) {
    /**
     * Checks one provided [value] against [option] at [path]; [owner] is the top-level option (for the role name),
     * [rejectedPaths] are paths where the port already reports an error (T015 is not added there).
     */
    fun check(
        option: OptionSpec,
        value: YValue,
        path: List<String>,
        owner: OptionSpec,
        rejectedPaths: Set<List<String>>,
        sink: MutableList<Finding>,
        depth: Int = 0,
    ) {
        val py = PyValue.fromYValue(value, isTemplated)
        if (isOpaque(py)) return
        if (py == PyValue.None) {
            if (!option.required && defaultOf(option) == null && path !in rejectedPaths) {
                sink += Finding(
                    DiagnosticCode.T015_NULL_FOR_OPTIONAL,
                    "`null` for optional `${option.type.name}` option `${text.path(path)}` without a spec default" +
                        "${text.owner(owner)}: ${text.core} skips validation and the ${receiver()} receives `None`",
                    value.range,
                    path,
                )
            }
            return
        }
        scalarOrShape(option.type, "`${option.type.name}`", value, py, path, owner, sink, option)
        if (depth >= maxDepth) return
        val elements = option.elements
        if (option.type == OptionType.List && value is YSeq) {
            value.items.forEachIndexed { index, item ->
                val itemPath = path + index.toString()
                val itemPy = PyValue.fromYValue(item, isTemplated)
                if (isOpaque(itemPy) || itemPy == PyValue.None) return@forEachIndexed
                if (elements != null) scalarOrShape(elements, "`elements: ${elements.name}`", item, itemPy, itemPath, owner, sink)
                if (elements == OptionType.Dict && item is YMap) subOptions(option, item, itemPath, owner, rejectedPaths, sink, depth)
            }
        }
        if (option.type == OptionType.Dict && value is YMap) subOptions(option, value, path, owner, rejectedPaths, sink, depth)
    }

    private fun subOptions(
        option: OptionSpec,
        map: YMap,
        path: List<String>,
        owner: OptionSpec,
        rejectedPaths: Set<List<String>>,
        sink: MutableList<Finding>,
        depth: Int,
    ) {
        val options = option.options ?: return
        val entries = map.entries.associateBy { it.key.text }
        for ((name, sub) in options) {
            val key = (sub.aliases.lastOrNull { it in entries } ?: name).takeIf { it in entries } ?: continue
            check(sub, entries.getValue(key).value, path + name, owner, rejectedPaths, sink, depth + 1)
        }
    }

    /** The T010/T011/T013/T014/T016 rules for one value against one documented type. */
    private fun scalarOrShape(
        type: OptionType,
        documented: String,
        value: YValue,
        py: PyValue,
        path: List<String>,
        owner: OptionSpec,
        sink: MutableList<Finding>,
        option: OptionSpec? = null,
    ) {
        val result by lazy { checkType.check(type, py) }
        fun add(code: DiagnosticCode, verb: String, hints: List<String> = emptyList(), note: String = "") {
            val outcome = text.outcome(py, result, verb)
                ?: if (code == DiagnosticCode.T010_SHAPE_CONTRADICTION) {
                    // The shape is certain even when templated leaves make the exact text unknown.
                    "${text.core} would stringify this ${py.typeName} (the exact text depends on templated values)"
                } else {
                    return
                }
            val rescue = option?.let { booleanRescue(it, result) }?.let { ", which `choices` maps to ${text.code(it)}" } ?: ""
            val kept = if (result is CheckResult.Accepted && kind == SpecKind.ROLE) " (the role itself still receives ${text.code(py)})" else ""
            sink += Finding(code, "documented $documented${text.owner(owner)}; $outcome$rescue$kept$note", value.range, path, hints)
        }
        when (type) {
            OptionType.Str, OptionType.Path -> when (py) {
                is PyValue.List, is PyValue.Dict -> add(DiagnosticCode.T010_SHAPE_CONTRADICTION, "stringify")
                is PyValue.Int, is PyValue.Float, is PyValue.Bool, is PyValue.Date ->
                    add(DiagnosticCode.T011_COERCED_SCALAR_TO_STR, "coerce", listOf("quote"), yamlReading(value, py))
                else -> Unit
            }
            OptionType.Int -> when (py) {
                is PyValue.Float, is PyValue.Bool, is PyValue.Date -> add(DiagnosticCode.T013_SCALAR_TYPE_MISMATCH, "coerce", replaceHint(result))
                is PyValue.Str -> add(DiagnosticCode.T016_STRING_FOR_NUMBER_OR_BOOL, "coerce", unquoteHint(value, result))
                else -> Unit
            }
            OptionType.Float -> when (py) {
                is PyValue.Bool, is PyValue.Date -> add(DiagnosticCode.T013_SCALAR_TYPE_MISMATCH, "coerce", replaceHint(result))
                is PyValue.Str -> add(DiagnosticCode.T016_STRING_FOR_NUMBER_OR_BOOL, "coerce", unquoteHint(value, result))
                else -> Unit
            }
            OptionType.Bool -> when (py) {
                is PyValue.Int, is PyValue.Float, is PyValue.Date -> add(DiagnosticCode.T013_SCALAR_TYPE_MISMATCH, "coerce", replaceHint(result))
                is PyValue.Str -> add(
                    DiagnosticCode.T016_STRING_FOR_NUMBER_OR_BOOL, "coerce", unquoteHint(value, result),
                    if (semantics.conditionalsMustBeBool && kind == SpecKind.ROLE) "; `when:` on it fails, conditionals must be bool" else "",
                )
                else -> Unit
            }
            OptionType.List -> when (py) {
                is PyValue.Str -> add(DiagnosticCode.T014_LEGACY_COERCION, "split", listOf("to-sequence"))
                is PyValue.Int, is PyValue.Float, is PyValue.Bool -> add(DiagnosticCode.T014_LEGACY_COERCION, "wrap", listOf("to-sequence"))
                else -> Unit
            }
            OptionType.Dict -> if (py is PyValue.Str) add(DiagnosticCode.T014_LEGACY_COERCION, "parse", listOf("to-mapping"))
            else -> Unit
        }
    }

    /**
     * `_validate_argument_values`' rescue: a coerced `'True'`/`'False'` not among the choices becomes the one
     * choice that is a boolean spelling of the same truth value, if exactly one is.
     */
    private fun booleanRescue(option: OptionSpec, result: CheckResult): PyValue? {
        val coerced = ((result as? CheckResult.Accepted)?.coerced as? PyValue.Str)?.value ?: return null
        val truth = when (coerced) {
            "True" -> true
            "False" -> false
            else -> return null
        }
        val choices = option.choices?.values?.map { PyValue.fromYValue(it) } ?: return null
        if (choices.any { it.pyEquals(PyValue.Str(coerced)) } || choices.any { !it.isHashable }) return null
        val overlap = choices.filter { Booleans.isMember(it, truth) }.distinctBy { PyKey(it) }
        return overlap.singleOrNull()
    }

    /** "(YAML 1.1 reads `3.10` as `3.1`)" when the spelling does not survive loading. */
    private fun yamlReading(value: YValue, py: PyValue): String {
        val scalar = value as? YScalar ?: return ""
        val shown = PyRepr.display(py)
        return if (scalar.style == ScalarStyle.PLAIN && scalar.sourceText != shown) {
            "; YAML 1.1 reads `${scalar.sourceText}` as ${text.code(py)}"
        } else {
            ""
        }
    }

    /** `replace=<literal>` for an accepted scalar coercion (`1` → `true`, `42.0` → `42`). */
    private fun replaceHint(result: CheckResult): List<String> {
        val coerced = (result as? CheckResult.Accepted)?.coerced ?: return emptyList()
        return when (coerced) {
            is PyValue.Bool -> listOf("replace=${if (coerced.value) "true" else "false"}")
            is PyValue.Int -> listOf("replace=${coerced.value}")
            is PyValue.Float -> listOf("replace=${PyRepr.floatRepr(coerced.value)}")
            else -> emptyList()
        }
    }

    /** `unquote` when the plain spelling loads as exactly the value ansible-core would coerce to. */
    private fun unquoteHint(value: YValue, result: CheckResult): List<String> {
        val scalar = value as? YScalar ?: return emptyList()
        val coerced = (result as? CheckResult.Accepted)?.coerced ?: return emptyList()
        if (scalar.style == ScalarStyle.PLAIN) return emptyList()
        val plain = when (val r = Yaml11Resolver.resolvePlain(scalar.text)) {
            is Resolved.Bool -> PyValue.Bool(r.value)
            is Resolved.Int -> PyValue.Int(r.value)
            is Resolved.Float -> PyValue.Float(r.value)
            else -> return emptyList()
        }
        return if (plain.typeName == coerced.typeName && plain.pyEquals(coerced)) listOf("unquote") else emptyList()
    }

    private fun receiver() = if (kind == SpecKind.ROLE) "role" else "module"

    private fun isOpaque(value: PyValue) = value == PyValue.Vault || value is PyValue.Templated || value == PyValue.Unloadable

    private fun defaultOf(option: OptionSpec): PyValue? =
        option.default?.let { PyValue.fromYValue(it) }?.takeUnless { it == PyValue.None }
}
