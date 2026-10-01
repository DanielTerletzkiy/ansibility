package de.terletzkiy.ansibility.runtime

import de.terletzkiy.ansibility.api.DocAnchor
import de.terletzkiy.ansibility.api.KeywordLevel
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.schema.PluginKind

/**
 * Builds Ansible documentation URLs (plan F5.5, X18, D11). Pages are addressed by the canonical FQCN, the way
 * ansible-core links them (`collections/<ns>/<coll>/<name>_<type>.html`, `cli/doc.py`); anchors follow the
 * antsibull-docs templates (`#parameter-a/b`, `#return-a/b`). The plugin never fetches these pages.
 */
object DocUrls {
    /** The root of the versioned trees (`<SITE>11/`, `<SITE>latest/`). */
    const val SITE: String = "https://docs.ansible.com/ansible/"

    /** The playbook keywords page, relative to a tree. */
    const val KEYWORDS_PAGE: String = "reference_appendices/playbooks_keywords.html"

    private const val JINJA_TEMPLATES = "https://jinja.palletsprojects.com/en/stable/templates/"

    /**
     * The Ansible community package that ships [core]: since Ansible 3 (core 2.10) package N ships core 2.(N+7),
     * so 11 ↔ 2.18, 12 ↔ 2.19, 13 ↔ 2.20 and 14 ↔ 2.21. Null for older cores.
     */
    fun packageMajor(core: CoreVersion): Int? = if (core.major == 2 && core.minor >= 10) core.minor - 7 else null

    /** The tree for a root: versioned by the target (falling back to `latest`), `latest`, or a custom root. */
    fun base(policy: DocsBase, target: CoreVersion?): String = when (policy) {
        DocsBase.TargetVersioned -> target?.let(::packageMajor)?.let { "$SITE$it/" } ?: "${SITE}latest/"
        DocsBase.Latest -> "${SITE}latest/"
        is DocsBase.Custom -> policy.url
    }

    /** A module page: `<base>collections/ansible/builtin/systemd_service_module.html#parameter-name`. */
    fun module(base: String, canonical: String, anchor: DocAnchor? = null): String = pluginPage(base, canonical, "module") + fragment(anchor)

    /** A filter, test or lookup page (`_filter.html`, `_test.html`, `_lookup.html`). */
    fun plugin(base: String, kind: PluginKind, canonical: String, anchor: DocAnchor? = null): String =
        pluginPage(base, canonical, pageSuffix(kind)) + fragment(anchor)

    /** Jinja2's own filters and tests have no Ansible page; they link to the Jinja template documentation. */
    fun jinjaBuiltin(kind: PluginKind, canonical: String): String {
        val name = canonical.substringAfterLast('.')
        val section = if (kind == PluginKind.TEST) "tests" else "filters"
        return "$JINJA_TEMPLATES#jinja-$section.$name"
    }

    /** The playbook keywords page, optionally at a section (`play`, `role`, `block`, `task`). */
    fun keywords(base: String, section: String?): String = base + KEYWORDS_PAGE + (section?.let { "#$it" } ?: "")

    /** The keywords page section of a structural level. Handlers and `loop_control` belong to the task section. */
    fun section(level: KeywordLevel): String = when (level) {
        KeywordLevel.PLAY, KeywordLevel.PLAYBOOK_INCLUDE -> "play"
        KeywordLevel.ROLE_ENTRY -> "role"
        KeywordLevel.BLOCK -> "block"
        KeywordLevel.TASK, KeywordLevel.HANDLER, KeywordLevel.LOOP_CONTROL -> "task"
    }

    /** The keywords page section for a snapshot `applies_to` label (`Play`, `Role`, `Block`, `Task` …). */
    fun section(appliesTo: String): String? = when (appliesTo) {
        "Play", "PlaybookInclude" -> "play"
        "Role" -> "role"
        "Block" -> "block"
        "Task", "Handler", "LoopControl" -> "task"
        else -> null
    }

    /** The URL fragment of [anchor], including `#`, or an empty string. */
    fun fragment(anchor: DocAnchor?): String = when (anchor) {
        null -> ""
        is DocAnchor.Parameter -> if (anchor.path.isEmpty()) "" else "#parameter-" + anchor.path.joinToString("/")
        is DocAnchor.ReturnValue -> if (anchor.path.isEmpty()) "" else "#return-" + anchor.path.joinToString("/")
        is DocAnchor.KeywordSection -> "#" + section(anchor.level)
    }

    private fun pageSuffix(kind: PluginKind): String = when (kind) {
        PluginKind.MODULE -> "module"
        PluginKind.FILTER -> "filter"
        PluginKind.TEST -> "test"
        PluginKind.LOOKUP -> "lookup"
    }

    /** `ns.coll.name` → `collections/ns/coll/name_<suffix>.html` (only the first two dots are separators, as in ansible-core). */
    private fun pluginPage(base: String, canonical: String, suffix: String): String {
        val parts = canonical.split('.', limit = 3)
        val path = if (parts.size == 3) "${parts[0]}/${parts[1]}/${parts[2]}" else canonical
        return "${base}collections/${path}_$suffix.html"
    }
}
