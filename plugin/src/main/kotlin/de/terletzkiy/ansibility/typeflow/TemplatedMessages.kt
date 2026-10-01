package de.terletzkiy.ansibility.typeflow

import de.terletzkiy.ansibility.api.SpecBinding
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.schema.OptionType
import de.terletzkiy.ansibility.semantics.typeflow.AValue
import de.terletzkiy.ansibility.semantics.typeflow.BaseType
import de.terletzkiy.ansibility.semantics.typeflow.ChainTrace
import de.terletzkiy.ansibility.semantics.typeflow.RuntimeOutcome
import de.terletzkiy.ansibility.semantics.typeflow.TemplatedMismatch
import de.terletzkiy.ansibility.semantics.typeflow.TypeEffect
import de.terletzkiy.ansibility.semantics.typeflow.TypeOrigin
import de.terletzkiy.ansibility.semantics.value.PyRepr
import de.terletzkiy.ansibility.typeflow.AnsibilityTypeflowBundle.message

/**
 * The ANS-T020 message (plan A.6, F3.4): the documented type and the declaring role, the logical type with the chain
 * that gives it, and what the target ansible-core does with the runtime value, e.g.
 * "documented `str` for role `haproxy` (entry point `main`), but this is int `65535` via
 * `haproxy_settings_maximum_connections: 65535` (defaults/main.yml:12); ansible-core 2.18.8 would coerce it to
 * `'65535'` (the role itself still receives `65535`)".
 *
 * Values come only from non-secret definitions (the evaluator never keeps a secret's value).
 */
internal class TemplatedMessages(private val core: CoreVersion) {
    private val coreText = "ansible-core $core"

    fun message(
        documented: OptionType,
        elements: Boolean,
        path: List<String>,
        bindings: List<SpecBinding>,
        mismatch: TemplatedMismatch,
        reachable: Boolean,
    ): String {
        val type = message(if (elements) "t020.documented.elements" else "t020.documented.type", documented.name)
        val head = message("t020.documented", type) + owner(bindings) + at(path)
        val logical = message("t020.this.is", head, describe(mismatch.logical), origin(mismatch.origin))
        val outcome = outcome(documented, mismatch)
        val core = if (sameTypes(mismatch.logical, mismatch.runtime)) {
            message("t020.core.outcome", coreText, outcome)
        } else {
            message("t020.core.renders", coreText, typeList(mismatch.runtime.types), outcome)
        }
        val stale = if (reachable) "" else unreachable(bindings)
        return "$logical; $core$stale"
    }

    private fun owner(bindings: List<SpecBinding>): String {
        val roles = bindings.map { it.role.name }.distinct()
        if (roles.size == 1) return message("t020.owner.role", roles.single(), bindings.first().entryPoint)
        return message("t020.owner.roles", nameList(roles))
    }

    private fun at(path: List<String>): String = if (path.size <= 1) "" else message("t020.at", pathText(path))

    private fun nameList(names: List<String>): String {
        val quoted = names.map { "`$it`" }
        return if (quoted.size <= MAX_NAMES) quoted.joinToString(", ") else message("t020.owner.more", quoted.take(MAX_NAMES).joinToString(", "), quoted.size - MAX_NAMES)
    }

    /** `int `65535``, or the type names when the exact value is unknown. */
    private fun describe(value: AValue): String {
        val known = value.known
        return if (known != null) message("t020.value", BaseType.of(known).pyName, PyRepr.display(known)) else typeList(value.types)
    }

    private fun typeList(types: Set<BaseType>): String {
        val names = types.filter { it != BaseType.OTHER }.map { it.pyName } + if (BaseType.OTHER in types) listOf(message("t020.type.other")) else emptyList()
        return when (names.size) {
            0 -> message("t020.type.other")
            1 -> names.single()
            else -> message("t020.types.or", names.dropLast(1).joinToString(", "), names.last())
        }
    }

    private fun sameTypes(logical: AValue, runtime: AValue): Boolean = logical.types == runtime.types

    private fun origin(origin: TypeOrigin): String = when (origin) {
        is TypeOrigin.Chain -> message("t020.origin.chain", chain(origin.chain, origin.accessors))
        is TypeOrigin.Filter -> message("t020.origin.filter", origin.name)
        TypeOrigin.MultiNode -> message("t020.origin.multinode")
        TypeOrigin.Concat -> message("t020.origin.concat")
        TypeOrigin.Literal -> message("t020.origin.literal")
        TypeOrigin.Conditional -> message("t020.origin.conditional")
        TypeOrigin.Test -> message("t020.origin.test")
        TypeOrigin.Other -> ""
    }

    /** `` `a` (defaults/main.yml:3) → `b: 5` (defaults/main.yml:4) `` or `` `x` (3 definitions: …) ``. */
    private fun chain(trace: ChainTrace, accessors: List<String> = emptyList()): String {
        val name = trace.name + accessors.joinToString("") { accessorText(it) }
        val definitions = trace.definitions
        if (definitions.size != 1) {
            val labels = definitions.take(MAX_NAMES).joinToString(", ") { it.label } + if (definitions.size > MAX_NAMES) message("t020.chain.more") else ""
            return message("t020.chain.definitions", name, definitions.size, labels)
        }
        val definition = definitions.single()
        val next = definition.next
        val head = if (definition.shown != null && next == null && accessors.isEmpty()) {
            message("t020.chain.value", name, definition.shown!!, definition.label)
        } else {
            message("t020.chain.definition", name, definition.label)
        }
        return if (next != null) message("t020.chain.next", head, chain(next)) else head
    }

    /** What `check_type_<documented>` does, as "would …". */
    private fun outcome(documented: OptionType, mismatch: TemplatedMismatch): String = when (val outcome = mismatch.outcome) {
        is RuntimeOutcome.Coerced ->
            message("t020.outcome.coerced", PyRepr.display(outcome.to)) + message("t020.kept.value", PyRepr.display(outcome.from))
        is RuntimeOutcome.Rejected -> message("t020.outcome.rejected", outcome.message)
        is RuntimeOutcome.Crashed -> message("t020.outcome.crashed", outcome.exceptionClass, outcome.message)
        is RuntimeOutcome.ByType -> when (outcome.effect) {
            TypeEffect.REJECT -> message("t020.outcome.reject")
            TypeEffect.COERCE -> message("t020.outcome.coerce", documented.name) + message("t020.kept")
            TypeEffect.CONVERT_OR_REJECT -> message("t020.outcome.convert", documented.name)
        }
    }

    private fun unreachable(bindings: List<SpecBinding>): String {
        val roles = bindings.map { it.role.name }.distinct()
        return if (roles.size == 1) message("t020.unreachable.role", roles.single()) else message("t020.unreachable.roles", nameList(roles))
    }

    companion object {
        private const val MAX_NAMES = 3
        private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

        /** `haproxy_servers[0].port`. */
        fun pathText(path: List<String>): String = buildString {
            path.forEachIndexed { index, segment ->
                when {
                    index == 0 -> append(segment)
                    segment.isNotEmpty() && segment.all { it in '0'..'9' } -> append('[').append(segment).append(']')
                    else -> append('.').append(segment)
                }
            }
        }

        /** `.port` for a name, `['a b']` or `[0]` otherwise (accessors arrive as names or Python reprs). */
        private fun accessorText(accessor: String): String = if (IDENTIFIER.matches(accessor)) ".$accessor" else "[$accessor]"
    }
}
