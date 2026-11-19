package de.terletzkiy.ansibility.vars

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.api.ValueShape
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarDefinition
import de.terletzkiy.ansibility.api.VarsLayer
import de.terletzkiy.ansibility.context.AnsibleLayout
import org.jetbrains.annotations.Nls

/**
 * The short texts of variable sources (plan amendment R31, D230, D234), shared by the Ctrl+B rows ([VarNavigation]) and
 * the inline variable source hints (`vars.hints`): the layer (`group_vars web`, `playbook group_vars all`, `defaults`),
 * its owner (the environment `prod` or the role) and the value as written, vault-safe.
 *
 * Values come from the index's previews only ([VarDefinition.preview], the one vault-safe rule): a value in a vault file,
 * of a `vault_*` name or tagged `!vault` reads `🔒 vault`, a variable a spec keeps secret (`no_log`) `🔒 no_log`. Nothing
 * here decrypts or reads a file.
 */
internal object VarSourceTexts {
    /** Characters of a value a Ctrl+B row shows before "…" (D230). */
    const val ROW_VALUE_MAX: Int = 40

    /** Characters of a value an inline hint shows before "…" (D234). */
    const val HINT_VALUE_MAX: Int = 24

    const val LOCK: String = "🔒"
    const val ELLIPSIS: String = "…"

    /** The kinds whose layer names an inventory or playbook group or host (`group_vars web`, `host_vars prod-web1`). */
    private val INVENTORY_KINDS = setOf(VarDefKind.GROUP_VARS, VarDefKind.HOST_VARS, VarDefKind.INVENTORY_INLINE, VarDefKind.MOLECULE_INVENTORY)

    /** The kinds of a role's own files, whose owner is the role. */
    private val ROLE_KINDS = setOf(VarDefKind.SPEC_OPTION, VarDefKind.ROLE_DEFAULT, VarDefKind.ROLE_VAR)

    /** `group_vars web`, `playbook host_vars web1`, `inventory group web`, `defaults`, `spec`, `play vars`, `set_fact` … */
    @Nls
    fun layer(definition: VarDefinition): String {
        val layer = definition.layer
        if (definition.kind in INVENTORY_KINDS && layer != null) return layer(layer, definition.group, definition.host)
        return VarLabels.kind(definition.kind)
    }

    /** The short label of [layer] for a source of [group] or [host] (an effective winner the symbol may not list). */
    @Nls
    fun layer(layer: VarsLayer, group: String?, host: String?): String {
        val owner = when (layer) {
            VarsLayer.INVENTORY_GROUP_VARS_ALL, VarsLayer.PLAYBOOK_GROUP_VARS_ALL -> group ?: ALL
            else -> group ?: host ?: ""
        }
        return AnsibilityVarsBundle.message("source.layer.${layer.name}", owner)
    }

    /** The environment's short name (`prod` of `environments/prod`), or null for a definition outside the inventories. */
    fun environment(definition: VarDefinition): String? = definition.environment?.let(::environmentName)

    /** `prod` of `environments/prod` (and of a plain `prod`). */
    fun environmentName(environment: String): String = environment.substringAfterLast('/')

    /**
     * The owner a row names after the layer: the role of a role's own files, else the environment (with [project], every
     * environment a shared vars directory serves: `prod, staging`), else, for playbook-level vars of a nested playbook
     * root, that root's directory (`deploy`), else the role.
     */
    fun owner(definition: VarDefinition, project: Project? = null): String? = when {
        definition.kind in ROLE_KINDS -> definition.roleName
        else -> environments(definition, project) ?: project?.let { playbookDir(it, definition) } ?: definition.roleName
    }

    /**
     * `prod` of a definition in `environments/prod`; with [project], `prod, staging` for a vars directory that several
     * inventories share (`inventory/group_vars` next to `inventory/prod.yml` and `inventory/staging.yml`); null outside
     * the inventories.
     */
    fun environments(definition: VarDefinition, project: Project?): String? {
        val own = environment(definition) ?: return null
        if (project == null) return own
        val served = AnsibleWorkspace.getInstance(project).contextOf(definition.location.file)?.environments.orEmpty()
        return if (served.size > 1) served.joinToString(", ", transform = ::environmentName) else own
    }

    /**
     * For a playbook-level `group_vars`/`host_vars` definition of a nested playbook root (plan amendment R31 fixes,
     * decision 11), that root's directory relative to the root it is nested in (`deploy`, `danger_zone/database`): its
     * values apply only to the playbooks there, and its paths read like the project root's own. Null otherwise.
     */
    fun playbookDir(project: Project, definition: VarDefinition): String? {
        if (definition.layer !in PLAYBOOK_LAYERS) return null
        val dir = generateSequence(definition.location.file.parent) { it.parent }
            .firstOrNull { it.name == AnsibleLayout.GROUP_VARS || it.name == AnsibleLayout.HOST_VARS }
            ?.parent ?: return null
        val nested = AnsibleWorkspace.getInstance(project).roots().firstOrNull { it.dir == dir && it.kind == RootKind.NESTED_PLAYBOOK } ?: return null
        return nested.parentDir?.let { VfsUtilCore.getRelativePath(dir, it) } ?: dir.name
    }

    /** True when the value of [definition] is never shown: a vault file, a `vault_*` name or a `!vault` value. */
    fun isVault(definition: VarDefinition): Boolean =
        definition.valueShape == ValueShape.VAULT || VaultInfo.isSecret(definition.name, definition.location.file)

    /**
     * The value as written, at most [max] characters (then "…"): `🔒 vault` for a secret value, `🔒 no_log` when [noLog]
     * (the spec keeps every value of the variable secret); null for a spec option, which declares rather than sets, and
     * for a definition without a preview.
     */
    @Nls
    fun value(definition: VarDefinition, max: Int, noLog: Boolean): String? {
        if (definition.kind == VarDefKind.SPEC_OPTION) return null
        return when {
            noLog -> AnsibilityVarsBundle.message("source.value.nolog")
            isVault(definition) -> AnsibilityVarsBundle.message("source.value.vault")
            else -> definition.preview?.let { cut(it, max) }
        }
    }

    /** The short value of an inline hint: the value cut at [HINT_VALUE_MAX], a lone `🔒` for secrets, "…" when unknown. */
    @Nls
    fun hintValue(definition: VarDefinition, noLog: Boolean): String = when {
        noLog || isVault(definition) -> LOCK
        else -> definition.preview?.let { cut(it, HINT_VALUE_MAX) } ?: ELLIPSIS
    }

    /** [text] on one line, at most [max] characters including the closing "…". */
    fun cut(text: String, max: Int): String {
        val line = text.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
        return if (line.length <= max) line else line.take(max - 1) + ELLIPSIS
    }

    /**
     * One Ctrl+B row (D230): `group_vars web · prod · environments/prod/group_vars/web/vars.yml:12 = 1.4.0`, `defaults ·
     * web · roles/web/defaults/main.yml:3 = 80`, `spec · web · roles/web/meta/argument_specs.yml:5`, `playbook group_vars
     * all · deploy · group_vars/all.yml:2 = 3.0.0` (a nested playbook root's vars, from that root); [mark] in front
     * (`✓ wins for prod-web1`).
     */
    @Nls
    fun row(project: Project, root: AnsibleRoot, definition: VarDefinition, noLog: Boolean, mark: String? = null): String {
        val where = VarLocations.label(root, definition.location)
        val value = value(definition, ROW_VALUE_MAX, noLog)
        val located = if (value == null) where else AnsibilityVarsBundle.message("source.row.value", where, value)
        return listOfNotNull(mark, layer(definition), owner(definition, project), located).joinToString(VarLabels.SEPARATOR)
    }

    private const val ALL = "all"

    /** The playbook-level layers, whose definitions apply only to the playbooks of their directory. */
    private val PLAYBOOK_LAYERS = setOf(VarsLayer.PLAYBOOK_GROUP_VARS_ALL, VarsLayer.PLAYBOOK_GROUP_VARS, VarsLayer.PLAYBOOK_HOST_VARS)
}
