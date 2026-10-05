package de.terletzkiy.ansibility.vars

import com.intellij.icons.AllIcons
import com.intellij.model.Pointer
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.CardContext
import de.terletzkiy.ansibility.api.CardSection
import de.terletzkiy.ansibility.api.CardSubject
import de.terletzkiy.ansibility.api.SiteDocumentation
import de.terletzkiy.ansibility.api.SourceLocation

/**
 * The variable documentation card (plan F1.2, F4.3, F4.8; X06, X07, X84) as a platform [DocumentationTarget]: for a
 * Jinja reference, a vars-file key, a spec option, a navigation target (Ctrl-hover) or a link inside another card.
 *
 * The card is computed when the platform asks for it (in a read action, in smart mode), from the root-scoped
 * [de.terletzkiy.ansibility.api.VarService] symbol; other areas add to it through [CardSection]s, which see the
 * card as [cardSubject] from [cardContext] and never run for the Ctrl-hover hint. [createPointer] keeps only the
 * root and file, the variable name and path, and a smart pointer to a definition, so it survives edits.
 */
class VarDocumentationTarget internal constructor(
    internal val project: Project,
    internal val subject: VarSubject,
    /** The card was reached through a link of another card (its [CardContext.offset] is then -1). */
    internal val viaLink: Boolean = false,
) : DocumentationTarget {

    override fun createPointer(): Pointer<out DocumentationTarget> {
        val project = project
        val subject = subject
        val viaLink = viaLink
        return Pointer { if (!project.isDisposed && subject.isValid) VarDocumentationTarget(project, subject, viaLink) else null }
    }

    override fun computePresentation(): TargetPresentation {
        val root = subject.root(project)
        return TargetPresentation.builder(subject.displayName)
            .icon(AllIcons.Nodes.Variable)
            .containerText(root?.displayName)
            .presentation()
    }

    override fun computeDocumentationHint(): String? = card()?.let(VarCardHtml::hint)

    override fun computeDocumentation(): DocumentationResult? {
        val card = card() ?: return null
        val cardSubject = cardSubject(card)
        val context = cardContext()
        return DocumentationResult.documentation(VarCardHtml.render(project, card, cardSubject, context, CardContributions.collect(cardSubject, context)))
    }

    /**
     * What [CardSection]s see: the variable of [card]'s root with its nested path, its definition for a definition
     * card (null for a reference), and whether it is a template local or a task's loop variable (no root variable).
     */
    internal fun cardSubject(card: VarCard): CardSubject.Variable = CardSubject.Variable(
        root = card.root,
        name = subject.name,
        path = subject.path,
        definition = definitionLocation,
        local = subject.origin == VarSubject.Origin.LOCAL || card.note is Note.Local || card.note is Note.Loop,
    )

    /** Where the documented definition is written, for a definition card; null for a reference ([CardSubject.Variable.definition]). */
    internal val definitionLocation: SourceLocation?
        get() = subject.location.takeIf { subject.origin == VarSubject.Origin.DEFINITION }

    /** Where the card is shown from: the subject's host file and offset, or offset -1 when reached through a link. */
    internal fun cardContext(): CardContext = CardContext(project, subject.file, if (viaLink) -1 else subject.offset)

    /** Definitions open at their position; references have no single source to jump to. */
    override val navigatable: Navigatable?
        get() = if (subject.origin == VarSubject.Origin.DEFINITION && subject.file.isValid) {
            OpenFileDescriptor(project, subject.file, subject.offset)
        } else {
            null
        }

    /** The same variable with the nested option [path] (a link from the Options table). */
    internal fun withPath(path: List<String>): VarDocumentationTarget = VarDocumentationTarget(project, subject.withPath(path), viaLink = true)

    /** The card, or null while indexing or when the root is gone. */
    internal fun card(): VarCard? {
        if (project.isDisposed || DumbService.isDumb(project) || !subject.isValid) return null
        return VarCards.build(project, subject)
    }

    override fun toString(): String = "VarDocumentationTarget($subject)"

    companion object {
        /**
         * The card of the reference [site] in host [file]; a loop variable's member (`item.floating.ssl.cert_file`)
         * documents the nested option of an element of the iterated variable ([LoopItems]).
         */
        internal fun forReference(file: PsiFile, site: AnsibleSite.VarRef): VarDocumentationTarget? {
            val subject = VarSubject.reference(file, site) ?: return null
            val project = file.project
            val root = subject.root(project) ?: return null
            return VarDocumentationTarget(project, LoopItems.documentedSubject(project, root, subject) ?: subject)
        }

        /** The card of the variable key [site] in host [file]. */
        internal fun forKey(file: PsiFile, site: AnsibleSite.VarKey): VarDocumentationTarget? {
            val virtualFile = file.originalFile.viewProvider.virtualFile
            val context = AnsibleWorkspace.getInstance(file.project).contextOf(virtualFile) ?: return null
            val keySite = VarKeySites.at(file, site.range.startOffset, context) ?: return null
            return VarSubject.key(keySite)?.let { VarDocumentationTarget(file.project, it) }
        }
    }
}

/**
 * The variables area's `siteDocumentation`: [VarDocumentationTarget] for [AnsibleSite.VarRef] and
 * [AnsibleSite.VarKey]; null for every other site. Needs indexes, so it is not `DumbAware`.
 */
class VarSiteDocumentation : SiteDocumentation {
    override fun documentation(site: AnsibleSite, file: PsiFile): DocumentationTarget? = when (site) {
        is AnsibleSite.VarRef -> VarDocumentationTarget.forReference(file, site)
        is AnsibleSite.VarKey -> VarDocumentationTarget.forKey(file, site)
        else -> null
    }
}
