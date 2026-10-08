package de.terletzkiy.ansibility.inspections.undefined

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.EvalTarget
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.api.PlayRef
import de.terletzkiy.ansibility.api.RenderKind
import de.terletzkiy.ansibility.api.RoleInfo
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.api.TemplateContextService
import de.terletzkiy.ansibility.context.MoleculeView
import de.terletzkiy.ansibility.context.MoleculeVisibility
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.context.host.witness.DefinitionWitnesses
import de.terletzkiy.ansibility.context.host.witness.WitnessReport
import de.terletzkiy.ansibility.index.PathFacts
import de.terletzkiy.ansibility.model.role.RoleLayout
import de.terletzkiy.ansibility.model.task.TaskFileKind
import de.terletzkiy.ansibility.model.task.TaskFileModels
import de.terletzkiy.ansibility.model.task.TaskItem
import de.terletzkiy.ansibility.model.task.TaskNode
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.resolve.include.IncludeBindings
import de.terletzkiy.ansibility.resolve.loop.TaskChains
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import org.jetbrains.annotations.Nls
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequenceItem

/** Which row of the F8.12 severity table a finding is. */
enum class UndefinedKind {
    /**
     * Some reachable (env, host, play) that runs the use unconditionally has no definition in any layer: a certain
     * failure there (ERROR).
     */
    MISSING,

    /**
     * Some reachable context lacks the variable, but the use runs there only when something else holds (a `when`, a loop
     * that may be empty, a handler, an `{% if %}` branch, a template chosen by a variable): it fails there whenever it
     * runs (WARNING).
     */
    MISSING_WHEN_RUN,

    /** Every reachable context defines the variable today; the role's spec says optional and the role has no default. */
    EVERYWHERE_SET,

    /** No play of the root runs the use (the role's reach is empty); the role's spec says optional. */
    NOT_REACHED,
}

/** One ANS-V003 finding: an unguarded use of a variable without a runtime default. */
class UndefinedFinding internal constructor(
    val use: SiteUse,
    val kind: UndefinedKind,
    /** The roles that run the use, none of which defaults the variable (empty for a playbook's own task). */
    val roles: List<RoleInfo>,
    /** The hosts without a definition (of [UndefinedKind.MISSING] and [UndefinedKind.MISSING_WHEN_RUN]), in context order. */
    val missing: List<HostKey>,
    /** The spec option of the first role that declares the variable, or null. */
    val spec: OptionSpec?,
    /** Whether the use is a YAML task's (the `when:` fix applies), with the task's mapping offset. */
    val taskOffset: Int,
    @Nls val message: String,
) {
    val name: String get() = use.name

    /** A witness exists: `SeverityPolicy` makes the finding an ERROR (`FindingContext.certainFailure`). */
    val certainFailure: Boolean get() = kind == UndefinedKind.MISSING

    override fun toString(): String = "UndefinedFinding($name ${kind.name} ${use.nameRange} missing=${missing.map { "${it.environment}/${it.host}" }})"
}

/**
 * ANS-V003 "possibly undefined variable" (plan amendment R7/R8, addendum F8.12; WU HA8d): the analysis of one host
 * file. The inspection, its tests and the corpus run call [findings]; the card section reuses [UndefinedRules].
 *
 * **(a) Uses.** Free Jinja variable uses ([UseSites]) in role templates (and other `*.j2` files of a root), role task
 * and handler files, role `defaults/`/`vars/` files and playbooks' tasks and role entries. Never reported: Jinja
 * locals, globals, special variables and facts ([UndefinedRules.isAlwaysDefined]); loop and index variables of any task
 * of the root; `name:` values (templated for display only, failures are swallowed) and `debug.var` (prints "VARIABLE IS
 * NOT DEFINED!"); names a task of the role (or of a dependency) sets itself (`set_fact`, `register`, a literal
 * `include_vars` file, include params, `template_vars`), or that a playbook's own task sets; the rendering task's and
 * its blocks' `vars:`, loop and index variables; names some include task that runs the use's task file (or the
 * rendering task's file) gives it ([IncludeBindings.names]: its `vars:`, the loop names of a looping include; any
 * includer counts, Molecule includers not for production files; the role params of a play's `include_role`, and those of
 * an `include_role` whose entry file a play also applies directly, are left to the witness, which evaluates them per
 * play); variables the owning role's spec marks `required: true` (ANS-P003's job, no double report). Play vars, `vars_files`, role params and every inventory layer are definitions, evaluated
 * per context by the witness. A name the role sets anywhere counts, not only before the use: the order of tasks across
 * dynamic includes, handlers and later plays is not known statically, so the check stays silent rather than guess.
 *
 * **(b) No runtime default** in the role that runs the use (for a template: the roles of its render contexts, or its own
 * role) nor in its `meta/main.yml` dependencies ([UndefinedRules.hasRuntimeDefault]; a spec `default:` is documentation).
 *
 * **(c) Not guarded** in the Jinja ([PsiJinjaUses]) and not by the `when:` of the running task, its blocks, its include
 * chain or the role's application in the play ([TaskGuards]); a template is guarded per render context. The same
 * [Gate]s tell whether the use runs unconditionally.
 *
 * **(d) Witness.** Over every reachable context that runs the use unguarded ([DefinitionWitnesses], selection-free,
 * D32): a context where no layer defines the name and no runtime source may set it is a witness. When the use runs
 * there unconditionally, the finding is [UndefinedKind.MISSING] (a certain failure: ERROR through `SeverityPolicy`);
 * when it runs only if something else holds (a `when`, a loop over a variable, `run_once`, `rescue`, a handler, an
 * `{% if %}`/`{% for %}`/macro body or an inline branch, a template chosen by a variable or included by another
 * template, a value in `defaults/`/`vars/` or a role param, which ansible-core templates only where it is used), it is
 * [UndefinedKind.MISSING_WHEN_RUN] (WARNING). A template no task is known to render and a task file no literal include
 * reaches (a commented-out or templated include) yield no witness: nothing shows they run. Without a witness, only for
 * variables the owning role's spec declares optional: [UndefinedKind.EVERYWHERE_SET] when some context gets the value
 * from the inventory alone (no role default of another role of the play, no play var, `vars_files` entry or role param
 * would define it), or
 * [UndefinedKind.NOT_REACHED] when no context runs the use (WARNING; ERROR with the root's "always errors" setting). A
 * `| mandatory` use is exempt from every WARNING row.
 *
 * Call in a read action in smart mode; nothing here runs a process or decrypts a vault value.
 */
internal class PossiblyUndefined(private val project: Project, private val file: PsiFile, private val context: FileContext) {
    private val root: AnsibleRoot = context.root
    private val virtualFile: VirtualFile = file.viewProvider.virtualFile
    private val rules = UndefinedRules(project, root, virtualFile)
    private val guardRules = GuardRules.of(TargetVersionDetector.getInstance(project).targetVersion(root).version)
    private val guards = TaskGuards(project, guardRules)
    private val witnesses = DefinitionWitnesses.getInstance(project)
    private val scopes = HashMap<Any, DefinitionWitnesses.WitnessScope>()
    private val reports = HashMap<Pair<DefinitionWitnesses.WitnessScope, String>, WitnessReport>()
    private val entryPoints = HashMap<VirtualFile, Set<String>>()
    private val targetGates = HashMap<Triple<PlayRef, VirtualFile, VirtualFile?>, Gate>()
    private val includeNames = HashMap<VirtualFile, Set<String>>()

    /** Analysis of a production file never counts Molecule includers (plan amendment R20, D157). */
    private val analysisView: MoleculeView = MoleculeView.forAnalysis(project, virtualFile)

    /** What runs a use: a role's task or template, or a play's own task. */
    private class Runner(
        /** The running role, or null for a playbook's own task. */
        val role: RoleInfo?,
        /** The play of a playbook's own task (its hosts are the targets). */
        val play: PlayRef?,
        /**
         * Names the running task provides itself (task and block `vars:`, loop and index variables, `template_vars`) and
         * the names some include task that runs its file gives it ([IncludeBindings.names]: `vars:` of includes and
         * imports, loop names of looping includes; role params the witness evaluates per play are not among them).
         */
        val provided: Set<String>,
        /** The task's file and the offset its guards are computed at (-1: no task, a vars file). */
        val taskFile: VirtualFile?,
        val taskOffset: Int,
        /** False for a template without render contexts: nothing proves it is rendered, so it has no witness. */
        val renderKnown: Boolean = true,
        /**
         * The use may not run for a reason the task's [Gate] does not show: a handler, a template chosen by a variable
         * (`src: "{{ item.template }}"`) or included by another template, a lazily templated value (a role default or
         * var, a role param).
         */
        val conditional: Boolean = false,
        /** Whether the task's loop gates the use (false for a template the loop selects itself: a fileglob render). */
        val loopGates: Boolean = true,
    )

    /** A use with its runners and, for YAML tasks, the task mapping's offset. */
    private class Located(val use: SiteUse, val runners: List<Runner>, val taskOffset: Int)

    fun findings(): List<UndefinedFinding> {
        val located = when (shapeOf(virtualFile, context) ?: return emptyList()) {
            Shape.TEMPLATE -> {
                val runners = templateRunners()
                UseSites.template(file, guardRules).map { Located(it, runners, -1) }
            }
            Shape.ROLE_TASKS -> yamlUses(guardRules) { scalar, use, yaml -> roleTaskUse(yaml, scalar, use) }
            Shape.ROLE_VARS -> {
                val role = ownRole() ?: return emptyList()
                // a value of defaults/ or vars/ is templated lazily: only where a task uses the variable holding it
                // and no higher layer overrides it
                yamlUses(guardRules) { _, use, _ -> Located(use, listOf(Runner(role, null, emptySet(), null, -1, conditional = true)), -1) }
            }
            Shape.PLAYBOOK -> yamlUses(guardRules) { scalar, use, yaml -> playbookUse(yaml, scalar, use) }
        }
        val findings = ArrayList<UndefinedFinding>()
        for (entry in located) {
            ProgressManager.checkCanceled()
            if (entry.use.raw.guarded || UndefinedRules.isAlwaysDefined(entry.use.name)) continue
            judge(entry)?.let(findings::add)
        }
        return findings
    }

    // ------------------------------------------------------------------------------------------------ judging

    private fun judge(entry: Located): UndefinedFinding? {
        val use = entry.use
        val name = use.name
        if (rules.isLoopVariable(name)) return null
        val roles = (entry.runners.mapNotNull { it.role } + listOfNotNull(ownRole())).distinctBy { it.ref.dir }
        val specs = roles.flatMap { rules.specOptions(it, name) }
        if (specs.any { it.required }) return null
        if (roles.any { rules.hasRuntimeDefault(it, name) || rules.setByRoleTasks(it, name) }) return null

        val missing = LinkedHashSet<HostKey>()
        val missingWhenRun = LinkedHashSet<HostKey>()
        var reached = false
        var fromInventory = false
        var unguarded = false
        val reasons = LinkedHashSet<String>()
        for (runner in entry.runners) {
            ProgressManager.checkCanceled()
            if (name in runner.provided) continue
            val static = if (runner.taskFile != null && runner.taskOffset >= 0) staticGate(runner, use) else Gate.OPEN
            if (name in static.defined) continue
            val scope = scopeOf(runner)
            val gates = scope.targets.map { targetGate(runner, it) }
            val open = scope.targets.indices.filter { name !in gates[it].defined }
            if (scope.targets.isNotEmpty() && open.isEmpty()) continue
            unguarded = true
            val report = reports.getOrPut(scope to name) { scope.report(name) }
            if (open.isEmpty()) {
                report.emptyReason?.let(reasons::add)
                continue
            }
            reached = true
            val openTargets = open.mapTo(HashSet()) { scope.targets[it] }
            if (report.inventoryOnlyOn.any { it in openTargets }) fromInventory = true
            if (!runner.renderKnown || !taskFileRuns(runner, scope)) continue
            val witnesses = report.missingOn.toSet()
            val conditional = use.raw.conditional || runner.conditional || static.conditional
            for (index in open) {
                val target = scope.targets[index]
                if (target !in witnesses) continue
                if (conditional || gates[index].conditional) missingWhenRun += target.host else missing += target.host
            }
        }
        if (!unguarded) return null
        missingWhenRun.removeAll(missing)
        val roleNames = roles.map { it.ref.name }
        val kind = when {
            missing.isNotEmpty() -> UndefinedKind.MISSING
            use.raw.mandatory -> return null
            missingWhenRun.isNotEmpty() -> UndefinedKind.MISSING_WHEN_RUN
            specs.isEmpty() -> return null
            reached && fromInventory -> UndefinedKind.EVERYWHERE_SET
            reached -> return null
            else -> UndefinedKind.NOT_REACHED
        }
        val hosts = if (kind == UndefinedKind.MISSING) missing.toList() else missingWhenRun.toList()
        val message = when (kind) {
            UndefinedKind.MISSING -> AnsibilityUndefinedBundle.message("v003.missing", name, UndefinedRules.hostsText(hosts), reasonText(roleNames))
            UndefinedKind.MISSING_WHEN_RUN ->
                AnsibilityUndefinedBundle.message("v003.missing.when.run", name, UndefinedRules.hostsText(hosts), reasonText(roleNames))
            UndefinedKind.EVERYWHERE_SET -> AnsibilityUndefinedBundle.message("v003.everywhere", name, UndefinedRules.rolesText(roleNames))
            UndefinedKind.NOT_REACHED -> notReachedMessage(name, roleNames, entry.runners, reasons)
        }
        return UndefinedFinding(use, kind, roles, hosts, specs.firstOrNull(), entry.taskOffset, message)
    }

    /** "role alloy has no default for it", or "nothing gives it a default" for a playbook's own task. */
    @Nls
    private fun reasonText(roles: List<String>): String = when (roles.size) {
        0 -> AnsibilityUndefinedBundle.message("v003.reason.none")
        1 -> AnsibilityUndefinedBundle.message("v003.reason.role", roles.single())
        else -> AnsibilityUndefinedBundle.message("v003.reason.roles", roles.joinToString(", "))
    }

    @Nls
    private fun notReachedMessage(name: String, roles: List<String>, runners: List<Runner>, reasons: Set<String>): String {
        val noPlay = runners.all { runner ->
            val role = runner.role ?: return@all false
            PlayGraph.getInstance(project).playsApplying(root, role.ref.name).isEmpty()
        }
        val reason = reasons.firstOrNull()
        return if (noPlay || reason == null) {
            AnsibilityUndefinedBundle.message("v003.not.reached", name, UndefinedRules.rolesText(roles))
        } else {
            AnsibilityUndefinedBundle.message("v003.not.reached.reason", name, UndefinedRules.rolesText(roles), reason)
        }
    }

    /**
     * Whether the runner's task file is known to run: a file of the role's `tasks/` must be reachable through literal
     * includes from an entry point the role is applied with (`main`, or a `tasks_from`); other files (handlers, vars
     * files, playbooks) always count.
     */
    private fun taskFileRuns(runner: Runner, scope: DefinitionWitnesses.WitnessScope): Boolean {
        val role = runner.role ?: return true
        val taskFile = runner.taskFile ?: return true
        val tasksDir = role.ref.dir.findChild(RoleLayout.TASKS) ?: return true
        if (!VfsUtilCore.isAncestor(tasksDir, taskFile, true)) return true
        val entryPoints = entryPoints.getOrPut(role.ref.dir) {
            val graph = PlayGraph.getInstance(project)
            val used = scope.targets.mapNotNull { it.play }.distinct().flatMap { play ->
                graph.rolesOfPlay(play).filter { it.role?.dir == role.ref.dir }.map { it.entryPoint }
            }
            (used + RoleLayout.MAIN).toSet()
        }
        return guards.runs(role.ref.dir, entryPoints, taskFile)
    }

    /**
     * The gate of the runner's own task chain at the use (a use in a `when` list sees only the earlier items, a use in
     * the loop is not gated by it).
     */
    private fun staticGate(runner: Runner, use: SiteUse): Gate {
        val taskFile = runner.taskFile ?: return Gate.OPEN
        val offset = if (taskFile == virtualFile) use.nameRange.startOffset else runner.taskOffset
        return guards.chainGate(taskFile, offset, runner.loopGates)
    }

    /** The gate a target adds: the role's application in the target's play and the include chain from its entry point. */
    private fun targetGate(runner: Runner, target: EvalTarget): Gate {
        val role = runner.role ?: return Gate.OPEN
        val play = target.play ?: return Gate.OPEN
        return targetGates.getOrPut(Triple(play, role.ref.dir, runner.taskFile)) { guards.playGate(play, role.ref.dir, runner.taskFile) }
    }

    private fun scopeOf(runner: Runner): DefinitionWitnesses.WitnessScope {
        val role = runner.role
        val play = runner.play
        return when {
            role != null -> scopes.getOrPut(role.ref.dir) { witnesses.ofRole(root, role.ref.name) }
            play != null -> scopes.getOrPut(play) {
                val targets = AnsibleContextService.getInstance(project).allHostsScope(play.file).targets.filter { it.play == play }
                witnesses.of(root, targets, null)
            }
            else -> scopes.getOrPut(Unit) { witnesses.of(root, emptyList(), null) }
        }
    }

    // ------------------------------------------------------------------------------------------------ runners

    private var ownRoleCache: RoleInfo? = null
    private var ownRoleLoaded = false

    /** The role of the analysed file (same root), or null. */
    private fun ownRole(): RoleInfo? {
        if (!ownRoleLoaded) {
            ownRoleCache = RoleRegistry.getInstance(project).roleOf(virtualFile)?.takeIf { it.ref.rootDir == root.dir }
            ownRoleLoaded = true
        }
        return ownRoleCache
    }

    /** One runner per render context of the template; its own role (with unknown rendering) when it has none. */
    private fun templateRunners(): List<Runner> {
        val registry = RoleRegistry.getInstance(project)
        val contexts = TemplateContextService.getInstance(project).renderContexts(virtualFile)
        val runners = contexts.mapNotNull { render ->
            ProgressManager.checkCanceled()
            val provided = HashSet(render.taskVars)
            render.loop?.let { loop ->
                provided += loop.loopVar
                loop.indexVar?.let(provided::add)
            }
            // The includers of the rendering task's file give the template their vars and loop names too (any includer).
            provided += includedNames(render.taskSite.file)
            val roleRef = render.role
            val conditional = render.kind in CHOSEN_BY_VALUE || render.via.isNotEmpty() || isHandlerFile(render.taskSite.file, roleRef?.dir)
            val loopGates = render.kind != RenderKind.FILEGLOB
            when {
                roleRef != null && roleRef.rootDir == root.dir -> registry.role(root, roleRef.name)
                    ?.let { Runner(it, null, provided, render.taskSite.file, render.taskSite.offset, conditional = conditional, loopGates = loopGates) }
                roleRef == null -> playAt(render.taskSite.file, render.taskSite.offset)
                    ?.let { Runner(null, it, provided, render.taskSite.file, render.taskSite.offset, conditional = conditional, loopGates = loopGates) }
                else -> null
            }
        }
        if (runners.isNotEmpty()) return runners
        val own = ownRole() ?: return emptyList()
        return listOf(Runner(own, null, emptySet(), null, -1, renderKnown = false))
    }

    /** The play of a playbook whose mapping contains [offset]. */
    private fun playAt(file: VirtualFile, offset: Int): PlayRef? {
        val yaml = YamlFiles.yamlFile(project, file) ?: return null
        val node = TaskFileModels.of(yaml, TaskFileKind.PLAYBOOK).playAt(offset) ?: return null
        return PlayGraph.getInstance(project).playsOf(file).getOrNull(node.index)?.ref
    }

    private fun yamlUses(rules: GuardRules, locate: (YAMLScalar, SiteUse, YAMLFile) -> Located?): List<Located> {
        val yaml = file as? YAMLFile ?: YamlFiles.yamlFile(project, virtualFile) ?: return emptyList()
        return UseSites.yaml(yaml, rules).mapNotNull { use -> use.scalar?.let { locate(it, use, yaml) } }
    }

    /** A use in a role's task or handler file: the role runs it; the task's chain provides and guards. */
    private fun roleTaskUse(yaml: YAMLFile, scalar: YAMLScalar, use: SiteUse): Located? {
        val role = ownRole() ?: return null
        val chain = TaskChains.chainAt(TaskFileModels.of(yaml), use.nameRange.startOffset)
        val task = chain.lastOrNull() ?: return null
        if (isDisplayOnly(scalar, task)) return null
        val runner = Runner(role, null, provided(chain) + includedNames(virtualFile), virtualFile, task.range.startOffset, conditional = isHandlerFile(virtualFile, role.ref.dir))
        return Located(use, listOf(runner), task.range.startOffset)
    }

    /** A use in a playbook: a task of a play, or a value of a `roles:` entry; play-level keys are not checked. */
    private fun playbookUse(yaml: YAMLFile, scalar: YAMLScalar, use: SiteUse): Located? {
        val offset = use.nameRange.startOffset
        val model = TaskFileModels.of(yaml, TaskFileKind.PLAYBOOK)
        val node = model.playAt(offset) ?: return null
        val play = PlayGraph.getInstance(project).playsOf(virtualFile).getOrNull(node.index)?.ref ?: return null
        val chain = TaskChains.chainAt(model, offset)
        val task = chain.lastOrNull()
        if (task != null) {
            if (isDisplayOnly(scalar, task)) return null
            return Located(use, listOf(Runner(null, play, provided(chain), virtualFile, task.range.startOffset)), task.range.startOffset)
        }
        val entry = node.roles.firstOrNull { it.range.containsOffset(offset) } ?: return null
        if (entry.name?.range?.containsOffset(offset) == true) return null
        if ((scalar.parent as? YAMLKeyValue)?.keyText in ENTRY_DISPLAY_KEYS) return null
        // a `roles:` entry's `when` is evaluated before each of the role's tasks; its params are play-level values
        val conditions = entry.expressions.filter { it.key == WHEN && !it.range.containsOffset(offset) }
        if (use.name in guards.conditionGuards(yaml, conditions)) return null
        // role params are templated lazily, like role defaults: only where the role uses them
        return Located(use, listOf(Runner(null, play, entry.vars.mapTo(HashSet()) { it.text }, null, -1, conditional = true)), -1)
    }

    /**
     * The names the include tasks that run [taskFile] give it, transitively: a name counts as set when ANY includer sets
     * it (its `vars:`, or the loop variable, `index_var` or `ansible_loop` of a looping `include_tasks`/`include_role`),
     * like a loop variable of any task of the root. Molecule includers do not count for a production file; role params
     * the witness evaluates per play stay with the witness ([IncludeBindings.names]).
     */
    private fun includedNames(taskFile: VirtualFile): Set<String> =
        includeNames.getOrPut(taskFile) { IncludeBindings.names(project, taskFile, analysisView) }

    /** Names the task chain provides: task and block `vars:`, the task's loop and index variables. */
    private fun provided(chain: List<TaskItem>): Set<String> {
        val names = HashSet<String>()
        for (item in chain) item.vars.mapTo(names) { it.text }
        (chain.lastOrNull() as? TaskNode)?.let { task ->
            task.loopVar?.let(names::add)
            task.loopControl?.indexVar?.text?.let(names::add)
        }
        return names
    }

    /** `name:` of a task or block (templated for display, failures swallowed) and `debug`'s `var` (never fails). */
    private fun isDisplayOnly(scalar: YAMLScalar, item: TaskItem): Boolean {
        val keyValue = scalar.parent as? YAMLKeyValue ?: return false
        val mapping = keyValue.parentMapping ?: return false
        if (keyValue.keyText == NAME && mapping.textRange.startOffset == item.range.startOffset && mapping.parent is YAMLSequenceItem) return true
        if (keyValue.keyText == VAR && item is TaskNode && item.module?.canonical == DEBUG) {
            return item.module.args.options[VAR]?.value?.range?.start == scalar.textRange.startOffset
        }
        return false
    }

    /** Whether [file] lies in the `handlers/` directory of the role at [roleDir]: it runs only when notified. */
    private fun isHandlerFile(file: VirtualFile, roleDir: VirtualFile?): Boolean {
        val handlers = roleDir?.findChild(RoleLayout.HANDLERS) ?: return false
        return VfsUtilCore.isAncestor(handlers, file, true)
    }

    private enum class Shape { TEMPLATE, ROLE_TASKS, ROLE_VARS, PLAYBOOK }

    private fun shapeOf(file: VirtualFile, context: FileContext): Shape? {
        // Molecule files run in their scenarios only (plan amendment R20): no production witness applies to them.
        if (MoleculeVisibility.isMoleculeFile(context.root, file)) return null
        return when (context.kind) {
            FileKind.ROLE_TEMPLATE -> Shape.TEMPLATE
            FileKind.ROLE_TASKS, FileKind.ROLE_HANDLERS -> Shape.ROLE_TASKS.takeIf { context.roleDir != null }
            FileKind.ROLE_DEFAULTS, FileKind.ROLE_VARS -> Shape.ROLE_VARS.takeIf { context.roleDir != null }
            FileKind.PLAYBOOK -> Shape.PLAYBOOK
            FileKind.OTHER -> Shape.TEMPLATE.takeIf { PathFacts.isJ2(file.name) }
            else -> null
        }
    }

    private companion object {
        const val NAME = "name"
        const val VAR = "var"
        const val WHEN = "when"
        const val DEBUG = "ansible.builtin.debug"
        val ENTRY_DISPLAY_KEYS = setOf("name", "role", "tags")

        /** Render kinds whose template is chosen by a variable's value: rendered only where the value names it. */
        val CHOSEN_BY_VALUE = setOf(RenderKind.DYNAMIC_PREFIX, RenderKind.WHOLE_VAR, RenderKind.INCLUDE)
    }
}
