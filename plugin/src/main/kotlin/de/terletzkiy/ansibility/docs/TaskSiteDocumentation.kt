package de.terletzkiy.ansibility.docs

import com.intellij.openapi.project.DumbAware
import com.intellij.platform.backend.documentation.DocumentationLinkHandler
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.LinkResolveResult
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.SiteDocumentation

/**
 * The docs area's `siteDocumentation` (plan F5.2–F5.4): [ModuleDocumentationTarget] for module keys (an info card
 * when nothing documents the module), [OptionDocumentationTarget] for documented option keys and
 * [KeywordDocumentationTarget] for keywords of the target line. Jinja filter and test documentation arrives in
 * v1.x; other sites are left to the other areas. Needs no indexes, so it is [DumbAware].
 */
class TaskSiteDocumentation : SiteDocumentation, DumbAware {
    override fun documentation(site: AnsibleSite, file: PsiFile): DocumentationTarget? {
        val project = file.project
        return when (site) {
            is AnsibleSite.ModuleKey -> AnsibleDocTarget.rootOf(file)?.let { ModuleDocumentationTarget(project, it, site.fqcn) }
            is AnsibleSite.ModuleOptionKey -> AnsibleDocTarget.rootOf(file)?.let { OptionDocumentationTarget.create(project, it, site.fqcn, site.path) }
            is AnsibleSite.KeywordKey -> AnsibleDocTarget.rootOf(file)?.let { KeywordDocumentationTarget.create(project, it, site.keyword, site.level) }
            else -> null
        }
    }
}

/**
 * Resolves the in-IDE links of the docs area's popups ([DocLink], `platform.backend.documentation.linkHandler`):
 * `M()` references, see-also entries and redirect targets open the other module's documentation, required
 * options and sub-option trees open the option's documentation. Links resolve in the root of the page they are
 * on; links of other pages are left to the other handlers.
 */
class AnsibleDocLinkHandler : DocumentationLinkHandler {
    override fun resolveLink(target: DocumentationTarget, url: String): LinkResolveResult? {
        val page = target as? AnsibleDocTarget ?: return null
        val link = DocLink.parse(url) ?: return null
        val resolved: DocumentationTarget = when (link) {
            is DocLink.Module -> ModuleDocumentationTarget(page.project, page.root, link.fqcn)
            is DocLink.Option -> OptionDocumentationTarget.create(page.project, page.root, link.fqcn, link.path) ?: return null
        }
        return LinkResolveResult.resolvedTarget(resolved)
    }
}
