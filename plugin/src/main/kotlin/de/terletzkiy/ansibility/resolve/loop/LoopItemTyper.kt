package de.terletzkiy.ansibility.resolve.loop

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.RenderLoop
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.ValueShape
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarDefinition
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.lang.jinja.lexer.JinjaLexMode
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaRefs
import de.terletzkiy.ansibility.model.task.LoopInfo
import de.terletzkiy.ansibility.model.task.TaskFileModels
import de.terletzkiy.ansibility.model.task.TaskItem
import de.terletzkiy.ansibility.model.task.TaskNode
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.schema.OptionType
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.vars.SpecOptions
import de.terletzkiy.ansibility.vars.VarLocations
import de.terletzkiy.ansibility.vars.VaultInfo
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import org.jetbrains.yaml.YAMLLanguage
import org.jetbrains.yaml.psi.YAMLFile
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/** The typed loop of one task (plan F1.7): its variables and the element type of what it iterates. */
class LoopItemType(
    val loop: RenderLoop,
    /** The loop keyword as written (`loop`, `with_items`, `with_dict` …). */
    val keyword: String,
    /** The looping task's mapping. */
    val task: SourceLocation,
) {
    val loopVar: String get() = loop.loopVar

    /** The names the loop defines inside the task: the loop variable, `index_var` and `ansible_loop` (extended). */
    val names: List<String> get() = LoopItemTyper.namesOf(loop)

    /** The type of `root.attrPath…` inside the task (and in the templates it renders), or null when unknown. */
    fun typeOfPath(root: String, attrPath: List<String>): OptionSpec? = LoopItemTyper.typeOfPath(loop, root, attrPath)

    override fun toString(): String = "LoopItemType(${loop.loopVar} over ${loop.sourceVariable ?: keyword}: ${loop.item?.type?.name})"
}

/**
 * Types the loop variable of a task (plan F1.7, X09 for literal lists and `with_dict`):
 * - `loop: "{{ var }}"`, `with_items: "{{ var }}"` and `var.sub` paths resolve `var` through the task's own `vars:`
 *   (literal values), the root's spec bindings (own role first, [SpecOptions] for nested paths), and else the first
 *   literal container definition of the root; a `list` gives its `elements`/`options` as the item;
 * - element-preserving filters (`default`, `list`, `sort`, `unique`, `reverse`, `select*`/`reject*`, `flatten`) keep
 *   the element, `map(attribute='a.b')` walks into it, `dict2items` and `with_dict` give `{key, value}`;
 * - a literal YAML list (or `with_dict` mapping) gives its element shape ([LiteralShapes]; `with_items` flattens one
 *   level); `with_fileglob`, `with_sequence`, `with_lines` and `lookup('fileglob', …)` give strings;
 * - `loop_control.loop_var` names the variable (default `item`), `index_var` is an `int`, and `extended` defines
 *   `ansible_loop`.
 *
 * Secrets are never read: vault files, `vault_*` names and `!vault` values are skipped, and only keys and types of
 * literal values are kept. Results are cached per file until YAML or the Ansible structure changes. Call in a read
 * action in smart mode (the spec lookup reads indexes).
 */
object LoopItemTyper {
    /** The variable `loop_control.extended` defines. */
    const val ANSIBLE_LOOP: String = "ansible_loop"

    private val CACHE = Key.create<CachedValue<ConcurrentHashMap<Int, Optional<LoopItemType>>>>("ansibility.resolve.loopTypes")

    private val PRESERVING = setOf("default", "d", "list", "sort", "unique", "reverse", "shuffle", "flatten", "select", "reject", "selectattr", "rejectattr")
    private val STRING_LOOKUPS = setOf("fileglob", "sequence", "lines", "file", "first_found", "env", "pipe", "password", "url", "varnames")
    private val WHOLE_EXPRESSION = Regex("""^\s*\{\{[-+]?(.*?)[-+]?}}\s*$""", RegexOption.DOT_MATCHES_ALL)
    private val LOOKUP_NAME = Regex("""^\s*\(\s*['"](?:ansible\.builtin\.|ansible\.legacy\.)?([A-Za-z_][\w.]*)['"]""")
    private val MAP_ATTRIBUTE = Regex("""^\s*\(\s*attribute\s*=\s*['"]([^'"]+)['"]""")

    /** Element type of `ansible_loop` (the `loop_control.extended` variables). */
    val ansibleLoopSpec: OptionSpec = OptionSpec(
        ANSIBLE_LOOP, OptionType.Dict,
        options = linkedMapOf(
            "allitems" to OptionSpec("allitems", OptionType.List),
            "index" to OptionSpec("index", OptionType.Int),
            "index0" to OptionSpec("index0", OptionType.Int),
            "revindex" to OptionSpec("revindex", OptionType.Int),
            "revindex0" to OptionSpec("revindex0", OptionType.Int),
            "first" to OptionSpec("first", OptionType.Bool),
            "last" to OptionSpec("last", OptionType.Bool),
            "length" to OptionSpec("length", OptionType.Int),
            "previtem" to OptionSpec("previtem", OptionType.Raw),
            "nextitem" to OptionSpec("nextitem", OptionType.Raw),
        ),
    )

    /** The loop of the innermost task around [offset] of [file], or null when that task does not loop. */
    fun typeAt(project: Project, file: YAMLFile, offset: Int): LoopItemType? {
        val chain = TaskChains.chainAt(TaskFileModels.of(file), offset)
        val task = TaskChains.taskOf(chain) ?: return null
        return typeOf(project, file, task, chain)
    }

    /**
     * The loop of [task] in [file] ([chain] is the task with its enclosing blocks, outermost first, for their
     * `vars:`), or null when the task does not loop.
     */
    fun typeOf(project: Project, file: YAMLFile, task: TaskNode, chain: List<TaskItem> = listOf(task)): LoopItemType? {
        val loop = task.loop ?: return null
        val cache = CachedValuesManager.getCachedValue(file, CACHE) {
            CachedValueProvider.Result.create(
                ConcurrentHashMap(),
                PsiModificationTracker.getInstance(project).forLanguage(YAMLLanguage.INSTANCE),
                AnsibleWorkspace.getInstance(project).structureTracker,
            )
        }
        val key = task.range.startOffset
        cache[key]?.let { return it.orElse(null) }
        val computed = compute(project, file, task, loop, chain)
        cache.putIfAbsent(key, Optional.ofNullable(computed))
        return computed
    }

    /** The names a typed loop defines inside its task. */
    fun namesOf(loop: RenderLoop): List<String> = listOfNotNull(loop.loopVar, loop.indexVar, ANSIBLE_LOOP.takeIf { loop.extended })

    /** The type of `root.attrPath…` for a name [loop] defines, or null when the name is not the loop's or untyped. */
    fun typeOfPath(loop: RenderLoop, root: String, attrPath: List<String>): OptionSpec? = when (root) {
        loop.loopVar -> loop.item?.let { walk(it, attrPath) }
        loop.indexVar -> OptionSpec(root, OptionType.Int).takeIf { attrPath.isEmpty() }
        ANSIBLE_LOOP -> if (loop.extended) walk(ansibleLoopSpec, attrPath) else null
        else -> null
    }

    /** Walks [path] (accessors as written) through [option]; integer segments address list elements. */
    fun walk(option: OptionSpec, path: List<String>): OptionSpec? {
        if (path.isEmpty()) return option
        if (option.type == OptionType.List && path.first().toIntOrNull() != null) {
            return walk(elementOf(option, option.name) ?: return null, path.drop(1))
        }
        return SpecOptions.resolve(option, path)?.option
    }

    /** The element of a `list` option, named [name] (null when the list documents no element type). */
    fun elementOf(option: OptionSpec, name: String): OptionSpec? {
        if (option.type != OptionType.List) return null
        val elements = option.elements ?: if (option.options != null) OptionType.Dict else return null
        return OptionSpec(
            name = name,
            type = elements,
            options = option.options?.takeIf { elements == OptionType.Dict },
            description = option.description,
            deprecated = option.deprecated,
            origin = option.origin,
        )
    }

    /** `{key, value}` of a `dict2items`/`with_dict` element named [name]. */
    fun keyValue(name: String, value: OptionSpec? = null): OptionSpec = OptionSpec(
        name, OptionType.Dict,
        options = linkedMapOf(
            "key" to OptionSpec("key", OptionType.Str),
            "value" to (value?.copy(name = "value") ?: OptionSpec("value", OptionType.Raw)),
        ),
    )

    // ------------------------------------------------------------------------------------------------ computation

    /** What is being iterated, before it is reduced to an element. */
    private sealed interface Collection {
        data class Sequence(val element: OptionSpec?) : Collection
        data class Mapping(val spec: OptionSpec?) : Collection
        data object Unknown : Collection
    }

    private class Source(val collection: Collection, val variable: String?, val path: List<String>)

    private fun compute(project: Project, file: YAMLFile, task: TaskNode, loop: LoopInfo, chain: List<TaskItem>): LoopItemType? {
        val loopVar = task.loopVar ?: return null
        val control = task.loopControl
        val virtualFile = file.originalFile.virtualFile ?: return null
        val context = AnsibleWorkspace.getInstance(project).contextOf(virtualFile)
        val source = sourceOf(project, context, chain, loop)
        val item = when (val collection = source.collection) {
            is Collection.Sequence -> collection.element?.copy(name = loopVar)
            is Collection.Mapping -> if (loop.lookup == "dict") keyValue(loopVar) else null
            Collection.Unknown -> null
        }
        val renderLoop = RenderLoop(
            loopVar = loopVar,
            indexVar = control?.indexVar?.text,
            extended = isTrue(control?.extended),
            item = item,
            sourceVariable = source.variable,
            sourcePath = source.path,
        )
        return LoopItemType(renderLoop, loop.keyword, SourceLocation(virtualFile, task.range.startOffset))
    }

    private fun sourceOf(project: Project, context: FileContext?, chain: List<TaskItem>, loop: LoopInfo): Source {
        val lookup = loop.lookup
        if (lookup != null && lookup in STRING_LOOKUPS) return Source(Collection.Sequence(OptionSpec("item", OptionType.Str)), null, emptyList())
        if (lookup != null && lookup !in setOf("items", "list", "dict", "flattened", "random_choice")) return Source(Collection.Unknown, null, emptyList())
        return when (val value = loop.value) {
            is YSeq -> Source(Collection.Sequence(LiteralShapes.elementOf("item", value.items, flatten = lookup == "items" || lookup == "flattened")), null, emptyList())
            is YMap -> if (lookup == "dict") literalDict(value) else Source(Collection.Unknown, null, emptyList())
            is YScalar -> expressionSource(project, context, chain, value.text, flatten = lookup == "items", withDict = lookup == "dict")
            else -> Source(Collection.Unknown, null, emptyList())
        }
    }

    private fun literalDict(map: YMap): Source {
        val values = map.entries.map { LiteralShapes.of("value", it.value) }.reduceOrNull { a, b -> LiteralShapes.union(a, b) }
        return Source(Collection.Sequence(keyValue("item", values)), null, emptyList())
    }

    /** `{{ var.path | filters }}` or a lookup call; anything else is unknown. */
    private fun expressionSource(
        project: Project,
        context: FileContext?,
        chain: List<TaskItem>,
        text: String,
        flatten: Boolean,
        withDict: Boolean,
    ): Source {
        val body = WHOLE_EXPRESSION.matchEntire(text)?.groupValues?.get(1)
        if (body == null || body.contains("{{")) {
            // `with_items: name` (no braces) is one literal string, as in ansible-core 2.x.
            return if (!text.contains("{{") && !text.contains("{%")) Source(Collection.Sequence(OptionSpec("item", OptionType.Str)), null, emptyList())
            else Source(Collection.Unknown, null, emptyList())
        }
        return iterationSource(body, { name, path -> variableType(project, context, chain, name, path) }, flatten, withDict)
    }

    /** What iterating a Jinja expression yields: the element type, and the variable (with accessors) iterated. */
    class Iteration(val element: OptionSpec?, val variable: String?, val path: List<String>)

    /**
     * The element of iterating the bare Jinja [expression] (`haproxy_servers | default([])`, no delimiters), with
     * the iterated variable typed by [typeOf] (name, accessor path → type). With [flatten] (the `with_items` rule) a
     * list of lists yields the inner elements; with [withDict] a mapping yields `{key, value}`. The element is named
     * `item`; callers rename it.
     */
    fun iterate(
        expression: String,
        typeOf: (String, List<String>) -> OptionSpec?,
        flatten: Boolean = false,
        withDict: Boolean = false,
    ): Iteration {
        val source = iterationSource(expression, typeOf, flatten, withDict)
        val element = when (val collection = source.collection) {
            is Collection.Sequence -> collection.element
            is Collection.Mapping, Collection.Unknown -> null
        }
        return Iteration(element, source.variable, source.path)
    }

    private fun iterationSource(
        body: String,
        typeOf: (String, List<String>) -> OptionSpec?,
        flatten: Boolean,
        withDict: Boolean,
    ): Source {
        val refs = JinjaRefs.analyze(body, JinjaLexMode.EXPRESSION)
        val first = refs.references.minByOrNull { it.range.startOffset } ?: return Source(Collection.Unknown, null, emptyList())
        if (first.range.startOffset != body.indexOfFirst { !it.isWhitespace() }) return Source(Collection.Unknown, null, emptyList())
        val rest = body.substring(first.range.endOffset)
        var collection: Collection
        var variable: String? = null
        var path: List<String> = emptyList()
        if (first.called) {
            if (first.name !in setOf("lookup", "query", "q")) return Source(Collection.Unknown, null, emptyList())
            val plugin = LOOKUP_NAME.find(rest)?.groupValues?.get(1)
            collection = if (plugin in STRING_LOOKUPS) Collection.Sequence(OptionSpec("item", OptionType.Str)) else Collection.Unknown
        } else {
            val afterRef = rest.trimStart()
            if (afterRef.isNotEmpty() && !afterRef.startsWith("|")) return Source(Collection.Unknown, null, emptyList())
            variable = first.name
            path = first.attrPath
            collection = collectionOf(typeOf(first.name, first.attrPath), flatten)
        }
        for ((filter, args) in topLevelFilters(body, first.range.endOffset)) {
            ProgressManager.checkCanceled()
            collection = applyFilter(collection, filter, args)
        }
        if (withDict && collection is Collection.Mapping) collection = Collection.Sequence(keyValue("item"))
        return Source(collection, variable, path)
    }

    private fun collectionOf(option: OptionSpec?, flatten: Boolean): Collection = when (option?.type) {
        null -> Collection.Unknown
        OptionType.List -> {
            val element = elementOf(option, "item")
            if (flatten && element?.type == OptionType.List) Collection.Sequence(element.let { elementOf(it, "item") }) else Collection.Sequence(element)
        }
        OptionType.Dict -> Collection.Mapping(option)
        else -> Collection.Unknown
    }

    private fun applyFilter(collection: Collection, filter: String, args: String): Collection = when {
        filter in PRESERVING -> collection
        filter == "dict2items" -> if (collection is Collection.Mapping) Collection.Sequence(keyValue("item")) else Collection.Unknown
        filter == "map" && collection is Collection.Sequence -> {
            val attribute = MAP_ATTRIBUTE.find(args)?.groupValues?.get(1)
            val element = collection.element
            if (attribute != null && element != null) Collection.Sequence(walk(element, attribute.split('.'))?.copy(name = "item")) else Collection.Sequence(null)
        }
        else -> Collection.Unknown
    }

    /**
     * The filters applied at the top level of [body] after [from], each with the text of its argument list (empty
     * when it takes none). Filters inside arguments (`default(x | list)`) are not top level.
     */
    private fun topLevelFilters(body: String, from: Int): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        var depth = 0
        var quote: Char? = null
        var i = from
        while (i < body.length) {
            val c = body[i]
            when {
                quote != null -> if (c == '\\') i++ else if (c == quote) quote = null
                c == '\'' || c == '"' -> quote = c
                c == '(' || c == '[' || c == '{' -> depth++
                c == ')' || c == ']' || c == '}' -> depth = maxOf(0, depth - 1)
                c == '|' && depth == 0 -> {
                    var start = i + 1
                    while (start < body.length && body[start].isWhitespace()) start++
                    var end = start
                    while (end < body.length && (body[end].isLetterOrDigit() || body[end] == '_' || body[end] == '.')) end++
                    val name = body.substring(start, end).removePrefix("ansible.builtin.").removePrefix("ansible.legacy.")
                    result += name to argumentsAt(body, end)
                    i = end - 1
                }
            }
            i++
        }
        return result
    }

    /** The parenthesised argument list starting at [from] (after optional blanks), or "". */
    private fun argumentsAt(body: String, from: Int): String {
        var start = from
        while (start < body.length && body[start].isWhitespace()) start++
        if (start >= body.length || body[start] != '(') return ""
        var depth = 0
        var quote: Char? = null
        for (i in start until body.length) {
            val c = body[i]
            when {
                quote != null -> if (c == quote && body[i - 1] != '\\') quote = null
                c == '\'' || c == '"' -> quote = c
                c == '(' -> depth++
                c == ')' -> if (--depth == 0) return body.substring(start, i + 1)
            }
        }
        return body.substring(start)
    }

    /**
     * The type of variable [name] at [path] as the task sees it: its own or enclosing blocks' literal `vars:`, then
     * the root's spec bindings (the file's role first), then the first literal container definition in the root.
     */
    fun variableType(project: Project, context: FileContext?, chain: List<TaskItem>, name: String, path: List<String>): OptionSpec? {
        for (item in chain.asReversed()) {
            val value = TaskChains.varsOf(item)?.get(name) ?: continue
            return walk(LiteralShapes.of(name, value), path)
        }
        val root = context?.root ?: return null
        val symbol = VarService.getInstance(project).symbol(root, name)
        val bindings = symbol.specBindings.sortedBy { if (it.role.name == context.roleName) 0 else 1 }
        for (binding in bindings) {
            val option = walk(binding.option, path)
            if (option != null) return option
        }
        return literalDefinitions(symbol.definitions, context.roleName).firstNotNullOfOrNull { definition ->
            ProgressManager.checkCanceled()
            literalValue(project, name, definition)?.let { walk(LiteralShapes.of(name, it), path) }
        }
    }

    /** Container definitions worth reading for a shape: own role defaults and vars first; never secrets. */
    private fun literalDefinitions(definitions: List<VarDefinition>, ownRole: String?): List<VarDefinition> =
        definitions.filter { it.valueShape == ValueShape.CONTAINER && it.kind != VarDefKind.SPEC_OPTION }
            .sortedWith(
                compareBy<VarDefinition>(
                    { if (it.roleName == ownRole && ownRole != null) 0 else 1 },
                    { if (it.kind == VarDefKind.ROLE_DEFAULT) 0 else if (it.kind == VarDefKind.ROLE_VAR) 1 else 2 },
                    { it.location.file.path },
                ),
            ).take(MAX_LITERAL_DEFINITIONS)

    private fun literalValue(project: Project, name: String, definition: VarDefinition): YValue? {
        val file: VirtualFile = definition.location.file
        if (VaultInfo.isSecret(name, file)) return null
        val keyValue = VarLocations.keyValueAt(project, definition.location) ?: return null
        if (VaultInfo.isVaultValue(keyValue)) return null
        return PsiYValueAdapter.valueOf(keyValue)
    }

    private fun isTrue(value: YValue?): Boolean =
        (value as? YScalar)?.text?.trim()?.lowercase() in setOf("yes", "on", "1", "true", "y", "t")

    /** Definitions read for a literal shape, at most. */
    private const val MAX_LITERAL_DEFINITIONS = 3
}
