package de.terletzkiy.ansibility.completion.tasks

import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.completion.impl.CamelHumpMatcher

/**
 * The prefix matcher of task keys: a name matches when the platform's camel-hump matcher accepts the whole name or,
 * for a dotted FQCN, its last segment, so `templ` finds `ansible.builtin.template` and `ansible.builtin.tem` finds
 * it too.
 *
 * Matching the short name here rather than adding it as a second lookup string keeps the ranking intact: the
 * platform lifts an item whose lookup string is a prefix of another item's (`apt_repo` before `apt_repository`)
 * across priority groups, which would pull unrelated collections in between the `ansible.builtin` modules.
 */
internal class ModuleNameMatcher(prefix: String) : PrefixMatcher(prefix) {
    private val delegate = CamelHumpMatcher(prefix)

    override fun prefixMatches(name: String): Boolean =
        delegate.prefixMatches(name) || shortName(name)?.let(delegate::prefixMatches) == true

    override fun isStartMatch(name: String): Boolean =
        delegate.isStartMatch(name) || shortName(name)?.let(delegate::isStartMatch) == true

    override fun matchingDegree(string: String): Int {
        val whole = if (delegate.prefixMatches(string)) delegate.matchingDegree(string) else Int.MIN_VALUE
        val short = shortName(string)?.takeIf(delegate::prefixMatches)?.let(delegate::matchingDegree) ?: Int.MIN_VALUE
        return maxOf(whole, short).takeIf { it != Int.MIN_VALUE } ?: 0
    }

    override fun cloneWithPrefix(prefix: String): PrefixMatcher = if (prefix == myPrefix) this else ModuleNameMatcher(prefix)

    override fun toString(): String = "ModuleNameMatcher($myPrefix)"

    private fun shortName(name: String): String? {
        val dot = name.lastIndexOf('.')
        return if (dot < 0 || dot == name.length - 1) null else name.substring(dot + 1)
    }
}
