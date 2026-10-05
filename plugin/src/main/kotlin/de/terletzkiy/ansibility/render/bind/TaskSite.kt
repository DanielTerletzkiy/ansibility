package de.terletzkiy.ansibility.render.bind

import com.intellij.openapi.project.Project
import de.terletzkiy.ansibility.api.RenderContext
import de.terletzkiy.ansibility.model.task.LoopControlInfo
import de.terletzkiy.ansibility.model.task.LoopInfo
import de.terletzkiy.ansibility.model.task.TaskFileModels
import de.terletzkiy.ansibility.model.task.TaskNode
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.resolve.loop.TaskChains
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YValue

/**
 * The task that renders a template, as the binder needs it: its block and task `vars:` (level 15, inner wins), its
 * loop and the template module's options. Read from the task model, so unsaved edits of the task file count.
 */
internal class TaskSite(
    val context: RenderContext,
    val task: TaskNode,
    val siteVars: Map<String, YValue>,
) {
    val loop: LoopInfo? get() = task.loop
    val loopControl: LoopControlInfo? get() = task.loopControl
    val loopVar: String get() = loopControl?.effectiveLoopVar ?: LoopControlInfo.DEFAULT_LOOP_VAR

    /** A template module option (`src`, `dest`, `trim_blocks` …); null for other modules and when unset. */
    fun option(name: String): YValue? = task.module?.args?.option(name)

    /** A literal boolean option of the template module (`trim_blocks: false`); null when unset or templated. */
    fun flag(name: String): Boolean? = when ((option(name) as? YScalar)?.text?.lowercase()) {
        "true", "yes", "on" -> true
        "false", "no", "off" -> false
        else -> null
    }

    /** True for `template:` tasks (the module that writes the file); lookups and includes have no `src` of their own. */
    val isTemplateModule: Boolean get() = task.module?.canonical in TEMPLATE_MODULES

    companion object {
        private val TEMPLATE_MODULES = setOf("ansible.builtin.template", "ansible.windows.win_template")

        /** The task of [context], or null when its file no longer has a task there. Call in a read action. */
        fun of(project: Project, context: RenderContext): TaskSite? {
            val yaml = YamlFiles.yamlFile(project, context.taskSite.file) ?: return null
            val chain = TaskChains.chainAt(TaskFileModels.of(yaml), context.taskSite.offset)
            val task = TaskChains.taskOf(chain) ?: return null
            val vars = LinkedHashMap<String, YValue>()
            for (item in chain) {
                TaskChains.varsOf(item)?.entries?.forEach { vars[it.key.text] = it.value }
            }
            return TaskSite(context, task, vars)
        }
    }
}
