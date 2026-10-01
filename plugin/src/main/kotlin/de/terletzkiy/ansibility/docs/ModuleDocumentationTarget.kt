package de.terletzkiy.ansibility.docs

import com.intellij.icons.AllIcons
import com.intellij.lang.documentation.QuickDocHighlightingHelper
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.presentation.TargetPresentation
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.ResolvedModuleDoc
import de.terletzkiy.ansibility.dochtml.MarkupHtml
import de.terletzkiy.ansibility.semantics.schema.ModuleDoc
import de.terletzkiy.ansibility.semantics.schema.RoutedNotice
import org.jetbrains.yaml.YAMLLanguage

/**
 * F5.2 module hover: the documentation of module [fqcn] (as written, routing applied by [AnsibleDocService]) in
 * [root]:
 * ```
 * ansible.builtin.template    module · ansible.builtin · core 2.18.8
 * <redirect, alias, deprecation and removal banners, depending on the root's target version>
 * <short description> <description>
 * Required options  src (path) · dest (path)
 * Attributes        check_mode: full · diff_mode: full · …
 * Returns           checksum · dest · gid · …
 * See also          ansible.builtin.copy · …
 * Example           the first example
 * docs: ansible-core 2.18.8 bundled + pinned collections          (X23 source banner)
 * ```
 * `M()`/`P()` references link to other modules' docs in the IDE, `O()`/`RV()` to anchors of the web page. A
 * module that no source documents gets an info card instead ("No documentation for …"), never an error. The web
 * page (Shift+F1, the popup's browser icon) is the canonical module's (`ansible.builtin.systemd` →
 * `systemd_service_module.html`).
 */
class ModuleDocumentationTarget(project: Project, root: AnsibleRoot, val fqcn: String) : AnsibleDocTarget(project, root) {

    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(fqcn).icon(AllIcons.Nodes.Plugin).containerText(root.displayName).presentation()

    override fun computeDocumentationHint(): String {
        val resolved = docs.moduleDoc(root, fqcn)
        val name = DocsHtml.bold(resolved?.requested ?: fqcn)
        val summary = resolved?.doc?.shortDescription?.let { " — " + MarkupHtml.inline(it) }.orEmpty()
        val marker = when {
            resolved?.tombstone != null -> AnsibilityDocsBundle.message("module.hint.removed")
            resolved?.isDeprecated == true -> AnsibilityDocsBundle.message("module.hint.deprecated")
            else -> null
        }
        val deprecated = marker?.let { " " + DocsHtml.grayed(DocsHtml.esc(it)) }.orEmpty()
        return name + " " + DocsHtml.esc(AnsibilityDocsBundle.message("module.kind")) + summary + deprecated
    }

    override fun computeDocumentation(): DocumentationResult {
        val resolved = docs.moduleDoc(root, fqcn)
        val documentation = DocumentationResult.documentation(render(resolved))
        // A module nobody documents, or one that was removed, has no page to open.
        return if (resolved == null || resolved.tombstone != null) documentation else documentation.externalUrl(resolved.docsUrl)
    }

    /** The popup HTML for [resolved] (null: nothing documents the module). */
    internal fun render(resolved: ResolvedModuleDoc?): String {
        if (resolved == null) return unknown()
        val doc = resolved.doc
        val links = links(resolved.canonical)
        val html = StringBuilder()
        html.append(DocsHtml.definition(header(resolved)))
        val content = StringBuilder()
        banners(resolved, links).forEach(content::append)
        if (doc != null) {
            doc.shortDescription?.takeIf { it.isNotBlank() }?.let { content.append("<p><b>").append(MarkupHtml.inline(it, links)).append("</b></p>") }
            content.append(MarkupHtml.paragraphs(doc.description, links))
        }
        html.append(DocsHtml.content(content.toString()))
        if (doc != null) {
            html.append(DocsHtml.sections(sections(resolved, doc, links)))
            resolved.source?.let { html.append(DocsHtml.bottom(DocsHtml.sourceBanner(it, target))) }
        }
        return html.toString()
    }

    private fun unknown(): String {
        val source = docs.primarySource(root)
        return DocsHtml.definition(DocsHtml.bold(fqcn) + "  " + DocsHtml.grayed(DocsHtml.esc(AnsibilityDocsBundle.message("module.kind")))) +
            DocsHtml.content(DocsHtml.info(DocsHtml.esc(AnsibilityDocsBundle.message("module.unknown", fqcn, source.label))))
    }

    /** `<b>fqcn</b>  module · community.docker 5.2.1 · core 2.18.8`. */
    private fun header(resolved: ResolvedModuleDoc): String {
        val parts = mutableListOf(AnsibilityDocsBundle.message("module.kind"))
        val source = resolved.source
        val collection = DocsHtml.collectionOf(resolved.canonical)
        if (collection != null) {
            val version = source?.collections?.get(collection)?.takeIf { collection != BUILTIN }
            parts += if (version == null) collection else AnsibilityDocsBundle.message("module.collection.version", collection, version)
        }
        source?.core?.let { parts += AnsibilityDocsBundle.message("module.core.version", it.toString()) }
        return DocsHtml.bold(resolved.requested) + "  " + DocsHtml.grayed(DocsHtml.esc(parts.joinToString(" · ")))
    }

    /** Removal, redirect, alias and deprecation notices; the deprecations depend on the root's target line. */
    private fun banners(resolved: ResolvedModuleDoc, links: DocLinks): List<String> {
        val banners = ArrayList<String>()
        resolved.tombstone?.let { banners += DocsHtml.warning(tombstone(it)) }
        if (resolved.isRedirected) {
            val chain = resolved.redirectChain.drop(1).joinToString(" → ") { DocsHtml.link(DocLink.moduleUrl(it), DocsHtml.code(it)) }
            banners += DocsHtml.info(DocsHtml.esc(AnsibilityDocsBundle.message("module.redirect")) + " " + chain)
        } else if (resolved.route.isAlias) {
            val canonical = DocsHtml.code(resolved.canonical)
            banners += DocsHtml.info(DocsHtml.esc(AnsibilityDocsBundle.message("module.alias")) + " " + canonical)
        }
        val own = resolved.deprecation
        for (notice in resolved.routingDeprecations) {
            // The module's own deprecation says the same about its canonical name, in more detail.
            if (own != null && notice.name == resolved.canonical) continue
            banners += DocsHtml.warning(routingDeprecation(notice))
        }
        own?.let { deprecation ->
            banners += DocsHtml.warning(DocsHtml.deprecation(deprecation, DocsHtml.collectionOf(resolved.canonical)) { MarkupHtml.inline(it, links) })
        }
        if (resolved.doc == null && resolved.tombstone == null) {
            val source = docs.primarySource(root)
            banners += DocsHtml.info(DocsHtml.esc(AnsibilityDocsBundle.message("module.unknown", resolved.canonical, source.label)))
        }
        return banners
    }

    private fun routingDeprecation(notice: RoutedNotice): String {
        val owner = DocsHtml.owner(DocsHtml.collectionOf(notice.name))
        val removal = notice.notice.removalVersion?.let { AnsibilityDocsBundle.message("deprecated.removal", owner, it) }
            ?: notice.notice.removalDate?.let { AnsibilityDocsBundle.message("deprecated.removal.date", it) }
        val text = StringBuilder(DocsHtml.bold(AnsibilityDocsBundle.message("deprecated.label")))
        text.append(' ').append(DocsHtml.esc(AnsibilityDocsBundle.message("module.deprecated.name", notice.name)))
        removal?.let { text.append(' ').append(DocsHtml.esc(it)) }
        notice.notice.warningText?.takeIf { it.isNotBlank() }?.let { text.append(' ').append(MarkupHtml.inline(it)) }
        return text.toString()
    }

    private fun tombstone(notice: RoutedNotice): String {
        val owner = DocsHtml.owner(DocsHtml.collectionOf(notice.name))
        val removal = notice.notice.removalVersion?.let { AnsibilityDocsBundle.message("deprecated.removal", owner, it) }
            ?: notice.notice.removalDate?.let { AnsibilityDocsBundle.message("module.removed.date", it) }
        val text = StringBuilder(DocsHtml.bold(AnsibilityDocsBundle.message("module.removed.label")))
        text.append(' ').append(DocsHtml.esc(AnsibilityDocsBundle.message("module.removed", notice.name)))
        removal?.let { text.append(' ').append(DocsHtml.esc(it)) }
        notice.notice.warningText?.takeIf { it.isNotBlank() }?.let { text.append(' ').append(MarkupHtml.inline(it)) }
        return text.toString()
    }

    private fun sections(resolved: ResolvedModuleDoc, doc: ModuleDoc, links: DocLinks): List<Pair<String, String>> {
        val rows = ArrayList<Pair<String, String>>()
        val required = doc.options.values.filter { it.required }
        if (required.isNotEmpty()) {
            rows += message("section.required.options") to required.joinToString(" · ") { option ->
                DocsHtml.link(DocLink.optionUrl(resolved.requested, listOf(option.name)), DocsHtml.code(option.name)) +
                    " (" + DocsHtml.esc(DocsHtml.typeText(option)) + ")"
            }
        }
        doc.freeForm?.let { name ->
            val option = DocsHtml.link(DocLink.optionUrl(resolved.requested, listOf(name)), DocsHtml.code(name))
            rows += message("section.free.form") to DocsHtml.esc(message("module.free.form")) + " " + option
        }
        if (doc.attributes.isNotEmpty()) {
            rows += message("section.attributes") to doc.attributes.entries.joinToString(" · ") { (name, support) ->
                DocsHtml.esc("$name: $support")
            }
        }
        if (doc.returns.isNotEmpty()) {
            val names = doc.returns.keys.take(MAX_RETURNS).joinToString(" · ") { DocsHtml.code(it) }
            rows += message("section.returns") to names + if (doc.returns.size > MAX_RETURNS) " · …" else ""
        }
        if (doc.requirements.isNotEmpty()) {
            rows += message("section.requirements") to doc.requirements.joinToString(" · ") { MarkupHtml.inline(it, links) }
        }
        if (doc.seeAlso.isNotEmpty()) {
            rows += message("section.see.also") to doc.seeAlso.joinToString("<br/>") { MarkupHtml.inline(it, links) }
        }
        firstExample(doc.examples)?.let { example ->
            ProgressManager.checkCanceled()
            rows += message("section.example") to QuickDocHighlightingHelper.getStyledCodeBlock(project, YAMLLanguage.INSTANCE, example)
        }
        return rows
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is ModuleDocumentationTarget && other.fqcn == fqcn && other.root.dir == root.dir && other.project == project

    override fun hashCode(): Int = 31 * fqcn.hashCode() + root.dir.hashCode()

    override fun toString(): String = "ModuleDocumentationTarget($fqcn in ${root.displayName})"

    companion object {
        private const val BUILTIN = "ansible.builtin"
        private const val MAX_RETURNS = 12
        private const val MAX_EXAMPLE_LINES = 15

        private fun message(key: String): String = AnsibilityDocsBundle.message(key)

        /**
         * The first example of an `EXAMPLES` block: its lines up to the next top-level `- ` item or top-level
         * comment that follows it, without trailing blank lines, cut at [MAX_EXAMPLE_LINES] lines (`# …` marks the
         * cut). Null when there are no examples.
         */
        internal fun firstExample(examples: String?): String? {
            if (examples.isNullOrBlank()) return null
            val lines = examples.trim('\n').lines()
            val result = ArrayList<String>()
            var seenItem = false
            for (line in lines) {
                val topLevel = line.startsWith("- ") || line == "-"
                if (seenItem && (topLevel || line.startsWith("#"))) break
                if (topLevel) seenItem = true
                result += line
            }
            while (result.isNotEmpty() && result.last().isBlank()) result.removeAt(result.lastIndex)
            if (result.isEmpty()) return null
            if (result.size > MAX_EXAMPLE_LINES) {
                val cut = result.take(MAX_EXAMPLE_LINES).toMutableList()
                cut += "# …"
                return cut.joinToString("\n")
            }
            return result.joinToString("\n")
        }
    }
}
