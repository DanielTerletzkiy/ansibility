package de.terletzkiy.ansibility.vars.gutter

import com.intellij.openapi.project.Project
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.VarDefinition
import de.terletzkiy.ansibility.context.host.symbols.HostSymbols

/**
 * Which (environment, host) pairs a definition can apply to, so two definitions only compete where they meet:
 * `build` host_vars never override `prod` group_vars, and `group_vars/web` and `group_vars/db` meet only on hosts in
 * both. Definitions without environment, group and host (role defaults, play vars), and `all` without an environment,
 * apply everywhere. A definition whose environment has no graph falls back to comparing environment names.
 */
internal class HostReach(project: Project, root: AnsibleRoot) {
    private val environments = HostSymbols.environments(project, root).associateBy { it.name }

    fun overlaps(a: VarDefinition, b: VarDefinition): Boolean {
        val reachA = reach(a) ?: return true
        val reachB = reach(b) ?: return true
        return reachA.any { it in reachB }
    }

    /** `env\u0000host` keys, or null for everywhere. */
    private fun reach(definition: VarDefinition): Set<String>? {
        val group = definition.group
        val host = definition.host
        val environment = definition.environment
        if (group == null && host == null) return environment?.let { env -> hostsIn(env, ALL) ?: setOf(env + SEPARATOR + WILDCARD) }
        if (environment == null) {
            if (host == null && group == ALL) return null
            return environments.keys.flatMapTo(HashSet()) { env -> hostsIn(env, group, host).orEmpty() }
        }
        return hostsIn(environment, group, host) ?: setOf(environment + SEPARATOR + WILDCARD)
    }

    private fun hostsIn(environment: String, group: String?, host: String? = null): Set<String>? {
        val graph = environments[environment]?.graph ?: return null
        val hosts = if (host != null) listOf(host).filter { it in graph.hosts } else graph.hostsOf(group ?: ALL)
        return hosts.mapTo(HashSet()) { environment + SEPARATOR + it }
    }

    private companion object {
        const val ALL = "all"
        const val SEPARATOR = "\u0000"
        const val WILDCARD = "*"
    }
}
