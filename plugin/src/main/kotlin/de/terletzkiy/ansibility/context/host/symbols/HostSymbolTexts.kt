package de.terletzkiy.ansibility.context.host.symbols

import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.HostConstruct
import de.terletzkiy.ansibility.api.HostPatternSite
import de.terletzkiy.ansibility.api.InventoryNameSite
import de.terletzkiy.ansibility.context.host.AnsibilityHostBundle.message
import org.jetbrains.annotations.Nls

/**
 * The hover of a host symbol (F8.8): a title and one line per environment, environments with the same outcome
 * joined (`build, ops, test: no hosts`). Pure over a [SymbolScope], so the wording is unit-tested.
 */
internal object HostSymbolTexts {
    class Hover(@Nls val title: String, val lines: List<String>)

    fun hover(site: AnsibleSite, scope: SymbolScope): Hover? = when (site) {
        is HostPatternSite -> if (site.construct == HostConstruct.DELEGATE_TO) delegate(site.pattern, scope) else pattern(site.pattern, scope)
        is InventoryNameSite -> when (site.construct) {
            HostConstruct.GROUP_NAMES -> groupNames(site.name, scope)
            HostConstruct.HOSTVARS -> hostvars(site.name, scope)
            else -> groups(site.name, scope)
        }
        else -> null
    }

    private fun pattern(pattern: String, scope: SymbolScope): Hover {
        val title = message("symbols.title.hosts", pattern)
        if (HostSymbolClassifier.isTemplated(pattern)) return Hover(title, listOf(message("symbols.templated")))
        var localhost = false
        val outcomes = scope.environments.map { env ->
            val result = HostSymbols.match(env, pattern)
            localhost = localhost || result.implicitLocalhost
            env.name to when {
                result.errors.isNotEmpty() -> message("symbols.pattern.error", result.errors.first())
                result.hosts.isNotEmpty() -> shortList(result.hosts)
                result.unmatched.any { it !in env.graph.groups && it !in env.graph.hosts } ->
                    message("symbols.no.hosts.undefined")
                else -> message("symbols.no.hosts")
            }
        }
        val lines = joined(outcomes).toMutableList()
        if (localhost) lines += message("symbols.localhost")
        if (scope.environments.isEmpty()) lines += message("symbols.no.inventory", scope.root.displayName)
        HostSymbols.names(pattern).mapNotNull { name -> scope.moleculeOnlyGroups[name]?.let { message("symbols.molecule.only", name, it) } }
            .forEach { lines += it }
        return Hover(title, lines)
    }

    private fun delegate(host: String, scope: SymbolScope): Hover {
        val title = message("symbols.title.delegate", host)
        val found = scope.environments.filter { host in it.graph.hosts }
        if (found.isEmpty()) {
            val line = if (host in HostSymbols.LOCALHOST_NAMES) message("symbols.localhost") else message("symbols.host.nowhere", host, scope.root.displayName)
            return Hover(title, listOf(line))
        }
        return Hover(title, found.map { env -> message("symbols.host.in", env.name, host, env.address(host)?.let { " ($it)" } ?: "") })
    }

    private fun groups(group: String, scope: SymbolScope): Hover {
        val title = message("symbols.title.groups", group)
        val outcomes = scope.environments.map { env ->
            env.name to if (group in env.graph.groups) "[" + shortList(env.graph.hostsOf(group)) + "]" else message("symbols.group.undefined")
        }
        val lines = joined(outcomes).toMutableList()
        scope.moleculeOnlyGroups[group]?.let { lines += message("symbols.molecule.only", group, it) }
        return Hover(title, lines)
    }

    private fun groupNames(group: String, scope: SymbolScope): Hover {
        val title = message("symbols.title.group.names", group)
        if (!scope.groupDefinedAnywhere(group)) {
            val molecule = scope.moleculeOnlyGroups[group]
            val line = if (molecule != null) message("symbols.molecule.only", group, molecule)
            else message("symbols.group.nowhere", group, scope.root.displayName)
            return Hover(title, listOf(line))
        }
        val members = scope.environments.mapNotNull { env -> env.graph.hostsOf(group).takeIf { it.isNotEmpty() }?.let { env.name to it } }
        val total = members.sumOf { it.second.size }
        val lines = listOf(message("symbols.true.on", total)) + members.map { (env, hosts) -> "$env: ${shortList(hosts)}" }
        return Hover(title, lines)
    }

    private fun hostvars(host: String, scope: SymbolScope): Hover {
        val title = message("symbols.title.hostvars", host)
        val found = scope.environments.filter { host in it.graph.hosts }
        if (found.isEmpty()) return Hover(title, listOf(message("symbols.hostvars.nowhere", host, scope.root.displayName)))
        return Hover(title, found.map { env -> message("symbols.host.in", env.name, host, env.address(host)?.let { " ($it)" } ?: "") })
    }

    /** One line per distinct outcome, its environments joined: `build, ops: no hosts`. */
    private fun joined(outcomes: List<Pair<String, String>>): List<String> =
        outcomes.groupBy({ it.second }, { it.first }).map { (outcome, envs) -> "${envs.joinToString(", ")}: $outcome" }

    private fun shortList(hosts: List<String>): String {
        val shown = hosts.take(MAX_HOSTS).joinToString(", ")
        return if (hosts.size > MAX_HOSTS) message("symbols.more", shown, hosts.size - MAX_HOSTS) else shown
    }

    private const val MAX_HOSTS = 8
}
