package de.terletzkiy.ansibility.docs

import de.terletzkiy.ansibility.api.AnsibleDocService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.DocAnchor
import de.terletzkiy.ansibility.api.DocKind
import de.terletzkiy.ansibility.dochtml.MarkupHtml
import de.terletzkiy.ansibility.semantics.markup.PluginRef
import java.net.URLDecoder
import java.net.URLEncoder

/** An in-IDE documentation link of the docs area, resolved by [AnsibleDocLinkHandler]. */
sealed interface DocLink {
    /** The documentation of another module (`M()`, see-also entries). */
    data class Module(val fqcn: String) : DocLink

    /** The documentation of a module option (the required options of a module card, sub-option trees). */
    data class Option(val fqcn: String, val path: List<String>) : DocLink

    companion object {
        private const val MODULE_PREFIX = "psi_element://ansibility-module/"
        private const val OPTION_PREFIX = "psi_element://ansibility-option/"

        /** `psi_element://ansibility-module/ansible.builtin.copy`. */
        fun moduleUrl(fqcn: String): String = MODULE_PREFIX + encode(fqcn)

        /** `psi_element://ansibility-option/community.docker.docker_container/mounts/type`. */
        fun optionUrl(fqcn: String, path: List<String>): String =
            OPTION_PREFIX + (listOf(fqcn) + path).joinToString("/") { encode(it) }

        /** The link [url] names, or null for any other URL. */
        fun parse(url: String): DocLink? = when {
            url.startsWith(MODULE_PREFIX) -> decode(url.removePrefix(MODULE_PREFIX)).takeIf { it.isNotBlank() }?.let(::Module)
            url.startsWith(OPTION_PREFIX) -> {
                val parts = url.removePrefix(OPTION_PREFIX).split('/').map(::decode)
                if (parts.size < 2 || parts.any { it.isEmpty() }) null else Option(parts.first(), parts.drop(1))
            }
            else -> null
        }

        private fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8)

        private fun decode(text: String): String = URLDecoder.decode(text, Charsets.UTF_8)
    }
}

/**
 * Link targets for Ansible markup in module, option and keyword docs of one [root] (plan F5.1): `M()` and module
 * `P()` references become in-IDE links ([DocLink.Module]); filter, test and lookup `P()` references open their web
 * pages; `O()`/`RV()` open the `#parameter-a/b` / `#return-a/b` anchor of the current module's page ([module]) or
 * of the plugin they name, on that plugin type's page (`O(ansible.builtin.file#lookup:_terms)` →
 * `file_lookup.html#parameter-_terms`). References to other plugin types (roles, callbacks …) stay code.
 */
internal class DocLinks(
    private val root: AnsibleRoot,
    private val docs: AnsibleDocService,
    private val module: String?,
) : MarkupHtml.Links {
    override fun plugin(fqcn: String, type: String): String? = when (type) {
        MODULE -> DocLink.moduleUrl(fqcn)
        FILTER -> docs.docsUrl(root, DocKind.FILTER, fqcn)
        TEST -> docs.docsUrl(root, DocKind.TEST, fqcn)
        LOOKUP -> docs.docsUrl(root, DocKind.LOOKUP, fqcn)
        else -> null
    }

    override fun option(path: List<String>, plugin: PluginRef?): String? = anchored(path, plugin, DocAnchor::Parameter)

    override fun returnValue(path: List<String>, plugin: PluginRef?): String? = anchored(path, plugin, DocAnchor::ReturnValue)

    /** The page of [plugin] (the current module without one) at the anchor [anchorOf] builds for [path]. */
    private fun anchored(path: List<String>, plugin: PluginRef?, anchorOf: (List<String>) -> DocAnchor): String? {
        if (path.isEmpty()) return null
        if (plugin == null) return module?.let { docs.docsUrl(root, DocKind.MODULE, it, anchorOf(path)) }
        val kind = when (plugin.type) {
            MODULE -> DocKind.MODULE
            FILTER -> DocKind.FILTER
            TEST -> DocKind.TEST
            LOOKUP -> DocKind.LOOKUP
            else -> return null
        }
        return docs.docsUrl(root, kind, plugin.fqcn, anchorOf(path))
    }

    private companion object {
        const val MODULE = "module"
        const val FILTER = "filter"
        const val TEST = "test"
        const val LOOKUP = "lookup"
    }
}
