package de.terletzkiy.ansibility.vars

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.index.TaskKeywords
import de.terletzkiy.ansibility.model.task.BlockNode
import de.terletzkiy.ansibility.model.task.ModuleForm
import de.terletzkiy.ansibility.model.task.TaskFileModels
import de.terletzkiy.ansibility.model.task.TaskItem
import de.terletzkiy.ansibility.model.task.TaskNode
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.yaml.YamlPaths
import de.terletzkiy.ansibility.yaml.YamlPsi
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * Which YAML keys are variable keys ([AnsibleSite.VarKey]), decided by the key's structural position in a file of a
 * known [FileKind] (never by its name):
 * - vars files (`defaults/`, `vars/`, `group_vars` and `host_vars` files, molecule `vars/`): every key of the top-level
 *   mapping and everything nested below it;
 * - `meta/argument_specs.yml` (and the `argument_specs` of `meta/main.yml`): option names, i.e. the keys directly
 *   under an `options` mapping of an entry point or of another option (`type`, `description`, … are not options);
 * - `hosts.yml` and molecule `provisioner.inventory`: keys under a group's `vars:`, a host's own keys, and
 *   `group_vars`/`host_vars` owners' keys;
 * - task files and playbooks: keys under a play's, block's, task's (so also `include_role`'s), role entry's or
 *   playbook import's `vars:`, role parameters of a `roles:` entry, and the variables set by `set_fact`
 *   (`cacheable` excluded).
 *
 * Module names, module options and keywords are never variable keys. The key path follows the contract of
 * [AnsibleSite.VarKey]: it starts at the variable name. Call in a read action.
 */
internal object VarKeySites {
    private val ANCHORS = Key.create<CachedValue<TaskAnchors>>("ansibility.vars.taskVarAnchors")

    private val VARS_FILE_KINDS = setOf(
        FileKind.ROLE_DEFAULTS, FileKind.ROLE_VARS, FileKind.GROUP_VARS, FileKind.HOST_VARS, FileKind.MOLECULE_VARS,
    )
    private val TASK_KINDS = setOf(
        FileKind.ROLE_TASKS, FileKind.ROLE_HANDLERS, FileKind.PLAYBOOK, FileKind.MOLECULE_PLAYBOOK, FileKind.MOLECULE_TASKS,
        FileKind.OTHER,
    )
    private const val SET_FACT_CACHEABLE = "cacheable"

    /** A variable key: the classified [site], the key-value under the caret and the key-value naming the variable. */
    class KeySite(
        val site: AnsibleSite.VarKey,
        /** The file the key is written in (the host file, also when [key] belongs to a YAML view of it). */
        val file: VirtualFile,
        /** The key-value whose key is under the caret. */
        val key: YAMLKeyValue,
        /** The key-value that names the variable: [key] itself for a top-level key, its outermost variable key otherwise. */
        val variable: YAMLKeyValue,
        /** For argument_specs options: the entry point (`main`). */
        val entryPoint: String?,
    ) {
        val name: String get() = site.keyPath.first()
    }

    /** The variable key whose key text contains [offset] in [file] (a host file classified as [context]), or null. */
    fun at(file: PsiFile, offset: Int, context: FileContext): KeySite? {
        val yaml = file as? YAMLFile
            ?: YamlFiles.yamlFile(file.project, file.originalFile.viewProvider.virtualFile)
            ?: return null
        val keyValue = keyValueAt(yaml, offset) ?: return null
        return of(keyValue, context, file.originalFile.viewProvider.virtualFile)
    }

    /** The key-value whose key contains [offset] (end inclusive, for a caret right after the key). */
    fun keyValueAt(file: YAMLFile, offset: Int): YAMLKeyValue? {
        for (candidate in listOf(offset, offset - 1)) {
            if (candidate < 0) continue
            val keyValue = PsiTreeUtil.getParentOfType(file.findElementAt(candidate), YAMLKeyValue::class.java, false) ?: continue
            val key = keyValue.key ?: continue
            if (key.textRange.startOffset <= offset && offset <= key.textRange.endOffset) return keyValue
        }
        return null
    }

    /** [keyValue] as a variable key of [file], classified as [context]; null when the key is structural. */
    fun of(keyValue: YAMLKeyValue, context: FileContext, file: VirtualFile): KeySite? {
        val key = keyValue.key ?: return null
        if (YamlPsi.isMergeKey(keyValue)) return null
        val yaml = keyValue.containingFile as? YAMLFile ?: return null
        val chain = chain(keyValue)
        val path = YamlPaths.keyPath(keyValue)
        if (chain.size != path.size || path.isEmpty()) return null
        var entryPoint: String? = null
        val start: Int? = when (context.kind) {
            in VARS_FILE_KINDS -> 0.takeIf { YamlPaths.topLevelValue(yaml) is YAMLMapping }
            FileKind.ROLE_ARGSPEC, FileKind.ROLE_META -> optionStart(path)?.also { entryPoint = path[1] }
            FileKind.INVENTORY -> inventoryVarStart(path, 0)
            FileKind.MOLECULE_CONFIG -> moleculeVarStart(path)
            in TASK_KINDS -> taskVarStart(yaml, chain)
            else -> null
        }
        if (start == null || start >= path.size) return null
        val keyPath = if (context.kind == FileKind.ROLE_ARGSPEC || context.kind == FileKind.ROLE_META) {
            optionNames(path) ?: return null
        } else {
            path.drop(start)
        }
        val variable = chain[start] as? YAMLKeyValue ?: return null
        if (keyPath.isEmpty() || keyPath.first().isEmpty()) return null
        return KeySite(AnsibleSite.VarKey(keyPath, context.kind, key.textRange), file, keyValue, variable, entryPoint)
    }

    /**
     * The structural elements from the document root down to [element], outermost first: one per key-value and per
     * sequence item, aligned with the entries of [YamlPaths.keyPath].
     */
    private fun chain(element: PsiElement): List<PsiElement> {
        val result = ArrayList<PsiElement>()
        var current: PsiElement? = element
        while (current != null && current !is YAMLDocument && current !is PsiFile) {
            if (current is YAMLKeyValue || (current is YAMLSequenceItem && current.parent is YAMLSequence)) result += current
            current = current.parent
        }
        return result.asReversed()
    }

    /** `argument_specs.<entry>.options.<name>(.options.<sub>)*`: the index of the top-level option name, or null. */
    private fun optionStart(path: List<String>): Int? {
        if (path.size < 4 || path.size % 2 != 0) return null
        if (path[0] != "argument_specs" || path[2] != "options") return null
        for (i in 4 until path.size step 2) if (path[i] != "options") return null
        return 3
    }

    /** The option names of an argument_specs option path (every other segment after the entry point's `options`). */
    private fun optionNames(path: List<String>): List<String>? {
        optionStart(path) ?: return null
        return (3 until path.size step 2).map { path[it] }
    }

    /**
     * In a YAML inventory (the groups mapping starting at [from]): the index of the variable name under a group's
     * `vars:`, or of a host's own key under `hosts:`; `children` nest to any depth.
     */
    private fun inventoryVarStart(path: List<String>, from: Int): Int? {
        var group = from
        while (group + 1 < path.size) {
            ProgressManager.checkCanceled()
            when (path[group + 1]) {
                "vars" -> return (group + 2).takeIf { it < path.size }
                "hosts" -> return (group + 3).takeIf { it < path.size }
                "children" -> group += 2
                else -> return null
            }
        }
        return null
    }

    /** `provisioner.inventory.{group_vars,host_vars}.<owner>.<var>` or an inline inventory under `provisioner.inventory.hosts`. */
    private fun moleculeVarStart(path: List<String>): Int? {
        if (path.size < 4 || path[0] != "provisioner" || path[1] != "inventory") return null
        return when (path[2]) {
            "group_vars", "host_vars" -> 4.takeIf { it < path.size }
            "hosts" -> inventoryVarStart(path, 3)
            else -> null
        }
    }

    /**
     * In a task file or playbook: the index in [chain] of the variable key whose parent mapping is a `vars:` mapping, a
     * `set_fact` argument mapping or (for role parameters) a `roles:` entry, or null.
     */
    private fun taskVarStart(file: YAMLFile, chain: List<PsiElement>): Int? {
        if (!YamlPaths.isTopLevelSequence(file)) return null
        val anchors = anchors(file)
        for (index in chain.indices.reversed()) {
            val element = chain[index] as? YAMLKeyValue ?: continue
            val mapping = element.parent as? YAMLMapping ?: continue
            val range = mapping.textRange
            val name = element.keyText
            when {
                range in anchors.vars -> return index
                range in anchors.setFact -> return index.takeIf { name != SET_FACT_CACHEABLE }
                anchors.roleParams[range]?.contains(name) == true -> return index
            }
        }
        return null
    }

    /** The mappings of one task file or playbook whose keys are variables. */
    private class TaskAnchors(
        val vars: Set<TextRange>,
        val setFact: Set<TextRange>,
        /** Role entry mapping → the keys that are role parameters (not role keywords). */
        val roleParams: Map<TextRange, Set<String>>,
    )

    private fun anchors(file: YAMLFile): TaskAnchors = CachedValuesManager.getCachedValue(file, ANCHORS) {
        val model = TaskFileModels.of(file)
        val vars = HashSet<TextRange>()
        val setFact = HashSet<TextRange>()
        val roleParams = HashMap<TextRange, Set<String>>()
        fun addVars(value: YValue?) {
            (value as? YMap)?.range?.let { vars += TextRange(it.start, it.end) }
        }
        fun visit(items: List<TaskItem>) {
            for (item in items) {
                ProgressManager.checkCanceled()
                addVars(item.keywords["vars"]?.value)
                when (item) {
                    is BlockNode -> {
                        visit(item.block)
                        visit(item.rescue)
                        visit(item.always)
                    }
                    is TaskNode -> {
                        val module = item.module ?: continue
                        val isSetFact = module.name in TaskKeywords.SET_FACT || module.canonical in TaskKeywords.SET_FACT
                        if (isSetFact && module.form == ModuleForm.KEY) {
                            (module.args.value as? YMap)?.range?.let { setFact += TextRange(it.start, it.end) }
                        }
                    }
                }
            }
        }
        visit(model.items)
        for (play in model.plays) {
            addVars(play.keywords["vars"]?.value)
            for (entry in play.roles) {
                addVars(entry.keywords["vars"]?.value)
                if (entry.params.isNotEmpty()) roleParams[entry.range] = entry.params.mapTo(HashSet()) { it.key.text }
            }
            play.sections().forEach(::visit)
        }
        model.imports.forEach { addVars(it.keywords["vars"]?.value) }
        CachedValueProvider.Result.create(TaskAnchors(vars, setFact, roleParams), file)
    }
}
