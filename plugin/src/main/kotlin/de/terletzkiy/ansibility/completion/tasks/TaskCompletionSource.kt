package de.terletzkiy.ansibility.completion.tasks

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.patterns.StandardPatterns
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.CompletionSource
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.docs.TaskSiteClassifier
import de.terletzkiy.ansibility.model.task.TaskFileModel
import de.terletzkiy.ansibility.model.task.TaskFileModels
import de.terletzkiy.ansibility.model.task.TaskSyntaxService
import org.jetbrains.yaml.YAMLLanguage
import org.jetbrains.yaml.psi.YAMLFile

/**
 * Completion inside tasks (plan F5.7, 🟣 X19; M3 WU B5): the `completionSource` of task-like files
 * ([TaskSiteClassifier.TASK_FILE_KINDS]: role tasks and handlers, playbooks, molecule playbooks and task files).
 *
 * - At a key of a task or handler without a module: every module name of the root's docs, the task (or handler)
 *   keywords and `block`; with a module: its keywords only.
 * - At a key of a module's mapping or `args:`: the module's options; below dict and `list[dict]` options their
 *   sub-options.
 * - At a key of a play, block, role entry, `loop_control` or include `apply:`: the keywords of that level (plays
 *   also offer `import_playbook` while the item could still be an import).
 * - At a value: an option's choices or `true`/`false`, and `true`/`false` for bool keywords.
 *
 * The position is analysed on the completion copy ([TaskSlotLocator]), because a half-typed key is no key yet and
 * the classified [AnsibleSite] (computed on the original file) is usually null there. Sites of other areas —
 * variables, Jinja filters and tests, roles, task files, handlers, templates — end the source at once, as do files
 * of other kinds and positions inside `vars:` or other free mappings. Items are described in [TaskLookupItems].
 *
 * Needs no indexes (only the "used in the root" module ranking does, and it is skipped while indexing), so it is
 * [DumbAware]. Never calls `runRemainingContributors` or `stopHere`; the dispatcher does.
 */
class TaskCompletionSource : CompletionSource, DumbAware {
    override fun complete(site: AnsibleSite?, parameters: CompletionParameters, result: CompletionResultSet) {
        if (site != null && site !is AnsibleSite.ModuleKey && site !is AnsibleSite.ModuleOptionKey && site !is AnsibleSite.KeywordKey) return
        val position = parameters.position
        if (position.containingFile !is YAMLFile) return
        val original = parameters.originalFile
        val project = original.project
        val context = AnsibleWorkspace.getInstance(project).contextOf(original.viewProvider.virtualFile) ?: return
        if (context.kind !in TaskSiteClassifier.TASK_FILE_KINDS) return
        val kind = TaskFileModels.kindFor(context.kind) ?: return
        val target = TargetVersionDetector.getInstance(project).targetVersion(context.root).version
        val syntax = TaskSyntaxService.getInstance().forVersion(target)
        val slot = TaskSlotLocator(syntax, kind).locate(position, parameters.offset) ?: return
        ProgressManager.checkCanceled()

        val sink = result.withPrefixMatcher(ModuleNameMatcher(slot.prefix))
        val items = TaskLookupItems(project, context.root, syntax, modelOf(original), sink.prefixMatcher)
        val aliases = items.aliases(slot)
        if (aliases.isNotEmpty()) {
            // Aliases are offered only once typed: restart when the prefix becomes the start of one.
            sink.restartCompletionOnPrefixChange(StandardPatterns.string().oneOf(aliases.flatMapTo(HashSet(), ::prefixesOf)))
        }
        for (item in items.items(slot)) {
            ProgressManager.checkCanceled()
            sink.addElement(item)
        }
    }

    /** The task model of the edited file, for ranking the options it already uses. */
    private fun modelOf(file: PsiFile): TaskFileModel? {
        val yaml = file as? YAMLFile ?: file.viewProvider.getPsi(YAMLLanguage.INSTANCE) as? YAMLFile ?: return null
        return TaskFileModels.of(yaml)
    }

    private companion object {
        /** `dest` → `d`, `de`, `des`, `dest`. */
        fun prefixesOf(alias: String): List<String> = (1..alias.length).map { alias.substring(0, it) }
    }
}
