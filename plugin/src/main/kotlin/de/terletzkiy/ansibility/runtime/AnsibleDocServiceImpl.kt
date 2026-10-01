package de.terletzkiy.ansibility.runtime

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import de.terletzkiy.ansibility.api.AnsibleDocService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.DocAnchor
import de.terletzkiy.ansibility.api.DocKind
import de.terletzkiy.ansibility.api.DocSource
import de.terletzkiy.ansibility.api.DocSourceKind
import de.terletzkiy.ansibility.api.KeywordLevel
import de.terletzkiy.ansibility.api.ModuleSummary
import de.terletzkiy.ansibility.api.ResolvedModuleDoc
import de.terletzkiy.ansibility.api.ResolvedPluginDoc
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.schema.DocSnapshot
import de.terletzkiy.ansibility.semantics.schema.KeywordDoc
import de.terletzkiy.ansibility.semantics.schema.ModuleDoc
import de.terletzkiy.ansibility.semantics.schema.PluginKind
import de.terletzkiy.ansibility.semantics.schema.RouteResolution
import de.terletzkiy.ansibility.semantics.schema.Routing

/**
 * The [AnsibleDocService]: bundled snapshots ([DocSnapshotStore]) and the local install's refreshed docs
 * ([LocalDocRefresher]), merged per root in the order of plan A.11.
 *
 * Routing (redirects, deprecations, tombstones, aliases) comes from the target's bundled line; the other line uses
 * its own routing when it is consulted. A module the first source does not document is queued for a local refresh by
 * [moduleDoc], so the local docs fill the gaps of the snapshot without any process on the caller's thread;
 * [moduleSummaries] resolves the same way without queueing anything.
 */
class AnsibleDocServiceImpl(private val project: Project) : AnsibleDocService {
    private val refresher: LocalDocRefresher get() = LocalDocRefresher.getInstance(project)
    private val options: AnsibleRuntimeOptions get() = AnsibleRuntimeOptions.getInstance()
    private val store: DocSnapshotStore get() = DocSnapshotStore.getInstance()

    override val docsTracker: ModificationTracker = ModificationTracker {
        refresher.docsTracker.modificationCount + options.tracker.modificationCount
    }

    override fun moduleDoc(root: AnsibleRoot, fqcn: String): ResolvedModuleDoc? {
        val name = fqcn.trim()
        if (name.isEmpty()) return null
        val sources = sources(root)
        val (resolved, missingFromPrimary) = resolveModule(sources, name)
        if (missingFromPrimary != null) requestLocal(root, missingFromPrimary)
        return resolved
    }

    override fun moduleSummaries(root: AnsibleRoot): Collection<ModuleSummary> {
        val sources = sources(root)
        return allModules(sources).map { name ->
            ProgressManager.checkCanceled()
            ModuleSummary(name, resolveModule(sources, name).first)
        }
    }

    /**
     * The routed documentation of [name] from [sources] (first hit wins), and the canonical name to refresh locally
     * when the first source does not document it (null when it does). Has no side effect.
     */
    private fun resolveModule(sources: Sources, name: String): Pair<ResolvedModuleDoc?, String?> {
        val primaryRoute = sources.bundledPrimary.snapshot.routing.resolve(name, PluginKind.MODULE)
        for ((index, source) in sources.ordered.withIndex()) {
            ProgressManager.checkCanceled()
            val (route, doc) = source.module(name, primaryRoute) ?: continue
            val resolved = ResolvedModuleDoc(route, doc, source.info, DocUrls.module(sources.base, route.canonical))
            return resolved to primaryRoute.canonical.takeIf { index > 0 }
        }
        val resolved = primaryRoute.takeIf { it.isNoteworthy() }
            ?.let { ResolvedModuleDoc(it, null, null, DocUrls.module(sources.base, it.canonical)) }
        return resolved to primaryRoute.canonical
    }

    override fun keywordDoc(root: AnsibleRoot, name: String, level: KeywordLevel?): KeywordDoc? {
        val doc = sources(root).bundledPrimary.snapshot.keyword(name.trim()) ?: return null
        if (level == null) return doc
        return doc.takeIf { appliesToLabels(level).any(doc.appliesTo::contains) }
    }

    override fun keywords(root: AnsibleRoot): Collection<KeywordDoc> = sources(root).bundledPrimary.snapshot.keywords.values

    override fun filterDoc(root: AnsibleRoot, name: String): ResolvedPluginDoc? = pluginDoc(root, name, PluginKind.FILTER)

    override fun testDoc(root: AnsibleRoot, name: String): ResolvedPluginDoc? = pluginDoc(root, name, PluginKind.TEST)

    override fun lookupDoc(root: AnsibleRoot, name: String): ResolvedPluginDoc? = pluginDoc(root, name, PluginKind.LOOKUP)

    override fun allModules(root: AnsibleRoot): Collection<String> = allModules(sources(root))

    private fun allModules(sources: Sources): Collection<String> {
        val names = sortedSetOf<String>()
        val primary = sources.bundledPrimary.snapshot
        names += primary.modules.keys
        names += primary.routing.moduleAliases.keys
        for ((redirected, entry) in primary.routing.section(PluginKind.MODULE.routingKey)) {
            // ansible.builtin's own table maps the 2.9 short names to collections; only collection-level redirects are real names.
            if (entry.redirect != null && entry.tombstone == null && !redirected.startsWith(BUILTIN)) names += redirected
        }
        sources.local?.let { local ->
            names += local.snapshot.modules.keys
            names += local.snapshot.routing.moduleAliases.keys
        }
        return names
    }

    override fun docsUrl(root: AnsibleRoot, kind: DocKind, name: String, anchor: DocAnchor?): String {
        val sources = sources(root)
        return when (kind) {
            DocKind.MODULE -> DocUrls.module(sources.base, moduleDoc(root, name)?.canonical ?: Routing.normalise(name.trim()), anchor)
            DocKind.KEYWORD -> {
                val section = (anchor as? DocAnchor.KeywordSection)?.let { DocUrls.section(it.level) }
                    ?: keywordDoc(root, name)?.appliesTo?.firstNotNullOfOrNull(DocUrls::section)
                DocUrls.keywords(sources.base, section)
            }
            DocKind.FILTER -> pluginUrl(root, sources, name, PluginKind.FILTER, anchor)
            DocKind.TEST -> pluginUrl(root, sources, name, PluginKind.TEST, anchor)
            DocKind.LOOKUP -> pluginUrl(root, sources, name, PluginKind.LOOKUP, anchor)
        }
    }

    override fun primarySource(root: AnsibleRoot): DocSource = sources(root).ordered.first().info

    // ------------------------------------------------------------------------------------------------ sources

    private fun pluginDoc(root: AnsibleRoot, name: String, kind: PluginKind): ResolvedPluginDoc? {
        val text = name.trim()
        if (text.isEmpty()) return null
        val sources = sources(root)
        for (source in listOf(sources.bundledPrimary, sources.bundledOther)) {
            ProgressManager.checkCanceled()
            val lookup = source.snapshot.plugin(text, kind)
            val doc = lookup.doc ?: continue
            val url = if (doc.jinjaBuiltin) DocUrls.jinjaBuiltin(kind, lookup.resolution.canonical) else DocUrls.plugin(sources.base, kind, lookup.resolution.canonical)
            return ResolvedPluginDoc(kind, lookup.resolution, doc, source.info, url)
        }
        val route = sources.bundledPrimary.snapshot.routing.resolve(text, kind)
        if (!route.isNoteworthy()) return null
        return ResolvedPluginDoc(kind, route, null, null, DocUrls.plugin(sources.base, kind, route.canonical))
    }

    private fun pluginUrl(root: AnsibleRoot, sources: Sources, name: String, kind: PluginKind, anchor: DocAnchor?): String {
        val resolved = pluginDoc(root, name, kind)
        if (resolved?.doc?.jinjaBuiltin == true) return resolved.docsUrl
        val canonical = resolved?.canonical ?: Routing.normalise(name.trim())
        return DocUrls.plugin(sources.base, kind, canonical, anchor)
    }

    private fun requestLocal(root: AnsibleRoot, canonical: String) {
        if (options.localDocRefresh) refresher.requestRefresh(root, listOf(canonical))
    }

    private fun sources(root: AnsibleRoot): Sources {
        val target = TargetVersionDetector.getInstance(project).targetVersion(root).version
        val primaryLine = DocSnapshotStore.lineFor(target)
        val primary = BundledSource(primaryLine, target)
        val other = BundledSource(primaryLine.other, target)
        val local = refresher.localDocs(root)?.let { LocalSource(it, target) }
        val ordered = when {
            local == null -> listOf(primary, local, other)
            // The local install goes first only when it is on the target's line and the bundled line is not.
            local.info.matchesTarget && !primary.info.matchesTarget -> listOf(local, primary, other)
            else -> listOf(primary, local, other)
        }.filterNotNull()
        return Sources(ordered, primary, other, local, DocUrls.base(options.docsBase, target))
    }

    private class Sources(
        val ordered: List<Source>,
        val bundledPrimary: BundledSource,
        val bundledOther: BundledSource,
        val local: LocalSource?,
        val base: String,
    )

    private sealed class Source {
        abstract val snapshot: DocSnapshot
        abstract val info: DocSource

        /** The route and docs of [name] in this source; [primaryRoute] is the target line's routing of it. */
        abstract fun module(name: String, primaryRoute: RouteResolution): Pair<RouteResolution, ModuleDoc>?
    }

    private inner class BundledSource(val line: BundledLine, private val target: CoreVersion?) : Source() {
        override val snapshot: DocSnapshot by lazy { store.snapshot(line) }

        override val info: DocSource by lazy {
            val core = snapshot.coreVersion
            val key = if (line == BundledLine.PINNED) "source.bundled.pinned" else "source.bundled.latest"
            DocSource(DocSourceKind.BUNDLED, core, AnsibilityRuntimeBundle.message(key, snapshot.core), sameLine(core, target), snapshot.collections)
        }

        override fun module(name: String, primaryRoute: RouteResolution): Pair<RouteResolution, ModuleDoc>? {
            val lookup = snapshot.module(name)
            return lookup.doc?.let { lookup.resolution to it }
        }
    }

    private class LocalSource(docs: LocalDocs, target: CoreVersion?) : Source() {
        override val snapshot: DocSnapshot = docs.catalog.snapshot

        override val info: DocSource =
            DocSource(DocSourceKind.LOCAL, docs.core, docs.label, sameLine(docs.core, target), docs.catalog.collections)

        override fun module(name: String, primaryRoute: RouteResolution): Pair<RouteResolution, ModuleDoc>? {
            // Local docs carry no routing of their own beyond aliases: follow the target line's redirects.
            val canonical = primaryRoute.canonical
            snapshot.modules[canonical]?.let { return primaryRoute to it.copy(fqcn = primaryRoute.requested) }
            val aliased = snapshot.routing.moduleAliases[canonical] ?: return null
            val doc = snapshot.modules[aliased] ?: return null
            return primaryRoute.copy(canonical = aliased, isAlias = true) to doc.copy(fqcn = primaryRoute.requested)
        }
    }

    companion object {
        private const val BUILTIN = "ansible.builtin."

        /** True when [core] is on [target]'s minor line; an unknown target never matches. */
        fun sameLine(core: CoreVersion?, target: CoreVersion?): Boolean =
            core != null && target != null && core.major == target.major && core.minor == target.minor

        /** The snapshot `applies_to` labels of a structural level. */
        fun appliesToLabels(level: KeywordLevel): List<String> = when (level) {
            // A playbook's top-level items are plays or `import_playbook` entries; classifiers may not know which yet.
            KeywordLevel.PLAY -> listOf("Play", "PlaybookInclude")
            KeywordLevel.PLAYBOOK_INCLUDE -> listOf("PlaybookInclude")
            KeywordLevel.ROLE_ENTRY -> listOf("Role")
            KeywordLevel.BLOCK -> listOf("Block")
            KeywordLevel.TASK -> listOf("Task")
            KeywordLevel.HANDLER -> listOf("Handler")
            KeywordLevel.LOOP_CONTROL -> listOf("LoopControl")
        }

        /** A route worth reporting without docs: it redirects, is an alias, or carries a notice. */
        private fun RouteResolution.isNoteworthy(): Boolean = isRedirected || isAlias || tombstone != null || deprecations.isNotEmpty()
    }
}
