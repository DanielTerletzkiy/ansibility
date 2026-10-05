package de.terletzkiy.ansibility.semantics.render

import de.terletzkiy.ansibility.semantics.value.PyRepr
import de.terletzkiy.ansibility.semantics.value.PyValue
import java.math.BigInteger

/**
 * Why a value is not known (plan amendment R11, F11.5): the one vocabulary of hover, the preview and inlays. A
 * placeholder names its source and never a secret's content, length or hash.
 */
data class Placeholder(val kind: Kind, val label: String) {
    enum class Kind {
        /** A vault value, a `vault_*` name or a value from a vault file, or anything derived from one. */
        SECRET,

        /** A registered result, a `set_fact` that runs first, an `include_vars` target. */
        RUNTIME,

        /** A gathered fact. */
        FACT,

        /** A lookup that would execute, a filter, test or method the renderer does not implement. */
        NOT_EMULATED,

        /** Controller state (`template_host`, `template_run_date` …). */
        CONTROLLER,

        /** `omit`. */
        OMIT,

        /** No definition is proven, but something may set the name (extra vars, templated `vars_files`). */
        UNKNOWN,

        /** A render budget was exceeded. */
        BUDGET,
    }

    /** The marker printed in place of the value. */
    val text: String
        get() = when (kind) {
            Kind.SECRET -> "⟨\uD83D\uDD12 $label⟩"
            Kind.RUNTIME -> "⟨runtime: $label⟩"
            Kind.FACT -> "⟨fact: $label⟩"
            Kind.NOT_EMULATED -> "⟨not emulated: $label⟩"
            Kind.CONTROLLER -> "⟨controller: $label⟩"
            Kind.OMIT -> "⟨omit⟩"
            Kind.UNKNOWN -> "⟨$label⟩"
            Kind.BUDGET -> "⟨budget: $label⟩"
        }

    override fun toString(): String = text

    companion object {
        fun secret(name: String) = Placeholder(Kind.SECRET, name)
        fun notEmulated(what: String) = Placeholder(Kind.NOT_EMULATED, what)
        fun unknown(what: String) = Placeholder(Kind.UNKNOWN, what)
    }
}

/**
 * A runtime value of the renderer: Python data plus Jinja's own objects and the [Hole] that stands for anything
 * unproven. Lists and dicts are mutable inside one render (`l.append(x)`); bindings are converted afresh for every
 * render, so a mutation never reaches the binding.
 */
sealed interface RValue {
    /** The Python type name (`str`, `list`, `NoneType` …), for messages. */
    val typeName: String

    data object None : RValue {
        override val typeName: String get() = "NoneType"
    }

    data class Bool(val value: Boolean) : RValue {
        override val typeName: String get() = "bool"
    }

    data class Int(val value: BigInteger) : RValue {
        constructor(value: Long) : this(BigInteger.valueOf(value))

        override val typeName: String get() = "int"
    }

    data class Float(val value: Double) : RValue {
        override val typeName: String get() = "float"
    }

    /**
     * A string; [native] marks filter results that classic templating never `literal_eval`s (`to_json`, `string`).
     * [origin] is 2.18's class of a loaded string (`AnsibleUnicode`, `AnsibleUnsafeText`), which `type_debug` shows.
     */
    data class Str(val value: String, val native: Boolean = false, val origin: String? = null) : RValue {
        override val typeName: String get() = "str"
        override fun equals(other: Any?): Boolean = other is Str && other.value == value
        override fun hashCode(): kotlin.Int = value.hashCode()
    }

    /** A `date`/`datetime` loaded from YAML; it prints in ISO form. */
    data class Date(val value: PyValue.Date) : RValue {
        override val typeName: String get() = value.typeName
    }

    /** A list; [range] marks the result of `range()` itself, which 2.19+ refuses to print or store. */
    class List(val items: MutableList<RValue>, val range: Boolean = false) : RValue {
        constructor(items: Iterable<RValue>) : this(items.toMutableList())

        override val typeName: String get() = if (range) "range" else "list"
        override fun toString(): String = "List($items)"
    }

    class Tuple(val items: kotlin.collections.List<RValue>) : RValue {
        override val typeName: String get() = "tuple"
        override fun toString(): String = "Tuple($items)"
    }

    /** An insertion-ordered dict; keys compare as Python compares them ([RKey]). */
    class Dict(val map: LinkedHashMap<RKey, RValue> = LinkedHashMap()) : RValue {
        override val typeName: String get() = "dict"

        operator fun get(key: String): RValue? = map[RKey(Str(key))]
        operator fun get(key: RValue): RValue? = map[RKey(key)]
        operator fun set(key: String, value: RValue) {
            map[RKey(Str(key))] = value
        }
        operator fun set(key: RValue, value: RValue) {
            map[RKey(key)] = value
        }

        val keys: kotlin.collections.List<RValue> get() = map.keys.map { it.value }
        val entries: kotlin.collections.List<Pair<RValue, RValue>> get() = map.entries.map { it.key.value to it.value }

        override fun toString(): String = "Dict($entries)"

        companion object {
            fun of(pairs: Iterable<Pair<RValue, RValue>>): Dict = Dict().also { d -> pairs.forEach { (k, v) -> d[k] = v } }
            fun ofStrings(vararg pairs: Pair<String, RValue>): Dict = Dict().also { d -> pairs.forEach { (k, v) -> d[k] = v } }
        }
    }

    /**
     * An undefined value: a name nothing defines, or a missing attribute or item. Attribute chains stay undefined
     * until the value is used (printed, compared, iterated), which raises [message].
     */
    /** [lenient]: Jinja's plain `Undefined` (an inline if without else), which 2.18 prints as an empty string. */
    class Undefined(val name: String, val message: String, val lenient: Boolean = false) : RValue {
        override val typeName: String get() = "AnsibleUndefined"
        override fun toString(): String = "Undefined($message)"

        companion object {
            fun name(name: String) = Undefined(name, "'$name' is undefined")
        }
    }

    /** An unproven value. Every operation on it gives a hole, every branch on it is unknown. */
    class Hole(val placeholder: Placeholder) : RValue {
        override val typeName: String get() = "<unknown>"
        override fun toString(): String = "Hole($placeholder)"
    }

    /** A callable: a global (`range`, `lookup`), a bound method (`'a'.split`), a macro or `loop`/`caller`. */
    class Callable(
        val name: String,
        /** The Python type a bound builtin method belongs to (`dict` for `d.items`). */
        val owner: String? = null,
        val call: (args: kotlin.collections.List<RValue>, kwargs: Map<String, RValue>) -> RValue,
    ) : RValue {
        override val typeName: String get() = if (owner != null) "builtin_function_or_method" else "function"
        override fun toString(): String = "Callable($name)"
    }

    /** An object with attributes only: `loop`, `namespace(...)`, an imported template module. */
    class Obj(val kind: String, val attributes: LinkedHashMap<String, RValue> = LinkedHashMap(), val mutable: Boolean = false) : RValue {
        override val typeName: String get() = kind
        override fun toString(): String = "Obj($kind, ${attributes.keys})"
    }
}

/** A dict key with Python's equality (`1 == 1.0 == True`). */
class RKey(val value: RValue) {
    override fun equals(other: Any?): Boolean = other is RKey && RValues.pyEquals(value, other.value)

    override fun hashCode(): kotlin.Int = RValues.pyHash(value)

    override fun toString(): String = value.toString()
}

/** Raised by an operation ansible-core would fail on; the message is ansible-core's (no secret ever, see [RValues]). */
class RenderError(message: String) : RuntimeException(message, null, false, false)

/** Thrown out of an operation whose operand is a hole: the caller turns it into that hole. */
internal class HoleSignal(val hole: RValue.Hole) : RuntimeException(null, null, false, false)

/** Values, conversions and Python semantics shared by the evaluator, filters and tests. */
object RValues {
    val TRUE = RValue.Bool(true)
    val FALSE = RValue.Bool(false)
    val EMPTY = RValue.Str("")

    fun bool(value: Boolean): RValue.Bool = if (value) TRUE else FALSE

    fun int(value: kotlin.Int): RValue.Int = RValue.Int(value.toLong())

    fun str(value: String): RValue.Str = RValue.Str(value)

    fun list(items: Iterable<RValue>): RValue.List = RValue.List(items)

    /** The data value of a loaded YAML/Python value; vault and unloadable values must be filtered out by the caller. */
    fun fromPy(value: PyValue): RValue = when (value) {
        PyValue.None -> RValue.None
        is PyValue.Bool -> bool(value.value)
        is PyValue.Int -> RValue.Int(value.value)
        is PyValue.Float -> RValue.Float(value.value)
        is PyValue.Str -> RValue.Str(value.value)
        is PyValue.Date -> RValue.Date(value)
        is PyValue.List -> RValue.List(value.items.map(::fromPy))
        is PyValue.Dict -> RValue.Dict.of(value.entries.map { fromPy(it.first) to fromPy(it.second) })
        PyValue.Vault -> RValue.Hole(Placeholder.secret("vault value"))
        PyValue.Unloadable -> RValue.Hole(Placeholder.unknown("unloadable value"))
        is PyValue.Templated -> RValue.Str(value.text)
    }

    /** The first hole among [values], a secret one preferred, or null. */
    fun firstHole(vararg values: RValue): RValue.Hole? = firstHole(values.asList())

    fun firstHole(values: Iterable<RValue>): RValue.Hole? {
        var first: RValue.Hole? = null
        for (value in values) {
            val hole = holeIn(value) ?: continue
            if (hole.placeholder.kind == Placeholder.Kind.SECRET) return hole
            if (first == null) first = hole
        }
        return first
    }

    /** A hole anywhere inside [value] (containers searched), or null. */
    fun holeIn(value: RValue, depth: kotlin.Int = 0): RValue.Hole? = when (value) {
        is RValue.Hole -> value
        is RValue.List -> if (depth > 32) null else firstHoleDeep(value.items, depth)
        is RValue.Tuple -> if (depth > 32) null else firstHoleDeep(value.items, depth)
        is RValue.Dict -> if (depth > 32) null else firstHoleDeep(value.map.values + value.keys, depth)
        else -> null
    }

    private fun firstHoleDeep(values: Collection<RValue>, depth: kotlin.Int): RValue.Hole? {
        var first: RValue.Hole? = null
        for (value in values) {
            val hole = holeIn(value, depth + 1) ?: continue
            if (hole.placeholder.kind == Placeholder.Kind.SECRET) return hole
            if (first == null) first = hole
        }
        return first
    }

    /** Throws the hole of [values] (see [HoleSignal]) when there is one. */
    internal fun requireKnown(vararg values: RValue) {
        firstHole(values.asList())?.let { throw HoleSignal(it) }
    }

    /** Raises the error of an undefined value that is used. */
    fun requireDefined(value: RValue): RValue {
        if (value is RValue.Undefined) throw RenderError(value.message)
        return value
    }

    // ------------------------------------------------------------------------------------------------ printing

    /**
     * `str(value)` as Jinja's output and `~` print it; [tuplesAsLists] is 2.19+'s printing of tuples. A hole prints as
     * its marker, so a container with a hole prints its known parts.
     */
    fun str(value: RValue, tuplesAsLists: Boolean): String = when (value) {
        is RValue.Str -> value.value
        is RValue.Date -> PyRepr.str(value.value)
        is RValue.Hole -> value.placeholder.text
        is RValue.Undefined -> throw RenderError(value.message)
        else -> repr(value, tuplesAsLists)
    }

    /** `repr(value)`. */
    fun repr(value: RValue, tuplesAsLists: Boolean): String = when (value) {
        RValue.None -> "None"
        is RValue.Bool -> if (value.value) "True" else "False"
        is RValue.Int -> value.value.toString()
        is RValue.Float -> PyRepr.floatRepr(value.value)
        is RValue.Str -> PyRepr.strRepr(value.value)
        is RValue.Date -> PyRepr.repr(value.value)
        is RValue.List -> value.items.joinToString(", ", "[", "]") { repr(it, tuplesAsLists) }
        is RValue.Tuple -> when {
            tuplesAsLists -> value.items.joinToString(", ", "[", "]") { repr(it, tuplesAsLists) }
            value.items.size == 1 -> "(" + repr(value.items[0], tuplesAsLists) + ",)"
            else -> value.items.joinToString(", ", "(", ")") { repr(it, tuplesAsLists) }
        }
        is RValue.Dict -> value.entries.joinToString(", ", "{", "}") { (k, v) -> repr(k, tuplesAsLists) + ": " + repr(v, tuplesAsLists) }
        is RValue.Hole -> value.placeholder.text
        is RValue.Undefined -> throw RenderError(value.message)
        is RValue.Callable -> if (value.owner != null) "<built-in method ${value.name} of ${value.owner} object at 0x...>" else "<function ${value.name}>"
        is RValue.Obj -> when (value.kind) {
            "Namespace" -> "<Namespace " + value.attributes.entries.joinToString(", ", "{", "}") { (k, v) -> PyRepr.strRepr(k) + ": " + repr(v, tuplesAsLists) } + ">"
            else -> "<${value.kind}>"
        }
    }

    // ------------------------------------------------------------------------------------------------ semantics

    /** Python truthiness; a hole has none (callers branch on [HoleSignal]). */
    fun truthy(value: RValue): Boolean = when (value) {
        RValue.None -> false
        is RValue.Bool -> value.value
        is RValue.Int -> value.value.signum() != 0
        is RValue.Float -> value.value != 0.0
        is RValue.Str -> value.value.isNotEmpty()
        is RValue.List -> value.items.isNotEmpty()
        is RValue.Tuple -> value.items.isNotEmpty()
        is RValue.Dict -> value.map.isNotEmpty()
        is RValue.Undefined -> throw RenderError(value.message)
        is RValue.Hole -> throw HoleSignal(value)
        else -> true
    }

    /** The numeric value of bool/int/float, or null. */
    fun number(value: RValue): Number? = when (value) {
        is RValue.Bool -> if (value.value) BigInteger.ONE else BigInteger.ZERO
        is RValue.Int -> value.value
        is RValue.Float -> value.value
        else -> null
    }

    fun pyEquals(a: RValue, b: RValue): Boolean {
        val x = number(a)
        val y = number(b)
        if (x != null || y != null) {
            if (x == null || y == null) return false
            return compareNumbers(x, y) == 0 && !(x is Double && x.isNaN()) && !(y is Double && y.isNaN())
        }
        return when (a) {
            RValue.None -> b == RValue.None
            is RValue.Str -> b is RValue.Str && a.value == b.value
            is RValue.Date -> b is RValue.Date && a.value == b.value
            is RValue.List -> b is RValue.List && seqEquals(a.items, b.items)
            is RValue.Tuple -> b is RValue.Tuple && seqEquals(a.items, b.items)
            is RValue.Dict -> b is RValue.Dict && a.map.size == b.map.size && a.map.all { (k, v) -> b.map[k]?.let { pyEquals(v, it) } == true }
            else -> a === b
        }
    }

    private fun seqEquals(a: kotlin.collections.List<RValue>, b: kotlin.collections.List<RValue>): Boolean =
        a.size == b.size && a.indices.all { pyEquals(a[it], b[it]) }

    fun pyHash(value: RValue): kotlin.Int = when (val n = number(value)) {
        null -> when (value) {
            RValue.None -> 0
            is RValue.Str -> value.value.hashCode()
            is RValue.Date -> value.value.hashCode()
            is RValue.Tuple -> value.items.fold(17) { h, v -> h * 31 + pyHash(v) }
            else -> System.identityHashCode(value)
        }
        is Double -> if (n == Math.floor(n) && !n.isInfinite() && kotlin.math.abs(n) < 1e18) n.toLong().hashCode() else n.hashCode()
        else -> (n as BigInteger).toLong().hashCode()
    }

    fun compareNumbers(a: Number, b: Number): kotlin.Int {
        if (a is BigInteger && b is BigInteger) return a.compareTo(b)
        val x = if (a is BigInteger) a.toDouble() else a as Double
        val y = if (b is BigInteger) b.toDouble() else b as Double
        return x.compareTo(y)
    }

    /** Python's `<` family: numbers, strings, and lists/tuples lexicographically; anything else is a `TypeError`. */
    fun compare(a: RValue, b: RValue, op: String): kotlin.Int {
        requireKnown(a, b)
        requireDefined(a)
        requireDefined(b)
        val x = number(a)
        val y = number(b)
        if (x != null && y != null) return compareNumbers(x, y)
        if (a is RValue.Str && b is RValue.Str) return a.value.compareTo(b.value)
        val la = sequence(a)
        val lb = sequence(b)
        if (la != null && lb != null && a.typeName == b.typeName) {
            for (i in 0 until minOf(la.size, lb.size)) {
                if (!pyEquals(la[i], lb[i])) return compare(la[i], lb[i], op)
            }
            return la.size.compareTo(lb.size)
        }
        throw RenderError("'$op' not supported between instances of '${a.typeName}' and '${b.typeName}'")
    }

    /** The items of a list or tuple, or null. */
    fun sequence(value: RValue): kotlin.collections.List<RValue>? = when (value) {
        is RValue.List -> value.items
        is RValue.Tuple -> value.items
        else -> null
    }

    /** What `for x in value` iterates: list/tuple items, dict keys, string characters; anything else fails. */
    fun iterate(value: RValue): kotlin.collections.List<RValue> = when (value) {
        is RValue.List -> value.items.toList()
        is RValue.Tuple -> value.items
        is RValue.Dict -> value.keys
        is RValue.Str -> value.value.codePoints().toArray().map { RValue.Str(String(Character.toChars(it))) }
        is RValue.Undefined -> throw RenderError(value.message)
        is RValue.Hole -> throw HoleSignal(value)
        else -> throw RenderError("'${value.typeName}' object is not iterable")
    }

    /** `x in container`. */
    fun contains(container: RValue, item: RValue): Boolean {
        requireKnown(container, item)
        requireDefined(container)
        requireDefined(item)
        return when (container) {
            is RValue.Str -> {
                if (item !is RValue.Str) throw RenderError("'in <string>' requires string as left operand, not ${item.typeName}")
                container.value.contains(item.value)
            }
            is RValue.List -> container.items.any { pyEquals(it, item) }
            is RValue.Tuple -> container.items.any { pyEquals(it, item) }
            is RValue.Dict -> container.map.containsKey(RKey(item))
            else -> throw RenderError("argument of type '${container.typeName}' is not iterable")
        }
    }

    /** Python's `int()` of a value (filters convert more leniently, see the `int` filter). */
    fun toPyValue(value: RValue): PyValue? = when (value) {
        RValue.None -> PyValue.None
        is RValue.Bool -> PyValue.Bool(value.value)
        is RValue.Int -> PyValue.Int(value.value)
        is RValue.Float -> PyValue.Float(value.value)
        is RValue.Str -> PyValue.Str(value.value)
        is RValue.Date -> value.value
        is RValue.List -> PyValue.List(value.items.map { toPyValue(it) ?: return null })
        is RValue.Tuple -> PyValue.List(value.items.map { toPyValue(it) ?: return null })
        is RValue.Dict -> PyValue.Dict.of(value.entries.map { (k, v) -> (toPyValue(k) ?: return null) to (toPyValue(v) ?: return null) })
        else -> null
    }

    /** A deep copy of containers (bindings are copied before a render may mutate them). */
    fun copy(value: RValue): RValue = when (value) {
        is RValue.List -> RValue.List(value.items.map(::copy))
        is RValue.Dict -> RValue.Dict.of(value.entries.map { (k, v) -> k to copy(v) })
        else -> value
    }
}
