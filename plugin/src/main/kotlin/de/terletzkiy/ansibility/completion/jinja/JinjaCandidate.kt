package de.terletzkiy.ansibility.completion.jinja

import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.facts.FactSpec
import de.terletzkiy.ansibility.facts.MagicVar
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import javax.swing.Icon

/**
 * Resolution tiers of plan A.5 as completion groups, best first: T0 Jinja locals, T1 the loop variable, T2 task and
 * block vars, T3 the own role, T4 the play scope, T5 inventory-only names, T6 special variables and facts, then the
 * root-wide fallback (other roles of the root). [base] is the `PrioritizedLookupElement` priority of the tier.
 */
internal enum class Tier(val base: Double) {
    LOCAL(7000.0),
    LOOP(6000.0),
    TASK(5000.0),
    ROLE(4000.0),
    PLAY(3000.0),
    INVENTORY(2000.0),
    MAGIC(1000.0),
    ROOT(0.0),
    ;

    companion object {
        /** Members of one owner share this tier; their rank orders them. */
        val MEMBER: Tier = LOCAL
    }
}

/** What Ctrl+Q in the completion popup shows for a candidate. */
internal sealed interface CandidateDoc {
    /** The M2 variable card of [name] with the nested option [path] (sequence indices address elements). */
    data class Variable(val name: String, val path: List<String>) : CandidateDoc

    /** A fact below `ansible_facts` ([path]), optionally reached through its injected name. */
    data class Fact(val path: List<String>, val fact: FactSpec, val injectedAs: String?) : CandidateDoc

    data class Magic(val variable: MagicVar, val path: List<String> = emptyList(), val key: FactSpec? = null) : CandidateDoc

    /** A Jinja local bound at [definition]. */
    data class Local(val name: String, val kindLabel: String, val definition: SourceLocation) : CandidateDoc

    /** A loop variable without a documented source ([option] is its inferred type, if any). */
    data class Loop(val name: String, val option: OptionSpec?, val task: SourceLocation?, val contexts: Int) : CandidateDoc

    /** A key of an inferred (literal) type or of a fixed member table. */
    data class Member(val owner: String, val name: String, val typeText: String?, val description: String?) : CandidateDoc

    data class Group(val name: String, val hosts: List<String>, val environments: List<String>) : CandidateDoc

    data class Host(val name: String, val environments: List<String>) : CandidateDoc
}

/** How accepting a candidate edits the text beyond replacing the prefix. */
internal sealed interface InsertStyle {
    data object Plain : InsertStyle

    /** A quoted subscript key: close the string with [closingQuote] (host text, escapes included) and the `]`. */
    data class CloseKey(val closingQuote: String) : InsertStyle

    /** A quoted key offered right after `[` (the lookup string carries its quotes): close the `]`. */
    data object CloseBracket : InsertStyle
}

/** One completion candidate before it becomes a lookup element. */
internal class JinjaCandidate(
    /** The inserted text (for bracket keys, with its quotes). */
    val lookupString: String,
    val tier: Tier,
    /** Higher ranks first inside the tier. */
    val rank: Int,
    val typeText: String?,
    val tail: String?,
    val icon: Icon,
    val doc: CandidateDoc,
    /** Required and without any default (plan F1.3: bold). */
    val bold: Boolean = false,
    val deprecated: Boolean = false,
    /** Inventory-only names are grey. */
    val grey: Boolean = false,
    val insert: InsertStyle = InsertStyle.Plain,
    /** The name shown when it differs from [lookupString]. */
    val presentable: String = lookupString,
) {
    /** The `PrioritizedLookupElement` priority. */
    val priority: Double get() = tier.base + rank.coerceIn(0, 999)

    override fun toString(): String = "JinjaCandidate($lookupString, $tier, $typeText, $tail)"
}
