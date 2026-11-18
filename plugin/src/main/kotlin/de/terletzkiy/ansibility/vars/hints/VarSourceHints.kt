package de.terletzkiy.ansibility.vars.hints

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarDefinition
import de.terletzkiy.ansibility.api.VarSymbol
import de.terletzkiy.ansibility.context.MoleculeView
import de.terletzkiy.ansibility.facts.FactsCatalog
import de.terletzkiy.ansibility.index.UseEntry
import de.terletzkiy.ansibility.resolve.VarViews
import de.terletzkiy.ansibility.semantics.validate.SpecDefaults
import de.terletzkiy.ansibility.vars.AnsibilityVarsBundle.message
import de.terletzkiy.ansibility.vars.LoopItems
import de.terletzkiy.ansibility.vars.VarContextWinner
import de.terletzkiy.ansibility.vars.VarLabels
import de.terletzkiy.ansibility.vars.VarLocations
import de.terletzkiy.ansibility.vars.VarSourceTexts
import de.terletzkiy.ansibility.vars.VaultInfo
import de.terletzkiy.ansibility.vars.gutter.OverrideLineMarkers
import de.terletzkiy.ansibility.vars.usages.OpenFileIndexData
import org.jetbrains.annotations.Nls

/**
 * The inline variable sources of one file (plan amendment R31, D232–D237): one hint right after each variable
 * reference (Jinja in YAML values, bare `when:`/`until:`/`that:` expressions, `.j2` templates; several per line when a
 * line has several references) that says where the variable gets its value:
 *
 * ```
 * {{ app_version }} ‹default 1.2.0 · prod 1.4.0 · test 1.3.0›
 * {{ app_version }} ‹→ 1.4.0 (prod, group_vars web) · default 1.2.0 · +2›     (an environment or host selected)
 * ```
 *
 * - **Entries (D234):** the role default, each environment's inventory value (`group_vars`, `host_vars`, `hosts.yml`),
 *   then the playbook-level values (`playbook` for playbook `group_vars`/`host_vars`, `play` for play `vars` and
 *   `vars_files`), the role's `vars/`, `include_vars` files, role and include params and, when the Molecule view shows
 *   them, Molecule inventories. Several definitions of one entry collapse to the most general one (lowest precedence,
 *   then path) with `(+1)`; four entries at most, then `+3 more`. Values as written, cut at 24 characters; a vault value
 *   or a variable a spec keeps secret reads `🔒`.
 * - **The role** whose default counts is the file's own role when it declares the name, else every declaring role.
 * - **With a selected context** (an environment or a host, [VarContextWinner]: the card's winner logic) the winner comes
 *   first, `→ value (env, layer)`, with `3/5 hosts` when not every host of the selection gets it, then the default and
 *   the number of the other entries.
 * - **Which variables (D233):** by default only those set somewhere besides the role's own default (and spec): an
 *   environment's value, a playbook-level value, role vars, params. [includeSingle] ("Also for variables with only a
 *   default") adds those that only a role default sets. Never for template locals (not free references), loop variables ([LoopItems]), registered results of the same role
 *   or file, facts and magic variables ([FactsCatalog]), calls (`lookup(…)`), reads by name (`hostvars[h].x`) and
 *   undefined names.
 * - **Tooltip (D235):** every counted definition with its file and value; the winner first.
 *
 * Speed (D237): the references come from the file's own `ansible.var.use` entries ([OpenFileIndexData], cached on its
 * PSI), the definitions from the root's cached symbols ([VarViews.symbol]); per name once, per reference only the
 * winner (cached by the card's views) and the loop check of names that some loop binds. No PSI of another file is built
 * and no line number of another file is computed. Call in a read action in smart mode.
 */
internal object VarSourceHints {
    /** The provider id: the one platform switch (Settings › Editor › Inlay Hints, the hint's menu, the toggle action). */
    const val PROVIDER_ID: String = "ansibility.variable.sources"

    /** The provider option "Also for variables with only a default". */
    const val OPTION_SINGLE: String = "single.sources"

    /** The click handler's id (`codeInsight.inlayActionHandler`). */
    const val HANDLER_ID: String = "ansibility.variable.sources"

    /** Entries before `+N more`. */
    const val MAX_ENTRIES: Int = 4

    /** Tooltip rows before "… and N more". */
    private const val MAX_TOOLTIP_ROWS = 20

    /** Labels and owners of an entry are cut like values. */
    private const val MAX_LABEL = 24

    /** File kinds whose Jinja-looking text Ansible never templates (as [de.terletzkiy.ansibility.resolve.VarUsageQuery]). */
    private val NOT_TEMPLATED = setOf(FileKind.ROLE_ARGSPEC, FileKind.ANSIBLE_CFG, FileKind.LINT_CONFIG, FileKind.REQUIREMENTS)

    /** One hint at [offset] (after the reference) for the reference of [name] starting at [reference]. */
    class Hint(
        val offset: Int,
        val name: String,
        val reference: Int,
        /** The text in pieces of at most 30 characters (the declarative hints' segment limit). */
        val segments: List<String>,
        @Nls val tooltip: String,
    ) {
        val text: String get() = segments.joinToString("")

        /** The click payload: where the reference starts and its name. */
        val payload: String get() = "$reference:$name"
    }

    /** `offset:name` of a click payload, or null. */
    fun parsePayload(payload: String?): Pair<Int, String>? {
        val colon = payload?.indexOf(':') ?: return null
        if (colon <= 0) return null
        val offset = payload.substring(0, colon).toIntOrNull() ?: return null
        return offset to payload.substring(colon + 1)
    }

    /** The hints of [file], in offset order; [includeSingle] also for variables that only a role default sets. */
    fun collect(project: Project, file: PsiFile, includeSingle: Boolean): List<Hint> {
        val virtualFile = file.viewProvider.virtualFile
        val context = AnsibleWorkspace.getInstance(project).contextOf(virtualFile) ?: return emptyList()
        if (context.kind in NOT_TEMPLATED) return emptyList()
        val uses = OpenFileIndexData.of(project, virtualFile).uses
        if (uses.isEmpty()) return emptyList()
        val text = file.viewProvider.contents
        val view = MoleculeView.of(project, virtualFile)
        val selected = VarContextWinner.isSelected(project, context.root)
        val request = Request(project, context.root, virtualFile, context.roleName, view, selected, text)
        val result = ArrayList<Hint>()
        for ((name, entries) in uses) {
            ProgressManager.checkCanceled()
            if (isFactOrMagic(name)) continue
            val references = entries.filter { !it.called && it.indirect == null && it.offset + name.length <= text.length }
            if (references.isEmpty()) continue
            val sources = request.sources(name, includeSingle) ?: continue
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

    /** Which entry of the hint a definition belongs to, in display order. */
    private enum class Group(val labelKey: String?) {
        DEFAULT("hints.entry.default"),
        ENVIRONMENT(null),
        PLAYBOOK("hints.entry.playbook"),
        PLAY("hints.entry.play"),
        VARS("hints.entry.vars"),
        INCLUDE_VARS("hints.entry.include.vars"),
        PARAMS("hints.entry.params"),
        MOLECULE("hints.entry.molecule"),
    }

    /** One entry: the definitions of one group (one environment), the most general first. */
    private class Entry(val group: Group, val label: String, val definitions: List<VarDefinition>)

    /** What one variable name shows, the same for every reference of the file (the winner aside). */
    private class Sources(
        val name: String,
        val symbol: VarSymbol,
        val entries: List<Entry>,
        val noLog: Boolean,
        /** Some loop binds the name somewhere: each reference is checked with [LoopItems]. */
        val loopBound: Boolean,
        /** The no-context hint text. */
        val segments: List<String>,
        /** The tooltip rows (without the winner). */
        val rows: List<String>,
    ) {
        /** The tooltip without a winner, built once for every reference of the name. */
        var plainTooltip: String? = null
    }

    private class Request(
        val project: Project,
        val root: AnsibleRoot,
        val file: VirtualFile,
        val ownRole: String?,
        val view: MoleculeView,
        val selected: Boolean,
        val text: CharSequence,
    ) {
        /** The sources of [name], or null when it gets no hint ([includeSingle]: D233's setting). */
        fun sources(name: String, includeSingle: Boolean): Sources? {
            val symbol = VarViews.symbol(project, root, name, view)
            if (symbol.definitions.isEmpty()) return null
            // A registered result of the same role (outside roles: the same file) is a runtime value, never a source hint.
            if (symbol.definitions.any { it.kind == VarDefKind.REGISTER && isLocal(it) }) return null
            val entries = entries(symbol)
            // D233: set somewhere besides the role's own default (an override, an environment's value, role vars …);
            // with the option, also a variable that only its default sets.
            val shown = entries.any { it.group != Group.DEFAULT } || (includeSingle && entries.isNotEmpty())
            if (!shown) return null
            val noLog = symbol.specBindings.any { SpecDefaults.hasNoLog(it.option) }
            val loopBound = symbol.definitions.any { it.kind == VarDefKind.LOOP_VAR || it.kind == VarDefKind.INDEX_VAR }
            return Sources(name, symbol, entries, noLog, loopBound, plainSegments(entries, noLog), tooltipRows(entries, noLog))
        }

        private fun isLocal(definition: VarDefinition): Boolean =
            if (ownRole != null) definition.roleName == ownRole else definition.location.file == file

        /** The hint of one reference, or null for a loop variable there. */
        fun hint(sources: Sources, entry: UseEntry): Hint? {
            val name = sources.name
            if (sources.loopBound && LoopItems.bindingAt(project, file, entry.offset, name, view) != null) return null
            val winner = if (selected) VarContextWinner.at(project, root, file, entry.offset, name) else null
            val segments = if (winner != null) winnerSegments(sources, winner) else sources.segments
            val tooltip = if (winner != null) tooltip(sources, winner) else sources.plainTooltip ?: tooltip(sources, null).also { sources.plainTooltip = it }
            return Hint(referenceEnd(text, entry.offset, name, entry.attrPath), name, entry.offset, segments, tooltip)
        }

        // ------------------------------------------------------------------------------------------------ entries

        private fun entries(symbol: VarSymbol): List<Entry> {
            val roleKinds = setOf(VarDefKind.SPEC_OPTION, VarDefKind.ROLE_DEFAULT, VarDefKind.ROLE_VAR)
            val declaringRoles = symbol.definitions.filter { it.kind in roleKinds }.mapNotNullTo(HashSet()) { it.roleName } +
                symbol.specBindings.map { it.role.name }
            // The file's own role when it declares the name; else every declaring role (a playbook, another role's var).
            val roles: (String?) -> Boolean = if (ownRole != null && ownRole in declaringRoles) { role -> role == ownRole } else { _ -> true }
            val grouped = LinkedHashMap<Pair<Group, String>, MutableList<VarDefinition>>()
            for (definition in symbol.definitions) {
                val group = groupOf(definition, roles) ?: continue
                val label = if (group == Group.ENVIRONMENT) VarSourceTexts.environment(definition)!! else ""
                grouped.getOrPut(group to label) { ArrayList() } += definition
            }
            return grouped.entries
                .sortedWith(compareBy({ it.key.first.ordinal }, { it.key.second }))
                .map { (key, definitions) ->
                    val (group, environment) = key
                    val label = group.labelKey?.let { message(it) } ?: VarSourceTexts.cut(environment, MAX_LABEL)
                    Entry(group, label, definitions.sortedWith(GENERAL_FIRST))
                }
        }

        private fun groupOf(definition: VarDefinition, roles: (String?) -> Boolean): Group? = when (definition.kind) {
            VarDefKind.ROLE_DEFAULT -> Group.DEFAULT.takeIf { roles(definition.roleName) }
            VarDefKind.ROLE_VAR -> Group.VARS.takeIf { roles(definition.roleName) }
            VarDefKind.GROUP_VARS, VarDefKind.HOST_VARS, VarDefKind.INVENTORY_INLINE ->
                if (VarSourceTexts.environment(definition) != null) Group.ENVIRONMENT else Group.PLAYBOOK
            VarDefKind.PLAY_VARS, VarDefKind.VARS_FILES -> Group.PLAY
            VarDefKind.INCLUDE_VARS -> Group.INCLUDE_VARS
            VarDefKind.ROLE_PARAMS, VarDefKind.INCLUDE_PARAMS -> Group.PARAMS
            VarDefKind.MOLECULE_INVENTORY -> Group.MOLECULE
            else -> null
        }

        // ------------------------------------------------------------------------------------------------ texts

        /** `default 1.2.0 · prod 1.4.0 (+1) · test 1.3.0 · playbook 2.0 · +1 more`. */
        private fun plainSegments(entries: List<Entry>, noLog: Boolean): List<String> {
            val segments = ArrayList<String>()
            for ((index, entry) in entries.take(MAX_ENTRIES).withIndex()) {
                if (index > 0) segments += VarLabels.SEPARATOR
                segments += entrySegments(entry, noLog)
            }
            val more = entries.size - MAX_ENTRIES
            if (more > 0) {
                segments += VarLabels.SEPARATOR
                segments += message("hints.more", more)
            }
            return segments
        }

        private fun entrySegments(entry: Entry, noLog: Boolean): List<String> {
            val segments = mutableListOf(entry.label + " ", VarSourceTexts.hintValue(entry.definitions.first(), noLog))
            if (entry.definitions.size > 1) segments += " " + message("hints.collapsed", entry.definitions.size - 1)
            return segments
        }

        /** `→ 1.4.0 (prod, group_vars web) · default 1.2.0 · +2`. */
        private fun winnerSegments(sources: Sources, winner: VarContextWinner): List<String> {
            val definition = sources.symbol.definitions.firstOrNull { it.location == winner.location }
            val ref = winner.ref
            val value = when {
                definition != null -> VarSourceTexts.hintValue(definition, sources.noLog)
                sources.noLog || ref.isVault || VaultInfo.isSecret(sources.name, ref.file) -> VarSourceTexts.LOCK
                else -> ref.preview?.let { VarSourceTexts.cut(it, VarSourceTexts.HINT_VALUE_MAX) } ?: VarSourceTexts.ELLIPSIS
            }
            val layer = definition?.let(VarSourceTexts::layer) ?: VarSourceTexts.layer(ref.layer, ref.group, ref.host)
            val details = listOfNotNull(
                winner.environment?.let { VarSourceTexts.cut(it, MAX_LABEL) },
                VarSourceTexts.cut(layer, MAX_LABEL),
                if (winner.everywhere) null else message("hints.winner.hosts", winner.wins, winner.of),
            )
            val segments = mutableListOf(message("hints.winner.arrow") + " " + value, " (")
            details.forEachIndexed { index, detail ->
                if (index > 0) segments += ", "
                segments += detail
            }
            segments += ")"
            val winnerEntry = sources.entries.firstOrNull { entry -> entry.definitions.any { it.location == winner.location } }
            val default = sources.entries.firstOrNull { it.group == Group.DEFAULT }?.takeIf { it !== winnerEntry }
            if (default != null) {
                segments += VarLabels.SEPARATOR
                segments += entrySegments(default, sources.noLog)
            }
            val rest = sources.entries.count { it !== winnerEntry && it !== default }
            if (rest > 0) {
                segments += VarLabels.SEPARATOR
                segments += message("hints.rest", rest)
            }
            return segments
        }

        /** `defaults · web · roles/web/defaults/main.yml = 80`: one row per counted definition, with its file (D235). */
        private fun tooltipRows(entries: List<Entry>, noLog: Boolean): List<String> =
            entries.flatMap { it.definitions }.map { definition ->
                val where = VarLocations.path(root, definition.location.file)
                val value = VarSourceTexts.value(definition, VarSourceTexts.ROW_VALUE_MAX, noLog)
                val located = if (value == null) where else message("source.row.value", where, value)
                listOfNotNull(VarSourceTexts.layer(definition), VarSourceTexts.owner(definition), located).joinToString(VarLabels.SEPARATOR)
            }

        private fun tooltip(sources: Sources, winner: VarContextWinner?): String {
            val lines = ArrayList<String>()
            lines += "<b>" + StringUtil.escapeXmlEntities(message("hints.tooltip.title", sources.name)) + "</b>"
            if (winner != null) {
                val where = VarLocations.path(root, winner.ref.file)
                val layer = VarSourceTexts.layer(winner.ref.layer, winner.ref.group, winner.ref.host)
                lines += StringUtil.escapeXmlEntities(message("hints.tooltip.winner", listOfNotNull(winner.environment, layer, where).joinToString(VarLabels.SEPARATOR)))
            }
            sources.rows.take(MAX_TOOLTIP_ROWS).mapTo(lines) { StringUtil.escapeXmlEntities(it) }
            if (sources.rows.size > MAX_TOOLTIP_ROWS) lines += StringUtil.escapeXmlEntities(message("hints.tooltip.more", sources.rows.size - MAX_TOOLTIP_ROWS))
            lines += "<i>" + StringUtil.escapeXmlEntities(message("hints.tooltip.click")) + "</i>"
            return lines.joinToString("<br>", prefix = "<html>", postfix = "</html>")
        }
    }

    /** The most general definition first: lowest precedence, `main` files first, then path and offset. */
    private val GENERAL_FIRST: Comparator<VarDefinition> = compareBy(
        { OverrideLineMarkers.levelOf(it) ?: Int.MAX_VALUE },
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
