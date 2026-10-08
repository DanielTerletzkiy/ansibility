package de.terletzkiy.ansibility.typeflow

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.api.SpecBinding
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.index.IndexInput
import de.terletzkiy.ansibility.index.JinjaBearing
import de.terletzkiy.ansibility.index.PathFacts
import de.terletzkiy.ansibility.index.VarDefIndexer
import de.terletzkiy.ansibility.resolve.VarDefinitions
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.coerce.CoreSemantics
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.schema.OptionType
import de.terletzkiy.ansibility.semantics.typeflow.JinjaTypeEvaluator
import de.terletzkiy.ansibility.semantics.typeflow.TemplateTypes
import de.terletzkiy.ansibility.semantics.typeflow.TemplatedMismatch
import de.terletzkiy.ansibility.semantics.typeflow.TemplatedTypeCheck
import de.terletzkiy.ansibility.semantics.typeflow.TemplatingRules
import de.terletzkiy.ansibility.semantics.validate.SpecValidator
import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.YEntry
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * One ANS-T020 finding: a templated value of a spec'd variable whose logical and runtime types are both outside the
 * documented type (plan A.6 must-rule, F3.4).
 *
 * @property range the templated YAML scalar in the file
 * @property path the variable name and the keys/indices below it (`["haproxy_servers", "0", "port"]`)
 * @property documented the documented type checked (`type`, or `elements` when [elements])
 * @property bindings the same-root specs declaring this documented type for the value (the message names their roles)
 * @property reachable whether some play of the root applies one of the declaring roles (D6 context; never lowers
 *   the severity unless "Require a reachable play for red" is on)
 */
class TemplatedFinding(
    val range: TextRange,
    val path: List<String>,
    val documented: OptionType,
    val elements: Boolean,
    val bindings: List<SpecBinding>,
    val mismatch: TemplatedMismatch,
    val reachable: Boolean,
    val message: String,
) {
    /** The analysed template. */
    val types: TemplateTypes get() = mismatch.types

    override fun toString(): String = "TemplatedFinding(${path.joinToString(".")}, $documented, $message)"
}

/**
 * Finds the ANS-T020 findings of one file (plan A.5 `JinjaTypeEvaluator`, A.6, F3.4).
 *
 * **Where.** The variable assignments ansible-core validates against role argument specs, at the same places the
 * literal checks look and `VarService` indexes: role `defaults/` and `vars/`, inventory and playbook `group_vars` and
 * `host_vars`, `hosts.yml` inline vars, molecule inventories and `molecule/vars`, playbook-level vars files, play,
 * block and task `vars:`, `include_role`/`import_role` vars and role parameters of `roles:` entries. A name counts
 * when some role spec of the same root declares it; every declaring spec is checked, nested values follow `options`
 * and `elements` (up to four levels, like the literal checks).
 *
 * **What.** Each Jinja-bearing scalar is evaluated by [JinjaTypeEvaluator] with the root's target ansible-core
 * ([TargetVersionDetector], pinned 2.18.8 when unknown), `jinja2_native` (settings override, else `ansible.cfg`) and
 * the root's chain depth; bare chains resolve through [ChainResolver]. [TemplatedTypeCheck] applies the must-rule.
 *
 * Call inside a read action in smart mode (the chain resolution uses indexes).
 */
internal class TemplatedValueTypes(private val project: Project, private val file: YAMLFile, private val context: FileContext) {
    private val root: AnsibleRoot = context.root
    private val core: CoreSemantics = CoreSemantics(TargetVersionDetector.getInstance(project).targetVersion(root).version ?: CoreVersion.PINNED)
    private val rules = TemplatingRules(core, jinja2Native(project, root))
    private val settings = AnsibilityProjectSettings.getInstance(project).rootSettings(root)
    private val resolver = ChainResolver(project, root, context.roleDir, file.originalFile.virtualFile ?: file.viewProvider.virtualFile)
    private val evaluator = JinjaTypeEvaluator(rules, LexerJinjaTokenizer, resolver, settings.effectiveChainDepth, ProgressManager::checkCanceled)
    private val check = TemplatedTypeCheck(core)
    private val messages = TemplatedMessages(core.version)
    private val reachability = HashMap<Pair<String, String>, Boolean>()

    /** The findings of the file, in file order. */
    fun findings(): List<TemplatedFinding> {
        if (context.kind !in CHECKED_FILE_KINDS) return emptyList()
        val text = file.viewProvider.contents
        if (!JinjaBearing.hasTemplateMarkers(text)) return emptyList()
        val service = VarService.getInstance(project)
        val findings = ArrayList<TemplatedFinding>()
        for ((name, entry) in assignments(text)) {
            ProgressManager.checkCanceled()
            val range = entry.value.range ?: continue
            if (!JinjaBearing.hasTemplateMarkers(text.subSequence(range.start, range.end))) continue
            val bindings = service.symbol(root, name).specBindings
            if (bindings.isEmpty()) continue
            findings += check(name, entry.value, bindings)
        }
        return findings.sortedWith(compareBy({ it.range.startOffset }, { it.path.size }))
    }

    /** One documented type of one templated leaf, and the specs that document it. */
    private data class Leaf(val scalar: YScalar, val path: List<String>, val documented: OptionType, val elements: Boolean)

    private fun check(name: String, value: YValue, bindings: List<SpecBinding>): List<TemplatedFinding> {
        val leaves = LinkedHashMap<Leaf, MutableList<SpecBinding>>()
        for (binding in bindings) {
            collectLeaves(binding.option, value, listOf(name), 0) { leaf -> leaves.getOrPut(leaf) { ArrayList() } += binding }
        }
        val types = HashMap<YScalar, TemplateTypes>()
        return leaves.mapNotNull { (leaf, declaring) ->
            val range = leaf.scalar.range ?: return@mapNotNull null
            val evaluated = types.getOrPut(leaf.scalar) { evaluate(leaf.scalar) }
            val mismatch = check.check(leaf.documented, evaluated) ?: return@mapNotNull null
            val reachable = declaring.any { reachable(it) }
            TemplatedFinding(
                TextRange(range.start, range.end), leaf.path, leaf.documented, leaf.elements, declaring, mismatch, reachable,
                messages.message(leaf.documented, leaf.elements, leaf.path, declaring, mismatch, reachable),
            )
        }
    }

    /**
     * The types of the templated [scalar]: from the Jinja PSI injected into its YAML scalar when the platform has built
     * it and it reads the same value ([PsiTemplateTypes.templateOf]), else from its text; both give the same types.
     */
    private fun evaluate(scalar: YScalar): TemplateTypes {
        val template = hostOf(scalar)?.let { PsiTemplateTypes.templateOf(it, scalar.text) }
        return if (template != null) evaluator.evaluate(template) else evaluator.evaluate(scalar.text)
    }

    /** The YAML scalar PSI that [scalar] was loaded from, or null when the value is not one written in [file]. */
    private fun hostOf(scalar: YScalar): YAMLScalar? {
        val range = scalar.range ?: return null
        val element = file.findElementAt(range.start) ?: return null
        val host = PsiTreeUtil.getParentOfType(element, YAMLScalar::class.java, false) ?: return null
        return host.takeIf { it.textRange.startOffset == range.start && it.textRange.endOffset == range.end }
    }

    /** The templated scalars of [value] with the type [option] documents for each (DocumentedTypeChecks' walk). */
    private fun collectLeaves(option: OptionSpec, value: YValue, path: List<String>, depth: Int, sink: (Leaf) -> Unit) {
        if (value is YScalar && isTemplated(value)) {
            sink(Leaf(value, path, option.type, elements = false))
            return
        }
        if (depth >= MAX_DEPTH) return
        if (option.type == OptionType.List && value is YSeq) {
            val elements = option.elements
            value.items.forEachIndexed { index, item ->
                val itemPath = path + index.toString()
                if (elements != null && item is YScalar && isTemplated(item)) sink(Leaf(item, itemPath, elements, elements = true))
                if (elements == OptionType.Dict && item is YMap) subOptions(option, item, itemPath, depth, sink)
            }
        }
        if (option.type == OptionType.Dict && value is YMap) subOptions(option, value, path, depth, sink)
    }

    private fun subOptions(option: OptionSpec, map: YMap, path: List<String>, depth: Int, sink: (Leaf) -> Unit) {
        val options = option.options ?: return
        val entries = map.entries.associateBy { it.key.text }
        for ((name, sub) in options) {
            val key = sub.aliases.lastOrNull { it in entries } ?: name.takeIf { it in entries } ?: continue
            collectLeaves(sub, entries.getValue(key).value, path + name, depth + 1, sink)
        }
    }

    /**
     * The checked assignments of the file: (variable name, entry) for every definition `ansible.var.def` reads here
     * whose query-time kind is in [CHECKED_DEFINITION_KINDS]. Merge-key copies resolve to the written entry.
     */
    private fun assignments(text: CharSequence): List<Pair<String, YEntry>> {
        val virtualFile = file.viewProvider.virtualFile
        val indexed = VarDefIndexer.index(IndexInput(PathFacts.of(virtualFile), text) { file })
        val offsets = HashMap<Int, String>()
        for ((name, entries) in indexed) {
            for (entry in entries) {
                val kind = VarDefinitions.classify(entry, context)?.first ?: continue
                if (kind in CHECKED_DEFINITION_KINDS) offsets[entry.offset] = name
            }
        }
        if (offsets.isEmpty()) return emptyList()
        val document = PsiYValueAdapter.documentValue(file) ?: return emptyList()
        val found = LinkedHashMap<Int, Pair<String, YEntry>>()
        fun walk(value: YValue, depth: Int) {
            if (depth > MAX_WALK_DEPTH) return
            when (value) {
                is YMap -> for (entry in value.entries) {
                    ProgressManager.checkCanceled()
                    val start = entry.key.range?.start
                    val name = start?.let(offsets::get)
                    if (name != null && name == entry.key.text) found.putIfAbsent(start, name to entry)
                    walk(entry.value, depth + 1)
                }
                is YSeq -> value.items.forEach { walk(it, depth + 1) }
                else -> Unit
            }
        }
        walk(document, 0)
        return found.values.toList()
    }

    private fun reachable(binding: SpecBinding): Boolean = reachability.getOrPut(binding.role.name to binding.entryPoint) {
        PlayGraph.getInstance(project).playsApplying(root, binding.role.name, binding.entryPoint).isNotEmpty()
    }

    companion object {
        /** How deep below the top-level option nested values are checked (as the literal documented-type checks). */
        const val MAX_DEPTH: Int = 4
        private const val MAX_WALK_DEPTH = 64

        /** The file kinds with assignments ansible-core validates against role specs. */
        val CHECKED_FILE_KINDS: Set<FileKind> = setOf(
            FileKind.ROLE_DEFAULTS, FileKind.ROLE_VARS, FileKind.GROUP_VARS, FileKind.HOST_VARS, FileKind.INVENTORY,
            FileKind.MOLECULE_CONFIG, FileKind.MOLECULE_VARS, FileKind.ROLE_TASKS, FileKind.ROLE_HANDLERS,
            FileKind.PLAYBOOK, FileKind.MOLECULE_PLAYBOOK, FileKind.MOLECULE_TASKS, FileKind.OTHER,
        )

        /** The definition kinds whose values are checked. */
        val CHECKED_DEFINITION_KINDS: Set<VarDefKind> = setOf(
            VarDefKind.ROLE_DEFAULT, VarDefKind.ROLE_VAR, VarDefKind.GROUP_VARS, VarDefKind.HOST_VARS,
            VarDefKind.INVENTORY_INLINE, VarDefKind.MOLECULE_INVENTORY, VarDefKind.VARS_FILES, VarDefKind.PLAY_VARS,
            VarDefKind.BLOCK_VARS, VarDefKind.TASK_VARS, VarDefKind.INCLUDE_PARAMS, VarDefKind.ROLE_PARAMS,
        )

        /** Whether ansible-core templates [scalar] (a string with `{{`, `{%` or `{#`, not `!unsafe`). */
        fun isTemplated(scalar: YScalar): Boolean = scalar.resolved is Resolved.Str && SpecValidator.containsJinja(scalar)

        /** `jinja2_native` for [root]: the settings override, else `ansible.cfg` `[defaults]`, else off. */
        fun jinja2Native(project: Project, root: AnsibleRoot): Boolean {
            AnsibilityProjectSettings.getInstance(project).rootSettings(root).cfgOverrides.jinja2Native?.let { return it }
            val workspace = AnsibleWorkspace.getInstance(project) as? AnsibleWorkspaceImpl ?: return false
            val value = workspace.configOf(root)?.value("defaults", "jinja2_native") ?: return false
            return value.trim().lowercase() in CFG_TRUE
        }

        /** ansible-core's `boolean()` spellings of true for config values. */
        private val CFG_TRUE = setOf("y", "yes", "on", "1", "true", "t", "1.0")
    }
}
