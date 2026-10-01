package de.terletzkiy.ansibility.inspections.references

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.navigation.AnsibilityNavigationBundle
import de.terletzkiy.ansibility.navigation.RefKind
import de.terletzkiy.ansibility.navigation.RefOccurrence
import de.terletzkiy.ansibility.navigation.RefResolver
import de.terletzkiy.ansibility.navigation.RefSites
import de.terletzkiy.ansibility.navigation.ResolutionStatus
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.settings.SeverityPolicy
import de.terletzkiy.ansibility.settings.toProblemHighlightType
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * 🟣 CLAUDE ANS-R001 (plan A.6, F1.8, X50; D7): a static reference whose target does not exist, which ansible-core
 * fails on: `include_tasks`/`import_tasks`, `tasks_from`/`handlers_from`/`vars_from`/`defaults_from`, `template`/`copy`
 * `src`, `notify`, roles (plays, `include_role`/`import_role`, `meta/main.yml` dependencies, molecule paths),
 * `vars_files`, `import_playbook` and `{% include %}` in role templates. Resolution is [RefResolver]'s, within the root.
 *
 * Exempt (never reported): a `copy` with a truthy or templated `remote_src`; templated values; absolute paths that
 * `ContainerPathMapper` does not map (`/etc/…`, `/tmp/…`); a handler found at play scope or in any other role of the
 * root, or when a handler name in scope is templated or a play in scope has a role the project cannot find; a role
 * name when the role search path is open (no project-only `roles_path`, see `RefCaches.isClosedRoleSearchPath`) or it
 * is a collection role; a `*_from` whose role subdirectory does not exist (ansible-core ignores it); `vars_files`
 * alternatives when one of them exists; `{% include … ignore missing %}`. Template-name values in vars files and
 * `listen` topics are navigation only.
 *
 * The severity comes from [SeverityPolicy] (ERROR by default, D7; at most WARNING with "Red for Claude's certain-failure
 * checks" off). The quick fix changes the value to the nearest existing name ([NearestName]).
 */
class AnsibleUnresolvedReferenceInspection : LocalInspectionTool() {

    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val project = file.project
        if (InjectedLanguageManager.getInstance(project).isInjectedFragment(file)) return null
        val viewProvider = file.viewProvider
        if (viewProvider.getPsi(viewProvider.baseLanguage) != file) return null
        val context = AnsibleWorkspace.getInstance(project).contextOf(viewProvider.virtualFile) ?: return null
        if (context.kind !in CHECKED_KINDS) return null
        val level = SeverityPolicy.getInstance(project).level(DiagnosticCode.R001_UNRESOLVED_REFERENCE, context.root)
        val highlight = level.toProblemHighlightType() ?: return null
        val problems = findUnresolved(file, context)
        if (problems.isEmpty()) return null
        return problems.mapNotNull { problem ->
            ProgressManager.checkCanceled()
            val (element, range) = anchor(file, problem.occurrence.range) ?: return@mapNotNull null
            val fixes = problem.replacement
                ?.takeIf { ElementManipulators.getManipulator(element) != null }
                ?.let { arrayOf<LocalQuickFix>(ChangeReferenceFix(it, range)) }
                ?: LocalQuickFix.EMPTY_ARRAY
            manager.createProblemDescriptor(element, range, problem.message, highlight, isOnTheFly, *fixes)
        }.toTypedArray()
    }

    /** One certain failure: the reference, the message and the nearest existing name, if any. */
    internal data class Unresolved(
        val occurrence: RefOccurrence,
        val message: String,
        val replacement: String?,
    ) {
        /** The reference as written. */
        val text: String get() = occurrence.text

        /** Its range in the file. */
        val range: TextRange get() = occurrence.range
    }

    companion object {
        /** The file kinds whose references are checked. */
        val CHECKED_KINDS: Set<FileKind> = RefSites.TASK_KINDS + setOf(FileKind.ROLE_META, FileKind.ROLE_TEMPLATE)

        /** References that are never reported: definitions (`listen`) and values that fail only for some inventories. */
        private val NEVER_REPORTED = setOf(RefKind.LISTEN, RefKind.TEMPLATE_NAME)

        /** The certain failures of [file] (classified as [context]) in file order. Call inside a read action in smart mode. */
        internal fun findUnresolved(file: PsiFile, context: FileContext): List<Unresolved> {
            val occurrences = RefSites.occurrences(file).filter { it.kind !in NEVER_REPORTED && !it.isDynamic && !it.isTemplated }
            if (occurrences.isEmpty()) return emptyList()
            val resolver = RefResolver(file, context)
            val resolutions = occurrences.associateWith { resolver.resolve(it) }
            // A nested vars_files list loads the first file that exists: only a group with no existing file fails.
            val satisfiedGroups = resolutions.filter { (o, r) -> o.alternativeGroup != null && r.status == ResolutionStatus.RESOLVED }
                .keys.mapTo(HashSet()) { it.alternativeGroup }
            return resolutions.mapNotNull { (occurrence, resolution) ->
                ProgressManager.checkCanceled()
                if (resolution.status != ResolutionStatus.UNRESOLVED) return@mapNotNull null
                if (occurrence.alternativeGroup != null && occurrence.alternativeGroup in satisfiedGroups) return@mapNotNull null
                Unresolved(occurrence, message(occurrence), NearestName.of(occurrence.text, resolution.suggestions))
            }
        }

        private fun message(occurrence: RefOccurrence): String {
            val text = occurrence.text
            return when (occurrence.kind) {
                RefKind.TASK_INCLUDE -> AnsibilityNavigationBundle.message("inspection.r001.task.file", text)
                RefKind.TASKS_FROM -> roleFile(text, RefResolver.SUBDIR_TASKS, occurrence)
                RefKind.HANDLERS_FROM -> roleFile(text, RefResolver.SUBDIR_HANDLERS, occurrence)
                RefKind.VARS_FROM -> roleFile(text, RefResolver.SUBDIR_VARS, occurrence)
                RefKind.DEFAULTS_FROM -> roleFile(text, RefResolver.SUBDIR_DEFAULTS, occurrence)
                RefKind.ROLE -> AnsibilityNavigationBundle.message("inspection.r001.role", text)
                RefKind.TEMPLATE_SRC, RefKind.TEMPLATE_INCLUDE, RefKind.TEMPLATE_NAME -> AnsibilityNavigationBundle.message("inspection.r001.template", text)
                RefKind.COPY_SRC -> AnsibilityNavigationBundle.message("inspection.r001.copy.source", text)
                RefKind.NOTIFY, RefKind.LISTEN -> AnsibilityNavigationBundle.message("inspection.r001.handler", text)
                RefKind.VARS_FILE -> AnsibilityNavigationBundle.message("inspection.r001.vars.file", text)
                RefKind.IMPORT_PLAYBOOK -> AnsibilityNavigationBundle.message("inspection.r001.playbook", text)
            }
        }

        private fun roleFile(text: String, subdir: String, occurrence: RefOccurrence): String =
            AnsibilityNavigationBundle.message("inspection.r001.role.file", text, subdir, RefOccurrence.roleNameOf(occurrence.role.orEmpty()))

        /** The element to report [range] on and the range inside it: the YAML scalar, else the leaves' common parent. */
        private fun anchor(file: PsiFile, range: TextRange): Pair<PsiElement, TextRange>? {
            val start = file.findElementAt(range.startOffset) ?: return null
            val element = PsiTreeUtil.getParentOfType(start, YAMLScalar::class.java, false)?.takeIf { it.textRange.contains(range) }
                ?: run {
                    val end = file.findElementAt((range.endOffset - 1).coerceAtLeast(range.startOffset)) ?: start
                    PsiTreeUtil.findCommonParent(start, end)
                }
                ?: return null
            val elementRange = element.textRange
            if (!elementRange.contains(range)) return null
            return element to range.shiftLeft(elementRange.startOffset)
        }
    }
}
