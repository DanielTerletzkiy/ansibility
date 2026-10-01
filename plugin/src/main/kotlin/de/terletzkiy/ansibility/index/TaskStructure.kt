package de.terletzkiy.ansibility.index

import com.intellij.openapi.progress.ProgressManager
import de.terletzkiy.ansibility.semantics.yaml.YEntry
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue

/**
 * The playbook keywords that are not module names, as ansible-core 2.18 and later define them
 * (`Task`, `Handler`, `Block`, `Play`, `PlaybookInclude` and `Role` field attributes, read from the bundled doc
 * snapshots), plus `listen` and every `with_<lookup>` key.
 */
object TaskKeywords {
    /** Task and handler keywords (`with_<lookup>` is matched by prefix). */
    val TASK: Set<String> = setOf(
        "action", "any_errors_fatal", "args", "async", "become", "become_exe", "become_flags", "become_method",
        "become_user", "changed_when", "check_mode", "collections", "connection", "debugger", "delay", "delegate_facts",
        "delegate_to", "diff", "environment", "failed_when", "ignore_errors", "ignore_unreachable", "listen",
        "local_action", "loop", "loop_control", "module_defaults", "name", "no_log", "notify", "poll", "port", "register",
        "remote_user", "retries", "run_once", "tags", "throttle", "timeout", "until", "vars", "when",
    )

    /** Names under which ansible-core runs `set_fact`. */
    val SET_FACT: Set<String> = setOf("set_fact", "ansible.builtin.set_fact", "ansible.legacy.set_fact")

    /** Names under which ansible-core runs `template`. */
    val TEMPLATE: Set<String> = setOf("template", "ansible.builtin.template", "ansible.legacy.template")

    /** `include_role` under all its names. */
    val INCLUDE_ROLE: Set<String> = setOf("include_role", "ansible.builtin.include_role", "ansible.legacy.include_role")

    /** `import_role` under all its names. */
    val IMPORT_ROLE: Set<String> = setOf("import_role", "ansible.builtin.import_role", "ansible.legacy.import_role")

    /** `import_playbook` under all its names. */
    val IMPORT_PLAYBOOK: Set<String> =
        setOf("import_playbook", "ansible.builtin.import_playbook", "ansible.legacy.import_playbook")

    /** Keys that hold task lists inside a play, and whether they are handlers. */
    val PLAY_TASK_SECTIONS: Map<String, Boolean> =
        mapOf("pre_tasks" to false, "tasks" to false, "post_tasks" to false, "handlers" to true)

    /** Keys that hold task lists inside a block. */
    val BLOCK_SECTIONS: List<String> = listOf("block", "rescue", "always")

    fun isTaskKeyword(key: String): Boolean = key in TASK || key.startsWith("with_")

    /**
     * The action of [task]: the one key that is not a keyword, as ansible-core's `ModuleArgsParser` finds it. When a
     * typo adds a second non-keyword key, the key that looks like an FQCN wins if it is the only one; otherwise there is
     * no action. `action:` and `local_action:` give the module named in their value.
     */
    fun action(task: YMap): TaskAction? {
        val candidates = task.entries.filter { !isTaskKeyword(it.key.text) }
        val entry = when (candidates.size) {
            0 -> null
            1 -> candidates[0]
            else -> candidates.singleOrNull { '.' in it.key.text }
        }
        if (entry != null) return TaskAction(entry.key.text, entry.key, entry.value)
        val legacy = task.entries.lastOrNull { it.key.text == "action" || it.key.text == "local_action" } ?: return null
        return when (val value = legacy.value) {
            is YScalar -> {
                val module = value.text.trim().substringBefore(' ').substringBefore('\n').trim()
                if (module.isEmpty()) null else TaskAction(module, value, YScalar(value.text.trim().removePrefix(module).trim(), value.style, range = value.range))
            }
            is YMap -> {
                val module = (value["module"] as? YScalar) ?: return null
                val args = YMap(value.entries.filter { it.key.text != "module" }, value.range)
                TaskAction(module.text, module, args)
            }
            else -> null
        }
    }
}

/**
 * The module a task runs: its [name] as written (`ansible.builtin.template`, `template`), the node that names it
 * ([nameNode], a key or the value of `action:`) and its arguments ([args]: a mapping, a free-form string or empty).
 */
class TaskAction(val name: String, val nameNode: YScalar, val args: YValue) {
    /** The argument mapping, including the task's `args:` keyword, which ansible-core merges under the inline ones. */
    fun argsMap(task: YMap): YMap? {
        val inline = args as? YMap
        val extra = task["args"] as? YMap
        return when {
            inline != null && extra != null -> YMap(extra.entries + inline.entries, inline.range)
            else -> inline ?: extra
        }
    }

    /** A free-form argument string (`set_fact: a=1 b=2`, `template: src=x dest=y`), or null. */
    fun freeForm(): String? = (args as? YScalar)?.text?.takeIf { it.isNotBlank() }
}

/** Callbacks of [TaskWalker]; every method has an empty default. */
interface TaskVisitor {
    /** A play: a top-level mapping with `hosts:`. */
    fun play(play: YMap) {}

    /** A top-level `import_playbook` entry; [target] is its value. */
    fun playbookImport(entry: YMap, key: YScalar, target: YValue) {}

    /** An entry of a play's `roles:` list (a mapping or a bare role name). */
    fun roleEntry(entry: YValue, play: YMap) {}

    /** A block (a mapping with `block:`); [play] is the enclosing play, null in task files. */
    fun block(block: YMap, handlers: Boolean, play: YMap?) {}

    /** A task or handler; [play] is the enclosing play, null in task files. */
    fun task(task: YMap, handlers: Boolean, play: YMap?) {}
}

/**
 * Walks the task structure of a document whose top level is a sequence: a playbook (plays, playbook imports) or a
 * task file (tasks and blocks, handlers when [handlerFile] is set). Purely structural, like ansible-core's loader:
 * a top-level mapping with `hosts:` is a play, one with `import_playbook` is an import, anything else is a task.
 */
class TaskWalker(private val visitor: TaskVisitor, private val handlerFile: Boolean = false) {
    fun walk(document: YValue?) {
        val items = (document as? YSeq)?.items ?: return
        for (item in items) {
            ProgressManager.checkCanceled()
            val map = item as? YMap ?: continue
            val import = map.entries.lastOrNull { it.key.text in TaskKeywords.IMPORT_PLAYBOOK }
            when {
                map["hosts"] != null -> play(map)
                import != null -> visitor.playbookImport(map, import.key, import.value)
                else -> taskOrBlock(map, handlerFile, null)
            }
        }
    }

    private fun play(play: YMap) {
        visitor.play(play)
        (play["roles"] as? YSeq)?.items?.forEach { visitor.roleEntry(it, play) }
        for ((section, handlers) in TaskKeywords.PLAY_TASK_SECTIONS) {
            tasks(play[section], handlers, play)
        }
    }

    private fun tasks(list: YValue?, handlers: Boolean, play: YMap?) {
        val items = (list as? YSeq)?.items ?: return
        for (item in items) {
            ProgressManager.checkCanceled()
            (item as? YMap)?.let { taskOrBlock(it, handlers, play) }
        }
    }

    private fun taskOrBlock(map: YMap, handlers: Boolean, play: YMap?) {
        if (map["block"] != null) {
            visitor.block(map, handlers, play)
            for (section in TaskKeywords.BLOCK_SECTIONS) tasks(map[section], handlers, play)
        } else {
            visitor.task(map, handlers, play)
        }
    }
}

/** The entries of [map] with the last one of each key, in first-seen order: what PyYAML (and so Ansible) loads. */
internal fun effectiveEntries(map: YMap): Collection<YEntry> {
    if (map.entries.size < 2) return map.entries
    val byKey = LinkedHashMap<String, YEntry>()
    for (entry in map.entries) byKey[entry.key.text] = entry
    return byKey.values
}

/** Parses `k=v k2='v 2'` free-form arguments into key → value text, the way ansible-core's `parse_kv` splits them. */
internal fun parseKeyValueArgs(text: String): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    for (token in splitArgs(text)) {
        val eq = token.indexOf('=')
        if (eq <= 0) continue
        val key = token.substring(0, eq)
        if (!key.all { it.isLetterOrDigit() || it == '_' }) continue
        result[key] = token.substring(eq + 1).removeSurrounding("\"").removeSurrounding("'")
    }
    return result
}

/** Splits on whitespace outside quotes and outside `{{ }}`/`{% %}`. */
private fun splitArgs(text: String): List<String> {
    val tokens = ArrayList<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var depth = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            quote != null -> {
                current.append(c)
                if (c == quote) quote = null
            }
            depth == 0 && (c == '"' || c == '\'') -> {
                quote = c
                current.append(c)
            }
            c == '{' && i + 1 < text.length && (text[i + 1] == '{' || text[i + 1] == '%') -> {
                depth++
                current.append(c).append(text[i + 1])
                i++
            }
            depth > 0 && (c == '}' || c == '%') && i + 1 < text.length && text[i + 1] == '}' -> {
                depth--
                current.append(c).append(text[i + 1])
                i++
            }
            depth == 0 && c.isWhitespace() -> {
                if (current.isNotEmpty()) tokens += current.toString()
                current.setLength(0)
            }
            else -> current.append(c)
        }
        i++
    }
    if (current.isNotEmpty()) tokens += current.toString()
    return tokens
}
