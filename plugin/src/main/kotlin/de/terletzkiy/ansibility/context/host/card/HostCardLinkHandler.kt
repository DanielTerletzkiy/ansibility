package de.terletzkiy.ansibility.context.host.card

import com.intellij.platform.backend.documentation.DocumentationLinkHandler
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.LinkResolveResult
import de.terletzkiy.ansibility.context.host.AnsibleContextServiceImpl
import de.terletzkiy.ansibility.settings.RootKeys
import de.terletzkiy.ansibility.vars.VarDocumentationLinkHandler
import de.terletzkiy.ansibility.vars.VarDocumentationTarget

/**
 * Resolves the links of the host-aware card parts (`platform.backend.documentation.linkHandler`, plan amendment R7/R8,
 * F8.2):
 * - an explain link ([ExplainLinks]) on a variable card or an Explain card opens the [ExplainDocumentationTarget] of
 *   that context, keeping the variable card it came from as its origin; a link whose host belongs to another root than
 *   the origin card's resolves to nothing (DEV.md rule 6: a card never explains another root's hosts);
 * - a definition link (`psi_element://ansibility-var/…`) on an Explain card resolves exactly as it would on its origin
 *   card ([VarDocumentationLinkHandler]): it opens the definition's card, whose "Jump to Source" goes to it.
 *
 * Every other URL is left to the other handlers (vault Reveal links are the vault area's). Resolution only builds
 * targets; their documentation is computed later in the platform's read action.
 */
class HostCardLinkHandler : DocumentationLinkHandler {
    override fun resolveLink(target: DocumentationTarget, url: String): LinkResolveResult? =
        resolveTarget(target, url)?.let(LinkResolveResult::resolvedTarget)

    /** The target [url] leads to from [target], or null when this handler does not handle it. */
    internal fun resolveTarget(target: DocumentationTarget, url: String): DocumentationTarget? {
        ExplainLinks.parse(url)?.let { request ->
            val (project, origin) = when (target) {
                is VarDocumentationTarget -> target.project to target
                is ExplainDocumentationTarget -> target.project to target.origin
                else -> return null
            }
            // The root whose hosts the link may name: the origin card's, else (origin gone) the explanation's own.
            val rootKey = if (origin != null) rootKeyOf(origin) else (target as ExplainDocumentationTarget).request.host.root
            if (rootKey == null || rootKey != request.host.root) return null
            return ExplainDocumentationTarget(project, origin, request)
        }
        if (target is ExplainDocumentationTarget) {
            val origin = target.origin ?: return null
            return VarDocumentationLinkHandler().resolveTarget(origin, url)
        }
        return null
    }

    /** The `HostKey.root` of [origin]'s hosts (a nested playbook root's hosts are its parent's), or null when its root is gone. */
    private fun rootKeyOf(origin: VarDocumentationTarget): String? {
        val project = origin.project
        val root = origin.subject.root(project) ?: return null
        val inventoryRoot = AnsibleContextServiceImpl.getInstance(project)?.inventoryRoot(root) ?: root
        return RootKeys.keyOf(project, inventoryRoot.dir)
    }
}
