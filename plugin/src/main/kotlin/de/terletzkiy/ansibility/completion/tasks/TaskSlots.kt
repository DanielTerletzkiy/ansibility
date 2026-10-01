package de.terletzkiy.ansibility.completion.tasks

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.api.KeywordLevel
import de.terletzkiy.ansibility.model.task.KeyValueArgs
import de.terletzkiy.ansibility.model.task.KeywordOwner
import de.terletzkiy.ansibility.model.task.TaskFileKind
import de.terletzkiy.ansibility.model.task.TaskModelBuilder
import de.terletzkiy.ansibility.model.task.TaskSyntax
import org.jetbrains.yaml.YAMLTokenTypes
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLQuotedText
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLScalarList
import org.jetbrains.yaml.psi.YAMLScalarText
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * The structural owner of the keys around a completion position in a task-like file (plan F5.7): which keyword
 * set, or which module's option mapping, a key typed there belongs to.
 */
internal sealed interface KeyOwner {
    /** A playbook item: a play, or an `import_playbook` entry once it has its import key. */
    data object Play : KeyOwner

    /** A `block:` item of a task or handler list. */
    data class Block(val handler: Boolean) : KeyOwner

    /** A task or handler; [module] is its module as written, null while it has none. */
    data class Task(val handler: Boolean, val module: String?) : KeyOwner

    /** A mapping entry of a play's `roles:` list. */
    data object RoleEntry : KeyOwner

    /** A task's `loop_control:`. */
    data object LoopControl : KeyOwner

    /** `apply:` of `include_tasks`/`include_role`: the task keywords applied to the included tasks. */
    data object Apply : KeyOwner

    /** The option mapping of [module] at [path]: empty for the module's own mapping and `args:`, else a dict option or a `list[dict]` element. */
    data class Options(val module: String, val path: List<String>) : KeyOwner
}

/** Where the caret is, structurally: a key being typed, a value, or a list element. */
internal sealed interface TaskSlot {
    /** The text typed so far, from the start of the key or value (inside its quotes) to the caret. */
    val prefix: String

    /** A key typed in a mapping of [owner]; [present] are the mapping's other keys. [flow] marks `{…}` mappings. */
    data class Key(val owner: KeyOwner, val present: Set<String>, override val prefix: String, val flow: Boolean) : TaskSlot

    /** The value of [key] in a mapping of [owner], on the key's line. [quoted] when the value is in quotes. */
    data class Value(val owner: KeyOwner, val key: String, override val prefix: String, val quoted: Boolean) : TaskSlot

    /**
     * A scalar item of a list that is the value of option [Options.path] (its last segment is the option): a new
     * `list[dict]` element (its keys are offered) or an element of a list of scalars (its choices are offered).
     */
    data class ListItem(val owner: KeyOwner.Options, override val prefix: String, val quoted: Boolean) : TaskSlot
}

/** The keyword set of a structural owner, and the level its documentation is shown for. */
internal fun KeyOwner.keywordOwner(): KeywordOwner? = when (this) {
    KeyOwner.Play -> KeywordOwner.PLAY
    is KeyOwner.Block -> KeywordOwner.BLOCK
    is KeyOwner.Task -> if (handler) KeywordOwner.HANDLER else KeywordOwner.TASK
    KeyOwner.RoleEntry -> KeywordOwner.ROLE
    KeyOwner.LoopControl -> KeywordOwner.LOOP_CONTROL
    KeyOwner.Apply -> KeywordOwner.TASK
    is KeyOwner.Options -> null
}

/** The documentation level of the keywords of [owner]. */
internal fun KeywordOwner.level(): KeywordLevel = when (this) {
    KeywordOwner.PLAY -> KeywordLevel.PLAY
    KeywordOwner.ROLE -> KeywordLevel.ROLE_ENTRY
    KeywordOwner.BLOCK -> KeywordLevel.BLOCK
    KeywordOwner.TASK -> KeywordLevel.TASK
    KeywordOwner.HANDLER -> KeywordLevel.HANDLER
    KeywordOwner.PLAYBOOK_INCLUDE -> KeywordLevel.PLAYBOOK_INCLUDE
    KeywordOwner.LOOP_CONTROL -> KeywordLevel.LOOP_CONTROL
}

/**
 * Finds the [TaskSlot] of a completion position from the PSI of the completion copy (plan F5.7).
 *
 * Only the ancestors of the caret are read, so a half-typed key never confuses the analysis. The YAML parser keeps
 * the incomplete text in one of four places, and each is a slot:
 * - the key of a key-value (`stat<caret>: x`, `IntellijIdeaRulezzz :`): a key of the enclosing mapping;
 * - a scalar directly inside a mapping (a key without its colon yet): a key of that mapping;
 * - the value of a key-value, on a later line than the key (`ansible.builtin.file:⏎    pa<caret>`): a key of the
 *   mapping that value is going to be; on the key's own line it is that key's value;
 * - a scalar sequence item (`- <caret>`): a key of a new play, task or `list[dict]` element, or an element of a
 *   list of scalars.
 *
 * The owner of a mapping is found by walking up to the document: the top-level sequence holds plays, tasks or
 * handlers ([kind]); plays hold `tasks`/`pre_tasks`/`post_tasks`/`handlers`/`roles`; blocks hold
 * `block`/`rescue`/`always`; a task's module key, `args:` and a mapping `action:` hold the module's options, whose
 * dict and `list[dict]` values nest; `loop_control:` and the `apply:` option of includes hold keywords. Everything
 * else (`vars:`, `module_defaults:`, free-form dicts, role parameters) belongs to no owner, and no slot is reported.
 * Modules are told from keywords with [syntax], the way `TaskModelBuilder` does it.
 */
internal class TaskSlotLocator(private val syntax: TaskSyntax, private val kind: TaskFileKind) {

    /** What a sequence holds. */
    private sealed interface ListKind {
        data object Plays : ListKind
        data class Tasks(val handler: Boolean) : ListKind
        data object Roles : ListKind
        data class Options(val owner: KeyOwner.Options) : ListKind
    }

    /** The slot at [caret] (an offset in [position]'s file), or null when the position is not ours. */
    fun locate(position: PsiElement, caret: Int): TaskSlot? {
        val text = position.containingFile?.viewProvider?.contents ?: return null
        (position.parent as? YAMLKeyValue)?.takeIf { it.key === position }?.let { keyValue ->
            val mapping = keyValue.parentMapping ?: return null
            val prefix = prefix(text, position.textRange.startOffset, caret) ?: return null
            val owner = mappingOwner(mapping, keyValue) ?: return null
            return TaskSlot.Key(owner, presentKeys(mapping, keyValue), prefix, isFlow(mapping))
        }
        val scalar = PsiTreeUtil.getParentOfType(position, YAMLScalar::class.java, false) ?: return null
        if (scalar is YAMLScalarList || scalar is YAMLScalarText) return null
        val quoted = scalar is YAMLQuotedText
        val start = scalar.textRange.startOffset + if (quoted) 1 else 0
        val prefix = prefix(text, start, caret) ?: return null
        return when (val parent = scalar.parent) {
            is YAMLMapping -> if (quoted) null else mappingOwner(parent, null)?.let { TaskSlot.Key(it, presentKeys(parent, null), prefix, isFlow(parent)) }
            is YAMLKeyValue -> {
                val keyEnd = parent.key?.textRange?.endOffset ?: return null
                val mapping = parent.parentMapping ?: return null
                val owner = mappingOwner(mapping, null) ?: return null
                if (text.subSequence(keyEnd, scalar.textRange.startOffset).contains('\n')) {
                    if (quoted) return null
                    childOwner(owner, parent)?.let { TaskSlot.Key(it, emptySet(), prefix, flow = false) }
                } else {
                    TaskSlot.Value(owner, parent.keyText, prefix, quoted)
                }
            }
            is YAMLSequenceItem -> when (val list = (parent.parent as? YAMLSequence)?.let(::listKind)) {
                is ListKind.Options -> TaskSlot.ListItem(list.owner, prefix, quoted)
                // A scalar in `roles:` is a role name, one in a task list may become a task key.
                ListKind.Roles, null -> null
                else -> if (quoted) null else itemOwner(list, null, exclude = null)?.let { TaskSlot.Key(it, emptySet(), prefix, flow = false) }
            }
            else -> null
        }
    }

    // ------------------------------------------------------------------------------------------------ owners

    /** The owner of [mapping]'s keys; [exclude] is the key being typed (never a module key). */
    private fun mappingOwner(mapping: YAMLMapping, exclude: YAMLKeyValue?): KeyOwner? {
        ProgressManager.checkCanceled()
        return when (val parent = mapping.parent) {
            is YAMLSequenceItem -> (parent.parent as? YAMLSequence)?.let(::listKind)?.let { itemOwner(it, mapping, exclude) }
            is YAMLKeyValue -> {
                val outer = parent.parentMapping ?: return null
                mappingOwner(outer, null)?.let { childOwner(it, parent) }
            }
            else -> null
        }
    }

    /** What [sequence] holds, from its place in the file. */
    private fun listKind(sequence: YAMLSequence): ListKind? = when (val parent = sequence.parent) {
        is YAMLDocument -> when (kind) {
            TaskFileKind.PLAYBOOK -> ListKind.Plays
            TaskFileKind.TASKS -> ListKind.Tasks(handler = false)
            TaskFileKind.HANDLERS -> ListKind.Tasks(handler = true)
        }
        is YAMLKeyValue -> {
            val mapping = parent.parentMapping
            when (val owner = mapping?.let { mappingOwner(it, null) }) {
                KeyOwner.Play -> when (parent.keyText) {
                    "tasks", "pre_tasks", "post_tasks" -> ListKind.Tasks(handler = false)
                    "handlers" -> ListKind.Tasks(handler = true)
                    "roles" -> ListKind.Roles
                    else -> null
                }
                is KeyOwner.Block -> if (parent.keyText in BLOCK_SECTIONS) ListKind.Tasks(owner.handler) else null
                is KeyOwner.Options -> (optionChild(owner, parent.keyText) as? KeyOwner.Options)?.let(ListKind::Options)
                else -> null
            }
        }
        else -> null
    }

    /** The owner of the keys of a [mapping] item (null: a scalar item still being typed) of a [list]. */
    private fun itemOwner(list: ListKind, mapping: YAMLMapping?, exclude: YAMLKeyValue?): KeyOwner? = when (list) {
        ListKind.Plays -> KeyOwner.Play
        is ListKind.Tasks -> {
            val keys = mapping?.keyValues.orEmpty().filter { it !== exclude }
            if (keys.any { it.keyText == BLOCK }) KeyOwner.Block(list.handler) else KeyOwner.Task(list.handler, moduleOf(keys, list.handler))
        }
        ListKind.Roles -> if (mapping == null) null else KeyOwner.RoleEntry
        is ListKind.Options -> list.owner
    }

    /** The owner of the mapping that is (or is going to be) the value of [keyValue] in a mapping of [owner]. */
    private fun childOwner(owner: KeyOwner, keyValue: YAMLKeyValue): KeyOwner? {
        val key = keyValue.keyText
        return when (owner) {
            is KeyOwner.Task -> when {
                key == LOOP_CONTROL -> KeyOwner.LoopControl
                key == ARGS -> owner.module?.let { KeyOwner.Options(it, emptyList()) }
                key == ACTION || key == LOCAL_ACTION -> actionModule(keyValue)?.let { KeyOwner.Options(it, emptyList()) }
                owner.module != null && key == owner.module -> KeyOwner.Options(owner.module, emptyList())
                else -> null
            }
            is KeyOwner.Options -> optionChild(owner, key)
            else -> null
        }
    }

    /** The owner below option [key] of [owner]: `apply:` of includes holds keywords, `vars:` holds variables. */
    private fun optionChild(owner: KeyOwner.Options, key: String): KeyOwner? {
        if (key == VARS) return null
        if (owner.path.isEmpty() && key == APPLY && syntax.canonicalModule(owner.module) in APPLY_MODULES) return KeyOwner.Apply
        return KeyOwner.Options(owner.module, owner.path + key)
    }

    /**
     * The module key among a task's [keys], as `TaskModelBuilder` picks it: the keys that are neither keywords nor
     * `with_<lookup>` loops are candidates, and a known module wins over the others. `action:`/`local_action:`
     * name the module in their value.
     */
    private fun moduleOf(keys: List<YAMLKeyValue>, handler: Boolean): String? {
        val owner = if (handler) KeywordOwner.HANDLER else KeywordOwner.TASK
        keys.firstOrNull { it.keyText == ACTION || it.keyText == LOCAL_ACTION }?.let { return actionModule(it) }
        val candidates = keys.map { it.keyText }.filter { key ->
            !syntax.isKeyword(owner, key) && !(key.startsWith(TaskSyntax.WITH_PREFIX) && key.length > TaskSyntax.WITH_PREFIX.length)
        }
        return candidates.firstOrNull { syntax.isKnownModule(it) } ?: candidates.firstOrNull()
    }

    /** The module named by `action: copy src=a` or `action: {module: copy, …}`. */
    private fun actionModule(keyValue: YAMLKeyValue): String? = when (val value = keyValue.value) {
        is YAMLMapping -> (value.getKeyValueByKey(ACTION_MODULE_KEY)?.value as? YAMLScalar)?.textValue?.takeIf { it.isNotBlank() }
        is YAMLScalar -> {
            val text = value.textValue
            KeyValueArgs.words(text).firstOrNull()?.let { text.substring(it.first, it.second) }
        }
        else -> null
    }

    private fun presentKeys(mapping: YAMLMapping, exclude: YAMLKeyValue?): Set<String> =
        mapping.keyValues.filter { it !== exclude }.mapTo(LinkedHashSet()) { it.keyText }

    private fun isFlow(mapping: YAMLMapping): Boolean = mapping.node.firstChildNode?.elementType == YAMLTokenTypes.LBRACE

    /** The text from [start] to [caret]; null when it spans lines or the caret is before the start. */
    private fun prefix(text: CharSequence, start: Int, caret: Int): String? {
        if (caret < start || caret > text.length) return null
        val prefix = text.subSequence(start, caret).toString()
        return if ('\n' in prefix) null else prefix
    }

    companion object {
        private const val BLOCK = "block"
        private const val ARGS = "args"
        private const val ACTION = "action"
        private const val LOCAL_ACTION = "local_action"
        private const val ACTION_MODULE_KEY = "module"
        private const val LOOP_CONTROL = "loop_control"
        private const val VARS = "vars"
        private const val APPLY = "apply"

        /** The task lists of a block. */
        val BLOCK_SECTIONS: Set<String> = setOf("block", "rescue", "always")

        /** The modules whose `apply:` option holds task keywords. */
        private val APPLY_MODULES = setOf(TaskModelBuilder.INCLUDE_TASKS, TaskModelBuilder.INCLUDE_ROLE)
    }
}
