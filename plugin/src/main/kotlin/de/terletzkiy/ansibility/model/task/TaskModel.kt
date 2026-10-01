package de.terletzkiy.ansibility.model.task

import com.intellij.openapi.util.TextRange
import de.terletzkiy.ansibility.semantics.yaml.YEntry
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YValue

/*
 * The task model (plan A.5 "Task model"): YAML task lists, handler files and playbooks classified into plays,
 * blocks, tasks and handlers. Every node is PSI-free and carries the file offsets of what it was built from
 * (TextRange, and the ranges inside the YValue/YEntry parts), so consumers find the PSI with
 * `ModelAnchors.element` or `PsiTreeUtil.findElementOfClassAtRange` in the file the model was built for.
 * A model is valid until its file changes; `TaskFileModels` caches it per file.
 */

/** A name written in a file: the loaded scalar text and the range of the scalar that holds it. */
data class NameRef(val text: String, val range: TextRange)

/** How a file's top-level sequence is read. */
enum class TaskFileKind {
    /** A list of plays and `import_playbook` entries. */
    PLAYBOOK,

    /** A list of tasks and blocks (role `tasks/`, molecule `*_tasks.yml`, included task files). */
    TASKS,

    /** A list of handlers (role `handlers/`). */
    HANDLERS,
}

/** One parsed file. */
class TaskFileModel(
    val kind: TaskFileKind,
    /** False when the first document is not a sequence (empty, a mapping, a scalar): nothing else is filled. */
    val isSequence: Boolean,
    /** The plays of a playbook, in file order. */
    val plays: List<PlayNode>,
    /** The `import_playbook` entries of a playbook, in file order. */
    val imports: List<PlaybookImportNode>,
    /** The top-level tasks and blocks of a task or handler file; empty for playbooks. */
    val items: List<TaskItem>,
) {
    /** Every task of the file, depth first in source order (plays' sections, then blocks' block/rescue/always). */
    fun tasks(): List<TaskNode> {
        val result = ArrayList<TaskNode>()
        fun visit(items: List<TaskItem>) {
            for (item in items) when (item) {
                is TaskNode -> result += item
                is BlockNode -> {
                    visit(item.block)
                    visit(item.rescue)
                    visit(item.always)
                }
            }
        }
        visit(items)
        plays.forEach { play -> play.sections().forEach { visit(it) } }
        return result
    }

    /** The innermost task or block whose mapping contains [offset]. */
    fun itemAt(offset: Int): TaskItem? {
        fun find(items: List<TaskItem>): TaskItem? {
            val item = items.firstOrNull { it.range.containsOffset(offset) } ?: return null
            if (item is BlockNode) {
                (find(item.block) ?: find(item.rescue) ?: find(item.always))?.let { return it }
            }
            return item
        }
        find(items)?.let { return it }
        val play = playAt(offset) ?: return null
        return play.sections().firstNotNullOfOrNull { find(it) }
    }

    /** The play whose mapping contains [offset]. */
    fun playAt(offset: Int): PlayNode? = plays.firstOrNull { it.range.containsOffset(offset) }

    companion object {
        /** The model of a file that is not a sequence. */
        fun empty(kind: TaskFileKind): TaskFileModel = TaskFileModel(kind, false, emptyList(), emptyList(), emptyList())
    }
}

/** An implicit Jinja expression: `when`, `changed_when`, `failed_when`, `until`, `loop_control.break_when`, `assert.that`, `debug.var`. */
data class ImplicitExpression(
    /** The key that makes the value an expression (`when`, `that`, `var`, …). */
    val key: String,
    /** The expression scalar (one per list item for list values). */
    val value: YScalar,
    val range: TextRange,
)

/** A task or a block: an item of a task list. */
sealed interface TaskItem {
    /** The item's mapping. */
    val range: TextRange
    val name: NameRef?

    /** The item's keyword entries by key (the last one of duplicates, as PyYAML loads them). */
    val keywords: Map<String, YEntry>

    /** Keys that are neither keywords nor the module key (misplaced options, typos, conflicting module keys). */
    val unknownKeys: List<YEntry>

    /** Implicit expressions of the item (`when` for blocks; all of them for tasks). */
    val expressions: List<ImplicitExpression>
    val tags: List<NameRef>

    /** The keys of the item's `vars:` mapping. */
    val vars: List<NameRef>

    /** True for handlers and for blocks and tasks inside a handler list. */
    val isHandler: Boolean

    /** The `when` expressions only. */
    val conditions: List<ImplicitExpression> get() = expressions.filter { it.key == "when" }
}

/** A `block:` with its `rescue:` and `always:` lists. */
data class BlockNode(
    override val range: TextRange,
    override val name: NameRef?,
    val block: List<TaskItem>,
    val rescue: List<TaskItem>,
    val always: List<TaskItem>,
    override val keywords: Map<String, YEntry>,
    override val unknownKeys: List<YEntry>,
    override val expressions: List<ImplicitExpression>,
    override val tags: List<NameRef>,
    override val vars: List<NameRef>,
    val notify: List<NameRef>,
    override val isHandler: Boolean,
) : TaskItem

/** How a task names its module. */
enum class ModuleForm {
    /** `ansible.builtin.copy: …`, the only form in the target repo. */
    KEY,

    /** `action: copy src=a` or `action: {module: copy, src: a}`. */
    ACTION,

    /** `local_action: …`: like [ACTION], delegated to localhost. */
    LOCAL_ACTION,
}

/** The module a task calls and its arguments. */
data class ModuleCall(
    /** The module name as written (`ansible.builtin.systemd`). */
    val name: String,
    /** The module key, or for [ModuleForm.ACTION]/[ModuleForm.LOCAL_ACTION] the value naming the module. */
    val nameRange: TextRange,
    /** The canonical FQCN after routing (`ansible.builtin.systemd_service`). */
    val canonical: String,
    val form: ModuleForm,
    val args: ModuleArgs,
)

/** Module arguments the way ansible-core's `ModuleArgsParser` merges them. */
data class ModuleArgs(
    /**
     * The effective options: the task's `args:` mapping, overridden by the module's own mapping or by the
     * `k=v` words of a string value. Entries made from `k=v` words get ranges inside the string, and their values
     * are `!!str`-tagged scalars, because ansible-core passes them to the module as strings.
     */
    val options: Map<String, YEntry>,
    /** The free-form text (`_raw_params`): the words of a string value that are not options; null when there are none. */
    val rawParams: String?,
    /** The string that holds [rawParams], for navigation (`include_tasks: setup.yml`). */
    val rawParamsValue: YScalar?,
    /** The module value as written: a mapping, a string or empty. */
    val value: YValue,
    /** The task's `args:` keyword entry, when present. */
    val argsKeyword: YEntry?,
) {
    /** The value of option [name]. */
    fun option(name: String): YValue? = options[name]?.value
}

/** A `loop:` or `with_<lookup>:` loop. */
data class LoopInfo(
    /** The loop key (`loop`, `with_items`, `with_dict` …). */
    val key: YScalar,
    /** What is looped over: a Jinja string or a literal list. */
    val value: YValue,
    /** The lookup of a `with_<lookup>` loop (`items`, `dict`); null for `loop`. */
    val lookup: String?,
) {
    val keyword: String get() = key.text
}

/** A task's `loop_control:`. */
data class LoopControlInfo(
    val entry: YEntry,
    val loopVar: NameRef?,
    val indexVar: NameRef?,
    val label: YValue?,
    val extended: YValue?,
    val extendedAllItems: YValue?,
    val pause: YValue?,
) {
    /** The loop variable name: `loop_var`, or `item`. */
    val effectiveLoopVar: String get() = loopVar?.text ?: DEFAULT_LOOP_VAR

    companion object {
        const val DEFAULT_LOOP_VAR: String = "item"
    }
}

/** Dynamic include or static import. */
enum class IncludeKind { INCLUDE, IMPORT }

/** An `include_tasks`/`import_tasks` call. */
data class TaskFileInclude(
    val kind: IncludeKind,
    /** The task file as written (free-form string or the `file` option); null when missing. */
    val file: NameRef?,
    /** The `apply:` option of `include_tasks`. */
    val apply: YEntry?,
)

/** An `include_role`/`import_role` call. */
data class RoleIncludeCall(
    val kind: IncludeKind,
    val name: NameRef?,
    val tasksFrom: NameRef?,
    val handlersFrom: NameRef?,
    val varsFrom: NameRef?,
    val defaultsFrom: NameRef?,
    val apply: YEntry?,
    val public: YValue?,
    /** The task-level `vars:` entry, which carries the role's parameters. */
    val vars: YEntry?,
)

/** The shape of a `src` value (plan A.7 `ansible.template.use`). */
enum class SrcKind {
    /** No Jinja: `templates/haproxy.cfg.j2`. */
    STATIC,

    /** Static text, then Jinja: `templates/nginx/{{ item.template }}`; see [SrcRef.staticPrefix]. */
    DYNAMIC_PREFIX,

    /** One whole expression: `{{ item.floating.ssl.client_cert_ca_src }}`. */
    WHOLE_VAR,

    /** Any other templated value, e.g. `{{ a }}/{{ b }}`. */
    TEMPLATED,
}

/** The `src` of a `template` or `copy` task. */
data class SrcRef(
    /** Canonical module FQCN (`ansible.builtin.template` or `ansible.builtin.copy`). */
    val module: String,
    val value: YScalar,
    val kind: SrcKind,
    /** The text before the first Jinja delimiter; the whole text for [SrcKind.STATIC]. */
    val staticPrefix: String,
    /** `remote_src: true` on `copy` (the source is on the managed host). */
    val remoteSrc: Boolean,
) {
    val text: String get() = value.text
    val isCopy: Boolean get() = module == TaskModelBuilder.COPY
}

/** One task or handler. */
data class TaskNode(
    override val range: TextRange,
    override val name: NameRef?,
    /** The module call; null when the task names no module (or only keywords). */
    val module: ModuleCall?,
    override val keywords: Map<String, YEntry>,
    override val unknownKeys: List<YEntry>,
    val loop: LoopInfo?,
    val loopControl: LoopControlInfo?,
    val register: NameRef?,
    val notify: List<NameRef>,
    /** `listen:` topics (handlers only). */
    val listen: List<NameRef>,
    override val expressions: List<ImplicitExpression>,
    override val tags: List<NameRef>,
    override val vars: List<NameRef>,
    val delegateTo: YEntry?,
    val become: YEntry?,
    val taskInclude: TaskFileInclude?,
    val roleInclude: RoleIncludeCall?,
    val src: SrcRef?,
    override val isHandler: Boolean,
) : TaskItem {
    /** The loop variable in effect: `loop_control.loop_var`, or `item` when the task loops; null without a loop. */
    val loopVar: String? get() = if (loop == null) null else loopControl?.effectiveLoopVar ?: LoopControlInfo.DEFAULT_LOOP_VAR
}

/** The `hosts:` of a play. */
data class HostsRef(
    /** The pattern text; a list is joined with `,`. */
    val pattern: String,
    val value: YValue,
    val range: TextRange,
)

/** An entry of a play's `roles:` list. */
data class RoleEntryNode(
    /** The role name or path (`role:`, `name:`, or the string item). */
    val name: NameRef?,
    val range: TextRange,
    val keywords: Map<String, YEntry>,
    /** Role parameters: the keys that are not role keywords. */
    val params: List<YEntry>,
    val tags: List<NameRef>,
    val expressions: List<ImplicitExpression>,
    val vars: List<NameRef>,
)

/** A play. */
data class PlayNode(
    /** 0-based index among the plays of the file. */
    val index: Int,
    /** 0-based index of the item in the file's top-level sequence. */
    val itemIndex: Int,
    val range: TextRange,
    val name: NameRef?,
    val hosts: HostsRef?,
    val roles: List<RoleEntryNode>,
    val preTasks: List<TaskItem>,
    val tasks: List<TaskItem>,
    val postTasks: List<TaskItem>,
    val handlers: List<TaskItem>,
    val vars: List<NameRef>,
    val varsFiles: List<NameRef>,
    val tags: List<NameRef>,
    val keywords: Map<String, YEntry>,
    val unknownKeys: List<YEntry>,
) {
    /** pre_tasks, tasks, post_tasks and handlers, in that order. */
    fun sections(): List<List<TaskItem>> = listOf(preTasks, tasks, postTasks, handlers)
}

/** An `import_playbook` entry of a playbook. */
data class PlaybookImportNode(
    val itemIndex: Int,
    val range: TextRange,
    /** The import key as written (`import_playbook`, `ansible.builtin.import_playbook`). */
    val key: YScalar,
    /** The imported path as written; null when missing. */
    val path: NameRef?,
    val name: NameRef?,
    val keywords: Map<String, YEntry>,
    val expressions: List<ImplicitExpression>,
    val tags: List<NameRef>,
    val vars: List<NameRef>,
)
