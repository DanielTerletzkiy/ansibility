package de.terletzkiy.ansibility.semantics.validate

import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.value.PyValue

/** The error classes `ArgumentSpecValidator.validate()` collects (`module_utils/errors.py`), in reporting order. */
enum class ErrorKind(val pyClass: String) {
    /** `_handle_aliases` failed; in practice "required and default are mutually exclusive" (a spec error). */
    ALIAS("AliasError"),

    /** `_list_no_log_values` could not walk a value (a date in a `no_log` option, a non-dict for a dict with options). */
    NO_LOG("NoLogError"),

    /** `_get_unsupported_parameters` raised `TypeError` at the top level. */
    REQUIRED_DEFAULT("RequiredDefaultError"),

    /** `check_required_arguments`: required options missing from a dict. */
    REQUIRED("RequiredError"),

    /** `_validate_argument_types`: the type check failed, or `elements` was given a non-list. */
    ARGUMENT_TYPE("ArgumentTypeError"),

    /** `_validate_elements`: one list element failed the `elements` check (it is dropped from the result). */
    ELEMENT("ElementError"),

    /** `_validate_argument_values`: the value (or a list item) is not among the `choices`. */
    ARGUMENT_VALUE("ArgumentValueError"),

    /** `_validate_sub_spec`: a value for a dict/list-of-dicts option with `options` is not a dict. */
    SUB_PARAMETER_TYPE("SubParameterTypeError"),

    /** Unknown keys (one error for all of them, appended last). */
    UNSUPPORTED("UnsupportedError"),
}

/**
 * One error of the ported validator.
 *
 * @property path where the offending value sits, as option names and list indices from the top-level parameters
 *   (for [ErrorKind.REQUIRED], the dict that lacks the keys; for [ErrorKind.UNSUPPORTED], see [unsupportedPaths])
 * @property names the missing options ([ErrorKind.REQUIRED]) or unsupported keys ([ErrorKind.UNSUPPORTED], flattened
 *   like ansible-core prints them)
 * @property option the option whose check failed, when there is one
 * @property valueWasNone the checked value was `None`
 * @property elementsShape an [ErrorKind.ARGUMENT_TYPE] about `elements` on a non-list, not a failed conversion
 * @property mismatchIndices for [ErrorKind.ARGUMENT_VALUE] on a list: the indices of the items outside the choices
 * @property unsupportedPaths for [ErrorKind.UNSUPPORTED]: the full path of every unsupported key
 */
data class ValidationError(
    val kind: ErrorKind,
    val message: String,
    val path: List<String>,
    val names: List<String> = emptyList(),
    val option: OptionSpec? = null,
    val valueWasNone: Boolean = false,
    val elementsShape: Boolean = false,
    val mismatchIndices: List<Int> = emptyList(),
    val unsupportedPaths: List<List<String>> = emptyList(),
) {
    /** The Python class name, e.g. `ArgumentTypeError`. */
    val errorClass: String get() = kind.pyClass
}

/** An exception that escapes `ArgumentSpecValidator.validate()` (the task fails with a traceback). */
data class ValidationCrash(val exceptionClass: String, val message: String, val path: List<String>)

/**
 * The result of [ArgumentSpecValidator.validate].
 *
 * @property errors the errors in ansible-core's order (an [ErrorKind.UNSUPPORTED] error, if any, is last)
 * @property validated `validated_parameters`: coerced values, defaults filled in (`None` for options without one);
 *   null when validation crashed
 * @property crash the escaping exception, if any; [errors] then holds what was collected before it
 * @property indeterminate paths whose checks depend on opaque values (vault, templates); nothing is reported there
 */
data class ArgSpecResult(
    val errors: List<ValidationError>,
    val validated: PyValue.Dict?,
    val crash: ValidationCrash?,
    val indeterminate: List<List<String>> = emptyList(),
) {
    /** ansible-core accepts the parameters (as far as this layer can know). */
    val accepted: Boolean get() = crash == null && errors.isEmpty()
}
