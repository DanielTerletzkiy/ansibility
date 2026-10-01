package de.terletzkiy.ansibility.dochtml

import com.intellij.openapi.util.text.StringUtil
import de.terletzkiy.ansibility.semantics.markup.AnsibleDocMarkup
import de.terletzkiy.ansibility.semantics.markup.MarkupPart
import de.terletzkiy.ansibility.semantics.markup.PluginRef

/**
 * Renders Ansible doc markup (`C()`, `O()`, `M()`, `U()`, `L()` …) as the HTML used inside
 * quick documentation popups. Shared by variable docs (argument_specs descriptions) and module docs.
 *
 * Links: `M()`/`P()` become `psi_element://` links resolved by a documentation link handler through [links];
 * `O()`/`RV()` become anchors of the current page or of the plugin they name (with its type, [Links.option]) when
 * [links] knows the page, `U()`/`L()` become external links,
 * `R()` stays plain text (there is no URL for Sphinx refs outside the docsite).
 */
object MarkupHtml {

    /** Resolves link targets; the default renders module/option references as code without links. */
    interface Links {
        /** URL for a module or plugin reference, e.g. `psi_element://ansibility-module/ansible.builtin.copy`. */
        fun plugin(fqcn: String, type: String): String? = null

        /**
         * URL for an option at [path] of the current page, or of [plugin] when the reference names one with its type
         * (`O(ansible.builtin.file#lookup:_terms)` → `ansible.builtin.file`, `lookup`); null renders plain code.
         * By default the name-only overload decides.
         */
        fun option(path: List<String>, plugin: PluginRef?): String? = option(path, plugin?.fqcn)

        /** Name-only variant of [option] (`plugin` is the referenced plugin's FQCN) for resolvers that ignore its type. */
        fun option(path: List<String>, plugin: String?): String? = null

        /** URL for a return value at [path] of the current page, or of [plugin] (`RV(ns.coll.name#module:key)`), like [option]. */
        fun returnValue(path: List<String>, plugin: PluginRef?): String? = returnValue(path, plugin?.fqcn)

        /** Name-only variant of [returnValue]. */
        fun returnValue(path: List<String>, plugin: String?): String? = null
    }

    private object NoLinks : Links

    /** Renders paragraphs (a description list) as `<p>` blocks. */
    fun paragraphs(paragraphs: List<String>, links: Links = NoLinks): String =
        paragraphs.filter { it.isNotBlank() }.joinToString("") { "<p>${inline(it, links)}</p>" }

    /** Renders one markup string without a surrounding block element. */
    fun inline(text: String, links: Links = NoLinks): String = render(AnsibleDocMarkup.parse(text), links)

    /** Renders RST-flavoured text (keyword descriptions). */
    fun rst(text: String, links: Links = NoLinks): String = render(AnsibleDocMarkup.parseRst(text), links)

    fun render(parts: List<MarkupPart>, links: Links = NoLinks): String = buildString {
        for (part in parts) append(renderPart(part, links))
    }

    private fun esc(text: String): String = StringUtil.escapeXmlEntities(text)

    private fun code(text: String) = "<code>${esc(text)}</code>"

    private fun anchor(url: String, inner: String) = "<a href=\"${esc(url)}\">$inner</a>"

    private fun renderPart(part: MarkupPart, links: Links): String = when (part) {
        is MarkupPart.Text -> linkifyUrls(esc(part.text))
        is MarkupPart.Code -> code(part.text)
        is MarkupPart.Italic -> "<i>${esc(part.text)}</i>"
        is MarkupPart.Bold -> "<b>${esc(part.text)}</b>"
        is MarkupPart.Url -> anchor(part.url, esc(part.url))
        is MarkupPart.Link -> anchor(part.url, esc(part.text))
        is MarkupPart.Module -> links.plugin(part.fqcn, "module")?.let { anchor(it, code(part.fqcn)) } ?: code(part.fqcn)
        is MarkupPart.Plugin -> links.plugin(part.fqcn, part.type)?.let { anchor(it, code(part.fqcn)) } ?: code(part.fqcn)
        is MarkupPart.Option -> {
            val label = code(if (part.value != null) "${part.name}=${part.value}" else part.name)
            val url = if (part.ignore) null else links.option(part.link, part.plugin)
            url?.let { anchor(it, label) } ?: label
        }
        is MarkupPart.Value -> code(part.text)
        is MarkupPart.EnvVar -> code(part.name)
        is MarkupPart.ReturnValue -> {
            val label = code(if (part.value != null) "${part.name}=${part.value}" else part.name)
            val url = if (part.ignore) null else links.returnValue(part.link, part.plugin)
            url?.let { anchor(it, label) } ?: label
        }
        is MarkupPart.Ref -> esc(part.text)
        MarkupPart.HorizontalLine -> "<hr/>"
    }

    private val BARE_URL = Regex("""https?://[^\s<>"')\]]+""")

    /** Turns bare URLs in already-escaped text into links (argument_specs descriptions often contain plain URLs). */
    private fun linkifyUrls(escaped: String): String =
        BARE_URL.replace(escaped) { m ->
            val url = m.value.trimEnd('.', ',', ';', ':')
            val tail = m.value.substring(url.length)
            "<a href=\"$url\">$url</a>$tail"
        }
}
