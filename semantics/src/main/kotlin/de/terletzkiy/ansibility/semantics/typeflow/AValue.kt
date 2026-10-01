package de.terletzkiy.ansibility.semantics.typeflow

import de.terletzkiy.ansibility.semantics.value.PyValue
import java.util.EnumSet

/** The Python types the type checks tell apart. */
enum class BaseType(val pyName: String) {
    NONE("NoneType"),
    BOOL("bool"),
    INT("int"),
    FLOAT("float"),
    STR("str"),

    /** `datetime.date` / `datetime.datetime` (YAML timestamps). */
    DATE("date"),
    LIST("list"),
    DICT("dict"),

    /** Any other Python type: a tuple, a set, an object a filter returns. Outside every documented type. */
    OTHER("object"),
    ;

    companion object {
        /** The type of a known value; [OTHER] for opaque ones. */
        fun of(value: PyValue): BaseType = when (value) {
            PyValue.None -> NONE
            is PyValue.Bool -> BOOL
            is PyValue.Int -> INT
            is PyValue.Float -> FLOAT
            is PyValue.Str, PyValue.Vault -> STR
            is PyValue.Date -> DATE
            is PyValue.List -> LIST
            is PyValue.Dict -> DICT
            is PyValue.Templated, PyValue.Unloadable -> OTHER
        }
    }
}

/**
 * An abstract value (plan A.5 `AValue`): the set of Python types a template or variable may have, or [unknown].
 *
 * `Known(v) ⊑ OfType(types) ⊑ Unknown`, plus [vault]. [join] combines alternatives (several definitions, both
 * branches of an inline `if`, `default(x)`).
 *
 * Two flags describe what ansible-core ≤ 2.18 does when it stringifies a result and `literal_eval`s strings that
 * look like a list, dict or bool:
 * - [strMayLookLiteral]: a `str` of unknown text might start with `[`/`{` or be exactly `True`/`False`;
 * - [containerReprUnsafe]: the `repr()` of a `list`/`dict` might not `literal_eval` back (dates, `inf`, results of
 *   unknown type inside), so the result might stay a `str`.
 *
 * @property known the exact value when one is known (never set from a secret definition)
 * @property vault the value is (or may be) a vault secret: it decrypts to a `str` whose text is never known
 */
class AValue private constructor(
    val types: Set<BaseType>,
    val unknown: Boolean,
    val vault: Boolean,
    val known: PyValue?,
    val strMayLookLiteral: Boolean,
    val containerReprUnsafe: Boolean,
) {
    /** Whether nothing at all is known about the value's type. */
    val isTop: Boolean get() = unknown

    /** Whether this is the empty alternative ([NOTHING]). */
    private val isNothing: Boolean get() = !unknown && !vault && types.isEmpty()

    /** The alternatives of both values. */
    fun join(other: AValue): AValue {
        if (isNothing) return other
        if (other.isNothing) return this
        if (unknown || other.unknown) return UNKNOWN
        return AValue(
            types = EnumSet.noneOf(BaseType::class.java).apply {
                addAll(types)
                addAll(other.types)
            },
            unknown = false,
            vault = vault || other.vault,
            known = if (known != null && known == other.known) known else null,
            strMayLookLiteral = strMayLookLiteral || other.strMayLookLiteral,
            containerReprUnsafe = containerReprUnsafe || other.containerReprUnsafe,
        )
    }

    /** This value without its exact value (keeps the types and flags). */
    fun withoutKnown(): AValue = if (known == null) this else AValue(types, unknown, vault, null, strMayLookLiteral, containerReprUnsafe)

    override fun toString(): String = when {
        unknown -> "AValue(unknown)"
        else -> "AValue(${types.joinToString("|") { it.pyName }}${if (vault) ", vault" else ""}${known?.let { ", known=$it" } ?: ""})"
    }

    override fun equals(other: Any?): Boolean = other is AValue && types == other.types && unknown == other.unknown &&
        vault == other.vault && known == other.known && strMayLookLiteral == other.strMayLookLiteral &&
        containerReprUnsafe == other.containerReprUnsafe

    override fun hashCode(): Int = listOf(types, unknown, vault, known, strMayLookLiteral, containerReprUnsafe).hashCode()

    companion object {
        /** Nothing is known. */
        val UNKNOWN: AValue = AValue(EnumSet.noneOf(BaseType::class.java), true, false, null, true, true)

        /** A vault secret: a `str` after decryption, content never known. */
        val VAULT: AValue = AValue(EnumSet.of(BaseType.STR), false, true, null, true, false)

        /** No alternative yet (the neutral element of [join]). */
        val NOTHING: AValue = AValue(EnumSet.noneOf(BaseType::class.java), false, false, null, false, false)

        /**
         * A value of one of [types] whose exact value is not known; [strMayLookLiteral] and [containerReprUnsafe]
         * as described on the class.
         */
        fun of(vararg types: BaseType, strMayLookLiteral: Boolean = true, containerReprUnsafe: Boolean = true): AValue =
            ofTypes(types.toSet(), strMayLookLiteral, containerReprUnsafe)

        /** See [of]. */
        fun ofTypes(types: Set<BaseType>, strMayLookLiteral: Boolean = true, containerReprUnsafe: Boolean = true): AValue {
            if (types.isEmpty()) return NOTHING
            val set = EnumSet.copyOf(types)
            return AValue(set, false, false, null, strMayLookLiteral && BaseType.STR in set, containerReprUnsafe && (BaseType.LIST in set || BaseType.DICT in set))
        }

        /**
         * The exact [value]. A string's text decides [strMayLookLiteral]; a container's [containerReprUnsafe] is
         * whether its `repr()` contains something `literal_eval` cannot read back.
         */
        fun known(value: PyValue): AValue {
            if (value == PyValue.Vault) return VAULT
            if (value is PyValue.Templated || value == PyValue.Unloadable) return UNKNOWN
            val type = BaseType.of(value)
            return AValue(
                EnumSet.of(type), false, false, value,
                strMayLookLiteral = value is PyValue.Str && looksLiteral(value.value),
                containerReprUnsafe = (value is PyValue.List || value is PyValue.Dict) && !reprRoundTrips(value),
            )
        }

        /** ansible-core ≤ 2.18 `literal_eval`s a rendered string that starts with `{` or `[`, or is `True`/`False`. */
        fun looksLiteral(text: String): Boolean = text.startsWith("{") || text.startsWith("[") || text == "True" || text == "False"

        /** Whether `literal_eval(repr(value))` gives the value back: no dates, `inf`/`nan` or opaque values inside. */
        fun reprRoundTrips(value: PyValue): Boolean = when (value) {
            PyValue.None, is PyValue.Bool, is PyValue.Int, is PyValue.Str, PyValue.Vault -> true
            is PyValue.Float -> value.value.isFinite()
            is PyValue.List -> value.items.all(::reprRoundTrips)
            is PyValue.Dict -> value.entries.all { (k, v) -> reprRoundTrips(k) && reprRoundTrips(v) }
            is PyValue.Date, is PyValue.Templated, PyValue.Unloadable -> false
        }
    }
}

/** Why a templated value has its logical type; the messages name it. */
sealed interface TypeOrigin {
    /** A bare variable reference, possibly with constant attribute/item accessors (`x`, `x.port`, `x[0]`). */
    data class Chain(val chain: ChainTrace, val accessors: List<String> = emptyList()) : TypeOrigin

    /** The trailing filter ([name] without collection prefix) gives the type. */
    data class Filter(val name: String) : TypeOrigin

    /** Text around an expression, or several expressions: Jinja renders a string. */
    data object MultiNode : TypeOrigin

    /** `~` concatenation. */
    data object Concat : TypeOrigin

    /** A literal (`{{ 5 }}`, `{{ [1, 2] }}`, a template without expressions). */
    data object Literal : TypeOrigin

    /** Both branches of an inline `if`. */
    data object Conditional : TypeOrigin

    /** A comparison, a test (`is defined`) or `not`. */
    data object Test : TypeOrigin

    /** Anything else (the type is then unknown, so no message needs it). */
    data object Other : TypeOrigin
}

/**
 * The definitions a variable reference was followed through, for the message ("via `x` (defaults/main.yml:12)").
 *
 * @property name the variable
 * @property definitions every reachable definition; empty for a `vault_*` name, which is never followed
 */
data class ChainTrace(val name: String, val definitions: List<DefinitionTrace>)

/**
 * One definition on a chain.
 *
 * @property label where it is written (`defaults/main.yml:12`)
 * @property types its logical types
 * @property shown a short rendering of the literal value, or null (secret, container, template)
 * @property next the chain its value continues with when the value is itself a bare `{{ name }}` template
 */
data class DefinitionTrace(val label: String, val types: Set<BaseType>, val shown: String?, val next: ChainTrace?)
