package de.terletzkiy.ansibility.api

import com.intellij.openapi.util.TextRange

/** Which `loop_control` key names the variable of a [LoopVarSite]. */
enum class LoopVarKind {
    /** `loop_control.loop_var`: the loop variable (instead of `item`). */
    LOOP_VAR,

    /** `loop_control.index_var`: the loop index. */
    INDEX_VAR,
}

/**
 * The value of a task's `loop_control.loop_var` or `index_var` (`loop_var: rule_set`): where the task names the variable
 * its loop binds, inside the task and in what the task includes or renders. [range] is the name inside the scalar
 * (quotes excluded).
 */
data class LoopVarSite(val name: String, val kind: LoopVarKind, override val range: TextRange) : AnsibleSite
