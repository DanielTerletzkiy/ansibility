package de.terletzkiy.ansibility.resolve.template

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.indexing.FileBasedIndex
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.RenderContext
import de.terletzkiy.ansibility.api.RenderKind
import de.terletzkiy.ansibility.api.RoleRef
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.TemplateContextService
import de.terletzkiy.ansibility.api.ValueShape
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.index.AnsibleIndexQueries
import de.terletzkiy.ansibility.index.PathFacts
import de.terletzkiy.ansibility.index.RenderEntry
import de.terletzkiy.ansibility.index.RootFamily
import de.terletzkiy.ansibility.index.SrcKind
import de.terletzkiy.ansibility.index.TemplateUseIndex
import de.terletzkiy.ansibility.model.task.TaskFileModels
import de.terletzkiy.ansibility.model.task.TaskItem
import de.terletzkiy.ansibility.model.task.TaskNode
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.resolve.loop.LoopItemTyper
import de.terletzkiy.ansibility.resolve.loop.MoleculePlatforms
import de.terletzkiy.ansibility.resolve.loop.TaskChains
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.vars.VarLocations
import de.terletzkiy.ansibility.vars.VaultInfo
import org.jetbrains.yaml.YAMLLanguage
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLScalar
import java.util.concurrent.ConcurrentHashMap

/**
 * The project's [TemplateContextService] (plan A.7, A.8 `renderContexts`, F2.3 text level): the tasks of a
 * template's root that render it, found through `ansible.template.use` ([AnsibleIndexQueries]) and resolved at query
 * time:
 * - **static** `src` and literal `lookup('template', …)` names the way `_find_needle` searches ([TemplateSearch]):
 *   `role/templates/<src>`, `role/<src>`, then relative to the task file;
 * - **dynamic prefix** (`templates/nginx/{{ item.template }}`): the `choices` of the variable path typed through
 *   [LoopItemTyper] (the loop item, or a spec'd variable), else the directory listing below the prefix;
 * - **whole variable** (`{{ var }}`): the variable's choices, spec default and literal string values in the root
 *   (never of a secret), through [TemplateSearch] or, when absolute, the container path mapping;
 * - **fileglob** loops: the glob, relative to `role_path` when the loop says so, else to the `files/` search paths;
 * - **`{% include %}`/`{% extends %}`** (and `import … with context`): the included template inherits every context of
 *   the including one, recorded in [RenderContext.via]; cycles are cut.
 *
 * Each context carries the rendering task's role (T3), its and its blocks' `vars:` keys plus
 * `template_vars` keys (T2), and its typed loop (T1). A template with no resolved context gets an empty list.
 *
 * Contexts are computed per template on demand and cached per root until the `ansible.template.use` index, YAML PSI,
 * the Ansible structure or the project roots change. Call in a read action in smart mode (a read lock is taken when
 * the caller holds none).
 */
class TemplateContextResolver(private val project: Project) : TemplateContextService {
    private val caches = ConcurrentHashMap<VirtualFile, CachedValue<RootRenders>>()

    private val indexStamp = ModificationTracker { FileBasedIndex.getInstance().getIndexModificationStamp(TemplateUseIndex.NAME, project) }

    override fun renderContexts(template: VirtualFile): List<RenderContext> = readLocked {
        if (!template.isValid || template.isDirectory) return@readLocked emptyList()
        val context = AnsibleWorkspace.getInstance(project).contextOf(template) ?: return@readLocked emptyList()
        // X77: Molecule's create playbook renders the scenario's Dockerfile templates once per building platform.
        MoleculePlatforms.renderContexts(project, template).takeIf { it.isNotEmpty() }?.let { return@readLocked it }
        rendersOf(context.root).contextsOf(template, emptySet())
    }

    private fun rendersOf(root: AnsibleRoot): RootRenders {
        if (caches.size > MAX_ROOTS) caches.clear()
        val cached = caches.computeIfAbsent(root.dir) {
            CachedValuesManager.getManager(project).createCachedValue(
                {
                    CachedValueProvider.Result.create(
                        RootRenders(root),
                        indexStamp,
                        PsiModificationTracker.getInstance(project).forLanguage(YAMLLanguage.INSTANCE),
                        AnsibleWorkspace.getInstance(project).structureTracker,
                        ProjectRootManager.getInstance(project),
                    )
                },
                false,
            )
        }
        return cached.value.takeIf { it.root == root } ?: RootRenders(root)
    }

    /** One render site: an `ansible.template.use` value with its literal key and file. */
    private class Site(val src: String, val entry: RenderEntry, val file: VirtualFile, val context: FileContext)

    /** The render sites of one root, their resolved targets and the contexts computed so far. */
    private inner class RootRenders(val root: AnsibleRoot) {
        private val sites: List<Site> by lazy { collectSites() }
        private val targets = ConcurrentHashMap<Site, List<VirtualFile>>()
        private val contexts = ConcurrentHashMap<VirtualFile, List<RenderContext>>()

        fun contextsOf(template: VirtualFile, visiting: Set<VirtualFile>): List<RenderContext> {
            contexts[template]?.let { return it }
            if (template in visiting) return emptyList()
            val computed = compute(template, visiting + template)
            if (visiting.isEmpty()) contexts.putIfAbsent(template, computed)
            return computed
        }

        private fun compute(template: VirtualFile, visiting: Set<VirtualFile>): List<RenderContext> {
            val result = ArrayList<RenderContext>()
            for (site in sites) {
                ProgressManager.checkCanceled()
                if (!mayRender(site, template)) continue
                if (template !in targets.getOrPut(site) { resolveTargets(site) }) continue
                result += contextsVia(site, template, visiting)
            }
            return result.distinct()
        }

        private fun collectSites(): List<Site> {
            val family = RootFamily.of(project, root)
            val result = ArrayList<Site>()
            for (src in AnsibleIndexQueries.renderedNames(project, root)) {
                ProgressManager.checkCanceled()
                for (hit in AnsibleIndexQueries.values(project, TemplateUseIndex.NAME, src, family)) result += Site(src, hit.value, hit.file, hit.context)
            }
            return result
        }

        // -------------------------------------------------------------------------------------------- targets

        /** A cheap test on the template's name before the site is resolved. */
        private fun mayRender(site: Site, template: VirtualFile): Boolean {
            val name = template.name
            return when (site.entry.srcKind) {
                SrcKind.STATIC, SrcKind.LOOKUP, SrcKind.INCLUDE -> site.src.substringAfterLast('/') == name
                SrcKind.DYNAMIC_PREFIX -> {
                    val prefix = staticPrefix(site.src).substringAfterLast('/')
                    val suffix = staticSuffix(site.src)
                    name.startsWith(prefix) && (if ('/' in suffix) name == suffix.substringAfterLast('/') else name.endsWith(suffix))
                }
                SrcKind.WHOLE_VAR -> true
                SrcKind.FILEGLOB -> site.entry.globPattern?.let { TemplateSearch.globRegex(it.substringAfterLast('/')).matches(name) } ?: false
            }
        }

        private fun resolveTargets(site: Site): List<VirtualFile> = when (site.entry.srcKind) {
            SrcKind.STATIC, SrcKind.LOOKUP -> byName(site, site.src)
            SrcKind.INCLUDE -> includeTargets(site)
            SrcKind.DYNAMIC_PREFIX -> dynamicTargets(site)
            SrcKind.WHOLE_VAR -> wholeVarTargets(site)
            SrcKind.FILEGLOB -> fileglobTargets(site)
        }

        /** A literal name from a task (or, for lookups in templates, from the template's role). */
        private fun byName(site: Site, name: String): List<VirtualFile> =
            if (isTemplateSite(site)) includeCandidates(site, name) else TemplateSearch.resolve(project, name, site.file, site.context)

        /** `{% include 'x' %}`: the role's `templates/`, the role, then the including template's directory. */
        private fun includeTargets(site: Site): List<VirtualFile> = includeCandidates(site, site.src)

        private fun includeCandidates(site: Site, name: String): List<VirtualFile> {
            val paths = listOfNotNull(site.context.roleDir, site.file.parent).distinct()
            return TemplateSearch.candidates(name.trim(), paths, TemplateSearch.TEMPLATES).take(1)
        }

        private fun dynamicTargets(site: Site): List<VirtualFile> {
            val prefix = staticPrefix(site.src)
            val suffix = staticSuffix(site.src)
            val choices = choicesOf(site)
            if (choices.isNotEmpty() && singleExpression(site.src)) {
                return choices.flatMap { byName(site, prefix + it + suffix) }.distinct()
            }
            val dir = prefix.substringBeforeLast('/', "")
            val filePrefix = prefix.substringAfterLast('/')
            val paths = if (isTemplateSite(site)) listOfNotNull(site.context.roleDir, site.file.parent) else TemplateSearch.searchPaths(site.file, site.context)
            return TemplateSearch.directories(dir, paths, TemplateSearch.TEMPLATES)
                .flatMap { TemplateSearch.listing(it, filePrefix, suffix.substringAfterLast('/')) }
                .distinct()
        }

        private fun wholeVarTargets(site: Site): List<VirtualFile> =
            valuesOf(site).flatMap { byName(site, it) }.distinct()

        private fun fileglobTargets(site: Site): List<VirtualFile> {
            val glob = site.entry.globPattern ?: return emptyList()
            val dir = glob.substringBeforeLast('/', "")
            val name = TemplateSearch.globRegex(glob.substringAfterLast('/'))
            val loop = site.entry.loopExprText.orEmpty()
            val dirs = if (glob.startsWith("/") && "role_path" in loop) {
                listOfNotNull(site.context.roleDir?.findFileByRelativePath(dir.trimStart('/'))?.takeIf { it.isDirectory })
            } else if (!glob.startsWith("/")) {
                TemplateSearch.directories(dir, TemplateSearch.searchPaths(site.file, site.context), TemplateSearch.FILES)
            } else {
                emptyList()
            }
            return dirs.flatMap { directory -> directory.children.orEmpty().filter { !it.isDirectory && name.matches(it.name) } }
        }

        /** The string choices of the first expression's variable path of a dynamic or whole-variable `src`. */
        private fun choicesOf(site: Site): List<String> = optionOf(site)?.choices?.values.orEmpty().mapNotNull { (it as? YScalar)?.text }

        /** Choices, the spec default and the root's literal string values of a whole-variable `src`. */
        private fun valuesOf(site: Site): List<String> {
            val option = optionOf(site)
            val values = LinkedHashSet<String>()
            option?.choices?.values?.forEach { (it as? YScalar)?.text?.let(values::add) }
            (option?.default as? YScalar)?.text?.let(values::add)
            val path = site.entry.dynamicVarPath
            val name = path.firstOrNull() ?: return values.toList()
            if (path.size == 1 && name != site.entry.loopVar) {
                for (definition in VarService.getInstance(project).symbol(root, name).definitions) {
                    ProgressManager.checkCanceled()
                    if (definition.valueShape != ValueShape.LITERAL || definition.literalType != "str") continue
                    if (VaultInfo.isSecret(name, definition.location.file)) continue
                    val keyValue = VarLocations.keyValueAt(project, definition.location) ?: continue
                    if (VaultInfo.isVaultValue(keyValue)) continue
                    (keyValue.value as? YAMLScalar)?.textValue?.let(values::add)
                }
            }
            return values.filter { it.isNotBlank() && !it.contains("{{") }
        }

        /** The option of the site's first variable path: the loop item's path, or a variable's (spec or literal). */
        private fun optionOf(site: Site): OptionSpec? {
            val path = site.entry.dynamicVarPath
            val name = path.firstOrNull() ?: return null
            val task = taskAt(site) ?: return null
            val (yaml, chain, node) = task
            if (name == site.entry.loopVar && node != null) {
                return LoopItemTyper.typeOf(project, yaml, node, chain)?.typeOfPath(name, path.drop(1))
            }
            return LoopItemTyper.variableType(project, site.context, chain, name, path.drop(1))
        }

        // -------------------------------------------------------------------------------------------- contexts

        private fun contextsVia(site: Site, template: VirtualFile, visiting: Set<VirtualFile>): List<RenderContext> {
            val kind = kindOf(site.entry.srcKind)
            if (isTemplateSite(site)) {
                if (site.entry.srcKind == SrcKind.INCLUDE && !passesContext(site)) return emptyList()
                val templateVars = templateVarKeys(textOf(site.file), site.entry.srcOffset)
                val outer = contextsOf(site.file, visiting)
                val location = SourceLocation(site.file, site.entry.taskOffset)
                if (outer.isEmpty()) {
                    return listOf(RenderContext(template, kind, location, location, roleOf(site.context), null, templateVars, listOf(site.file)))
                }
                return outer.map { it.copy(template = template, kind = kind, site = location, taskVars = it.taskVars + templateVars, via = it.via + site.file) }
            }
            val (yaml, chain, task) = taskAt(site) ?: return emptyList()
            val loop = task?.let { LoopItemTyper.typeOf(project, yaml, it, chain) }?.loop
            val vars = chain.flatMap { item -> item.vars.map { it.text } } +
                if (site.entry.srcKind == SrcKind.LOOKUP) templateVarKeys(yaml.viewProvider.contents, site.entry.srcOffset) else emptyList()
            val location = SourceLocation(site.file, (task ?: chain.lastOrNull())?.range?.startOffset ?: site.entry.taskOffset)
            return listOf(RenderContext(template, kind, location, location, roleOf(site.context), loop, vars.distinct(), emptyList()))
        }

        private fun roleOf(context: FileContext): RoleRef? {
            val name = context.roleName ?: return null
            val dir = context.roleDir ?: return null
            return RoleRef(root.dir, name, dir)
        }

        /** The task (with its enclosing blocks) of a YAML render site. */
        private fun taskAt(site: Site): Triple<YAMLFile, List<TaskItem>, TaskNode?>? {
            if (isTemplateSite(site)) return null
            val yaml = YamlFiles.yamlFile(project, site.file) ?: return null
            val chain = TaskChains.chainAt(TaskFileModels.of(yaml), site.entry.taskOffset)
            return Triple(yaml, chain, TaskChains.taskOf(chain))
        }

        /** `include` and `extends` pass the context; `import`/`from` only `with context`; `without context` never. */
        private fun passesContext(site: Site): Boolean {
            val text = textOf(site.file)
            val start = site.entry.taskOffset.coerceIn(0, text.length)
            val end = text.indexOf("%}", start).let { if (it < 0) text.length else it }
            val tag = text.subSequence(start, end).toString()
            val keyword = TAG_KEYWORD.find(tag)?.groupValues?.get(1) ?: return false
            return when {
                "without context" in tag -> false
                keyword == "include" || keyword == "extends" -> true
                else -> "with context" in tag
            }
        }

        private fun isTemplateSite(site: Site): Boolean = site.entry.srcKind == SrcKind.INCLUDE || !isYamlTaskFile(site)

        private fun isYamlTaskFile(site: Site): Boolean = site.context.kind !in TEMPLATE_KINDS && !PathFacts.isJ2(site.file.name)
    }

    private fun kindOf(kind: SrcKind): RenderKind = when (kind) {
        SrcKind.STATIC -> RenderKind.STATIC
        SrcKind.DYNAMIC_PREFIX -> RenderKind.DYNAMIC_PREFIX
        SrcKind.WHOLE_VAR -> RenderKind.WHOLE_VAR
        SrcKind.FILEGLOB -> RenderKind.FILEGLOB
        SrcKind.LOOKUP -> RenderKind.LOOKUP
        SrcKind.INCLUDE -> RenderKind.INCLUDE
    }

    private fun textOf(file: VirtualFile): CharSequence = PsiManager.getInstance(project).findFile(file)?.viewProvider?.contents ?: ""

    private fun <T> readLocked(action: () -> T): T =
        if (ApplicationManager.getApplication().isReadAccessAllowed) action() else runReadActionBlocking(action)

    companion object {
        private const val MAX_ROOTS = 64
        private val TEMPLATE_KINDS = setOf(FileKind.ROLE_TEMPLATE)
        private val TAG_KEYWORD = Regex("""^\{%[-+]?\s*(include|extends|import|from)\b""")
        private val TEMPLATE_VARS = Regex("""template_vars\s*=\s*""")
        private val DICT_CALL_KEY = Regex("""([A-Za-z_]\w*)\s*=(?!=)""")
        private val MAPPING_KEY = Regex("""['"]([A-Za-z_]\w*)['"]\s*:""")

        /** This project's resolver, when it is this implementation. */
        fun getInstance(project: Project): TemplateContextResolver? = TemplateContextService.getInstance(project) as? TemplateContextResolver

        /** The text of a dynamic `src` before its first Jinja delimiter. */
        fun staticPrefix(src: String): String {
            val index = listOf(src.indexOf("{{"), src.indexOf("{%")).filter { it >= 0 }.minOrNull() ?: return src
            return src.substring(0, index)
        }

        /** The literal text after the last `}}` of a dynamic `src` (empty when more Jinja follows or none). */
        fun staticSuffix(src: String): String {
            val end = src.lastIndexOf("}}")
            if (end < 0) return ""
            val suffix = src.substring(end + 2)
            return if (suffix.contains("{{") || suffix.contains("{%")) "" else suffix
        }

        /** True when a `src` holds exactly one `{{ … }}` and no statement. */
        fun singleExpression(src: String): Boolean {
            val first = src.indexOf("{{")
            return first >= 0 && src.indexOf("{{", first + 2) < 0 && !src.contains("{%")
        }

        /**
         * The keys of a `template_vars=dict(a=…, b=…)` or `template_vars={'a': …}` argument of the lookup call whose
         * template name literal starts at [from] in [text]; only keys at the first nesting level of that argument.
         */
        fun templateVarKeys(text: CharSequence, from: Int): List<String> {
            val start = from.coerceIn(0, text.length)
            val raw = text.subSequence(start, minOf(text.length, start + MAX_LOOKUP_TEXT)).toString()
            // Start after the name literal's closing quote; YAML double-quoted scalars escape the quotes.
            val afterName = raw.indexOfFirst { it == '\'' || it == '"' } + 1
            if (afterName <= 0) return emptyList()
            val window = raw.substring(afterName).replace("\\\"", "\"").replace("\\'", "'")
            val callEnd = endOfCall(window)
            val match = TEMPLATE_VARS.find(window.substring(0, callEnd)) ?: return emptyList()
            var i = match.range.last + 1
            val (bodyStart, pattern) = when {
                window.getOrNull(i) == '{' -> i + 1 to MAPPING_KEY
                window.startsWith("dict(", i) -> i + 5 to DICT_CALL_KEY
                else -> return emptyList()
            }
            val top = StringBuilder()
            var depth = 1
            var quote: Char? = null
            i = bodyStart
            while (i < callEnd && depth > 0) {
                val c = window[i]
                if (quote != null) {
                    if (c == quote && window[i - 1] != '\\') quote = null
                    if (depth == 1) top.append(c)
                } else {
                    when (c) {
                        '\'', '"' -> {
                            quote = c
                            if (depth == 1) top.append(c)
                        }
                        '(', '[', '{' -> depth++
                        ')', ']', '}' -> depth--
                        else -> if (depth == 1) top.append(c)
                    }
                }
                i++
            }
            return pattern.findAll(top).map { it.groupValues[1] }.distinct().toList()
        }

        /** The index of the bracket that closes the call [window] starts inside (its length when none does). */
        private fun endOfCall(window: String): Int {
            var depth = 0
            var quote: Char? = null
            for (i in window.indices) {
                val c = window[i]
                when {
                    quote != null -> if (c == quote && window[i - 1] != '\\') quote = null
                    c == '\'' || c == '"' -> quote = c
                    c == '(' || c == '[' || c == '{' -> depth++
                    c == ')' || c == ']' || c == '}' -> if (depth == 0) return i else depth--
                }
            }
            return window.length
        }

        private const val MAX_LOOKUP_TEXT = 2000
    }
}
