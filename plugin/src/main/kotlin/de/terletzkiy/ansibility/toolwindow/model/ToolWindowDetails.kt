package de.terletzkiy.ansibility.toolwindow.model

import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.InventoryGroup
import de.terletzkiy.ansibility.api.InventoryHost
import de.terletzkiy.ansibility.api.PlayInfo
import de.terletzkiy.ansibility.api.PlayRef
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.context.ContextPresentation
import de.terletzkiy.ansibility.context.host.PlayMatch
import de.terletzkiy.ansibility.toolwindow.AnsibilityToolWindowBundle.message
import de.terletzkiy.ansibility.toolwindow.model.ToolWindowTexts.joinCapped

/**
 * The details pane content of every node kind (plan F6.2):
 * - a **group**: its parents and children, the order the environment's groups are applied (`sort_groups`), its
 *   sources in load order and the hosts it applies to (directly or through a child group);
 * - a **host**: `ansible_host` as written, its groups in apply order and its sources in load order;
 * - a **var file**: layer, level, environment, group or host, and the hosts it applies to (per environment for a
 *   playbook-level file);
 * - roots, environments, worktrees, playbooks and plays with their key facts.
 *
 * HA7 (plan amendment R7/R8 F8.7) adds what the host context computes, passed in by the nodes: a host's evaluated
 * address and shared-address names ([HostBadge]), a group's reach ([GroupReach]: the plays and roles that run on its
 * hosts), a var file's effect ([VarFileEffect]: "applies to n hosts · effective on k of n") and a play's matched hosts
 * per environment ([PlayMatch]).
 *
 * Pure functions of their arguments; values of variables are never read or shown.
 */
object ToolWindowDetails {
    fun root(root: RootSnapshot): NodeDetails {
        val details = DetailsBuilder(root.root.displayName, message("root.kind.${root.kind.name}"))
        val environments = when {
            root.kind == RootKind.NESTED_PLAYBOOK && root.inventories.isNotEmpty() ->
                message("details.item.environments.shared", root.parent?.displayName ?: root.root.parentDir?.name.orEmpty())
            root.environments.isEmpty() -> message("details.item.environments.none")
            else -> message("details.item.environments", root.environments.joinToString(", ") { it.name })
        }
        details.section(
            message("details.section.root"),
            DetailItem(message("details.item.path", root.dir.presentableUrl), target = NavigationTarget(root.dir)),
            DetailItem(message("details.item.target", ContextPresentation.targetText(root.target))),
            DetailItem(message("details.item.roles", root.roleCount)),
            DetailItem(environments),
            DetailItem(message("details.item.playbooks", root.playbooks.size)),
            root.cfgFile?.let { DetailItem(message("details.item.cfg", it.presentableUrl), target = NavigationTarget(it)) },
            if (root.kind == RootKind.NESTED_PLAYBOOK) DetailItem(message("details.note.nested", root.playbookVarsLabel(root.parent?.dir ?: root.dir))) else null,
        )
        details.section(message("details.section.precedence"), precedenceNote(root))
        return details.build()
    }

    fun environment(env: EnvironmentView): NodeDetails {
        val details = DetailsBuilder(message("details.environment.title", env.name), message("details.subtitle.environment", env.root.root.displayName, env.name))
        details.section(
            message("details.section.inventory"),
            DetailItem(message("details.item.hosts.file", env.hostsFileLabel), target = NavigationTarget(env.hostsFile)),
            DetailItem(
                message(
                    "details.item.counts",
                    message("count.groups", env.visibleGroups.size),
                    message("count.hosts", env.inventory.hosts.size),
                    message("count.files", env.inventory.varFiles.size),
                ),
            ),
        )
        details.section(message("details.section.apply.order"), applyOrder(env, null))
        details.section(message("details.section.precedence"), precedenceNote(env.root))
        return details.build()
    }

    fun group(env: EnvironmentView, group: InventoryGroup, reach: GroupReach? = null): NodeDetails {
        val details = DetailsBuilder(
            message("details.group.title", group.name),
            message("details.subtitle.group", env.root.root.displayName, env.name, group.depth, group.priority),
        )
        details.section(message("details.section.parents"), group.parents.mapNotNull(env::group).map(::groupItem))
        details.section(message("details.section.children"), env.childrenOf(group).map(::groupItem))
        details.section(message("details.section.apply.order"), applyOrder(env, group))
        details.section(message("details.section.files"), env.sourcesOf(group).map { sourceItem(env.root, it) })
        details.section(message("details.section.inline"), group.inlineVarKeys.map { DetailItem(it, target = NavigationTarget.of(group.location)) })
        details.section(
            message("details.section.hosts"),
            env.membersOf(group).map { host ->
                val via = env.viaGroups(group, host)
                val note = if (via.isEmpty()) message("details.note.direct") else message("details.note.via", via.joinToString(", "))
                hostItem(env, host, note)
            },
        )
        reach?.let { groupReach(details, env, it) }
        details.section(message("details.section.precedence"), precedenceNote(env.root))
        return details.build()
    }

    fun host(env: EnvironmentView, host: InventoryHost, badge: HostBadge = HostBadge.written(host)): NodeDetails {
        val details = DetailsBuilder(message("details.host.title", host.name), message("details.subtitle.environment", env.root.root.displayName, env.name))
        val address = host.ansibleHost
        val location = NavigationTarget.of(host.location)
        details.section(
            message("details.section.address"),
            when {
                badge.hidden -> DetailItem(badge.address.orEmpty(), message("details.note.address.hidden"), location)
                badge.address != null && badge.template != null -> DetailItem(badge.address, message("details.note.address.evaluated", badge.template), location)
                address == null -> DetailItem(host.name, message("details.note.address.none"), location)
                isTemplated(address) -> DetailItem(address, message("details.note.address.templated"), location)
                else -> DetailItem(address, target = location)
            },
            badge.shared?.let { DetailItem(it, joinCapped(badge.sharedWith, MAX_HOSTS_PER_ENV)) },
        )
        details.section(message("details.section.groups"), host.groups.mapNotNull(env::group).map(::groupItem))
        details.section(message("details.section.files"), env.sourcesOf(host).map { sourceItem(env.root, it) })
        details.section(message("details.section.inline"), host.inlineVarKeys.map { DetailItem(it, target = NavigationTarget.of(host.location)) })
        details.section(message("details.section.precedence"), precedenceNote(env.root))
        return details.build()
    }

    fun source(root: RootSnapshot, env: EnvironmentView?, source: LayerSource, effect: VarFileEffect? = null): NodeDetails = when (source) {
        is LayerSource.File -> varFile(root, source, effect)
        is LayerSource.Inline -> {
            val view = env ?: root.environments.firstOrNull { it.hostsFile == source.hostsFile }
            val details = DetailsBuilder(message("inline.name", source.hostsFile.name, source.group.name), LayerTexts.label(source))
            details.section(message("details.section.layer"), layerItems(source))
            details.section(message("details.section.inline"), source.group.inlineVarKeys.map { DetailItem(it, target = source.target) })
            view?.let { v -> details.section(message("details.section.hosts"), v.membersOf(source.group).map { hostItem(v, it, null) }) }
            details.build()
        }
        is LayerSource.HostInline -> {
            val view = env ?: root.environments.firstOrNull { it.hostsFile == source.hostsFile }
            val details = DetailsBuilder(message("inline.name", source.hostsFile.name, source.host.name), LayerTexts.label(source))
            details.section(message("details.section.layer"), layerItems(source))
            details.section(message("details.section.inline"), source.host.inlineVarKeys.map { DetailItem(it, target = source.target) })
            view?.let { v -> details.section(message("details.section.hosts"), hostItem(v, source.host, null)) }
            details.build()
        }
        is LayerSource.Connection -> {
            val settings = source.settings
            val details = DetailsBuilder(settings.file.name, LayerTexts.label(source))
            details.section(
                message("details.section.connection"),
                settings.remoteUser?.let { DetailItem(message("details.item.value", ConnectionSettings.REMOTE_USER, it), target = source.target) },
                settings.remotePort?.let { DetailItem(message("details.item.value", ConnectionSettings.REMOTE_PORT, it), target = source.target) },
            )
            details.section(message("details.section.layer"), layerItems(source))
            details.build()
        }
    }

    private fun varFile(root: RootSnapshot, source: LayerSource.File, effect: VarFileEffect?): NodeDetails {
        val varFile = source.varFile
        val details = DetailsBuilder(root.displayPath(varFile), LayerTexts.label(source))
        details.section(message("details.section.layer"), layerItems(source))
        details.section(
            message("details.section.scope"),
            DetailItem(varFile.environment?.let { message("details.item.environment", it) } ?: message("details.item.environment.every")),
            varFile.group?.let { DetailItem(message("details.item.group", it)) },
            varFile.host?.let { DetailItem(message("details.item.host", it)) },
            DetailItem(message("details.item.file", varFile.file.presentableUrl), target = source.target),
        )
        val environment = root.environment(varFile.environment)
        val applies = if (environment != null) {
            val hosts = environment.hostsFor(varFile)
            if (hosts.isEmpty()) listOf(orphanItem(environment, source)) else hosts.map { hostItem(environment, it, null) }
        } else {
            root.environments.map { env ->
                val hosts = env.hostsFor(varFile).map { it.name }
                DetailItem(env.name, if (hosts.isEmpty()) message("details.note.no.hosts") else joinCapped(hosts, MAX_HOSTS_PER_ENV), NavigationTarget(env.hostsFile))
            }
        }
        details.section(message("details.section.hosts"), applies)
        effect?.let { varFileEffect(details, root, it) }
        return details.build()
    }

    fun worktree(worktree: WorktreeSnapshot): NodeDetails {
        val details = DetailsBuilder(message("details.worktree.title", worktree.name), worktree.dir.presentableUrl)
        details.section(
            message("details.section.roots"),
            worktree.roots.map { DetailItem(it.displayName, message("root.kind.${it.kind.name}"), NavigationTarget(it.dir)) } +
                DetailItem(message("details.note.worktree")),
        )
        return details.build()
    }

    /** A playbook; with [plays] (HA7b) also each play with the hosts it matches per environment. */
    fun playbook(root: RootSnapshot, file: VirtualFile, plays: List<Pair<PlayInfo, PlayMatch?>> = emptyList()): NodeDetails {
        val details = DetailsBuilder(message("details.playbook.title", root.relativePath(file)), root.root.displayName)
        details.section(message("details.section.root"), DetailItem(message("details.item.path", file.presentableUrl), target = NavigationTarget(file)))
        details.section(message("details.section.plays"), plays.map { (play, match) ->
            val perEnvironment = match?.takeIf { root.environments.isNotEmpty() }?.let { m ->
                if (m.templated) {
                    message("play.matches.templated.short")
                } else {
                    root.environments.filter { m.hostsIn(it.name).isNotEmpty() }
                        .joinToString(" · ") { env -> message("details.play.matches.env", env.name, m.hostsIn(env.name).size) }
                        .ifEmpty { message("details.note.no.hosts") }
                }
            }
            val note = listOfNotNull(play.ref.hostsPattern?.let { message("play.extra", it) }, perEnvironment).joinToString(" · ")
            DetailItem(play.ref.name ?: message("play.unnamed", play.ref.playIndex + 1), note.ifEmpty { null }, NavigationTarget(play.location.file, play.location.offset))
        })
        return details.build()
    }

    fun play(root: RootSnapshot, node: PlayNode, match: PlayMatch? = null, selectedEnvironment: String? = null): NodeDetails {
        val play = node.play
        val details = DetailsBuilder(
            message("details.play.title", node.name),
            message("details.subtitle.environment", root.root.displayName, root.relativePath(play.ref.file)),
        )
        details.section(
            message("details.section.play"),
            play.ref.hostsPattern?.let { DetailItem(message("details.item.hosts.pattern", it)) },
            DetailItem(message("details.item.file", play.ref.file.presentableUrl), target = node.target),
            DetailItem(message("details.item.playbook.dir", SourceLabels.playbookDir(root, play.ref.playbookDir))),
        )
        if (match != null && root.environments.isNotEmpty()) {
            details.section(
                message("details.section.matches"),
                if (match.templated) {
                    listOf(DetailItem(message("play.matches.templated"), message("play.matches.templated.extra")))
                } else {
                    root.environments.map { env ->
                        val hosts = match.hostsIn(env.name)
                        val selected = env.name == selectedEnvironment
                        val note = if (hosts.isEmpty()) message("details.note.no.hosts") else joinCapped(hosts, MAX_HOSTS_PER_ENV)
                        DetailItem(env.name, if (selected) message("details.note.selected.environment", note) else note, NavigationTarget(env.hostsFile), emphasized = selected)
                    }
                },
            )
        }
        details.section(message("details.section.roles"), play.roles.map { entry ->
            DetailItem(entry.name, entry.requiredBy?.let { message("role.kind.dependency", it) }, NavigationTarget.of(entry.location) ?: entry.role?.dir?.let { NavigationTarget(it) })
        })
        return details.build()
    }

    // ------------------------------------------------------------------------------------------------ HA7b reach

    /** The group's reach: a summary line, the plays that run on its hosts (direct ones bold) and the roles they apply there. */
    private fun groupReach(details: DetailsBuilder, env: EnvironmentView, reach: GroupReach) {
        val summary = when {
            reach.members.isEmpty() -> message("details.reach.no.hosts", reach.group)
            reach.plays.isEmpty() -> message("details.reach.no.plays", reach.group)
            reach.direct.isEmpty() -> message("details.reach.indirect", reach.group, joinCapped(reach.reachedVia, MAX_PATTERNS))
            else -> message("details.reach.direct", reach.direct.size, reach.plays.size - reach.direct.size)
        }
        val plays = reach.plays.map { play ->
            val note = listOfNotNull(
                play.play.hostsPattern?.let { message("play.extra", it) },
                message("details.reach.hosts.of", play.hosts.size, reach.members.size),
                if (play.direct) message("details.reach.direct.note") else null,
            ).joinToString(" · ")
            DetailItem(playLabel(env.root, play.play), note, NavigationTarget.of(play.location), emphasized = play.direct)
        }
        details.section(message("details.section.reach"), listOf(DetailItem(summary)) + plays)
        details.section(message("details.section.reach.roles"), reach.roles.map { role ->
            val note = message("details.reach.hosts.of", role.hosts.size, reach.members.size) + " · " +
                joinCapped(role.plays.map { it.name ?: it.file.name }.distinct(), MAX_PATTERNS)
            DetailItem(role.name, note, role.dir?.let { NavigationTarget(it) })
        })
    }

    /** The var file's effect: "applies to n hosts · effective on k of n", then the keys no host lets win. */
    private fun varFileEffect(details: DetailsBuilder, root: RootSnapshot, effect: VarFileEffect) {
        val summary = DetailItem(
            message("details.effect.summary", effect.appliesTo.size, effect.effectiveOn.size),
            message("details.effect.keys", effect.keys, effect.ineffective.size),
        )
        val keys = effect.ineffective.take(MAX_INEFFECTIVE).map { key ->
            val hosts = joinCapped(key.shadowedOn.map { it.host }, MAX_HOSTS_PER_ENV)
            val note = when (key.winners.size) {
                0 -> message("details.effect.shadowed", hosts)
                1 -> message("details.effect.shadowed.by", hosts, key.winners.single().let { SourceLabels.of(root.dir, it.file, it.offset) })
                else -> message("details.effect.shadowed.by.several", hosts, key.winners.size)
            }
            DetailItem(key.name, note, NavigationTarget(key.location.file, key.location.offset))
        }
        val more = (effect.ineffective.size - MAX_INEFFECTIVE).takeIf { it > 0 }?.let { DetailItem(message("count.more", it)) }
        details.section(message("details.section.effect"), listOf(summary) + keys + listOfNotNull(more))
    }

    /** `playbook-setup-system.yml › System` (or the play's index when it has no name). */
    private fun playLabel(root: RootSnapshot, play: PlayRef): String =
        message("details.play.label", root.relativePath(play.file), play.name ?: message("play.unnamed", play.playIndex + 1))

    // ------------------------------------------------------------------------------------------------ items

    /** Every group of [env] in apply order; [selected] is emphasized. */
    private fun applyOrder(env: EnvironmentView, selected: InventoryGroup?): List<DetailItem> =
        env.applyOrder.mapIndexed { index, group ->
            val sort = message("details.note.sort", group.depth, group.priority)
            val isSelected = group.name == selected?.name
            DetailItem(
                message("details.item.order", index + 1, group.name),
                if (isSelected) message("details.note.sort.selected", sort) else sort,
                NavigationTarget.of(group.location),
                emphasized = isSelected,
            )
        }

    private fun groupItem(group: InventoryGroup): DetailItem =
        DetailItem(group.name, message("details.note.sort", group.depth, group.priority), NavigationTarget.of(group.location))

    private fun hostItem(env: EnvironmentView, host: InventoryHost, note: String?): DetailItem =
        DetailItem(host.name, listOfNotNull(host.ansibleHost, note).joinToString(" · ").ifEmpty { null }, NavigationTarget.of(host.location) ?: NavigationTarget(env.hostsFile))

    private fun sourceItem(root: RootSnapshot, source: LayerSource): DetailItem = when (source) {
        is LayerSource.File -> DetailItem(root.displayPath(source.varFile), LayerTexts.label(source), source.target)
        is LayerSource.Inline -> DetailItem(
            message("inline.name", source.hostsFile.name, joinCapped(source.group.inlineVarKeys, MAX_INLINE_KEYS)),
            LayerTexts.label(source),
            source.target,
        )
        is LayerSource.HostInline -> DetailItem(
            message("inline.name", source.hostsFile.name, joinCapped(source.host.inlineVarKeys, MAX_INLINE_KEYS)),
            LayerTexts.label(source),
            source.target,
        )
        is LayerSource.Connection -> DetailItem(message("connection.name", source.settings.text), LayerTexts.label(source), source.target)
    }

    private fun layerItems(source: LayerSource): List<DetailItem> =
        listOf(DetailItem(LayerTexts.label(source), LayerTexts.tooltip(source)), DetailItem(message("details.item.level", source.level)))

    private fun orphanItem(env: EnvironmentView, source: LayerSource.File): DetailItem {
        val varFile = source.varFile
        val key = if (varFile.host != null) "details.note.orphan.host" else "details.note.orphan.group"
        return DetailItem(message("details.note.no.hosts"), message(key, source.owner.orEmpty(), env.name), NavigationTarget(env.hostsFile))
    }

    private fun precedenceNote(root: RootSnapshot): DetailItem? =
        if (root.customPrecedence) DetailItem(message("details.note.precedence", root.precedence.joinToString(", ") { it.configName })) else null

    private fun isTemplated(value: String): Boolean = "{{" in value || "{%" in value

    private const val MAX_HOSTS_PER_ENV = 12
    private const val MAX_INLINE_KEYS = 4
    private const val MAX_PATTERNS = 6
    private const val MAX_INEFFECTIVE = 20
}
