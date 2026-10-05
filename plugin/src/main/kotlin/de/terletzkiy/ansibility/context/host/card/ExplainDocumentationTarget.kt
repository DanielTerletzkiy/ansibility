package de.terletzkiy.ansibility.context.host.card

import com.intellij.icons.AllIcons
import com.intellij.model.Pointer
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.settings.RootKeys
import de.terletzkiy.ansibility.vars.VarDocumentationTarget

/**
 * The **Explain precedence** card (plan amendment R7/R8, F8.2, ex-X14): the full ordered chain of every definition of
 * one variable that one (env, host, play) context loads, lowest precedence first, with the winner marked, the
 * runtime-default chain of a bare `{{ other }}` winner, the runtime markers and unknown sources, and links to the same
 * explanation on the other hosts of the card it came from ([ExplainCardHtml]).
 *
 * Reached from the Effective section's links ([ExplainLinks], resolved by [HostCardLinkHandler]). [origin] is the
 * variable card the link was on: definition links resolve as they do there, and its file scope lists the other hosts.
 * The card is computed when the platform asks for it, in a read action in smart mode; it never decrypts.
 */
class ExplainDocumentationTarget internal constructor(
    internal val project: Project,
    /** The variable card the explanation was opened from, or null when it is gone. */
    internal val origin: VarDocumentationTarget?,
    internal val request: ExplainLinks.Request,
) : DocumentationTarget {

    override fun createPointer(): Pointer<out DocumentationTarget> {
        val project = project
        val request = request
        val originPointer = origin?.createPointer()
        return Pointer {
            if (project.isDisposed) return@Pointer null
            ExplainDocumentationTarget(project, originPointer?.dereference() as? VarDocumentationTarget, request)
        }
    }

    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(request.name)
            .icon(AllIcons.Nodes.Variable)
            .containerText(HostCardTexts.hostLabel(request.host))
            .presentation()

    override fun computeDocumentation(): DocumentationResult? {
        if (project.isDisposed || DumbService.isDumb(project)) return null
        val root = root() ?: return null
        return DocumentationResult.documentation(ExplainCardHtml(project, root, request, origin).render())
    }

    /** The root whose labels the card uses: the origin card's, else the root the host key names. */
    private fun root(): AnsibleRoot? {
        origin?.subject?.root(project)?.let { return it }
        return AnsibleWorkspace.getInstance(project).roots()
            .firstOrNull { it.kind != RootKind.NESTED_PLAYBOOK && RootKeys.keyOf(project, it.dir) == request.host.root }
    }

    override fun toString(): String = "ExplainDocumentationTarget(${request.name} on ${request.host})"
}
