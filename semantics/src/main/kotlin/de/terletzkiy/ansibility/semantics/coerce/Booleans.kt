package de.terletzkiy.ansibility.semantics.coerce

import de.terletzkiy.ansibility.semantics.value.IndeterminateValueException
import de.terletzkiy.ansibility.semantics.value.PyException
import de.terletzkiy.ansibility.semantics.value.PyRepr
import de.terletzkiy.ansibility.semantics.value.PyValue
import de.terletzkiy.ansibility.semantics.value.isHashable
import java.math.BigInteger

/** Port of `module_utils/parsing/convert_bool.py`. */
object Booleans {
    /** `BOOLEANS_TRUE`'s string members; the numeric members are `1`, `1.0` and `True`. */
    val TRUE_STRINGS: Set<String> = setOf("y", "yes", "on", "1", "true", "t")

    /** `BOOLEANS_FALSE`'s string members; the numeric members are `0`, `0.0` and `False`. */
    val FALSE_STRINGS: Set<String> = setOf("n", "no", "off", "0", "false", "f")

    /** `repr` of every member of `BOOLEANS`, as the error message lists them (set order is arbitrary in Python). */
    private const val VALID = "'y', 'yes', 'on', '1', 'true', 't', 1, 1.0, True, 'n', 'no', 'off', '0', 'false', 'f', 0, 0.0, False"

    /**
     * `boolean(value, strict)`: a bool is returned as is; strings are lower-cased and stripped; the result is
     * membership in `BOOLEANS_TRUE`/`BOOLEANS_FALSE` (numbers compare by value, so `1.0` and `-0.0` count).
     * Anything else raises `TypeError` when [strict], and is `False` otherwise.
     */
    fun boolean(value: PyValue, strict: Boolean = true, semantics: CoreSemantics = CoreSemantics.PINNED): CheckResult =
        CheckResult.of(setOf("TypeError")) { PyValue.Bool(convert(value, strict, semantics)) }

    internal fun convert(value: PyValue, strict: Boolean, semantics: CoreSemantics): Boolean {
        if (value is PyValue.Bool) return value.value
        if (value == PyValue.Vault || value is PyValue.Templated || value == PyValue.Unloadable) {
            throw IndeterminateValueException(value)
        }
        var normalized: PyValue? = value
        if (value is PyValue.Str) normalized = PyValue.Str(PyNumbers.strip(value.value.lowercase()))
        if (!value.isHashable) {
            if (!semantics.booleanToleratesUnhashable) throw PyException.typeError("unhashable type: '${value.typeName}'")
            normalized = null
        }
        return when {
            normalized != null && isMember(normalized, true) -> true
            (normalized != null && isMember(normalized, false)) || !strict -> false
            else -> throw PyException.typeError("The value '${PyRepr.str(value)}' is not a valid boolean. Valid booleans include: $VALID")
        }
    }

    /** Whether [value] is in `BOOLEANS_TRUE` ([truth]) or `BOOLEANS_FALSE`, with Python's set semantics. */
    internal fun isMember(value: PyValue, truth: Boolean): Boolean = when (value) {
        is PyValue.Str -> value.value in (if (truth) TRUE_STRINGS else FALSE_STRINGS)
        is PyValue.Bool -> value.value == truth
        is PyValue.Int -> value.value == if (truth) BigInteger.ONE else BigInteger.ZERO
        is PyValue.Float -> value.value == if (truth) 1.0 else 0.0
        else -> false
    }
}
