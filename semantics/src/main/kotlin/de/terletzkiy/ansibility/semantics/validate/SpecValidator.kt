package de.terletzkiy.ansibility.semantics.validate

import de.terletzkiy.ansibility.semantics.coerce.CheckType
import de.terletzkiy.ansibility.semantics.coerce.CoreSemantics
import de.terletzkiy.ansibility.semantics.coerce.PathEnvironment
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.semantics.diagnostics.Finding
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.value.PyRepr
import de.terletzkiy.ansibility.semantics.value.PyValue
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.semantics.yaml.SourceRange
import de.terletzkiy.ansibility.semantics.yaml.YEntry
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue

/** Which kind of spec is checked; it decides how strict the top level is (plan R3). */
enum class SpecKind {
    /**
     * A role entry point (`meta/argument_specs.yml`). ansible-core validates only the spec's own names, taken
     * from the merged variables: unknown top-level names are never flagged, and a missing top-level required
     * option is not decidable from one file. Nested keys are checked strictly.
     */
    ROLE,

    /** Module options: unknown top-level options are ANS-M001, missing required ones ANS-M002. */
    MODULE,
}

/**
 * The findings for one checked value set.
 *
 * @property findings every finding: the port's rejections (T001–T005, T002/T003 nested, M001/M002 for modules)
 *   and the documented-type findings (T010–T016), which are reported independently of acceptance
 * @property result the faithful port result for the same values (coerced values, error classes)
 */
data class SpecValidation(val findings: List<Finding>, val result: ArgSpecResult) {
    /**
     * [findings] with one finding per rejected value: where T001 or T005 reports a rejection, the documented-type
     * findings and T004 for the same path are dropped (their message would repeat the rejection).
     */
    val primary: List<Finding>
        get() {
            val rejected = findings.filter { it.code in REJECTIONS }.map { it.path }.toSet()
            return findings.filter { it.code in REJECTIONS || it.code !in SECONDARY || it.path !in rejected }
        }

    private companion object {
        val REJECTIONS = setOf(DiagnosticCode.T001_VALUE_REJECTED, DiagnosticCode.T005_NULL_FOR_TYPED_OPTION)
        val SECONDARY = setOf(
            DiagnosticCode.T004_CHOICE_MISMATCH, DiagnosticCode.T010_SHAPE_CONTRADICTION,
            DiagnosticCode.T011_COERCED_SCALAR_TO_STR, DiagnosticCode.T013_SCALAR_TYPE_MISMATCH,
            DiagnosticCode.T014_LEGACY_COERCION, DiagnosticCode.T016_STRING_FOR_NUMBER_OR_BOOL,
        )
    }
}

/**
 * Checks YAML values against option specs: what ansible-core's `ArgumentSpecValidator` would do (through
 * [ArgumentSpecValidator]) plus the documented-type rules (plan A.6), as [Finding]s with source ranges and
 * quick-fix hints (`quote`, `unquote`, `replace=<literal>`, `to-sequence`, `to-mapping`, `nearest-choice=<v>`,
 * `remove-key`, `nearest-key=<k>`, `add-key=<k>`).
 *
 * Severity is not decided here; the plugin maps each code through the active preset.
 *
 * @property isTemplated scalars ansible-core renders before validating; they are never judged (T020 covers them)
 * @property maxDocumentedDepth how many `options`/`elements` levels below the top the documented-type rules follow
 */
class SpecValidator(
    val semantics: CoreSemantics = CoreSemantics.PINNED,
    val kind: SpecKind = SpecKind.ROLE,
    paths: PathEnvironment = PathEnvironment.EMPTY,
    val isTemplated: (YScalar) -> Boolean = ::containsJinja,
    val maxDocumentedDepth: Int = 4,
) {
    private val port = ArgumentSpecValidator(semantics, paths)
    private val text = FindingText(semantics)
    private val documented = DocumentedTypeChecks(semantics, CheckType(semantics, paths), kind, isTemplated, maxDocumentedDepth, text)

    /** Checks the entries of [provided] (a vars file, play `vars:`, module arguments …) against [options]. */
    fun validate(options: Map<String, OptionSpec>, provided: YMap): SpecValidation = validate(options, provided.entries, provided.range)

    /** Checks one top-level value, e.g. one vars-file key, against the option it binds to. */
    fun validateValue(option: OptionSpec, value: YValue, key: YScalar = YScalar(option.name, ScalarStyle.PLAIN)): SpecValidation =
        validate(mapOf(option.name to option), listOf(YEntry(key, value)), value.range)

    /** Checks [entries] (in file order; a repeated key counts once, the last wins) against [options]. */
    fun validate(options: Map<String, OptionSpec>, entries: List<YEntry>, containerRange: SourceRange? = null): SpecValidation {
        val byName = LinkedHashMap<String, YEntry>()
        for (entry in entries) {
            if (kind == SpecKind.ROLE && entry.key.text !in options) continue
            byName.remove(entry.key.text)
            byName[entry.key.text] = entry
        }
        val params = PyValue.Dict.of(byName.values.map { PyValue.fromYValue(it.key) to PyValue.fromYValue(it.value, isTemplated) })
        val locator = Locator(options, byName)
        val full = port.validate(options, params)
        val findings = mutableListOf<Finding>()
        val effective = if (full.crash == null) full else withoutCrashes(options, params, full, locator, findings)
        for (error in effective.errors) findings += map(error, locator, options, containerRange)
        val rejectedPaths = effective.errors.flatMap { listOf(it.path) + it.mismatchIndices.map { i -> it.path + i.toString() } }.toSet()
        for ((name, option) in options) {
            val entry = byName[option.aliases.lastOrNull { it in byName } ?: name] ?: continue
            documented.check(option, entry.value, listOf(name), option, rejectedPaths, findings)
        }
        return SpecValidation(findings, full)
    }

    /**
     * ansible-core stops at the first crash. To report every value, each crash on a provided option becomes a
     * finding, and validation re-runs without that option until it completes.
     */
    private fun withoutCrashes(
        options: Map<String, OptionSpec>,
        params: PyValue.Dict,
        full: ArgSpecResult,
        locator: Locator,
        findings: MutableList<Finding>,
    ): ArgSpecResult {
        var spec = options
        var values = params
        var current = full
        while (true) {
            val crash = current.crash ?: return current
            val top = crash.path.firstOrNull()
            if (top == null || top !in spec) return current
            if (locator.locate(crash.path).provided) {
                findings += Finding(
                    DiagnosticCode.T001_VALUE_REJECTED,
                    "${text.core} would crash with ${crash.exceptionClass} (${crash.message}) validating " +
                        "`${text.path(crash.path)}`${text.owner(options[top])} instead of reporting a validation error",
                    locator.locate(crash.path).value?.range,
                    crash.path,
                )
            }
            spec = spec - top
            values = PyValue.Dict.of(values.entries.filter { (key, _) -> (key as? PyValue.Str)?.value != top })
            current = port.validate(spec, values)
        }
    }

    private fun map(error: ValidationError, locator: Locator, options: Map<String, OptionSpec>, containerRange: SourceRange?): List<Finding> {
        val owner = error.path.firstOrNull()?.let(options::get)
            ?: error.unsupportedPaths.firstOrNull()?.firstOrNull()?.let(options::get)
            ?: options.values.firstOrNull().takeIf { kind == SpecKind.MODULE }
        val ownerText = text.owner(owner)
        return when (error.kind) {
            ErrorKind.ALIAS, ErrorKind.REQUIRED_DEFAULT -> emptyList() // spec errors (ANS-S001), not value errors
            ErrorKind.REQUIRED -> required(error, locator, containerRange, ownerText)
            ErrorKind.UNSUPPORTED -> unsupported(error, locator, options, ownerText)
            ErrorKind.ARGUMENT_TYPE, ErrorKind.ELEMENT, ErrorKind.SUB_PARAMETER_TYPE, ErrorKind.NO_LOG -> {
                val located = locator.locate(error.path)
                if (!located.provided) return emptyList()
                val option = error.option
                val code = if (error.valueWasNone && error.kind == ErrorKind.ARGUMENT_TYPE) {
                    DiagnosticCode.T005_NULL_FOR_TYPED_OPTION
                } else {
                    DiagnosticCode.T001_VALUE_REJECTED
                }
                val message = if (code == DiagnosticCode.T005_NULL_FOR_TYPED_OPTION && option != null) {
                    val why = if (option.required) "required" else "defaulted"
                    "`null` for $why `${option.type.name}` option `${text.path(error.path)}`$ownerText: " +
                        "${text.core} would reject it: ${error.message}"
                } else {
                    "${text.core} would reject `${text.path(error.path)}`$ownerText: ${error.message}"
                }
                listOf(Finding(code, message, located.value?.range, error.path))
            }
            ErrorKind.ARGUMENT_VALUE -> choices(error, locator, ownerText)
        }
    }

    private fun required(error: ValidationError, locator: Locator, containerRange: SourceRange?, ownerText: String): List<Finding> {
        if (error.path.isEmpty()) {
            if (kind == SpecKind.ROLE) return emptyList()
            return listOf(
                Finding(
                    DiagnosticCode.M002_MISSING_MODULE_OPTION,
                    "Missing required option${if (error.names.size > 1) "s" else ""} " +
                        "${error.names.joinToString(", ") { "`$it`" }}$ownerText: ${text.core} would fail with \"${error.message}\"",
                    containerRange,
                    emptyList(),
                    error.names.map { "add-key=$it" },
                ),
            )
        }
        val located = locator.locate(error.path)
        if (!located.provided) return emptyList()
        return listOf(
            Finding(
                DiagnosticCode.T003_MISSING_REQUIRED_SUB_OPTION,
                "Missing required key${if (error.names.size > 1) "s" else ""} ${error.names.joinToString(", ") { "`$it`" }} " +
                    "in `${text.path(error.path)}`$ownerText: ${text.core} would fail with \"${error.message}\"",
                located.value?.range,
                error.path,
                error.names.map { "add-key=$it" },
            ),
        )
    }

    private fun unsupported(error: ValidationError, locator: Locator, options: Map<String, OptionSpec>, ownerText: String): List<Finding> =
        error.unsupportedPaths.mapNotNull { path ->
            val located = locator.locate(path)
            if (!located.provided) return@mapNotNull null
            val key = path.last()
            val topLevel = path.size == 1
            if (topLevel && kind == SpecKind.ROLE) return@mapNotNull null
            val supported = if (topLevel) options.keys else locator.optionAt(path.dropLast(1))?.options?.keys.orEmpty()
            val hints = listOfNotNull("remove-key", text.nearest(key, supported, 2)?.let { "nearest-key=$it" })
            val supportedText = if (supported.isEmpty()) "none" else supported.sorted().joinToString(", ")
            val code = if (topLevel) DiagnosticCode.M001_UNKNOWN_MODULE_OPTION else DiagnosticCode.T002_UNSUPPORTED_SUB_OPTION
            val where = if (topLevel) "" else " in `${text.path(path.dropLast(1))}`"
            Finding(
                code,
                "Unsupported key `$key`$where$ownerText: ${text.core} rejects unknown keys here " +
                    "(supported: $supportedText)",
                located.key?.range ?: located.value?.range,
                path,
                hints,
            )
        }

    private fun choices(error: ValidationError, locator: Locator, ownerText: String): List<Finding> {
        val located = locator.locate(error.path)
        if (!located.provided) return emptyList()
        val choiceTexts = error.option?.choices?.values.orEmpty().map {
            when (val choice = PyValue.fromYValue(it)) {
                is PyValue.Str -> choice.value
                else -> PyRepr.display(choice, Int.MAX_VALUE)
            }
        }
        val node = located.value
        val targets: List<Pair<YValue?, List<String>>> = if (error.mismatchIndices.isNotEmpty() && node is YSeq) {
            error.mismatchIndices.map { node.items.getOrNull(it) to error.path + it.toString() }
        } else {
            listOf(node to error.path)
        }
        return targets.map { (target, path) ->
            val shown = (target as? YScalar)?.text
            val hint = shown?.let { text.nearest(it, choiceTexts, maxOf(2, it.length / 2)) }
            Finding(
                DiagnosticCode.T004_CHOICE_MISMATCH,
                "${text.core} would reject `${text.path(path)}`$ownerText: ${error.message}",
                target?.range ?: node?.range,
                path,
                listOfNotNull(hint?.let { "nearest-choice=$it" }),
            )
        }
    }

    /** Maps validator paths back to the provided YAML nodes (following aliases the way `_handle_aliases` does). */
    private class Locator(private val options: Map<String, OptionSpec>, private val provided: Map<String, YEntry>) {
        class Located(val value: YValue?, val key: YScalar?, val provided: Boolean)

        fun locate(path: List<String>): Located {
            val top = path.firstOrNull() ?: return Located(null, null, false)
            var option: OptionSpec? = options[top]
            val entry = provided[option?.aliases?.lastOrNull { it in provided } ?: top] ?: return Located(null, null, false)
            var value: YValue = entry.value
            var key: YScalar? = entry.key
            for (segment in path.drop(1)) {
                when (value) {
                    is YSeq -> {
                        val index = segment.toIntOrNull() ?: break
                        value = value.items.getOrNull(index) ?: break
                        key = null
                    }
                    is YMap -> {
                        val sub = option?.options?.get(segment)
                        val present = value.entries.associateBy { it.key.text }
                        val name = sub?.aliases?.lastOrNull { it in present } ?: segment
                        val found = present[name] ?: break
                        value = found.value
                        key = found.key
                        option = sub
                    }
                    else -> break
                }
            }
            return Located(value, key, true)
        }

        /** The option whose value sits at [path] (list indices are skipped). */
        fun optionAt(path: List<String>): OptionSpec? {
            var option = options[path.firstOrNull() ?: return null] ?: return null
            for (segment in path.drop(1)) {
                if (segment.toIntOrNull() != null) continue
                option = option.options?.get(segment) ?: return null
            }
            return option
        }
    }

    companion object {
        /** Whether ansible-core would template the scalar (`{{`, `{%` or `{#`; `!unsafe` strings are never templated). */
        fun containsJinja(scalar: YScalar): Boolean =
            scalar.tag != "!unsafe" && ("{{" in scalar.text || "{%" in scalar.text || "{#" in scalar.text)
    }
}
