package de.terletzkiy.ansibility.completion.tasks

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.LookupElementDocumentationTargetProvider
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.KeywordLevel
import de.terletzkiy.ansibility.docs.KeywordDocumentationTarget
import de.terletzkiy.ansibility.docs.ModuleDocumentationTarget
import de.terletzkiy.ansibility.docs.OptionDocumentationTarget

/**
 * The object behind a task completion item (`LookupElement.getObject()`): what the item names, in which root, so
 * that Ctrl+Q in the completion popup shows the docs area's card for it ([TaskLookupDocumentationProvider]).
 * It holds no PSI.
 */
internal sealed interface TaskLookupObject {
    val root: AnsibleRoot

    /** The card of the docs area (plan F5.2–F5.4) for this item, or null when the docs do not know it. */
    fun documentationTarget(project: Project): DocumentationTarget?

    /** A module name: the module card. */
    data class Module(override val root: AnsibleRoot, val fqcn: String) : TaskLookupObject {
        override fun documentationTarget(project: Project): DocumentationTarget = ModuleDocumentationTarget(project, root, fqcn)
    }

    /** An option key (or one of its aliases) at [path] of [fqcn]: the option card. */
    data class Option(override val root: AnsibleRoot, val fqcn: String, val path: List<String>) : TaskLookupObject {
        override fun documentationTarget(project: Project): DocumentationTarget? = OptionDocumentationTarget.create(project, root, fqcn, path)
    }

    /** A value of the option at [path] of [fqcn]: the option card, which lists the choices with their descriptions. */
    data class OptionValue(override val root: AnsibleRoot, val fqcn: String, val path: List<String>, val value: String) : TaskLookupObject {
        override fun documentationTarget(project: Project): DocumentationTarget? = OptionDocumentationTarget.create(project, root, fqcn, path)
    }

    /** A playbook keyword at [level]: the keyword card. */
    data class Keyword(override val root: AnsibleRoot, val keyword: String, val level: KeywordLevel) : TaskLookupObject {
        override fun documentationTarget(project: Project): DocumentationTarget? = KeywordDocumentationTarget.create(project, root, keyword, level)
    }

    /** A value (`true`/`false`) of the keyword [keyword] at [level]: the keyword card. */
    data class KeywordValue(override val root: AnsibleRoot, val keyword: String, val level: KeywordLevel, val value: String) : TaskLookupObject {
        override fun documentationTarget(project: Project): DocumentationTarget? = KeywordDocumentationTarget.create(project, root, keyword, level)
    }
}

/**
 * Ctrl+Q inside the completion popup (plan F5.7): the module, option and keyword cards of the docs area for task
 * completion items. Other items are left to other providers.
 *
 * `LookupElementDocumentationTargetProvider` and its extension point
 * `com.intellij.platform.backend.documentation.lookupElementTargetProvider` are `@ApiStatus.Experimental` in 262
 * (the interface is also `@OverrideOnly`; only [documentationTarget] is implemented). Needs no indexes.
 */
class TaskLookupDocumentationProvider : LookupElementDocumentationTargetProvider, DumbAware {
    override fun documentationTarget(psiFile: PsiFile, element: LookupElement, offset: Int): DocumentationTarget? {
        val item = element.`object` as? TaskLookupObject ?: return null
        val project = psiFile.project
        if (project.isDisposed || !item.root.dir.isValid) return null
        return item.documentationTarget(project)
    }
}
