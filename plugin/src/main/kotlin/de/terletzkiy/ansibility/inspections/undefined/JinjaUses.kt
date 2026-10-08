package de.terletzkiy.ansibility.inspections.undefined

import com.intellij.openapi.util.TextRange
import de.terletzkiy.ansibility.semantics.CoreVersion

/**
 * The ansible-core rules a use is judged by, from the root's target version (verified on 2.18.8 and 2.21.4, see
 * `plan/research/host-awareness.md`, "V003 guard semantics").
 */
data class GuardRules(
    /**
     * From 2.19 on an undefined value travels through filters: `x | int | default(0)` renders `0`. Up to 2.18 the first
     * filter fails (`x | lower | default('')` raises).
     */
    val undefinedPassesFilters: Boolean,
    /**
     * Up to 2.18 the type tests `none`, `string`, `mapping`, `sequence`, `number`, `integer`, `float`, `boolean`, `true`,
     * `false`, `sameas` and `callable` answer for an undefined operand without failing; from 2.19 on every test except
     * `defined`/`undefined` fails on one.
     */
    val typeTestsTolerateUndefined: Boolean,
) {
    companion object {
        private val FIRST_LENIENT = CoreVersion(2, 19, 0)

        /** The rules of [version]; an unknown target is treated as the pinned 2.18.8. */
        fun of(version: CoreVersion?): GuardRules {
            val lenient = (version ?: CoreVersion.PINNED) >= FIRST_LENIENT
            return GuardRules(undefinedPassesFilters = lenient, typeTestsTolerateUndefined = !lenient)
        }

        /** Filters whose operand may be undefined: `default` and its alias `d`. */
        val DEFAULT_FILTERS: Set<String> = setOf("default", "d")

        /** Tests that ask for definedness. */
        val DEFINED_TESTS: Set<String> = setOf("defined", "undefined")

        /** `mandatory` fails on an undefined operand on purpose (with its own message). */
        val MANDATORY_FILTERS: Set<String> = setOf("mandatory", "ansible.builtin.mandatory")

        /** Tests that do not fail on an undefined operand up to 2.18 (probed on 2.18.8; see [typeTestsTolerateUndefined]). */
        val TOLERANT_TESTS: Set<String> = setOf(
            "none", "string", "mapping", "sequence", "number", "integer", "float", "boolean", "true", "false", "sameas", "callable",
        )
    }
}

/**
 * One free variable reference of a Jinja text as V003 sees it, in the coordinates of the analysed text (a template, an
 * injected fragment, or a decoded YAML scalar).
 */
data class RawUse(
    /** The root name: `x` in `x.a['b']`. */
    val name: String,
    /** The root name. */
    val nameRange: TextRange,
    /** The name and its constant accessors (`x.a['b']`, not a dynamic subscript or a method call). */
    val pathRange: TextRange,
    /** The whole postfix chain (`x.items()`, `x[k]`), which a filter appended after [pathRange] would not cover. */
    val chainEnd: Int,
    /** A definedness guard protects the use: a test, `| default`, or an enclosing condition that implies it. */
    val guarded: Boolean,
    /** `x | mandatory`: the author opted into failing. */
    val mandatory: Boolean,
    /** The use is the iterable of a `{% for %}`. */
    val iterable: Boolean,
    /**
     * The use is evaluated only when something else holds: inside an `{% if %}`/`{% elif %}`/`{% else %}` branch, a
     * `{% for %}` body or `{% else %}`, a macro or call body, a branch of an inline `if`, or the right side of `and`/`or`.
     * Such a use fails only where that condition holds, so it never proves a certain failure.
     */
    val conditional: Boolean,
    /**
     * The statement to wrap in a definedness guard: the output tag, or the whole block statement whose opening, `elif`
     * or `else` tag holds the use; null when it cannot be told.
     */
    val statement: TextRange?,
) {
    /** Whether `| default(…)` can be appended right after the use ([pathRange] is the whole chain). */
    val appendable: Boolean get() = chainEnd == pathRange.endOffset
}

/** Names that are never variables of the root: Jinja globals and constants Ansible's Jinja environment defines. */
internal val JINJA_GLOBALS: Set<String> = setOf(
    "range", "lipsum", "dict", "cycler", "joiner", "namespace", "lookup", "query", "q", "now", "undef",
    "True", "False", "None", "true", "false", "none",
)
