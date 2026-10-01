package de.terletzkiy.ansibility.dispatch

import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.JinjaContainer
import de.terletzkiy.ansibility.api.KeywordLevel
import de.terletzkiy.ansibility.api.RenderKind
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.context.ContextPresentation
import org.jetbrains.annotations.Nls

/** Human-readable descriptions of [AnsibleSite]s and variable facts for X75. Pure, so the wording is unit-tested. */
object SitePresentation {

    /** E.g. `Variable reference item.floating.ssl (Jinja in YAML)` or `Module ansible.builtin.template`. */
    @Nls
    fun describe(site: AnsibleSite): String = when (site) {
        is AnsibleSite.VarRef -> {
            val path = (listOf(site.name) + site.attrPath).joinToString(".")
            val key = if (site.name in site.localNames) "site.var.ref.local" else "site.var.ref"
            AnsibilityDispatchBundle.message(key, path, containerName(site.container))
        }
        is AnsibleSite.VarKey ->
            AnsibilityDispatchBundle.message("site.var.key", site.keyPath.joinToString("."), ContextPresentation.fileKindName(site.kind))
        is AnsibleSite.ModuleKey -> AnsibilityDispatchBundle.message("site.module", site.fqcn)
        is AnsibleSite.ModuleOptionKey -> AnsibilityDispatchBundle.message("site.module.option", site.path.joinToString("."), site.fqcn)
        is AnsibleSite.KeywordKey -> AnsibilityDispatchBundle.message("site.keyword", site.keyword, levelName(site.level))
        is AnsibleSite.RoleRef -> AnsibilityDispatchBundle.message("site.role", site.name)
        is AnsibleSite.TaskFileRef ->
            if (site.roleName == null) AnsibilityDispatchBundle.message("site.task.file", site.path)
            else AnsibilityDispatchBundle.message("site.task.file.of.role", site.path, site.roleName)
        is AnsibleSite.HandlerRef -> AnsibilityDispatchBundle.message("site.handler", site.name)
        is AnsibleSite.TemplateRef ->
            AnsibilityDispatchBundle.message(if (site.isCopySource) "site.copy.source" else "site.template", site.path)
        is AnsibleSite.PlaybookFileRef -> AnsibilityDispatchBundle.message("site.playbook.file", site.path)
        is AnsibleSite.JinjaFilter -> AnsibilityDispatchBundle.message("site.jinja.filter", site.name)
        is AnsibleSite.JinjaTest -> AnsibilityDispatchBundle.message("site.jinja.test", site.name)
    }

    /**
     * The variable a site names, or null for other sites: a [AnsibleSite.VarRef]'s name; for a
     * [AnsibleSite.VarKey], the top-level key of a vars file, the option name after `options` in
     * `argument_specs`, and otherwise the key after the last `vars` (play, block and task vars).
     */
    fun variableName(site: AnsibleSite): String? = when (site) {
        is AnsibleSite.VarRef -> site.name
        is AnsibleSite.VarKey -> variableNameOf(site.keyPath, site.kind)
        else -> null
    }

    private fun variableNameOf(keyPath: List<String>, kind: FileKind): String? {
        if (keyPath.isEmpty()) return null
        return when (kind) {
            FileKind.ROLE_DEFAULTS, FileKind.ROLE_VARS, FileKind.GROUP_VARS, FileKind.HOST_VARS, FileKind.MOLECULE_VARS ->
                keyPath.first()
            // keyPath starts at the variable name (api.AnsibleSite.VarKey); the OPTIONS/VARS branches keep old shapes working.
            FileKind.ROLE_ARGSPEC -> keyPath.indexOf(OPTIONS).takeIf { it >= 0 }?.let { keyPath.getOrNull(it + 1) } ?: keyPath.first()
            else -> keyPath.lastIndexOf(VARS).takeIf { it >= 0 }?.let { keyPath.getOrNull(it + 1) } ?: keyPath.first()
        }
    }

    @Nls
    fun containerName(container: JinjaContainer): String = AnsibilityDispatchBundle.message(
        when (container) {
            JinjaContainer.TEMPLATE_FILE -> "container.template.file"
            JinjaContainer.YAML_TEMPLATE -> "container.yaml.template"
            JinjaContainer.YAML_EXPRESSION -> "container.yaml.expression"
        },
    )

    /** How a rendering task names its template: `static src`, `dynamic src`, `included by` … */
    @Nls
    fun renderKindName(kind: RenderKind): String = AnsibilityDispatchBundle.message(
        when (kind) {
            RenderKind.STATIC -> "render.kind.static"
            RenderKind.DYNAMIC_PREFIX -> "render.kind.dynamic"
            RenderKind.WHOLE_VAR -> "render.kind.whole.var"
            RenderKind.FILEGLOB -> "render.kind.fileglob"
            RenderKind.LOOKUP -> "render.kind.lookup"
            RenderKind.INCLUDE -> "render.kind.include"
        },
    )

    @Nls
    fun levelName(level: KeywordLevel): String = AnsibilityDispatchBundle.message(
        when (level) {
            KeywordLevel.PLAY -> "keyword.level.play"
            KeywordLevel.BLOCK -> "keyword.level.block"
            KeywordLevel.TASK -> "keyword.level.task"
            KeywordLevel.HANDLER -> "keyword.level.handler"
            KeywordLevel.ROLE_ENTRY -> "keyword.level.role.entry"
            KeywordLevel.LOOP_CONTROL -> "keyword.level.loop.control"
            KeywordLevel.PLAYBOOK_INCLUDE -> "keyword.level.playbook.include"
        },
    )

    @Nls
    fun definitionKindName(kind: VarDefKind): String = AnsibilityDispatchBundle.message(
        when (kind) {
            VarDefKind.SPEC_OPTION -> "def.kind.spec.option"
            VarDefKind.ROLE_DEFAULT -> "def.kind.role.default"
            VarDefKind.ROLE_VAR -> "def.kind.role.var"
            VarDefKind.INVENTORY_INLINE -> "def.kind.inventory.inline"
            VarDefKind.GROUP_VARS -> "def.kind.group.vars"
            VarDefKind.HOST_VARS -> "def.kind.host.vars"
            VarDefKind.MOLECULE_INVENTORY -> "def.kind.molecule.inventory"
            VarDefKind.PLAY_VARS -> "def.kind.play.vars"
            VarDefKind.VARS_FILES -> "def.kind.vars.files"
            VarDefKind.BLOCK_VARS -> "def.kind.block.vars"
            VarDefKind.TASK_VARS -> "def.kind.task.vars"
            VarDefKind.INCLUDE_PARAMS -> "def.kind.include.params"
            VarDefKind.SET_FACT -> "def.kind.set.fact"
            VarDefKind.REGISTER -> "def.kind.register"
            VarDefKind.LOOP_VAR -> "def.kind.loop.var"
            VarDefKind.INDEX_VAR -> "def.kind.index.var"
            VarDefKind.TEMPLATE_VARS -> "def.kind.template.vars"
            VarDefKind.JINJA_LOCAL -> "def.kind.jinja.local"
            VarDefKind.ROLE_PARAMS -> "def.kind.role.params"
            VarDefKind.VARS_PROMPT -> "def.kind.vars.prompt"
        },
    )

    /** `role default, group_vars ×2`: kinds in first-seen order, with a count when a kind repeats. */
    @Nls
    fun definitionKinds(kinds: List<VarDefKind>): String = kinds.groupingBy { it }.eachCount().entries.joinToString(", ") { (kind, count) ->
        val name = definitionKindName(kind)
        if (count == 1) name else AnsibilityDispatchBundle.message("def.kind.count", name, count)
    }

    private const val OPTIONS = "options"
    private const val VARS = "vars"
}
