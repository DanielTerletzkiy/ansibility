package de.terletzkiy.ansibility.inspections.modules

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import de.terletzkiy.ansibility.api.ResolvedModuleDoc
import de.terletzkiy.ansibility.model.task.KeywordOwner
import de.terletzkiy.ansibility.model.task.ModuleCall
import de.terletzkiy.ansibility.model.task.ModuleForm
import de.terletzkiy.ansibility.model.task.TaskFileModel
import de.terletzkiy.ansibility.model.task.TaskNode
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.semantics.diagnostics.Finding
import de.terletzkiy.ansibility.semantics.keywords.UnknownKeywords
import de.terletzkiy.ansibility.semantics.schema.ModuleDoc
import de.terletzkiy.ansibility.semantics.validate.SpecKind
import de.terletzkiy.ansibility.semantics.validate.SpecValidator
import de.terletzkiy.ansibility.semantics.yaml.SourceRange
import de.terletzkiy.ansibility.semantics.yaml.YEmpty
import de.terletzkiy.ansibility.semantics.yaml.YEntry
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.settings.FindingContext

/**
 * The module option checks of one task file (plan F3.3, F5.6): ANS-M001 unknown options, ANS-M002 missing required
 * options and the option values (T001–T005 rejections, T010–T016 documented-type coercions), from the module's
 * documentation for the root's target ([de.terletzkiy.ansibility.api.AnsibleDocService]) and the arguments the
 * task model merged the way `ModuleArgsParser` does (`args:`, the module mapping, `k=v` words).
 *
 * Nothing is reported for a module no documentation knows. Unknown options are not reported for free-form modules
 * (`command`, `shell`, `raw`, `script`, `meta`), for modules that accept arbitrary keys (`set_fact`, `add_host`), when
 * the arguments come from a template (`args: "{{ x }}"`, a templated module value), or when an action plugin
 * ignores or hands on unknown arguments ([ActionArguments]); missing options are not reported for templated
 * arguments either. Documentation that is not for the target's minor line marks every finding
 * [FindingContext.docsDifferFromTarget].
 */
object ModuleOptionChecks {
    /** Pseudo-options the docs list for free-form text and arbitrary keys; never real arguments. */
    private val PSEUDO_OPTIONS = setOf("free_form", "free-form", "key_value")

    /** Modules whose string arguments become `_raw_params` (`mod_args.RAW_PARAM_MODULES`), never `_variable_params`. */
    private val RAW_PARAM_MODULES = setOf(
        "include_vars", "include_tasks", "include_role", "import_tasks", "import_role", "add_host", "group_by",
        "set_fact", "meta", "command", "shell", "script", "raw",
    ).mapTo(HashSet()) { "ansible.builtin.$it" }

    /** The value findings that are rejections; the others are documented-type coercions. */
    val VALUE_REJECTIONS: Set<DiagnosticCode> = setOf(
        DiagnosticCode.T001_VALUE_REJECTED, DiagnosticCode.T002_UNSUPPORTED_SUB_OPTION, DiagnosticCode.T003_MISSING_REQUIRED_SUB_OPTION,
        DiagnosticCode.T004_CHOICE_MISMATCH, DiagnosticCode.T005_NULL_FOR_TYPED_OPTION,
    )

    /** The documented-type codes reported on module option values (silent by default, D5). */
    val VALUE_COERCIONS: Set<DiagnosticCode> = setOf(
        DiagnosticCode.T010_SHAPE_CONTRADICTION, DiagnosticCode.T011_COERCED_SCALAR_TO_STR, DiagnosticCode.T013_SCALAR_TYPE_MISMATCH,
        DiagnosticCode.T014_LEGACY_COERCION, DiagnosticCode.T015_NULL_FOR_OPTIONAL, DiagnosticCode.T016_STRING_FOR_NUMBER_OR_BOOL,
    )

    /** Every module option finding of [model], in task order. Call inside a read action. */
    fun problems(model: TaskFileModel, env: TaskCheckEnvironment): List<TaskProblem> {
        val validator = SpecValidator(env.semantics, SpecKind.MODULE)
        val result = ArrayList<TaskProblem>()
        for (task in model.tasks()) {
            ProgressManager.checkCanceled()
            val module = task.module ?: continue
            val resolved = env.docs.moduleDoc(env.root, module.name) ?: continue
            val doc = resolved.doc ?: continue
            result += TaskChecks(task, module, resolved, doc, env, validator).problems()
        }
        return result
    }

    private class TaskChecks(
        val task: TaskNode,
        val module: ModuleCall,
        val resolved: ResolvedModuleDoc,
        val doc: ModuleDoc,
        val env: TaskCheckEnvironment,
        val validator: SpecValidator,
    ) {
        private val canonical = resolved.canonical
        private val action = ActionArguments.of(canonical)
        private val args = module.args
        private val pseudo: Set<String> = PSEUDO_OPTIONS + listOfNotNull(doc.freeForm)
        private val mismatch = resolved.source?.matchesTarget != true
        private val context = FindingContext(onModuleOption = true, docsDifferFromTarget = mismatch)
        private val moduleKeyRange = module.nameRange
        private val mismatchNote = if (mismatch) env.docsMismatchNote(resolved.source?.label ?: "?") else ""

        /** The arguments come from a template (`_variable_params`): which keys ansible-core sees is unknown. */
        private val dynamic: Boolean = run {
            val argsValue = args.argsKeyword?.value
            val templatedArgs = argsValue is YScalar && SpecValidator.containsJinja(argsValue)
            val raw = args.rawParams
            val templatedRaw = raw != null && canonical !in RAW_PARAM_MODULES && doc.freeForm == null &&
                ("{{" in raw || "{%" in raw)
            templatedArgs || templatedRaw
        }

        fun problems(): List<TaskProblem> = unknownOptions() + missingOptions() + values()

        private fun unknownOptions(): List<TaskProblem> {
            if (doc.freeForm != null || doc.acceptsArbitraryKeys || dynamic || action.unknown != UnknownArguments.REJECTED) return emptyList()
            val accepted = HashSet<String>()
            for (option in doc.options.values) {
                accepted += option.name
                accepted += option.aliases
            }
            accepted += action.extraAccepted
            val documented = doc.options.keys.filter { it !in pseudo }
            return args.options.values.filter { it.key.text !in accepted }.map { entry -> unknownOption(entry, documented) }
        }

        private fun unknownOption(entry: YEntry, documented: List<String>): TaskProblem {
            val key = entry.key.text
            val keyRange = range(entry.key.range) ?: moduleKeyRange
            val runtime = action.unknownError(env.version, module.name, canonical, key)?.let { " ($it)" } ?: ""
            val fixes = ArrayList<ProblemFix>()
            val keywordOwner = if (task.isHandler) KeywordOwner.HANDLER else KeywordOwner.TASK
            val isKeyword = env.syntax.isKeyword(keywordOwner, key)
            if (isKeyword && fromMapping(entry) && key !in task.keywords) fixes += ProblemFix.MoveToTaskLevel(key, keyRange)
            val nearest = if (isKeyword) null else UnknownKeywords.suggestion(key, documented)
            if (nearest != null && fromMapping(entry)) fixes += ProblemFix.RenameKey(keyRange, nearest)
            val message = when {
                isKeyword -> AnsibilityModuleChecksBundle.message("m001.message.keyword", key, module.name, env.coreLabel, runtime)
                nearest != null -> AnsibilityModuleChecksBundle.message("m001.message.nearest", key, module.name, env.coreLabel, runtime, nearest)
                else -> AnsibilityModuleChecksBundle.message("m001.message", key, module.name, env.coreLabel, runtime)
            } + mismatchNote
            return TaskProblem(DiagnosticCode.M001_UNKNOWN_MODULE_OPTION, message, keyRange, context, fixes)
        }

        /** Whether [entry] is a key of the module's own mapping or of `args:` (not a `k=v` word of a string). */
        private fun fromMapping(entry: YEntry): Boolean {
            val moduleMap = args.value as? YMap
            val argsMap = args.argsKeyword?.value as? YMap
            return moduleMap?.entries?.any { it === entry } == true || argsMap?.entries?.any { it === entry } == true
        }

        private fun missingOptions(): List<TaskProblem> {
            if (!action.checksRequired || dynamic) return emptyList()
            val provided = args.options.keys
            return doc.options.values
                .filter { it.required && it.name !in pseudo && it.name !in provided && it.aliases.none { alias -> alias in provided } }
                .filter { option -> action.requiredAliases[option.name].orEmpty().none { it in provided } }
                .map { option ->
                    val runtime = action.missingError(module.name, option.name)?.let { " ($it)" } ?: ""
                    val fixes = if (module.form == ModuleForm.KEY && (args.value is YMap || args.value is YEmpty)) {
                        listOf(ProblemFix.AddOption(moduleKeyRange, option.name))
                    } else {
                        emptyList()
                    }
                    TaskProblem(
                        DiagnosticCode.M002_MISSING_MODULE_OPTION,
                        AnsibilityModuleChecksBundle.message("m002.message", option.name, module.name, env.coreLabel, runtime) + mismatchNote,
                        moduleKeyRange,
                        context,
                        fixes,
                    )
                }
        }

        private fun values(): List<TaskProblem> {
            val spec = action.valueSpec(doc, pseudo) ?: return emptyList()
            val scalars = args.options.mapValues { it.value.value as? YScalar }
            if (action.skipValuesWhen(scalars)) return emptyList()
            if (args.options.isEmpty()) return emptyList()
            // Unknown keys come back as M001 findings of the validator, which ANS-M001 above reports with its own rules.
            val validation = validator.validate(spec, args.options.values.toList(), args.value.range)
            return validation.primary
                .filter { it.code in VALUE_REJECTIONS || it.code in VALUE_COERCIONS }
                .map { finding -> valueProblem(finding) }
        }

        private fun valueProblem(finding: Finding): TaskProblem {
            val range = range(finding.range) ?: moduleKeyRange
            val fixes = finding.fixHints.mapNotNull { hint ->
                when {
                    hint.startsWith(NEAREST_CHOICE) -> ProblemFix.ReplaceValue(range, hint.removePrefix(NEAREST_CHOICE))
                    hint.startsWith(NEAREST_KEY) -> ProblemFix.RenameKey(range, hint.removePrefix(NEAREST_KEY))
                    else -> null
                }
            }
            return TaskProblem(finding.code, finding.message + mismatchNote, range, context, fixes)
        }

        private fun range(source: SourceRange?): TextRange? = source?.let { TextRange(it.start, it.end) }
    }

    private const val NEAREST_CHOICE = "nearest-choice="
    private const val NEAREST_KEY = "nearest-key="
}
