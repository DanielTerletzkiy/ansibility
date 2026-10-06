package de.terletzkiy.ansibility.api

import com.intellij.openapi.util.TextRange

/** Where a host or group name sits (plan amendment R7/R8, F8.8). */
enum class HostConstruct {
    /** A play's `hosts:` pattern. */
    PLAY_HOSTS,

    /** A literal `delegate_to:` value. */
    DELEGATE_TO,

    /** `groups['g']` or `groups.g`. */
    GROUPS,

    /** `'g' in group_names`. */
    GROUP_NAMES,

    /** The host part of `hostvars['h']`. */
    HOSTVARS,
}

/** A host pattern: a play's `hosts:` (`web:&prod:!db`, `all`) or a literal `delegate_to:` host. */
data class HostPatternSite(val pattern: String, val construct: HostConstruct, override val range: TextRange) : AnsibleSite

/** A group or host name literal inside an expression: `groups['g']`, `groups.g`, `'g' in group_names`, `hostvars['h']`. */
data class InventoryNameSite(
    val name: String,
    val isGroup: Boolean,
    val construct: HostConstruct,
    override val range: TextRange,
) : AnsibleSite
