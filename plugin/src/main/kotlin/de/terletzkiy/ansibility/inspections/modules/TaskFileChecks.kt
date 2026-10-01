package de.terletzkiy.ansibility.inspections.modules

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import de.terletzkiy.ansibility.api.AnsibleDocService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.inspections.keywords.KeywordChecks
import de.terletzkiy.ansibility.model.task.TaskFileModels
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import org.jetbrains.yaml.psi.YAMLFile

/**
 * The module option and keyword findings of one task-like YAML file (role tasks and handlers, playbooks, molecule
 * playbooks and task files), computed once and shared by the six inspections. Cached on the PSI file until it, the
 * Ansible structure (roots, `ansible.cfg`), a root's target version or the documentation changes; severities are
 * applied by each inspection, so settings changes need no recomputation.
 */
object TaskFileChecks {
    /** The findings of one file and the root they were computed for. */
    class Analysis(val root: AnsibleRoot, val modules: List<TaskProblem>, val keywords: List<TaskProblem>)

    private val ANALYSIS = Key.create<CachedValue<Analysis?>>("ansibility.inspections.taskChecks")
    private val MODULE_DEFAULTS = Key.create<CachedValue<Boolean>>("ansibility.inspections.moduleDefaultsInUse")

    /**
     * The analysis of [file], or null when it is not a checked Ansible file (outside every root, another file kind,
     * an injected fragment). Call inside a read action.
     */
    fun of(file: PsiFile): Analysis? {
        val yaml = file as? YAMLFile ?: return null
        val project = file.project
        if (InjectedLanguageManager.getInstance(project).isInjectedFragment(file)) return null
        val virtualFile = file.viewProvider.virtualFile
        val workspace = AnsibleWorkspace.getInstance(project)
        val context = workspace.contextOf(virtualFile) ?: return null
        if (context.kind !in TaskCheckEnvironment.CHECKED_KINDS) return null
        return CachedValuesManager.getCachedValue(yaml, ANALYSIS) {
            val current = workspace.contextOf(virtualFile)
            val analysis = current?.takeIf { it.kind in TaskCheckEnvironment.CHECKED_KINDS }?.let { compute(yaml, it.root) }
            CachedValueProvider.Result.create(
                analysis,
                yaml,
                workspace.structureTracker,
                TargetVersionDetector.getInstance(project).modificationTracker,
                AnsibleDocService.getInstance(project).docsTracker,
            )
        }
    }

    private fun compute(file: YAMLFile, root: AnsibleRoot): Analysis {
        val env = TaskCheckEnvironment.of(file.project, root)
        val model = TaskFileModels.of(file)
        val modules = ModuleOptionChecks.problems(model, env)
        val keywords = KeywordChecks.problems(model, PsiYValueAdapter.documentValue(file), env)
        return Analysis(root, modules, keywords)
    }

    /**
     * Whether any file of the project mentions `module_defaults`, which can supply required module options from a
     * play, block or role the task file does not show (ANS-M002 is not reported then). Deliberately project-wide, not
     * root-scoped: roles of a role library (`golden/`) run under plays of other roots, whose `module_defaults` apply
     * to them, so a narrower scope could turn a supplied option into a false "missing". An index lookup, cached until
     * the next PSI change.
     */
    fun moduleDefaultsInUse(project: Project): Boolean =
        CachedValuesManager.getManager(project).getCachedValue(project, MODULE_DEFAULTS, {
            val cost = PsiSearchHelper.getInstance(project).isCheapEnoughToSearch(MODULE_DEFAULTS_WORD, GlobalSearchScope.projectScope(project), null)
            CachedValueProvider.Result.create(cost != PsiSearchHelper.SearchCostResult.ZERO_OCCURRENCES, PsiModificationTracker.MODIFICATION_COUNT)
        }, false)

    private const val MODULE_DEFAULTS_WORD = "module_defaults"
}
