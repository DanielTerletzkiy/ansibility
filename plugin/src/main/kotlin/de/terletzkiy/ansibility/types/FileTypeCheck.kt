package de.terletzkiy.ansibility.types

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.api.SpecBinding
import de.terletzkiy.ansibility.api.TypeFinding
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.context.MoleculeVisibility
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.coerce.CoreSemantics
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.semantics.diagnostics.Finding
import de.terletzkiy.ansibility.semantics.schema.OptionType
import de.terletzkiy.ansibility.semantics.schema.SpecOrigin
import de.terletzkiy.ansibility.semantics.validate.SpecKind
import de.terletzkiy.ansibility.semantics.validate.SpecValidator
import de.terletzkiy.ansibility.semantics.yaml.SourceRange
import de.terletzkiy.ansibility.semantics.yaml.YEntry
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.semantics.yaml.YVault
import org.jetbrains.yaml.psi.YAMLFile

/**
 * One type-check run over one file (plan F3.1–F3.2, F4.4), the work behind [TypeCheckServiceImpl]:
 *
 * 1. **Bindings.** Each assignment of [ValueSites] is checked against the specs that declare its name: in a role's own
 *    `defaults/` and `vars/` only that role's spec; in a role's molecule scenario (`molecule.yml`, `molecule/vars`,
 *    converge and friends) the specs of the roles the scenario's playbooks apply, because its pseudo-inventory reaches
 *    no other play; elsewhere every spec of the root ([VarService] spec bindings). Names nobody declares are not
 *    checked (ansible-core validates only spec'd names).
 * 2. **Skipped.** `vault_*` names, `!vault` values and templated values (ANS-T020 covers those), which the
 *    validator treats as opaque wherever they occur.
 * 3. **Validation.** [SpecValidator] with the [CoreSemantics] of the root's target ansible-core and [SpecKind.ROLE],
 *    recursing into `options`/`elements`; only [de.terletzkiy.ansibility.semantics.validate.SpecValidation.primary]
 *    is kept, so a rejected value is not reported twice.
 * 4. **Merging.** Findings with the same code, range and wording from several specs (a name declared by many roles,
 *    such as `system_networking_main_ip`) become one finding naming all of those roles.
 * 5. **Context.** Outside the role's own files, a role that no play of the root applies (molecule scenarios do not
 *    count) adds "its spec may be stale" (D6; severity is the policy's business). For list items that are mappings
 *    where the spec documents `elements: str`, the attributes the looping tasks read are added (🟣 CLAUDE X79).
 * 6. **Secrets.** A finding under a `no_log` option, or on a value holding a plaintext `vault_*` key, gets a message
 *    without the value.
 *
 * Call inside a read action in smart mode.
 */
internal class FileTypeCheck(private val project: Project, private val file: YAMLFile, private val context: FileContext) {
    private val root = context.root
    private val core: CoreVersion = TargetVersionDetector.getInstance(project).targetVersion(root).version ?: CoreVersion.PINNED
    private val validator = SpecValidator(CoreSemantics(core), SpecKind.ROLE)
    private val ownRoleOnly = context.kind == FileKind.ROLE_DEFAULTS || context.kind == FileKind.ROLE_VARS
    private val roleDirs: Set<VirtualFile>? = when {
        ownRoleOnly -> setOfNotNull(context.roleDir)
        MoleculeVisibility.isMoleculeFile(root, file.originalFile.virtualFile ?: file.viewProvider.virtualFile) -> context.roleDir?.let { scenarioRoles(it) }
        else -> null
    }
    private val reachabilityApplies = context.kind in REACHABILITY_KINDS
    private val applied = HashMap<String, Boolean>()
    private val loopReads = HashMap<String, LoopReads>()

    /** One semantics finding with the binding and assignment it came from. */
    private class Raw(val finding: Finding, val binding: SpecBinding, val site: YEntry)

    /** Findings that merge across bindings: same code, range, path and wording (with the role left out). */
    private data class GroupKey(val code: DiagnosticCode, val range: SourceRange, val path: List<String>, val template: String)

    fun run(): List<TypeFinding> {
        if (roleDirs != null && roleDirs.isEmpty()) return emptyList()
        val varService = VarService.getInstance(project)
        val raws = ArrayList<Raw>()
        for (site in ValueSites.of(file, context.kind)) {
            ProgressManager.checkCanceled()
            val name = site.key.text
            if (name.isEmpty() || name.startsWith(VAULT_PREFIX) || isOpaque(site.value)) continue
            val bindings = varService.symbol(root, name).specBindings.filter { roleDirs == null || it.role.dir in roleDirs }
            for (binding in bindings) {
                for (finding in validator.validateValue(binding.option, site.value, site.key).primary) raws += Raw(finding, binding, site)
            }
        }
        return merge(raws)
    }

    private fun merge(raws: List<Raw>): List<TypeFinding> {
        val groups = LinkedHashMap<GroupKey, MutableList<Raw>>()
        for (raw in raws) {
            val range = raw.finding.range ?: raw.site.key.range ?: continue
            val owner = ownerText(raw.binding)
            val message = raw.finding.message
            val template = if (owner.isNotEmpty() && owner in message) message.replaceFirst(owner, OWNER_MARK) else message
            groups.getOrPut(GroupKey(raw.finding.code, range, raw.finding.path, template)) { ArrayList() } += raw
        }
        // One problem per code, range and specs: ansible-core can report several errors for one written value (a comma
        // string for a list of dicts fails the list, then each element). The shallowest stays, with a count of the others.
        return groups.entries
            .groupBy { (key, members) -> Triple(key.code, key.range, members.map { it.binding }.distinct()) }
            .map { (_, same) ->
                val (key, members) = same.minBy { it.key.path.size }
                finding(key, members, same.flatMap { it.value }, same.size - 1)
            }
            .sortedWith(compareBy<TypeFinding>({ it.range.startOffset }, { it.code.ordinal }))
    }

    /** The finding of one group; [all] are the raw findings of the groups it stands for, [moreErrors] how many it hides. */
    private fun finding(key: GroupKey, members: List<Raw>, all: List<Raw>, moreErrors: Int): TypeFinding {
        val first = members.first()
        val bindings = members.map { it.binding }.distinct()
        val roles = bindings.map { it.role.name }.distinct()
        val path = first.finding.path
        val value = SpecPaths.valueAt(first.site.value, path)
        val unapplied = if (reachabilityApplies) roles.filterNot(::isApplied) else emptyList()
        val reads = if (isElementShapeCandidate(key.code, first.binding, path, value)) readsOf(path.first()).orderedLike(value as YMap) else LoopReads.NONE
        val message = buildString {
            append(if (isSecret(bindings, path, value)) masked(key.code, first.binding, path, bindings) else key.template.replace(OWNER_MARK, owners(bindings)))
            if (moreErrors > 0) append(AnsibilityTypesBundle.message("finding.more.errors", moreErrors))
            if (reads.attributes.isNotEmpty()) append(usageHint(reads, bindings))
            if (unapplied.isNotEmpty()) append(unappliedNote(unapplied))
        }
        return TypeFinding(
            code = key.code,
            message = message,
            range = TextRange(key.range.start, key.range.end),
            path = path,
            bindings = bindings,
            fixHints = all.flatMap { it.finding.fixHints }.distinct(),
            reachable = if (reachabilityApplies) unapplied.size < roles.size else null,
            value = value,
            usageAttributes = reads.attributes,
        )
    }

    // ------------------------------------------------------------------------------------------------ context

    /**
     * The roles a molecule scenario of [roleDir] runs: the role itself and every role the scenario's playbooks apply
     * (`roles:`, includes, imports, `meta/main.yml` dependencies). A file of a single scenario counts that scenario; the
     * role's shared `molecule/vars` files count all of them.
     */
    private fun scenarioRoles(roleDir: VirtualFile): Set<VirtualFile> {
        val workspace = AnsibleWorkspace.getInstance(project)
        val graph = PlayGraph.getInstance(project)
        val scenarios = context.moleculeScenarioDir?.let(::listOf)
            ?: roleDir.findChild(AnsibleLayout.MOLECULE)?.children?.filter { it.isDirectory }.orEmpty()
        val dirs = linkedSetOf(roleDir)
        for (scenario in scenarios) {
            for (file in scenario.children.orEmpty()) {
                ProgressManager.checkCanceled()
                if (file.isDirectory || workspace.contextOf(file)?.kind != FileKind.MOLECULE_PLAYBOOK) continue
                graph.playsOf(file).forEach { play -> play.roles.mapNotNullTo(dirs) { it.role?.dir } }
            }
        }
        return dirs
    }

    /** Whether a play of the root (molecule scenarios aside) applies [roleName]; cached for the run. */
    private fun isApplied(roleName: String): Boolean = applied.getOrPut(roleName) {
        PlayGraph.getInstance(project).playsApplying(root, roleName).any { !MoleculeVisibility.isMoleculeFile(project, it.file) }
    }

    private fun readsOf(name: String): LoopReads = loopReads.getOrPut(name) { LoopReads.of(project, root, name) }

    /** 🟣 CLAUDE X79 applies to a mapping item of a top-level list whose spec documents `elements: str`/`path` without options. */
    private fun isElementShapeCandidate(code: DiagnosticCode, binding: SpecBinding, path: List<String>, value: YValue?): Boolean {
        val option = binding.option
        return code == DiagnosticCode.T010_SHAPE_CONTRADICTION && path.size == 2 && SpecPaths.isIndex(path[1]) && value is YMap &&
            option.type == OptionType.List && option.options == null && (option.elements == OptionType.Str || option.elements == OptionType.Path)
    }

    private fun usageHint(reads: LoopReads, bindings: List<SpecBinding>): String {
        val attributes = reads.attributes.joinToString("/") { "`item.$it`" }
        val inRole = reads.files.all { file -> bindings.any { VfsUtilCore.isAncestor(it.role.dir, file, false) } }
        if (inRole) return AnsibilityTypesBundle.message("finding.usage.role", attributes)
        val files = reads.files.take(MAX_LISTED).joinToString(", ") { "`${VfsUtilCore.getRelativePath(it, root.dir) ?: it.name}`" }
        return AnsibilityTypesBundle.message("finding.usage.files", attributes, files)
    }

    private fun unappliedNote(roles: List<String>): String =
        if (roles.size == 1) {
            AnsibilityTypesBundle.message("finding.unapplied.one", roles.single())
        } else {
            AnsibilityTypesBundle.message("finding.unapplied.many", listed(roles))
        }

    // ------------------------------------------------------------------------------------------------ wording

    /** The owner text the semantics layer writes for [binding]'s option (`FindingText.owner`). */
    private fun ownerText(binding: SpecBinding): String = when (val origin = binding.option.origin) {
        is SpecOrigin.RoleSpec -> " for role `${origin.roleName}` (entry point `${origin.entryPoint}`)"
        else -> ""
    }

    /** The owner text for one or several bindings. */
    private fun owners(bindings: List<SpecBinding>): String {
        if (bindings.size == 1) return ownerText(bindings.single())
        val entryPoints = bindings.map { it.entryPoint }.distinct()
        return if (entryPoints.size == 1) {
            AnsibilityTypesBundle.message("finding.owner.roles", listed(bindings.map { it.role.name }.distinct()), entryPoints.single())
        } else {
            AnsibilityTypesBundle.message(
                "finding.owner.roles.entry.points",
                bindings.joinToString(", ") { AnsibilityTypesBundle.message("finding.owner.role.entry.point", it.role.name, it.entryPoint) },
            )
        }
    }

    /** `` `a`, `b` and `c` ``; more than [MAX_LISTED] names end in "and N more". */
    private fun listed(names: List<String>): String {
        val quoted = names.map { "`$it`" }
        return when {
            quoted.size == 1 -> quoted.single()
            quoted.size <= MAX_LISTED -> AnsibilityTypesBundle.message("finding.list.and", quoted.dropLast(1).joinToString(", "), quoted.last())
            else -> AnsibilityTypesBundle.message("finding.list.more", quoted.take(MAX_LISTED).joinToString(", "), quoted.size - MAX_LISTED)
        }
    }

    /** True when the message must not show the value: a `no_log` option on the path, or a plaintext `vault_*` key in it. */
    private fun isSecret(bindings: List<SpecBinding>, path: List<String>, value: YValue?): Boolean =
        bindings.any { SpecPaths.crossesNoLog(it.option, path) } || (value != null && holdsPlaintextVaultKey(value))

    private fun masked(code: DiagnosticCode, binding: SpecBinding, path: List<String>, bindings: List<SpecBinding>): String {
        val where = pathText(path)
        val owner = owners(bindings)
        return if (code in REJECTIONS) {
            AnsibilityTypesBundle.message("finding.masked.rejected", core.toString(), where, owner)
        } else {
            val type = SpecPaths.documentedTypeAt(binding.option, path)?.name ?: "?"
            AnsibilityTypesBundle.message("finding.masked.coerced", core.toString(), where, owner, type)
        }
    }

    private fun pathText(path: List<String>): String = buildString {
        path.forEachIndexed { index, segment ->
            when {
                index == 0 -> append(segment)
                SpecPaths.isIndex(segment) -> append('[').append(segment).append(']')
                else -> append('.').append(segment)
            }
        }
    }

    companion object {
        private const val VAULT_PREFIX = "vault_"
        private const val OWNER_MARK = "\u0000"
        private const val MAX_LISTED = 4

        /** Kinds outside the declaring role, where "no play applies role X" is meaningful context (D6). */
        private val REACHABILITY_KINDS = setOf(
            FileKind.GROUP_VARS, FileKind.HOST_VARS, FileKind.INVENTORY, FileKind.PLAYBOOK, FileKind.ROLE_TASKS, FileKind.ROLE_HANDLERS,
        )

        /** Codes that report a rejection (the rest report a coercion of a documented-type mismatch). */
        private val REJECTIONS = setOf(
            DiagnosticCode.T001_VALUE_REJECTED, DiagnosticCode.T002_UNSUPPORTED_SUB_OPTION, DiagnosticCode.T003_MISSING_REQUIRED_SUB_OPTION,
            DiagnosticCode.T004_CHOICE_MISMATCH, DiagnosticCode.T005_NULL_FOR_TYPED_OPTION,
        )

        /** Values the checks never judge at the top level: vault values and templated scalars (ANS-T020). */
        fun isOpaque(value: YValue): Boolean = value is YVault || (value is YScalar && SpecValidator.containsJinja(value))

        private fun holdsPlaintextVaultKey(value: YValue): Boolean = when (value) {
            is YMap -> value.entries.any { (it.key.text.startsWith(VAULT_PREFIX) && it.value !is YVault) || holdsPlaintextVaultKey(it.value) }
            is YSeq -> value.items.any(::holdsPlaintextVaultKey)
            else -> false
        }
    }
}
