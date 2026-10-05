package de.terletzkiy.ansibility.render.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.EvalTarget
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.HostScope
import de.terletzkiy.ansibility.api.HostValueService
import de.terletzkiy.ansibility.api.RenderContext
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.api.TemplateContextService
import de.terletzkiy.ansibility.context.AnsibleCfg
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.render.AnsibilityRenderBundle
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

/**
 * Renders template files for the preview (F11.2): the render contexts of the template × the root's selected host
 * (the first target of the task's host scope) × the task's loop items whose `src` renders this file. Call in a read
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

        class Prepared(val context: RenderContext?, val site: TaskSite?, val scope: HostScope, val target: EvalTarget?, val binder: RenderBinder, val search: SearchPath, val items: LoopItems.Result?)

        fun prepare(context: RenderContext?): Prepared {
            ProgressManager.checkCanceled()
            val site = context?.let { TaskSite.of(project, it) }
            val scope = if (context != null) contextService.hostScope(context.taskSite.file, context.taskSite.offset) else contextService.hostScope(template)
            val target = scope.targets.firstOrNull()
            val roleName = context?.role?.name ?: fileContext.roleName
            val roleDir = context?.role?.dir ?: fileContext.roleDir
            val playbookDir = target?.play?.playbookDir ?: context?.takeIf { it.role == null }?.taskSite?.file?.parent
            val values = target?.let { HostValueService.getInstance(project).values(it, roleName, site?.siteVars.orEmpty()) }
            val binder = RenderBinder(
                target, values, target?.let { contextService.inventoryFacts(scope) }, template, roleName, roleDir, playbookDir, core,
                cfg?.value("defaults", "ansible_managed"), secrets,
            )
            val search = SearchPath(roleDir, playbookDir, listOfNotNull(root.dir, root.parentDir) + root.rolesDirs)
            val options = RenderOptions(RenderMode.YAML_VALUE, core, jinja2Native = jinja2Native)
            val items = site?.let { s ->
                when (val result = LoopItems.evaluate(s, binder.scope(), options, search.resolver())) {
                    is LoopItems.Result.Items -> LoopItems.Result.Items(result.items.filter { rendersThis(s, it, binder, options, search, template) }, result.total)
                    else -> result
                }
            }
            return Prepared(context, site, scope, target, binder, search, items)
        }

        val prepared = if (contexts.isEmpty()) listOf(prepare(null)) else contexts.map(::prepare)
        val choices = ArrayList<PreviewChoice>()
        prepared.forEachIndexed { i, p ->
            val index = if (p.context == null) -1 else i
            val task = p.site?.task?.name?.text ?: p.context?.let { "${it.taskSite.file.name}" } ?: AnsibilityRenderBundle.message("preview.choice.no.task")
            when (val items = p.items) {
                is LoopItems.Result.Items -> items.items.forEach { item ->
                    choices += PreviewChoice(PreviewPick(index, item.index), AnsibilityRenderBundle.message("preview.choice.item", task, item.index + 1, items.total, item.label))
                }
                else -> choices += PreviewChoice(PreviewPick(index, null), task)
            }
        }
        val chosen = choices.firstOrNull { it.pick == pick }?.pick ?: choices.firstOrNull()?.pick
        val p = prepared.firstOrNull { (if (it.context == null) -1 else prepared.indexOf(it)) == chosen?.context } ?: prepared.first()
        val item = (p.items as? LoopItems.Result.Items)?.items?.firstOrNull { it.index == chosen?.item }
        val env = EnvOptions(
            trimBlocks = p.site?.flag("trim_blocks") ?: true,
            lstripBlocks = p.site?.flag("lstrip_blocks") ?: false,
            newlineSequence = (p.site?.option("newline_sequence") as? YScalar)?.text?.let(::unescapeNewline) ?: "\n",
        )
        val options = RenderOptions(RenderMode.TEMPLATE_FILE, core, env, jinja2Native = jinja2Native)
        val tree = TemplateTree.parse(source, LexerJinjaTokenizer, env)
        val rendered = TemplateRenderer(
            LexerJinjaTokenizer, p.binder.scope(item?.locals.orEmpty()), options, p.search.resolver(), p.search.loader(template),
            cancel = ProgressManager::checkCanceled,
        ).renderTemplate(tree, template.url)
        return PreviewReport(
            rendered.text, rendered, choices, chosen, headline(p.target, p.context, p.items, item, core, rendered), null,
            p.binder.secretSources.toMap(), p.binder.secretsShown,
        )
    }

    /**
     * The variable [name] fully rendered for [target] (the card's "renders:" line): the value ansible-core would template
     * on that host, with facts, vault values and runtime results as placeholders. [at] is the file the card is about
     * (`template_*` magic variables); [roleName] the role whose defaults and vars apply. [text], the winner's scalar as
     * written, is rendered itself so placeholders sit inside the literal text around them; without it, `{{ name }}`.
     * Read action, smart mode.
     */
    fun renderVariable(target: EvalTarget, scope: HostScope, name: String, roleName: String?, at: VirtualFile, text: String? = null): Rendered {
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
        val tree = TemplateTree.parse(text ?: "{{ $name }}", LexerJinjaTokenizer, env)
        return TemplateRenderer(LexerJinjaTokenizer, binder.scope(), options, search.resolver(), search.loader(at), cancel = ProgressManager::checkCanceled)
            .renderTemplate(tree, at.url)
    }

    /** Whether the task's `src` for [item] names [template]; an unknown `src` keeps the item. */
    private fun rendersThis(site: TaskSite, item: LoopItems.Item, binder: RenderBinder, options: RenderOptions, search: SearchPath, template: VirtualFile): Boolean {
        if (!site.isTemplateModule) return true
        val src = site.option("src") ?: return true
        val value = LoopItems.render(src, LoopItems.scopeWith(binder.scope(), item.locals), options, search.resolver()) as? RValue.Str ?: return true
        val file = search.template(value.value, null) ?: return false
        return file == template
    }

    @Nls
    private fun headline(target: EvalTarget?, context: RenderContext?, items: LoopItems.Result?, item: LoopItems.Item?, core: CoreVersion, rendered: Rendered): String {
        val where = target?.let { "${it.host.environment} › ${it.host.host}" } ?: AnsibilityRenderBundle.message("preview.headline.no.host")
        val markers = rendered.placeholders + rendered.unknownBranches
        val parts = mutableListOf(AnsibilityRenderBundle.message("preview.headline.core", core.toString()), where)
        if (context == null) parts += AnsibilityRenderBundle.message("preview.headline.no.task")
        when (items) {
            is LoopItems.Result.Items -> if (item != null) parts += AnsibilityRenderBundle.message("preview.headline.item", item.index + 1, items.total)
            is LoopItems.Result.Unknown -> parts += AnsibilityRenderBundle.message("preview.headline.items.unknown", items.reason)
            null -> {}
        }
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

        fun getInstance(project: Project): TemplatePreviewService = project.service()
    }
}
