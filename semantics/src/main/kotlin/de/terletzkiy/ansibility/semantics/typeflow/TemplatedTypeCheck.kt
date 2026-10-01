package de.terletzkiy.ansibility.semantics.typeflow

import de.terletzkiy.ansibility.semantics.coerce.CheckResult
import de.terletzkiy.ansibility.semantics.coerce.CheckType
import de.terletzkiy.ansibility.semantics.coerce.CoreSemantics
import de.terletzkiy.ansibility.semantics.coerce.PathEnvironment
import de.terletzkiy.ansibility.semantics.schema.OptionType
import de.terletzkiy.ansibility.semantics.value.PyValue

/** What ansible-core's `check_type_<doc>` does with the runtime value of a mismatching templated value. */
sealed interface RuntimeOutcome {
    /** The known value [from] is accepted and converted to [to]. */
    data class Coerced(val from: PyValue, val to: PyValue) : RuntimeOutcome

    /** The known value [value] is rejected with [message]. */
    data class Rejected(val value: PyValue, val message: String) : RuntimeOutcome

    /** The known value [value] makes the check raise [exceptionClass], which the validator does not catch. */
    data class Crashed(val value: PyValue, val exceptionClass: String, val message: String) : RuntimeOutcome

    /** The exact value is not known; [effect] holds for every value of the runtime types. */
    data class ByType(val effect: TypeEffect) : RuntimeOutcome
}

/** What `check_type_<doc>` does with every value of some types. */
enum class TypeEffect {
    /** Every value is rejected (a list for `int`, a dict for `list` …). */
    REJECT,

    /** Every value is accepted and converted (anything for `str`, a scalar for `list`, an int for `float`). */
    COERCE,

    /** Some values convert, others are rejected (a string for `int`, a float for `int`). */
    CONVERT_OR_REJECT,
}

/**
 * A templated value whose type is outside the documented type (ANS-T020).
 *
 * @property documented the documented type (`type`, or `elements` for a list item)
 * @property certainRejection ansible-core rejects every value the runtime type allows (the equivalent literal
 *   finding would be a rejection, ANS-T001)
 */
data class TemplatedMismatch(
    val documented: OptionType,
    val types: TemplateTypes,
    val outcome: RuntimeOutcome,
) {
    val logical: AValue get() = types.logical
    val runtime: AValue get() = types.runtime
    val origin: TypeOrigin get() = types.origin

    val certainRejection: Boolean
        get() = when (outcome) {
            is RuntimeOutcome.Rejected, is RuntimeOutcome.Crashed -> true
            is RuntimeOutcome.ByType -> outcome.effect == TypeEffect.REJECT
            is RuntimeOutcome.Coerced -> false
        }
}

/**
 * The documented-type judgement of templated values (plan A.6, ANS-T020) and its must-rule: a templated value is a
 * finding only when **both** its logical type and its runtime type under the target core lie entirely outside the
 * documented type, and both are certain (not unknown, not vault, no `None` alternative).
 *
 * For a bare `{{ name }}` the logical type is the definitions' type and the runtime type is what the target renders
 * (native for a definition holding a bool, int, float, list or dict literal on every core; ≤ 2.18 renders a chain
 * through another template, or to a date, as a string). Requiring both keeps every red message true:
 * `'{{ x | int }}'` into a `str` option is silent on 2.18 (it arrives as `'5'`), `'{{ p | int }}'` into an `int`
 * option is silent (logical int), `'{{ items | join(",") }}'` into an `int` option is red.
 *
 * "Inside" follows the literal documented-type rules (T010–T016): `str`/`path` take only `str`; `int` only `int`;
 * `float` `float` and `int` (an int only widens); `bool` only `bool`; `list` only `list`; `dict` only `dict`. Other
 * documented types (`raw`, `jsonarg`, `bytes`, `bits`, invalid ones) are never judged.
 */
class TemplatedTypeCheck(semantics: CoreSemantics = CoreSemantics.PINNED, paths: PathEnvironment = PathEnvironment.EMPTY) {
    private val checkType = CheckType(semantics, paths)

    /** The mismatch of [types] against [documented], or null when the value is fine or not certainly wrong. */
    fun check(documented: OptionType, types: TemplateTypes): TemplatedMismatch? {
        if (!isJudged(documented)) return null
        if (!outside(documented, types.logical) || !outside(documented, types.runtime)) return null
        return TemplatedMismatch(documented, types, outcome(documented, types.runtime))
    }

    private fun outside(documented: OptionType, value: AValue): Boolean =
        !value.unknown && !value.vault && value.types.isNotEmpty() && BaseType.NONE !in value.types &&
            value.types.none { accepts(documented, it) == true }

    private fun outcome(documented: OptionType, runtime: AValue): RuntimeOutcome {
        runtime.known?.let { value ->
            return when (val result = checkType.check(documented, value)) {
                is CheckResult.Accepted -> RuntimeOutcome.Coerced(value, result.coerced)
                is CheckResult.Rejected -> RuntimeOutcome.Rejected(value, result.message)
                is CheckResult.Crash -> RuntimeOutcome.Crashed(value, result.exceptionClass, result.message)
                is CheckResult.Indeterminate -> RuntimeOutcome.ByType(effect(documented, runtime.types))
            }
        }
        return RuntimeOutcome.ByType(effect(documented, runtime.types))
    }

    companion object {
        /** Whether templated values are judged against [documented] at all. */
        fun isJudged(documented: OptionType): Boolean = when (documented) {
            OptionType.Str, OptionType.Path, OptionType.Int, OptionType.Float, OptionType.Bool, OptionType.List, OptionType.Dict -> true
            else -> false
        }

        /** Whether a value of [type] is inside [documented] (no documented-type finding); null when [documented] is not judged. */
        fun accepts(documented: OptionType, type: BaseType): Boolean? = when (documented) {
            OptionType.Str, OptionType.Path -> type == BaseType.STR
            OptionType.Int -> type == BaseType.INT
            OptionType.Float -> type == BaseType.FLOAT || type == BaseType.INT
            OptionType.Bool -> type == BaseType.BOOL
            OptionType.List -> type == BaseType.LIST
            OptionType.Dict -> type == BaseType.DICT
            else -> null
        }

        /**
         * What `check_type_<documented>` does with the values of each type in [types] (measured semantics, see
         * `CheckType`): every value rejected, every value converted, or depending on the value.
         */
        fun effect(documented: OptionType, types: Set<BaseType>): TypeEffect {
            val effects = types.map { effectOf(documented, it) }.toSet()
            return when {
                effects.all { it == TypeEffect.REJECT } -> TypeEffect.REJECT
                effects.all { it == TypeEffect.COERCE } -> TypeEffect.COERCE
                else -> TypeEffect.CONVERT_OR_REJECT
            }
        }

        private fun effectOf(documented: OptionType, type: BaseType): TypeEffect = when (documented) {
            // check_type_str converts everything (None aside, which is never judged here).
            OptionType.Str, OptionType.Path -> TypeEffect.COERCE
            OptionType.Int -> when (type) {
                BaseType.INT, BaseType.BOOL -> TypeEffect.COERCE
                BaseType.FLOAT, BaseType.STR -> TypeEffect.CONVERT_OR_REJECT
                else -> TypeEffect.REJECT
            }
            OptionType.Float -> when (type) {
                BaseType.FLOAT, BaseType.INT, BaseType.BOOL -> TypeEffect.COERCE
                BaseType.STR -> TypeEffect.CONVERT_OR_REJECT
                else -> TypeEffect.REJECT
            }
            OptionType.Bool -> when (type) {
                BaseType.BOOL -> TypeEffect.COERCE
                BaseType.INT, BaseType.FLOAT, BaseType.STR -> TypeEffect.CONVERT_OR_REJECT
                else -> TypeEffect.REJECT
            }
            // check_type_list splits strings on commas and wraps numbers and bools.
            OptionType.List -> when (type) {
                BaseType.LIST, BaseType.STR, BaseType.INT, BaseType.FLOAT, BaseType.BOOL -> TypeEffect.COERCE
                else -> TypeEffect.REJECT
            }
            OptionType.Dict -> when (type) {
                BaseType.DICT -> TypeEffect.COERCE
                BaseType.STR -> TypeEffect.CONVERT_OR_REJECT
                else -> TypeEffect.REJECT
            }
            else -> TypeEffect.CONVERT_OR_REJECT
        }
    }
}
