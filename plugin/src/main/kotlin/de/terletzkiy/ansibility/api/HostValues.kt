package de.terletzkiy.ansibility.api

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import de.terletzkiy.ansibility.semantics.yaml.YValue

/**
 * The effective value of one name in one evaluation context (plan amendment R11, R11-8). [value] null: no static
 * definition applies, only [runtimeMarkers] may set the name. Nothing is decrypted: a vault value is a `YVault` and
 * [secret] is set.
 */
class HostValue(
    val value: YValue?,
    /** The winning definition; null for the site's own task vars and when [value] is null. */
    val source: VarSourceRef?,
    val secret: Boolean,
    /** `set_fact`, `register` and `include_vars` tasks of the context's plays and roles that set the name. */
    val runtimeMarkers: List<RuntimeMarker>,
)

/** Single-name values of one context; built once per render and read per name. Call in a read action in smart mode. */
interface HostValues {
    val target: EvalTarget

    fun valueOf(name: String): HostValue?
}

/**
 * The host area's value seam for rendering (plan amendment R11, "The host value seam"). The precedence rules stay
 * the engine's: the site's block and task vars go into it as level-15 sources, so role params and extra vars still
 * beat them.
 */
interface HostValueService {
    /** The values a task of [runningRole] sees on [target], with [siteVars] (the task's and its blocks' `vars:`) at level 15. */
    fun values(target: EvalTarget, runningRole: String?, siteVars: Map<String, YValue> = emptyMap()): HostValues?

    companion object {
        fun getInstance(project: Project): HostValueService = project.service()
    }
}
