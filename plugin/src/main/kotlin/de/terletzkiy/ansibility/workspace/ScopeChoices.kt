package de.terletzkiy.ansibility.workspace

import de.terletzkiy.ansibility.api.ScopeChoice
import de.terletzkiy.ansibility.settings.WorkspaceState

/**
 * The stored form of a [ScopeChoice] in `settings.WorkspaceState.scope` (plan amendment R9, F9.1 "Persistence"):
 * `all | current-root | named:<scopeId> | roots:<RootKey,…>`.
 *
 * - Scope ids are stored verbatim after `named:` (an id may contain any character, the prefix is unambiguous).
 * - Root keys (`settings.RootKeys`) are sorted and joined with `,`; a `,` or `%` inside a key is written `%2C` / `%25`,
 *   so every key survives the round trip. `roots:` alone is a valid, empty choice.
 * - Anything unreadable (an unknown prefix, `named:` without an id) reads as [ScopeChoice.AllRoots], the default (D39).
 */
object ScopeChoices {
    const val ALL: String = WorkspaceState.SCOPE_ALL
    const val CURRENT_ROOT: String = "current-root"
    const val NAMED_PREFIX: String = "named:"
    const val ROOTS_PREFIX: String = "roots:"

    /** The stored text of [choice]. */
    fun encode(choice: ScopeChoice): String = when (choice) {
        ScopeChoice.AllRoots -> ALL
        ScopeChoice.CurrentFileRoot -> CURRENT_ROOT
        is ScopeChoice.Named -> if (choice.scopeId.isEmpty()) ALL else NAMED_PREFIX + choice.scopeId
        is ScopeChoice.Roots -> ROOTS_PREFIX + choice.keys.sorted().joinToString(",") { escape(it) }
    }

    /** The choice stored as [text]; null, blank or unreadable text is [ScopeChoice.AllRoots]. */
    fun decode(text: String?): ScopeChoice {
        val value = text?.trim().orEmpty()
        return when {
            value.isEmpty() || value == ALL -> ScopeChoice.AllRoots
            value == CURRENT_ROOT -> ScopeChoice.CurrentFileRoot
            value.startsWith(NAMED_PREFIX) ->
                value.removePrefix(NAMED_PREFIX).takeIf { it.isNotEmpty() }?.let { ScopeChoice.Named(it) } ?: ScopeChoice.AllRoots
            value.startsWith(ROOTS_PREFIX) -> ScopeChoice.Roots(
                value.removePrefix(ROOTS_PREFIX).split(',').map(::unescape).filter { it.isNotBlank() }.toSet(),
            )
            else -> ScopeChoice.AllRoots
        }
    }

    private fun escape(key: String): String = buildString(key.length) {
        for (c in key) {
            when (c) {
                '%' -> append("%25")
                ',' -> append("%2C")
                else -> append(c)
            }
        }
    }

    private fun unescape(text: String): String {
        if ('%' !in text) return text
        return buildString(text.length) {
            var i = 0
            while (i < text.length) {
                val c = text[i]
                val code = if (c == '%' && i + 2 < text.length) text.substring(i + 1, i + 3) else null
                when (code?.uppercase()) {
                    "25" -> { append('%'); i += 3 }
                    "2C" -> { append(','); i += 3 }
                    else -> { append(c); i++ }
                }
            }
        }
    }
}
