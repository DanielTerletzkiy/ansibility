package de.terletzkiy.ansibility.docs

import com.intellij.icons.AllIcons
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.presentation.TargetPresentation
import de.terletzkiy.ansibility.api.AnsibleDocService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.DocAnchor
import de.terletzkiy.ansibility.api.DocKind
import de.terletzkiy.ansibility.api.ResolvedModuleDoc
import de.terletzkiy.ansibility.dochtml.MarkupHtml
import de.terletzkiy.ansibility.semantics.schema.Choices
import de.terletzkiy.ansibility.semantics.schema.OptionSpec

/**
 * F5.3 option hover: the documentation of option [path] (`["mounts", "type"]`, aliases accepted) of module
 * [fqcn] in [root]:
 * - the definition `<option> : <type> · required` and the module it belongs to;
 * - default, choices (described choices as a value/description table), aliases, version added;
 * - the sub-options as a tree of in-IDE links, the deprecation, `no_log`;
 * - an explanation for free-form pseudo-options (`free_form` of command/shell/raw/meta), which are no keys;
 * - the description, and the X23 source banner.
 *
 * The web page is the module page at the option's anchor (`#parameter-mounts/type`, X18). Created through
 * [create], which gives no target for an option the documentation does not know.
 */
class OptionDocumentationTarget private constructor(
    project: Project,
    root: AnsibleRoot,
    val fqcn: String,
    val path: List<String>,
) : AnsibleDocTarget(project, root) {

    private data class Resolved(val module: ResolvedModuleDoc, val chain: List<OptionSpec>) {
        val option: OptionSpec get() = chain.last()
        val canonicalPath: List<String> get() = chain.map { it.name }
    }

    private fun resolve(): Resolved? = resolve(docs, root, fqcn, path)

    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(path.joinToString(".")).icon(AllIcons.Nodes.Parameter).containerText(fqcn).presentation()

    override fun computeDocumentationHint(): String? {
        val resolved = resolve() ?: return null
        val option = resolved.option
        val summary = DocsHtml.firstSentence(option.description)?.let { " — " + DocsHtml.esc(it) }.orEmpty()
        return DocsHtml.bold(resolved.canonicalPath.joinToString(".")) + " : " + DocsHtml.esc(DocsHtml.typeAndRequired(option)) + summary
    }

    override fun computeDocumentation(): DocumentationResult? {
        val resolved = resolve() ?: return null
        val url = docs.docsUrl(root, DocKind.MODULE, fqcn, DocAnchor.Parameter(resolved.canonicalPath))
        return DocumentationResult.documentation(render(resolved)).externalUrl(url)
    }

    private fun render(resolved: Resolved): String {
        val option = resolved.option
        val module = resolved.module
        val links = links(module.canonical)
        val moduleLink = DocsHtml.link(DocLink.moduleUrl(fqcn), DocsHtml.code(module.requested))
        val definition = DocsHtml.bold(resolved.canonicalPath.joinToString(".")) + " : " + DocsHtml.esc(DocsHtml.typeAndRequired(option))
        val html = StringBuilder(DocsHtml.definition(definition))

        val content = StringBuilder()
        option.deprecated?.let { deprecation ->
            content.append(DocsHtml.warning(DocsHtml.deprecation(deprecation, DocsHtml.collectionOf(module.canonical)) { MarkupHtml.inline(it, links) }))
        }
        if (resolved.chain.size == 1 && module.doc?.freeForm == option.name) {
            content.append(DocsHtml.info(DocsHtml.esc(AnsibilityDocsBundle.message("option.free.form", module.requested))))
        }
        if (option.noLog) content.append(DocsHtml.info(DocsHtml.esc(AnsibilityDocsBundle.message("option.no.log"))))
        content.append(MarkupHtml.paragraphs(option.description, links))
        html.append(DocsHtml.content(content.toString()))

        val rows = ArrayList<Pair<String, String>>()
        rows += AnsibilityDocsBundle.message("section.module") to moduleLink
        option.default?.let { rows += AnsibilityDocsBundle.message("section.default") to DocsHtml.code(DocsHtml.valueText(it)) }
        option.choices?.let { rows += AnsibilityDocsBundle.message("section.choices") to choices(it, option, links) }
        if (option.aliases.isNotEmpty()) {
            rows += AnsibilityDocsBundle.message("section.aliases") to option.aliases.joinToString(" · ") { DocsHtml.code(it) }
        }
        option.versionAdded?.let { version ->
            val owner = DocsHtml.owner(DocsHtml.collectionOf(module.canonical))
            rows += AnsibilityDocsBundle.message("section.version.added") to DocsHtml.esc(AnsibilityDocsBundle.message("option.version.added", owner, version))
        }
        option.options?.takeIf { it.isNotEmpty() }?.let { suboptions ->
            rows += AnsibilityDocsBundle.message("section.suboptions", suboptions.size) to tree(suboptions, resolved.canonicalPath, 1)
        }
        html.append(DocsHtml.sections(rows))
        module.source?.let { html.append(DocsHtml.bottom(DocsHtml.sourceBanner(it, target))) }
        return html.toString()
    }

    private fun choices(choices: Choices, option: OptionSpec, links: DocLinks): String {
        val default = option.default?.let(DocsHtml::valueText)
        fun label(text: String): String =
            if (text == default) DocsHtml.code(text) + " " + DocsHtml.grayed(DocsHtml.esc(AnsibilityDocsBundle.message("option.choice.default"))) else DocsHtml.code(text)
        return when (choices) {
            is Choices.Values -> choices.values.joinToString(" · ") { label(DocsHtml.valueText(it)) }
            is Choices.Described -> choices.described.joinToString("", "<table>", "</table>") { (value, description) ->
                "<tr><td valign='top'>" + label(DocsHtml.valueText(value)) + "</td><td valign='top'>" +
                    MarkupHtml.paragraphs(description, links).ifEmpty { "&nbsp;" } + "</td></tr>"
            }
        }
    }

    /** Sub-options as nested lists of links, [MAX_TREE_DEPTH] levels deep. */
    private fun tree(options: Map<String, OptionSpec>, parent: List<String>, depth: Int): String =
        options.values.joinToString("", "<ul>", "</ul>") { option ->
            ProgressManager.checkCanceled()
            val path = parent + option.name
            val item = DocsHtml.link(DocLink.optionUrl(fqcn, path), DocsHtml.code(option.name)) + " : " +
                DocsHtml.esc(DocsHtml.typeAndRequired(option))
            val nested = option.options?.takeIf { it.isNotEmpty() && depth < MAX_TREE_DEPTH }?.let { tree(it, path, depth + 1) }.orEmpty()
            "<li>$item$nested</li>"
        }

    override fun equals(other: Any?): Boolean =
        this === other || other is OptionDocumentationTarget && other.fqcn == fqcn && other.path == path &&
            other.root.dir == root.dir && other.project == project

    override fun hashCode(): Int = (31 * fqcn.hashCode() + path.hashCode()) * 31 + root.dir.hashCode()

    override fun toString(): String = "OptionDocumentationTarget($fqcn ${path.joinToString(".")} in ${root.displayName})"

    companion object {
        private const val MAX_TREE_DEPTH = 3

        /** The target for option [path] of module [fqcn], or null when no documentation source knows that option. */
        fun create(project: Project, root: AnsibleRoot, fqcn: String, path: List<String>): OptionDocumentationTarget? {
            if (path.isEmpty()) return null
            resolve(AnsibleDocService.getInstance(project), root, fqcn, path) ?: return null
            return OptionDocumentationTarget(project, root, fqcn, path)
        }

        private fun resolve(docs: AnsibleDocService, root: AnsibleRoot, fqcn: String, path: List<String>): Resolved? {
            val module = docs.moduleDoc(root, fqcn) ?: return null
            val doc = module.doc ?: return null
            val chain = ModuleOptions.chain(doc.options, path) ?: return null
            return Resolved(module, chain)
        }
    }
}
