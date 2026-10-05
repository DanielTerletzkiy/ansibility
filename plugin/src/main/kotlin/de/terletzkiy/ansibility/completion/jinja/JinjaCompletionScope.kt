package de.terletzkiy.ansibility.completion.jinja

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.util.indexing.FileBasedIndex
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.JinjaContainer
import de.terletzkiy.ansibility.api.RenderContext
import de.terletzkiy.ansibility.api.RenderLoop
import de.terletzkiy.ansibility.api.RoleInfo
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.TemplateContextService
import de.terletzkiy.ansibility.index.PlayEntry
import de.terletzkiy.ansibility.index.PlayIndex
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaLocal
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaLocalKind
import de.terletzkiy.ansibility.model.task.TaskFileModels
import de.terletzkiy.ansibility.model.task.TaskItem
import de.terletzkiy.ansibility.model.task.TaskNode
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.resolve.loop.LiteralShapes
import de.terletzkiy.ansibility.resolve.loop.LoopItemTyper
import de.terletzkiy.ansibility.resolve.loop.TaskChains
import de.terletzkiy.ansibility.resolve.register.Cutoff
import de.terletzkiy.ansibility.resolve.register.IncludeSite
import de.terletzkiy.ansibility.resolve.register.RegisterVisibility
import de.terletzkiy.ansibility.resolve.register.RegisteredResult
import de.terletzkiy.ansibility.resolve.register.RegisteredResults
import de.terletzkiy.ansibility.resolve.register.RoleTaskOrder
import de.terletzkiy.ansibility.resolve.register.RuntimeName
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.vars.JinjaTextSites
import de.terletzkiy.ansibility.yaml.YamlPaths
import org.jetbrains.yaml.psi.YAMLFile
import java.util.Optional

/** A loop in scope at the caret (tier T1): the task's own loop, or the loops of a template's rendering tasks. */
internal class ScopeLoop(
    val loop: RenderLoop,
    /** The looping task, when there is exactly one. */
    val task: SourceLocation?,
    /** How many rendering tasks contribute (1 outside templates). */
    val contexts: Int,
)

/**
 * A tier-T2 name: a `vars:` key of the task, an enclosing block or an `include_tasks` that includes the file, or a
 * `template_vars` key of a lookup.
 */
internal class ScopeTaskVar(val name: String, val value: YValue?, val source: Source) {
    enum class Source { TASK, BLOCK, INCLUDE, TEMPLATE }
}

/** A tier-T3 role and where its runtime names end (null: every runtime name counts, e.g. in handlers or defaults). */
internal class ScopeRole(val info: RoleInfo, val cutoffs: List<Cutoff>?)

/** A play of the play scope (tier T4): its playbook and its `ansible.play` entry. */
internal data class ScopePlay(val file: VirtualFile, val entry: PlayEntry) {
    /** The directory the play's relative `vars_files` resolve against. */
    val playbookDir: VirtualFile? get() = file.parent
}

/**
 * Everything known about the caret of one Jinja completion (plan A.5 tiers): the host file, its root and context,
 * the Jinja text around the caret ([analysis]), the template's render contexts ([TemplateContextService]), the loops
 * (T1), task and block vars (T2), the own role(s) with their runtime cutoffs (T3) and, in playbooks, the play (T4).
 * Built once per completion in a read action.
 */
internal class JinjaCompletionScope private constructor(
    val project: Project,
    val root: AnsibleRoot,
    val file: VirtualFile,
    val fileContext: FileContext,
    val analysis: JinjaTextSites.Analysis,
    val hostOffset: Int,
    /** The host file's text (for the quoting of inserted keys). */
    val hostText: CharSequence,
    val contexts: List<RenderContext>,
    val loops: List<ScopeLoop>,
    val taskVars: List<ScopeTaskVar>,
    /** The caret's task with its enclosing blocks (YAML task files only). */
    val chain: List<TaskItem>,
    val roles: List<ScopeRole>,
    /** The play around the caret in a playbook. */
    val play: ScopePlay?,
    /**
     * `register`/`set_fact` names of the caret's own task-like file before the caret, when the file is not one of the
     * own role's task files (playbooks, molecule playbooks and task files); role files are covered by [roles].
     */
    val localRuntime: List<RuntimeName> = emptyList(),
) {
    val isTemplate: Boolean get() = analysis.container == JinjaContainer.TEMPLATE_FILE

    val catalog: RootNameCatalog by lazy(LazyThreadSafetyMode.NONE) { JinjaNameCatalogs.getInstance(project).catalog(root) }

    /** Jinja locals visible at the caret (tier T0), innermost first; namespace attributes are not bare names. */
    val locals: List<JinjaLocal> by lazy(LazyThreadSafetyMode.NONE) {
        analysis.result.localsVisibleAt(analysis.textOffset).filter { it.kind != JinjaLocalKind.NAMESPACE_ATTRIBUTE }.distinctBy { it.name }
    }

    /** Names of the own role(s). */
    val roleNames: Set<String> by lazy(LazyThreadSafetyMode.NONE) { roles.mapTo(LinkedHashSet()) { it.info.ref.name } }

    /** The host-file location of a local's binding. */
    fun locationOf(local: JinjaLocal): SourceLocation = SourceLocation(file, analysis.toHost(local.definitionRange.startOffset))

    /** The loop that defines [name], if any. */
    fun loopOf(name: String): ScopeLoop? = loops.firstOrNull { name in LoopItemTyper.namesOf(it.loop) }

    private val registered = HashMap<String, Optional<RegisteredResult>>()

    /**
     * The typed result of [name] when a `register:` visible at the caret defines it ([RegisteredResults], plan amendment
     * FU F1.12), memoised for this completion; null for every other name.
     */
    fun registered(name: String): RegisteredResult? = registered.getOrPut(name) {
        Optional.ofNullable(RegisteredResults.getInstance(project).at(file, hostOffset, name))
    }.orElse(null)

    /** The own role's `register`/`set_fact` names visible at the caret, in role order. */
    fun runtimeNames(role: ScopeRole): List<RuntimeName> {
        val order = RoleTaskOrder(project, role.info)
        val cutoffs = role.cutoffs
        return order.runtimeNames().filter { name -> cutoffs == null || cutoffs.any { order.isVisible(name, it) } }
    }

    companion object {
        /** The scope of [analysis] at [hostOffset] of the host [file], or null outside every root. */
        fun create(file: PsiFile, hostOffset: Int, analysis: JinjaTextSites.Analysis): JinjaCompletionScope? {
            val project = file.project
            val virtualFile = file.originalFile.viewProvider.virtualFile
            val context = AnsibleWorkspace.getInstance(project).contextOf(virtualFile) ?: return null
            val registry = RoleRegistry.getInstance(project)
            val ownRole = context.roleName?.let { registry.role(context.root, it) }
            val hostText = file.viewProvider.contents
            if (analysis.container == JinjaContainer.TEMPLATE_FILE) {
                val contexts = TemplateContextService.getInstance(project).renderContexts(virtualFile)
                val roles = templateRoles(project, context, ownRole, contexts)
                val includes = contexts.flatMap { rendering ->
                    val role = roles.firstOrNull { it.info.ref.name == rendering.role?.name } ?: return@flatMap emptyList()
                    RoleTaskOrder(project, role.info).enclosingIncludes(rendering.taskSite.file)
                }.distinctBy { it.task.range.startOffset to it.file }
                val loops = shadowed(loopsOf(contexts) + includeLoops(project, includes))
                return JinjaCompletionScope(
                    project, context.root, virtualFile, context, analysis, hostOffset, hostText, contexts,
                    loops, templateVars(contexts), emptyList(), roles, null,
                )
            }
            val yaml = file as? YAMLFile ?: YamlFiles.yamlFile(project, virtualFile)
            val taskLike = yaml != null && YamlPaths.isTopLevelSequence(yaml) && TaskFileModels.kindFor(context.kind) != null
            if (yaml == null || !taskLike) {
                val roles = listOfNotNull(ownRole?.let { ScopeRole(it, null) })
                return JinjaCompletionScope(project, context.root, virtualFile, context, analysis, hostOffset, hostText, emptyList(), emptyList(), emptyList(), emptyList(), roles, null)
            }
            val model = TaskFileModels.of(yaml)
            val chain = TaskChains.chainAt(model, hostOffset)
            val task = TaskChains.taskOf(chain)
            val ownLoops = task?.takeUnless { inLoopValue(it, hostOffset) }
                ?.let { LoopItemTyper.typeOf(project, yaml, it, chain) }
                ?.let { listOf(ScopeLoop(it.loop, it.task, 1)) }.orEmpty()
            // Loops and vars of the `include_tasks` that include this file (transitively) reach its tasks too.
            val includes = ownRole?.let { RoleTaskOrder(project, it).enclosingIncludes(virtualFile) }.orEmpty()
            val loops = shadowed(ownLoops + includeLoops(project, includes))
            val vars = (chain.flatMapIndexed { index, item ->
                val source = if (index == chain.lastIndex && item is TaskNode) ScopeTaskVar.Source.TASK else ScopeTaskVar.Source.BLOCK
                varsOf(item, source)
            }.asReversed() + includes.flatMap { site -> site.chain.asReversed().flatMap { varsOf(it, ScopeTaskVar.Source.INCLUDE) } }).distinctBy { it.name }
            val handlers = context.kind == FileKind.ROLE_HANDLERS
            // The task's own `register` is set only for its `until`/`changed_when`/`failed_when` expressions.
            val cutoff = RegisterVisibility.cutoffAt(virtualFile, model, hostOffset)
            val roles = listOfNotNull(ownRole?.let { ScopeRole(it, if (handlers) null else listOf(cutoff)) })
            val play = model.playAt(hostOffset)?.let { node -> playAt(project, virtualFile, node.range.startOffset) }
            val local = if (ownRole != null && virtualFile in ownRole.taskFiles) {
                emptyList()
            } else {
                RoleTaskOrder.runtimeNamesOf(virtualFile, model).filter { RoleTaskOrder.isVisibleInFile(it, cutoff) }
            }
            return JinjaCompletionScope(project, context.root, virtualFile, context, analysis, hostOffset, hostText, emptyList(), loops, vars, chain, roles, play, local)
        }

        /** The `ansible.play` entry of the play whose mapping starts at [start] in [file]. */
        private fun playAt(project: Project, file: VirtualFile, start: Int): ScopePlay? {
            val entries = FileBasedIndex.getInstance().getFileData(PlayIndex.NAME, file, project)[PlayIndex.KEY].orEmpty()
            val entry = entries.lastOrNull { it.importPlaybook == null && it.offset <= start } ?: return null
            return ScopePlay(file, entry)
        }

        private fun varsOf(item: TaskItem, source: ScopeTaskVar.Source): List<ScopeTaskVar> {
            val values = TaskChains.varsOf(item)
            return item.vars.map { ScopeTaskVar(it.text, values?.get(it.text), source) }
        }

        /** The typed loops of including tasks, nearest include first. */
        private fun includeLoops(project: Project, includes: List<IncludeSite>): List<ScopeLoop> =
            includes.mapNotNull { site -> LoopItemTyper.typeOf(project, site.yaml, site.task, site.chain)?.let { ScopeLoop(it.loop, it.task, 1) } }

        /** Loops whose variable an inner loop redefines are dropped (the inner one shadows it). */
        private fun shadowed(loops: List<ScopeLoop>): List<ScopeLoop> {
            val seen = HashSet<String>()
            return loops.filter { seen.add(it.loop.loopVar) }
        }

        /** True when [offset] is inside the task's own loop expression, where the loop variable is not defined yet. */
        private fun inLoopValue(task: TaskNode, offset: Int): Boolean {
            val range = task.loop?.value?.range ?: return false
            return offset >= range.start && offset <= range.end
        }

        /** One [ScopeLoop] per loop variable name over every rendering task; differing item types are unioned. */
        private fun loopsOf(contexts: List<RenderContext>): List<ScopeLoop> =
            contexts.mapNotNull { context -> context.loop?.let { it to context.taskSite } }
                .groupBy { it.first.loopVar }
                .map { (_, group) ->
                    val loops = group.map { it.first }
                    val items = loops.mapNotNull { it.item }
                    val item = items.reduceOrNull { a, b -> LiteralShapes.union(a, b) }
                    val source = loops.map { it.sourceVariable to it.sourcePath }.distinct().singleOrNull()
                    val merged = RenderLoop(
                        loopVar = loops.first().loopVar,
                        indexVar = loops.firstNotNullOfOrNull { it.indexVar },
                        extended = loops.any { it.extended },
                        item = item,
                        sourceVariable = source?.first,
                        sourcePath = source?.second.orEmpty(),
                    )
                    ScopeLoop(merged, group.map { it.second }.distinct().singleOrNull(), group.size)
                }

        private fun templateVars(contexts: List<RenderContext>): List<ScopeTaskVar> =
            contexts.flatMap { context -> context.taskVars.map { ScopeTaskVar(it, null, if (context.via.isEmpty()) ScopeTaskVar.Source.TASK else ScopeTaskVar.Source.TEMPLATE) } }
                .distinctBy { it.name }

        /**
         * The roles of a template: the template's own role and the roles of its rendering tasks, each visible up to
         * its rendering tasks (every runtime name when no task of that role renders it).
         */
        private fun templateRoles(project: Project, context: FileContext, ownRole: RoleInfo?, contexts: List<RenderContext>): List<ScopeRole> {
            val registry = RoleRegistry.getInstance(project)
            val result = LinkedHashMap<String, ScopeRole>()
            val byRole = contexts.filter { it.role != null }.groupBy { it.role!!.name }
            ownRole?.let { role ->
                val own = byRole[role.ref.name]
                result[role.ref.name] = ScopeRole(role, own?.map { Cutoff(it.taskSite.file, it.taskSite.offset, null) })
            }
            for ((name, group) in byRole) {
                ProgressManager.checkCanceled()
                if (name in result) continue
                val info = registry.role(context.root, name) ?: continue
                result[name] = ScopeRole(info, group.map { Cutoff(it.taskSite.file, it.taskSite.offset, null) })
            }
            return result.values.toList()
        }
    }
}
