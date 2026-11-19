package de.terletzkiy.ansibility.vars.hints

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.EvalTarget
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.api.HostScope
import de.terletzkiy.ansibility.api.RuntimeMarkerKind
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.ValueShape
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarDefinition
import de.terletzkiy.ansibility.api.VarSourceRef
import de.terletzkiy.ansibility.api.VarSymbol
import de.terletzkiy.ansibility.api.VarsLayer
import de.terletzkiy.ansibility.context.MoleculeView
import de.terletzkiy.ansibility.context.MoleculeVisibility
import de.terletzkiy.ansibility.context.host.AnsibleContextServiceImpl
import de.terletzkiy.ansibility.context.host.RootEffectiveSummaries
import de.terletzkiy.ansibility.context.host.RootEffectiveSummary
import de.terletzkiy.ansibility.context.host.SummaryDefinition
import de.terletzkiy.ansibility.context.host.card.TaskVars
import de.terletzkiy.ansibility.facts.FactsCatalog
import de.terletzkiy.ansibility.index.UseContainer
import de.terletzkiy.ansibility.index.UseEntry
import de.terletzkiy.ansibility.index.ValueSummary
import de.terletzkiy.ansibility.model.effective.ExecutionSources
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.resolve.VarViews
import de.terletzkiy.ansibility.semantics.validate.SpecDefaults
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.semantics.yaml.YVault
import de.terletzkiy.ansibility.vars.AnsibilityVarsBundle.message
import de.terletzkiy.ansibility.vars.LoopItems
import de.terletzkiy.ansibility.vars.SeenDefinitions
import de.terletzkiy.ansibility.vars.VarContextWinner
import de.terletzkiy.ansibility.vars.VarLabels
import de.terletzkiy.ansibility.vars.VarLocations
import de.terletzkiy.ansibility.vars.VarSourceTexts
import de.terletzkiy.ansibility.vars.VaultInfo
import de.terletzkiy.ansibility.vars.usages.OpenFileIndexData
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import org.jetbrains.annotations.Nls
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * The inline variable sources of one file (plan amendment R31, D232–D237, as amended by the R31 fixes): a hint after a
 * variable reference that says what the hosts get there, per environment.
 *
 * ```
 * {{ app_version }} ‹default 1.2.0 · prod 1.4.1 / 1.4.2-hotfix · test 1.3.0›
 * {{ app_version }} ‹→ 1.4.1 on 2 of 3 hosts (prod) · default 1.2.0›     (an environment or host selected)
 * {{ app_port }}    ‹task vars 8080›                                        (the task's own `vars:` set it)
 * ```
 *
 * - **Effective values (decision 1).** An environment's entry is what its hosts **get** at this usage: the hosts the
 *   file applies to whatever the selection ([AnsibleContextServiceImpl.allHostsScopeAt], the scope the inspections use:
 *   a role file's hosts are those of the plays that apply the role, a playbook's those of the play at the reference),
 *   and per host the winning definition from the root's background summary ([RootEffectiveSummary.Scope.winners]: the
 *   precedence engine's winner for a task of the running role; role `vars/`, play vars, playbook-level vars and role
 *   params count as there). Hosts that agree read `prod 1.4.1`, hosts that disagree `prod 1.4.1 / 1.4.2-hotfix` (two
 *   values, then `+N`); environments that get the same values share an entry (`stage, test 2.0.0`); an environment
 *   whose hosts all get the role's default is left out (the `default` entry says it). So nothing that never reaches the
 *   role's hosts counts (decision 2), and a vars directory several inventories share counts for each of them.
 * - **Shadowing at the usage (decision 3).** The block and task vars and include params in force at the reference
 *   ([TaskVars], as the card applies them) and a `set_fact` of the name earlier in the same file (unconditional) replace
 *   the static winners where they beat them; when they win on every host the hint names them: `task vars 8080`,
 *   `set_fact 9000`. A reference whose value depends on the path that runs it gets no hint: a template that some
 *   rendering tasks give their own value, include tasks that give it on some paths, a `set_fact` in another file or a
 *   conditional one, `include_vars` or `register` of the name in the plays or roles that run the file. Loop variables,
 *   template locals, facts and magic variables never get one.
 * - **Where (decision 4).** Tasks, handlers, playbooks and templates. With [OPTION_VARS_FILES] also inventory files (the
 *   winners for the hosts the file applies to, rule 3 of [de.terletzkiy.ansibility.vars.VarNavigation]) and role
 *   `defaults/`/`vars/` and `vars_files` files (what [SeenDefinitions] says the file sees there). Never in Molecule files.
 * - **Quiet (decision 5).** A hint only when the reference's values differ: at least two distinct values among the
 *   role's default and the hosts' values ([OPTION_SINGLE] brings back the rest); none when every value is a vault or
 *   `no_log` value (`🔒`). One hint per name in one YAML scalar or one template line. The visible text stops after about
 *   [MAX_VISIBLE] characters with `+N`; the tooltip has every value with its definition.
 * - **Accessors (decision 6).** After `app_cfg.port` the value is the nested key of a literal mapping (`default 80 ·
 *   test 8443`), `?` when the definition is no such mapping; never the whole container.
 * - **A selected context** (an environment or a host): `→ 1.4.1 (prod)` when all its hosts agree, `→ 1.4.1 on 2 of 3
 *   hosts (prod)` otherwise ([VarContextWinner.outcomes], the card's evaluation), then the default. Whether a hint is
 *   shown never depends on the selection.
 *
 * Values are the index's vault-safe previews ([ValueSummary.preview]); a vault value, a `vault_*` name, a vault file or
 * a variable a spec keeps secret reads `🔒`; nothing is decrypted. Speed (D237): the references come from the file's
 * own `ansible.var.use` entries (cached on its PSI), the winners from the summary (the targets resolved once per scope,
 * then a few map lookups per name and host); other files' PSI only for accessor values. Call in a read action in smart
 * mode.
 */
internal object VarSourceHints {
    /** The provider id: the one platform switch (Settings › Editor › Inlay Hints, the hint's menu, the toggle action). */
    const val PROVIDER_ID: String = "ansibility.variable.sources"

    /** The provider option "Also when every environment gets the same value" (the R31 id, renamed by decision 5). */
    const val OPTION_SINGLE: String = "single.sources"

    /** The provider option "Also in vars and inventory files" (decision 4). */
    const val OPTION_VARS_FILES: String = "vars.files"

    /** The click handler's id (`codeInsight.inlayActionHandler`). */
    const val HANDLER_ID: String = "ansibility.variable.sources"

    /** Visible characters after which the remaining entries collapse to `+N` (decision 5: about 45). */
    const val MAX_VISIBLE: Int = 46

    /** Values of one entry before `+N`. */
    const val MAX_VALUES: Int = 2

    /** Tooltip rows before "… and N more". */
    private const val MAX_TOOLTIP_ROWS = 20

    /** Labels of an entry are cut like values. */
    private const val MAX_LABEL = 24

    /** Hosts a tooltip row names before it counts them. */
    private const val MAX_HOSTS_NAMED = 2

    /** The text of a value the hint cannot tell (decision 6: `prod ?`). */
    private const val UNKNOWN = "?"

    /** File kinds whose Jinja-looking text Ansible never templates (as [de.terletzkiy.ansibility.resolve.VarUsageQuery]). */
    private val NOT_TEMPLATED = setOf(FileKind.ROLE_ARGSPEC, FileKind.ANSIBLE_CFG, FileKind.LINT_CONFIG, FileKind.REQUIREMENTS)

    /** Where the hints appear by default (decision 4): tasks, handlers, playbooks and templates. */
    private val CODE_KINDS = setOf(FileKind.ROLE_TASKS, FileKind.ROLE_HANDLERS, FileKind.PLAYBOOK, FileKind.ROLE_TEMPLATE)

    /** Inventory files ([OPTION_VARS_FILES]): what the hosts they apply to get. */
    private val INVENTORY_KINDS = setOf(FileKind.GROUP_VARS, FileKind.HOST_VARS, FileKind.INVENTORY, FileKind.INVENTORY_INI)

    /** Role vars and `vars_files` files ([OPTION_VARS_FILES]): what [SeenDefinitions] says the file sees. */
    private val SEEN_KINDS = setOf(FileKind.ROLE_DEFAULTS, FileKind.ROLE_VARS, FileKind.OTHER)

    /** Definitions an include task gives that the engine already evaluates (`include_role`/`import_role` vars, role params). */
    private val ENGINE_INCLUDED = setOf(VarDefKind.INCLUDE_PARAMS, VarDefKind.ROLE_PARAMS)

    /** Task keys that make a `set_fact` run conditionally or several times. */
    private const val WHEN = "when"
    private const val LOOP = "loop"
    private const val WITH_PREFIX = "with_"
    private const val VAULT_PREFIX = "vault_"

    /** One entry of a hint, uncut: environments (or `default`, `task vars` …) and the values they get, most hosts first. */
    class Entry(val labels: List<String>, val values: List<String>) {
        override fun toString(): String = labels.joinToString(", ") + " " + values.joinToString(" / ")
    }

    /** One hint at [offset] (after the reference) for the reference of [name] starting at [reference]. */
    class Hint(
        val offset: Int,
        val name: String,
        val reference: Int,
        /** The text in pieces of at most 30 characters (the declarative hints' segment limit). */
        val segments: List<String>,
        @Nls val tooltip: String,
        /** Every entry of the text without a selected context, none cut away (what the tooltip details). */
        val entries: List<Entry>,
    ) {
        val text: String get() = segments.joinToString("")

        /**
         * The click payload: how far the hint sits after the start of its reference, and the name. The handler finds the
         * reference from the inlay's current offset (decision 9), so an edit above the hint never sends it elsewhere.
         */
        val payload: String get() = "${offset - reference}:$name"
    }

    /** `distance:name` of a click payload (the hint's distance from its reference's start), or null. */
    fun parsePayload(payload: String?): Pair<Int, String>? {
        val colon = payload?.indexOf(':') ?: return null
        if (colon <= 0) return null
        val distance = payload.substring(0, colon).toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return distance to payload.substring(colon + 1)
    }

    /**
     * The hints of [file], in offset order; [includeSame]: also where every environment gets the same value
     * ([OPTION_SINGLE]); [inVarsFiles]: also in vars and inventory files ([OPTION_VARS_FILES]).
     */
    fun collect(project: Project, file: PsiFile, includeSame: Boolean, inVarsFiles: Boolean = false): List<Hint> {
        val virtualFile = file.viewProvider.virtualFile
        val context = AnsibleWorkspace.getInstance(project).contextOf(virtualFile) ?: return emptyList()
        if (context.kind in NOT_TEMPLATED || MoleculeVisibility.isMoleculeFile(project, virtualFile)) return emptyList()
        val mode = when {
            context.kind in CODE_KINDS -> Mode.CODE
            !inVarsFiles -> return emptyList()
            context.kind in INVENTORY_KINDS -> Mode.INVENTORY
            context.kind in SEEN_KINDS -> Mode.SEEN
            else -> return emptyList()
        }
        val impl = AnsibleContextServiceImpl.getInstance(project) ?: return emptyList()
        val uses = OpenFileIndexData.of(project, virtualFile).uses
        if (uses.isEmpty()) return emptyList()
        val request = Request(project, impl, context, virtualFile, file, mode, includeSame)
        val result = ArrayList<Hint>()
        for ((name, entries) in uses) {
            ProgressManager.checkCanceled()
            if (isFactOrMagic(name)) continue
            val references = request.firstPerUnit(entries.filter { !it.called && it.indirect == null && it.offset + name.length <= request.text.length })
            if (references.isEmpty()) continue
            val sources = request.sources(name) ?: continue
            for (entry in references) {
                ProgressManager.checkCanceled()
                request.hint(sources, entry)?.let(result::add)
            }
        }
        result.sortBy { it.offset }
        return result
    }

    /** Facts (`ansible_facts`, the injected `ansible_*` facts) and magic variables never get a hint. */
    private fun isFactOrMagic(name: String): Boolean =
        name == "ansible_facts" || FactsCatalog.magicVars.containsKey(name) || FactsCatalog.injected.containsKey(name)

    private enum class Mode {
        /** Tasks, handlers, playbooks, templates: each environment's effective values, the default, the shadows. */
        CODE,

        /** Inventory files: the effective values of the hosts the file applies to. */
        INVENTORY,

        /** Role vars and `vars_files` files: what [SeenDefinitions] says the file sees. */
        SEEN,
    }

    /** One value: its text in a hint and in a tooltip row, and what makes two values equal (the value as written, or its definition when the text is cut or hidden). */
    private class Value(val key: Any, val text: String, val row: String, val lock: Boolean, val unknown: Boolean = false)

    /** What one variable name brings, the same for every reference of the file. */
    private class Sources(
        val name: String,
        val symbol: VarSymbol,
        val byLocation: Map<SourceLocation, VarDefinition>,
        /** The own role's default (`main` first), shown as `default`; null outside roles or when the role has none. */
        val default: VarDefinition?,
        val noLog: Boolean,
        /** Some loop binds the name somewhere: each reference is checked with [LoopItems]. */
        val loopBound: Boolean,
    )

    /** What may change the value at runtime in the contexts of one scope: `set_fact` tasks, and whether anything else does. */
    private class Runtime(val setFacts: List<SourceLocation>, val unknown: Boolean)

    /** The rendered hint of one (scope, name, accessor path, shadow): text, tooltip rows, entries. */
    private class Rendered(
        val segments: List<String>,
        val rows: List<String>,
        val entries: List<Entry>,
        /** The default's value, for the text of a selected context. */
        val default: Value?,
        /** Every host gets one shadowing definition (task vars, include params, `set_fact`): the text never follows the selection. */
        val shadowed: Boolean,
    )

    /** The block and task vars ([TaskVars]) and `set_fact` in force at a reference, as one cache key. */
    private data class Shadow(val taskVars: TaskVars, val setFact: SourceLocation?)

    private data class RenderKey(val name: String, val path: List<String>, val shadow: Shadow)

    private class Request(
        val project: Project,
        val impl: AnsibleContextServiceImpl,
        val context: FileContext,
        val file: VirtualFile,
        val psi: PsiFile,
        val mode: Mode,
        val includeSame: Boolean,
    ) {
        val root: AnsibleRoot = context.root
        val text: CharSequence = psi.viewProvider.contents
        private val ownRole = context.roleName
        private val view = MoleculeView.of(project, file)
        private val selected = VarContextWinner.isSelected(project, root)
        private val document = PsiDocumentManager.getInstance(project).getDocument(psi)
        private val resolved = java.util.IdentityHashMap<HostScope, Resolved>()
        private val rendered = java.util.IdentityHashMap<HostScope, HashMap<RenderKey, Rendered?>>()
        private val values = HashMap<Triple<SourceLocation, String, List<String>>, Value>()
        private val tooltips = HashMap<Rendered, String>()

        // ------------------------------------------------------------------------------------------------ references

        /** The first reference of each YAML scalar or template line (decision 5: one hint per name there), in offset order. */
        fun firstPerUnit(references: List<UseEntry>): List<UseEntry> {
            if (references.size < 2) return references
            val units = HashSet<Any>()
            return references.sortedBy { it.offset }.filter { units.add(unitOf(it)) }
        }

        private fun unitOf(entry: UseEntry): Any {
            if (entry.container != UseContainer.TEMPLATE_FILE) {
                val yaml = YamlFiles.yamlFile(project, file)
                val scalar = yaml?.findElementAt(entry.offset)?.let { PsiTreeUtil.getParentOfType(it, YAMLScalar::class.java, false) }
                if (scalar != null) return scalar.textRange
            }
            return lineOf(entry.offset)
        }

        private fun lineOf(offset: Int): Int =
            document?.takeIf { offset <= it.textLength }?.getLineNumber(offset) ?: StringUtil.offsetToLineNumber(text, offset)

        /** The sources of [name], or null when no reference of it gets a hint. */
        fun sources(name: String): Sources? {
            val symbol = VarViews.symbol(project, root, name, view)
            if (symbol.definitions.isEmpty()) return null
            // A registered result of the same role (outside roles: the same file) is a runtime value, never a source hint.
            if (symbol.definitions.any { it.kind == VarDefKind.REGISTER && isLocal(it) }) return null
            val default = if (mode == Mode.CODE && ownRole != null) {
                symbol.definitions.filter { it.kind == VarDefKind.ROLE_DEFAULT && it.roleName == ownRole }.sortedWith(MAIN_FIRST).firstOrNull()
            } else {
                null
            }
            // Nothing but the role's own declaration: one value at most.
            if (!includeSame && symbol.definitions.all { it.kind == VarDefKind.SPEC_OPTION || it.location == default?.location }) return null
            val noLog = symbol.specBindings.any { SpecDefaults.hasNoLog(it.option) }
            val loopBound = symbol.definitions.any { it.kind == VarDefKind.LOOP_VAR || it.kind == VarDefKind.INDEX_VAR }
            return Sources(name, symbol, symbol.definitions.associateBy { it.location }, default, noLog, loopBound)
        }

        private fun isLocal(definition: VarDefinition): Boolean =
            if (ownRole != null) definition.roleName == ownRole else definition.location.file == file

        /** The hint of one reference, or null. */
        fun hint(sources: Sources, entry: UseEntry): Hint? {
            val name = sources.name
            if (sources.loopBound && LoopItems.bindingAt(project, file, entry.offset, name, view) != null) return null
            val path = entry.attrPath
            val rendered = when (mode) {
                Mode.CODE, Mode.INVENTORY -> effective(sources, entry, path)
                Mode.SEEN -> seen(sources, entry, path)
            } ?: return null
            var segments = rendered.segments
            var winnerText: String? = null
            if (selected && mode != Mode.SEEN && !rendered.shadowed) {
                selection(sources, entry.offset, path, rendered.default)?.let { (selectionSegments, head) ->
                    segments = selectionSegments
                    winnerText = head
                }
            }
            val tooltip = if (winnerText == null) tooltips.getOrPut(rendered) { tooltip(name, rendered.rows, null) } else tooltip(name, rendered.rows, winnerText)
            return Hint(referenceEnd(text, entry.offset, name, path), name, entry.offset, segments, tooltip, rendered.entries)
        }

        // ------------------------------------------------------------------------------------------------ effective values

        /** The scope's targets resolved once ([RootEffectiveSummary.scope]), and per name the static winners and runtime changes. */
        private inner class Resolved(val scope: HostScope) {
            val role: String? = impl.runningRole(scope)
            val targets: List<EvalTarget> = scope.targets.filter { !it.host.isMolecule }
            private val summaryScope: RootEffectiveSummary.Scope? by lazy(LazyThreadSafetyMode.NONE) {
                if (targets.isEmpty()) null else summary(scope.root).scope(targets, role)
            }
            private val moleculeTargets: List<EvalTarget> by lazy(LazyThreadSafetyMode.NONE) {
                if (mode == Mode.CODE && MoleculeVisibility.showInNavigation(project)) impl.moleculeCompanionTargets(scope) else emptyList()
            }
            private val moleculeScope: RootEffectiveSummary.Scope? by lazy(LazyThreadSafetyMode.NONE) {
                if (moleculeTargets.isEmpty()) null else summary(scope.root).scope(moleculeTargets, role)
            }
            private val winners = HashMap<String, List<VarSourceRef?>>()
            private val runtimes = HashMap<String, Runtime>()
            private val previews = HashMap<SourceLocation, String?>()

            /** The static winner of each target ([targets] order); null where nothing defines the name. */
            fun winners(sources: Sources): List<VarSourceRef?> = winners.getOrPut(sources.name) { winnersOf(sources, targets, summaryScope) }

            /** The Molecule hosts' winners while "Show Molecule in navigation and search" is on (tooltip only). */
            fun moleculeWinners(sources: Sources): List<Pair<HostKey, VarSourceRef>> {
                val refs = winnersOf(sources, moleculeTargets, moleculeScope)
                return moleculeTargets.indices.mapNotNull { i -> refs.getOrNull(i)?.let { moleculeTargets[i].host to it } }
            }

            private fun winnersOf(sources: Sources, targets: List<EvalTarget>, scope: RootEffectiveSummary.Scope?): List<VarSourceRef?> {
                val definitions = scope?.winners(sources.name) ?: return emptyList()
                return targets.indices.map { i -> definitions.getOrNull(i)?.let { refOf(sources, it, targets[i]) } }
            }

            /** The summary's winner as a source with the index's preview (the engine's own for a definition the index does not list). */
            private fun refOf(sources: Sources, definition: SummaryDefinition, target: EvalTarget): VarSourceRef {
                val location = definition.location
                val indexed = sources.byLocation[location]
                val preview = when {
                    indexed != null -> indexed.preview
                    definition.isVault -> null
                    else -> previews.getOrPut(location) { enginePreview(sources.name, location, target) }
                }
                val vault = definition.isVault || indexed?.valueShape == ValueShape.VAULT
                return VarSourceRef(location.file, location.offset, definition.layer, definition.group, definition.host, preview, vault, definition.role, definition.play)
            }

            private fun enginePreview(name: String, location: SourceLocation, target: EvalTarget): String? {
                val evaluation = impl.evaluator.evaluation(target, role, scope.root) ?: return null
                val ref = evaluation.effectiveOf(name)?.let(evaluation::ref) ?: return null
                return ref.preview.takeIf { ref.file == location.file && ref.offset == location.offset }
            }

            /**
             * What may change [sources]' variable at runtime in the scope's contexts (the engine's runtime markers): the
             * `set_fact` tasks that do not just reshape the value (`x: "{{ x | int }}"`), and whether an `include_vars` or
             * `register` does.
             */
            fun runtime(sources: Sources): Runtime = runtimes.getOrPut(sources.name) {
                val setFacts = LinkedHashSet<SourceLocation>()
                var unknown = false
                for (target in targets.distinctBy { it.play }) {
                    ProgressManager.checkCanceled()
                    val evaluation = impl.evaluator.evaluation(target, role, scope.root) ?: continue
                    for (marker in ExecutionSources.getInstance(project).runtimeMarkers(evaluation.root, evaluation.inputs, sources.name)) {
                        when (marker.kind) {
                            RuntimeMarkerKind.SET_FACT -> if (!reshapes(sources, marker.location)) setFacts += marker.location
                            RuntimeMarkerKind.INCLUDE_VARS, RuntimeMarkerKind.REGISTER -> unknown = true
                        }
                    }
                }
                Runtime(setFacts.toList(), unknown)
            }
        }

        private fun summary(root: AnsibleRoot): RootEffectiveSummary {
            // A stale summary still answers: its scopes evaluate through the current views; only a missing one is built.
            val summaries = RootEffectiveSummaries.getInstance(project)
            return summaries.current(root) ?: summaries.latest(root) ?: summaries.compute(root)
        }

        /** A `set_fact` that writes the name from itself (`x: "{{ x | int }}"`) keeps the static values flowing. */
        private fun reshapes(sources: Sources, location: SourceLocation): Boolean {
            val preview = sources.byLocation[location]?.preview ?: return false
            return Regex("(?<![A-Za-z0-9_])" + Regex.escape(sources.name) + "(?![A-Za-z0-9_])").containsMatchIn(preview)
        }

        private fun resolvedAt(offset: Int): Resolved {
            val scope = impl.allHostsScopeAt(file, offset)
            return resolved.getOrPut(scope) { Resolved(scope) }
        }

        /** The effective values at [entry] (CODE and INVENTORY modes), or null for no hint. */
        private fun effective(sources: Sources, entry: UseEntry, path: List<String>): Rendered? {
            val resolved = resolvedAt(entry.offset)
            val runtime = resolved.runtime(sources)
            if (runtime.unknown) return null
            if (mode == Mode.INVENTORY) {
                // `x: "{{ x }}"` reads the value below its own key: where that key wins, the hint cannot tell the value.
                val enclosing = enclosingKey(entry.offset, sources.name)
                if (enclosing != null && resolved.winners(sources).any { it?.location() == enclosing }) return null
            }
            val shadow = shadowAt(sources, entry.offset, path, runtime) ?: return null
            return rendered.getOrPut(resolved.scope) { HashMap() }.getOrPut(RenderKey(sources.name, path, shadow)) {
                render(sources, resolved, path, shadow)
            }
        }

        /**
         * The task vars and `set_fact` in force at [offset] (decision 3), or null when the value there depends on the
         * path that runs it.
         */
        private fun shadowAt(sources: Sources, offset: Int, path: List<String>, runtime: Runtime): Shadow? {
            val taskVars = if (mode == Mode.CODE) TaskVars.at(project, root, file, offset, sources.name) else TaskVars.NONE
            if (taskVars.partial.isNotEmpty()) return null
            if (taskVars.applied == null) {
                if (taskVars.includeParam) {
                    if (taskVars.included.map { valueOf(sources, it, path).key }.distinct().size > 1) return null
                } else if (taskVars.included.any { sources.byLocation[it.location()]?.kind !in ENGINE_INCLUDED }) {
                    return null
                }
            }
            var setFact: SourceLocation? = null
            if (runtime.setFacts.isNotEmpty()) {
                // Only an earlier, unconditional set_fact of the same file is known to run before the reference.
                if (runtime.setFacts.any { it.file != file }) return null
                val before = runtime.setFacts.filter { it.offset < offset }.maxByOrNull { it.offset }
                if (before != null) {
                    if (conditional(before.offset)) return null
                    setFact = before
                }
            }
            return Shadow(taskVars, setFact)
        }

        /** Whether the task at [offset] of this file, or a block around it, runs conditionally or in a loop. */
        private fun conditional(offset: Int): Boolean {
            val yaml = YamlFiles.yamlFile(project, file) ?: return true
            var mapping = PsiTreeUtil.getParentOfType(yaml.findElementAt(offset), YAMLMapping::class.java, false)
            while (mapping != null) {
                if (mapping.keyValues.any { it.keyText == WHEN || it.keyText == LOOP || it.keyText.startsWith(WITH_PREFIX) }) return true
                mapping = PsiTreeUtil.getParentOfType(mapping, YAMLMapping::class.java, true)
            }
            return false
        }

        private fun render(sources: Sources, resolved: Resolved, path: List<String>, shadow: Shadow): Rendered? {
            val taskVars = shadow.taskVars
            val local = taskVars.applied ?: taskVars.included.firstOrNull()?.takeIf { taskVars.includeParam }
            val setFact = shadow.setFact?.let { sources.byLocation[it] }?.let(::refOf)
            val statics = resolved.winners(sources)
            val finals = ArrayList<Pair<HostKey, VarSourceRef>>()
            resolved.targets.forEachIndexed { i, target ->
                var winner = statics.getOrNull(i)
                if (local != null) winner = taskVars.outcome(winner, emptyList())?.first
                if (setFact != null) {
                    val includeParamWins = taskVars.includeParam && winner != null && winner == local
                    if (winner == null || (!includeParamWins && winner.layer.level < VarsLayer.SET_FACT_REGISTER.level)) winner = setFact
                }
                if (winner != null) finals += target.host to winner
            }
            val default = sources.default?.let { valueOf(sources, refOf(it), path) }
            val shadows = listOfNotNull(local, setFact).map { it.location() }
            val winning = finals.map { it.second.location() }.distinct()
            // Every host gets one task var, include param or set_fact: the hint names it (decision 3).
            if (winning.size == 1 && winning.single() in shadows) {
                val ref = finals.first().second
                val value = valueOf(sources, ref, path)
                if (!visible(listOfNotNull(default, value))) return null
                val label = sources.byLocation[ref.location()]?.let(VarSourceTexts::layer) ?: VarSourceTexts.layer(ref.layer, ref.group, ref.host)
                val entry = Entry(listOf(label), listOf(value.text))
                val rows = listOf(message("hints.tooltip.shadow", row(sources, ref, value)))
                return Rendered(segments(listOf(entry)), rows, listOf(entry), default, shadowed = true)
            }
            // Per environment, the values its hosts get, most hosts first.
            val byEnvironment = sortedMapOf<String, LinkedHashMap<Any, HostValues>>()
            for ((host, ref) in finals) {
                val value = valueOf(sources, ref, path)
                val values = byEnvironment.getOrPut(VarSourceTexts.environmentName(host.environment)) { LinkedHashMap() }
                values.getOrPut(value.key) { HostValues(value) }.add(host, ref)
            }
            val all = finals.map { valueOf(sources, it.second, path) } + listOfNotNull(default)
            if (!visible(all)) return null
            // Environments with the same values share an entry; one whose hosts all get the default is left out.
            val groups = LinkedHashMap<Set<Any>, MutableList<String>>()
            for ((environment, values) in byEnvironment) {
                if (default != null && values.keys == setOf(default.key)) continue
                groups.getOrPut(values.keys) { ArrayList() } += environment
            }
            val entries = ArrayList<Entry>()
            default?.let { entries += Entry(listOf(message("hints.entry.default")), listOf(it.text)) }
            for ((keys, environments) in groups) {
                val ordered = keys.sortedWith(
                    compareByDescending<Any> { key -> environments.sumOf { byEnvironment.getValue(it).getValue(key).hosts.size } }
                        .thenBy { key -> byEnvironment.getValue(environments.first()).getValue(key).value.text },
                )
                entries += Entry(environments, ordered.map { byEnvironment.getValue(environments.first()).getValue(it).value.text })
            }
            val rows = ArrayList<String>()
            val defaultDefinition = sources.default
            if (defaultDefinition != null && default != null) {
                rows += message("hints.tooltip.for", message("hints.entry.default"), row(sources, refOf(defaultDefinition), default))
            }
            rows += winnerRows(sources, byEnvironment)
            rows += moleculeRows(sources, resolved, path)
            return Rendered(segments(entries), rows, entries, default, shadowed = false)
        }

        /** The hosts of one environment that get one value, and the definitions they get it from. */
        private class HostValues(val value: Value) {
            val hosts = LinkedHashSet<HostKey>()
            val refs = LinkedHashMap<SourceLocation, Pair<VarSourceRef, LinkedHashSet<HostKey>>>()

            fun add(host: HostKey, ref: VarSourceRef) {
                hosts += host
                refs.getOrPut(ref.location()) { ref to LinkedHashSet() }.second += host
            }
        }

        /**
         * `prod (prod-web1, prod-web3): group_vars web · prod · environments/prod/group_vars/web.yml = 1.4.1`: one row per
         * winning definition, with the environments (and, when not all of an environment's hosts get it, the hosts) it wins
         * for.
         */
        private fun winnerRows(sources: Sources, byEnvironment: Map<String, Map<Any, HostValues>>): List<String> {
            class Won(val ref: VarSourceRef, val value: Value) {
                val by = LinkedHashMap<String, Pair<Set<HostKey>, Int>>()
            }
            val won = LinkedHashMap<SourceLocation, Won>()
            for ((environment, values) in byEnvironment) {
                val total = values.values.sumOf { it.hosts.size }
                for (hostValues in values.values.sortedByDescending { it.hosts.size }) {
                    for ((location, refAndHosts) in hostValues.refs) {
                        val (ref, hosts) = refAndHosts
                        won.getOrPut(location) { Won(ref, hostValues.value) }.by[environment] = hosts to total
                    }
                }
            }
            return won.values.map { entry ->
                val who = entry.by.entries.joinToString(", ") { (environment, counts) ->
                    val (hosts, total) = counts
                    when {
                        hosts.size == total -> environment
                        hosts.size <= MAX_HOSTS_NAMED -> message("hints.tooltip.hosts.named", environment, hosts.joinToString(", ") { it.host })
                        else -> message("hints.tooltip.hosts.counted", environment, hosts.size, total)
                    }
                }
                message("hints.tooltip.for", who, row(sources, entry.ref, entry.value))
            }
        }

        /** The Molecule scenarios' values, while "Show Molecule in navigation and search" is on. */
        private fun moleculeRows(sources: Sources, resolved: Resolved, path: List<String>): List<String> {
            val winners = resolved.moleculeWinners(sources)
            if (winners.isEmpty()) return emptyList()
            val byRef = LinkedHashMap<SourceLocation, Pair<VarSourceRef, LinkedHashSet<String>>>()
            for ((host, ref) in winners) {
                byRef.getOrPut(ref.location()) { ref to LinkedHashSet() }.second += host.environment.removePrefix(HostKey.MOLECULE_PREFIX)
            }
            return byRef.values.map { (ref, scenarios) ->
                val who = message("hints.tooltip.molecule", scenarios.joinToString(", "))
                message("hints.tooltip.for", who, row(sources, ref, valueOf(sources, ref, path)))
            }
        }

        // ------------------------------------------------------------------------------------------------ rule 3

        /** What a vars file sees at [entry] ([SeenDefinitions]), labelled by layer (`vars 9999`, `defaults 80`). */
        private fun seen(sources: Sources, entry: UseEntry, path: List<String>): Rendered? {
            val seen = SeenDefinitions.of(project, file, entry.offset, context, sources.symbol, enclosingKey(entry.offset, sources.name))
            val definitions = seen.mapNotNull { it.definition }
            if (definitions.isEmpty()) return null
            val byLabel = LinkedHashMap<String, LinkedHashMap<Any, Value>>()
            val rows = ArrayList<String>()
            for (definition in definitions) {
                val ref = refOf(definition)
                val value = valueOf(sources, ref, path)
                byLabel.getOrPut(VarSourceTexts.layer(definition)) { LinkedHashMap() }.putIfAbsent(value.key, value)
                rows += row(sources, ref, value)
            }
            if (!visible(byLabel.values.flatMap { it.values })) return null
            val entries = byLabel.map { (label, values) -> Entry(listOf(label), values.values.map { it.text }) }
            return Rendered(segments(entries), rows, entries, null, shadowed = true)
        }

        /** The key whose value holds the reference (`x: "{{ x }}"`), which a reference never refers to. */
        private fun enclosingKey(offset: Int, name: String): SourceLocation? {
            val yaml = YamlFiles.yamlFile(project, file) ?: return null
            var element = yaml.findElementAt(offset)
            while (element != null && element !is PsiFile) {
                if (element is YAMLKeyValue && element.keyText == name) return element.key?.let { SourceLocation(file, it.textRange.startOffset) }
                element = element.parent
            }
            return null
        }

        // ------------------------------------------------------------------------------------------------ selection

        /**
         * `→ 1.4.1 on 2 of 3 hosts (prod) · default 1.2.0` for the selected context (decision 1), with its head for the
         * tooltip; null when the selection does not apply to the reference.
         */
        private fun selection(sources: Sources, offset: Int, path: List<String>, default: Value?): Pair<List<String>, String>? {
            val groups = VarContextWinner.outcomes(project, root, file, offset, sources.name) ?: return null
            val byValue = LinkedHashMap<Any, Pair<Value, LinkedHashSet<HostKey>>>()
            for (group in groups) {
                val winner = group.winner ?: continue
                val value = valueOf(sources, winner, path)
                byValue.getOrPut(value.key) { value to LinkedHashSet() }.second += group.hosts
            }
            if (byValue.isEmpty()) return null
            val hosts = byValue.values.flatMapTo(LinkedHashSet()) { it.second }
            val (value, on) = byValue.values.maxBy { it.second.size }
            val label = AnsibleContextService.getInstance(project).selection(root).host
                ?: hosts.map { VarSourceTexts.environmentName(it.environment) }.distinct().joinToString(", ")
            val segments = mutableListOf(message("hints.winner.arrow") + " ", value.text)
            if (on.size < hosts.size) segments += message("hints.winner.on", on.size, hosts.size)
            segments += " ("
            segments += VarSourceTexts.cut(label, MAX_LABEL)
            segments += ")"
            val head = segments.joinToString("")
            if (default != null && default.key != value.key) {
                val tail = listOf(VarLabels.SEPARATOR, message("hints.entry.default") + " ", default.text)
                if (head.length + tail.sumOf { it.length } <= MAX_VISIBLE) segments += tail
            }
            return segments to head
        }

        // ------------------------------------------------------------------------------------------------ texts

        /** Whether [values] earn a hint: two distinct ones (or one, with [includeSame]), and not only locks or unknowns. */
        private fun visible(values: List<Value>): Boolean {
            if (values.isEmpty() || values.all { it.lock || it.unknown }) return false
            val distinct = values.mapTo(HashSet()) { it.key }.size
            return distinct >= 2 || includeSame
        }

        /** `default 1.2.0 · prod 1.4.1 / 1.4.2-hotfix · +1`: entries while about [MAX_VISIBLE] characters last. */
        private fun segments(entries: List<Entry>): List<String> {
            val segments = ArrayList<String>()
            var length = 0
            for ((index, entry) in entries.withIndex()) {
                val parts = entrySegments(entry)
                val size = parts.sumOf { it.length } + if (index > 0) VarLabels.SEPARATOR.length else 0
                if (index > 0 && length + size > MAX_VISIBLE) {
                    segments += VarLabels.SEPARATOR
                    segments += message("hints.rest", entries.size - index)
                    break
                }
                if (index > 0) segments += VarLabels.SEPARATOR
                segments += parts
                length += size
            }
            return segments
        }

        private fun entrySegments(entry: Entry): List<String> {
            val segments = mutableListOf(VarSourceTexts.cut(entry.labels.joinToString(", "), MAX_LABEL) + " ")
            entry.values.take(MAX_VALUES).forEachIndexed { index, value ->
                if (index > 0) segments += " / "
                segments += value
            }
            if (entry.values.size > MAX_VALUES) segments += " " + message("hints.rest", entry.values.size - MAX_VALUES)
            return segments
        }

        /** `group_vars web · prod · environments/prod/group_vars/web.yml = 1.4.1`: a definition and its value (D235). */
        private fun row(sources: Sources, ref: VarSourceRef, value: Value): String {
            val definition = sources.byLocation[ref.location()]
            val layer = definition?.let(VarSourceTexts::layer) ?: VarSourceTexts.layer(ref.layer, ref.group, ref.host)
            val owner = definition?.let { VarSourceTexts.owner(it, project) }
            val located = message("source.row.value", VarLocations.path(root, ref.file), value.row)
            return listOfNotNull(layer, owner, located).joinToString(VarLabels.SEPARATOR)
        }

        private fun tooltip(name: String, rows: List<String>, winner: String?): String {
            val lines = ArrayList<String>()
            lines += "<b>" + StringUtil.escapeXmlEntities(message("hints.tooltip.title", name)) + "</b>"
            if (winner != null) lines += StringUtil.escapeXmlEntities(message("hints.tooltip.winner", winner))
            rows.take(MAX_TOOLTIP_ROWS).mapTo(lines) { StringUtil.escapeXmlEntities(it) }
            if (rows.size > MAX_TOOLTIP_ROWS) lines += StringUtil.escapeXmlEntities(message("hints.tooltip.more", rows.size - MAX_TOOLTIP_ROWS))
            lines += "<i>" + StringUtil.escapeXmlEntities(message("hints.tooltip.click")) + "</i>"
            return lines.joinToString("<br>", prefix = "<html>", postfix = "</html>")
        }

        // ------------------------------------------------------------------------------------------------ values

        /** The value [ref] gives the reference: the written value, the nested key's after an accessor [path], or a lock. */
        private fun valueOf(sources: Sources, ref: VarSourceRef, path: List<String>): Value {
            val location = ref.location()
            return values.getOrPut(Triple(location, sources.name, path)) {
                val definition = sources.byLocation[location]
                when {
                    sources.noLog -> lock(location, message("source.value.nolog"))
                    ref.isVault || VaultInfo.isSecret(sources.name, ref.file) || definition?.let(VarSourceTexts::isVault) == true ->
                        lock(location, message("source.value.vault"))
                    path.isNotEmpty() -> nested(location, definition, path)
                    else -> written(location, definition?.preview ?: ref.preview)
                }
            }
        }

        private fun lock(key: Any, row: String) = Value(key, VarSourceTexts.LOCK, row, lock = true)

        /** The value as written (a cut or hidden text compares by definition, never as equal to another). */
        private fun written(key: Any, preview: String?): Value {
            if (preview == null) return Value(key, UNKNOWN, UNKNOWN, lock = false, unknown = true)
            val exact = !preview.endsWith(VarSourceTexts.ELLIPSIS)
            return Value(if (exact) preview else key, VarSourceTexts.cut(preview, VarSourceTexts.HINT_VALUE_MAX), VarSourceTexts.cut(preview, VarSourceTexts.ROW_VALUE_MAX), lock = false)
        }

        /** The nested key [path] of a literal mapping written at [location] (decision 6), `?` for anything else. */
        private fun nested(location: SourceLocation, definition: VarDefinition?, path: List<String>): Value {
            val key = location to path
            if (definition != null && definition.valueShape != ValueShape.CONTAINER) return written(key, null)
            val keyValue = VarLocations.keyValueAt(project, location) ?: return written(key, null)
            var value: YValue = PsiYValueAdapter.valueOf(keyValue)
            for (step in path) {
                value = when (val container = value) {
                    is YMap -> container[step]
                    is YSeq -> step.toIntOrNull()?.let { container.items.getOrNull(it) }
                    else -> null
                } ?: return written(key, null)
                if (step.startsWith(VAULT_PREFIX)) return lock(key, message("source.value.vault"))
            }
            if (value is YVault) return lock(key, message("source.value.vault"))
            return written(key, ValueSummary.render(value))
        }

        /** [definition] as a source (its index preview), for the default, `set_fact` and rule 3's answers. */
        private fun refOf(definition: VarDefinition): VarSourceRef = VarSourceRef(
            file = definition.location.file,
            offset = definition.location.offset,
            layer = definition.layer ?: if (definition.kind == VarDefKind.SET_FACT) VarsLayer.SET_FACT_REGISTER else VarsLayer.ROLE_DEFAULTS,
            group = definition.group,
            host = definition.host,
            preview = definition.preview,
            isVault = definition.valueShape == ValueShape.VAULT,
            role = definition.roleName,
        )
    }

    private fun VarSourceRef.location(): SourceLocation = SourceLocation(file, offset)

    /** The own role's default first from `main` files, then by path and offset. */
    private val MAIN_FIRST: Comparator<VarDefinition> = compareBy(
        { if (it.location.file.nameWithoutExtension == "main") 0 else 1 },
        { it.location.file.path },
        { it.location.offset },
    )

    /**
     * The end of the reference at [start]: after the root [name] and the constant accessors of [path] as written
     * (`.port`, `['port']`, `["port"]`, `[0]`), so a hint never splits `app.port`; the root's end when the text does not
     * match.
     */
    fun referenceEnd(text: CharSequence, start: Int, name: String, path: List<String>): Int {
        var end = start + name.length
        for (step in path) {
            end = accessorEnd(text, end, step) ?: break
        }
        return end.coerceAtMost(text.length)
    }

    private fun accessorEnd(text: CharSequence, at: Int, step: String): Int? {
        if (at >= text.length) return null
        return when (text[at]) {
            '.' -> (at + 1).takeIf { StringUtil.startsWith(text, it, step) && !continuesName(text, it + step.length) }?.let { it + step.length }
            '[' -> {
                var i = skipSpaces(text, at + 1)
                val quote = text.getOrNull(i)?.takeIf { it == '\'' || it == '"' }
                if (quote != null) i++
                if (!StringUtil.startsWith(text, i, step)) return null
                i += step.length
                if (quote != null) {
                    if (text.getOrNull(i) != quote) return null
                    i++
                }
                i = skipSpaces(text, i)
                if (text.getOrNull(i) != ']') null else i + 1
            }
            else -> null
        }
    }

    private fun continuesName(text: CharSequence, at: Int): Boolean = text.getOrNull(at)?.let { it.isLetterOrDigit() || it == '_' } == true

    private fun skipSpaces(text: CharSequence, from: Int): Int {
        var i = from
        while (i < text.length && text[i] == ' ') i++
        return i
    }
}
