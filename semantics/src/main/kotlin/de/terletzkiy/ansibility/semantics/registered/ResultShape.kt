package de.terletzkiy.ansibility.semantics.registered

import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.schema.OptionType
import de.terletzkiy.ansibility.semantics.schema.ReturnSpec

/*
 * The shape of a `register:` result (plan amendment FU, F1.12 "typed register results"): what a registered variable
 * holds after its task ran, built by [ResultShapes] from the module's documented return values and the task's
 * features. PSI-free and IntelliJ-free; the plugin maps task indices back to its own task locations.
 */

/**
 * What one registering task contributes to its result's shape.
 *
 * @property module the canonical FQCN of the task's module after routing (`ansible.builtin.command`), or null when the
 *   task names no module the plugin knows
 * @property returns the module's documented return values (its `RETURN` block); null when no documentation source
 *   documents the module (the result then has the common keys only)
 * @property backup the module documents a `backup` option, so its result may carry `backup_file`
 * @property loop the task loops (`loop:`, `with_<lookup>:`): the result holds `results`, one result per item
 * @property retried the task retries (`until:` set, or `retries:` asking for at least one retry): results carry
 *   `attempts`
 * @property async the task runs asynchronously (`async:` above 0)
 */
data class ResultSource(
    val module: String?,
    val returns: Map<String, ReturnSpec>?,
    val backup: Boolean = false,
    val loop: LoopFeature? = null,
    val retried: Boolean = false,
    val async: AsyncFeature? = null,
)

/**
 * The loop of a registering task.
 *
 * @property loopVar the loop variable (`loop_control.loop_var`, default `item`): each item result holds the item
 *   under this name
 * @property indexVar `loop_control.index_var`: each item result holds the index under this name
 * @property extended `loop_control.extended`: each item result holds `ansible_loop`
 * @property item the type of one loop item when the plugin knows it (a spec'd or literal list), else null
 */
data class LoopFeature(
    val loopVar: String,
    val indexVar: String? = null,
    val extended: Boolean = false,
    val item: OptionSpec? = null,
)

/**
 * The `async:` of a registering task.
 *
 * @property polled `poll:` is above 0 (or unset, default 15): the task waits for the job, so the result is the
 *   module's result plus the job keys; with `poll: 0` the result only describes the started job
 * @property statusReturns the documented return values of `ansible.builtin.async_status`, which types the job keys
 *   (`ansible_job_id`, `started`, `finished`) of the target ansible-core; null when unknown (built-in types are used)
 */
data class AsyncFeature(val polled: Boolean, val statusReturns: Map<String, ReturnSpec>? = null)

/** Where a member of a registered result comes from (decides its documentation and its web page). */
enum class MemberKind {
    /** A return value the module documents. */
    MODULE,

    /** A key the module or its action plugin returns although its documentation omits it ([ResultShapes.SUPPLEMENTS]). */
    SUPPLEMENT,

    /** `stdout_lines`/`stderr_lines`: split by the action plugin from a returned `stdout`/`stderr`. */
    DERIVED,

    /** A common return value every task result has (`changed`, `failed`, `skipped`, `msg` …). */
    COMMON,

    /** `attempts`/`retries` of a retried task. */
    RETRY,

    /** The job keys of an asynchronous task. */
    ASYNC,

    /** `results` of a looping task and the loop's own top-level `msg`. */
    LOOP,

    /** The keys of one item's result that name the item (the loop variable, `ansible_loop_var`, the index …), and the item's own keys. */
    LOOP_ITEM,
}

/**
 * One way a member is documented, and the registering tasks that set it this way. A member set alike by several tasks
 * has one doc listing all of them; tasks that document it differently each add one.
 *
 * @property module the module whose page documents the member ([MemberKind.MODULE], [MemberKind.SUPPLEMENT],
 *   [MemberKind.DERIVED], [MemberKind.ASYNC] job keys documented by `async_status`); null for the others
 * @property returnPath the documented return value's path on [module]'s page (`["stat", "exists"]`, its
 *   `#return-stat/exists` anchor); empty when the page has no anchor for it
 * @property textKey the stable key of the built-in description for members no module documents (`common.changed`,
 *   `loop.results`, `item.loop_var` …); null when [description] holds the text
 * @property description the documentation paragraphs (Ansible doc markup), empty when [textKey] names the text
 * @property returned when the member is present, as documented (`always`, `success`, …)
 * @property sample the documented sample as JSON text (strings as they are)
 * @property tasks indices into [ResultShape.sources] of the tasks that set the member this way, ascending
 */
data class MemberDoc(
    val kind: MemberKind,
    val module: String?,
    val returnPath: List<String>,
    val textKey: String?,
    val type: OptionType,
    val elements: OptionType?,
    val description: List<String>,
    val returned: String?,
    val sample: String?,
    val tasks: List<Int>,
) {
    /** The same documentation regardless of which tasks it belongs to. */
    internal fun sameAs(other: MemberDoc): Boolean = copy(tasks = other.tasks) == other
}

/**
 * One member of a registered result: a key of the result (or of a nested return value) with its type.
 *
 * A `list` of dicts keeps the keys of its elements in [members], like `options` of a role spec list
 * (`results`, `files` of `find`); an integer segment in a [member] path steps into an element.
 *
 * @property type the documented type; `raw` when the tasks that set the member disagree
 * @property members the documented keys of a dict (or of each element of a list of dicts), in presentation order;
 *   null when the value is free-form
 * @property docs how the member is documented, one entry per distinct documentation
 */
class ResultMember(
    val name: String,
    val type: OptionType,
    val elements: OptionType?,
    val members: Map<String, ResultMember>?,
    val docs: List<MemberDoc>,
) {
    /** The registering tasks that set this member, ascending. */
    val tasks: List<Int> get() = docs.flatMapTo(sortedSetOf()) { it.tasks }.toList()

    /** The best documentation: the first one with a module page or a text. */
    val primary: MemberDoc? get() = docs.firstOrNull()

    /** The kinds this member comes from. */
    val kinds: Set<MemberKind> get() = docs.mapTo(LinkedHashSet()) { it.kind }

    /**
     * The member at [path] (keys as written; below a `list` only an integer segment, which addresses an element:
     * [element]); null when a key is not documented or a list is read without an index.
     */
    fun member(path: List<String>): ResultMember? {
        var current = this
        for (segment in path) {
            current = if (current.type == OptionType.List) {
                if (segment.toIntOrNull() == null) return null
                current.element() ?: return null
            } else {
                current.members?.get(segment) ?: return null
            }
        }
        return current
    }

    /**
     * The element of a `list` member: the documented keys of its elements for a list of dicts, else a member of the
     * element type (`str` for `stdout_lines`); null for other types or lists without a documented element type.
     */
    fun element(): ResultMember? {
        if (type != OptionType.List) return null
        if (members != null) return ResultMember(name, OptionType.Dict, null, members, docs)
        val elementType = elements ?: return null
        return ResultMember(name, elementType, null, null, docs)
    }

    /**
     * This member as a role-spec-like option tree (plan "ReturnSpec tree + task features → OptionSpec tree"), for the
     * plugin's option walkers: type, elements, the first description and nested `options`.
     */
    val option: OptionSpec by lazy(LazyThreadSafetyMode.PUBLICATION) {
        OptionSpec(
            name = name,
            type = type,
            elements = elements ?: OptionType.Dict.takeIf { type == OptionType.List && members != null },
            description = docs.firstOrNull { it.description.isNotEmpty() }?.description.orEmpty(),
            options = members?.mapValuesTo(LinkedHashMap()) { it.value.option },
        )
    }

    override fun toString(): String = "ResultMember($name: ${type.name}${elements?.let { "[${it.name}]" } ?: ""}, ${members?.keys ?: "free-form"})"
}

/**
 * The shape of registered variable [name]: the union of the results of the registering [sources] (in the order the
 * caller found them), each member saying which of them set it ([MemberDoc.tasks]).
 */
class ResultShape(val name: String, val sources: List<ResultSource>, val root: ResultMember) {
    /** The documented keys of the result. */
    val members: Map<String, ResultMember> get() = root.members.orEmpty()

    /** The member at [path] below the result (see [ResultMember.member]); the result itself for an empty path. */
    fun member(path: List<String>): ResultMember? = root.member(path)

    override fun toString(): String = "ResultShape($name from ${sources.size} task(s): ${members.keys})"
}
