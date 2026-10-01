package de.terletzkiy.ansibility.semantics.validate

import de.terletzkiy.ansibility.semantics.coerce.CheckResult
import de.terletzkiy.ansibility.semantics.coerce.CoreSemantics
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.schema.SpecOrigin
import de.terletzkiy.ansibility.semantics.value.PyRepr
import de.terletzkiy.ansibility.semantics.value.PyValue

/** Wording shared by the findings: every message says what the target ansible-core would do. */
internal class FindingText(private val semantics: CoreSemantics) {
    val core: String = "ansible-core ${semantics.version}"

    /** `` `3.2` `` style rendering of a Python value (truncated). */
    fun code(value: PyValue): String = "`" + PyRepr.display(value) + "`"

    /** `keycloak_clients[0].optional_client_scopes`. */
    fun path(path: List<String>): String = buildString {
        path.forEachIndexed { index, segment ->
            when {
                index == 0 -> append(segment)
                segment.isNotEmpty() && segment.all { it in '0'..'9' } -> append('[').append(segment).append(']')
                else -> append('.').append(segment)
            }
        }
    }

    /** ` for role `keycloak` (entry point `main`)`, from the top-level option's origin. */
    fun owner(option: OptionSpec?): String = when (val origin = option?.origin) {
        is SpecOrigin.RoleSpec -> " for role `${origin.roleName}` (entry point `${origin.entryPoint}`)"
        is SpecOrigin.ModuleDoc -> " for module `${origin.fqcn}`"
        else -> ""
    }

    /**
     * What the check does to [value]: "would coerce `3.2` → `'3.2'`", "would reject it: …", "would crash with …";
     * null when the outcome is indeterminate.
     */
    fun outcome(value: PyValue, result: CheckResult, verb: String = "coerce"): String? = when (result) {
        is CheckResult.Accepted -> "$core would $verb ${code(value)} → ${code(result.coerced)}"
        is CheckResult.Rejected -> "$core would reject it: ${result.message}"
        is CheckResult.Crash -> "$core would crash with ${result.exceptionClass} (${result.message})"
        is CheckResult.Indeterminate -> null
    }

    /** Levenshtein distance, for "did you mean" hints. */
    fun distance(a: String, b: String): Int {
        val previous = IntArray(b.length + 1) { it }
        val current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            current.copyInto(previous)
        }
        return previous[b.length]
    }

    /** The closest candidate within [maxDistance] edits (ties: first in order), if any. */
    fun nearest(text: String, candidates: Collection<String>, maxDistance: Int): String? =
        candidates.map { it to distance(text, it) }.filter { it.second <= maxDistance }.minByOrNull { it.second }?.first
}
