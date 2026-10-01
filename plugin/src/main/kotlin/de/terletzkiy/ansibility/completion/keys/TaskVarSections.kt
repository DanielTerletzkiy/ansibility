package de.terletzkiy.ansibility.completion.keys

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import de.terletzkiy.ansibility.index.TaskKeywords
import de.terletzkiy.ansibility.model.task.BlockNode
import de.terletzkiy.ansibility.model.task.ModuleForm
import de.terletzkiy.ansibility.model.task.NameRef
import de.terletzkiy.ansibility.model.task.TaskFileModels
import de.terletzkiy.ansibility.model.task.TaskItem
import de.terletzkiy.ansibility.model.task.TaskNode
import de.terletzkiy.ansibility.semantics.yaml.YEntry
import org.jetbrains.yaml.psi.YAMLFile

/**
 * The places of a task file or playbook whose keys are variables, read from its task model: the same places the
 * variables classifier (`vars.VarKeySites`) treats as variable keys, but found by their *owner* so that the first key of
 * an empty `vars:` can be completed too.
 *
 * - [sections]: the key of every `vars:` keyword (play, block, task, `include_role`/`import_role`, role entry,
 *   playbook import) and the module key of every `set_fact` task in key form, by the key's start offset, with the
 *   roles the variables are meant for (the play's roles, the included role, the role entry's role);
 * - [roleEntries]: the mapping of every `roles:` entry, whose non-keyword keys are role parameters, with its role.
 *
 * Built per completion from the completion copy (the model is cached on the copy itself). Call in a read action.
 */
internal class TaskVarSections private constructor(
    val sections: Map<Int, List<String>>,
    val roleEntries: Map<TextRange, String>,
) {
    companion object {
        fun of(file: YAMLFile, ownRole: String?): TaskVarSections {
            val model = TaskFileModels.of(file)
            val sections = HashMap<Int, List<String>>()
            val roleEntries = HashMap<TextRange, String>()
            val own = listOfNotNull(ownRole)

            fun addVars(keywords: Map<String, YEntry>, roles: List<String>) {
                keywords["vars"]?.key?.range?.let { sections[it.start] = roles }
            }

            fun visit(items: List<TaskItem>, roles: List<String>) {
                for (item in items) {
                    ProgressManager.checkCanceled()
                    when (item) {
                        is BlockNode -> {
                            addVars(item.keywords, roles)
                            visit(item.block, roles)
                            visit(item.rescue, roles)
                            visit(item.always, roles)
                        }
                        is TaskNode -> {
                            val included = item.roleInclude?.name?.let(::roleName)
                            addVars(item.keywords, if (included != null) listOf(included) else roles)
                            val module = item.module ?: continue
                            val isSetFact = module.name in TaskKeywords.SET_FACT || module.canonical in TaskKeywords.SET_FACT
                            if (isSetFact && module.form == ModuleForm.KEY) sections[module.nameRange.startOffset] = roles
                        }
                    }
                }
            }

            visit(model.items, own)
            for (play in model.plays) {
                val playRoles = play.roles.mapNotNull { entry -> entry.name?.let(::roleName) }
                addVars(play.keywords, (playRoles + own).distinct())
                for (entry in play.roles) {
                    val role = entry.name?.let(::roleName) ?: continue
                    addVars(entry.keywords, listOf(role))
                    roleEntries[entry.range] = role
                }
                play.sections().forEach { visit(it, (playRoles + own).distinct()) }
            }
            model.imports.forEach { addVars(it.keywords, emptyList()) }
            return TaskVarSections(sections, roleEntries)
        }

        /** The role name of a reference as written: `haproxy`, or the last segment of `/ansible/roles/haproxy`. */
        private fun roleName(reference: NameRef): String? =
            reference.text.trimEnd('/').substringAfterLast('/').takeIf { it.isNotEmpty() }
    }
}
