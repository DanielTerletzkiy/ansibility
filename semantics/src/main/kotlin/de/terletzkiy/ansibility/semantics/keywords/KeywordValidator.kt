package de.terletzkiy.ansibility.semantics.keywords

import de.terletzkiy.ansibility.semantics.coerce.Booleans
import de.terletzkiy.ansibility.semantics.coerce.CheckResult
import de.terletzkiy.ansibility.semantics.coerce.CoreSemantics
import de.terletzkiy.ansibility.semantics.coerce.PyNumbers
import de.terletzkiy.ansibility.semantics.schema.KeywordDoc
import de.terletzkiy.ansibility.semantics.schema.TemplateMode
import de.terletzkiy.ansibility.semantics.validate.SpecValidator
import de.terletzkiy.ansibility.semantics.value.PyException
import de.terletzkiy.ansibility.semantics.value.PyRepr
import de.terletzkiy.ansibility.semantics.value.PyValue
import de.terletzkiy.ansibility.semantics.value.isTruthy
import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.SourceRange
import de.terletzkiy.ansibility.semantics.yaml.YEmpty
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.semantics.yaml.YVault

/**
 * A keyword value ansible-core rejects (ANS-K001).
 *
 * @property keyword the keyword as written
 * @property reason ansible-core's own error text in the target version's wording
 * @property range the rejected node: the whole value, or the offending list item or mapping key
 * @property crash ansible-core fails with a traceback instead of an error message (2.18's `OverflowError` for
 *   `retries: inf`, an invalid `serial` batch)
 */
data class KeywordRejection(
    val keyword: String,
    val reason: String,
    val range: SourceRange?,
    val crash: Boolean = false,
)

/**
 * Port of how ansible-core loads and post-validates playbook keywords (`playbook/base.py` `FieldAttributeBase`:
 * `load_data`, `validate`, `post_validate`, `get_validated_value`, plus the `_load_*`, `_validate_*` and
 * `_post_validate_*` overrides of `Play`, `Block`, `Task`, `Handler`, `LoopControl`, `Taggable`, `CollectionSearch`
 * and `PlaybookInclude`) for 2.18.8 and 2.21.4. [check] returns what ansible-core rejects, never what it merely
 * converts:
 *
 * - `isa` conversions: `int` through `Decimal` (`3.0` and `'1e3'` pass, `3.5` and `'abc'` fail), `float`, `bool`
 *   through `boolean(strict=True)`, `percent` (`'50%'`), `list` (a scalar is wrapped, so `tags: haproxy`,
 *   `notify: x`, `when: x` and `serial: 1` stay clean; `listof` item types are checked), `dict`, and the load-time
 *   rule that a `string` keyword cannot be a list or mapping;
 * - loaders that reject shapes: `tags` (a list or a comma string), `vars` (a mapping with valid names), `register`,
 *   `loop_control` (a literal mapping), `module_defaults`, `args`, task lists (`tasks`, `block` …), `roles`,
 *   `hosts`, `import_playbook`;
 * - runtime checks on play keywords: `serial` batches (`pct_to_int`), `order` (the inventory's host orders),
 *   `debugger` values.
 *
 * Only objects ansible-core post-validates are converted: tasks and handlers, and what they inherit from plays,
 * blocks and role entries; a play's own `always_post_validate` keywords; `loop_control`'s; `import_playbook`
 * entries only from 2.19. Templated scalars are skipped (their rendered value is unknown), except for keywords
 * ansible-core never templates (`register`, `vars` names, `loop_control`, `module_defaults`, static keywords).
 * Vault values are always skipped.
 *
 * Severity, unknown keywords (ANS-K002) and the walk over a file belong to the caller.
 */
class KeywordValidator(
    val semantics: CoreSemantics = CoreSemantics.PINNED,
    val isTemplated: (YScalar) -> Boolean = SpecValidator::containsJinja,
) {
    private val rules = KeywordSemantics.of(semantics)

    /**
     * What ansible-core rejects in [value], the value of keyword [name] on [owner] described by [doc] (the target
     * line's keyword docs; null when the keyword is undocumented, which only the shape loaders handle).
     */
    fun check(owner: PlaybookObject, name: String, doc: KeywordDoc?, value: YValue): List<KeywordRejection> {
        val rejection = when (name) {
            "tags" -> tags(value)
            "vars" -> vars(owner, value)
            "register" -> if (owner.isTask) register(value) else null
            "listen" -> if (owner == PlaybookObject.HANDLER) staticStrings(name, value) else null
            "collections" -> if (owner != PlaybookObject.PLAYBOOK_INCLUDE) staticStrings(name, value) else null
            "module_defaults" -> moduleDefaults(value)
            "loop_control" -> if (owner.isTask) loopControl(value) else null
            "args" -> if (owner.isTask) args(value) else null
            "action", "local_action", "loop", "environment", "when", "changed_when", "failed_when", "until",
            "break_when", "vars_files", "vars_prompt", "notify" -> null
            else -> byOwner(owner, name, doc, value)
        }
        return listOfNotNull(rejection)
    }

    private fun byOwner(owner: PlaybookObject, name: String, doc: KeywordDoc?, value: YValue): KeywordRejection? = when {
        owner == PlaybookObject.PLAY && name == "hosts" -> hosts(value)
        owner == PlaybookObject.PLAY && name in PLAY_TASK_LISTS -> taskList(name, value, nullAllowed = true)
        owner == PlaybookObject.PLAY && name == "roles" -> roleList(value)
        owner == PlaybookObject.BLOCK && name in BLOCK_TASK_LISTS -> taskList(name, value, nullAllowed = false)
        owner == PlaybookObject.PLAYBOOK_INCLUDE && name == "import_playbook" -> importPath(value)
        else -> generic(owner, name, doc, value)
    }

    // ------------------------------------------------------------------------------------------------ generic isa

    private fun generic(owner: PlaybookObject, name: String, doc: KeywordDoc?, value: YValue): KeywordRejection? {
        val isa = doc?.isa ?: return null
        if (doc.template == TemplateMode.STATIC) return null
        // FieldAttributeBase.validate(), at load time, on every object.
        if (isa == "string" && (value is YSeq || value is YMap)) return stringShape(name, value)
        if (!postValidated(owner, name, doc)) return null
        if (isOpaque(value)) return null
        val py = PyValue.fromYValue(value, isTemplated)
        if (py == PyValue.None || py.isOpaqueValue()) return null
        return when (name) {
            "serial" -> if (owner == PlaybookObject.PLAY) serial(value) else null
            "order" -> if (owner == PlaybookObject.PLAY) order(py, value) else null
            "debugger" -> debugger(py, value)
            "loop_var", "index_var" -> if (owner == PlaybookObject.LOOP_CONTROL) loopVariable(name, py, value) else null
            else -> convert(name, isa, doc.listOf, value, py)
        }
    }

    /** Whether ansible-core post-validates keyword [name] of [owner] (directly or on the tasks that inherit it). */
    private fun postValidated(owner: PlaybookObject, name: String, doc: KeywordDoc): Boolean = when (owner) {
        PlaybookObject.TASK, PlaybookObject.HANDLER -> true
        PlaybookObject.LOOP_CONTROL -> name in LOOP_CONTROL_ALWAYS
        PlaybookObject.PLAY -> name in PLAY_ALWAYS || inheritedByTasks(doc)
        PlaybookObject.BLOCK, PlaybookObject.ROLE -> inheritedByTasks(doc)
        PlaybookObject.PLAYBOOK_INCLUDE -> rules.postValidatesPlaybookImports
    }

    private fun inheritedByTasks(doc: KeywordDoc): Boolean = PlaybookObject.TASK.docName in doc.appliesTo

    /** `get_validated_value` for one value that is not None, opaque or templated. */
    private fun convert(name: String, isa: String, listOf: List<String>?, value: YValue, py: PyValue): KeywordRejection? = when (isa) {
        "int" -> toInt(name, py, value)
        "float" -> conversion(name, isa, py, value) { toFloat(it) }
        "bool" -> toBool(name, py, value)
        "percent" -> conversion(name, isa, py, value) { v ->
            if (v is PyValue.Str && '%' in v.value) toFloat(PyValue.Str(v.value.replace("%", ""))) else toFloat(v)
        }
        "list" -> listOf?.let { items(name, listOf, value, dropNone = false) }
        "dict" -> if (py !is PyValue.Dict) reject(name, convertFailure(name, isa, py), value) else null
        else -> null // string (to_text never fails), class (loop_control), set (no keyword)
    }

    private fun toInt(name: String, py: PyValue, value: YValue): KeywordRejection? {
        if (py is PyValue.Int || py is PyValue.Bool) return null
        return try {
            PyNumbers.integralValue(PyNumbers.decimal(py)) ?: return reject(name, convertFailure(name, "int", py), value)
            null
        } catch (e: PyException) {
            if (e.pyClass == "OverflowError" || e.pyClass == "MemoryError") crash(name, "int", py, value, e) else reject(name, convertFailure(name, "int", py), value)
        }
    }

    private fun toBool(name: String, py: PyValue, value: YValue): KeywordRejection? = when (val result = Booleans.boolean(py, strict = true, semantics = semantics)) {
        is CheckResult.Rejected, is CheckResult.Crash -> reject(name, convertFailure(name, "bool", py), value)
        else -> null
    }

    /** `float(value)` as Python does it; raises [PyException] like CPython. */
    private fun toFloat(value: PyValue): Double = when (value) {
        is PyValue.Float -> value.value
        is PyValue.Bool -> if (value.value) 1.0 else 0.0
        is PyValue.Int -> PyNumbers.floatFromInt(value.value)
        is PyValue.Str -> PyNumbers.floatFromString(value.value)
        else -> throw PyException.typeError("float() argument must be a string or a real number, not '${value.typeName}'")
    }

    private fun conversion(name: String, isa: String, py: PyValue, value: YValue, convert: (PyValue) -> Double): KeywordRejection? = try {
        convert(py)
        null
    } catch (e: PyException) {
        if (e.pyClass == "OverflowError") crash(name, isa, py, value, e) else reject(name, convertFailure(name, isa, py), value)
    }

    private fun crash(name: String, isa: String, py: PyValue, value: YValue, e: PyException): KeywordRejection =
        if (rules.wrapsConversionCrashes) {
            reject(name, convertFailure(name, isa, py), value)
        } else {
            KeywordRejection(name, "${e.pyClass}: ${e.message}", value.range, crash = true)
        }

    /** The `listof` check of a list keyword: every item (a scalar is wrapped) must have one of [types]. */
    private fun items(name: String, types: List<String>, value: YValue, dropNone: Boolean): KeywordRejection? {
        val nodes = if (value is YSeq) value.items else listOf(value)
        for (node in nodes) {
            if (isOpaque(node)) continue
            val item = PyValue.fromYValue(node, isTemplated)
            if (item.isOpaqueValue()) continue
            if (item == PyValue.None && dropNone) continue
            if (types.none { matches(item, it) }) return reject(name, listOfFailure(name, types, item), node)
        }
        return null
    }

    private fun matches(item: PyValue, type: String): Boolean = when (type) {
        "str" -> item is PyValue.Str
        "int" -> item is PyValue.Int || item is PyValue.Bool
        else -> true
    }

    // ------------------------------------------------------------------------------------------------ loaders

    /** `Taggable._load_tags`, then the `listof=(str, int)` check (`_extend_value` drops `None` items first). */
    private fun tags(value: YValue): KeywordRejection? {
        if (isOpaque(value)) return null
        if (value !is YSeq) {
            val py = PyValue.fromYValue(value, isTemplated)
            return if (py is PyValue.Str || py.isOpaqueValue()) null else reject("tags", "tags must be specified as a list", value)
        }
        // 2.18 unions an import's items into each imported play's tags, which its tasks inherit: same check.
        return items("tags", TAG_TYPES, value, dropNone = true)
    }

    /** `Base._load_vars` (or 2.18's `PlaybookInclude.preprocess_data`): a mapping whose keys are valid variable names. */
    private fun vars(owner: PlaybookObject, value: YValue): KeywordRejection? {
        if (value is YVault) return null
        if (owner == PlaybookObject.PLAYBOOK_INCLUDE && rules.importVarsMustBeMapping) {
            return if (value is YMap) null else reject("vars", "vars for import_playbook statements must be specified as a dictionary", value)
        }
        return when (value) {
            is YEmpty -> null
            is YMap -> value.entries.firstOrNull { PyNames.rejectsVariableName(PyValue.fromYValue(it.key), rules) }?.let { entry ->
                val key = PyRepr.display(PyValue.fromYValue(entry.key))
                val reason = if (rules.newWording) {
                    "Invalid variable name in vars specified for ${owner.className}: invalid variable name $key."
                } else {
                    "Invalid variable name in vars specified for ${owner.className}: $key is not a valid variable name"
                }
                reject("vars", reason, entry.key)
            }
            is YScalar -> if (value.resolved == Resolved.Null) null else varsShape(owner, value)
            else -> varsShape(owner, value)
        }
    }

    private fun varsShape(owner: PlaybookObject, value: YValue) =
        reject("vars", "Vars in a ${owner.className} must be specified as a dictionary", value)

    /** `Task._validate_register`: a valid variable name; never templated (a `{{ }}` value is rejected too). */
    private fun register(value: YValue): KeywordRejection? {
        if (value is YEmpty || value is YVault) return null
        if (value is YMap && !rules.rejectsRegisterMapping) return null
        if (value is YScalar && value.resolved == Resolved.Null) return null
        val py = PyValue.fromYValue(value)
        if (!PyNames.rejectsVariableName(py, rules)) return null
        val reason = if (rules.newWording) "Invalid 'register' specified." else "Invalid variable name in 'register' specified: '${pyStr(py)}'"
        return reject("register", reason, value)
    }

    /** A static `list` of `str` (`listen`, `collections`), validated untemplated at load time. */
    private fun staticStrings(name: String, value: YValue): KeywordRejection? {
        if (value is YEmpty || isOpaque(value)) return null
        if (value is YScalar && value.resolved == Resolved.Null) return null
        val nodes = if (value is YSeq) value.items else listOf(value)
        for (node in nodes) {
            if (node is YVault) continue
            val item = PyValue.fromYValue(node)
            if (item !is PyValue.Str) return reject(name, listOfFailure(name, STR_TYPE, item), node)
        }
        return null
    }

    /** `_load_module_defaults`: a mapping or a list of mappings; a template is not accepted. */
    private fun moduleDefaults(value: YValue): KeywordRejection? {
        if (value is YEmpty) return null
        if (value is YScalar && value.resolved == Resolved.Null) return null
        val nodes = if (value is YSeq) value.items else listOf(value)
        val bad = nodes.firstOrNull { it !is YMap } ?: return null
        return reject(
            "module_defaults",
            "The field 'module_defaults' is supposed to be a dictionary or list of dictionaries, the keys of which must be " +
                "static action, module, or group names. Only the values may contain templates.",
            bad,
        )
    }

    /** `Task._load_loop_control`: a literal mapping (not a template, not null). */
    private fun loopControl(value: YValue): KeywordRejection? = if (value is YMap) {
        null
    } else {
        reject(
            "loop_control",
            "the `loop_control` value must be specified as a dictionary and cannot be a variable itself (though it can contain variables)",
            value,
        )
    }

    /** `ModuleArgsParser._normalize_parameters` on the task's `args:`: a mapping or a whole template. */
    private fun args(value: YValue): KeywordRejection? {
        val invalid = when (value) {
            is YMap, is YEmpty, is YVault -> false
            // 2.18 skips an empty string (`if additional_args:`); 2.19+ requires a mapping or a template.
            is YScalar -> value.resolved != Resolved.Null && !isTemplated(value) && (value.text.isNotEmpty() || rules.newWording)
            else -> true
        }
        if (!invalid) return null
        val reason = when {
            rules.newWording -> "The value of the task `args` keyword is invalid. A mapping or template which resolves to a mapping is required."
            value is YScalar -> "Complex args containing variables cannot use bare variables (without Jinja2 delimiters), and must use the " +
                "full variable style ('{{var_name}}')"
            else -> "Complex args must be a dictionary or variable string (\"{{var}}\")."
        }
        return reject("args", reason, value)
    }

    /** `load_list_of_blocks` (play sections: a list or null) and `load_list_of_tasks` (block sections: a list). */
    private fun taskList(name: String, value: YValue, nullAllowed: Boolean): KeywordRejection? = when {
        value is YSeq -> null
        nullAllowed && isNull(value) -> null
        else -> reject(name, "A malformed block was encountered while loading $name: the value should be a list${if (nullAllowed) " or None" else ""}", value)
    }

    /** `load_list_of_roles`: a list (null means no roles). */
    private fun roleList(value: YValue): KeywordRejection? =
        if (value is YSeq || isNull(value)) null else reject("roles", "A malformed role declaration was encountered: the value should be a list", value)

    /** `PlaybookInclude._preprocess_import`: a string naming the playbook. */
    private fun importPath(value: YValue): KeywordRejection? {
        if (isNull(value)) return reject("import_playbook", "playbook import parameter is missing", value)
        if (isOpaque(value)) return null
        val py = PyValue.fromYValue(value, isTemplated)
        if (py is PyValue.Str || py.isOpaqueValue()) return null
        return reject("import_playbook", "playbook import parameter must be a string indicating a file path, got ${pyClass(py)} instead", value)
    }

    /** `Play._validate_hosts` (load time) and the `required`, `listof=(str,)` post-validation. */
    private fun hosts(value: YValue): KeywordRejection? {
        if (isOpaque(value)) return null
        val py = PyValue.fromYValue(value, isTemplated)
        if (py.isOpaqueValue()) return null
        if (!py.isTruthy) return reject("hosts", "Hosts list cannot be empty. Please check your playbook", value)
        if (value is YSeq) {
            for (node in value.items) {
                val item = PyValue.fromYValue(node, isTemplated)
                if (item.isOpaqueValue()) continue
                if (item == PyValue.None) return reject("hosts", "Hosts list cannot contain values of 'None'. Please check your playbook", node)
                if (item !is PyValue.Str) return reject("hosts", "Hosts list contains an invalid host value: '${pyStr(item)}'", node)
                if (item.value.isBlank()) return reject("hosts", "the field 'hosts' is required, and cannot have empty values", node)
            }
            return null
        }
        if (py !is PyValue.Str) return reject("hosts", "Hosts list must be a sequence or string. Please check your playbook.", value)
        if (py.value.isBlank()) return reject("hosts", "the field 'hosts' is required, and cannot have empty values", value)
        return null
    }

    /** `pct_to_int` over every batch of `serial` (`PlaybookExecutor._get_serialized_batches`); failures are tracebacks. */
    private fun serial(value: YValue): KeywordRejection? {
        val nodes = if (value is YSeq) value.items else listOf(value)
        for (node in nodes) {
            if (isOpaque(node)) continue
            val item = PyValue.fromYValue(node, isTemplated)
            val problem = when (item) {
                is PyValue.Int, is PyValue.Bool -> null
                is PyValue.Float -> when {
                    item.value.isNaN() -> "ValueError: cannot convert float NaN to integer"
                    item.value.isInfinite() -> "OverflowError: cannot convert float infinity to integer"
                    else -> null
                }
                is PyValue.Str -> {
                    val text = if (item.value.endsWith("%")) item.value.replace("%", "") else item.value
                    if (PyNames.parseInt(text) == null) "ValueError: invalid literal for int() with base 10: ${PyRepr.strRepr(text)}" else null
                }
                is PyValue.Templated, PyValue.Vault, PyValue.Unloadable -> null
                else -> "TypeError: int() argument must be a string, a bytes-like object or a real number, not '${item.typeName}'"
            }
            if (problem != null) return KeywordRejection("serial", problem, node.range, crash = true)
        }
        return null
    }

    /** `InventoryManager.get_hosts(order=…)`: one of the inventory's host orders. */
    private fun order(py: PyValue, value: YValue): KeywordRejection? {
        val text = PyRepr.str(py)
        return if (text in HOST_ORDERS) null else reject("order", "Invalid 'order' specified for inventory hosts: $text", value)
    }

    /** `Base._post_validate_debugger`: a string value must be one of the debugger modes. */
    private fun debugger(py: PyValue, value: YValue): KeywordRejection? {
        if (py !is PyValue.Str || py.value.isEmpty() || py.value in DEBUGGER_MODES) return null
        return reject("debugger", "'${py.value}' is not a valid value for debugger. Must be one of ${DEBUGGER_MODES.joinToString(", ")}", value)
    }

    /** 2.21's `LoopControl.post_validate`: `loop_var` and `index_var` must be valid variable names (after `to_text`). */
    private fun loopVariable(name: String, py: PyValue, value: YValue): KeywordRejection? {
        if (!rules.validatesLoopVariableNames) return null
        if (!PyNames.rejectsVariableName(PyValue.Str(PyRepr.str(py)), rules)) return null
        return reject(name, "Invalid '$name'.", value)
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private fun stringShape(name: String, value: YValue): KeywordRejection =
        reject(name, "The field '$name' is supposed to be a string type, however the incoming data structure is a ${if (value is YSeq) "list" else "dict"}", value)

    /** The post-validation error text of the target version. */
    private fun convertFailure(name: String, isa: String, py: PyValue): String = if (rules.newWording) {
        "Error processing keyword '$name': The value ${PyRepr.display(py)} could not be converted to '$isa'."
    } else {
        "the field '${FIELD_NAMES[name] ?: name}' has an invalid value (${PyRepr.display(py)}), and could not be converted to $isa."
    }

    private fun listOfFailure(name: String, types: List<String>, item: PyValue): String = if (rules.newWording) {
        "Keyword '$name' items must be of type ${types.joinToString(" or ") { "'$it'" }}, not '${item.typeName}'."
    } else {
        "the field '$name' should be a list of (${types.joinToString(", ")}), but the item '${pyStr(item)}' is a ${item.typeName}"
    }

    private fun reject(name: String, reason: String, node: YValue): KeywordRejection = KeywordRejection(name, reason, node.range)

    private fun isOpaque(value: YValue): Boolean = value is YVault || (value is YScalar && isTemplated(value))

    private fun isNull(value: YValue): Boolean =
        value is YEmpty || (value is YScalar && value.resolved == Resolved.Null)

    private fun PyValue.isOpaqueValue(): Boolean = this == PyValue.Vault || this is PyValue.Templated || this == PyValue.Unloadable

    private fun pyClass(value: PyValue): String = "<class '${value.typeName}'>"

    /** `str(value)` as `'%s' %` formats it; opaque values get [PyRepr.display]'s placeholder. */
    private fun pyStr(value: PyValue): String = if (value.isOpaqueValue()) PyRepr.display(value) else PyRepr.str(value).let { if (it.length > 60) it.take(59) + "…" else it }

    private companion object {
        val TAG_TYPES = listOf("str", "int")
        val STR_TYPE = listOf("str")

        /** `Play` keywords with `always_post_validate=True` (the others are validated on the tasks that inherit them). */
        val PLAY_ALWAYS = setOf(
            "hosts", "gather_facts", "gather_subset", "gather_timeout", "force_handlers", "max_fail_percentage", "serial",
            "strategy", "order", "name", "validate_argspec",
        )

        /** `LoopControl` keywords with `always_post_validate=True`. */
        val LOOP_CONTROL_ALWAYS = setOf("loop_var", "index_var", "pause", "extended", "extended_allitems")

        val PLAY_TASK_LISTS = setOf("tasks", "pre_tasks", "post_tasks", "handlers")
        val BLOCK_TASK_LISTS = setOf("block", "rescue", "always")

        /** The orders `InventoryManager.get_hosts` accepts. */
        val HOST_ORDERS = setOf("inventory", "sorted", "reverse_sorted", "reverse_inventory", "shuffle")

        /** `Base._post_validate_debugger`'s valid values. */
        val DEBUGGER_MODES = listOf("always", "on_failed", "on_unreachable", "on_skipped", "never")

        /** Keywords whose attribute has another name, which 2.18's messages show (`async` is `async_val`). */
        val FIELD_NAMES = mapOf("async" to "async_val")
    }
}
