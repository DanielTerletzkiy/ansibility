package de.terletzkiy.ansibility.api

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.semantics.schema.OptionSpec

/** How a render site names its template (plan A.7 `srcKind`, F2.3). */
enum class RenderKind {
    /** A literal `src` of a template task: `src: templates/haproxy.cfg.j2`. */
    STATIC,

    /** A literal prefix plus Jinja, expanded through the spec `choices` or the directory listing. */
    DYNAMIC_PREFIX,

    /** `src: "{{ var }}"`, expanded through the variable's known values. */
    WHOLE_VAR,

    /** `src: "{{ item }}"` over a `fileglob` loop. */
    FILEGLOB,

    /** `lookup('template', 'x.j2', …)` in a task or in another template. */
    LOOKUP,

    /** `{% include %}` or `{% extends %}` in another template, which passes its context on. */
    INCLUDE,
}

/** The loop of a rendering task, as far as the plugin can type it (plan F1.7). */
data class RenderLoop(
    /** `loop_control.loop_var`, or `item`. */
    val loopVar: String,
    /** `loop_control.index_var`, if any (an int). */
    val indexVar: String?,
    /** `loop_control.extended`: `ansible_loop` is defined. */
    val extended: Boolean,
    /** The element type as an option tree (named [loopVar]); null when the iterated value is unknown. */
    val item: OptionSpec?,
    /** The variable the loop iterates, e.g. `grafana_nginx_sites`; null for literal lists and lookups. */
    val sourceVariable: String?,
    /** Accessors below [sourceVariable] (`item.children` iterates `["children"]` of `item`). */
    val sourcePath: List<String>,
)

/**
 * One way a template gets rendered (plan F2.3): by a task of the same root, or through another template that
 * includes it (whose contexts it inherits). The union of a template's contexts decides which variables it sees.
 *
 * The play scope (tier T4) follows from [role] (the plays applying it, `PlayGraph.playsApplying`) or, for a playbook
 * task, from the play around [taskSite].
 */
data class RenderContext(
    val template: VirtualFile,
    val kind: RenderKind,
    /** The rendering task's mapping (or, for [RenderKind.INCLUDE], the including template's tag). */
    val site: SourceLocation,
    /** The file of the task that finally renders the template (the including chain resolved). */
    val taskSite: SourceLocation,
    /** The role of the rendering task (tier T3); null for playbook tasks. */
    val role: RoleRef?,
    /** The rendering task's loop (tier T1); null when it does not loop. */
    val loop: RenderLoop?,
    /** Tier T2 names: the task's and enclosing blocks' `vars:` keys and `lookup('template', template_vars=…)` keys. */
    val taskVars: List<String>,
    /** Templates the context was inherited through, outermost first (empty for a direct render). */
    val via: List<VirtualFile>,
)

/**
 * Render contexts of templates (plan A.7 query-time rules, A.8 `renderContexts`), implemented in
 * `resolve.template`. Scoped to the template's root; a template with no resolved context gets an empty list and
 * callers fall back to root-wide resolution. Call in a read action in smart mode.
 */
interface TemplateContextService {
    fun renderContexts(template: VirtualFile): List<RenderContext>

    companion object {
        fun getInstance(project: Project): TemplateContextService = project.service()
    }
}
