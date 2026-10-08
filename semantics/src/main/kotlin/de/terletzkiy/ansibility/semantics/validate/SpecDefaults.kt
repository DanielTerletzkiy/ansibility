package de.terletzkiy.ansibility.semantics.validate

import de.terletzkiy.ansibility.semantics.coerce.CheckResult
import de.terletzkiy.ansibility.semantics.coerce.CheckType
import de.terletzkiy.ansibility.semantics.coerce.CoreSemantics
import de.terletzkiy.ansibility.semantics.diagnostics.Level
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.schema.OptionType
import de.terletzkiy.ansibility.semantics.value.PyValue
import de.terletzkiy.ansibility.semantics.value.pyEquals
import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.YEmpty
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.semantics.yaml.YVault

/**
 * Whether a role's documented argument_specs `default:` agrees with the role default, the value Ansible actually uses
 * (plan amendment R23, D170). ansible-core never applies a role spec's default: the implicit `validate_argument_spec`
 * task validates a copy and returns only errors, so the variable always comes from `defaults/`.
 *
 * The rule is ansible-test's `doc-default-does-not-match-spec`: both values go through the option type's checker
 * (`DEFAULT_TYPE_VALIDATORS`, a missing type is `str`) and are then compared with Python's `==`, so a type-only
 * difference (`1.2` vs `"1.2"` under `str`, `yes` vs `true` under `bool`, `"8080"` vs `8080` under `int`) is
 * [Verdict.Equal]: the type checks on the defaults value (ANS-T011/T013/T016) report those. In order:
 * - **No claim**: the spec has no default (absent, `null`, `~` or empty, which Ansible treats alike), the option is
 *   `required` with a default (ANS-S001: Ansible always fails), its type is invalid, or either side cannot be loaded.
 * - **Unknown**: a secret (the caller's `secret` flag for `vault_*` names and vault files, a `no_log` option or a
 *   `no_log` sub-option anywhere below it ([hasNoLog]), or a `!vault` value on either side) is never compared; Jinja
 *   on either side is unknown, except identical template text (whitespace inside the delimiters normalised), which is
 *   equal, and a role default that is a bare
 *   `{{ name }}` [resolve]d to a literal through the same role's defaults (at most [MAX_CHAIN] steps), which is
 *   compared and capped at WARNING.
 * - **Null**: a `null` role default against a documented `''`, `[]` or `{}` is a WEAK_WARNING mismatch, against any
 *   other documented value an ERROR. `None` is never coerced, so `check_type_str(None)` cannot hide the difference.
 * - **Coercion**: `path` is compared as `str` (no `~` or `$VAR` expansion), `raw` is not converted, `list` elements go
 *   through `elements`, and `dict`s (and `elements: dict` items) with `options` convert only the keys that are
 *   present; sub-option defaults are never filled in. When either side fails its conversion, the raw values are
 *   compared instead.
 * - **Equality**: values that are strictly equal as loaded (dicts in any key order) are equal before any conversion,
 *   so `str()` of two dicts that differ only in key order (a missing type) is no difference: the task sees the same
 *   dict. Otherwise Python's `==` after conversion; strict typed equality (bool, int and float differ) for `raw` and
 *   for the raw fallback.
 * - **Cap**: a mismatch is ERROR; WARNING when two scalars have the same text and differ only in YAML typing
 *   (`"1.20"` vs `1.20`) and for the bare-reference chain; WEAK_WARNING for `null` against an empty value.
 *
 * Pure: no IntelliJ classes, safe on any thread.
 *
 * @property semantics the target core's version flags (`check_type_str(None)` and friends)
 * @property isTemplated scalars ansible-core renders before it uses them
 * @property resolve the value of variable `name` in the same role's defaults, or null when there is none or it must
 *   stay unseen (a secret)
 */
class SpecDefaults(
    private val semantics: CoreSemantics = CoreSemantics.PINNED,
    private val isTemplated: (YScalar) -> Boolean = SpecValidator::containsJinja,
    private val resolve: (String) -> YValue? = { null },
) {
    private val checkType = CheckType(semantics)

    /** The outcome of one comparison. */
    sealed interface Verdict {
        /** The spec makes no claim that could be checked. */
        data object NoClaim : Verdict

        /** The documented default is what Ansible uses (after the option type's conversion). */
        data object Equal : Verdict

        /** The values cannot be compared statically (or must not be: secrets). */
        data class Unknown(val reason: UnknownReason) : Verdict

        /**
         * The documented default differs from the role default. [cap] is the most severe level the finding may have;
         * for [MismatchReason.CHAIN], [via] is the last variable of the `{{ … }}` chain and [resolved] its literal.
         */
        data class Mismatch(
            val cap: Level,
            val reason: MismatchReason,
            val via: String? = null,
            val resolved: YValue? = null,
        ) : Verdict
    }

    enum class UnknownReason { SECRET, TEMPLATED }

    enum class MismatchReason {
        /** Different values after the option type's conversion (or in the raw fallback). */
        VALUE,

        /** The role default is `null`, the spec documents a value. */
        NULL_ROLE_DEFAULT,

        /** The role default is `null`, the spec documents `''`, `[]` or `{}`. */
        EMPTY_VS_NULL,

        /** Two scalars with the same text that YAML types differently (`"1.20"` vs `1.20` under `str`). */
        YAML_TYPING,

        /** The role default is a bare `{{ name }}` whose literal differs. */
        CHAIN,
    }

    /**
     * Compares [option]'s documented default with [roleDefault], the value Ansible uses. [secret] is the caller's
     * knowledge that the variable holds a secret (`no_log`, a `vault_*` name, a vault file): never compared.
     */
    fun compare(option: OptionSpec, roleDefault: YValue, secret: Boolean = false): Verdict {
        val documented = documentedDefault(option) ?: return Verdict.NoClaim
        if (option.required) return Verdict.NoClaim
        if (option.type is OptionType.Invalid || option.elements is OptionType.Invalid) return Verdict.NoClaim
        if (secret || hasNoLog(option) || containsVault(documented) || containsVault(roleDefault)) return Verdict.Unknown(UnknownReason.SECRET)
        if (unloadable(documented) || unloadable(roleDefault)) return Verdict.NoClaim

        val documentedTemplated = containsTemplate(documented)
        val roleTemplated = containsTemplate(roleDefault)
        if (documentedTemplated || roleTemplated) {
            if (documentedTemplated && roleTemplated && sameWithTemplates(documented, roleDefault)) return Verdict.Equal
            if (documentedTemplated) return Verdict.Unknown(UnknownReason.TEMPLATED)
            val (via, literal) = followChain(roleDefault) ?: return Verdict.Unknown(UnknownReason.TEMPLATED)
            return when (val verdict = compareLiterals(option, documented, literal)) {
                is Verdict.Mismatch -> Verdict.Mismatch(atMostWarning(verdict.cap), MismatchReason.CHAIN, via, literal)
                else -> verdict
            }
        }
        return compareLiterals(option, documented, roleDefault)
    }

    /** [documented] against [roleDefault], neither of them templated, secret or unloadable. */
    private fun compareLiterals(option: OptionSpec, documented: YValue, roleDefault: YValue): Verdict {
        val spec = PyValue.fromYValue(documented, isTemplated)
        val role = PyValue.fromYValue(roleDefault, isTemplated)
        if (role == PyValue.None) {
            return if (isEmptyValue(spec)) {
                Verdict.Mismatch(Level.WEAK_WARNING, MismatchReason.EMPTY_VS_NULL)
            } else {
                Verdict.Mismatch(Level.ERROR, MismatchReason.NULL_ROLE_DEFAULT)
            }
        }
        // The same value as loaded: whatever the type does to it, it does to both (a missing type's str() of two dicts in
        // another key order would differ, but the task sees an equal dict).
        if (strictEquals(spec, role)) return Verdict.Equal
        val coercedSpec = coerce(option, spec, 0)
        val coercedRole = coerce(option, role, 0)
        val equal = when {
            coercedSpec == null || coercedRole == null -> strictEquals(spec, role)
            option.type == OptionType.Raw -> strictEquals(coercedSpec, coercedRole)
            else -> coercedSpec.pyEquals(coercedRole)
        }
        if (equal) return Verdict.Equal
        val sameText = documented is YScalar && roleDefault is YScalar && documented.text == roleDefault.text
        return if (sameText) Verdict.Mismatch(Level.WARNING, MismatchReason.YAML_TYPING) else Verdict.Mismatch(Level.ERROR, MismatchReason.VALUE)
    }

    // ------------------------------------------------------------------------------------------------ coercion

    /** [value] after [option]'s checks as `ArgumentSpecValidator` runs them; null when a check does not accept it. */
    private fun coerce(option: OptionSpec, value: PyValue, depth: Int): PyValue? {
        if (value == PyValue.None) return value
        val converted = convert(option.type, value) ?: return null
        if (depth >= MAX_DEPTH) return converted
        val elements = option.elements
        val options = option.options
        return when {
            elements != null && converted is PyValue.List -> PyValue.List(
                converted.items.map { item ->
                    if (item == PyValue.None) return@map item
                    val element = convert(elements, item) ?: return null
                    if (elements == OptionType.Dict && options != null && element is PyValue.Dict) subOptions(options, element, depth) ?: return null else element
                },
            )
            option.type == OptionType.Dict && options != null && converted is PyValue.Dict -> subOptions(options, converted, depth)
            else -> converted
        }
    }

    /** [dict] with the keys that name a sub-option (or an alias of one) converted; other keys and `None` values stay. */
    private fun subOptions(options: Map<String, OptionSpec>, dict: PyValue.Dict, depth: Int): PyValue.Dict? {
        val pairs = dict.entries.map { (key, value) ->
            val name = (key as? PyValue.Str)?.value
            val sub = name?.let { options[it] ?: options.values.firstOrNull { option -> it in option.aliases } }
            key to if (sub == null || sub.type is OptionType.Invalid) value else coerce(sub, value, depth + 1) ?: return null
        }
        return PyValue.Dict.of(pairs)
    }

    /** One `check_type_*` call; `path` as `str` (no expansion), `raw` unchanged. Null when it is not accepted. */
    private fun convert(type: OptionType, value: PyValue): PyValue? {
        val result = when (type) {
            OptionType.Raw -> return value
            OptionType.Path -> checkType.str(value)
            else -> checkType.check(type, value)
        }
        return (result as? CheckResult.Accepted)?.coerced
    }

    // ------------------------------------------------------------------------------------------------ templates

    /** Follows a bare `{{ name }}` through [resolve] to a literal: the last name and its value, or null. */
    private fun followChain(start: YValue): Pair<String, YValue>? {
        var name = bareReference(start) ?: return null
        val seen = HashSet<String>()
        repeat(MAX_CHAIN) {
            if (!seen.add(name)) return null
            val value = resolve(name) ?: return null
            val next = bareReference(value)
            if (next == null) {
                if (containsTemplate(value) || containsVault(value) || unloadable(value)) return null
                return name to value
            }
            name = next
        }
        return null
    }

    /** Equal trees where templated scalars compare by normalised text and every other scalar strictly. */
    private fun sameWithTemplates(a: YValue, b: YValue): Boolean = when {
        a is YScalar && b is YScalar && isTemplated(a) && isTemplated(b) -> normalizeTemplate(a.text) == normalizeTemplate(b.text)
        a is YScalar && isTemplated(a) || b is YScalar && isTemplated(b) -> false
        a is YSeq && b is YSeq -> a.items.size == b.items.size && a.items.indices.all { sameWithTemplates(a.items[it], b.items[it]) }
        a is YMap && b is YMap -> {
            val left = a.entries.associate { it.key.text to it.value }
            val right = b.entries.associate { it.key.text to it.value }
            left.keys == right.keys && left.all { (key, value) -> sameWithTemplates(value, right.getValue(key)) }
        }
        else -> strictEquals(PyValue.fromYValue(a), PyValue.fromYValue(b))
    }

    private fun containsTemplate(value: YValue): Boolean = when (value) {
        is YScalar -> isTemplated(value)
        is YSeq -> value.items.any(::containsTemplate)
        is YMap -> value.entries.any { containsTemplate(it.value) }
        else -> false
    }

    companion object {
        /** How many `{{ name }}` steps a chain may take. */
        const val MAX_CHAIN: Int = 8

        /** How deep `options` and `elements` are followed. */
        private const val MAX_DEPTH: Int = 8

        private val BARE_REFERENCE = Regex("""^\s*\{\{-?\s*([A-Za-z_][A-Za-z0-9_]*)\s*-?}}\s*$""")
        private val DELIMITED = Regex("""(\{\{|\{%|\{#)([-+]?)\s*(.*?)\s*([-+]?)(}}|%}|#})""", RegexOption.DOT_MATCHES_ALL)
        private val BLANKS = Regex("""\s+""")

        /**
         * The documented default of [option]: its `default:` value, or null when the spec documents none (absent,
         * `null`, `~` or empty, which `_set_defaults` treats alike).
         */
        fun documentedDefault(option: OptionSpec): YValue? = option.default?.takeUnless(::isNull)

        /** `null`, `~` or an empty value. */
        fun isNull(value: YValue): Boolean = value is YEmpty || value is YScalar && value.tag == null && value.resolved == Resolved.Null

        /** The variable a bare `{{ name }}` scalar names (whitespace control allowed), or null. */
        fun bareReference(value: YValue): String? =
            (value as? YScalar)?.takeIf { it.tag != "!unsafe" }?.let { BARE_REFERENCE.matchEntire(it.text)?.groupValues?.get(1) }

        /** [text] with the whitespace inside each Jinja delimiter pair collapsed to single blanks. */
        fun normalizeTemplate(text: String): String = DELIMITED.replace(text.trim()) { match ->
            val (open, leftControl, body, rightControl, close) = match.destructured
            "$open$leftControl ${body.replace(BLANKS, " ")} $rightControl$close"
        }

        /**
         * Whether [option] or any sub-option below it (`options`, at most [MAX_DEPTH] levels) is `no_log`: a dict holding
         * a secret key is never compared or shown as a whole.
         */
        fun hasNoLog(option: OptionSpec): Boolean = hasNoLog(option, 0)

        private fun hasNoLog(option: OptionSpec, depth: Int): Boolean =
            option.noLog || depth < MAX_DEPTH && option.options?.values?.any { hasNoLog(it, depth + 1) } == true

        /** Whether [value] holds a `!vault` value anywhere. */
        fun containsVault(value: YValue): Boolean = when (value) {
            is YVault -> true
            is YSeq -> value.items.any(::containsVault)
            is YMap -> value.entries.any { containsVault(it.value) }
            else -> false
        }

        private fun unloadable(value: YValue): Boolean = PyValue.fromYValue(value).let { it == PyValue.Unloadable || containsUnloadable(it) }

        private fun containsUnloadable(value: PyValue): Boolean = when (value) {
            PyValue.Unloadable -> true
            is PyValue.List -> value.items.any(::containsUnloadable)
            is PyValue.Dict -> value.entries.any { containsUnloadable(it.second) }
            else -> false
        }

        /** [level], lowered to WARNING when it is ERROR ([Level] lists the most severe first). */
        private fun atMostWarning(level: Level): Level = if (level.ordinal < Level.WARNING.ordinal) Level.WARNING else level

        /** `''`, `[]` or `{}`. */
        private fun isEmptyValue(value: PyValue): Boolean =
            value == PyValue.Str("") || value is PyValue.List && value.items.isEmpty() || value is PyValue.Dict && value.entries.isEmpty()

        /**
         * Equality without Python's numeric tower: `True`, `1` and `1.0` differ; lists compare in order, dicts by keys
         * (Python key equality) and values, recursively.
         */
        fun strictEquals(a: PyValue, b: PyValue): Boolean = when (a) {
            is PyValue.Bool -> b is PyValue.Bool && a.value == b.value
            is PyValue.Int -> b is PyValue.Int && a.value == b.value
            is PyValue.Float -> b is PyValue.Float && a.value == b.value
            is PyValue.List -> b is PyValue.List && a.items.size == b.items.size && a.items.indices.all { strictEquals(a.items[it], b.items[it]) }
            is PyValue.Dict -> b is PyValue.Dict && a.entries.size == b.entries.size &&
                a.entries.all { (key, value) -> b[key]?.let { strictEquals(value, it) } == true }
            else -> a.pyEquals(b)
        }
    }
}
