package de.terletzkiy.ansibility.vars.registered

import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.backend.documentation.DocumentationLinkHandler
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.LinkResolveResult
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.docs.DocLink
import de.terletzkiy.ansibility.docs.ModuleDocumentationTarget
import de.terletzkiy.ansibility.vars.VarDocumentationTarget
import de.terletzkiy.ansibility.vars.VarLinks
import de.terletzkiy.ansibility.vars.VarSubject

/**
 * Resolves the links of registered member cards ([RegisteredMemberDocumentationTarget],
 * `platform.backend.documentation.linkHandler`): a "Set by" task opens the variable card of its `register:`
 * definition (whose "Jump to Source" goes to the line), an `M()` reference in a return value's description opens that
 * module's card. Links resolve only inside the card's own root; web pages are left to the browser.
 */
class RegisteredDocumentationLinkHandler : DocumentationLinkHandler {
    override fun resolveLink(target: DocumentationTarget, url: String): LinkResolveResult? =
        resolveTarget(target, url)?.let(LinkResolveResult::resolvedTarget)

    /** The card [url] leads to from [target], or null when it is no link of a registered member card. */
    internal fun resolveTarget(target: DocumentationTarget, url: String): DocumentationTarget? {
        if (target !is RegisteredMemberDocumentationTarget) return null
        val project = target.project
        val root = target.result.root
        (VarLinks.parse(url) as? VarLinks.Link.Definition)?.let { link ->
            val file = VirtualFileManager.getInstance().findFileByUrl(link.fileUrl) ?: return null
            val location = SourceLocation(file, link.offset)
            if (target.result.tasks.none { it.register == location }) return null
            return VarDocumentationTarget(project, VarSubject.definition(project, root, target.result.name, emptyList(), location))
        }
        val module = DocLink.parse(url) as? DocLink.Module ?: return null
        return ModuleDocumentationTarget(project, root, module.fqcn)
    }
}
