package de.terletzkiy.ansibility.semantics.keywords

import de.terletzkiy.ansibility.semantics.CoreVersion

/**
 * What ansible-core does with keys that are not keywords of the object they sit on (ANS-K002), from 2.18.8 and
 * 2.21.4:
 *
 * - a play, block, `import_playbook` entry or `loop_control` fails with `'x' is not a valid attribute for a Block`
 *   (`FieldAttributeBase._validate_attributes`);
 * - a task or handler with a second non-keyword key fails with `conflicting action statements: a, b`
 *   (`ModuleArgsParser.parse(skip_action_validation=True)` in `load_list_of_tasks`, whatever the keys are), and a
 *   `with_x` key that is no lookup with `'with_x' is not a valid attribute for a Task` (unless
 *   `invalid_task_attribute_failed` is off, which only warns);
 * - a dynamic include (`include_tasks`, `include_role`) accepts only [dynamicIncludeKeywords] and fails with
 *   `'become' is not a valid attribute for a TaskInclude` otherwise (same setting);
 * - keys of a role entry that are not keywords are role parameters, never errors.
 */
object UnknownKeywords {
    private val V2_19 = CoreVersion(2, 19, 0)

    /** The shortest key [suggestion] proposes a keyword for. */
    const val MIN_SUGGESTED_LENGTH: Int = 3

    /** `TaskInclude.VALID_INCLUDE_KEYWORDS` (identical in 2.18.8 and 2.21.4). */
    val DYNAMIC_INCLUDE_KEYWORDS: Set<String> = setOf(
        "action", "args", "collections", "debugger", "ignore_errors", "loop", "loop_control", "loop_with", "name",
        "no_log", "register", "run_once", "tags", "timeout", "vars", "when",
    )

    /** The keywords a dynamic include accepts; handlers (`HandlerTaskInclude`) also accept `listen`. */
    fun dynamicIncludeKeywords(handler: Boolean): Set<String> = if (handler) DYNAMIC_INCLUDE_KEYWORDS + "listen" else DYNAMIC_INCLUDE_KEYWORDS

    /** The class an include's error names: `TaskInclude`/`HandlerTaskInclude` for `include_tasks`, `IncludeRole` for `include_role`. */
    fun includeClassName(role: Boolean, handler: Boolean): String = when {
        role -> "IncludeRole"
        handler -> "HandlerTaskInclude"
        else -> "TaskInclude"
    }

    /**
     * Keys ansible-core accepts on [owner] although the keyword docs do not list them: the private or aliased
     * attributes `async_val` and `loop_with` of tasks, `user` on plays (`Play.preprocess_data` renames it to
     * `remote_user`), and 2.18's `vars_val` attribute of `import_playbook` entries.
     */
    fun undocumentedKeys(owner: PlaybookObject, version: CoreVersion): Set<String> = when (owner) {
        PlaybookObject.TASK, PlaybookObject.HANDLER -> setOf("async_val", "loop_with")
        PlaybookObject.PLAY -> setOf("user")
        PlaybookObject.PLAYBOOK_INCLUDE -> if (version < V2_19) setOf("vars_val") else emptySet()
        else -> emptySet()
    }

    /** ansible-core's error for a key that is not an attribute of [className]. */
    fun notAnAttribute(key: String, className: String): String = "'$key' is not a valid attribute for a $className"

    /** ansible-core's error for a task with two action keys, in the order they appear. */
    fun conflictingActions(first: String, second: String): String = "conflicting action statements: $first, $second"

    /**
     * The candidate [key] was most likely meant to be: the closest by edit distance (adjacent transpositions count
     * one edit) within 2 edits and at most half of [key]'s length; ties go to the candidate sharing the longer prefix,
     * then to the alphabetically first. Keys shorter than [MIN_SUGGESTED_LENGTH] get no suggestion, since one edit
     * there already changes half the key. `become_usr` → `become_user`, `delegat_to` → `delegate_to`.
     */
    fun suggestion(key: String, candidates: Collection<String>): String? {
        if (key.length < MIN_SUGGESTED_LENGTH) return null
        val maxDistance = minOf(2, key.length / 2)
        return candidates.asSequence()
            .filter { it != key && it.isNotEmpty() }
            .map { Triple(it, distance(key, it), commonPrefix(key, it)) }
            .filter { it.second <= maxDistance }
            .sortedWith(compareBy<Triple<String, Int, Int>> { it.second }.thenByDescending { it.third }.thenBy { it.first })
            .firstOrNull()?.first
    }

    private fun commonPrefix(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return i
    }

    /** Optimal string alignment distance (Levenshtein plus adjacent transpositions). */
    internal fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var best = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) best = minOf(best, d[i - 2][j - 2] + 1)
                d[i][j] = best
            }
        }
        return d[a.length][b.length]
    }
}
