package de.terletzkiy.ansibility.docs

import com.intellij.icons.AllIcons
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.util.text.StringUtil
import de.terletzkiy.ansibility.api.DocSource
import de.terletzkiy.ansibility.context.TargetVersion
import de.terletzkiy.ansibility.semantics.json.Json
import de.terletzkiy.ansibility.semantics.json.YValueJson
import de.terletzkiy.ansibility.semantics.markup.AnsibleDocMarkup
import de.terletzkiy.ansibility.semantics.schema.Deprecation
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.schema.OptionType
import de.terletzkiy.ansibility.semantics.yaml.YValue

/**
 * HTML building blocks of the docs area's popups, in the platform's quick documentation layout
 * ([DocumentationMarkup]): a definition block, content, a sections table and a bottom line.
 * Every argument named `html` is already HTML; every other text is escaped here.
 */
internal object DocsHtml {
    private const val BUILTIN = "ansible.builtin"

    fun esc(text: String): String = StringUtil.escapeXmlEntities(text)

    fun code(text: String): String = "<code>${esc(text)}</code>"

    fun link(url: String, html: String): String = "<a href=\"${esc(url)}\">$html</a>"

    fun bold(text: String): String = "<b>${esc(text)}</b>"

    fun definition(html: String): String = DocumentationMarkup.DEFINITION_START + html + DocumentationMarkup.DEFINITION_END

    fun content(html: String): String =
        if (html.isEmpty()) "" else DocumentationMarkup.CONTENT_START + html + DocumentationMarkup.CONTENT_END

    fun grayed(html: String): String = DocumentationMarkup.GRAYED_START + html + DocumentationMarkup.GRAYED_END

    /** A sections table; [rows] pairs a plain-text header with HTML content. Empty without rows. */
    fun sections(rows: List<Pair<String, String>>): String {
        if (rows.isEmpty()) return ""
        return rows.joinToString("", DocumentationMarkup.SECTIONS_START, DocumentationMarkup.SECTIONS_END) { (header, html) ->
            DocumentationMarkup.SECTION_HEADER_START + esc(header) + DocumentationMarkup.SECTION_SEPARATOR + html +
                DocumentationMarkup.SECTION_END + "</tr>"
        }
    }

    /** The last line of a popup (the X23 source banner). */
    fun bottom(html: String): String = DocumentationMarkup.BOTTOM_ELEMENT.child(HtmlChunk.raw(html)).toString()

    /** A paragraph with the warning icon, for deprecations and removals. */
    fun warning(html: String): String =
        "<p>" + HtmlChunk.icon("AllIcons.General.Warning", AllIcons.General.Warning) + "&nbsp;" + html + "</p>"

    /** A paragraph with the information icon, for redirects and missing documentation. */
    fun info(html: String): String = "<p>" + DocumentationMarkup.INFORMATION_ICON + "&nbsp;" + html + "</p>"

    /** `str`, `path`, `list / elements=dict` (the docsite's spelling). */
    fun typeText(spec: OptionSpec): String {
        val elements = spec.elements
        return if (spec.type == OptionType.List && elements != null) {
            AnsibilityDocsBundle.message("type.list.elements", spec.type.name, elements.name)
        } else {
            spec.type.name
        }
    }

    /** `path · required` or just `str`. */
    fun typeAndRequired(spec: OptionSpec): String =
        if (spec.required) AnsibilityDocsBundle.message("option.type.required", typeText(spec)) else typeText(spec)

    /** A documented value as JSON text (`"volume"`, `true`, `[]`), the way the docsite prints defaults and choices. */
    fun valueText(value: YValue): String = Json.write(YValueJson.toJson(value))

    /**
     * The X23 banner: `docs: <source label>`, followed by the root's target when the documentation does not come
     * from the target's ansible-core line.
     */
    fun sourceBanner(source: DocSource, target: TargetVersion): String {
        val label = AnsibilityDocsBundle.message("source.banner", source.label)
        if (source.matchesTarget) return esc(label)
        val version = target.version
        val mismatch = if (version == null) {
            AnsibilityDocsBundle.message("source.banner.target.unknown")
        } else {
            AnsibilityDocsBundle.message("source.banner.target", version.toString())
        }
        return esc(label) + " · " + esc(mismatch)
    }

    /** `ansible-core` for builtin content, else the collection name. */
    fun owner(collection: String?): String =
        if (collection == null || collection == BUILTIN) AnsibilityDocsBundle.message("owner.core") else collection

    /** The collection of an FQCN (`community.docker` for `community.docker.docker_container`), or null for a short name. */
    fun collectionOf(fqcn: String): String? {
        val parts = fqcn.split('.')
        return if (parts.size >= 3) "${parts[0]}.${parts[1]}" else null
    }

    /**
     * A deprecation from the docs (a module's or an option's): why, the removal version and the alternative, all
     * rendered from Ansible markup with [render].
     */
    fun deprecation(deprecation: Deprecation, fallbackCollection: String?, render: (String) -> String): String {
        val parts = ArrayList<String>()
        val removal = deprecation.removedIn?.let { version ->
            val owner = owner(deprecation.removedFromCollection ?: fallbackCollection)
            AnsibilityDocsBundle.message("deprecated.removal", owner, version)
        }
        parts += bold(AnsibilityDocsBundle.message("deprecated.label")) + (removal?.let { " " + esc(it) } ?: "")
        deprecation.why?.takeIf { it.isNotBlank() }?.let { parts += render(it) }
        deprecation.alternative?.takeIf { it.isNotBlank() }?.let { alternative ->
            val text = alternative.trim()
            val html = if (FQCN.matches(text)) link(DocLink.moduleUrl(text), code(text)) else render(alternative)
            parts += esc(AnsibilityDocsBundle.message("deprecated.alternative")) + " " + html
        }
        return parts.joinToString(" ")
    }

    /** The first sentence of the first paragraph as plain text, for one-line hints. */
    fun firstSentence(paragraphs: List<String>, rst: Boolean = false): String? {
        val first = paragraphs.firstOrNull { it.isNotBlank() } ?: return null
        val plain = AnsibleDocMarkup.plainText(if (rst) AnsibleDocMarkup.parseRst(first) else AnsibleDocMarkup.parse(first)).trim()
        val end = SENTENCE_END.find(plain)?.range?.first
        return if (end == null) plain else plain.substring(0, end + 1)
    }

    private val SENTENCE_END = Regex("""[.!?](\s|$)""")

    /** A plain FQCN such as `ansible.builtin.deb822_repository` (deprecation alternatives are often just that). */
    private val FQCN = Regex("""[A-Za-z0-9_]+\.[A-Za-z0-9_]+\.[A-Za-z0-9_]+""")
}
