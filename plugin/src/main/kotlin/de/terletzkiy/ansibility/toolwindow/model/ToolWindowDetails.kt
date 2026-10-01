package de.terletzkiy.ansibility.toolwindow.model

import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.InventoryGroup
import de.terletzkiy.ansibility.api.InventoryHost
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.context.ContextPresentation
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
 * Pure functions of the snapshot; values of variables are never read or shown.
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

    fun group(env: EnvironmentView, group: InventoryGroup): NodeDetails {
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
        details.section(message("details.section.precedence"), precedenceNote(env.root))
        return details.build()
    }

    fun host(env: EnvironmentView, host: InventoryHost): NodeDetails {
        val details = DetailsBuilder(message("details.host.title", host.name), message("details.subtitle.environment", env.root.root.displayName, env.name))
        val address = host.ansibleHost
        details.section(
            message("details.section.address"),
            when {
                address == null -> DetailItem(host.name, message("details.note.address.none"), NavigationTarget.of(host.location))
                isTemplated(address) -> DetailItem(address, message("details.note.address.templated"), NavigationTarget.of(host.location))
                else -> DetailItem(address, target = NavigationTarget.of(host.location))
            },
        )
        details.section(message("details.section.groups"), host.groups.mapNotNull(env::group).map(::groupItem))
        details.section(message("details.section.files"), env.sourcesOf(host).map { sourceItem(env.root, it) })
        details.section(message("details.section.inline"), host.inlineVarKeys.map { DetailItem(it, target = NavigationTarget.of(host.location)) })
        details.section(message("details.section.precedence"), precedenceNote(env.root))
        return details.build()
    }

    fun source(root: RootSnapshot, env: EnvironmentView?, source: LayerSource): NodeDetails = when (source) {
        is LayerSource.File -> varFile(root, source)
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

    private fun varFile(root: RootSnapshot, source: LayerSource.File): NodeDetails {
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

    fun playbook(root: RootSnapshot, file: VirtualFile): NodeDetails {
        val details = DetailsBuilder(message("details.playbook.title", root.relativePath(file)), root.root.displayName)
        details.section(message("details.section.root"), DetailItem(message("details.item.path", file.presentableUrl), target = NavigationTarget(file)))
        return details.build()
    }

    fun play(root: RootSnapshot, node: PlayNode): NodeDetails {
        val play = node.play
        val details = DetailsBuilder(
            message("details.play.title", node.name),
            message("details.subtitle.environment", root.root.displayName, root.relativePath(play.ref.file)),
        )
        details.section(
            message("details.section.play"),
            play.ref.hostsPattern?.let { DetailItem(message("details.item.hosts.pattern", it)) },
            DetailItem(message("details.item.file", play.ref.file.presentableUrl), target = node.target),
        )
        return details.build()
    }

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
}
