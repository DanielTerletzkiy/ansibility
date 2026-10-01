package de.terletzkiy.ansibility.completion.jinja

import com.intellij.icons.AllIcons
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.indexing.FileBasedIndex
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.RoleInfo
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.api.ValueShape
import de.terletzkiy.ansibility.completion.jinja.AnsibilityJinjaCompletionBundle.message
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.facts.FactsCatalog
import de.terletzkiy.ansibility.facts.MagicVar
import de.terletzkiy.ansibility.index.AnsibleIndexQueries
import de.terletzkiy.ansibility.index.LiteralType
import de.terletzkiy.ansibility.index.ValueSummary
import de.terletzkiy.ansibility.index.VarDefIndex
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaLocalKind
import de.terletzkiy.ansibility.resolve.loop.LiteralShapes
import de.terletzkiy.ansibility.resolve.loop.LoopItemTyper
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.schema.OptionType
import de.terletzkiy.ansibility.vars.VarCard

/**
 * Variable-name candidates at a [JinjaCompletionPosition.Name] (plan F1.3, F1.6, X10), grouped by tier and each name
 * offered once, at its best tier:
 * - T0 Jinja locals visible at the caret; T1 the loop variable, `index_var` and `ansible_loop`; T2 task, block and
 *   `template_vars` names;
 * - T3 the own role(s): spec options, `defaults/`, `vars/`, then `register`/`set_fact` names set before the caret in
 *   role order; T4 the play scope: other roles applied by the plays of the own role (or of the playbook's play), play
 *   `vars` and `vars_files` keys;
 * - T5 names only the inventory defines (grey); T6 special variables (template-only ones in templates), facts and
 *   their injected `ansible_*` names; last, the names other roles of the root declare.
 *
 * Presentation follows the plan: spec type text (`list[dict]`), tail ` = <default> · <role>` or
 * ` · required · <role>`, bold for required options without any default, struck out when deprecated. Values come only
 * from the index's vault-safe previews or spec defaults (never of a `vault_*` name).
 */
internal class JinjaNames(private val scope: JinjaCompletionScope, private val accept: (String) -> Boolean) {
    private val result = LinkedHashMap<String, JinjaCandidate>()
    private val project = scope.project

    /** Molecule plays that test an own role (found by [playScope]); their vars are test values, offered with T5. */
    private val moleculePlays = ArrayList<ScopePlay>()

    fun collect(): List<JinjaCandidate> {
        locals()
        loops()
        taskVars()
        scope.roles.forEach { role -> role(role.info, Tier.ROLE) }
        scope.roles.forEach { role -> runtime(role) }
        localRuntime()
        playScope()
        inventory()
        magic()
        rootWide()
        return result.values.toList()
    }

    private fun wanted(name: String): Boolean = name !in result && accept(name)

    private fun add(candidate: JinjaCandidate) {
        result.putIfAbsent(candidate.lookupString, candidate)
    }

    // ------------------------------------------------------------------------------------------------ T0-T2

    private fun locals() {
        for ((index, local) in scope.locals.withIndex()) {
            if (!wanted(local.name)) continue
            val kind = message("type.local.${local.kind.name}")
            val isNamespace = local.kind == JinjaLocalKind.SET &&
                scope.analysis.result.locals.any { it.kind == JinjaLocalKind.NAMESPACE_ATTRIBUTE && it.owner == local.name }
            val typed = if (local.kind == JinjaLocalKind.FOR_TARGET) JinjaMembers(scope).typeOf(local.name, emptyList()) else null
            add(
                JinjaCandidate(
                    local.name, Tier.LOCAL, 999 - index,
                    typeText = typed?.option?.let(::typeText) ?: if (isNamespace) message("type.local.NAMESPACE_ATTRIBUTE") else kind,
                    tail = part(message("part.local")),
                    icon = AllIcons.Nodes.Parameter,
                    doc = typed?.doc ?: CandidateDoc.Local(local.name, kind, scope.locationOf(local)),
                ),
            )
        }
    }

    private fun loops() {
        for (entry in scope.loops) {
            val loop = entry.loop
            val over = loop.sourceVariable?.let { source -> (listOf(source) + loop.sourcePath).joinToString(".") }
            val loopTail = buildString {
                append(part(if (over != null) message("part.loop.over", over) else message("part.loop.item")))
                if (entry.contexts > 1) append(part(message("part.loop.contexts", entry.contexts)))
            }
            if (wanted(loop.loopVar)) {
                val doc = loop.sourceVariable?.let { CandidateDoc.Variable(it, loop.sourcePath + "0") }
                    ?: CandidateDoc.Loop(loop.loopVar, loop.item, entry.task, entry.contexts)
                add(JinjaCandidate(loop.loopVar, Tier.LOOP, 30, loop.item?.let(::typeText), loopTail, AllIcons.Nodes.Parameter, doc))
            }
            loop.indexVar?.takeIf(::wanted)?.let { name ->
                add(JinjaCandidate(name, Tier.LOOP, 20, "int", part(message("part.loop.index")), AllIcons.Nodes.Parameter, CandidateDoc.Loop(name, OptionSpec(name, OptionType.Int), entry.task, entry.contexts)))
            }
            if (loop.extended && wanted(LoopItemTyper.ANSIBLE_LOOP)) {
                add(
                    JinjaCandidate(
                        LoopItemTyper.ANSIBLE_LOOP, Tier.LOOP, 10, "dict", part(message("part.loop.extended")), AllIcons.Nodes.Parameter,
                        CandidateDoc.Loop(LoopItemTyper.ANSIBLE_LOOP, LoopItemTyper.ansibleLoopSpec, entry.task, entry.contexts),
                    ),
                )
            }
        }
    }

    private fun taskVars() {
        for ((index, variable) in scope.taskVars.withIndex()) {
            if (!wanted(variable.name)) continue
            val label = when (variable.source) {
                ScopeTaskVar.Source.TASK -> message("part.task.vars")
                ScopeTaskVar.Source.BLOCK -> message("part.block.vars")
                ScopeTaskVar.Source.INCLUDE -> message("part.include.vars")
                ScopeTaskVar.Source.TEMPLATE -> message("part.template.vars")
            }
            val type = variable.value?.let { LiteralShapes.of(variable.name, it) }?.let(::typeText)?.takeIf { it != "raw" }
            add(JinjaCandidate(variable.name, Tier.TASK, 999 - index, type, part(label), AllIcons.Nodes.Variable, CandidateDoc.Variable(variable.name, emptyList())))
        }
    }

    // ------------------------------------------------------------------------------------------------ roles

    /** Spec options, then `defaults/` keys, then `vars/` keys of [role] at [tier]. */
    private fun role(role: RoleInfo, tier: Tier) {
        val name = role.ref.name
        val entries = scope.catalog.entriesOf(name)
        val options = LinkedHashMap<String, OptionSpec>()
        val specs = role.argumentSpecs.entries.sortedBy { if (it.key == MAIN) 0 else 1 }
        specs.forEach { (_, spec) -> spec.options.forEach { (option, value) -> options.putIfAbsent(option, value) } }
        for ((option, spec) in options) {
            ProgressManager.checkCanceled()
            if (!wanted(option)) continue
            val runtime = entries[option]?.firstOrNull { it.site == CatalogSite.DEFAULTS }
            val default = runtime?.let(::previewOf) ?: spec.default?.takeUnless { ValueSummary.isSecret(option, it) }?.let(ValueSummary::render)
            val missing = runtime == null && spec.default == null
            val tail = buildString {
                default?.let { append(message("tail.default", shorten(it))) }
                if (default == null && spec.required) append(part(message("part.required")))
                append(part(name))
            }
            add(
                JinjaCandidate(
                    option, tier, 300, typeText(spec), tail, AllIcons.Nodes.Variable, CandidateDoc.Variable(option, emptyList()),
                    bold = spec.required && missing, deprecated = spec.deprecated != null,
                ),
            )
        }
        for ((variable, list) in entries) {
            ProgressManager.checkCanceled()
            if (!wanted(variable)) continue
            val entry = list.firstOrNull { it.site == CatalogSite.DEFAULTS } ?: list.firstOrNull { it.site == CatalogSite.VARS } ?: continue
            val owner = if (entry.site == CatalogSite.VARS) message("part.vars", name) else name
            val tail = buildString {
                previewOf(entry)?.let { append(message("tail.default", shorten(it))) }
                append(part(owner))
            }
            val rank = if (entry.site == CatalogSite.DEFAULTS) 200 else 100
            add(JinjaCandidate(variable, tier, rank, literalTypeText(entry), tail, AllIcons.Nodes.Variable, CandidateDoc.Variable(variable, emptyList())))
        }
    }

    /** `register` and `set_fact` names of an own role set before the caret. */
    private fun runtime(role: ScopeRole) {
        for (runtime in scope.runtimeNames(role)) {
            ProgressManager.checkCanceled()
            if (!wanted(runtime.name)) continue
            val kind = message(if (runtime.isRegister) "part.register" else "part.set.fact")
            add(
                JinjaCandidate(
                    runtime.name, Tier.ROLE, 50, if (runtime.isRegister) message("type.result") else null,
                    part(kind) + part(role.info.ref.name), AllIcons.Nodes.Variable, CandidateDoc.Variable(runtime.name, emptyList()),
                ),
            )
        }
    }

    /** `register`/`set_fact` names of the caret's own playbook or molecule file before the caret. */
    private fun localRuntime() {
        for (runtime in scope.localRuntime) {
            if (!wanted(runtime.name)) continue
            val kind = message(if (runtime.isRegister) "part.register" else "part.set.fact")
            add(
                JinjaCandidate(
                    runtime.name, Tier.ROLE, 60, if (runtime.isRegister) message("type.result") else null, part(kind),
                    AllIcons.Nodes.Variable, CandidateDoc.Variable(runtime.name, emptyList()),
                ),
            )
        }
    }

    /**
     * T4: roles applied together with the own roles (or by the caret's play) and their `meta/main.yml` dependencies,
     * with their spec, defaults, vars and runtime names; play `vars` and `vars_files` keys. Plays come from the
     * `ansible.play` index (molecule playbooks only for the caret's own play).
     */
    private fun playScope() {
        val plays = LinkedHashSet<ScopePlay>()
        scope.play?.let(plays::add)
        if (scope.roleNames.isNotEmpty()) {
            for (hit in AnsibleIndexQueries.plays(project, scope.root)) {
                ProgressManager.checkCanceled()
                val entry = hit.value
                if (entry.importPlaybook != null || entry.roles.none { roleNameOf(it.name) in scope.roleNames }) continue
                when (hit.context.kind) {
                    FileKind.PLAYBOOK -> if (plays.size < MAX_PLAYS) plays += ScopePlay(hit.file, entry)
                    FileKind.MOLECULE_PLAYBOOK -> moleculePlays += ScopePlay(hit.file, entry)
                    else -> Unit
                }
            }
        }
        val registry = RoleRegistry.getInstance(project)
        val roles = LinkedHashSet<String>()
        plays.forEach { play -> play.entry.roles.forEach { roles += roleNameOf(it.name) } }
        roles.removeAll(scope.roleNames)
        for (play in plays) {
            val file = play.file.name
            for (key in play.entry.varsKeys) {
                if (wanted(key)) add(JinjaCandidate(key, Tier.PLAY, 500, null, part(message("part.play.vars")) + part(file), AllIcons.Nodes.Variable, CandidateDoc.Variable(key, emptyList())))
            }
            for (path in play.entry.varsFiles) {
                if (path.contains("{{")) continue
                val target = play.playbookDir?.findFileByRelativePath(path)?.takeIf { !it.isDirectory } ?: continue
                for (key in FileBasedIndex.getInstance().getFileData(VarDefIndex.NAME, target, project).keys) {
                    if (wanted(key)) add(JinjaCandidate(key, Tier.PLAY, 400, null, part(message("part.vars.files")) + part(target.name), AllIcons.Nodes.Variable, CandidateDoc.Variable(key, emptyList())))
                }
            }
        }
        val infos = roles.mapNotNull { registry.role(scope.root, it) }.toMutableList()
        // `meta/main.yml` dependencies run in the same play.
        (infos + scope.roles.map { it.info }).flatMap { it.metaDependencies }.map(::roleNameOf)
            .filter { it !in roles && it !in scope.roleNames }.distinct()
            .mapNotNullTo(infos) { registry.role(scope.root, it) }
        infos.forEach { role(it, Tier.PLAY) }
        // Registers and set_facts of the other roles of the play (they run in the same play, in some order).
        for (info in infos) {
            for (runtime in RoleTaskOrder(project, info).runtimeNames()) {
                ProgressManager.checkCanceled()
                if (!wanted(runtime.name)) continue
                val kind = message(if (runtime.isRegister) "part.register" else "part.set.fact")
                add(
                    JinjaCandidate(
                        runtime.name, Tier.PLAY, 50, if (runtime.isRegister) message("type.result") else null,
                        part(kind) + part(info.ref.name), AllIcons.Nodes.Variable, CandidateDoc.Variable(runtime.name, emptyList()),
                    ),
                )
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ T5, T6, root

    private fun inventory() {
        val catalog = scope.catalog
        val ownScenario = scope.fileContext.moleculeScenarioDir
        for ((scenario, names) in catalog.molecule) {
            val own = scenario == ownScenario
            if (!own && names.values.none { list -> list.any { it.role in scope.roleNames } }) continue
            for ((name, entries) in names) {
                if (name in FactsCatalog.magicVars || !wanted(name)) continue
                add(
                    JinjaCandidate(
                        name, Tier.INVENTORY, if (own) 150 else 100, entries.firstNotNullOfOrNull(::literalTypeText),
                        part(message("part.molecule.inventory")), AllIcons.Nodes.Variable, CandidateDoc.Variable(name, emptyList()), grey = true,
                    ),
                )
            }
        }
        for (play in moleculePlays) {
            val names = play.entry.varsKeys + play.entry.varsFiles.filter { !it.contains("{{") }.flatMap { path ->
                val target = play.playbookDir?.findFileByRelativePath(path)?.takeIf { !it.isDirectory } ?: return@flatMap emptyList()
                FileBasedIndex.getInstance().getFileData(VarDefIndex.NAME, target, project).keys
            }
            for (name in names) {
                if (name in FactsCatalog.magicVars || !wanted(name)) continue
                add(
                    JinjaCandidate(
                        name, Tier.INVENTORY, 100, null, part(message("part.molecule.inventory")) + part(play.file.name),
                        AllIcons.Nodes.Variable, CandidateDoc.Variable(name, emptyList()), grey = true,
                    ),
                )
            }
        }
        for ((name, entries) in catalog.inventory) {
            ProgressManager.checkCanceled()
            if (name in catalog.roleDeclared || name in FactsCatalog.magicVars || !wanted(name)) continue
            val environments = entries.map { it.environment }.distinct()
            val where = environments.filterNotNull().sorted()
            val label = when {
                where.isEmpty() -> message("part.inventory.playbook")
                else -> message("part.inventory.environments", where.joinToString(", "))
            }
            add(
                JinjaCandidate(
                    name, Tier.INVENTORY, 0, entries.firstNotNullOfOrNull(::literalTypeText), part(label), AllIcons.Nodes.Variable,
                    CandidateDoc.Variable(name, emptyList()), grey = true,
                ),
            )
        }
    }

    private fun magic() {
        for (variable in FactsCatalog.magicVars.values) {
            if (variable.templateOnly && !scope.isTemplate) continue
            if (!wanted(variable.name)) continue
            add(
                JinjaCandidate(
                    variable.name, Tier.MAGIC, if (variable.connection) 100 else 200, variable.typeText, part(magicLabel(variable)),
                    AllIcons.Nodes.Constant, CandidateDoc.Magic(variable), deprecated = variable.deprecated != null,
                ),
            )
        }
        if (!injectFacts()) return
        for ((name, fact) in FactsCatalog.injected) {
            if (!wanted(name)) continue
            add(JinjaCandidate(name, Tier.MAGIC, 50, fact.typeText, part(message("part.fact")), AllIcons.Nodes.Property, CandidateDoc.Fact(listOf(fact.name), fact, name)))
        }
    }

    /** Names other roles of the root declare (not in the own or play scope). */
    private fun rootWide() {
        val seen = scope.roleNames
        val owners = HashMap<String, MutableList<CatalogEntry>>()
        for ((role, entries) in scope.catalog.roles) {
            if (role in seen) continue
            for ((name, list) in entries) {
                if (name in result || !accept(name)) continue
                owners.getOrPut(name) { ArrayList(1) } += list.first()
            }
        }
        for ((name, list) in owners) {
            ProgressManager.checkCanceled()
            val roles = list.mapNotNull { it.role }.distinct().sorted()
            val first = list.firstOrNull { it.site == CatalogSite.DEFAULTS } ?: list.first()
            val owner = if (roles.size > 1) "${roles.first()} +${roles.size - 1}" else roles.firstOrNull().orEmpty()
            val tail = buildString {
                if (roles.size == 1 && first.site == CatalogSite.DEFAULTS) previewOf(first)?.let { append(message("tail.default", shorten(it))) }
                append(part(owner))
            }
            add(JinjaCandidate(name, Tier.ROOT, 0, literalTypeText(first), tail, AllIcons.Nodes.Variable, CandidateDoc.Variable(name, emptyList())))
        }
    }

    private fun injectFacts(): Boolean {
        val cfg = AnsibleWorkspaceImpl.getInstance(project)?.configOf(scope.root) ?: return true
        val value = cfg.value("defaults", "inject_facts_as_vars")?.trim()?.lowercase() ?: return true
        return value !in setOf("false", "no", "off", "0", "n", "f")
    }

    companion object {
        private const val MAIN = "main"
        private const val MAX_PLAYS = 50
        private const val MAX_TAIL_VALUE = 30

        /** ` · text`. */
        fun part(text: String): String = message("tail.part", text)

        fun typeText(option: OptionSpec): String = VarCard.typeText(option)

        fun shorten(value: String): String = StringUtil.first(value, MAX_TAIL_VALUE, true)

        /** The role name of a role reference as written (`haproxy`, `/ansible/roles/haproxy`). */
        fun roleNameOf(written: String): String = written.trim().trimEnd('/').substringAfterLast('/')

        fun magicLabel(variable: MagicVar): String = message(
            when {
                variable.templateOnly -> "part.magic.template"
                variable.connection -> "part.magic.connection"
                else -> "part.magic"
            },
        )

        /** The vault-safe preview of a catalog entry (none for secrets and Jinja values shown as written). */
        fun previewOf(entry: CatalogEntry): String? = entry.preview?.takeUnless { entry.shape == ValueShape.VAULT }

        /** `str`, `int` … for literals, `list`/`dict` for containers, null for Jinja and unknown values. */
        fun literalTypeText(entry: CatalogEntry): String? = when (entry.literalType) {
            LiteralType.STR -> "str"
            LiteralType.INT -> "int"
            LiteralType.FLOAT -> "float"
            LiteralType.BOOL -> "bool"
            LiteralType.TIMESTAMP -> "timestamp"
            LiteralType.NULL -> "null"
            LiteralType.NONE, LiteralType.UNLOADABLE -> when {
                entry.shape != ValueShape.CONTAINER -> null
                entry.preview?.startsWith("[") == true -> "list"
                entry.preview?.startsWith("{") == true -> "dict"
                else -> null
            }
        }
    }
}
