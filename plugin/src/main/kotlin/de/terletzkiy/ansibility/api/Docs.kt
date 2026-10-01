package de.terletzkiy.ansibility.api

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.schema.Deprecation
import de.terletzkiy.ansibility.semantics.schema.KeywordDoc
import de.terletzkiy.ansibility.semantics.schema.ModuleDoc
import de.terletzkiy.ansibility.semantics.schema.PluginDoc
import de.terletzkiy.ansibility.semantics.schema.PluginKind
import de.terletzkiy.ansibility.semantics.schema.RouteResolution
import de.terletzkiy.ansibility.semantics.schema.RoutedNotice

/** The kinds of page on the Ansible documentation site (plan F5.5). */
enum class DocKind { MODULE, KEYWORD, FILTER, TEST, LOOKUP }

/** A fragment of a documentation page (plan X18). */
sealed interface DocAnchor {
    /** `#parameter-a/b`: an option; sub-options are joined by `/` (`healthcheck`, `interval`). */
    data class Parameter(val path: List<String>) : DocAnchor

    /** `#return-a/b`: a return value (`stat`, `exists`). */
    data class ReturnValue(val path: List<String>) : DocAnchor

    /**
     * A section of the playbook keywords page: `#play`, `#role`, `#block` or `#task`. The page has no anchor per
     * keyword; handler and `loop_control` keywords live in the task section.
     */
    data class KeywordSection(val level: KeywordLevel) : DocAnchor
}

/** Whether documentation comes from a snapshot shipped with the plugin or from the local Ansible install. */
enum class DocSourceKind { BUNDLED, LOCAL }

/** Where a piece of documentation came from; doc popups show [label] as their source banner (plan X23). */
data class DocSource(
    val kind: DocSourceKind,
    /** The ansible-core version the docs were generated from. */
    val core: CoreVersion?,
    /** For example "ansible-core 2.18.8 bundled + pinned collections" or "local ansible-core 2.21.4". */
    val label: String,
    /**
     * True when [core] is on the target's minor line (2.18.8 docs for a 2.18.x target). A module or keyword finding
     * from docs that do not match the target is capped at WARNING (plan A.6); an unknown target never matches.
     */
    val matchesTarget: Boolean,
    /** Collection name → version of the source, `ansible.builtin` included. */
    val collections: Map<String, String> = emptyMap(),
)

/**
 * A module's documentation with routing applied (plan A.11 "Routing"): the name as written, the canonical module
 * whose documentation and web page apply, the redirect chain, and the deprecation and tombstone notices.
 *
 * `community.mysql.mysql_user` resolves to `ansible.mysql.mysql_user` with a routing deprecation (removal 6.0.0);
 * `ansible.builtin.systemd` resolves to the `ansible.builtin.systemd_service` docs and page.
 */
data class ResolvedModuleDoc(
    val route: RouteResolution,
    /** The canonical module's documentation, or null when no source documents it (a tombstone, or an unknown redirect target). */
    val doc: ModuleDoc?,
    /** Where [doc] came from; null when [doc] is null. */
    val source: DocSource?,
    /** The web page of the canonical module (plan D11), for Ctrl+B and the popup's external link. */
    val docsUrl: String,
) {
    /** The requested name as an FQCN (`copy` → `ansible.builtin.copy`). */
    val requested: String get() = route.requested

    /** The module whose documentation applies. */
    val canonical: String get() = route.canonical

    /** The requested name followed by every redirect target, in order. */
    val redirectChain: List<String> get() = route.chain

    val isRedirected: Boolean get() = route.isRedirected

    /** Deprecations from `plugin_routing` met along the redirect chain. */
    val routingDeprecations: List<RoutedNotice> get() = route.deprecations

    /** Set when a name on the chain was removed; ansible-core fails with its text. */
    val tombstone: RoutedNotice? get() = route.tombstone

    /** The module's own deprecation in the docs of the source (it depends on the version: `apt_repository` from 2.21). */
    val deprecation: Deprecation? get() = doc?.deprecated

    /** True when the name is deprecated through routing or the module itself is deprecated. */
    val isDeprecated: Boolean get() = deprecation != null || route.deprecations.isNotEmpty()
}

/**
 * One module name a root's documentation sources offer (an FQCN, a documentation alias or a collection redirect, as
 * [AnsibleDocService.allModules] lists it) with its routed documentation.
 */
data class ModuleSummary(
    /** The name as offered, e.g. `ansible.builtin.systemd` or `community.mysql.mysql_user`. */
    val name: String,
    /**
     * The routed documentation of [name], as [AnsibleDocService.moduleDoc] would return it; null when no source
     * documents the name and its routing carries nothing worth showing.
     */
    val doc: ResolvedModuleDoc?,
)

/** Documentation of a filter, test or lookup plugin with routing applied, like [ResolvedModuleDoc]. */
data class ResolvedPluginDoc(
    val kind: PluginKind,
    val route: RouteResolution,
    val doc: PluginDoc?,
    val source: DocSource?,
    /** The plugin's web page; Jinja2's own filters and tests link to the Jinja documentation. */
    val docsUrl: String,
) {
    val requested: String get() = route.requested
    val canonical: String get() = route.canonical
    val tombstone: RoutedNotice? get() = route.tombstone
    val isDeprecated: Boolean get() = doc?.deprecated != null || route.deprecations.isNotEmpty()
}

/**
 * Project service (implemented in `runtime`): module, keyword, filter, test and lookup documentation per root.
 *
 * Sources per root, first hit wins (plan A.11): the source that matches the root's target ansible-core (the bundled
 * 2.18.8 snapshot for a 2.18 target, the bundled latest line for 2.19 and later), then the local install's docs
 * (refreshed in the background, D14), then the other bundled line. Keywords, filters, tests and lookups come from
 * the bundled lines only. Every call is cheap after the first load of a snapshot and never runs a process; calls
 * may happen in read actions.
 */
interface AnsibleDocService {
    /** Docs of [fqcn] (an FQCN, a short name or an `ansible.legacy.` name); null when nothing knows the module. */
    fun moduleDoc(root: AnsibleRoot, fqcn: String): ResolvedModuleDoc?

    /**
     * A playbook keyword on the root's target line; `with_<lookup>` loops resolve to the `with_<lookup>` entry.
     * With a [level], null unless the keyword applies there ([KeywordLevel.PLAY] also accepts `import_playbook`
     * entries' keywords, which have no level of their own).
     */
    fun keywordDoc(root: AnsibleRoot, name: String, level: KeywordLevel? = null): KeywordDoc?

    /** All keywords of the root's target line (the caller filters by level). */
    fun keywords(root: AnsibleRoot): Collection<KeywordDoc>

    /**
     * A Jinja filter by FQCN or short name (`to_json`, `community.general.json_query`); Jinja2's own filters
     * (`default`) are `ansible.builtin.` names. Null when nothing knows it.
     */
    fun filterDoc(root: AnsibleRoot, name: String): ResolvedPluginDoc?

    /** A Jinja test (`defined`, `ansible.builtin.version`), like [filterDoc]. */
    fun testDoc(root: AnsibleRoot, name: String): ResolvedPluginDoc?

    /** A lookup plugin (`file`, `community.general.passwordstore`), like [filterDoc]. */
    fun lookupDoc(root: AnsibleRoot, name: String): ResolvedPluginDoc?

    /**
     * Module names for completion: every module documented by the target's source or the local install, plus
     * documentation aliases (`ansible.builtin.systemd`) and collection redirects (`community.mysql.mysql_user`).
     */
    fun allModules(root: AnsibleRoot): Collection<String>

    /**
     * Every name of [allModules] with its routed documentation, for listings such as completion. Unlike
     * [moduleDoc], this never queues a local documentation refresh, so listing all modules (including redirect
     * targets that no source documents) has no side effect. Sorted by name.
     */
    fun moduleSummaries(root: AnsibleRoot): Collection<ModuleSummary>

    /**
     * The web page for [name] of [kind], built from the canonical name, under the root's versioned docs tree
     * (core 2.18 → `https://docs.ansible.com/ansible/11/`, plan D11). For [DocKind.KEYWORD], [name] is the keyword
     * and the page is `reference_appendices/playbooks_keywords.html` with a section anchor.
     */
    fun docsUrl(root: AnsibleRoot, kind: DocKind, name: String, anchor: DocAnchor? = null): String

    /** The source consulted first for [root] (for banners that are not about one doc). */
    fun primarySource(root: AnsibleRoot): DocSource

    /** Bumped whenever new documentation arrives (a local refresh finished); cached values built from docs depend on it. */
    val docsTracker: ModificationTracker

    companion object {
        fun getInstance(project: Project): AnsibleDocService = project.service()
    }
}

/** An ansible-core install found on this machine (project SDK first, then PATH). */
data class LocalAnsibleInstall(
    val coreVersion: CoreVersion,
    /** The `ansible` executable that reported [coreVersion]. */
    val executable: String,
    /** `ansible python module location`, when reported. */
    val moduleLocation: String? = null,
    /** `ansible collection location`, in search order. */
    val collectionPaths: List<String> = emptyList(),
)

/**
 * Application service (implemented in `runtime`): what the local Ansible install reports, for the D10 fallback
 * "target guessed from local install". Never blocks and never runs a process on the calling thread.
 */
interface LocalAnsibleRuntime {
    /**
     * The local install as seen from [project] (its Python SDK first, then PATH), or null while unknown. The first
     * call starts a background probe (`ansible --version`); [probeTracker] is bumped when a result arrives.
     */
    fun localInstall(project: Project): LocalAnsibleInstall?

    /** Bumped when a probe finds an install. */
    val probeTracker: ModificationTracker

    companion object {
        /** The registered implementation, or null when the runtime area is not loaded. */
        fun getInstanceOrNull(): LocalAnsibleRuntime? = ApplicationManager.getApplication().serviceOrNull()
    }
}
