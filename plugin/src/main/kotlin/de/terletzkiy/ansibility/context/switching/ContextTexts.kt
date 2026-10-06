package de.terletzkiy.ansibility.context.switching

import com.intellij.openapi.util.NlsSafe
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.api.HostScope
import de.terletzkiy.ansibility.api.HostScopeOrigin
import de.terletzkiy.ansibility.api.PlayRef
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.context.AnsibilityCoreBundle
import de.terletzkiy.ansibility.context.InventoryShape
import de.terletzkiy.ansibility.context.TargetVersion
import de.terletzkiy.ansibility.context.host.AnsibilityHostBundle
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext
import de.terletzkiy.ansibility.settings.RootKeys
import org.jetbrains.annotations.Nls

/**
 * The wording of the Ansible context UI (plan amendment R7/R8, F8.1): the built-in status-bar text, selection labels,
 * the file-scope segment and its "applies to" line. Pure: everything comes in as arguments, so the texts are tested
 * without a status bar or popup.
 */
object ContextTexts {
    /** Separator between status-bar parts and between the parts of one line. */
    const val SEPARATOR: String = " · "

    /** Separator between env and host (`prod › prod-prod1`) and in root-qualified names. */
    const val CHAIN: String = " › "

    /** How many names a list shows before "+n more". */
    private const val MAX_NAMES = 3

    /**
     * The built-in status-bar text `Ansibility: <root> · <env> › <host> · core <v>` (the prefix is the core bundle's
     * `status.text`, so the widget names the product as the branding says). The default selection reads
     * `All envs` exactly as before host awareness; a role library without a selected scenario has no env part. A
     * selection with a part that no longer exists ([stale]) is shown as stored, marked with `⚠`.
     */
    @Nls
    fun statusText(
        root: AnsibleRoot,
        target: TargetVersion,
        selection: RootContext,
        stale: Boolean,
        inventory: InventoryShape = InventoryShape.of(root),
    ): String = statusParts(root, target, selection, stale, inventory).joinToString("") { it.text }

    /** [statusText] as its click targets: the root, the environment, the host and the core version, with separators. */
    fun statusParts(
        root: AnsibleRoot,
        target: TargetVersion,
        selection: RootContext,
        stale: Boolean,
        inventory: InventoryShape = InventoryShape.of(root),
    ): List<WidgetTexts.Part> {
        val name = if (root.kind == RootKind.ROLE_LIBRARY) {
            AnsibilityCoreBundle.message("status.root.library", root.displayName)
        } else {
            root.displayName
        }
        val parts = mutableListOf(WidgetTexts.Part(AnsibilityCoreBundle.message("status.text", name), WidgetTexts.PartKind.ROOT))
        if (inventory.hasInventory || selection.environment is EnvironmentChoice.Named) {
            val named = (selection.environment as? EnvironmentChoice.Named)?.name
            val host = selection.host?.takeIf { selection.environment is EnvironmentChoice.Named }
            parts += WidgetTexts.Part.separator(SEPARATOR)
            if (inventory.single) {
                parts += WidgetTexts.Part(host ?: AnsibilityCoreBundle.message("status.hosts.all"), WidgetTexts.PartKind.HOST)
            } else {
                parts += WidgetTexts.Part(named ?: AnsibilityCoreBundle.message("status.envs.all"), WidgetTexts.PartKind.ENVIRONMENT)
            }
            if (host != null && !inventory.single) {
                parts += WidgetTexts.Part.separator(CHAIN)
                parts += WidgetTexts.Part(host, WidgetTexts.PartKind.HOST)
            }
            if (stale) parts[parts.lastIndex] = parts.last().let { it.copy(text = message("status.selection.stale", it.text)) }
        }
        target.version?.let { version ->
            parts += WidgetTexts.Part.separator(SEPARATOR)
            val core = AnsibilityCoreBundle.message(if (target.guessed) "status.core.guessed" else "status.core", version.toString())
            parts += WidgetTexts.Part(core, WidgetTexts.PartKind.CORE)
        }
        return parts
    }

    /** `prod › prod-prod1`, `prod`, or [all] for the All selection. */
    @Nls
    fun environmentAndHost(selection: RootContext, @Nls all: String = message("selection.all.environments")): String {
        val environment = (selection.environment as? EnvironmentChoice.Named)?.name ?: return all
        val host = selection.host ?: return environment
        return "$environment$CHAIN$host"
    }

    /** `falcon · prod › prod-prod1`: the tool-window context button; just the root's name for a root without inventory. */
    @Nls
    fun buttonText(root: AnsibleRoot, selection: RootContext, inventory: InventoryShape = InventoryShape.of(root)): String {
        if (!inventory.hasInventory && selection.environment !is EnvironmentChoice.Named) return root.displayName
        if (inventory.single) return "${root.displayName}$SEPARATOR${selection.host ?: AnsibilityCoreBundle.message("status.hosts.all")}"
        return "${root.displayName}$SEPARATOR${environmentAndHost(selection, message("selection.all.environments.short"))}"
    }

    /** The X75 selection value `env prod · host prod-prod1 · play auto`. */
    @Nls
    fun selectionLine(selection: RootContext, @NlsSafe playLabel: String?): String {
        val environment = (selection.environment as? EnvironmentChoice.Named)?.name ?: message("selection.all")
        val host = selection.host ?: message("selection.all")
        val play = playLabel ?: selection.play ?: message("selection.auto")
        return message("selection.line", environment, host, play)
    }

    /** `playbook-setup-system.yml › KeepAliveD` (`#<index>` for an unnamed play), relative to [base]. */
    @NlsSafe
    fun playLabel(base: AnsibleRoot, play: PlayRef): String {
        val path = RootKeys.relativePath(base.dir, play.file) ?: play.file.name
        return "$path$CHAIN${play.name ?: "#${play.playIndex}"}"
    }

    /** `1 host`, `4 hosts`. */
    @Nls
    fun hostCount(count: Int): String = message("count.hosts", count)

    /** `prod-prod1, prod-prod2 +3 more`. */
    @NlsSafe
    fun names(names: List<String>): String {
        if (names.size <= MAX_NAMES) return names.joinToString(", ")
        return message("names.more", names.take(MAX_NAMES).joinToString(", "), names.size - MAX_NAMES)
    }

    /** The hosts of [keys] as users read them: bare names within one environment, `env › host` across several. */
    @NlsSafe
    fun hostNames(keys: List<HostKey>): String {
        val qualified = keys.map { it.environment }.distinct().size > 1
        return names(keys.map { if (qualified) "${it.environment}$CHAIN${it.host}" else it.host })
    }

    /**
     * What a file scope is named after in the `file: <scope> → n hosts` segment: the role, host, group, play or
     * molecule scenario; null for scopes that do not narrow the selection (no segment then).
     */
    @NlsSafe
    fun scopeName(origin: HostScopeOrigin): String? = when (origin) {
        is HostScopeOrigin.RoleReach -> origin.role
        is HostScopeOrigin.HostVars -> origin.host.host
        is HostScopeOrigin.GroupVars -> origin.group
        is HostScopeOrigin.InventoryEntry -> origin.host ?: origin.group ?: origin.environment
        is HostScopeOrigin.Play -> origin.play.name ?: "${origin.play.file.name}#${origin.play.playIndex}"
        is HostScopeOrigin.Molecule -> origin.scenarioDir.name
        HostScopeOrigin.Selection, HostScopeOrigin.RootWide -> null
    }

    /**
     * `file: postfix → 4 hosts` (`→ no hosts` for an empty scope), or null when the file does not narrow the selection.
     * It counts the hosts the file applies to before the selection is applied ([HostScope.fileHosts]), so it reads the
     * same whatever host is selected.
     */
    @Nls
    fun fileSegment(scope: HostScope): String? {
        val name = scopeName(scope.origin) ?: return null
        val hosts = scope.fileHosts.size
        return if (hosts == 0) message("segment.file.empty", name) else message("segment.file", name, hostCount(hosts))
    }

    /**
     * The "applies to" line of a file scope (status-bar tooltip, popup, X75):
     * `role postfix → 4 hosts in ops, prod, test via playbook-setup-system.yml › System`,
     * `host_vars of prod-prod1`, `group_vars/database → prod-alias1, prod-mlflow1, prod-platform1 +1 more`: the hosts the
     * file applies to before the selection is applied ([HostScope.fileHosts]).
     */
    @Nls
    fun appliesTo(scope: HostScope): String {
        val hosts = scope.fileHosts
        val environments = hosts.map { it.environment }.distinct()
        val count = hostCount(hosts.size)
        return when (val origin = scope.origin) {
            is HostScopeOrigin.RoleReach -> {
                val plays = names(origin.plays.map { playLabel(scope.root, it) })
                when {
                    hosts.isEmpty() -> message("applies.role.none", origin.role, scope.emptyReason.orEmpty())
                    origin.plays.isEmpty() -> message("applies.role.no.play", origin.role, count, names(environments))
                    else -> message("applies.role", origin.role, count, names(environments), plays)
                }
            }
            is HostScopeOrigin.HostVars -> message("applies.host.vars", origin.host.host)
            is HostScopeOrigin.GroupVars -> message("applies.group.vars", origin.group, hostNamesOrNone(hosts))
            is HostScopeOrigin.InventoryEntry -> when {
                origin.host != null -> message("applies.inventory.host", origin.host, origin.environment)
                origin.group != null -> message("applies.inventory.group", origin.group, origin.environment, hostNamesOrNone(hosts))
                else -> message("applies.inventory.file", origin.environment, count)
            }
            is HostScopeOrigin.Play -> message("applies.play", playLabel(scope.root, origin.play), hostNamesOrNone(hosts))
            is HostScopeOrigin.Molecule -> message("applies.molecule", origin.scenarioDir.name, hostNamesOrNone(hosts))
            HostScopeOrigin.RootWide -> message("applies.root.wide")
            HostScopeOrigin.Selection -> message("applies.selection")
        }
    }

    private fun hostNamesOrNone(hosts: List<HostKey>): String = if (hosts.isEmpty()) message("applies.no.hosts") else hostNames(hosts)

    @Nls
    internal fun message(key: String, vararg params: Any): String = AnsibilityHostBundle.message(key, *params)
}
