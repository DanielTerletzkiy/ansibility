package de.terletzkiy.ansibility.semantics.value

import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.YEmpty
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.semantics.yaml.YVault
import java.math.BigDecimal
import java.math.BigInteger

/**
 * A Python value as ansible-core holds it after loading YAML with PyYAML (YAML 1.1): the input and the output of
 * the `check_type_*` port.
 *
 * Kotlin `equals` is structural. Python's `==` (where `1 == 1.0 == True`, `nan != nan` and dicts compare without
 * order) is [pyEquals]; [pyHash] is consistent with it.
 */
sealed interface PyValue {
    /** The Python type name, as `type(value).__name__` prints it (`NoneType`, `int`, `date` …). */
    val typeName: String

    /** `None` (YAML `~`, `null`, or an empty value). */
    data object None : PyValue {
        override val typeName: String get() = "NoneType"
    }

    /** `True` / `False`. Python's bool is a subclass of int. */
    data class Bool(val value: Boolean) : PyValue {
        override val typeName: String get() = "bool"
    }

    /** An arbitrary-precision `int`. */
    data class Int(val value: BigInteger) : PyValue {
        constructor(value: Long) : this(BigInteger.valueOf(value))

        override val typeName: String get() = "int"
    }

    /** A `float` (IEEE double, like CPython). */
    data class Float(val value: Double) : PyValue {
        override val typeName: String get() = "float"
    }

    /** A `str`. */
    data class Str(val value: String) : PyValue {
        override val typeName: String get() = "str"
    }

    /**
     * A `datetime.date` or `datetime.datetime` built by PyYAML's timestamp constructor from [text]
     * (which must match the YAML 1.1 timestamp pattern and name a valid date; see [PyTimestamp.parse]).
     * Two spellings of the same date are equal, as in Python.
     */
    class Date(val text: String) : PyValue {
        /** The constructed value. */
        val timestamp: PyTimestamp = requireNotNull(PyTimestamp.parse(text)) { "not a constructible YAML timestamp: $text" }

        override val typeName: String get() = if (timestamp.isDateTime) "datetime" else "date"

        override fun equals(other: Any?): Boolean = other is Date && other.timestamp == timestamp

        override fun hashCode(): kotlin.Int = timestamp.hashCode()

        override fun toString(): String = "Date($text)"
    }

    /** A `list`. */
    data class List(val items: kotlin.collections.List<PyValue>) : PyValue {
        override val typeName: String get() = "list"
    }

    /**
     * An insertion-ordered `dict`. Keys are hashable scalars; [of] merges keys that Python considers equal
     * (`{1: a, 1.0: b}` is `{1: 'b'}`: the first key object stays, the last value wins).
     */
    class Dict internal constructor(val entries: kotlin.collections.List<Pair<PyValue, PyValue>>) : PyValue {
        override val typeName: String get() = "dict"

        val keys: kotlin.collections.List<PyValue> get() = entries.map { it.first }

        /** The value stored under the `str` key [key], if any. */
        operator fun get(key: String): PyValue? = get(Str(key))

        /** The value stored under a key Python considers equal to [key], if any. */
        operator fun get(key: PyValue): PyValue? = entries.firstOrNull { it.first.pyEquals(key) }?.second

        fun containsKey(key: String): Boolean = get(key) != null

        override fun equals(other: Any?): Boolean = other is Dict && other.entries == entries

        override fun hashCode(): kotlin.Int = entries.hashCode()

        override fun toString(): String = "Dict($entries)"

        companion object {
            /** Builds a dict the way Python's `d[k] = v` does for each pair in order. Keys must be hashable. */
            fun of(pairs: Iterable<Pair<PyValue, PyValue>>): Dict {
                val map = LinkedHashMap<PyKey, PyValue>()
                for ((key, value) in pairs) {
                    require(key.isHashable) { "unhashable dict key: ${key.typeName}" }
                    map[PyKey(key)] = value // an equal existing key keeps its original object, as in Python
                }
                return Dict(map.entries.map { it.key.value to it.value })
            }

            fun of(vararg pairs: Pair<String, PyValue>): Dict = of(pairs.map { Str(it.first) to it.second })

            val EMPTY: Dict = Dict(emptyList())
        }
    }

    /** A `!vault` value. It decrypts to a `str` whose content this layer never sees. */
    data object Vault : PyValue {
        override val typeName: String get() = "str"
    }

    /**
     * A scalar PyYAML cannot construct, so the whole file fails to load: a bare `=`, a timestamp naming an
     * impossible date, or a decimal integer longer than Python's 4300-digit conversion limit.
     */
    data object Unloadable : PyValue {
        override val typeName: String get() = "<unloadable>"
    }

    /**
     * A Jinja-bearing string. ansible-core renders it before validating, so its runtime value (and even its type)
     * is unknown here; every check on it is indeterminate. The YAML loader itself never produces this: callers
     * opt in through [fromYValue]'s `isTemplated` predicate.
     */
    data class Templated(val text: String) : PyValue {
        override val typeName: String get() = "<template>"
    }

    companion object {
        /** Python's default `sys.get_int_max_str_digits()`: longer decimal strings do not convert to int. */
        const val INT_MAX_STR_DIGITS: kotlin.Int = 4300

        /**
         * The value ansible-core's loader produces for [value] (PyYAML's constructors on top of
         * [YScalar.resolved]). Scalars for which [isTemplated] is true become [Templated].
         */
        fun fromYValue(value: YValue, isTemplated: (YScalar) -> Boolean = { false }): PyValue = when (value) {
            is YEmpty -> None
            is YVault -> Vault
            is YScalar -> if (isTemplated(value)) Templated(value.text) else fromScalar(value)
            is YSeq -> List(value.items.map { fromYValue(it, isTemplated) })
            is YMap -> fromMap(value, isTemplated)
        }

        private fun fromScalar(scalar: YScalar): PyValue = when (val resolved = scalar.resolved) {
            Resolved.Null -> None
            is Resolved.Bool -> Bool(resolved.value)
            is Resolved.Int -> if (exceedsDecimalDigitLimit(scalar)) Unloadable else Int(resolved.value)
            is Resolved.Float -> Float(resolved.value)
            is Resolved.Str -> Str(resolved.value)
            is Resolved.Timestamp -> if (PyTimestamp.parse(resolved.text) != null) Date(resolved.text) else Unloadable
            Resolved.Unloadable -> Unloadable
        }

        /** PyYAML runs `int(text)` for decimal spellings, which Python refuses beyond [INT_MAX_STR_DIGITS] digits. */
        private fun exceedsDecimalDigitLimit(scalar: YScalar): Boolean {
            val digits = scalar.text.replace("_", "").removePrefix("-").removePrefix("+")
            // Octal, hex, binary (leading 0) and sexagesimal spellings convert in pieces or in a power-of-two base.
            return digits.length > INT_MAX_STR_DIGITS && !digits.startsWith("0") && ':' !in digits
        }

        /** Keys are scalars, hence hashable; an unloadable key makes the mapping (and the file) unloadable. */
        private fun fromMap(map: YMap, isTemplated: (YScalar) -> Boolean): PyValue {
            val pairs = map.entries.map { fromYValue(it.key, isTemplated) to fromYValue(it.value, isTemplated) }
            return if (pairs.any { it.first == Unloadable }) Unloadable else Dict.of(pairs)
        }
    }
}

/** Whether Python can hash this value (containers cannot). */
val PyValue.isHashable: Boolean
    get() = this !is PyValue.List && this !is PyValue.Dict

/** Python truthiness (`bool(value)`); indeterminate values count as true. */
val PyValue.isTruthy: Boolean
    get() = when (this) {
        PyValue.None -> false
        is PyValue.Bool -> value
        is PyValue.Int -> value.signum() != 0
        is PyValue.Float -> value != 0.0
        is PyValue.Str -> value.isNotEmpty()
        is PyValue.List -> items.isNotEmpty()
        is PyValue.Dict -> entries.isNotEmpty()
        else -> true
    }

/**
 * Python's `==`: numbers compare by value across bool/int/float, `nan` equals nothing, `str` equals only `str`,
 * lists compare element-wise, dicts by key set and values, dates by value (a date never equals a datetime, and a
 * naive datetime never equals an aware one). Opaque values ([PyValue.Vault], [PyValue.Templated],
 * [PyValue.Unloadable]) equal nothing.
 */
fun PyValue.pyEquals(other: PyValue): Boolean {
    val a = numericValue(this)
    val b = numericValue(other)
    if (a != null || b != null) return a != null && b != null && a.compareTo(b) == 0
    return when (this) {
        PyValue.None -> other == PyValue.None
        is PyValue.Str -> other is PyValue.Str && other.value == value
        is PyValue.Date -> other is PyValue.Date && timestamp.pyEquals(other.timestamp)
        is PyValue.List -> other is PyValue.List && other.items.size == items.size &&
            items.indices.all { items[it].pyEquals(other.items[it]) }
        is PyValue.Dict -> other is PyValue.Dict && other.entries.size == entries.size &&
            entries.all { (k, v) -> other[k]?.pyEquals(v) == true }
        else -> false
    }
}

/** A hash consistent with [pyEquals] (for hashable values). */
fun PyValue.pyHash(): Int {
    numericValue(this)?.let { return it.stripTrailingZerosSafe().hashCode() }
    return when (this) {
        PyValue.None -> 0
        is PyValue.Str -> value.hashCode()
        is PyValue.Date -> timestamp.pyHash()
        is PyValue.Float -> System.identityHashCode(this) // NaN only: equal to nothing
        else -> System.identityHashCode(this)
    }
}

/** The exact numeric value of a bool/int/float (infinities as far-away sentinels), or null (not a number, or NaN). */
private fun numericValue(value: PyValue): BigDecimal? = when (value) {
    is PyValue.Bool -> if (value.value) BigDecimal.ONE else BigDecimal.ZERO
    is PyValue.Int -> BigDecimal(value.value)
    is PyValue.Float -> when {
        value.value.isNaN() -> null
        value.value == Double.POSITIVE_INFINITY -> POSITIVE_INFINITY
        value.value == Double.NEGATIVE_INFINITY -> NEGATIVE_INFINITY
        else -> BigDecimal(value.value)
    }
    else -> null
}

// Beyond any int this layer can hold (see CheckType's integer size cap), so infinities compare only to themselves.
private val POSITIVE_INFINITY = BigDecimal("1e999999999")
private val NEGATIVE_INFINITY = BigDecimal("-1e999999999")

private fun BigDecimal.stripTrailingZerosSafe(): BigDecimal = if (signum() == 0) BigDecimal.ZERO else stripTrailingZeros()

/** Wraps a hashable [PyValue] so Kotlin collections use Python's key semantics. */
internal class PyKey(val value: PyValue) {
    override fun equals(other: Any?): Boolean = other is PyKey && value.pyEquals(other.value)

    override fun hashCode(): Int = value.pyHash()

    override fun toString(): String = value.toString()
}
