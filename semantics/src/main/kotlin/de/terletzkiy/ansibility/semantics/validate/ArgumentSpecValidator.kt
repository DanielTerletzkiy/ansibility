package de.terletzkiy.ansibility.semantics.validate

import de.terletzkiy.ansibility.semantics.coerce.Booleans
import de.terletzkiy.ansibility.semantics.coerce.CheckType
import de.terletzkiy.ansibility.semantics.coerce.CoreSemantics
import de.terletzkiy.ansibility.semantics.coerce.PathEnvironment
import de.terletzkiy.ansibility.semantics.schema.Choices
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.schema.OptionType
import de.terletzkiy.ansibility.semantics.value.IndeterminateValueException
import de.terletzkiy.ansibility.semantics.value.PyException
import de.terletzkiy.ansibility.semantics.value.PyKey
import de.terletzkiy.ansibility.semantics.value.PyRepr
import de.terletzkiy.ansibility.semantics.value.PyValue
import de.terletzkiy.ansibility.semantics.value.isHashable
import de.terletzkiy.ansibility.semantics.value.isTruthy
import de.terletzkiy.ansibility.semantics.value.pyEquals
import java.util.IdentityHashMap

/**
 * Line-by-line port of ansible-core's `ArgumentSpecValidator.validate()` (`module_utils/common/arg_spec.py` and
 * `parameters.py`): aliases → no_log walk → unsupported parameters → defaults (non-None) → required → types and
 * `elements` → `choices` (with the `'True'`/`'False'` rescue; `raw` values are not coerced first) → all defaults
 * → recursive sub-specs for `dict` and `list`/`elements: dict` options with `options`.
 *
 * `mutually_exclusive`, `required_*`, `fallback`, `apply_defaults` and deprecation keys are not part of
 * [OptionSpec] and are therefore not checked. `choices` given as a mapping ([Choices.Described]) behave like the
 * mapping's keys view, which is how ansible-doc's dict choices are normalised.
 */
class ArgumentSpecValidator(
    val semantics: CoreSemantics = CoreSemantics.PINNED,
    paths: PathEnvironment = PathEnvironment.EMPTY,
) {
    private val checkType = CheckType(semantics, paths)

    /** `ArgumentSpecValidator(spec).validate(parameters)`. */
    fun validate(spec: Map<String, OptionSpec>, parameters: PyValue.Dict): ArgSpecResult = Run(spec, parameters).execute()

    // ------------------------------------------------------------------------------------------ mutable values

    /** Python objects are mutated in place by the validator; these mirror them (identity included). */
    private sealed interface Node

    private class Leaf(val value: PyValue) : Node

    /** [origins] maps each item to its index in the list as provided (items failing `elements` are dropped). */
    private class MList(val items: MutableList<Node>, val origins: MutableList<Int> = items.indices.toMutableList()) : Node

    private class MDict(val map: LinkedHashMap<PyKey, Node>) : Node {
        fun containsKey(name: String): Boolean = map.containsKey(PyKey(PyValue.Str(name)))

        operator fun get(name: String): Node? = map[PyKey(PyValue.Str(name))]

        operator fun set(name: String, node: Node) {
            map[PyKey(PyValue.Str(name))] = node
        }
    }

    private fun thaw(value: PyValue): Node = when (value) {
        is PyValue.List -> MList(value.items.mapTo(mutableListOf()) { thaw(it) })
        is PyValue.Dict -> MDict(value.entries.associateTo(LinkedHashMap()) { (k, v) -> PyKey(k) to thaw(v) })
        else -> Leaf(value)
    }

    private fun freeze(node: Node): PyValue = when (node) {
        is Leaf -> node.value
        is MList -> PyValue.List(node.items.map(::freeze))
        is MDict -> PyValue.Dict(node.map.entries.map { (k, v) -> k.value to freeze(v) })
    }

    private fun isOpaque(value: PyValue) = value == PyValue.Vault || value is PyValue.Templated || value == PyValue.Unloadable

    /** An exception escaping `validate()`, with the path being checked when it was raised. */
    private class CrashSignal(val exception: PyException, val path: List<String>) : RuntimeException(exception.message)

    /** A `TypeError` raised inside `_list_no_log_values`, caught by the caller as a `NoLogError`. */
    private class NoLogFailure(message: String, val path: List<String>) : RuntimeException(message)

    // ------------------------------------------------------------------------------------------ one validation

    private inner class Run(private val spec: Map<String, OptionSpec>, parameters: PyValue.Dict) {
        private val params = thaw(parameters) as MDict
        private val errors = mutableListOf<ValidationError>()
        private val indeterminate = mutableListOf<List<String>>()
        private val unsupportedContexts = LinkedHashSet<List<PyValue>>()
        private val unsupportedPaths = mutableListOf<List<String>>()
        private var lastSupported: Pair<List<String>, List<String>> = emptyList<String>() to emptyList()
        private val defaults = IdentityHashMap<OptionSpec, PyValue?>()
        private val choiceValues = IdentityHashMap<OptionSpec, List<PyValue>>()

        fun execute(): ArgSpecResult = try {
            var aliases: Map<String, String> = emptyMap()
            try {
                aliases = handleAliases(spec, params)
            } catch (e: PyException) {
                if (e.pyClass !in CAUGHT) throw CrashSignal(e, emptyList())
                errors += ValidationError(ErrorKind.ALIAS, e.message.orEmpty(), emptyList())
            }
            val legal = aliases.keys + spec.keys
            try {
                listNoLogValues(spec, params, emptyList())
            } catch (e: NoLogFailure) {
                errors += ValidationError(ErrorKind.NO_LOG, e.message.orEmpty(), e.path)
            }
            try {
                unsupportedParameters(spec, params, legal, emptyList(), emptyList())
            } catch (e: PyException) {
                when (e.pyClass) {
                    "TypeError" -> errors += ValidationError(ErrorKind.REQUIRED_DEFAULT, e.message.orEmpty(), emptyList())
                    "ValueError" -> errors += ValidationError(ErrorKind.ALIAS, e.message.orEmpty(), emptyList())
                    else -> throw CrashSignal(e, emptyList())
                }
            }
            setDefaults(spec, params, setDefault = false)
            requiredArguments(spec, params, emptyList(), emptyList())
            validateArgumentTypes(spec, params, emptyList(), emptyList())
            validateArgumentValues(spec, params, emptyList(), emptyList())
            setDefaults(spec, params, setDefault = true)
            validateSubSpec(spec, params, emptyList(), mutableListOf())
            if (unsupportedContexts.isNotEmpty()) errors += unsupportedError()
            ArgSpecResult(errors.toList(), freeze(params) as PyValue.Dict, null, indeterminate.toList())
        } catch (crash: CrashSignal) {
            val crashInfo = ValidationCrash(crash.exception.pyClass, crash.exception.message.orEmpty(), crash.path)
            ArgSpecResult(errors.toList(), null, crashInfo, indeterminate.toList())
        }

        private fun defaultOf(option: OptionSpec): PyValue? = defaults.getOrPut(option) {
            option.default?.let { PyValue.fromYValue(it) }?.takeUnless { it == PyValue.None }
        }

        private fun choicesOf(option: OptionSpec): List<PyValue> = choiceValues.getOrPut(option) {
            option.choices?.values.orEmpty().map { PyValue.fromYValue(it) }
        }

        private fun foundIn(context: List<String>) = context.joinToString(" -> ")

        /** `type(value)` in 2.18 messages, `native_type_name(value)` in 2.19+. */
        private fun typeText(value: PyValue): String =
            if (semantics.nativeTypeNamesInMessages) value.typeName else CheckType.pyClass(value)

        private fun strOf(value: PyValue): String = try {
            PyRepr.str(value)
        } catch (e: IndeterminateValueException) {
            PyRepr.display(value)
        } catch (e: PyException) {
            PyRepr.display(value)
        }

        // -------------------------------------------------------------------------------------- helpers

        /** `_handle_aliases`: copies alias values onto the canonical name; returns alias → canonical. */
        private fun handleAliases(spec: Map<String, OptionSpec>, params: MDict): Map<String, String> {
            val results = LinkedHashMap<String, String>()
            for ((name, option) in spec) {
                if (defaultOf(option) != null && option.required) {
                    throw PyException.valueError("internal error: required and default are mutually exclusive for $name")
                }
                for (alias in option.aliases) {
                    results[alias] = name
                    params[alias]?.let { params[name] = it }
                }
            }
            return results
        }

        /** `_list_no_log_values`, for its errors only (the no_log values themselves are not needed). */
        private fun listNoLogValues(spec: Map<String, OptionSpec>, params: MDict, location: List<String>) {
            for ((name, option) in spec) {
                if (option.noLog) {
                    val node = params[name]
                    if (node != null && freeze(node).isTruthy) {
                        try {
                            walkNoLog(node)
                        } catch (e: PyException) {
                            throw NoLogFailure("Failed to convert \"$name\": ${e.message}", location + name)
                        }
                    }
                }
                val subSpec = option.options ?: continue
                val sub = params[name] ?: continue
                if (sub is Leaf && sub.value == PyValue.None) continue
                if (!hasSubSpecType(option)) continue
                val items = if (sub is MList) sub.items.mapIndexed { i, n -> n to location + name + sub.origins[i].toString() } else
                    listOf(sub to location + name)
                for ((item, itemPath) in items) {
                    var candidate = item
                    if (candidate is Leaf && candidate.value is PyValue.Str) {
                        candidate = try {
                            thaw(checkType.checkDict(candidate.value))
                        } catch (e: PyException) {
                            throw NoLogFailure(e.message.orEmpty(), itemPath)
                        } catch (e: IndeterminateValueException) {
                            continue
                        }
                    }
                    if (candidate is Leaf && isOpaque(candidate.value)) continue
                    if (candidate !is MDict) {
                        val value = freeze(candidate)
                        throw NoLogFailure(
                            "Value '${strOf(value)}' in the sub parameter field '$name' must be a ${option.type.name}, " +
                                "not '${value.typeName}'",
                            itemPath,
                        )
                    }
                    listNoLogValues(subSpec, candidate, itemPath)
                }
            }
        }

        /** `_return_datastructure_name`: raises `TypeError` for values it cannot stringify (dates). */
        private fun walkNoLog(node: Node) {
            when (node) {
                is MDict -> node.map.values.forEach(::walkNoLog)
                is MList -> node.items.forEach(::walkNoLog)
                is Leaf -> if (node.value is PyValue.Date) {
                    throw PyException.typeError("Unknown parameter type: ${CheckType.pyClass(node.value)}")
                }
            }
        }

        private fun hasSubSpecType(option: OptionSpec): Boolean =
            option.type == OptionType.Dict || (option.type == OptionType.List && option.elements == OptionType.Dict)

        /** `_get_unsupported_parameters` with `store_supported`; unsupported keys are collected for the final error. */
        private fun unsupportedParameters(
            spec: Map<String, OptionSpec>,
            params: MDict,
            legal: Collection<String>,
            context: List<String>,
            location: List<String>,
        ) {
            val found = mutableListOf<Pair<List<PyValue>, List<String>>>()
            for (key in params.map.keys.toList()) {
                val name = (key.value as? PyValue.Str)?.value
                if (name != null && name in legal) continue
                val contextTuple = context.map { PyValue.Str(it) } + key.value
                found += contextTuple to location + (name ?: PyRepr.display(key.value))
                val aliases = handleAliases(spec, params)
                lastSupported = legal.filter { it !in aliases }.sorted() to aliases.keys.sorted()
            }
            for ((contextTuple, path) in found) {
                unsupportedContexts += contextTuple
                unsupportedPaths += path
            }
        }

        private fun unsupportedError(): ValidationError {
            val flattened = unsupportedContexts.map { tuple ->
                if (tuple.any { it !is PyValue.Str }) {
                    throw CrashSignal(PyException.typeError("sequence item: expected str instance"), emptyList())
                }
                tuple.joinToString(".") { (it as PyValue.Str).value }
            }.sorted()
            val (supportedParams, supportedAliases) = lastSupported
            var supported = supportedParams.joinToString(", ")
            if (supportedAliases.isNotEmpty()) supported += " (${supportedAliases.joinToString(", ")})"
            return ValidationError(
                ErrorKind.UNSUPPORTED,
                "${flattened.joinToString(", ")}. Supported parameters include: $supported.",
                unsupportedPaths.firstOrNull()?.dropLast(1).orEmpty(),
                names = flattened,
                unsupportedPaths = unsupportedPaths.toList(),
            )
        }

        /** `_set_defaults`: absent options get their default (or None, when [setDefault]). */
        private fun setDefaults(spec: Map<String, OptionSpec>, params: MDict, setDefault: Boolean) {
            for ((name, option) in spec) {
                val default = defaultOf(option)
                if (!params.containsKey(name) && (default != null || setDefault)) params[name] = thaw(default ?: PyValue.None)
            }
        }

        /** `check_required_arguments`. */
        private fun requiredArguments(spec: Map<String, OptionSpec>, params: MDict, context: List<String>, location: List<String>) {
            val missing = spec.filter { (name, option) -> option.required && !params.containsKey(name) }.keys.sorted()
            if (missing.isEmpty()) return
            var message = "missing required arguments: ${missing.joinToString(", ")}"
            if (context.isNotEmpty()) message += " found in ${foundIn(context)}"
            errors += ValidationError(ErrorKind.REQUIRED, message, location, names = missing)
        }

        /** The type checker, keeping container identity where Python's checker returns its argument. */
        private fun convertNode(type: OptionType, node: Node): Node = when {
            type == OptionType.Raw -> node
            type == OptionType.Dict && node is MDict -> node
            type == OptionType.List && node is MList -> node
            else -> thaw(checkType.convert(type, freeze(node)))
        }

        /** `_validate_argument_types`, including the `elements` check. */
        private fun validateArgumentTypes(spec: Map<String, OptionSpec>, params: MDict, location: List<String>, context: List<String>) {
            for ((name, option) in spec) {
                val node = params[name] ?: continue
                val value = freeze(node)
                if (value == PyValue.None && !option.required && defaultOf(option) == null) continue
                val path = location + name
                try {
                    val converted = convertNode(option.type, node)
                    params[name] = converted
                    val elementsType = option.elements
                    if (elementsType != null) {
                        val notList = if (semantics.elementsRequireListType) option.type != OptionType.List else converted !is MList
                        if (notList || converted !is MList) {
                            var message = "Invalid type ${option.type.name} for option '${strOf(freeze(converted))}'"
                            if (context.isNotEmpty()) message += " found in '${foundIn(context)}'."
                            message += ", elements value check is supported only with 'list' type"
                            errors += ValidationError(ErrorKind.ARGUMENT_TYPE, message, path, option = option, elementsShape = true)
                        }
                        params[name] = validateElements(elementsType, name, converted, context, path, option)
                    }
                } catch (e: PyException) {
                    if (e.pyClass !in CAUGHT) throw CrashSignal(e, path)
                    var message = "argument '$name' is of type ${typeText(value)}"
                    if (context.isNotEmpty()) message += " found in '${foundIn(context)}'."
                    message += " and we were unable to convert to ${option.type.name}: ${e.message}"
                    errors += ValidationError(ErrorKind.ARGUMENT_TYPE, message, path, option = option, valueWasNone = value == PyValue.None)
                } catch (e: IndeterminateValueException) {
                    indeterminate += path
                }
            }
        }

        /** `_validate_elements`: iterates the value (list items, dict keys, or the characters of a str). */
        private fun validateElements(
            type: OptionType,
            name: String,
            values: Node,
            context: List<String>,
            path: List<String>,
            option: OptionSpec,
        ): Node {
            val items: List<Pair<Node, Int>> = when (values) {
                is MList -> values.items.mapIndexed { i, n -> n to values.origins[i] }
                is MDict -> values.map.keys.mapIndexed { i, k -> Leaf(k.value) to i }
                is Leaf -> when (val v = values.value) {
                    is PyValue.Str -> v.value.codePoints().toArray().mapIndexed { i, cp -> Leaf(PyValue.Str(String(Character.toChars(cp)))) to i }
                    PyValue.Vault, is PyValue.Templated, PyValue.Unloadable -> throw IndeterminateValueException(v)
                    else -> throw PyException.typeError("'${v.typeName}' object is not iterable")
                }
            }
            val result = MList(mutableListOf(), mutableListOf())
            for ((item, origin) in items) {
                val itemPath = path + origin.toString()
                try {
                    result.items += convertNode(type, item)
                    result.origins += origin
                } catch (e: PyException) {
                    if (e.pyClass !in CAUGHT) throw CrashSignal(e, itemPath)
                    val itemValue = freeze(item)
                    var message = "Elements value for option '$name'"
                    if (context.isNotEmpty()) message += " found in '${foundIn(context)}'"
                    message += " is of type ${typeText(itemValue)} and we were unable to convert to ${type.name}: ${e.message}"
                    errors += ValidationError(ErrorKind.ELEMENT, message, itemPath, option = option, valueWasNone = itemValue == PyValue.None)
                } catch (e: IndeterminateValueException) {
                    indeterminate += itemPath
                    result.items += item
                    result.origins += origin
                }
            }
            return result
        }

        /** `x in choices`: a list compares with `==`; a keys view hashes [value] first. */
        private fun inChoices(option: OptionSpec, value: PyValue, path: List<String>): Boolean {
            if (option.choices is Choices.Described && !value.isHashable) {
                throw CrashSignal(PyException.typeError("unhashable type: '${value.typeName}'"), path)
            }
            return choicesOf(option).any { it.pyEquals(value) }
        }

        /** `BOOLEANS_TRUE/FALSE.intersection(choices)`: the choices (first of equal ones) that are boolean spellings. */
        private fun booleanOverlap(option: OptionSpec, truth: Boolean, path: List<String>): List<PyValue> {
            val overlap = mutableListOf<PyValue>()
            for (choice in choicesOf(option)) {
                if (!choice.isHashable) throw CrashSignal(PyException.typeError("unhashable type: '${choice.typeName}'"), path)
                if (Booleans.isMember(choice, truth) && overlap.none { it.pyEquals(choice) }) overlap += choice
            }
            return overlap
        }

        /** `_validate_argument_values` (choices). */
        private fun validateArgumentValues(spec: Map<String, OptionSpec>, params: MDict, location: List<String>, context: List<String>) {
            for ((name, option) in spec) {
                if (option.choices == null) continue
                val node = params[name] ?: continue
                val path = location + name
                if (path in indeterminate) continue
                val value = freeze(node)
                val choicesText = choicesOf(option).joinToString(", ") { strOf(it) }
                if (value is PyValue.List) {
                    val misses = value.items.withIndex().filter { (_, item) -> !isOpaque(item) && !inChoices(option, item, path) }
                    if (misses.isNotEmpty()) {
                        var message = "value of $name must be one or more of: $choicesText. Got no match for: " +
                            misses.joinToString(", ") { strOf(it.value) }
                        if (context.isNotEmpty()) message = "$message found in ${foundIn(context)}"
                        val origins = (node as? MList)?.origins
                        errors += ValidationError(
                            ErrorKind.ARGUMENT_VALUE, message, path, option = option,
                            mismatchIndices = misses.map { origins?.getOrNull(it.index) ?: it.index },
                        )
                    }
                    continue
                }
                if (isOpaque(value) || inChoices(option, value, path)) continue
                var current = value
                if (current == PyValue.Str("False")) {
                    booleanOverlap(option, false, path).singleOrNull()?.let {
                        current = it
                        params[name] = thaw(it)
                    }
                }
                if (current == PyValue.Str("True")) {
                    booleanOverlap(option, true, path).singleOrNull()?.let {
                        current = it
                        params[name] = thaw(it)
                    }
                }
                if (!inChoices(option, current, path)) {
                    var message = "value of $name must be one of: $choicesText, got: ${strOf(current)}"
                    if (context.isNotEmpty()) message = "$message found in ${foundIn(context)}"
                    errors += ValidationError(ErrorKind.ARGUMENT_VALUE, message, path, option = option, valueWasNone = value == PyValue.None)
                }
            }
        }

        /** `_validate_sub_spec`: recursion into dict and list-of-dict options that declare `options`. */
        private fun validateSubSpec(spec: Map<String, OptionSpec>, params: MDict, location: List<String>, context: MutableList<String>) {
            for ((name, option) in spec) {
                if (!hasSubSpecType(option)) continue
                val subSpec = option.options ?: continue
                val node = params[name] ?: continue
                if (node is Leaf && node.value == PyValue.None) continue
                context += name
                val elements = if (node is MList) node.items.mapIndexed { i, n -> n to location + name + node.origins[i].toString() } else
                    listOf(node to location + name)
                for ((sub, subPath) in elements) {
                    if (sub is Leaf && isOpaque(sub.value)) {
                        indeterminate += subPath
                        continue
                    }
                    // set_fallbacks evaluates `name not in sub_parameters`, which only containers and str support.
                    if (subSpec.isNotEmpty() && sub is Leaf && sub.value !is PyValue.Str) {
                        throw CrashSignal(PyException.typeError("argument of type '${sub.value.typeName}' is not iterable"), subPath)
                    }
                    if (sub !is MDict) {
                        errors += ValidationError(
                            ErrorKind.SUB_PARAMETER_TYPE, "value of '$name' must be of type dict or list of dicts", subPath, option = option,
                        )
                        continue
                    }
                    var aliases: Map<String, String> = emptyMap()
                    try {
                        aliases = handleAliases(subSpec, sub)
                    } catch (e: PyException) {
                        errors += ValidationError(ErrorKind.ALIAS, e.message.orEmpty(), subPath)
                    }
                    try {
                        listNoLogValues(subSpec, sub, subPath)
                    } catch (e: NoLogFailure) {
                        errors += ValidationError(ErrorKind.NO_LOG, e.message.orEmpty(), e.path)
                    }
                    try {
                        unsupportedParameters(subSpec, sub, aliases.keys + subSpec.keys, context.toList(), subPath)
                    } catch (e: PyException) {
                        throw CrashSignal(e, subPath)
                    }
                    setDefaults(subSpec, sub, setDefault = false)
                    requiredArguments(subSpec, sub, context.toList(), subPath)
                    validateArgumentTypes(subSpec, sub, subPath, context.toList())
                    validateArgumentValues(subSpec, sub, subPath, context.toList())
                    setDefaults(subSpec, sub, setDefault = true)
                    validateSubSpec(subSpec, sub, subPath, context)
                }
                context.removeAt(context.lastIndex)
            }
        }
    }

    private companion object {
        val CAUGHT = setOf("TypeError", "ValueError")
    }
}
