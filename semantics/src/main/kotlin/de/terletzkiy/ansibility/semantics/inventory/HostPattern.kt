package de.terletzkiy.ansibility.semantics.inventory

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** The hosts a pattern selects in one inventory, plus what Ansible would warn or fail about. */
data class HostPatternMatch(
    /** Matching host names, deduplicated, in Ansible's order. */
    val hosts: List<String>,
    /** Single patterns (without `&`/`!`) that matched no group and no host ("Could not match supplied host pattern"). */
    val unmatched: List<String>,
    /** Names in [hosts] that are the implicit localhost Ansible creates on demand (not defined in the inventory). */
    val implicitHosts: Set<String>,
    /** Patterns Ansible rejects: an invalid `~regex`, an out-of-range subscript, an unsupported `@file` limit. */
    val errors: List<String>,
)

/**
 * Port of `InventoryManager`'s host pattern handling (`split_host_pattern`, `order_patterns`,
 * `_evaluate_patterns`, `_match_one_pattern`, `_enumerate_matches`, subscripts and `subset`):
 *
 * - `,` separates patterns; `:` does too when the whole string is not a valid host identifier;
 * - `&p` intersects, `!p` excludes (applied after all plain patterns, whatever their position);
 * - a pattern matches group names first (all hosts of matching groups and their children) and host names when no
 *   group matched or the pattern contains `.`, `?`, `*`, `[` or starts with `~`;
 * - `*`/`?`/`[...]` are fnmatch wildcards, `~expr` is a regular expression matched at the start of the name;
 * - `all` (and `*`) select every host; `pattern[0]`, `pattern[-1]`, `pattern[1:3]`, `pattern[2:]` subscripts.
 *
 * `~` expressions use Java regex syntax, which accepts the common Python forms (`(?P<n>…)` is rewritten).
 */
object HostPattern {
    private val PATTERN_WITH_SUBSCRIPT: Pattern = Pattern.compile("""^(.+)\[(?:(-?[0-9]+)|([0-9]+)([:-])([0-9]*))\]$""")
    private val COLON_SEPARATED: Pattern =
        Pattern.compile("""(?:[^\s:\[\]]|\[[^\]]*\])+""", Pattern.UNICODE_CHARACTER_CLASS)
    private val LOCALHOST = listOf("127.0.0.1", "localhost", "::1")

    /** `split_host_pattern`: one pattern string → single patterns, whitespace trimmed, empties dropped. */
    fun split(pattern: String): List<String> {
        val parts: List<String> = if (',' in pattern) {
            pattern.split(',')
        } else if (HostAddress.parse(pattern, allowRanges = true) != null) {
            listOf(pattern)
        } else {
            val m = COLON_SEPARATED.matcher(pattern)
            buildList { while (m.find()) add(m.group()) }
        }
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** `split_host_pattern` for a list (a play's `hosts:` may be a list). */
    fun split(patterns: List<String>): List<String> = patterns.flatMap { split(it) }

    /** `order_patterns`: plain patterns first, then `&` intersections, then `!` exclusions; `all` if only modifiers. */
    fun order(patterns: List<String>): List<String> {
        val regular = ArrayList<String>()
        val intersection = ArrayList<String>()
        val exclude = ArrayList<String>()
        for (p in patterns) {
            if (p.isEmpty()) continue
            when (p[0]) {
                '!' -> exclude += p
                '&' -> intersection += p
                else -> regular += p
            }
        }
        if (regular.isEmpty()) regular += "all"
        return regular + intersection + exclude
    }

    /** `InventoryManager.get_hosts(pattern)` with an optional `--limit` ([limit], `subset()`). */
    fun resolve(graph: InventoryGraph, pattern: String, limit: String? = null): HostPatternMatch =
        resolve(graph, listOf(pattern), limit)

    /** Same as [resolve] for a list of pattern strings (a play's `hosts:` may be a list); each one is split. */
    fun resolve(graph: InventoryGraph, patterns: List<String>, limit: String?): HostPatternMatch {
        val evaluator = Evaluator(graph)
        var hosts = evaluator.evaluate(split(patterns))
        if (limit != null) {
            val subset = ArrayList<String>()
            for (p in split(limit)) {
                if (p.startsWith("@")) evaluator.errors += "Limit files are not supported: $p" else subset += p
            }
            val allowed = evaluator.evaluate(subset).toSet()
            hosts = hosts.filter { it in allowed }
        }
        val deduplicated = hosts.distinct()
        return HostPatternMatch(
            hosts = deduplicated,
            unmatched = evaluator.unmatched.distinct(),
            implicitHosts = evaluator.implicit.filterTo(LinkedHashSet()) { it in deduplicated },
            errors = evaluator.errors.distinct(),
        )
    }

    private class Evaluator(val graph: InventoryGraph) {
        val unmatched = ArrayList<String>()
        val implicit = LinkedHashSet<String>()
        val errors = ArrayList<String>()
        private val cache = HashMap<String, List<String>>()

        /** `_evaluate_patterns`. */
        fun evaluate(patterns: List<String>): List<String> {
            var hosts = ArrayList<String>()
            for (p in order(patterns)) {
                if (p in graph.hosts) {
                    hosts += p
                    continue
                }
                val that = matchOne(p)
                when (p[0]) {
                    '!' -> {
                        val exclude = that.toSet()
                        hosts = hosts.filterTo(ArrayList()) { it !in exclude }
                    }
                    '&' -> {
                        val keep = that.toSet()
                        hosts = hosts.filterTo(ArrayList()) { it in keep }
                    }
                    else -> {
                        val existing = hosts.toSet()
                        hosts.addAll(that.filter { it !in existing })
                    }
                }
            }
            return hosts
        }

        /** `_match_one_pattern`. */
        private fun matchOne(raw: String): List<String> {
            val pattern = if (raw[0] == '&' || raw[0] == '!') raw.substring(1) else raw
            if (pattern.isEmpty()) return emptyList()
            return cache.getOrPut(pattern) {
                val (expr, subscript) = splitSubscript(pattern)
                val hosts = enumerate(expr)
                applySubscript(hosts, subscript) ?: run {
                    errors += "No hosts matched the subscripted pattern '$pattern'"
                    emptyList()
                }
            }
        }

        /** `_split_subscript`: `(pattern, (start, end))`, end null for a single index, -1 for an open end. */
        private fun splitSubscript(pattern: String): Pair<String, Pair<Int, Int?>?> {
            if (pattern[0] == '~') return pattern to null
            val m = PATTERN_WITH_SUBSCRIPT.matcher(pattern)
            if (!m.find()) return pattern to null
            val expr = m.group(1)
            val index = m.group(2)
            if (index != null) return expr to ((index.toIntOrNull() ?: return pattern to null) to null)
            val start = m.group(3).toIntOrNull() ?: return pattern to null
            val end = m.group(5).ifEmpty { "-1" }.toIntOrNull() ?: return pattern to null
            return expr to (start to end)
        }

        /** `_apply_subscript` with Python slicing; null where Python raises IndexError. */
        private fun applySubscript(hosts: List<String>, subscript: Pair<Int, Int?>?): List<String>? {
            if (hosts.isEmpty() || subscript == null) return hosts
            val (start, endOrNull) = subscript
            if (endOrNull != null && endOrNull != 0) {
                val end = if (endOrNull == -1) hosts.size - 1 else endOrNull
                return pySlice(hosts, start, end + 1)
            }
            // `if end:` is false for 0, so `[x:0]` selects the single host x, like an index.
            val index = if (start < 0) hosts.size + start else start
            return if (index in hosts.indices) listOf(hosts[index]) else null
        }

        private fun pySlice(list: List<String>, start: Int, stop: Int): List<String> {
            fun clamp(i: Int) = (if (i < 0) list.size + i else i).coerceIn(0, list.size)
            val from = clamp(start)
            val to = clamp(stop)
            return if (from < to) list.subList(from, to).toList() else emptyList()
        }

        /** `_enumerate_matches`. */
        private fun enumerate(pattern: String): List<String> {
            val results = ArrayList<String>()
            val matcher = compile(pattern) ?: return results
            val matchingGroups = graph.groups.keys.filter(matcher)
            for (group in matchingGroups) results += graph.hostsOf(group)
            if (matchingGroups.isEmpty() || pattern[0] == '~' || pattern.any { it in ".?*[" }) {
                results += graph.hosts.keys.filter(matcher)
            }
            if (results.isEmpty() && pattern in LOCALHOST) {
                // get_host() returns the inventory's first localhost-like host, or creates an implicit one.
                val existing = graph.hosts.keys.firstOrNull { it in LOCALHOST }
                if (existing != null) {
                    results += existing
                } else {
                    results += pattern
                    implicit += pattern
                }
            }
            if (results.isEmpty() && matchingGroups.isEmpty() && pattern != "all") unmatched += pattern
            return results
        }

        /** `_match_list`'s compiled pattern: fnmatch, or a `~` regex matched at the start of the name. */
        private fun compile(pattern: String): ((String) -> Boolean)? {
            return try {
                if (pattern[0] == '~') {
                    val regex = Pattern.compile(pattern.substring(1).replace("(?P<", "(?<").replace(Regex("""\(\?P=(\w+)\)"""), """\\k<$1>"""))
                    ({ name: String -> regex.matcher(name).lookingAt() })
                } else {
                    val glob = Fnmatch.compile(pattern)
                    ({ name: String -> glob.matches(name) })
                }
            } catch (e: PatternSyntaxException) {
                errors += "Invalid host list pattern: $pattern"
                null
            } catch (e: IllegalArgumentException) {
                errors += "Invalid host list pattern: $pattern"
                null
            }
        }
    }
}
