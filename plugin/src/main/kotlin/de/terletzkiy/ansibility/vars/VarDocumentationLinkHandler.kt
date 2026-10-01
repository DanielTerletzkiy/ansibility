package de.terletzkiy.ansibility.vars

import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.backend.documentation.DocumentationLinkHandler
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.LinkResolveResult
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.SourceLocation

/**
 * Resolves the links of variable cards ([VarLinks], `platform.backend.documentation.linkHandler`): a sub-option in
 * the Options table opens that option's card, `O(name)` in a description opens that variable's card, and a
 * definition ("Set in", "Declared by", the runtime default) opens the card of the definition, whose "Jump to Source"
 * goes to it. Links resolve only inside the card's own root.
 */
class VarDocumentationLinkHandler : DocumentationLinkHandler {
    override fun resolveLink(target: DocumentationTarget, url: String): LinkResolveResult? =
        resolveTarget(target, url)?.let(LinkResolveResult::resolvedTarget)

    /** The card [url] leads to from [target], or null when it is not a variable-card link. */
    internal fun resolveTarget(target: DocumentationTarget, url: String): DocumentationTarget? {
        if (target !is VarDocumentationTarget) return null
        return when (val link = VarLinks.parse(url) ?: return null) {
            is VarLinks.Link.Option -> target.withPath(link.names)
            is VarLinks.Link.Variable -> sibling(target, link)
            is VarLinks.Link.Definition -> definition(target, link)
        }
    }

    private fun sibling(target: VarDocumentationTarget, link: VarLinks.Link.Variable): DocumentationTarget? {
        val subject = target.subject
        val project = target.project
        val root = subject.root(project) ?: return null
        return VarDocumentationTarget(project, VarSubject.referenceTo(root, link.name, link.path, subject.file, subject.offset))
    }

    private fun definition(target: VarDocumentationTarget, link: VarLinks.Link.Definition): DocumentationTarget? {
        val project = target.project
        val file = VirtualFileManager.getInstance().findFileByUrl(link.fileUrl) ?: return null
        val root = target.subject.root(project) ?: return null
        val context = AnsibleWorkspace.getInstance(project).contextOf(file) ?: return null
        val location = SourceLocation(file, link.offset)
        val keyValue = VarLocations.keyValueAt(project, location)
        keyValue?.let { VarKeySites.of(it, context, file) }?.let { site -> VarSubject.key(site) }?.let {
            return VarDocumentationTarget(project, it)
        }
        return VarDocumentationTarget(project, VarSubject.definition(project, root, target.subject.name, emptyList(), location))
    }
}
