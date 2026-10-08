package de.terletzkiy.ansibility.render.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.semantics.render.RenderScope
import de.terletzkiy.ansibility.semantics.render.Binding
import de.terletzkiy.ansibility.resolve.include.Includer
import de.terletzkiy.ansibility.resolve.include.IncludePath
import de.terletzkiy.ansibility.context.host.values.HostValueServiceImpl
import de.terletzkiy.ansibility.api.InventoryFacts
import de.terletzkiy.ansibility.api.HostValues
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.EvalTarget
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.api.HostScope
import de.terletzkiy.ansibility.api.HostScopeOrigin
import de.terletzkiy.ansibility.api.HostValueService
import de.terletzkiy.ansibility.api.RenderContext
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.api.TemplateContextService
import de.terletzkiy.ansibility.context.AnsibleCfg
import de.terletzkiy.ansibility.context.MoleculeView
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.render.AnsibilityRenderBundle
import de.terletzkiy.ansibility.render.bind.IncludeRuns
import de.terletzkiy.ansibility.render.bind.LoopItems
import de.terletzkiy.ansibility.render.bind.RenderBinder
import de.terletzkiy.ansibility.render.bind.TaskSite
import de.terletzkiy.ansibility.render.lookup.SearchPath
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.render.EnvOptions
import de.terletzkiy.ansibility.semantics.render.RValue
import de.terletzkiy.ansibility.semantics.render.RenderMode
import de.terletzkiy.ansibility.semantics.render.RenderOptions
import de.terletzkiy.ansibility.semantics.render.Rendered
import de.terletzkiy.ansibility.semantics.render.TemplateRenderer
import de.terletzkiy.ansibility.semantics.render.TemplateTree
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.typeflow.LexerJinjaTokenizer
import org.jetbrains.annotations.Nls
import java.io.IOException

/** Which way to render a template: the [context]-th render context (-1: no task, the role's scope only) and its loop [item]. */
data class PreviewPick(val context: Int, val item: Int?)

/** One entry of the preview's render-target picker. */
class PreviewChoice(val pick: PreviewPick, @Nls val label: String)

/**
 * One rendered preview (plan amendment R11, F11.2). [text] is vault-free: holes print as markers. [problem] is set
 * instead of a render when there is nothing to render with (no root, a vault file).
 */
class PreviewReport(
    val text: String,
    val rendered: Rendered?,
    val choices: List<PreviewChoice>,
    val pick: PreviewPick?,
    @Nls val headline: String,
    @Nls val problem: String?,
    /** The vault values the render met, by name, with where each is written; holes unless the tab opted in. */
    val secretSources: Map<String, SourceLocation> = emptyMap(),
    /** How many of them printed in plaintext (the tab's "Render vault values" was on and the decrypt worked). */
    val secretsShown: Int = 0,
) {
    val complete: Boolean get() = rendered?.complete == true
}

/** One rendered output of a templated value and who produces it: `prod › prod-prod1`, `item 2 (web)` … */
class ValueOutcome(
    val text: String,
    val who: List<String>,
    /** The first error the run would raise here (the text is then what rendered before it), or null. */
    val error: String?,
    val placeholders: Int,
)

/**
 * A templated value rendered over the hosts of its scope × its task's loop items (plan amendment R11, F11.1), equal
 * outputs grouped. [hosts] counts the hosts rendered; [items] the loop length when the task loops; [itemsUnknown] why
 * the loop could not be evaluated. [noHost] is set when nothing selects a host, so names resolve root-wide.
 */
class ValueReport(
    val outcomes: List<ValueOutcome>,
    val hosts: Int,
    val items: Int?,
    val itemsUnknown: String?,
    val core: CoreVersion,
    @Nls val noHost: String?,
    /** The rendering tasks of a template, when [ValueReport] is about a template file. */
    val tasks: Int = 0,
)

/**
 * Renders template files for the preview (F11.2): the render contexts of the template × the root's selected host
 * (the first target of the task's host scope) × the task's loop items whose `src` renders this file. When include
 * tasks run the rendering task's file (or the file of a rendered value), each include path is a context of its own
 * ([IncludeRuns]): its includers' `vars:` bind under the task's own, and its looping includers' items (the product of
 * nested ones) are further choices, labelled by the includer (`rules.yml:8 · item 1 (/etc/a)`). Call in a read
 * action in smart mode; reads models and documents only, never runs a process, never decrypts.
 */
@Service(Service.Level.PROJECT)
class TemplatePreviewService(private val project: Project) {

    /** Whether [file] gets the preview: a role template, or any `.j2` inside an Ansible root. */
    fun isTemplate(file: VirtualFile): Boolean {
        if (file.isDirectory) return false
        val context = AnsibleWorkspace.getInstance(project).contextOf(file) ?: return false
        return context.kind == FileKind.ROLE_TEMPLATE || file.extension == "j2"
    }

    fun render(template: VirtualFile, source: String, pick: PreviewPick?, secrets: Map<String, String> = emptyMap()): PreviewReport {
        val fileContext = AnsibleWorkspace.getInstance(project).contextOf(template)
            ?: return problem(AnsibilityRenderBundle.message("preview.problem.no.root"))
        if (source.startsWith("\$ANSIBLE_VAULT")) return problem(AnsibilityRenderBundle.message("preview.problem.vault"))
        val root = fileContext.root
        val core = TargetVersionDetector.getInstance(project).targetVersion(root).version ?: CoreVersion.PINNED
        val cfg = cfgOf(root)
        val jinja2Native = cfg?.value("defaults", "jinja2_native")?.lowercase() in setOf("true", "yes", "1", "on")
        val contexts = TemplateContextService.getInstance(project).renderContexts(template).take(MAX_CONTEXTS)
        val contextService = AnsibleContextService.getInstance(project)

        /**
         * One way to render with a prepared context: [pick] is its [PreviewPick.item] (the own loop item's index, or
         * the running number when includers loop), [label] the picker's text, [locals] the loop names it binds,
         * [headline] what the headline says about it (include, include items, own item).
         */
        class Variant(val pick: Int?, @Nls val label: String, val locals: Map<String, RValue>, val headline: List<String>)

        class Prepared(
            val context: RenderContext?, val site: TaskSite?, val scope: HostScope, val target: EvalTarget?, val binder: RenderBinder,
            val search: SearchPath, val variants: List<Variant>, val itemsUnknown: String?,
        )

        fun prepare(context: RenderContext?, include: IncludeRuns.Variant?): Prepared {
            ProgressManager.checkCanceled()
            val site = context?.let { TaskSite.of(project, it) }?.withInclude(include)
            val scope = if (context != null) contextService.hostScope(context.taskSite.file, context.taskSite.offset) else contextService.hostScope(template)
            val target = scope.targets.firstOrNull()
            val roleName = context?.role?.name ?: fileContext.roleName
            val roleDir = context?.role?.dir ?: fileContext.roleDir
            val playbookDir = target?.play?.playbookDir ?: context?.takeIf { it.role == null }?.taskSite?.file?.parent
            val values = target?.let { hostValues(it, roleName, site) }
            val facts = target?.let { contextService.inventoryFacts(scope) }
            val managed = cfg?.value("defaults", "ansible_managed")
            val binder = RenderBinder(target, values, facts, template, roleName, roleDir, playbookDir, core, managed, secrets)
            val search = SearchPath(roleDir, playbookDir, listOfNotNull(root.dir, root.parentDir) + root.rolesDirs)
            val options = RenderOptions(RenderMode.YAML_VALUE, core, jinja2Native = jinja2Native)
            val task = site?.task?.name?.text ?: context?.let { "${it.taskSite.file.name}" } ?: AnsibilityRenderBundle.message("preview.choice.no.task")
            val taskLabel = listOfNotNull(task, include?.label).joinToString(" · ")
            val includeHeadline = listOfNotNull(include?.label)
            if (site == null) return Prepared(context, null, scope, target, binder, search, listOf(Variant(null, taskLabel, emptyMap(), includeHeadline)), null)
            val includerScopes = includerScopes(target, facts, template, core, managed, playbookDir, include?.path)
            val runs = if (include == null) null else IncludeRuns.runs(include, includerScopes, options, search.resolver(), MAX_PREVIEW_RUNS, ::hoverItem)
            val looping = runs?.total != null
            var unknown = runs?.unknown
            val variants = ArrayList<Variant>()
            for (run in runs?.runs ?: listOf(IncludeRuns.Run(emptyList(), emptyMap()))) {
                ProgressManager.checkCanceled()
                // Include runs × own items stay within one loop's bound per context (the picker's and the render's cost).
                if (variants.size >= MAX_PREVIEW_CHOICES) break
                val runLabel = (listOf(taskLabel) + run.labels).joinToString(" · ")
                val headline = includeHeadline + run.labels
                when (val items = LoopItems.evaluate(site, binder.scope(run.locals), options, search.resolver())) {
                    is LoopItems.Result.Items -> for (item in items.items) {
                        if (variants.size >= MAX_PREVIEW_CHOICES) break
                        if (!rendersThis(site, item, run.locals, binder, options, search, template)) continue
                        variants += Variant(
                            if (looping) variants.size else item.index,
                            AnsibilityRenderBundle.message("preview.choice.item", runLabel, item.index + 1, items.total, item.label),
                            run.locals + item.locals,
                            headline + AnsibilityRenderBundle.message("preview.headline.item", item.index + 1, items.total),
                        )
                    }
                    is LoopItems.Result.Unknown -> {
                        if (unknown == null) unknown = items.reason
                        variants += Variant(if (looping) variants.size else null, runLabel, run.locals, headline)
                    }
                    null -> variants += Variant(if (looping) variants.size else null, runLabel, run.locals, headline)
                }
            }
            return Prepared(context, site, scope, target, binder, search, variants, unknown)
        }

        // One prepared context per render context and include path of its task file (the includers' vars and loops; a
        // Molecule includer never binds in a production file, plan amendment R20).
        val prepared = if (contexts.isEmpty()) {
            listOf(prepare(null, null))
        } else {
            contexts.flatMap { context ->
                IncludeRuns.variants(project, context.taskSite.file, MoleculeView.forAnalysis(project, context.taskSite.file)).map { context to it }
            }.take(MAX_CONTEXTS).map { (context, include) -> prepare(context, include) }
        }
        val choices = ArrayList<PreviewChoice>()
        prepared.forEachIndexed { i, p ->
            val index = if (p.context == null) -1 else i
            p.variants.forEach { choices += PreviewChoice(PreviewPick(index, it.pick), it.label) }
        }
        val chosen = choices.firstOrNull { it.pick == pick }?.pick ?: choices.firstOrNull()?.pick
        val p = prepared.firstOrNull { (if (it.context == null) -1 else prepared.indexOf(it)) == chosen?.context } ?: prepared.first()
        val variant = p.variants.firstOrNull { it.pick == chosen?.item } ?: p.variants.firstOrNull()
        val env = EnvOptions(
            trimBlocks = p.site?.flag("trim_blocks") ?: true,
            lstripBlocks = p.site?.flag("lstrip_blocks") ?: false,
            newlineSequence = (p.site?.option("newline_sequence") as? YScalar)?.text?.let(::unescapeNewline) ?: "\n",
        )
        val options = RenderOptions(RenderMode.TEMPLATE_FILE, core, env, jinja2Native = jinja2Native)
        val tree = TemplateTree.parse(source, LexerJinjaTokenizer, env)
        val rendered = TemplateRenderer(
            LexerJinjaTokenizer, p.binder.scope(variant?.locals.orEmpty()), options, p.search.resolver(), p.search.loader(template),
            cancel = ProgressManager::checkCanceled,
        ).renderTemplate(tree, template.url)
        return PreviewReport(
            rendered.text, rendered, choices, chosen, headline(p.target, p.context, variant?.headline.orEmpty(), p.itemsUnknown, core, rendered), null,
            p.binder.secretSources.toMap(), p.binder.secretsShown,
        )
    }

    /**
     * The hosts that render [template], for the preview's context picker: every host of each rendering task's scope
     * before the selection narrows it (the plays that run the task, the plays that apply the role). Null when nothing
     * narrows the template to a set of hosts (a root-wide scope, or no host at all). Read action, smart mode.
     */
    fun renderingHosts(template: VirtualFile): List<HostKey>? {
        if (AnsibleWorkspace.getInstance(project).contextOf(template) == null) return null
        val contextService = AnsibleContextService.getInstance(project)
        val contexts = TemplateContextService.getInstance(project).renderContexts(template).take(MAX_CONTEXTS)
        val scopes = if (contexts.isEmpty()) listOf(contextService.hostScope(template))
        else contexts.map { contextService.hostScope(it.taskSite.file, it.taskSite.offset) }
        if (scopes.any { it.origin is HostScopeOrigin.RootWide || it.origin is HostScopeOrigin.Selection }) return null
        return scopes.flatMap { it.fileHosts }.distinct().ifEmpty { null }
    }

    /**
     * The variable [name] fully rendered for [target] (the card's "renders:" line): the value ansible-core would template
     * on that host, with facts, vault values and runtime results as placeholders. [at] is the file the card is about
     * (`template_*` magic variables); [roleName] the role whose defaults and vars apply. [text], the winner's scalar as
     * written, is rendered itself so placeholders sit inside the literal text around them; without it, [value] (the
     * winner's value, for a winner the model does not evaluate itself: a block or task var, an include var), else
     * `{{ name }}`. Read action, smart mode.
     */
    fun renderVariable(
        target: EvalTarget, scope: HostScope, name: String, roleName: String?, at: VirtualFile, text: String? = null, value: YValue? = null,
    ): Rendered {
        val root = scope.root
        val core = TargetVersionDetector.getInstance(project).targetVersion(root).version ?: CoreVersion.PINNED
        val cfg = cfgOf(root)
        val jinja2Native = cfg?.value("defaults", "jinja2_native")?.lowercase() in setOf("true", "yes", "1", "on")
        val roleDir = roleName?.let { RoleRegistry.getInstance(project).role(root, it)?.ref?.dir }
        val playbookDir = target.play?.playbookDir
        val contextService = AnsibleContextService.getInstance(project)
        val values = HostValueService.getInstance(project).values(target, roleName, emptyMap())
        val binder = RenderBinder(
            target, values, contextService.inventoryFacts(scope), at, roleName, roleDir, playbookDir, core, cfg?.value("defaults", "ansible_managed"),
        )
        val search = SearchPath(roleDir, playbookDir, listOfNotNull(root.dir, root.parentDir) + root.rolesDirs)
        // Template mode prints each hole where it sits; a YAML value with a hole would be one marker as a whole.
        val env = EnvOptions()
        val options = RenderOptions(RenderMode.TEMPLATE_FILE, core, env, jinja2Native = jinja2Native)
        val base = binder.scope()
        val renderScope = if (text != null || value == null) base else RenderScope { n -> if (n == WINNER) Binding.Yaml(value, name) else base.lookup(n) }
        val source = text ?: if (value != null) "{{ $WINNER }}" else "{{ $name }}"
        val tree = TemplateTree.parse(source, LexerJinjaTokenizer, env)
        return TemplateRenderer(LexerJinjaTokenizer, renderScope, options, search.resolver(), search.loader(at), cancel = ProgressManager::checkCanceled)
            .renderTemplate(tree, at.url)
    }

    /**
     * [text], the Jinja at [offset] of [file] (a decoded YAML scalar, or one `{{ }}`/`{% %}` span of a template), rendered
     * on up to [MAX_HOSTS] hosts of its scope and up to [MAX_ITEMS] items of its task's loop (and of the loops of the
     * include tasks that run its file, one outcome group per include path, an includer that does not set a name keeping
     * its error). An implicit expression (`when:`) renders to its value. In a template file, every rendering task
     * counts. Read action, smart mode.
     */
    fun renderValue(
        file: VirtualFile, offset: Int, text: String, expression: Boolean, loopApplies: Boolean = true, prelude: String = "", postlude: String = "",
    ): ValueReport? {
        val fileContext = AnsibleWorkspace.getInstance(project).contextOf(file) ?: return null
        val root = fileContext.root
        val core = TargetVersionDetector.getInstance(project).targetVersion(root).version ?: CoreVersion.PINNED
        val cfg = cfgOf(root)
        val jinja2Native = cfg?.value("defaults", "jinja2_native")?.lowercase() in setOf("true", "yes", "1", "on")
        val contextService = AnsibleContextService.getInstance(project)
        val source = prelude + (if (expression) "{{ ($text) }}" else text) + postlude
        val env = EnvOptions()
        val tree = TemplateTree.parse(source, LexerJinjaTokenizer, env)
        val template = isTemplate(file)

        // One origin per rendering task (or the task at the offset) and include path of its file: the includers' vars
        // and loops bind there too (a Molecule includer never in a production file, plan amendment R20).
        class Origin(
            val site: TaskSite?, val scope: HostScope, val roleName: String?, val roleDir: VirtualFile?, val fallbackPlaybookDir: VirtualFile?,
            val include: IncludeRuns.Variant?,
        )
        val origins = if (template) {
            TemplateContextService.getInstance(project).renderContexts(file).take(MAX_CONTEXTS).flatMap { context ->
                val site = TaskSite.of(project, context)
                val scope = contextService.hostScope(context.taskSite.file, context.taskSite.offset)
                val includes: List<IncludeRuns.Variant?> =
                    if (site == null) listOf(null) else IncludeRuns.variants(project, context.taskSite.file, MoleculeView.forAnalysis(project, context.taskSite.file))
                includes.map { include ->
                    Origin(site?.withInclude(include), scope, context.role?.name, context.role?.dir, context.taskSite.file.parent.takeIf { context.role == null }, include)
                }
            }.take(MAX_CONTEXTS).ifEmpty { listOf(Origin(null, contextService.hostScope(file), fileContext.roleName, fileContext.roleDir, null, null)) }
        } else {
            val site = TaskSite.at(project, file, offset)
            val includes: List<IncludeRuns.Variant?> = if (site == null) listOf(null) else IncludeRuns.variants(project, file, MoleculeView.forAnalysis(project, file))
            val scope = contextService.hostScope(file, offset)
            includes.map { include -> Origin(site?.withInclude(include), scope, fileContext.roleName, fileContext.roleDir, null, include) }
        }

        val outputs = LinkedHashMap<String, MutableList<String>>()
        val errors = HashMap<String, String>()
        val holes = HashMap<String, Int>()
        val hosts = LinkedHashSet<String>()
        var items: Int? = null
        var itemsUnknown: String? = null
        var noHost: String? = null
        for (origin in origins) {
            val targets: List<EvalTarget?> = origin.scope.targets.take(MAX_HOSTS).ifEmpty {
                noHost = origin.scope.emptyReason ?: AnsibilityRenderBundle.message("preview.headline.no.host")
                listOf(null)
            }
            for (target in targets) {
                ProgressManager.checkCanceled()
                val playbookDir = target?.play?.playbookDir ?: origin.fallbackPlaybookDir
                val values = target?.let { hostValues(it, origin.roleName, origin.site) }
                val facts = target?.let { contextService.inventoryFacts(origin.scope) }
                val managed = cfg?.value("defaults", "ansible_managed")
                val binder = RenderBinder(target, values, facts, file, origin.roleName, origin.roleDir, playbookDir, core, managed)
                val search = SearchPath(origin.roleDir, playbookDir, listOfNotNull(root.dir, root.parentDir) + root.rolesDirs)
                val options = RenderOptions(RenderMode.TEMPLATE_FILE, core, env, jinja2Native = jinja2Native)
                val valueOptions = options.copy(mode = RenderMode.YAML_VALUE)
                // The include tasks' loops apply to every key of the included task; [loopApplies] is about its own loop.
                val includerScopes = includerScopes(target, facts, file, core, managed, playbookDir, origin.include?.path)
                val includeRuns = origin.include?.let { IncludeRuns.runs(it, includerScopes, valueOptions, search.resolver(), MAX_ITEMS, ::hoverItem) }
                includeRuns?.unknown?.let { if (itemsUnknown == null) itemsUnknown = it }
                val host = target?.let { "${it.host.environment} › ${it.host.host}" }
                host?.let(hosts::add)
                var rendersLeft = MAX_ITEMS
                for (run in includeRuns?.runs ?: listOf(IncludeRuns.Run(emptyList(), emptyMap()))) {
                    if (rendersLeft <= 0) break
                    val loop = origin.site?.takeIf { loopApplies }?.let { site ->
                        LoopItems.evaluate(site, binder.scope(run.locals), valueOptions, search.resolver())
                    }
                    val runs: List<Pair<String?, Map<String, RValue>>> = when (loop) {
                        is LoopItems.Result.Items -> {
                            items = (includeRuns?.total ?: 1) * loop.total
                            loop.items.take(minOf(MAX_ITEMS, rendersLeft)).map { item -> hoverItem(item) to run.locals + item.locals }
                        }
                        is LoopItems.Result.Unknown -> {
                            itemsUnknown = loop.reason
                            includeRuns?.total?.let { items = it }
                            listOf(null to run.locals)
                        }
                        null -> {
                            includeRuns?.total?.let { items = it }
                            listOf(null to run.locals)
                        }
                    }
                    for ((item, locals) in runs) {
                        ProgressManager.checkCanceled()
                        rendersLeft--
                        val rendered = TemplateRenderer(
                            LexerJinjaTokenizer, binder.scope(locals), options, search.resolver(), search.loader(file), cancel = ProgressManager::checkCanceled,
                        ).renderTemplate(tree, file.url)
                        val who = (listOfNotNull(host, origin.include?.label) + run.labels + listOfNotNull(item)).joinToString(" · ").ifEmpty { root.displayName }
                        outputs.getOrPut(rendered.text) { ArrayList() } += who
                        rendered.errors.firstOrNull()?.let { errors.putIfAbsent(rendered.text, it.message) }
                        holes[rendered.text] = rendered.placeholders + rendered.unknownBranches
                    }
                }
            }
        }
        val outcomes = outputs.map { (text, who) -> ValueOutcome(text, who, errors[text], holes[text] ?: 0) }
        val tasks = if (template) origins.mapNotNull { it.site?.context?.taskSite }.distinct().size else 0
        return ValueReport(outcomes, hosts.size, items, itemsUnknown, core, noHost.takeIf { hosts.isEmpty() }, tasks)
    }

    /** The values a task of [roleName] sees on [target] at [site]: its own and its imports' `vars:` at level 15, its include params above role params. */
    private fun hostValues(target: EvalTarget, roleName: String?, site: TaskSite?): HostValues? =
        hostValues(target, roleName, site?.siteVars.orEmpty(), site?.includeParams.orEmpty())

    private fun hostValues(target: EvalTarget, roleName: String?, siteVars: Map<String, YValue>, includeParams: Map<String, YValue>): HostValues? {
        val service = HostValueService.getInstance(project)
        if (includeParams.isNotEmpty() && service is HostValueServiceImpl) return service.values(target, roleName, siteVars, includeParams)
        return service.values(target, roleName, siteVars)
    }

    /**
     * Where each includer of [path] evaluates its loop on [target] ([IncludeRuns.runs]): a binder in the includer's own
     * role (none for a play's `include_role`) over the includer's `vars:` (with its blocks') above the import vars of the
     * includes outside it, with their include params on top; never the included task's own `vars:`. One binder per
     * includer, then its scope over the given locals.
     */
    private fun includerScopes(
        target: EvalTarget?, facts: InventoryFacts?, file: VirtualFile, core: CoreVersion, managed: String?, fallbackPlaybookDir: VirtualFile?, path: IncludePath?,
    ): (Includer, Map<String, RValue>) -> RenderScope {
        val binders = HashMap<SourceLocation, RenderBinder>()
        return { includer, locals ->
            val binder = binders.getOrPut(includer.location) {
                val outside = path?.outside(includer)
                val siteVars = LinkedHashMap<String, YValue>()
                outside?.importVars()?.forEach { (name, variable) -> siteVars[name] = variable.value }
                for ((name, variable) in includer.vars) {
                    siteVars.remove(name)
                    siteVars[name] = variable.value
                }
                val params = outside?.params()?.mapValues { it.value.value }.orEmpty()
                val context = AnsibleWorkspace.getInstance(project).contextOf(includer.file)
                val playbookDir = target?.play?.playbookDir ?: fallbackPlaybookDir
                val values = target?.let { hostValues(it, context?.roleName, siteVars, params) }
                RenderBinder(target, values, facts, file, context?.roleName, context?.roleDir, playbookDir, core, managed)
            }
            binder.scope(locals)
        }
    }

    /** `item 2 (web)`: one loop item in a "who" list or a picker label. */
    @Nls
    private fun hoverItem(item: LoopItems.Item): String = AnsibilityRenderBundle.message("hover.item", item.index + 1, item.label)

    /** Whether the task's `src` for [item] (inside the includers' run that binds [runLocals]) names [template]; an unknown `src` keeps the item. */
    private fun rendersThis(
        site: TaskSite, item: LoopItems.Item, runLocals: Map<String, RValue>, binder: RenderBinder, options: RenderOptions, search: SearchPath, template: VirtualFile,
    ): Boolean {
        if (!site.isTemplateModule) return true
        val src = site.option("src") ?: return true
        val value = LoopItems.render(src, LoopItems.scopeWith(binder.scope(runLocals), item.locals), options, search.resolver()) as? RValue.Str ?: return true
        val file = search.template(value.value, null) ?: return false
        return file == template
    }

    @Nls
    private fun headline(target: EvalTarget?, context: RenderContext?, variant: List<String>, itemsUnknown: String?, core: CoreVersion, rendered: Rendered): String {
        val where = target?.let { "${it.host.environment} › ${it.host.host}" } ?: AnsibilityRenderBundle.message("preview.headline.no.host")
        val markers = rendered.placeholders + rendered.unknownBranches
        val parts = mutableListOf(AnsibilityRenderBundle.message("preview.headline.core", core.toString()), where)
        if (context == null) parts += AnsibilityRenderBundle.message("preview.headline.no.task")
        parts += variant
        itemsUnknown?.let { parts += AnsibilityRenderBundle.message("preview.headline.items.unknown", it) }
        parts += if (rendered.errors.isNotEmpty()) AnsibilityRenderBundle.message("preview.headline.errors", rendered.errors.size)
        else AnsibilityRenderBundle.message("preview.headline.placeholders", markers)
        return parts.joinToString(" · ")
    }

    private fun problem(@Nls text: String) = PreviewReport("", null, emptyList(), null, text, text)

    private fun cfgOf(root: AnsibleRoot): AnsibleCfg? {
        val file = root.dir.findChild("ansible.cfg") ?: root.parentDir?.findChild("ansible.cfg") ?: return null
        return try {
            AnsibleCfg.parse(VfsUtilCore.loadText(file))
        } catch (e: IOException) {
            null
        }
    }

    private fun unescapeNewline(text: String): String = text.replace("\\r", "\r").replace("\\n", "\n")

    companion object {
        private const val MAX_CONTEXTS = 20
        private const val MAX_HOSTS = 10
        private const val MAX_ITEMS = 10

        /** Include-loop runs the preview's picker offers per render context and include path. */
        private const val MAX_PREVIEW_RUNS = 100

        /** Choices one prepared context offers at most: include runs × own loop items stay within one loop's bound. */
        private const val MAX_PREVIEW_CHOICES = LoopItems.MAX_ITEMS

        /** The name the card's winner value is bound to when [renderVariable] renders it itself. */
        private const val WINNER = "__ansibility_winner__"

        fun getInstance(project: Project): TemplatePreviewService = project.service()
    }
}
