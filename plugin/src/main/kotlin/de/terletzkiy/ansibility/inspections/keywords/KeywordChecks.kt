package de.terletzkiy.ansibility.inspections.keywords

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import de.terletzkiy.ansibility.inspections.modules.AnsibilityModuleChecksBundle
import de.terletzkiy.ansibility.inspections.modules.ProblemFix
import de.terletzkiy.ansibility.inspections.modules.TaskCheckEnvironment
import de.terletzkiy.ansibility.inspections.modules.TaskProblem
import de.terletzkiy.ansibility.model.task.BlockNode
import de.terletzkiy.ansibility.model.task.KeywordOwner
import de.terletzkiy.ansibility.model.task.PlayNode
import de.terletzkiy.ansibility.model.task.PlaybookImportNode
import de.terletzkiy.ansibility.model.task.TaskFileModel
import de.terletzkiy.ansibility.model.task.TaskItem
import de.terletzkiy.ansibility.model.task.TaskModelBuilder
import de.terletzkiy.ansibility.model.task.TaskNode
import de.terletzkiy.ansibility.model.task.TaskSyntax
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.semantics.diagnostics.Level
import de.terletzkiy.ansibility.semantics.keywords.KeywordValidator
import de.terletzkiy.ansibility.semantics.keywords.PlaybookObject
import de.terletzkiy.ansibility.semantics.keywords.UnknownKeywords
import de.terletzkiy.ansibility.semantics.schema.DocSnapshot
import de.terletzkiy.ansibility.semantics.yaml.SourceRange
import de.terletzkiy.ansibility.semantics.yaml.YEntry
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.settings.FindingContext

/**
 * The keyword checks of one task file (plan F5.10): ANS-K001 for keyword values ansible-core rejects
 * ([KeywordValidator]) and ANS-K002 for keys it fails on ([UnknownKeywords]) on plays, `import_playbook` entries,
 * role entries (values only: their other keys are role parameters), blocks, tasks, handlers and `loop_control`.
 *
 * The keyword sets are those of the target's documentation line ([TaskSyntax]); the second action key of a task is
 * what the task model left over after choosing the module ([TaskNode.unknownKeys]). A `with_x` key that the model does
 * not read as a loop is not reported: `x` may be a lookup plugin of the project, which the bundled docs do not list.
 * Findings from keyword docs of another minor line are marked [FindingContext.docsDifferFromTarget]; with
 * `invalid_task_attribute_failed = False` the task-key findings ansible-core only warns about are capped at WARNING.
 */
object KeywordChecks {
    /** Every keyword finding of [model] built from [document], in file order. Call inside a read action. */
    fun problems(model: TaskFileModel, document: YValue?, env: TaskCheckEnvironment): List<TaskProblem> =
        Walk(env, document).run(model)

    private class Walk(private val env: TaskCheckEnvironment, private val document: YValue?) {
        private val validator = KeywordValidator(env.semantics)
        private val syntax = env.syntax
        private val context = FindingContext(docsDifferFromTarget = !env.keywordDocsMatchTarget)
        private val result = ArrayList<TaskProblem>()

        fun run(model: TaskFileModel): List<TaskProblem> {
            for (play in model.plays) {
                ProgressManager.checkCanceled()
                play(play)
            }
            for (import in model.imports) import(import)
            items(model.items)
            return result.sortedBy { it.range.startOffset }
        }

        private fun play(play: PlayNode) {
            values(PlaybookObject.PLAY, play.keywords)
            unknown(play.unknownKeys, PlaybookObject.PLAY, KeywordOwner.PLAY)
            for (role in play.roles) values(PlaybookObject.ROLE, role.keywords)
            for (section in play.sections()) items(section)
        }

        private fun import(import: PlaybookImportNode) {
            values(PlaybookObject.PLAYBOOK_INCLUDE, import.keywords)
            val map = (document as? YSeq)?.items?.getOrNull(import.itemIndex) as? YMap ?: return
            val isImportKey = { entry: YEntry -> entry.key.range == import.key.range && entry.key.text == import.key.text }
            map.entries.firstOrNull(isImportKey)?.let { check(PlaybookObject.PLAYBOOK_INCLUDE, IMPORT_PLAYBOOK, it) }
            val others = map.entries.filter { !isImportKey(it) && it.key.text !in import.keywords }
            unknown(others, PlaybookObject.PLAYBOOK_INCLUDE, KeywordOwner.PLAYBOOK_INCLUDE)
        }

        private fun items(items: List<TaskItem>) {
            for (item in items) {
                ProgressManager.checkCanceled()
                when (item) {
                    is BlockNode -> block(item)
                    is TaskNode -> task(item)
                }
            }
        }

        private fun block(block: BlockNode) {
            values(PlaybookObject.BLOCK, block.keywords)
            unknown(block.unknownKeys, PlaybookObject.BLOCK, KeywordOwner.BLOCK)
            items(block.block)
            items(block.rescue)
            items(block.always)
        }

        private fun task(task: TaskNode) {
            val owner = if (task.isHandler) PlaybookObject.HANDLER else PlaybookObject.TASK
            values(owner, task.keywords)
            for (entry in task.unknownKeys) taskKey(task, owner, entry)
            dynamicInclude(task)
            val loopControl = task.loopControl?.entry?.value as? YMap ?: return
            val loopKeywords = syntax.keywords(KeywordOwner.LOOP_CONTROL)
            for (entry in loopControl.entries) {
                if (entry.key.text in loopKeywords) check(PlaybookObject.LOOP_CONTROL, entry.key.text, entry)
            }
            unknown(loopControl.entries.filter { it.key.text !in loopKeywords }, PlaybookObject.LOOP_CONTROL, KeywordOwner.LOOP_CONTROL)
        }

        private fun values(owner: PlaybookObject, keywords: Map<String, YEntry>) {
            for ((name, entry) in keywords) check(owner, name, entry)
        }

        private fun check(owner: PlaybookObject, name: String, entry: YEntry) {
            val doc = env.docs.keywordDoc(env.root, name)
            for (rejection in validator.check(owner, name, doc, entry.value)) {
                val key = if (name == IMPORT_PLAYBOOK) entry.key.text else name
                val message = if (rejection.crash) {
                    AnsibilityModuleChecksBundle.message("k001.message.crash", env.coreLabel, key, rejection.reason)
                } else {
                    AnsibilityModuleChecksBundle.message("k001.message", env.coreLabel, key, rejection.reason)
                }
                val range = range(rejection.range) ?: range(entry.value.range) ?: range(entry.key.range) ?: continue
                result += TaskProblem(DiagnosticCode.K001_KEYWORD_VALUE_REJECTED, message, range, context)
            }
        }

        /** Keys of a play, block, import or `loop_control` that are no keywords: `'x' is not a valid attribute for a Block`. */
        private fun unknown(entries: List<YEntry>, owner: PlaybookObject, syntaxOwner: KeywordOwner) {
            if (entries.isEmpty()) return
            val accepted = UnknownKeywords.undocumentedKeys(owner, env.version)
            val candidates = syntax.keywords(syntaxOwner) + accepted
            for (entry in entries) {
                val key = entry.key.text
                if (key in accepted) continue
                val keyRange = range(entry.key.range) ?: continue
                val runtime = UnknownKeywords.notAnAttribute(key, owner.className)
                report(key, keyRange, candidates, runtime, owner, maxLevel = null)
            }
        }

        /** A task key that is neither a keyword nor the module: a second action key. */
        private fun taskKey(task: TaskNode, owner: PlaybookObject, entry: YEntry) {
            val key = entry.key.text
            if (key in UnknownKeywords.undocumentedKeys(owner, env.version)) return
            val keyRange = range(entry.key.range) ?: return
            val syntaxOwner = if (task.isHandler) KeywordOwner.HANDLER else KeywordOwner.TASK
            val candidates = syntax.keywords(syntaxOwner) + UnknownKeywords.undocumentedKeys(owner, env.version)
            // A `with_x` the model did not read as a loop names a module option (`with_dependencies`); x may still be a
            // lookup plugin of the project, which would make it a loop: not certain, not reported.
            if (key.startsWith(TaskSyntax.WITH_PREFIX)) return
            val module = task.module
            if (module == null) {
                val warnOnly = if (env.invalidTaskAttributeFailed) null else Level.WARNING
                report(key, keyRange, candidates, UnknownKeywords.notAnAttribute(key, owner.className), owner, warnOnly)
                return
            }
            val moduleFirst = module.nameRange.startOffset <= keyRange.startOffset
            val runtime = if (moduleFirst) UnknownKeywords.conflictingActions(module.name, key) else UnknownKeywords.conflictingActions(key, module.name)
            val suggestion = UnknownKeywords.suggestion(key, candidates)
            val message = if (suggestion != null) {
                AnsibilityModuleChecksBundle.message("k002.message.action.nearest", module.name, key, suggestion, env.coreLabel, runtime)
            } else {
                AnsibilityModuleChecksBundle.message("k002.message.action", module.name, key, env.coreLabel, runtime)
            }
            val fixes = listOfNotNull(suggestion?.let { ProblemFix.RenameKey(keyRange, it) })
            result += TaskProblem(DiagnosticCode.K002_UNKNOWN_KEYWORD, message, keyRange, context, fixes)
        }

        /** `include_tasks`/`include_role` accept only `TaskInclude.VALID_INCLUDE_KEYWORDS`. */
        private fun dynamicInclude(task: TaskNode) {
            val module = task.module ?: return
            val canonical = module.canonical
            val role = canonical == TaskModelBuilder.INCLUDE_ROLE
            if (!role && canonical != TaskModelBuilder.INCLUDE_TASKS) return
            val allowed = UnknownKeywords.dynamicIncludeKeywords(task.isHandler)
            val className = UnknownKeywords.includeClassName(role, task.isHandler)
            for ((key, entry) in task.keywords) {
                if (key in allowed) continue
                val keyRange = range(entry.key.range) ?: continue
                val runtime = UnknownKeywords.notAnAttribute(key, className)
                val message = AnsibilityModuleChecksBundle.message("k002.message.include", key, module.name, env.coreLabel, runtime)
                val warnOnly = if (env.invalidTaskAttributeFailed) null else Level.WARNING
                result += TaskProblem(DiagnosticCode.K002_UNKNOWN_KEYWORD, message, keyRange, context, maxLevel = warnOnly)
            }
        }

        private fun report(key: String, keyRange: TextRange, candidates: Set<String>, runtime: String, owner: PlaybookObject, maxLevel: Level?) {
            val suggestion = UnknownKeywords.suggestion(key, candidates.filter { it != DocSnapshot.WITH_LOOKUP })
            val what = AnsibilityModuleChecksBundle.message("k002.object.${owner.name.lowercase()}")
            val message = if (suggestion != null) {
                AnsibilityModuleChecksBundle.message("k002.message.nearest", key, what, suggestion, env.coreLabel, runtime)
            } else {
                AnsibilityModuleChecksBundle.message("k002.message", key, what, env.coreLabel, runtime)
            }
            val fixes = listOfNotNull(suggestion?.let { ProblemFix.RenameKey(keyRange, it) })
            result += TaskProblem(DiagnosticCode.K002_UNKNOWN_KEYWORD, message, keyRange, context, fixes, maxLevel)
        }

        private fun range(source: SourceRange?): TextRange? = source?.let { TextRange(it.start, it.end) }
    }

    private const val IMPORT_PLAYBOOK = "import_playbook"
}
