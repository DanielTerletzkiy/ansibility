package de.terletzkiy.ansibility.inspections.spec

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.RoleInfo
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.index.ValueSummary
import de.terletzkiy.ansibility.inspections.types.SpecEdits
import de.terletzkiy.ansibility.inspections.undefined.UndefinedRules
import de.terletzkiy.ansibility.model.inventory.ModelCache
import de.terletzkiy.ansibility.model.inventory.ModelInputs
import de.terletzkiy.ansibility.model.inventory.VarsConfig
import de.terletzkiy.ansibility.model.inventory.VarsDocuments
import de.terletzkiy.ansibility.model.role.RoleDefault
import de.terletzkiy.ansibility.model.role.RoleDefaults
import de.terletzkiy.ansibility.model.role.RoleLayout
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.coerce.CoreSemantics
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.semantics.diagnostics.Level
import de.terletzkiy.ansibility.semantics.schema.ArgSpecParser
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.schema.OptionType
import de.terletzkiy.ansibility.semantics.validate.SpecDefaults
import de.terletzkiy.ansibility.semantics.validate.SpecValidator
import de.terletzkiy.ansibility.semantics.yaml.SourceRange
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.vars.VarLocations
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import org.jetbrains.annotations.Nls
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping

/** One option of one entry point of a role's argument spec: where a spec edit applies. */
internal data class SpecTarget(val entryPoint: String, val path: List<String>)

/**
 * A value inside a role default: the winning top-level [default], then [valuePath] (dict keys) down to [value], whose key
 * starts at [keyRange] in [RoleDefault.file].
 */
internal class RoleValue(val default: RoleDefault, val valuePath: List<String>, val value: YValue, val keyRange: SourceRange?) {
    val file: VirtualFile get() = default.file
    val keyOffset: Int get() = keyRange?.start ?: default.keyOffset
}

/**
 * One finding of ANS-S003, S004 or S005 in the role's spec file.
 *
 * [range] anchors it (the `default:` value, or the option key for S005); [cap] limits the level ([Level.ERROR] when
 * nothing lowers it). Fix data: [source] (S003: the role default text to document; S005: the role default), [documented]
 * (S004: the spec default text is read from the spec when the fix runs).
 */
internal class SpecDefaultFinding(
    val code: DiagnosticCode,
    val cap: Level,
    val range: TextRange,
    @Nls val message: String,
    val target: SpecTarget,
    val source: RoleValue?,
    /** The one-line value the fix names, null when the fix is not offered (secrets, a block value in a flow mapping). */
    val fixValue: String?,
    /** S004: where the "Add" fix writes, relative to the role (`defaults/main.yml`, `defaults/main/20-b.yml`). */
    val fixTarget: String? = null,
)

/** The quiet ANS-S003 twin on the role default's key in a defaults file: every differing entry point of one option path. */
internal class SpecDefaultTwin(
    val file: VirtualFile,
    val range: TextRange,
    @Nls val message: String,
    val targets: List<SpecTarget>,
    val source: RoleValue,
    val fixValue: String?,
)

/** The S003–S005 findings of one role, computed from its spec file and the role defaults Ansible loads. */
internal class SpecDefaultAnalysis(
    val root: AnsibleRoot,
    val role: RoleInfo,
    val specFile: VirtualFile,
    val findings: List<SpecDefaultFinding>,
    val twins: List<SpecDefaultTwin>,
) {
    fun findings(code: DiagnosticCode): List<SpecDefaultFinding> = findings.filter { it.code == code }
}

/**
 * The argument_specs default checks (plan amendment R23, D169–D172) for one role, shared by the three inspections:
 * - ANS-S003: every entry point × option with a documented default that differs from the role default
 *   ([SpecDefaults]), and along `type: dict` paths the sub-options whose key the role default's literal dict holds;
 * - ANS-S004: a documented default while nothing sets the variable (no own or dependency default, no `vars/` key, not
 *   set by the role's tasks: [UndefinedRules.hasRuntimeDefault], [UndefinedRules.setByRoleTasks]);
 * - ANS-S005: a role default that an optional, non-secret option does not document.
 *
 * The role default is [RoleDefaults]' (Ansible's file selection); a key only a dependency sets, a merged dictionary
 * (`hash_behaviour = merge`) and a whole-file vault in `defaults/` loaded after the winning file (it may override it)
 * make the comparison unknown; S004 is silent when any loaded defaults file of the role or its dependencies cannot be
 * read. Secrets (`vault_*` names, vault files, `!vault` values, `no_log` options and dicts with a `no_log` or `vault_*`
 * sub-option) are never compared and never shown, also not as the end of a `{{ name }}` chain.
 *
 * Cached per spec file in a [ModelCache] (DEV.md rule 9): the entry depends on the spec, the defaults and `meta/main.yml`
 * files it read (own and dependencies'), the layout (structure, file tree, settings, target versions) and, only for a
 * role with S004 candidates, every vars-like PSI change (the role's tasks may set the variable). Call in a read action in
 * smart mode.
 */
internal object SpecDefaultChecks {
    /** The name of the analysis cache in the model cache counters. */
    const val CACHE_NAME: String = "inspections.specDefaults"
    private const val DEFAULT = "default"
    private const val MAX_DEPTH = 8
    private const val MAX_FIX_VALUE = 40
    private const val VAULT_PREFIX = "vault_"

    /** The analysis of [role]'s spec, or null when the role has no spec file. */
    fun of(project: Project, role: RoleInfo): SpecDefaultAnalysis? {
        val specFile = role.specFile ?: return null
        return project.service<Cache>().analyses.get(specFile) { compute(project, specFile) }
    }

    private fun compute(project: Project, specFile: VirtualFile): SpecDefaultAnalysis? {
        ModelInputs.file(project, specFile)
        val spec = YamlFiles.yamlFile(project, specFile) ?: return null
        val current = RoleRegistry.getInstance(project).roleOf(specFile)?.takeIf { it.specFile == specFile } ?: return null
        val root = AnsibleWorkspace.getInstance(project).contextOf(specFile)?.root ?: return null
        // The role's dependencies come from its `meta/main.yml` (the dependencies' own are recorded by RoleDefaults).
        RoleLayout.metaFile(current.ref.dir)?.let { ModelInputs.file(project, it) }
        return Computation(project, root, current, spec).run()
    }

    /** The analyses by spec file, held by the project (PSI-free values). */
    @Service(Service.Level.PROJECT)
    class Cache(project: Project) {
        internal val analyses = ModelCache<VirtualFile, SpecDefaultAnalysis?>(project, CACHE_NAME)
    }

    /** The context, role and analysis for an inspected [file] (a spec or defaults file of a role with a spec), or null. */
    fun forFile(file: PsiFile): Triple<FileContext, RoleInfo, SpecDefaultAnalysis>? {
        val project = file.project
        if (DumbService.isDumb(project) || InjectedLanguageManager.getInstance(project).isInjectedFragment(file)) return null
        val viewProvider = file.viewProvider
        if (viewProvider.getPsi(viewProvider.baseLanguage) != file) return null
        val virtualFile = viewProvider.virtualFile
        val context = AnsibleWorkspace.getInstance(project).contextOf(virtualFile) ?: return null
        val role = RoleRegistry.getInstance(project).roleOf(virtualFile) ?: return null
        if (role.specFile == null) return null
        if (virtualFile != role.specFile && virtualFile !in RoleDefaults.loadedFiles(role.ref.dir)) return null
        val analysis = of(project, role) ?: return null
        return Triple(context, role, analysis)
    }

    /** `defaults/main.yml:3`: [offset] in [file], relative to the role directory. */
    fun label(role: RoleInfo, file: VirtualFile, offset: Int): String =
        "${VfsUtilCore.getRelativePath(file, role.ref.dir) ?: file.name}:${VarLocations.line(file, offset)}"

    /** A value for a message or fix name: one line, at most [MAX_FIX_VALUE] characters. */
    fun display(value: YValue): String = StringUtil.first(ValueSummary.render(value), MAX_FIX_VALUE, true)

    private class Computation(private val project: Project, private val root: AnsibleRoot, private val role: RoleInfo, private val spec: YAMLFile) {
        private val specFile = spec.viewProvider.virtualFile
        private val findings = ArrayList<SpecDefaultFinding>()
        private val twins = LinkedHashMap<Pair<VirtualFile, Int>, MutableList<Pair<SpecTarget, SpecDefaultFinding>>>()
        private val twinSources = HashMap<Pair<VirtualFile, Int>, Pair<RoleValue, RoleValue>>()
        private val entryPoints = ArgSpecParser.parse(PsiYValueAdapter.documentValue(spec), role.ref.name).entryPoints
        private val several = entryPoints.size > 1

        /** Variables the spec keeps secret (`no_log` on the option or below it): a `{{ name }}` chain never reads them. */
        private val noLogNames: Set<String> = entryPoints.values.flatMapTo(HashSet()) { spec -> spec.options.filterValues(SpecDefaults::hasNoLog).keys }
        private val own = RoleDefaults.scan(project, role.ref.dir, VarsConfig.of(root).hashBehaviour)
        private val rules by lazy(LazyThreadSafetyMode.NONE) { UndefinedRules(project, root, origin = null) }
        private var indexRecorded = false
        private val comparator = SpecDefaults(
            CoreSemantics(TargetVersionDetector.getInstance(project).targetVersion(root).version ?: CoreVersion.PINNED),
            SpecValidator::containsJinja,
        ) { name -> own.defaults[name]?.takeUnless { !comparable(it) || it.isSecret || name in noLogNames }?.value }

        /** A role default whose value is known: not a merged dictionary, not overridable by a later unreadable file. */
        private fun comparable(default: RoleDefault): Boolean = !default.merged && !own.mayBeOverridden(default)

        fun run(): SpecDefaultAnalysis {
            for ((entryPoint, argumentSpec) in entryPoints) {
                for ((name, option) in argumentSpec.options) {
                    ProgressManager.checkCanceled()
                    val target = SpecTarget(entryPoint, listOf(name))
                    val mapping = SpecEdits.optionMapping(spec, entryPoint, target.path) ?: continue
                    val roleDefault = own.defaults[name]
                    val documented = SpecDefaults.documentedDefault(option)
                    when {
                        roleDefault != null -> {
                            val value = RoleValue(roleDefault, emptyList(), roleDefault.value, roleDefault.keyRange)
                            if (documented != null) mismatch(target, option, value, mapping) else undocumented(target, option, value, mapping)
                            subOptions(target, option, value)
                        }
                        documented != null -> notApplied(target, option, mapping)
                    }
                }
            }
            return SpecDefaultAnalysis(root, role, specFile, findings, twins())
        }

        /** S003 along `type: dict` paths whose role default value is a literal dict holding the sub-option's key. */
        private fun subOptions(target: SpecTarget, option: OptionSpec, value: RoleValue) {
            // A `no_log` dictionary keeps its keys secret too.
            if (target.path.size >= MAX_DEPTH || option.type != OptionType.Dict || option.noLog) return
            val options = option.options ?: return
            val map = value.value as? YMap ?: return
            for ((subName, sub) in options) {
                ProgressManager.checkCanceled()
                val entry = map.entries.lastOrNull { it.key.text == subName } ?: continue
                val subTarget = SpecTarget(target.entryPoint, target.path + subName)
                val mapping = SpecEdits.optionMapping(spec, subTarget.entryPoint, subTarget.path) ?: continue
                val subValue = RoleValue(value.default, value.valuePath + subName, entry.value, entry.key.range)
                if (SpecDefaults.documentedDefault(sub) != null) mismatch(subTarget, sub, subValue, mapping)
                subOptions(subTarget, sub, subValue)
            }
        }

        /** A `no_log` option, a `vault_*` name on the path, or a dict with a `no_log` or `vault_*` sub-option below it. */
        private fun isSecret(target: SpecTarget, option: OptionSpec): Boolean =
            SpecDefaults.hasNoLog(option) || target.path.any { it.startsWith(VAULT_PREFIX) } || hasVaultSubOption(option, 0)

        private fun hasVaultSubOption(option: OptionSpec, depth: Int): Boolean = depth < MAX_DEPTH &&
            option.options.orEmpty().any { (name, sub) -> name.startsWith(VAULT_PREFIX) || hasVaultSubOption(sub, depth + 1) }

        // -------------------------------------------------------------------------------------------- ANS-S003

        private fun mismatch(target: SpecTarget, option: OptionSpec, value: RoleValue, mapping: YAMLMapping) {
            if (!comparable(value.default)) return
            val secret = isSecret(target, option) || value.default.isSecret
            val verdict = comparator.compare(option, value.value, secret) as? SpecDefaults.Verdict.Mismatch ?: return
            val documented = SpecDefaults.documentedDefault(option) ?: return
            val keyValue = mapping.getKeyValueByKey(DEFAULT) ?: return
            val via = verdict.via
            // The chain's literal is what the fix documents: the role default of the last variable of the chain.
            val source = if (via != null) own.defaults[via]?.takeIf(::comparable)?.let { RoleValue(it, emptyList(), it.value, it.keyRange) } ?: return else value
            val used = if (via != null) {
                AnsibilitySpecBundle.message("inspection.s003.value.chain", display(source.value), via)
            } else {
                display(value.value)
            }
            val label = label(role, value.file, value.keyOffset)
            val message = if (several) {
                AnsibilitySpecBundle.message("inspection.s003.message.entry", display(documented), target.path.joinToString("."), used, label, target.entryPoint)
            } else {
                AnsibilitySpecBundle.message("inspection.s003.message", display(documented), target.path.joinToString("."), used, label)
            }
            val fixValue = if (SpecDefaultEdits.canDocument(keyValue.parentMapping, project, source)) display(source.value) else null
            val finding = SpecDefaultFinding(DiagnosticCode.S003_SPEC_DEFAULT_MISMATCH, verdict.cap, anchor(keyValue), message, target, source, fixValue)
            findings += finding
            val keyRange = value.keyRange ?: return
            val twinKey = value.file to keyRange.start
            twins.getOrPut(twinKey) { ArrayList() } += target to finding
            twinSources.putIfAbsent(twinKey, value to source)
        }

        private fun twins(): List<SpecDefaultTwin> = twins.map { (key, list) ->
            val (value, source) = twinSources.getValue(key)
            val first = list.first().second
            val documented = SpecDefaults.documentedDefault(optionAt(list.first().first) ?: return@map null) ?: return@map null
            val where = label(role, specFile, first.range.startOffset)
            val message = if (list.size > 1) {
                AnsibilitySpecBundle.message("inspection.s003.twin.several", display(documented), first.target.path.joinToString("."), where, list.size - 1)
            } else {
                AnsibilitySpecBundle.message("inspection.s003.twin", display(documented), first.target.path.joinToString("."), where)
            }
            val range = value.keyRange ?: return@map null
            SpecDefaultTwin(value.file, TextRange(range.start, range.end), message, list.map { it.first }, source, first.fixValue)
        }.filterNotNull()

        private fun optionAt(target: SpecTarget): OptionSpec? {
            var option = entryPoints[target.entryPoint]?.options?.get(target.path.first()) ?: return null
            for (name in target.path.drop(1)) option = option.options?.get(name) ?: return null
            return option
        }

        // -------------------------------------------------------------------------------------------- ANS-S004

        private fun notApplied(target: SpecTarget, option: OptionSpec, mapping: YAMLMapping) {
            if (option.required || own.opaque) return
            val name = target.path.single()
            // A found default, or an unreadable defaults file (of a dependency too) that may set it: nothing to report.
            val lookup = RoleDefaults.lookup(project, root, role, name)
            if (lookup !is RoleDefaults.Lookup.None || lookup.opaque) return
            // The rules read the variable index (the role's tasks, `vars/`): follow every vars-like PSI change.
            if (!indexRecorded) {
                val tracker = VarsDocuments.tracker(project)
                ModelInputs.external(tracker.modificationCount) { tracker.modificationCount }
                indexRecorded = true
            }
            if (rules.hasRuntimeDefault(role, name) || rules.setByRoleTasks(role, name)) return
            val keyValue = mapping.getKeyValueByKey(DEFAULT) ?: return
            val documented = SpecDefaults.documentedDefault(option) ?: return
            val secret = isSecret(target, option) || SpecDefaults.containsVault(documented)
            val roleName = role.ref.name
            val message = when {
                secret && several -> AnsibilitySpecBundle.message("inspection.s004.message.secret.entry", name, roleName, target.entryPoint)
                secret -> AnsibilitySpecBundle.message("inspection.s004.message.secret", name, roleName)
                several -> AnsibilitySpecBundle.message("inspection.s004.message.entry", display(documented), name, roleName, target.entryPoint)
                else -> AnsibilitySpecBundle.message("inspection.s004.message", display(documented), name, roleName)
            }
            // The fix is offered only where it writes: a file that can take the line, a value that can be copied.
            val fixTarget = AddDocumentedDefaultToDefaultsFix.targetLabel(project, role.ref.dir)
            val writable = fixTarget != null && SpecDefaultEdits.topLevelLine(name, keyValue) != null
            val fixValue = if (secret || !writable) null else display(documented)
            findings += SpecDefaultFinding(
                DiagnosticCode.S004_SPEC_DEFAULT_NOT_APPLIED, Level.ERROR, anchor(keyValue), message, target, null, fixValue, fixTarget,
            )
        }

        // -------------------------------------------------------------------------------------------- ANS-S005

        private fun undocumented(target: SpecTarget, option: OptionSpec, value: RoleValue, mapping: YAMLMapping) {
            if (option.required || !comparable(value.default)) return
            if (isSecret(target, option) || value.default.isSecret) return
            val roleValue = value.value
            if (SpecDefaults.isNull(roleValue) || SpecDefaultEdits.containsTemplate(roleValue)) return
            val key = (mapping.parent as? YAMLKeyValue)?.key ?: return
            val name = target.path.single()
            val label = label(role, value.file, value.keyOffset)
            val message = if (several) {
                AnsibilitySpecBundle.message("inspection.s005.message.entry", name, display(roleValue), label, target.entryPoint)
            } else {
                AnsibilitySpecBundle.message("inspection.s005.message", name, display(roleValue), label)
            }
            val fixValue = if (SpecDefaultEdits.canDocument(mapping, project, value)) display(roleValue) else null
            findings += SpecDefaultFinding(DiagnosticCode.S005_SPEC_DEFAULT_UNDOCUMENTED, Level.ERROR, key.textRange, message, target, value, fixValue)
        }

        /** The `default:` value when it fits on one line, else the `default` key. */
        private fun anchor(keyValue: YAMLKeyValue): TextRange {
            val value = keyValue.value
            return if (value != null && '\n' !in value.text) value.textRange else keyValue.key?.textRange ?: keyValue.textRange
        }
    }
}
