package de.terletzkiy.ansibility.docs

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.KeywordLevel
import de.terletzkiy.ansibility.api.SiteClassifier
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.index.JinjaBearing
import de.terletzkiy.ansibility.lang.jinja.lexer.JinjaLexMode
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaRefs
import de.terletzkiy.ansibility.model.task.BlockNode
import de.terletzkiy.ansibility.model.task.ImplicitExpression
import de.terletzkiy.ansibility.model.task.KeywordOwner
import de.terletzkiy.ansibility.model.task.ModuleCall
import de.terletzkiy.ansibility.model.task.ModuleForm
import de.terletzkiy.ansibility.model.task.PlayNode
import de.terletzkiy.ansibility.model.task.PlaybookImportNode
import de.terletzkiy.ansibility.model.task.RoleEntryNode
import de.terletzkiy.ansibility.model.task.TaskFileModel
import de.terletzkiy.ansibility.model.task.TaskFileModels
import de.terletzkiy.ansibility.model.task.TaskModelBuilder
import de.terletzkiy.ansibility.model.task.TaskNode
import de.terletzkiy.ansibility.model.task.TaskSyntax
import de.terletzkiy.ansibility.model.task.TaskSyntaxService
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import org.jetbrains.yaml.YAMLLanguage
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * The docs area's `siteClassifier` (plan A.4, track B): modules, module options, playbook keywords and Jinja
 * filter/test names in task-like files ([FileKind.ROLE_TASKS], [FileKind.ROLE_HANDLERS], [FileKind.PLAYBOOK],
 * [FileKind.MOLECULE_PLAYBOOK], [FileKind.MOLECULE_TASKS]).
 *
 * The structure comes from the task model ([TaskFileModels]), keyword sets from the root's target ansible-core
 * ([TaskSyntaxService]):
 * - the module key of a task ([AnsibleSite.ModuleKey], the name as written), also the module named in the value of
 *   `action:`/`local_action:`;
 * - option keys under the module mapping and under `args:` ([AnsibleSite.ModuleOptionKey]); dict and `list[dict]`
 *   values give nested paths without indices (`mounts[0].type` → `["mounts", "type"]`); `k=v` words of a string
 *   value are option keys too;
 * - keyword keys of plays, blocks, tasks, handlers, role entries and `loop_control` ([AnsibleSite.KeywordKey]), the
 *   `with_<lookup>` loop key included; `import_playbook` items give [KeywordLevel.PLAYBOOK_INCLUDE]; the task
 *   keywords under `apply:` of `include_tasks`/`include_role` are task keywords;
 * - filter and test names inside Jinja ([AnsibleSite.JinjaFilter], [AnsibleSite.JinjaTest]), found with [JinjaRefs]
 *   in template scalars and in implicit expressions (`when`, `that` …).
 *
 * Returns null for everything the vars track owns: variable references in Jinja, keys inside any `vars:` mapping,
 * `set_fact` keys (only its documented `cacheable` option is an option) and role parameters; for values (they are
 * role, task file, template and handler references of other tracks); for unknown keys. Pure PSI and model work
 * without indexes, so it is [DumbAware].
 */
class TaskSiteClassifier : SiteClassifier, DumbAware {
    override fun classify(file: PsiFile, offset: Int): AnsibleSite? {
        val project = file.project
        val virtualFile = file.originalFile.viewProvider.virtualFile
        val context = AnsibleWorkspace.getInstance(project).contextOf(virtualFile) ?: return null
        if (context.kind !in TASK_FILE_KINDS) return null
        val yaml = yamlOf(file, virtualFile) ?: return null
        val model = TaskFileModels.of(yaml)
        if (!model.isSequence) return null
        val target = TargetVersionDetector.getInstance(project).targetVersion(context.root).version
        return TaskSites(yaml, model, TaskSyntaxService.getInstance().forVersion(target)).at(offset)
    }

    private fun yamlOf(file: PsiFile, virtualFile: VirtualFile): YAMLFile? =
        file as? YAMLFile
            ?: file.viewProvider.getPsi(YAMLLanguage.INSTANCE) as? YAMLFile
            ?: YamlFiles.yamlFile(file.project, virtualFile)

    companion object {
        /** The file kinds whose top-level sequence holds plays or tasks. */
        val TASK_FILE_KINDS: Set<FileKind> = setOf(
            FileKind.ROLE_TASKS, FileKind.ROLE_HANDLERS, FileKind.PLAYBOOK, FileKind.MOLECULE_PLAYBOOK, FileKind.MOLECULE_TASKS,
        )
    }
}

/** Classification of one position in one task-like [file], see [TaskSiteClassifier]. */
internal class TaskSites(private val file: YAMLFile, private val model: TaskFileModel, private val syntax: TaskSyntax) {

    /** A structural owner of keys: a task, block, play, role entry or `import_playbook` item. */
    private sealed class Node(val range: TextRange, val expressions: List<ImplicitExpression>) {
        class Task(val task: TaskNode) : Node(task.range, task.expressions)
        class Block(val block: BlockNode) : Node(block.range, block.expressions)
        class Play(val play: PlayNode) : Node(play.range, emptyList())
        class RoleEntry(val entry: RoleEntryNode) : Node(entry.range, entry.expressions)
        class Import(val entry: PlaybookImportNode) : Node(entry.range, entry.expressions)
    }

    /** One step from a node's mapping down to a key: a mapping key or a sequence item. */
    private sealed interface Step {
        data class Key(val name: String, val range: TextRange) : Step
        data object Item : Step
    }

    fun at(offset: Int): AnsibleSite? {
        keyAt(offset)?.let { return keySite(it) }
        return valueSite(offset)
    }

    // ------------------------------------------------------------------------------------------------ keys

    /** The key-value whose key holds [offset] (or ends right before it, for a caret after the key). */
    private fun keyAt(offset: Int): YAMLKeyValue? {
        for (candidate in intArrayOf(offset, offset - 1)) {
            if (candidate < 0) continue
            val leaf = file.findElementAt(candidate) ?: continue
            val keyValue = PsiTreeUtil.getParentOfType(leaf, YAMLKeyValue::class.java, false) ?: continue
            val key = keyValue.key ?: continue
            if (PsiTreeUtil.isAncestor(key, leaf, false)) return keyValue
        }
        return null
    }

    private fun keySite(keyValue: YAMLKeyValue): AnsibleSite? {
        val keyRange = keyValue.key?.textRange ?: return null
        val node = nodeAt(keyRange.startOffset) ?: return null
        val path = pathFrom(node.range, keyValue) ?: return null
        val top = path.first() as? Step.Key ?: return null
        return when (node) {
            is Node.Task -> taskKey(node.task, top, path.drop(1))
            is Node.Block -> keyword(path, KeywordOwner.BLOCK, KeywordLevel.BLOCK)
            is Node.Play -> keyword(path, KeywordOwner.PLAY, KeywordLevel.PLAY)
            is Node.RoleEntry -> keyword(path, KeywordOwner.ROLE, KeywordLevel.ROLE_ENTRY)
            is Node.Import ->
                if (path.size == 1 && node.entry.key.range?.start == top.range.startOffset) {
                    AnsibleSite.KeywordKey(TaskModelBuilder.IMPORT_PLAYBOOK_KEY, KeywordLevel.PLAYBOOK_INCLUDE, top.range)
                } else {
                    keyword(path, KeywordOwner.PLAYBOOK_INCLUDE, KeywordLevel.PLAYBOOK_INCLUDE)
                }
        }
    }

    /** A keyword directly on a node; nested keys (vars, role parameters, …) are not ours. */
    private fun keyword(path: List<Step>, owner: KeywordOwner, level: KeywordLevel): AnsibleSite? {
        val key = path.singleOrNull() as? Step.Key ?: return null
        return if (syntax.isKeyword(owner, key.name)) AnsibleSite.KeywordKey(key.name, level, key.range) else null
    }

    private fun taskKey(task: TaskNode, top: Step.Key, rest: List<Step>): AnsibleSite? {
        val owner = if (task.isHandler) KeywordOwner.HANDLER else KeywordOwner.TASK
        val level = if (task.isHandler) KeywordLevel.HANDLER else KeywordLevel.TASK
        val module = task.module
        val isModuleKey = module != null && module.form == ModuleForm.KEY && module.nameRange.startOffset == top.range.startOffset
        if (rest.isEmpty()) {
            return when {
                isModuleKey -> AnsibleSite.ModuleKey(module.name, top.range)
                syntax.isKeyword(owner, top.name) -> AnsibleSite.KeywordKey(top.name, level, top.range)
                task.loop?.key?.range?.start == top.range.startOffset -> AnsibleSite.KeywordKey(top.name, level, top.range)
                else -> null
            }
        }
        return when {
            top.name == LOOP_CONTROL && syntax.isKeyword(owner, LOOP_CONTROL) -> loopControlKey(rest)
            module == null -> null
            isModuleKey -> optionSite(module, rest)
            top.name == ARGS && syntax.isKeyword(owner, ARGS) -> optionSite(module, rest)
            (top.name == ACTION || top.name == LOCAL_ACTION) && module.form != ModuleForm.KEY ->
                if ((rest.first() as? Step.Key)?.name == ACTION_MODULE_KEY) null else optionSite(module, rest)
            else -> null
        }
    }

    private fun loopControlKey(rest: List<Step>): AnsibleSite? {
        val key = rest.singleOrNull() as? Step.Key ?: return null
        return if (syntax.isKeyword(KeywordOwner.LOOP_CONTROL, key.name)) AnsibleSite.KeywordKey(key.name, KeywordLevel.LOOP_CONTROL, key.range) else null
    }

    /** An option key at [rest] below the module mapping (or `args:`); sequence items are skipped in the path. */
    private fun optionSite(module: ModuleCall, rest: List<Step>): AnsibleSite? {
        val keys = rest.filterIsInstance<Step.Key>()
        val last = keys.lastOrNull() ?: return null
        val names = keys.map { it.name }
        // Keys inside any `vars:` mapping are variables (the vars track's).
        if (VARS in names.dropLast(1)) return null
        when (module.canonical) {
            SET_FACT -> {
                // Every set_fact key defines a variable, except its documented options.
                val option = names.singleOrNull() ?: return null
                return if (option != SET_FACT_FREE_FORM && syntax.moduleHasOption(module.name, option) == true) {
                    AnsibleSite.ModuleOptionKey(module.name, names, last.range)
                } else {
                    null
                }
            }
            in APPLY_MODULES -> if (names.first() == APPLY && names.size > 1) {
                // `apply:` holds keywords applied to the included tasks.
                return if (names.size == 2 && syntax.isKeyword(KeywordOwner.TASK, names[1])) {
                    AnsibleSite.KeywordKey(names[1], KeywordLevel.TASK, last.range)
                } else {
                    null
                }
            }
        }
        return AnsibleSite.ModuleOptionKey(module.name, names, last.range)
    }

    /** The steps from the mapping spanning [nodeRange] down to [keyValue]; null when [keyValue] is not below it. */
    private fun pathFrom(nodeRange: TextRange, keyValue: YAMLKeyValue): List<Step>? {
        val steps = ArrayList<Step>()
        var current: PsiElement = keyValue
        while (true) {
            ProgressManager.checkCanceled()
            when (current) {
                is YAMLKeyValue -> steps += Step.Key(PsiYValueAdapter.keyOf(current).text, current.key?.textRange ?: current.textRange)
                is YAMLSequenceItem -> steps += Step.Item
            }
            val parent = current.parent ?: return null
            if (current is YAMLKeyValue && parent is YAMLMapping && parent.textRange == nodeRange) break
            if (parent is YAMLDocument || parent is PsiFile) return null
            current = parent
        }
        return steps.asReversed()
    }

    /** The innermost task, block, role entry, `import_playbook` item or play at [offset]. */
    private fun nodeAt(offset: Int): Node? {
        when (val item = model.itemAt(offset)) {
            is TaskNode -> return Node.Task(item)
            is BlockNode -> return Node.Block(item)
            null -> Unit
        }
        model.imports.firstOrNull { it.range.containsOffset(offset) }?.let { return Node.Import(it) }
        val play = model.playAt(offset) ?: return null
        play.roles.firstOrNull { it.range.containsOffset(offset) }?.let { return Node.RoleEntry(it) }
        return Node.Play(play)
    }

    // ------------------------------------------------------------------------------------------------ values

    private fun valueSite(offset: Int): AnsibleSite? {
        val scalar = scalarAt(offset) ?: return null
        val node = nodeAt(offset)
        (node as? Node.Task)?.task?.module?.let { module ->
            if (module.form != ModuleForm.KEY && module.nameRange.containsOffset(offset)) {
                return AnsibleSite.ModuleKey(module.name, module.nameRange)
            }
            keyValueOptionAt(module, offset)?.let { return it }
        }
        return jinjaNameAt(scalar, node, offset)
    }

    private fun scalarAt(offset: Int): YAMLScalar? {
        for (candidate in intArrayOf(offset, offset - 1)) {
            if (candidate < 0) continue
            val leaf = file.findElementAt(candidate) ?: continue
            PsiTreeUtil.getParentOfType(leaf, YAMLScalar::class.java, false)?.let { return it }
        }
        return null
    }

    /** The key of a `k=v` word in the module's string value (`ansible.builtin.copy: src=a dest=b`, `action: copy src=a`). */
    private fun keyValueOptionAt(module: ModuleCall, offset: Int): AnsibleSite? {
        val value = module.args.value as? YScalar ?: return null
        val valueRange = value.range ?: return null
        for (entry in module.args.options.values) {
            val key = entry.key.range ?: continue
            // Words of a scalar whose text is not verbatim in the file carry the whole scalar's range: skip those.
            if (key.end - key.start != entry.key.text.length) continue
            if (key.start < valueRange.start || key.end > valueRange.end) continue
            if (offset in key.start..key.end) {
                return AnsibleSite.ModuleOptionKey(module.name, listOf(entry.key.text), TextRange(key.start, key.end))
            }
        }
        return null
    }

    /** A filter or test name inside the Jinja of [scalar]; variable references are the vars track's. */
    private fun jinjaNameAt(scalar: YAMLScalar, node: Node?, offset: Int): AnsibleSite? {
        val value = PsiYValueAdapter.toYValue(scalar) as? YScalar ?: return null
        if (value.tag in JinjaBearing.NON_TEMPLATED_TAGS) return null
        val escaper = scalar.createLiteralTextEscaper()
        val relevant = escaper.relevantTextRange
        val decoded = StringBuilder()
        if (!escaper.decode(relevant, decoded)) return null
        val implicit = node?.expressions?.any { it.range.containsOffset(offset) } == true
        val mode = when {
            JinjaBearing.hasTemplateMarkers(decoded) -> JinjaLexMode.TEMPLATE
            implicit -> JinjaLexMode.EXPRESSION
            else -> return null
        }
        val start = scalar.textRange.startOffset
        val index = decodedIndex(offset - start, decoded.length) { escaper.getOffsetInHost(it, relevant) } ?: return null
        val refs = JinjaRefs.analyze(decoded, mode)
        fun hostRange(range: TextRange): TextRange? {
            val from = escaper.getOffsetInHost(range.startOffset, relevant)
            val to = escaper.getOffsetInHost(range.endOffset, relevant)
            return if (from < 0 || to < from) null else TextRange(start + from, start + to)
        }
        refs.filterNames.firstOrNull { it.range.containsOffset(index) }?.let { site ->
            return hostRange(site.range)?.let { AnsibleSite.JinjaFilter(site.name, it) }
        }
        refs.testNames.firstOrNull { it.range.containsOffset(index) }?.let { site ->
            return hostRange(site.range)?.let { AnsibleSite.JinjaTest(site.name, it) }
        }
        return null
    }

    companion object {
        private const val ARGS = "args"
        private const val ACTION = "action"
        private const val LOCAL_ACTION = "local_action"
        private const val ACTION_MODULE_KEY = "module"
        private const val LOOP_CONTROL = "loop_control"
        private const val VARS = "vars"
        private const val APPLY = "apply"
        private const val SET_FACT = "ansible.builtin.set_fact"

        /** set_fact's documented stand-in for its arbitrary keys. */
        private const val SET_FACT_FREE_FORM = "key_value"

        private val APPLY_MODULES = setOf(TaskModelBuilder.INCLUDE_TASKS, TaskModelBuilder.INCLUDE_ROLE)

        /**
         * The decoded index whose host offset is the largest one not after [hostOffset] (host offsets grow with the
         * index), found by binary search over `0..length`; null when even index 0 lies after it.
         */
        fun decodedIndex(hostOffset: Int, length: Int, offsetInHost: (Int) -> Int): Int? {
            var low = 0
            var high = length
            var found: Int? = null
            while (low <= high) {
                val mid = (low + high) ushr 1
                val host = offsetInHost(mid)
                if (host in 0..hostOffset) {
                    found = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            return found
        }
    }
}
