package de.terletzkiy.ansibility.context.host.card

import com.intellij.openapi.progress.ProgressManager
import de.terletzkiy.ansibility.api.VarSourceRef
import de.terletzkiy.ansibility.context.host.Evaluation
import de.terletzkiy.ansibility.context.host.Location
import de.terletzkiy.ansibility.index.JinjaBearing
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.semantics.yaml.YVault
import de.terletzkiy.ansibility.vars.ValueDisplay

/** How a [ResolvedChain] ends. */
internal enum class ChainEnd {
    /** A value without Jinja: shown with its type. */
    VALUE,

    /** A vault value, a `vault_*` name or a value of a vault file: masked, never shown. */
    SECRET,

    /** A template that is not a bare reference: evaluated at runtime, shown as written. */
    TEMPLATE,

    /** No layer defines the last name of [ResolvedChain.via] in the context. */
    UNDEFINED,
}

/**
 * Where a bare `{{ name }}` value leads in one evaluation context (plan amendment R7/R8, F8.2 "runtime-default chain"):
 * through the names in [via], to [end] (null when [kind] is [ChainEnd.UNDEFINED]). [typeName] is the YAML type of the
 * final value (`str`, `int`, …) for [ChainEnd.VALUE].
 */
internal data class ResolvedChain(
    val via: List<String>,
    val kind: ChainEnd,
    val end: VarSourceRef?,
    val typeName: String?,
) {
    /** Chains are grouped by where they end and through which names, never by value. */
    val key: Any get() = Triple(via, kind, end?.let(Location::of))
}

/**
 * Follows a bare `{{ name }}` value through the precedence of one context: each hop is the winner of
 * `PrecedenceEngine.effectiveOf` in that context (`Evaluation.effectiveOf`), so inventory overrides of intermediate names
 * count, not only the role's own defaults (the card's "Runtime default" row follows those). Cycles end the chain, as does
 * a depth over [MAX_DEPTH]. Values are never decrypted: a vault value ends the chain as [ChainEnd.SECRET], and a masked
 * start (the card shows it as hidden) has no chain at all, since the names it leads through would tell what it says.
 */
internal object ValueChains {
    private const val MAX_DEPTH = 8

    private val BARE_REFERENCE = Regex("""^\s*\{\{-?\s*([A-Za-z_][A-Za-z0-9_]*)\s*-?}}\s*$""")

    /** The name a bare `{{ name }}` scalar refers to, or null for any other value. */
    fun bareReference(value: YValue): String? = (value as? YScalar)?.let { BARE_REFERENCE.matchEntire(it.text)?.groupValues?.get(1) }

    /**
     * Whether the card masks the value [ref] names: a vault value, or a value without a preview under the one vault-safe
     * rule (a `vault_*` name, any value of a vault file).
     */
    fun isMasked(ref: VarSourceRef): Boolean = ref.isVault || ref.preview == null

    /**
     * The chain of [value] (the effective value of [name] in [evaluation], written at [winner]), or null when it is not
     * a bare reference or when [winner] is masked ([isMasked]).
     */
    fun follow(evaluation: Evaluation, name: String, winner: VarSourceRef, value: YValue): ResolvedChain? {
        if (isMasked(winner)) return null
        var next = bareReference(value) ?: return null
        val seen = hashSetOf(name)
        val via = ArrayList<String>()
        repeat(MAX_DEPTH) {
            ProgressManager.checkCanceled()
            if (!seen.add(next)) return null
            via += next
            val effective = evaluation.effectiveOf(next) ?: return ResolvedChain(via.toList(), ChainEnd.UNDEFINED, null, null)
            val ref = evaluation.ref(effective) ?: return null
            val current = effective.value
            if (current is YVault || isMasked(ref)) return ResolvedChain(via.toList(), ChainEnd.SECRET, ref, null)
            val reference = bareReference(current)
            if (reference == null) {
                val templated = current is YScalar && JinjaBearing.hasTemplateMarkers(current.text)
                return if (templated) {
                    ResolvedChain(via.toList(), ChainEnd.TEMPLATE, ref, null)
                } else {
                    ResolvedChain(via.toList(), ChainEnd.VALUE, ref, ValueDisplay.typeName(current))
                }
            }
            next = reference
        }
        return null
    }
}
