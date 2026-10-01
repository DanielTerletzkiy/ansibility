package de.terletzkiy.ansibility.types

import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.schema.OptionType
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue

/**
 * Walks finding paths (`["haproxy_servers", "0", "port"]`: the variable, then keys and list indices) through a value
 * and through its option spec.
 */
internal object SpecPaths {
    /** Whether [segment] is a list index. */
    fun isIndex(segment: String): Boolean = segment.isNotEmpty() && segment.all { it in '0'..'9' }

    /** The option names of [path] without list indices (`["keycloak_clients", "secret"]`). */
    fun optionNames(path: List<String>): List<String> = path.filterNot(::isIndex)

    /**
     * The value at [path] below [top], the variable's value ([path]'s first segment names the variable itself and
     * is skipped). A repeated key finds the last occurrence, as PyYAML loads it. Null when the path leaves the value.
     */
    fun valueAt(top: YValue, path: List<String>): YValue? {
        var current: YValue = top
        for (segment in path.drop(1)) {
            current = when (current) {
                is YMap -> current[segment]
                is YSeq -> segment.toIntOrNull()?.let { current.items.getOrNull(it) }
                else -> null
            } ?: return null
        }
        return current
    }

    /** The option whose value sits at [path] below the top-level [option] (list indices are skipped), or null. */
    fun optionAt(option: OptionSpec, path: List<String>): OptionSpec? {
        var current = option
        for (segment in path.drop(1)) {
            if (isIndex(segment)) continue
            current = current.options?.get(segment) ?: return null
        }
        return current
    }

    /**
     * The documented type of the value at [path]: the option's `elements` type for a list item, otherwise the
     * option's type (`str` for `["totp_users", "0"]` with `elements: str`).
     */
    fun documentedTypeAt(option: OptionSpec, path: List<String>): OptionType? {
        val target = optionAt(option, path) ?: return null
        return if (path.size > 1 && isIndex(path.last())) target.elements else target.type
    }

    /** True when an option along [path] (the top-level [option] included) is `no_log`. */
    fun crossesNoLog(option: OptionSpec, path: List<String>): Boolean {
        var current = option
        if (current.noLog) return true
        for (segment in path.drop(1)) {
            if (isIndex(segment)) continue
            current = current.options?.get(segment) ?: return false
            if (current.noLog) return true
        }
        return false
    }
}
