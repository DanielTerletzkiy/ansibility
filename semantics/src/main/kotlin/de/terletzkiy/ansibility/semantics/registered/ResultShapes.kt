package de.terletzkiy.ansibility.semantics.registered

import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.schema.OptionType
import de.terletzkiy.ansibility.semantics.schema.ReturnSpec
import de.terletzkiy.ansibility.semantics.typeflow.AValue
import de.terletzkiy.ansibility.semantics.typeflow.BaseType

/**
 * Builds the shape of a `register:` result (plan amendment FU, F1.12) from the module's documented return values and
 * the registering task's features, the way ansible-core assembles a task result:
 *
 * - **Module returns** (`RETURN`, `contains` for nested values) first, then keys the module returns although its
 *   documentation omits them ([SUPPLEMENTS]: `json` of `uri`, `rc`/`stdout`/`stderr` of `raw` and `script`), then
 *   `stdout_lines`/`stderr_lines`, which the action plugin splits from a returned `stdout`/`stderr` when the module
 *   does not return them itself; they are lists of `str` either way (`command` and `shell` document a bare `list`).
 * - **Common keys** every task result has: `changed`, `failed`, `skipped`, `msg`, `invocation`, `warnings`,
 *   `deprecations`, `diff`, and `backup_file` when the module has a `backup` option. A key the module documents keeps
 *   the module's documentation.
 * - **Retries** (`until:`, or `retries:` above 0): `attempts`, and `retries` once the task was retried.
 * - **Async** (`async:` above 0): the job keys `ansible_job_id`, `started`, `finished` (typed by the target's
 *   `async_status` documentation when given) and `results_file`; with `poll: 0` the result describes only the started
 *   job, so the module's own returns are absent.
 * - **Loops**: the result becomes `results` (a list with one item result each: the module's result plus the loop
 *   variable, `ansible_loop_var`, the `index_var` and `ansible_index_var` when set, `ansible_loop` when extended) and
 *   the loop's `changed`, `failed`, `skipped` and `msg`.
 *
 * Several registering tasks give the union of their results: keys of every task, nested keys merged, a type the tasks
 * disagree on becomes `raw`, and each member keeps one [MemberDoc] per distinct documentation with the tasks it comes
 * from. Members are ordered module returns first, common keys last.
 *
 * Built-in members carry a [MemberDoc.textKey] instead of a description; the plugin renders those texts.
 */
object ResultShapes {
    /** The loop variable's default name. */
    const val DEFAULT_LOOP_VAR: String = "item"

    /** The module whose documented returns type the job keys of an async task. */
    const val ASYNC_STATUS: String = "ansible.builtin.async_status"

    /** A key a module returns although its documentation omits it. */
    data class Supplement(val name: String, val type: OptionType, val elements: OptionType? = null, val textKey: String)

    private val COMMAND_LIKE = listOf(
        Supplement("rc", OptionType.Int, textKey = "supplement.command.rc"),
        Supplement("stdout", OptionType.Str, textKey = "supplement.command.stdout"),
        Supplement("stderr", OptionType.Str, textKey = "supplement.command.stderr"),
    )

    /**
     * Undocumented returns of builtin modules, by canonical FQCN: `uri` adds the parsed body as `json` for JSON
     * responses; `raw` and `script` (no `RETURN` block) return the command keys like `command` does.
     */
    val SUPPLEMENTS: Map<String, List<Supplement>> = mapOf(
        "ansible.builtin.uri" to listOf(Supplement("json", OptionType.Raw, textKey = "supplement.uri.json")),
        "ansible.builtin.raw" to COMMAND_LIKE,
        "ansible.builtin.script" to COMMAND_LIKE,
    )

    /** The keys `loop_control.extended` puts into `ansible_loop`, with their types. */
    val ANSIBLE_LOOP_KEYS: List<Pair<String, OptionType>> = listOf(
        "allitems" to OptionType.List, "index" to OptionType.Int, "index0" to OptionType.Int, "revindex" to OptionType.Int,
        "revindex0" to OptionType.Int, "first" to OptionType.Bool, "last" to OptionType.Bool, "length" to OptionType.Int,
        "previtem" to OptionType.Raw, "nextitem" to OptionType.Raw,
    )

    /** The common keys of every task result (plan F1.12), with their types; `backup_file` only for modules with `backup`. */
    private val COMMON: List<Triple<String, OptionType, OptionType?>> = listOf(
        Triple("changed", OptionType.Bool, null),
        Triple("failed", OptionType.Bool, null),
        Triple("skipped", OptionType.Bool, null),
        Triple("msg", OptionType.Str, null),
        Triple("invocation", OptionType.Dict, null),
        Triple("warnings", OptionType.List, OptionType.Str),
        Triple("deprecations", OptionType.List, OptionType.Dict),
        Triple("diff", OptionType.Raw, null),
    )

    private const val BACKUP_FILE = "backup_file"
    private const val STDOUT = "stdout"
    private const val STDERR = "stderr"
    private const val LINES = "_lines"

    /** Presentation order of member kinds: what the module returns first, what every task has last. */
    private val RANK: Map<MemberKind, Int> = listOf(
        MemberKind.LOOP, MemberKind.MODULE, MemberKind.SUPPLEMENT, MemberKind.DERIVED, MemberKind.LOOP_ITEM,
        MemberKind.RETRY, MemberKind.ASYNC, MemberKind.COMMON,
    ).withIndex().associate { (index, kind) -> kind to index }

    /** The shape of [name] registered by [sources] (at least one), their results unioned in order. */
    fun build(name: String, sources: List<ResultSource>): ResultShape {
        require(sources.isNotEmpty()) { "a registered result needs at least one registering task" }
        val root = sources.mapIndexed { index, source -> resultOf(source, index) }.reduce(::union)
        return ResultShape(name, sources, root)
    }

    /** The result of the single registering task [source], whose index among the shape's sources is [task]. */
    fun resultOf(source: ResultSource, task: Int): ResultMember {
        val tasks = listOf(task)
        val item = itemResult(source, tasks)
        val members = source.loop?.let { loopResult(item, tasks) } ?: item
        return ResultMember("", OptionType.Dict, null, ordered(members), emptyList())
    }

    /**
     * The union of two results or members: the keys of both (nested keys merged), the type both agree on (else `raw`),
     * and the documentation of both (identical documentation merged, keeping all tasks).
     */
    fun union(a: ResultMember, b: ResultMember): ResultMember {
        val sameType = a.type == b.type
        val members = when {
            a.members == null -> b.members
            b.members == null -> a.members
            else -> {
                val merged = LinkedHashMap(a.members)
                for ((key, member) in b.members) merged[key] = merged[key]?.let { union(it, member) } ?: member
                ordered(merged)
            }
        }
        return ResultMember(
            name = a.name,
            type = if (sameType) a.type else OptionType.Raw,
            elements = if (sameType && a.elements == b.elements) a.elements else null,
            members = members,
            docs = mergeDocs(a.docs, b.docs),
        )
    }

    /**
     * The logical type a member's documented type gives an expression reading it (ANS-T020: `{{ x.rc }}` is an int);
     * null when the documentation says nothing checkable (`raw`, `json`, `any` …), so the value stays unknown.
     */
    fun logicalType(member: ResultMember): AValue? = when (member.type) {
        OptionType.Str, OptionType.Path -> AValue.of(BaseType.STR)
        OptionType.Int -> AValue.of(BaseType.INT)
        OptionType.Float -> AValue.of(BaseType.FLOAT)
        OptionType.Bool -> AValue.of(BaseType.BOOL)
        OptionType.List -> AValue.of(BaseType.LIST)
        OptionType.Dict -> AValue.of(BaseType.DICT)
        else -> null
    }

    // ------------------------------------------------------------------------------------------------ one module run

    /** The result of one run of the module: the whole result without a loop, one item's result with one. */
    private fun itemResult(source: ResultSource, tasks: List<Int>): LinkedHashMap<String, ResultMember> {
        val result = LinkedHashMap<String, ResultMember>()
        val module = source.module
        val async = source.async
        if (async == null || async.polled) {
            source.returns?.forEach { (name, spec) -> result.putIfAbsent(name, fromReturn(spec, module, listOf(name), MemberKind.MODULE, tasks)) }
            module?.let { SUPPLEMENTS[it] }?.forEach { supplement ->
                result.putIfAbsent(supplement.name, builtIn(supplement.name, supplement.type, supplement.elements, MemberKind.SUPPLEMENT, supplement.textKey, tasks, module))
            }
            derivedLines(result, STDOUT, module, tasks)
            derivedLines(result, STDERR, module, tasks)
        }
        if (async != null) asyncKeys(result, async, tasks)
        if (source.retried) {
            result.putIfAbsent("attempts", builtIn("attempts", OptionType.Int, null, MemberKind.RETRY, "retry.attempts", tasks))
            result.putIfAbsent("retries", builtIn("retries", OptionType.Int, null, MemberKind.RETRY, "retry.retries", tasks))
        }
        source.loop?.let { loopItemKeys(result, it, tasks) }
        for ((name, type, elements) in COMMON) result.putIfAbsent(name, common(name, type, elements, tasks))
        if (source.backup) result.putIfAbsent(BACKUP_FILE, common(BACKUP_FILE, OptionType.Str, null, tasks))
        return result
    }

    /**
     * `stdout_lines` for a returned `stdout` string (likewise `stderr`), unless the module documents it itself; a
     * documented one that names no element type (`command` documents a bare `list`) gets `str` elements, since the
     * lines are the text's `splitlines()`.
     */
    private fun derivedLines(result: MutableMap<String, ResultMember>, stream: String, module: String?, tasks: List<Int>) {
        val text = result[stream] ?: return
        if (text.type != OptionType.Str) return
        val documented = text.primary?.takeIf { it.kind == MemberKind.MODULE }
        val name = stream + LINES
        result[name]?.let { own ->
            if (own.type == OptionType.List && own.elements == null && own.members == null) {
                result[name] = ResultMember(own.name, own.type, OptionType.Str, null, own.docs)
            }
            return
        }
        val doc = MemberDoc(
            MemberKind.DERIVED, module, documented?.returnPath.orEmpty(), "derived.$name", OptionType.List, OptionType.Str,
            emptyList(), documented?.returned, null, tasks,
        )
        result.putIfAbsent(name, ResultMember(name, OptionType.List, OptionType.Str, null, listOf(doc)))
    }

    private fun asyncKeys(result: MutableMap<String, ResultMember>, async: AsyncFeature, tasks: List<Int>) {
        for ((name, fallback) in listOf("ansible_job_id" to OptionType.Str, "started" to OptionType.Int, "finished" to OptionType.Int)) {
            val documented = async.statusReturns?.get(name)
            val member = if (documented != null) {
                fromReturn(documented, ASYNC_STATUS, listOf(name), MemberKind.ASYNC, tasks)
            } else {
                builtIn(name, fallback, null, MemberKind.ASYNC, "async.$name", tasks)
            }
            result.putIfAbsent(name, member)
        }
        result.putIfAbsent("results_file", builtIn("results_file", OptionType.Str, null, MemberKind.ASYNC, "async.results_file", tasks))
    }

    // ------------------------------------------------------------------------------------------------ loops

    /** The keys of one item's result that name the item. */
    private fun loopItemKeys(result: MutableMap<String, ResultMember>, loop: LoopFeature, tasks: List<Int>) {
        result.putIfAbsent(loop.loopVar, loopItem(loop, tasks))
        result.putIfAbsent("ansible_loop_var", builtIn("ansible_loop_var", OptionType.Str, null, MemberKind.LOOP_ITEM, "item.ansible_loop_var", tasks))
        loop.indexVar?.let { indexVar ->
            result.putIfAbsent(indexVar, builtIn(indexVar, OptionType.Int, null, MemberKind.LOOP_ITEM, "item.index_var", tasks))
            result.putIfAbsent("ansible_index_var", builtIn("ansible_index_var", OptionType.Str, null, MemberKind.LOOP_ITEM, "item.ansible_index_var", tasks))
        }
        if (loop.extended) {
            val keys = ANSIBLE_LOOP_KEYS.associateTo(LinkedHashMap()) { (name, type) ->
                name to builtIn(name, type, null, MemberKind.LOOP_ITEM, "item.ansible_loop.$name", tasks)
            }
            val doc = builtInDoc(MemberKind.LOOP_ITEM, "item.ansible_loop", OptionType.Dict, null, tasks)
            result.putIfAbsent("ansible_loop", ResultMember("ansible_loop", OptionType.Dict, null, keys, listOf(doc)))
        }
    }

    /** The loop variable in an item's result, typed like the loop item when known. */
    private fun loopItem(loop: LoopFeature, tasks: List<Int>): ResultMember {
        val item = loop.item
        val doc = builtInDoc(MemberKind.LOOP_ITEM, "item.loop_var", item?.type ?: OptionType.Raw, item?.elements, tasks)
        if (item == null) return ResultMember(loop.loopVar, OptionType.Raw, null, null, listOf(doc))
        return ResultMember(loop.loopVar, item.type, item.elements, item.options?.let { options(it, tasks) }, listOf(doc))
    }

    /** The documented keys of a loop item, as members described by the item's own documentation. */
    private fun options(options: Map<String, OptionSpec>, tasks: List<Int>): Map<String, ResultMember> =
        options.mapValuesTo(LinkedHashMap()) { (name, option) ->
            val doc = MemberDoc(MemberKind.LOOP_ITEM, null, emptyList(), null, option.type, option.elements, option.description, null, null, tasks)
            ResultMember(name, option.type, option.elements, option.options?.let { options(it, tasks) }, listOf(doc))
        }

    /** The top level of a looping task's result: `results` holding the item results, and the loop's own keys. */
    private fun loopResult(item: Map<String, ResultMember>, tasks: List<Int>): LinkedHashMap<String, ResultMember> {
        val result = LinkedHashMap<String, ResultMember>()
        val doc = builtInDoc(MemberKind.LOOP, "loop.results", OptionType.List, OptionType.Dict, tasks)
        result["results"] = ResultMember("results", OptionType.List, OptionType.Dict, ordered(item), listOf(doc))
        result["msg"] = builtIn("msg", OptionType.Str, null, MemberKind.LOOP, "loop.msg", tasks)
        for (name in listOf("changed", "failed", "skipped")) result[name] = common(name, OptionType.Bool, null, tasks)
        return result
    }

    // ------------------------------------------------------------------------------------------------ members

    /** A documented return value (and its `contains`) of [module], at [path] on the module's page. */
    private fun fromReturn(spec: ReturnSpec, module: String?, path: List<String>, kind: MemberKind, tasks: List<Int>): ResultMember {
        val contains = spec.contains?.takeIf { it.isNotEmpty() }
        val elements = spec.elements ?: OptionType.Dict.takeIf { spec.type == OptionType.List && contains != null }
        val members = contains?.entries?.associateTo(LinkedHashMap()) { (name, child) -> name to fromReturn(child, module, path + name, kind, tasks) }
        val doc = MemberDoc(kind, module, path, null, spec.type, elements, spec.description, spec.returned, spec.sample, tasks)
        return ResultMember(spec.name, spec.type, elements, members, listOf(doc))
    }

    private fun common(name: String, type: OptionType, elements: OptionType?, tasks: List<Int>): ResultMember {
        val members = if (name == "invocation") {
            linkedMapOf("module_args" to builtIn("module_args", OptionType.Dict, null, MemberKind.COMMON, "common.invocation.module_args", tasks))
        } else {
            null
        }
        return ResultMember(name, type, elements, members, listOf(builtInDoc(MemberKind.COMMON, "common.$name", type, elements, tasks)))
    }

    private fun builtIn(
        name: String,
        type: OptionType,
        elements: OptionType?,
        kind: MemberKind,
        textKey: String,
        tasks: List<Int>,
        module: String? = null,
    ): ResultMember = ResultMember(name, type, elements, null, listOf(builtInDoc(kind, textKey, type, elements, tasks, module)))

    private fun builtInDoc(kind: MemberKind, textKey: String, type: OptionType, elements: OptionType?, tasks: List<Int>, module: String? = null) =
        MemberDoc(kind, module, emptyList(), textKey, type, elements, emptyList(), null, null, tasks)

    /** [members] in presentation order: by the best kind of each member ([RANK]), stable within a kind. */
    private fun ordered(members: Map<String, ResultMember>): Map<String, ResultMember> =
        members.entries.sortedBy { (_, member) -> member.docs.minOfOrNull { RANK.getValue(it.kind) } ?: Int.MAX_VALUE }
            .associateTo(LinkedHashMap()) { it.key to it.value }

    /** [a] then [b], documentation identical but for its tasks merged into one entry. */
    private fun mergeDocs(a: List<MemberDoc>, b: List<MemberDoc>): List<MemberDoc> {
        val result = ArrayList(a)
        for (doc in b) {
            val index = result.indexOfFirst { it.sameAs(doc) }
            if (index < 0) result += doc else result[index] = result[index].copy(tasks = (result[index].tasks + doc.tasks).distinct().sorted())
        }
        return result
    }
}
