package de.terletzkiy.ansibility.vars.usages

import com.intellij.usages.impl.rules.UsageType
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarDefinition
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaIndirection
import org.jetbrains.annotations.PropertyKey
import java.util.concurrent.ConcurrentHashMap

private const val BUNDLE = "messages.AnsibilityUsagesBundle"

/**
 * The usage-type group of one occurrence of a variable in the Find tool window (F1.10): `Read: template`,
 * `Set: group_vars · prod`, `Spec: argument spec`, `Shared name · role web` … The platform sorts the groups by
 * their text, so reads (`Read:`) come before writes (`Set:`), the other owners of a runtime name (`Shared name`) and
 * declarations (`Spec:`).
 *
 * [usageType] gives one [UsageType] instance per label: the platform's grouping compares usage types by identity.
 */
internal data class VarUsageKind(
    @PropertyKey(resourceBundle = BUNDLE) private val key: String,
    private val param: String? = null,
) {
    val usageType: UsageType get() = TYPES.computeIfAbsent(this) { kind -> UsageType { kind.text } }

    val text: String
        get() = if (param == null) AnsibilityUsagesBundle.message(key) else AnsibilityUsagesBundle.message(key, param)

    /** A read by name (`hostvars[h].x`, `vars['x']`) rather than a direct use or a definition. */
    val isByName: Boolean get() = this == READ_INDIRECT || this == READ_BY_NAME

    companion object {
        private val TYPES = ConcurrentHashMap<VarUsageKind, UsageType>()

        val READ_TEMPLATE = VarUsageKind("usage.read.template")
        val READ_TASK = VarUsageKind("usage.read.task")
        val READ_CONDITION = VarUsageKind("usage.read.condition")
        val READ_DEBUG_VAR = VarUsageKind("usage.read.debug.var")
        val READ_VALUE = VarUsageKind("usage.read.value")
        val READ_LOOP_SOURCE = VarUsageKind("usage.read.loop.source")
        val READ_PLAYBOOK = VarUsageKind("usage.read.playbook")
        val READ_LOCAL = VarUsageKind("usage.read.local")

        /** `hostvars[h].x`, `hostvars[h]['x']`, `map('extract', hostvars, 'x')`: some host's variable ([JinjaIndirection.HOSTVARS]). */
        val READ_INDIRECT = VarUsageKind("usage.read.indirect")

        /** `vars['x']`, `vars.x`, `lookup('vars', 'x')`: the current host's variable read by name ([JinjaIndirection.VARS]). */
        val READ_BY_NAME = VarUsageKind("usage.read.by.name")
        val SET_LOOP = VarUsageKind("usage.set.loop")
        val SET_LOOP_VAR = VarUsageKind("usage.set.loop.var")
        val SET_INDEX_VAR = VarUsageKind("usage.set.index.var")
        val SET_LOCAL = VarUsageKind("usage.set.local")
        val SPEC = VarUsageKind("usage.spec")

        /** The group of a read by name through [via] (FU2): whatever holds it, a template, a task or a condition. */
        fun read(via: JinjaIndirection): VarUsageKind = when (via) {
            JinjaIndirection.HOSTVARS -> READ_INDIRECT
            JinjaIndirection.VARS -> READ_BY_NAME
        }

        /** The group of the other owner [ownerLabel] of a runtime name (D-FU5). */
        fun shared(ownerLabel: String): VarUsageKind = VarUsageKind("usage.shared", ownerLabel)

        /** The group of [definition], by its kind and, for inventory layers, its environment (`playbook` for playbook-level files). */
        fun of(definition: VarDefinition): VarUsageKind = when (definition.kind) {
            VarDefKind.SPEC_OPTION -> SPEC
            VarDefKind.ROLE_DEFAULT -> VarUsageKind("usage.set.role.default")
            VarDefKind.ROLE_VAR -> VarUsageKind("usage.set.role.vars")
            VarDefKind.GROUP_VARS -> VarUsageKind("usage.set.group.vars", environmentOf(definition))
            VarDefKind.HOST_VARS -> VarUsageKind("usage.set.host.vars", environmentOf(definition))
            VarDefKind.INVENTORY_INLINE -> VarUsageKind("usage.set.inventory", environmentOf(definition))
            VarDefKind.MOLECULE_INVENTORY -> VarUsageKind("usage.set.molecule")
            VarDefKind.PLAY_VARS -> VarUsageKind("usage.set.play.vars")
            VarDefKind.VARS_FILES -> VarUsageKind("usage.set.vars.files")
            VarDefKind.BLOCK_VARS -> VarUsageKind("usage.set.block.vars")
            VarDefKind.TASK_VARS -> VarUsageKind("usage.set.task.vars")
            VarDefKind.INCLUDE_PARAMS -> VarUsageKind("usage.set.include.vars")
            VarDefKind.ROLE_PARAMS -> VarUsageKind("usage.set.role.params")
            VarDefKind.SET_FACT -> VarUsageKind("usage.set.set.fact")
            VarDefKind.REGISTER -> VarUsageKind("usage.set.register")
            VarDefKind.LOOP_VAR -> SET_LOOP_VAR
            VarDefKind.INDEX_VAR -> SET_INDEX_VAR
            VarDefKind.TEMPLATE_VARS -> VarUsageKind("usage.set.template.vars")
            VarDefKind.VARS_PROMPT -> VarUsageKind("usage.set.vars.prompt")
            VarDefKind.JINJA_LOCAL -> SET_LOCAL
            VarDefKind.INCLUDE_VARS -> VarUsageKind("usage.set.include.vars.file")
        }

        private fun environmentOf(definition: VarDefinition): String =
            definition.environment ?: AnsibilityUsagesBundle.message("usage.layer.playbook")
    }
}
