package de.terletzkiy.ansibility.semantics.coerce

import de.terletzkiy.ansibility.semantics.schema.OptionType
import de.terletzkiy.ansibility.semantics.value.IndeterminateValueException
import de.terletzkiy.ansibility.semantics.value.PyException
import de.terletzkiy.ansibility.semantics.value.PyRepr
import de.terletzkiy.ansibility.semantics.value.PyValue

/**
 * Line-by-line port of ansible-core's `module_utils/common/validation.py` `check_type_*` functions, i.e.
 * `DEFAULT_TYPE_VALIDATORS` as `ArgumentSpecValidator` calls them (`allow_conversion=True`).
 *
 * Each check returns [CheckResult.Accepted] with the value ansible-core continues with, [CheckResult.Rejected]
 * when ansible-core reports a validation error, [CheckResult.Crash] when the check raises something the validator
 * does not catch, or [CheckResult.Indeterminate] when the value is opaque (vault, template, unloadable).
 *
 * @property semantics the target core's version flags (`check_type_str(None)`, jsonarg dates …)
 * @property paths the environment `path` values are expanded against
 */
class CheckType(
    val semantics: CoreSemantics = CoreSemantics.PINNED,
    val paths: PathEnvironment = PathEnvironment.EMPTY,
) {
    /** `DEFAULT_TYPE_VALIDATORS[type](value)`; an [OptionType.Invalid] type has no checker and fails with `TypeError`. */
    fun check(type: OptionType, value: PyValue): CheckResult = CheckResult.of { convert(type, value) }

    /** `check_type_str`. */
    fun str(value: PyValue, allowConversion: Boolean = true): CheckResult = CheckResult.of { checkStr(value, allowConversion) }

    /** `check_type_bool`. */
    fun bool(value: PyValue): CheckResult = CheckResult.of { checkBool(value) }

    /** `check_type_int` (Decimal-based: `42.0`, `'42.0'`, `'1e3'` pass; `'inf'` crashes with `OverflowError`). */
    fun int(value: PyValue): CheckResult = CheckResult.of { checkInt(value) }

    /** `check_type_float`. */
    fun float(value: PyValue): CheckResult = CheckResult.of { checkFloat(value) }

    /** `check_type_list` (strings are split on commas, numbers wrapped). */
    fun list(value: PyValue): CheckResult = CheckResult.of { checkList(value) }

    /** `check_type_dict` (JSON, then `literal_eval`, for `{…}` strings; `k=v` strings). */
    fun dict(value: PyValue): CheckResult = CheckResult.of { checkDict(value) }

    /** `check_type_path`: `str`, then `expanduser(expandvars(…))` against [paths]. */
    fun path(value: PyValue): CheckResult = CheckResult.of { checkPath(value) }

    /** `check_type_raw`: the value unchanged. */
    fun raw(value: PyValue): CheckResult = CheckResult.Accepted(value)

    /** `check_type_jsonarg` (also the `json` type). */
    fun jsonArg(value: PyValue): CheckResult = CheckResult.of { checkJsonArg(value) }

    /** `check_type_bytes`. */
    fun bytes(value: PyValue): CheckResult = CheckResult.of { checkBytes(value, isBits = false) }

    /** `check_type_bits`. */
    fun bits(value: PyValue): CheckResult = CheckResult.of { checkBytes(value, isBits = true) }

    /** The checker for [type], raising [PyException]/[IndeterminateValueException] like the Python function. */
    internal fun convert(type: OptionType, value: PyValue): PyValue = when (type) {
        OptionType.Str -> checkStr(value, true)
        OptionType.Bool -> checkBool(value)
        OptionType.Int -> checkInt(value)
        OptionType.Float -> checkFloat(value)
        OptionType.List -> checkList(value)
        OptionType.Dict -> checkDict(value)
        OptionType.Path -> checkPath(value)
        OptionType.Raw -> value
        OptionType.JsonArg, OptionType.Json -> checkJsonArg(value)
        OptionType.Bytes -> checkBytes(value, isBits = false)
        OptionType.Bits -> checkBytes(value, isBits = true)
        is OptionType.Invalid -> throw PyException.typeError("'NoneType' object is not callable")
    }

    private fun opaque(value: PyValue): Boolean =
        value == PyValue.Vault || value is PyValue.Templated || value == PyValue.Unloadable

    internal fun checkStr(value: PyValue, allowConversion: Boolean): PyValue {
        if (value is PyValue.Str || value == PyValue.Vault) return value
        if (opaque(value)) throw IndeterminateValueException(value)
        if (value == PyValue.None) {
            if (semantics.strRejectsNone) throw PyException.typeError("'None' is not a string and conversion is not allowed")
            return PyValue.Str("")
        }
        if (allowConversion) return PyValue.Str(PyRepr.str(value))
        throw PyException.typeError("'${PyRepr.repr(value)}' is not a string and conversion is not allowed")
    }

    internal fun checkList(value: PyValue): PyValue = when (value) {
        is PyValue.List -> value
        is PyValue.Str -> PyValue.List(value.value.split(",").map { PyValue.Str(it) })
        is PyValue.Int, is PyValue.Float, is PyValue.Bool -> PyValue.List(listOf(PyValue.Str(PyRepr.str(value))))
        PyValue.Vault, is PyValue.Templated, PyValue.Unloadable -> throw IndeterminateValueException(value)
        else -> throw PyException.typeError("${pyClass(value)} cannot be converted to a list")
    }

    internal fun checkDict(value: PyValue): PyValue {
        if (value is PyValue.Dict) return value
        if (opaque(value)) throw IndeterminateValueException(value)
        if (value !is PyValue.Str) throw PyException.typeError("${pyClass(value)} cannot be converted to a dict")
        val text = value.value
        return when {
            text.startsWith("{") -> try {
                PyJson.loads(text)
            } catch (json: PyException) {
                try {
                    PyLiteral.evalDict(text)
                } catch (literal: PyException) {
                    throw PyException.typeError("unable to evaluate string as dictionary")
                }
            }
            '=' in text -> keyValueDict(text)
            else -> throw PyException.typeError("dictionary requested, could not parse JSON or key=value")
        }
    }

    /** The `k1=v1, k2=v2` / `k1=v1 k2=v2` parser of `check_type_dict` (quotes group, backslash escapes). */
    private fun keyValueDict(text: String): PyValue {
        val fields = mutableListOf<String>()
        val buffer = StringBuilder()
        var inQuote: Int? = null
        var inEscape = false
        val stripped = PyNumbers.strip(text)
        var i = 0
        while (i < stripped.length) {
            val c = stripped.codePointAt(i)
            i += Character.charCount(c)
            when {
                inEscape -> {
                    buffer.appendCodePoint(c)
                    inEscape = false
                }
                c == '\\'.code -> inEscape = true
                inQuote == null && (c == '\''.code || c == '"'.code) -> inQuote = c
                inQuote != null && inQuote == c -> inQuote = null
                inQuote == null && (c == ','.code || c == ' '.code) -> {
                    if (buffer.isNotEmpty()) fields += buffer.toString()
                    buffer.setLength(0)
                }
                else -> buffer.appendCodePoint(c)
            }
        }
        if (buffer.isNotEmpty()) fields += buffer.toString()
        val pairs = fields.map { field ->
            val eq = field.indexOf('=')
            if (eq < 0) throw PyException.typeError("unable to evaluate string in the \"key=value\" format as dictionary")
            PyValue.Str(field.substring(0, eq)) to PyValue.Str(field.substring(eq + 1))
        }
        return PyValue.Dict.of(pairs)
    }

    internal fun checkBool(value: PyValue): PyValue = when (value) {
        is PyValue.Bool -> value
        is PyValue.Str, is PyValue.Int, is PyValue.Float -> PyValue.Bool(Booleans.convert(value, strict = true, semantics))
        PyValue.Vault, is PyValue.Templated, PyValue.Unloadable -> throw IndeterminateValueException(value)
        else -> throw PyException.typeError("${pyClass(value)} cannot be converted to a bool")
    }

    internal fun checkInt(value: PyValue): PyValue {
        if (value is PyValue.Int || value is PyValue.Bool) return value
        if (opaque(value)) throw IndeterminateValueException(value)
        try {
            val integral = PyNumbers.integralValue(PyNumbers.decimal(value))
                ?: throw PyException.valueError("Significant decimal part found")
            return PyValue.Int(integral)
        } catch (e: PyException) {
            if (e.pyClass !in INT_CAUGHT) throw e
            throw PyException.typeError("\"${PyRepr.repr(value)}\" cannot be converted to an int")
        }
    }

    internal fun checkFloat(value: PyValue): PyValue {
        if (value is PyValue.Float) return value
        if (opaque(value)) throw IndeterminateValueException(value)
        try {
            return PyValue.Float(
                when (value) {
                    is PyValue.Bool -> if (value.value) 1.0 else 0.0
                    is PyValue.Int -> PyNumbers.floatFromInt(value.value)
                    is PyValue.Str -> PyNumbers.floatFromString(value.value)
                    else -> throw PyException.typeError("float() argument must be a string or a real number, not '${value.typeName}'")
                },
            )
        } catch (e: PyException) {
            if (e.pyClass !in CheckResult.CAUGHT) throw e
            throw PyException.typeError("${pyClass(value)} cannot be converted to a float")
        }
    }

    internal fun checkPath(value: PyValue): PyValue = when (val text = checkStr(value, true)) {
        is PyValue.Str -> PyValue.Str(paths.expandUser(paths.expandVars(text.value)))
        else -> text // vault: some str we cannot expand
    }

    internal fun checkJsonArg(value: PyValue): PyValue = when (value) {
        is PyValue.Str -> PyValue.Str(PyNumbers.strip(value.value))
        PyValue.Vault -> value
        is PyValue.List, is PyValue.Dict -> PyValue.Str(PyJson.dumps(value, ::encodeDate))
        is PyValue.Templated, PyValue.Unloadable -> throw IndeterminateValueException(value)
        else -> throw PyException.typeError("${pyClass(value)} cannot be converted to a json string")
    }

    /** ≤ 2.18 `jsonify`'s fallback serialises only datetimes; 2.19+'s legacy encoder handles dates too. */
    private fun encodeDate(date: PyValue.Date): String {
        if (semantics.jsonargEncodesDates || date.timestamp.isDateTime) return date.timestamp.isoFormat()
        throw PyException.typeError("Cannot json serialize ${date.timestamp.pyStr()}")
    }

    internal fun checkBytes(value: PyValue, isBits: Boolean): PyValue {
        try {
            return PyValue.Int(HumanToBytes.parse(value, null, isBits))
        } catch (e: PyException) {
            if (e.pyClass != "ValueError") throw e
            throw PyException.typeError("${pyClass(value)} cannot be converted to a ${if (isBits) "Bit" else "Byte"} value")
        }
    }

    companion object {
        private val INT_CAUGHT = setOf("TypeError", "ValueError", "InvalidOperation")

        /** `str(type(value))`, e.g. `<class 'dict'>`, `<class 'datetime.date'>`. */
        internal fun pyClass(value: PyValue): String = when (value) {
            is PyValue.Date -> "<class 'datetime.${value.typeName}'>"
            else -> "<class '${value.typeName}'>"
        }
    }
}
