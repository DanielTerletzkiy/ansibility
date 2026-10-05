package de.terletzkiy.ansibility.resolve.register

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.RoleInfo
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.model.task.TaskFileModel
import de.terletzkiy.ansibility.model.task.TaskFileModels
import de.terletzkiy.ansibility.model.task.TaskItem
import de.terletzkiy.ansibility.model.task.TaskNode
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.resolve.loop.TaskChains
import org.jetbrains.yaml.psi.YAMLFile

/**
 * Where the own role's runtime names (`register`, `set_fact`) stop being visible: at [offset] of [file] in role
 * order. The names of the task spanning [ownTask] (the caret's own task) are set only after it ran, so they are not
 * visible inside it, except a `register` of the task spanning [task] (the caret's task when the caret is in its
 * `until`, `changed_when` or `failed_when`).
 */
internal class Cutoff(val file: VirtualFile, val offset: Int, val task: TextRange?, val ownTask: TextRange? = null)

/** A role task in [file] that includes another task file ([chain] is the task with its enclosing blocks). */
internal class IncludeSite(val file: VirtualFile, val yaml: YAMLFile, val task: TaskNode, val chain: List<TaskItem>)

/** A `register`/`set_fact` name of a role task file. */
internal class RuntimeName(val name: String, val isRegister: Boolean, val location: SourceLocation)

/**
 * The execution order of a role's task files (plan F1.6 "role order"): `tasks/main.yml` first, static
 * `include_tasks`/`import_tasks` expanded where they are written. A position's key is the list of include offsets
 * leading to its file plus its own offset; positions compare lexicographically. Files not reachable from `main.yml`
 * (dynamic includes, `tasks_from` entry points) have no key and count as "earlier" (permissive).
 */
internal class RoleTaskOrder(private val project: Project, private val role: RoleInfo) {
    private val keys: Map<VirtualFile, List<Int>> by lazy(LazyThreadSafetyMode.NONE) { computeKeys() }

    /** Task file → the role's `include_tasks`/`import_tasks` tasks that include it statically. */
    private val includers: Map<VirtualFile, List<IncludeSite>> by lazy(LazyThreadSafetyMode.NONE) { computeIncluders() }

    /**
     * The include tasks whose included files lead to [file], nearest first (an include of an include is further
     * away); each file's includers are visited once.
     */
    fun enclosingIncludes(file: VirtualFile): List<IncludeSite> {
        val result = ArrayList<IncludeSite>()
        val seen = hashSetOf(file)
        var level = listOf(file)
        while (level.isNotEmpty() && result.size < MAX_INCLUDES) {
            ProgressManager.checkCanceled()
            val next = ArrayList<VirtualFile>()
            for (target in level) {
                for (site in includers[target].orEmpty()) {
                    result += site
                    if (seen.add(site.file)) next += site.file
                }
            }
            level = next
        }
        return result
    }

    /** Every `register` and `set_fact` name of the role's task files, in file order. */
    fun runtimeNames(): List<RuntimeName> = role.taskFiles.flatMap { file ->
        ProgressManager.checkCanceled()
        YamlFiles.yamlFile(project, file)?.let { runtimeNamesOf(file, TaskFileModels.of(it)) }.orEmpty()
    }

    /** True when [name] is set before [cutoff] in role order (or by the cutoff's own task, for registers). */
    fun isVisible(name: RuntimeName, cutoff: Cutoff): Boolean {
        val location = name.location
        if (location.file == cutoff.file) return isVisibleInFile(name, cutoff)
        val defKey = keys[location.file] ?: return true
        val cutKey = keys[cutoff.file] ?: return true
        val a = defKey + location.offset
        val b = cutKey + cutoff.offset
        for (i in 0 until minOf(a.size, b.size)) {
            if (a[i] != b[i]) return a[i] < b[i]
        }
        return false
    }

    private fun computeIncluders(): Map<VirtualFile, List<IncludeSite>> {
        val result = HashMap<VirtualFile, MutableList<IncludeSite>>()
        for (file in role.taskFiles) {
            ProgressManager.checkCanceled()
            val yaml = YamlFiles.yamlFile(project, file) ?: continue
            val model = TaskFileModels.of(yaml)
            for (task in model.tasks()) {
                val target = includeTarget(file, task) ?: continue
                result.getOrPut(target) { ArrayList(1) } += IncludeSite(file, yaml, task, TaskChains.chainAt(model, task.range.startOffset))
            }
        }
        return result
    }

    /** The task file a static `include_tasks`/`import_tasks` of [task] in [file] names, if it exists. */
    private fun includeTarget(file: VirtualFile, task: TaskNode): VirtualFile? {
        val include = task.taskInclude?.file?.text?.trim() ?: return null
        if (include.contains("{{") || include.contains("{%")) return null
        return file.parent?.findFileByRelativePath(include)?.takeIf { !it.isDirectory }
            ?: role.ref.dir.findFileByRelativePath("$TASKS/$include")?.takeIf { !it.isDirectory }
    }

    private fun computeKeys(): Map<VirtualFile, List<Int>> {
        val result = HashMap<VirtualFile, List<Int>>()
        val main = role.taskFiles.firstOrNull { it.nameWithoutExtension == MAIN && it.parent?.name == TASKS } ?: return result
        fun visit(file: VirtualFile, key: List<Int>) {
            if (file in result || key.size > MAX_DEPTH) return
            result[file] = key
            val yaml = YamlFiles.yamlFile(project, file) ?: return
            for (task in TaskFileModels.of(yaml).tasks()) {
                ProgressManager.checkCanceled()
                val target = includeTarget(file, task) ?: continue
                visit(target, key + task.range.startOffset)
            }
        }
        visit(main, emptyList())
        return result
    }

    companion object {
        private const val MAIN = "main"
        private const val TASKS = "tasks"
        private const val SET_FACT = "ansible.builtin.set_fact"
        private const val CACHEABLE = "cacheable"
        private const val MAX_DEPTH = 16
        private const val MAX_INCLUDES = 32

        /** The `register` names and `set_fact` keys of the tasks in [model] (the model of [file]), in file order. */
        fun runtimeNamesOf(file: VirtualFile, model: TaskFileModel): List<RuntimeName> {
            val result = ArrayList<RuntimeName>()
            for (task in model.tasks()) {
                ProgressManager.checkCanceled()
                task.register?.let { result += RuntimeName(it.text, true, SourceLocation(file, it.range.startOffset)) }
                val module = task.module ?: continue
                if (module.canonical != SET_FACT) continue
                for ((key, entry) in module.args.options) {
                    if (key == CACHEABLE) continue
                    result += RuntimeName(key, false, SourceLocation(file, entry.key.range?.start ?: task.range.startOffset))
                }
            }
            return result
        }

        /**
         * Visibility of [name] at [cutoff] in the cutoff's own file: before it and outside the cutoff's own task, or a
         * register of the cutoff's task in its result keys.
         */
        fun isVisibleInFile(name: RuntimeName, cutoff: Cutoff): Boolean {
            val offset = name.location.offset
            if (name.isRegister && cutoff.task?.containsOffset(offset) == true) return true
            if (cutoff.ownTask?.containsOffset(offset) == true) return false
            return offset < cutoff.offset
        }
    }
}
