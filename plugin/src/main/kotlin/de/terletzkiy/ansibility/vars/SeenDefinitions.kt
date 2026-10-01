package de.terletzkiy.ansibility.vars

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.OutcomeGroup
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarDefinition
import de.terletzkiy.ansibility.api.VarSymbol

/**
 * Rule 0 of [VarNavigation]: a reference inside a file that itself defines variables goes to the definition the
 * reference actually sees there, not to the variable's documentation. `color_prompt_environment:
 * "{{ environment_group | upper }}"` in `environments/ops/group_vars/all/vars.yml` goes straight to
 * `environment_group: ops` a few lines below; the spec stays one Ctrl+B away, on that key ([VarNavigation]'s key rule).
 *
 * Per file kind:
 * - inventory files (`group_vars`, `host_vars`, `hosts.yml`, molecule inventories): the definitions that win for the
 *   hosts the caret applies to ([AnsibleContextService.hostScope], so a `hosts.yml` host entry, a selected environment
 *   or a selected host narrow them), a winner in the same file first, then the largest host group first, ties by path.
 *   A value is templated per host where it is used, so a same-file key that `host_vars`, a more specific group or
 *   playbook-level `group_vars` override is not offered for those hosts. Only when the model has no answer (indexing,
 *   an empty scope) the same file's keys are used, nearest before the caret first;
 * - role `defaults/`: the own role's `vars/` value when it has one (role vars always beat defaults), else the same
 *   file's key, else the role's other `defaults/` files;
 * - role `vars/`: the same file's key, else the role's other `vars/` files, else its `defaults/`;
 * - play `vars_files` targets (play-level values): the same file's key.
 *
 * The key whose value holds the reference (`x: "{{ x }}"`, a recursive definition) is never a target ([excluded]).
 * Other files (tasks, templates, playbooks) keep the documented order of [VarNavigation].
 */
internal object SeenDefinitions {
    private val LOG = logger<SeenDefinitions>()

    /** File kinds whose values Ansible evaluates per host from the inventory. */
    private val INVENTORY_KINDS = setOf(
        FileKind.GROUP_VARS, FileKind.HOST_VARS, FileKind.INVENTORY, FileKind.MOLECULE_CONFIG, FileKind.MOLECULE_VARS,
    )

    /** Kinds that never answer "where does this file get the value from". */
    private val NOT_VALUES = setOf(VarDefKind.SPEC_OPTION, VarDefKind.JINJA_LOCAL)

    /**
     * One target: a known [definition], or a winner the symbol does not list (an inventory `host:port` entry), then
     * only [location], its [layerLabel] and [owner].
     */
    class Seen(val location: SourceLocation, val definition: VarDefinition?, val layerLabel: String?, val owner: String?)

    /** The targets for a reference at [offset] in [file]; empty when rule 0 has nothing to say. */
    fun of(
        project: Project,
        file: VirtualFile,
        offset: Int,
        context: FileContext,
        symbol: VarSymbol,
        excluded: SourceLocation?,
    ): List<Seen> {
        val values = symbol.definitions.filter { it.kind !in NOT_VALUES && it.location != excluded }
        val sameFile = VarRanking.nearestFirst(values.filter { it.location.file == file }, file, offset)
        return when (context.kind) {
            in INVENTORY_KINDS -> winners(project, file, offset, symbol.name, values, excluded).ifEmpty { sameFile.map(::seen) }
            FileKind.ROLE_DEFAULTS -> {
                val own = ownRole(context, values)
                own(VarDefKind.ROLE_VAR).ifEmpty { sameFile }.ifEmpty { own(VarDefKind.ROLE_DEFAULT) }.map(::seen)
            }
            FileKind.ROLE_VARS -> {
                val own = ownRole(context, values)
                sameFile.ifEmpty { own(VarDefKind.ROLE_VAR) }.ifEmpty { own(VarDefKind.ROLE_DEFAULT) }.map(::seen)
            }
            else -> if (sameFile.any { it.kind == VarDefKind.VARS_FILES }) sameFile.map(::seen) else emptyList()
        }
    }

    private fun seen(definition: VarDefinition) = Seen(definition.location, definition, null, null)

    /** The own role's definitions of one kind, `main` files first. */
    private fun ownRole(context: FileContext, values: List<VarDefinition>): (VarDefKind) -> List<VarDefinition> {
        val role = context.roleName ?: return { emptyList() }
        val own = values.filter { it.roleName == role }
        return { kind ->
            own.filter { it.kind == kind }.sortedWith(
                compareBy<VarDefinition>({ if (it.location.file.nameWithoutExtension == "main") 0 else 1 }, { it.location.file.path }, { it.location.offset }),
            )
        }
    }

    /** The winners over the hosts the caret applies to; none in dumb mode or when the model cannot answer. */
    private fun winners(
        project: Project,
        file: VirtualFile,
        offset: Int,
        name: String,
        values: List<VarDefinition>,
        excluded: SourceLocation?,
    ): List<Seen> {
        if (DumbService.isDumb(project)) return emptyList()
        val groups = try {
            val service = AnsibleContextService.getInstance(project)
            val breakdown = service.effective(service.hostScope(file, offset), name)
            (breakdown.groups + breakdown.molecule).filter { it.winner != null }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: IndexNotReadyException) {
            return emptyList()
        } catch (e: RuntimeException) {
            LOG.warn("Effective winners for navigation failed in ${file.path}", e)
            return emptyList()
        }
        val byLocation = values.associateBy { it.location }
        return groups
            .sortedWith(
                compareBy<OutcomeGroup> { if (it.winner!!.file == file) 0 else 1 }
                    .thenByDescending { it.hosts.size }
                    .thenBy { it.winner!!.file.path }
                    .thenBy { it.winner!!.offset },
            )
            .distinctBy { it.winner!!.file to it.winner!!.offset }
            .mapNotNull { group ->
                ProgressManager.checkCanceled()
                val ref = group.winner!!
                val location = SourceLocation(ref.file, ref.offset)
                if (location == excluded) return@mapNotNull null
                byLocation[location]?.let(::seen) ?: Seen(
                    location,
                    definition = null,
                    layerLabel = AnsibilityVarsBundle.message("layer.${ref.layer.name}", ref.group ?: ref.host ?: ""),
                    owner = group.hosts.firstOrNull()?.environment?.let { AnsibilityVarsBundle.message("card.this.environment", it) },
                )
            }
    }
}
