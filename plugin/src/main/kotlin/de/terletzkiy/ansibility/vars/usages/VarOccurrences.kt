package de.terletzkiy.ansibility.vars.usages

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.JinjaContainer
import de.terletzkiy.ansibility.api.ProjectLayoutService
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.TemplateContextService
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarDefinition
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.index.RootFamily
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaIndirection
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaLocal
import de.terletzkiy.ansibility.model.task.NameRef
import de.terletzkiy.ansibility.model.task.TaskFileModels
import de.terletzkiy.ansibility.model.task.TaskNode
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.resolve.InlineInventoryDefinitions
import de.terletzkiy.ansibility.resolve.VarDefinitions
import de.terletzkiy.ansibility.resolve.VarUsage
import de.terletzkiy.ansibility.resolve.VarUsageQuery
import de.terletzkiy.ansibility.resolve.loop.LoopItemTyper
import de.terletzkiy.ansibility.resolve.loop.TaskChains
import de.terletzkiy.ansibility.vars.JinjaTextSites
import de.terletzkiy.ansibility.vars.VarLocations
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLMapping

/** One place a variable is written ([write]) or read, as a range of a host file. */
internal class VarOccurrence(
    val file: VirtualFile,
    val range: TextRange,
    val write: Boolean,
    /** The definition written there, for definitions of root variables (vault safety needs its value shape). */
    val definition: VarDefinition? = null,
    /** Where a read sits; null for writes. */
    val container: JinjaContainer? = null,
    /** The usage-type group when it is known without the PSI around the occurrence; null for templated YAML reads. */
    val kind: VarUsageKind? = null,
)

/**
 * The occurrences of a [VarSymbolElement] (plan amendment FU, F1.10), from the indexes: the definitions of
 * [VarService] (writes; argument spec options as declarations) and the Jinja uses of [VarUsageQuery] (reads, called
 * globals dropped), scoped by the symbol's [VarScope]:
 * - **root variables**: the root's family, plus, for a name the project root's `environments/` defines, the nested
 *   playbook roots that share those inventories (`danger_zone/database`); reads by name (`hostvars[h].x`, `vars['x']`)
 *   included, in groups of their own;
 * - **loop variables**: uses inside the looping tasks (not in their own loop expression) and in the templates those
 *   tasks render, plus every use in [VarScope.Loop.file] (the template the search started in, whoever renders it, or
 *   the file of an `item` no loop binds), never a `hostvars[h]` member; the write is the `loop_var`/`index_var`
 *   value, or the loop keyword for `item` and `ansible_loop`;
 * - **Jinja locals**: the binding and the references that resolve to it, in its file only (no index).
 *
 * Call in a read action in smart mode.
 */
internal object VarOccurrences {
    /** Definition kinds an inventory under `environments/` writes. */
    private val INVENTORY_KINDS = setOf(VarDefKind.GROUP_VARS, VarDefKind.HOST_VARS, VarDefKind.INVENTORY_INLINE)

    /** Every occurrence of [symbol] inside [scope] (null: the whole scope of the symbol), in file and offset order. */
    fun of(project: Project, symbol: VarSymbolElement, scope: GlobalSearchScope?): List<VarOccurrence> {
        val found = when (val symbolScope = symbol.scope) {
            is VarScope.Root -> root(project, symbol.root, symbol.name, scope)
            is VarScope.Member -> member(project, symbol.root, symbol.name, symbolScope.path, scope, null)
            is VarScope.Loop -> loop(project, symbol.root, symbol.name, symbolScope, scope, null)
            is VarScope.Local -> if (scope == null || scope.contains(symbolScope.file)) local(project, symbolScope, symbol.name) else emptyList()
        }
        return ordered(found)
    }

    /**
     * The occurrences of [symbol] in [file] only (caret highlighting, on every caret move and keystroke): from the
     * file's own entries as its indexers compute them ([OpenFileIndexData]), never from the indexes.
     */
    fun inFile(project: Project, symbol: VarSymbolElement, file: VirtualFile): List<VarOccurrence> {
        val found = when (val symbolScope = symbol.scope) {
            is VarScope.Root -> definitionsIn(project, symbol.root, file, symbol.name) + fileUses(project, symbol.root, file, symbol.name).mapNotNull(::read)
            is VarScope.Member -> member(project, symbol.root, symbol.name, symbolScope.path, null, file)
            is VarScope.Loop -> loop(project, symbol.root, symbol.name, symbolScope, null, file)
            is VarScope.Local -> if (symbolScope.file == file) local(project, symbolScope, symbol.name) else emptyList()
        }
        return ordered(found)
    }

    /**
     * The primary declaration of [symbol], where navigating the search target goes: for a root variable the spec
     * option, then the role default of the role the search started in, then of any role (path order), then the first
     * definition; the loop variable's binding; the local's binding.
     */
    fun primaryDeclaration(project: Project, symbol: VarSymbolElement): SourceLocation? = when (val scope = symbol.scope) {
        is VarScope.Root -> {
            val definitions = VarService.getInstance(project).symbol(symbol.root, symbol.name).definitions.filter { it.kind != VarDefKind.JINJA_LOCAL }
            val ownRole = scope.home?.let { AnsibleWorkspace.getInstance(project).contextOf(it)?.roleName }
            val ranked = definitions.sortedBy { definition ->
                val declaration = when (definition.kind) {
                    VarDefKind.SPEC_OPTION -> 0
                    VarDefKind.ROLE_DEFAULT -> 1
                    else -> 2
                }
                if (ownRole != null && definition.roleName == ownRole && declaration < 2) declaration else declaration + 2
            }
            ranked.firstOrNull()?.location
        }
        is VarScope.Loop -> scope.tasks.firstNotNullOfOrNull { location ->
            loopTask(project, location)?.let { loopWrite(it, symbol.name) }?.let { SourceLocation(it.file, it.range.startOffset) }
        } ?: scope.file?.let { file -> loopReads(VarUsageQuery.getInstance(project).usagesIn(symbol.root, file, symbol.name)).firstOrNull()?.location }
        is VarScope.Member -> memberKeys(project, symbol.root, symbol.name, scope.path, null, null).firstOrNull()?.let { SourceLocation(it.file, it.range.startOffset) }
        is VarScope.Local -> SourceLocation(scope.file, scope.binding)
    }

    // -------------------------------------------------------------------------------------------- members

    /**
     * The occurrences of the member [path] of [name]: its key in every definition that writes it, and the reads whose
     * constant accessors start with [path] (direct reads only; by-name reads have no member path). [file] limits the
     * search to one file (caret highlighting).
     */
    private fun member(project: Project, root: AnsibleRoot, name: String, path: List<String>, scope: GlobalSearchScope?, file: VirtualFile?): List<VarOccurrence> {
        val result = ArrayList(memberKeys(project, root, name, path, scope, file))
        val uses = if (file != null) {
            fileUses(project, root, file, name)
        } else {
            val query = VarUsageQuery.getInstance(project)
            val found = ArrayList<VarUsage>()
            val definitions = VarService.getInstance(project).symbol(root, name).definitions
            for (member in listOf(root) + reach(project, root, definitions)) query.process(member, name, scope) { found += it; true }
            found
        }
        for (use in uses) {
            ProgressManager.checkCanceled()
            if (use.called || use.indirect != null || use.attrPath.size < path.size || use.attrPath.subList(0, path.size) != path) continue
            result += VarOccurrence(use.location.file, memberRange(use, path), write = false, container = use.container,
                kind = if (use.container == JinjaContainer.TEMPLATE_FILE) VarUsageKind.READ_TEMPLATE else null)
        }
        return result
    }

    /** The keys of member [path] in the definitions of [name] (in [file] only when it is set). */
    private fun memberKeys(project: Project, root: AnsibleRoot, name: String, path: List<String>, scope: GlobalSearchScope?, file: VirtualFile?): List<VarOccurrence> {
        val result = ArrayList<VarOccurrence>()
        for (definition in VarService.getInstance(project).symbol(root, name).definitions) {
            ProgressManager.checkCanceled()
            val location = definition.location
            if (file != null && location.file != file || scope != null && !scope.contains(location.file)) continue
            var keyValue = VarLocations.keyValueAt(project, location) ?: continue
            for (step in path) keyValue = (keyValue.value as? YAMLMapping)?.getKeyValueByKey(step) ?: break
            if (keyValue.keyText != path.last()) continue
            val key = keyValue.key ?: continue
            val quoted = key.textLength >= 2 && key.text.first() in QUOTES && key.text.last() == key.text.first()
            val range = if (quoted) TextRange(key.textRange.startOffset + 1, key.textRange.endOffset - 1) else key.textRange
            result += VarOccurrence(location.file, range, write = true, definition = definition, kind = VarUsageKind.of(definition))
        }
        return result
    }

    /** The range of the last member name of [path] after the read's root name, else the root name's range. */
    private fun memberRange(use: VarUsage, path: List<String>): TextRange {
        val text = FileDocumentManager.getInstance().getDocument(use.location.file)?.charsSequence ?: return use.range
        val from = use.range.endOffset
        val window = text.subSequence(from, minOf(text.length, from + MEMBER_WINDOW)).toString()
        var at = 0
        var found = -1
        for (step in path) {
            found = window.indexOf(step, at).takeIf { it >= 0 } ?: return use.range
            at = found + step.length
        }
        return TextRange(from + found, from + found + path.last().length)
    }

    private const val MEMBER_WINDOW = 400
    private val QUOTES = setOf('\'', '"')

    // -------------------------------------------------------------------------------------------- root variables

    private fun root(project: Project, root: AnsibleRoot, name: String, scope: GlobalSearchScope?): List<VarOccurrence> {
        val result = ArrayList<VarOccurrence>()
        val definitions = VarService.getInstance(project).symbol(root, name).definitions
        for (definition in definitions) {
            ProgressManager.checkCanceled()
            if (definition.kind == VarDefKind.JINJA_LOCAL) continue
            val file = definition.location.file
            if (scope != null && !scope.contains(file)) continue
            val range = definitionRange(project, definition.location, name) ?: continue
            result += VarOccurrence(file, range, write = true, definition = definition, kind = VarUsageKind.of(definition))
        }
        val query = VarUsageQuery.getInstance(project)
        val uses = ArrayList<VarUsage>()
        query.process(root, name, scope) { uses += it; true }
        for (nested in reach(project, root, definitions)) query.process(nested, name, scope) { uses += it; true }
        uses.mapNotNullTo(result, ::read)
        return result
    }

    /**
     * The nested playbook roots that read [root]'s inventories (F1.10 "nested-root reach"): when [root] is a project
     * root whose `environments/` defines the name, every nested playbook root inside it that shares those inventories.
     */
    private fun reach(project: Project, root: AnsibleRoot, definitions: List<VarDefinition>): List<AnsibleRoot> {
        if (root.kind != RootKind.PROJECT) return emptyList()
        val layout = ProjectLayoutService.getInstance(project).layout(root)
        val inventoryDirs = listOfNotNull(root.environmentsDir) + layout.inventories.flatMap { it.varsDirs }
        if (inventoryDirs.isEmpty()) return emptyList()
        val inventoryDefined = definitions.any { d ->
            d.kind in INVENTORY_KINDS && inventoryDirs.any { VfsUtilCore.isAncestor(it, d.location.file, false) }
        }
        if (!inventoryDefined) return emptyList()
        return AnsibleWorkspace.getInstance(project).roots().filter {
            it.kind == RootKind.NESTED_PLAYBOOK && it.parentDir == root.dir && it.detached == root.detached &&
                (root.environmentsDir == null || it.environmentsDir == root.environmentsDir)
        }
    }

    /** The definitions of [name] written in [file], from the file's own `ansible.var.def` entries ([OpenFileIndexData]). */
    private fun definitionsIn(project: Project, root: AnsibleRoot, file: VirtualFile, name: String): List<VarOccurrence> {
        val workspace = AnsibleWorkspace.getInstance(project)
        val context = workspace.contextOf(file) ?: return emptyList()
        if (!RootFamily.of(project, root, workspace).admits(file, context)) return emptyList()
        if (context.kind == FileKind.INVENTORY_INI) {
            return InlineInventoryDefinitions.of(project, root, name).filter { it.location.file == file }.mapNotNull { definition ->
                definitionRange(project, definition.location, name)?.let { VarOccurrence(file, it, write = true) }
            }
        }
        val entries = OpenFileIndexData.of(project, file).definitions[name].orEmpty()
        return entries.mapNotNull { entry ->
            ProgressManager.checkCanceled()
            val (kind, _) = VarDefinitions.classify(entry, context) ?: return@mapNotNull null
            if (kind == VarDefKind.JINJA_LOCAL) return@mapNotNull null
            definitionRange(project, SourceLocation(file, entry.offset), name)?.let { VarOccurrence(file, it, write = true) }
        }
    }

    /** The uses of [name] in [file] from the file's own `ansible.var.use` entries ([OpenFileIndexData]). */
    fun fileUses(project: Project, root: AnsibleRoot, file: VirtualFile, name: String): List<VarUsage> =
        VarUsageQuery.getInstance(project).usagesIn(root, file, name, OpenFileIndexData.of(project, file).uses[name].orEmpty())

    /**
     * The read [usage] is, or null for a called global (`lookup(…)`). A read by name (FU2) has its own group whatever
     * holds it: `Read: through hostvars` for some host's variable, `Read: by name (vars)` for `vars['x']`.
     */
    private fun read(usage: VarUsage): VarOccurrence? {
        if (usage.called) return null
        val kind = when {
            usage.indirect != null -> VarUsageKind.read(usage.indirect)
            usage.container == JinjaContainer.TEMPLATE_FILE -> VarUsageKind.READ_TEMPLATE
            else -> null
        }
        return VarOccurrence(usage.location.file, usage.range, write = false, container = usage.container, kind = kind)
    }

    /**
     * The uses that can read a loop variable: not a member of `hostvars[h]`, which is some host's variable even when
     * it is called `item` (`vars['item']` is the loop's).
     */
    private fun loopReads(uses: List<VarUsage>): List<VarUsage> = uses.filter { it.indirect != JinjaIndirection.HOSTVARS }

    /** The range of the name written at [location]: a key's text, else the scalar or leaf starting there. */
    private fun definitionRange(project: Project, location: SourceLocation, name: String): TextRange? {
        VarLocations.keyValueAt(project, location)?.key?.let { return it.textRange }
        val element = VarLocations.elementAt(project, location) ?: return null
        val range = element.textRange ?: return null
        val end = minOf(range.endOffset, location.offset + name.length)
        return if (end > location.offset) TextRange(location.offset, end) else null
    }

    // -------------------------------------------------------------------------------------------- loop variables

    /** A looping task: its file, its YAML model and the task. */
    private class LoopTask(val file: VirtualFile, val yaml: YAMLFile, val task: TaskNode) {
        fun contains(location: SourceLocation): Boolean = location.file == file && task.range.containsOffset(location.offset)

        /** True inside the task but outside its own loop expression, where the loop variable is not defined yet. */
        fun bodyContains(location: SourceLocation): Boolean {
            if (!contains(location)) return false
            val loopValue = task.loop?.value?.range ?: return true
            return location.offset < loopValue.start || location.offset > loopValue.end
        }
    }

    /**
     * The occurrences of loop variable [name] of [scope]'s tasks and of [VarScope.Loop.file]; with [onlyIn], those of
     * one file (from that file's own entries only, [fileUses]).
     */
    private fun loop(
        project: Project,
        root: AnsibleRoot,
        name: String,
        scope: VarScope.Loop,
        searchScope: GlobalSearchScope?,
        onlyIn: VirtualFile?,
    ): List<VarOccurrence> {
        val tasks = scope.tasks.mapNotNull { loopTask(project, it) }
        val query = VarUsageQuery.getInstance(project)
        if (tasks.isEmpty()) {
            // No task loop (a molecule platform loop, an unbound `item`): the uses in the file the search started in.
            val file = scope.file ?: return emptyList()
            if (onlyIn != null && onlyIn != file || searchScope != null && !searchScope.contains(file)) return emptyList()
            val uses = if (onlyIn != null) fileUses(project, root, file, name) else query.usagesIn(root, file, name)
            return loopReads(uses).mapNotNull(::read)
        }
        val result = ArrayList<VarOccurrence>()
        for (task in tasks) {
            if (onlyIn != null && task.file != onlyIn) continue
            if (searchScope != null && !searchScope.contains(task.file)) continue
            loopWrite(task, name)?.let(result::add)
        }
        val uses = if (onlyIn != null) fileUses(project, root, onlyIn, name) else ArrayList<VarUsage>().also { list -> query.process(root, name, searchScope) { list += it; true } }
        val rendered = HashMap<VirtualFile, Boolean>()
        for (use in loopReads(uses)) {
            ProgressManager.checkCanceled()
            val inLoop = use.location.file == scope.file || tasks.any { it.bodyContains(use.location) } ||
                rendered.getOrPut(use.location.file) { rendersWithLoop(project, use.location.file, tasks, name) }
            if (inLoop) read(use)?.let(result::add)
        }
        return result
    }

    private fun loopTask(project: Project, location: SourceLocation): LoopTask? {
        val yaml = YamlFiles.yamlFile(project, location.file) ?: return null
        val task = TaskChains.taskOf(TaskChains.chainAt(TaskFileModels.of(yaml), location.offset)) ?: return null
        return LoopTask(location.file, yaml, task)
    }

    /** Where [task] binds [name]: its `loop_var`/`index_var` value, else (`item`, `ansible_loop`) its loop keyword. */
    private fun loopWrite(task: LoopTask, name: String): VarOccurrence? {
        val control = task.task.loopControl
        control?.loopVar?.takeIf { it.text == name }?.let { return nameOccurrence(task, it, VarUsageKind.SET_LOOP_VAR) }
        control?.indexVar?.takeIf { it.text == name }?.let { return nameOccurrence(task, it, VarUsageKind.SET_INDEX_VAR) }
        val key = task.task.loop?.key?.range ?: return null
        return VarOccurrence(task.file, TextRange(key.start, key.end), write = true, kind = VarUsageKind.SET_LOOP)
    }

    /** The name inside the scalar of [ref] (quotes excluded). */
    private fun nameOccurrence(task: LoopTask, ref: NameRef, kind: VarUsageKind): VarOccurrence {
        val text = task.yaml.viewProvider.contents
        val inScalar = text.subSequence(ref.range.startOffset, ref.range.endOffset).indexOf(ref.text)
        val start = ref.range.startOffset + inScalar.coerceAtLeast(0)
        val range = if (inScalar >= 0) TextRange(start, start + ref.text.length) else ref.range
        return VarOccurrence(task.file, range, write = true, kind = kind)
    }

    /** True when [file] is a template that one of [tasks] renders with a loop defining [name]. */
    private fun rendersWithLoop(project: Project, file: VirtualFile, tasks: List<LoopTask>, name: String): Boolean {
        val context = AnsibleWorkspace.getInstance(project).contextOf(file) ?: return false
        if (!JinjaTextSites.isTemplateFile(file, context)) return false
        return TemplateContextService.getInstance(project).renderContexts(file).any { render ->
            ProgressManager.checkCanceled()
            val loop = render.loop ?: return@any false
            name in LoopItemTyper.namesOf(loop) && tasks.any { it.contains(render.taskSite) }
        }
    }

    // -------------------------------------------------------------------------------------------- Jinja locals

    private fun local(project: Project, scope: VarScope.Local, name: String): List<VarOccurrence> {
        val psi = PsiManager.getInstance(project).findFile(scope.file) ?: return emptyList()
        val analysis = JinjaTextSites.analysisAt(psi, scope.binding) ?: return emptyList()
        val local: JinjaLocal = analysis.result.locals.firstOrNull { it.name == name && analysis.toHost(it.definitionRange.startOffset) == scope.binding }
            ?: return emptyList()
        val result = ArrayList<VarOccurrence>()
        result += VarOccurrence(scope.file, analysis.toHost(local.definitionRange), write = true, kind = VarUsageKind.SET_LOCAL)
        for (reference in analysis.result.localReferences) {
            if (reference.local == local) result += VarOccurrence(scope.file, analysis.toHost(reference.nameRange), write = false, kind = VarUsageKind.READ_LOCAL)
        }
        return result
    }

    private fun ordered(found: List<VarOccurrence>): List<VarOccurrence> =
        found.distinctBy { it.file to it.range.startOffset }.sortedWith(compareBy<VarOccurrence>({ it.file.path }, { it.range.startOffset }))
}
