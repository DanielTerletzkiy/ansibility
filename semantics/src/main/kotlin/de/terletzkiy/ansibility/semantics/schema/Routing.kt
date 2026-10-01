package de.terletzkiy.ansibility.semantics.schema

/** The documented plugin types of a snapshot. [routingKey] is the section name in `plugin_routing`. */
enum class PluginKind(val routingKey: String) {
    MODULE("modules"),
    FILTER("filter"),
    TEST("test"),
    LOOKUP("lookup"),
}

/** A `deprecation` or `tombstone` notice from `plugin_routing` (`meta/runtime.yml`). */
data class RouteNotice(
    val warningText: String? = null,
    val removalVersion: String? = null,
    val removalDate: String? = null,
)

/** One `plugin_routing` entry, keyed by the full name of the routed plugin. */
data class RouteEntry(
    val redirect: String? = null,
    val deprecation: RouteNotice? = null,
    val tombstone: RouteNotice? = null,
)

/** A routing notice together with the name whose entry carries it. */
data class RoutedNotice(val name: String, val notice: RouteNotice)

/**
 * The result of [Routing.resolve].
 *
 * [chain] starts with the requested name (normalised to an FQCN) and lists every redirect target in order, so
 * `community.mysql.mysql_user` gives `[community.mysql.mysql_user, ansible.mysql.mysql_user]`. [canonical] is the
 * plugin whose documentation and web page apply: the end of the chain with documentation aliases applied
 * (`ansible.builtin.systemd` → `ansible.builtin.systemd_service`, which [isAlias] reports).
 */
data class RouteResolution(
    val requested: String,
    val canonical: String,
    val chain: List<String>,
    /** Deprecation notices met along the chain, in order. */
    val deprecations: List<RoutedNotice> = emptyList(),
    /** Set when a name on the chain has been removed: ansible-core fails with the tombstone's text. */
    val tombstone: RoutedNotice? = null,
    val isAlias: Boolean = false,
    /** Set when the redirects form a loop (ansible-core fails with "redirect loop"); [canonical] is where it stopped. */
    val loop: Boolean = false,
) {
    val isRedirected: Boolean get() = chain.size > 1
}

/**
 * Redirects, deprecations and tombstones from `ansible/config/ansible_builtin_runtime.yml` and each collection's
 * `meta/runtime.yml`, plus module documentation aliases. Names in [entries] are full names (`collection.name`).
 *
 * Module resolution follows ansible-core: the `modules` routing entry, or else the `action` entry (tasks resolve the
 * action plugin first, which is where `ansible.builtin.include`'s tombstone lives).
 */
class Routing(
    private val entries: Map<String, Map<String, RouteEntry>>,
    /** Module name → the module whose documentation it shares (a symlinked module, e.g. `systemd`). */
    val moduleAliases: Map<String, String> = emptyMap(),
) {
    /** All entries of one `plugin_routing` section (`modules`, `action`, `filter`, `test`, `lookup`). */
    fun section(name: String): Map<String, RouteEntry> = entries[name].orEmpty()

    /** The routing entry for [fqcn] of [kind], if any. */
    fun entry(fqcn: String, kind: PluginKind = PluginKind.MODULE): RouteEntry? {
        val direct = entries[kind.routingKey]?.get(fqcn)
        return if (direct == null && kind == PluginKind.MODULE) entries["action"]?.get(fqcn) else direct
    }

    /**
     * Resolves [name] of [kind] through redirects and aliases. A short name (`copy`) and an `ansible.legacy.` name
     * are read as `ansible.builtin.` names; a play's `collections:` search list is the caller's business.
     */
    fun resolve(name: String, kind: PluginKind = PluginKind.MODULE): RouteResolution {
        val requested = normalise(name)
        val chain = mutableListOf(requested)
        val deprecations = mutableListOf<RoutedNotice>()
        var tombstone: RoutedNotice? = null
        var loop = false
        var current = requested
        while (true) {
            val entry = entry(current, kind) ?: break
            entry.deprecation?.let { deprecations += RoutedNotice(current, it) }
            if (entry.tombstone != null) {
                tombstone = RoutedNotice(current, entry.tombstone)
                break
            }
            val target = entry.redirect?.let(::normalise) ?: break
            if (target in chain) {
                loop = true
                break
            }
            chain += target
            current = target
        }
        val aliased = if (kind == PluginKind.MODULE) moduleAliases[current] else null
        return RouteResolution(
            requested = requested,
            canonical = aliased ?: current,
            chain = chain,
            deprecations = deprecations,
            tombstone = tombstone,
            isAlias = aliased != null,
            loop = loop,
        )
    }

    /** Number of entries with a redirect, over all sections. */
    val redirectCount: Int get() = entries.values.sumOf { section -> section.values.count { it.redirect != null } }

    companion object {
        private const val BUILTIN = "ansible.builtin."
        private const val LEGACY = "ansible.legacy."

        /** `copy` and `ansible.legacy.copy` → `ansible.builtin.copy`; FQCNs are returned unchanged. */
        fun normalise(name: String): String = when {
            name.startsWith(LEGACY) -> BUILTIN + name.removePrefix(LEGACY)
            '.' !in name -> BUILTIN + name
            else -> name
        }
    }
}
