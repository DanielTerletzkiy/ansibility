package de.terletzkiy.ansibility.toolwindow.model

import de.terletzkiy.ansibility.api.RoleTestState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.InventoryGroup
import de.terletzkiy.ansibility.api.InventoryHost
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.api.PlayInfo
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.api.ToolWindowNodeContributor
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.context.host.PlayMatch
import de.terletzkiy.ansibility.toolwindow.AnsibilityToolWindowBundle.message
import de.terletzkiy.ansibility.toolwindow.model.ToolWindowTexts.coreText
import de.terletzkiy.ansibility.toolwindow.model.ToolWindowTexts.joinCapped
import org.jetbrains.annotations.Nls

/** The icon of a node; the UI layer maps it to a Swing icon. */
enum class NodeIcon {
    WORKSPACE, PROJECT_ROOT, ROLE_LIBRARY, NESTED_ROOT, WORKTREE, FOLDER, ENVIRONMENT, GROUP, HOST,
    VAR_FILE, VAULT_FILE, INLINE_VARS, CONFIG, PLAYBOOK, PLAY,

    /** HA7: a variable of a host's Effective vars, a definition it shadows, a role a play applies, a runtime marker. */
    VARIABLE, SHADOWED, ROLE, RUNTIME,

    /** R9: a plain file of a role's listing. */
    FILE,

    /** R24: the "Differences from golden" folder of a role copy and its category groups (a yellow folder). */
    DRIFT_FOLDER,

    /** R24 follow-up: the standard directories of a role, by what Ansible loads from them ([RoleDirectoryIcons]). */
    ROLE_TASKS, ROLE_HANDLERS, ROLE_VARS, ROLE_META, ROLE_TEMPLATES, ROLE_FILES, ROLE_TESTS, ROLE_PLUGINS,
}

/** The icon of a directory directly inside a role ("tasks", "defaults", "molecule", "filter_plugins"): see [of]. */
object RoleDirectoryIcons {
    /**
     * [NodeIcon.ROLE_TASKS] for `tasks`, [NodeIcon.ROLE_HANDLERS] for `handlers`, [NodeIcon.ROLE_VARS] for `defaults`
     * and `vars`, [NodeIcon.ROLE_META] for `meta`, [NodeIcon.ROLE_TEMPLATES] for `templates`, [NodeIcon.ROLE_FILES]
     * for `files`, [NodeIcon.ROLE_TESTS] for `molecule` and `tests`, [NodeIcon.ROLE_PLUGINS] for `library`,
     * `module_utils` and every `*_plugins`; any other directory is a plain [NodeIcon.FOLDER].
     */
    fun of(name: String): NodeIcon = when {
        name == "tasks" -> NodeIcon.ROLE_TASKS
        name == "handlers" -> NodeIcon.ROLE_HANDLERS
        name == "defaults" || name == "vars" -> NodeIcon.ROLE_VARS
        name == "meta" -> NodeIcon.ROLE_META
        name == "templates" -> NodeIcon.ROLE_TEMPLATES
        name == "files" -> NodeIcon.ROLE_FILES
        name == "molecule" || name == "tests" -> NodeIcon.ROLE_TESTS
        name == "library" || name == "module_utils" || name.endsWith("_plugins") -> NodeIcon.ROLE_PLUGINS
        else -> NodeIcon.FOLDER
    }
}

/** A marker after a node's icon (plan amendment R16): a role's Molecule tests, running, or how their last run went. */
enum class NodeMarker {
    TESTS, TESTS_RUNNING, TESTS_PASSED, TESTS_FAILED;

    companion object {
        /** The marker of [state]; null for a role without tests. */
        fun of(state: RoleTestState): NodeMarker? = when (state) {
            RoleTestState.NONE -> null
            RoleTestState.NOT_RUN -> TESTS
            RoleTestState.RUNNING -> TESTS_RUNNING
            RoleTestState.PASSED -> TESTS_PASSED
            RoleTestState.FAILED -> TESTS_FAILED
        }
    }
}

/** How the name of a node is drawn: regular, bold (the selected environment of a play), struck through (a shadowed definition). */
enum class NodeStyle { NORMAL, EMPHASIZED, STRUCK }

/**
 * The colour of a node's name (plan amendment R24, D179): the VCS file-status colours a file has against the golden
 * copy (changed, only in this copy, only in the golden copy). The UI layer maps them to the colour scheme's
 * `FILESTATUS_*` keys.
 */
enum class NodeColor { MODIFIED, ADDED, DELETED }

/**
 * How a node renders: [name] in the regular colour or [color] (bold or struck through by [style]), an optional [badge]
 * after it in the regular colour (a host's address, a role's drift tier), [extra] in grey after that, [tooltip] lines
 * on hover.
 */
data class NodePresentation(
    @Nls val name: String,
    @Nls val extra: String? = null,
    val tooltip: List<String> = emptyList(),
    val icon: NodeIcon,
    val style: NodeStyle = NodeStyle.NORMAL,
    @Nls val badge: String? = null,
    val marker: NodeMarker? = null,
    val color: NodeColor? = null,
) {
    /** The row as one string (name, badge and extra separated by two spaces), for speed search and tests. */
    val text: String get() = listOfNotNull(name, badge?.takeIf { it.isNotEmpty() }, extra?.takeIf { it.isNotEmpty() }).joinToString("  ")
}

/**
 * What node children need beyond the snapshot. Plays are read from the play graph only when a playbook is expanded,
 * on the tree's background read-action thread.
 */
fun interface TreeContext {
    fun playsOf(playbook: VirtualFile): List<PlayInfo>

    companion object {
        val NONE: TreeContext = TreeContext { emptyList() }
    }
}

/**
 * What a node may do when it is activated (double-click, Enter or F4) instead of opening its target (plan amendment
 * R24): the tool window panel provides it.
 */
interface NodeActivation {
    /**
     * Runs the registered action [id] with the tree's data context (the selected node's data); false when no such
     * action is registered or it is disabled there.
     */
    fun runAction(id: String): Boolean

    /** Opens [target]; false when it is gone. */
    fun navigate(target: NavigationTarget): Boolean
}

/**
 * One node of the Ansible tree (plan F6.1): an immutable view over a [WorkspaceSnapshot].
 *
 * Equality is by class and [key], the node's path in the tree, never by content. After a refresh the tree reuses
 * the Swing node of an equal element and takes the new element's presentation, so expansion and selection survive
 * while the text is fresh.
 */
abstract class AnsibleTreeNode(val parent: AnsibleTreeNode?, segment: String) {
    val key: String = if (parent == null) segment else "${parent.childKeyBase}/$segment"

    /**
     * What the keys of this node's children start with: its own [key]. A grouping row that its children's keys ignore
     * (Group by Variant, plan amendment R24, X123) returns its parent's, so a copy row keeps the same key, and with it
     * its expansion and selection, whether it is grouped or not.
     */
    open val childKeyBase: String get() = key

    /**
     * The project the tree shows, found through the parent chain (the [WorkspaceNode] holds it); null for synthetic
     * snapshots in tests. Nodes that ask the Ansible services (HA7: effective vars, play matches, reach) use it.
     */
    open val project: Project? get() = parent?.project

    /** The snapshot the tree shows, found like [project]; null for nodes built outside a [WorkspaceNode]. */
    open val snapshot: WorkspaceSnapshot? get() = parent?.snapshot

    abstract fun presentation(): NodePresentation

    /**
     * Double-click, Enter and F4 ask this first (plan amendment R24): true when the node handled the activation itself
     * (a drift file row runs Compare with Golden), false for the default (open [target], or expand).
     */
    open fun activate(activation: NodeActivation): Boolean = false

    /** Where double-click, Enter or F4 jumps to (plan F6.3), or null for pure containers. */
    open val target: NavigationTarget? get() = null

    /** Whether double-click and Enter open [target]; otherwise they expand or collapse the node (F4 still opens it). */
    open val navigatesOnDoubleClick: Boolean get() = false

    /** True for nodes that never have children. */
    open val isLeaf: Boolean get() = false

    /**
     * True for nodes whose children are loaded only when the node is expanded, never to find out whether it has any
     * (host nodes: their contributed children compute per-host models).
     */
    open val childrenOnDemand: Boolean get() = false

    /**
     * False for nodes that the toolbar's Expand All leaves collapsed: their subtree is computed per host and large (a
     * host's Effective vars and Targeted by, a few hundred rows each, under every group the host is in). The node
     * itself still shows; expanding it by hand loads its children as usual.
     */
    open val expandsWithAll: Boolean get() = true

    abstract fun children(context: TreeContext): List<AnsibleTreeNode>

    /** The details pane content (plan F6.2), or null for containers. */
    open fun details(): NodeDetails? = null

    final override fun equals(other: Any?): Boolean = other is AnsibleTreeNode && other.javaClass == javaClass && other.key == key

    final override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = presentation().text
}

/**
 * The invisible root: one node per non-detached root, then one per detached worktree. In the Roles tab, [driftedOnly]
 * (the toolbar's Drifted Only, plan amendment R24, D179) keeps the role names whose known drift has a copy that
 * differs from the golden root, and [groupByVariant] (Group by Variant, X123) lists a name's copies in variant groups
 * ([VariantGroupNode]); without a golden root neither has an effect (the toggles are hidden then).
 */
class WorkspaceNode(
    override val snapshot: WorkspaceSnapshot,
    val view: TreeView = TreeView.REPOS,
    val driftedOnly: Boolean = false,
    val groupByVariant: Boolean = false,
) : AnsibleTreeNode(null, view.segment) {
    override val project: Project? get() = snapshot.project

    override fun presentation() = NodePresentation(message("toolwindow.title"), icon = NodeIcon.WORKSPACE)

    override fun children(context: TreeContext): List<AnsibleTreeNode> = when (view) {
        TreeView.REPOS -> snapshot.roots.map { RootNode(this, it) } + snapshot.worktrees.map { WorktreeNode(this, it) }
        TreeView.ROLES -> RoleNameNode.all(this, snapshot).let { names -> if (driftedOnly && snapshot.golden.isSet) names.filter { it.drifts() } else names }
        TreeView.ENVIRONMENTS -> EnvironmentNameNode.all(this, snapshot)
    }
}

/** A root: `falcon  core 2.18.8 (docker pin) · 35 roles · 4 envs`. */
class RootNode(parent: AnsibleTreeNode, val root: RootSnapshot) : AnsibleTreeNode(parent, "root:${root.dir.path}") {
    override fun presentation(): NodePresentation {
        val extra = when (root.kind) {
            RootKind.PROJECT -> message(
                "root.project.extra", coreText(root.target), message("count.roles", root.roleCount), message("count.envs", root.environments.size),
            )
            RootKind.NESTED_PLAYBOOK -> message("root.nested.extra", root.playbookVarsLabel(root.parent?.dir ?: root.root.parentDir ?: root.dir))
            RootKind.ROLE_LIBRARY -> message("root.library.extra", message("count.roles", root.roleCount), message("count.playbooks", root.playbooks.size))
        }
        val icon = when (root.kind) {
            RootKind.PROJECT -> NodeIcon.PROJECT_ROOT
            RootKind.NESTED_PLAYBOOK -> NodeIcon.NESTED_ROOT
            RootKind.ROLE_LIBRARY -> NodeIcon.ROLE_LIBRARY
        }
        return NodePresentation(root.root.displayName, extra, listOf(root.dir.presentableUrl, message("root.tooltip.target", coreText(root.target))), icon)
    }

    /** `ansible.cfg` of a project root, the directory of other roots. */
    override val target: NavigationTarget
        get() = NavigationTarget(root.cfgFile?.takeIf { root.kind == RootKind.PROJECT } ?: root.dir)

    override fun children(context: TreeContext): List<AnsibleTreeNode> = buildList {
        if (root.playbookVarFiles.isNotEmpty()) add(SharedVarsNode(this@RootNode, root))
        when (root.kind) {
            RootKind.PROJECT -> if (root.environments.isNotEmpty()) add(EnvironmentsNode(this@RootNode, root))
            RootKind.NESTED_PLAYBOOK -> if (root.inventories.isNotEmpty()) add(SharedEnvironmentsNode(this@RootNode, root))
            RootKind.ROLE_LIBRARY -> Unit
        }
        if (root.playbooks.isNotEmpty()) add(PlaybooksNode(this@RootNode, root))
        if (root.ownRoles.isNotEmpty()) add(RolesNode(this@RootNode, root))
    }.withContributions(this)

    override fun details(): NodeDetails = ToolWindowDetails.root(root)
}

/** X01: `Detached worktree: <name>`, a single leaf however many roots the worktree holds. */
class WorktreeNode(parent: AnsibleTreeNode, val worktree: WorktreeSnapshot) : AnsibleTreeNode(parent, "worktree:${worktree.dir.path}") {
    override fun presentation() = NodePresentation(
        message("worktree.name", worktree.name),
        message("worktree.extra", message("count.roots", worktree.roots.size)),
        listOf(worktree.dir.presentableUrl, message("worktree.tooltip")),
        NodeIcon.WORKTREE,
    )

    override val target: NavigationTarget get() = NavigationTarget(worktree.dir)
    override val navigatesOnDoubleClick: Boolean get() = true
    override val isLeaf: Boolean get() = true

    override fun children(context: TreeContext): List<AnsibleTreeNode> = emptyList()

    override fun details(): NodeDetails = ToolWindowDetails.worktree(worktree)
}

/** `<root>/group_vars` and `<root>/host_vars` (levels 5, 7, 10) in load order. */
class SharedVarsNode(parent: AnsibleTreeNode, val root: RootSnapshot) : AnsibleTreeNode(parent, "shared") {
    override fun presentation(): NodePresentation {
        val dirs = root.playbookVarFiles.map { if (it.host != null) AnsibleLayout.HOST_VARS else AnsibleLayout.GROUP_VARS }.distinct()
            .joinToString(", ") { "${root.dir.name}/$it" }
        return NodePresentation(
            message("shared.name"),
            message("shared.extra", dirs, message("count.files", root.playbookVarFiles.size)),
            listOf(message("shared.tooltip", root.dir.name)),
            NodeIcon.FOLDER,
        )
    }

    override fun children(context: TreeContext): List<AnsibleTreeNode> =
        root.playbookVarFiles.map { LayerSourceNode(this, root, null, LayerSource.File(it)) }
}

/** The environments of a project root. */
class EnvironmentsNode(parent: AnsibleTreeNode, val root: RootSnapshot) : AnsibleTreeNode(parent, "environments") {
    override fun presentation() = NodePresentation(message("environments.name"), root.environments.size.toString(), icon = NodeIcon.FOLDER)

    override fun children(context: TreeContext): List<AnsibleTreeNode> = root.environments.map { EnvironmentNode(this, it) }
}

/** A nested playbook root shares its parent's inventories; they are listed under the parent only. */
class SharedEnvironmentsNode(parent: AnsibleTreeNode, val root: RootSnapshot) : AnsibleTreeNode(parent, "environments-shared") {
    private val owner: String get() = root.parent?.displayName ?: root.root.parentDir?.name.orEmpty()

    override fun presentation() = NodePresentation(
        message("environments.name"),
        message("environments.shared.extra", owner, root.environments.joinToString(", ") { it.name }),
        listOf(message("environments.shared.tooltip", owner)),
        NodeIcon.FOLDER,
    )

    override val target: NavigationTarget?
        get() = root.root.environmentsDir?.let { NavigationTarget(it) }

    override val navigatesOnDoubleClick: Boolean get() = true
    override val isLeaf: Boolean get() = true

    override fun children(context: TreeContext): List<AnsibleTreeNode> = emptyList()
}

/** `prod  environments/prod/hosts.yml · 12 groups · 2 hosts`. */
class EnvironmentNode(
    parent: AnsibleTreeNode,
    val env: EnvironmentView,
    /** The row's name in the Environments tab (the root), instead of the environment name. */
    @Nls private val label: String? = null,
    segment: String = "env:${env.name}",
) : AnsibleTreeNode(parent, segment) {
    override fun presentation() = NodePresentation(
        label ?: env.name,
        message(
            "environment.extra", env.hostsFileLabel, message("count.groups", env.visibleGroups.size), message("count.hosts", env.inventory.hosts.size),
        ),
        listOf(env.hostsFile.presentableUrl, message("environment.tooltip", env.hostsFileLabel)),
        NodeIcon.ENVIRONMENT,
    )

    override val target: NavigationTarget get() = NavigationTarget(env.hostsFile)

    override fun children(context: TreeContext): List<AnsibleTreeNode> = buildList {
        add(GroupsNode(this@EnvironmentNode, env))
        if (env.inventory.hosts.isNotEmpty()) add(HostsNode(this@EnvironmentNode, env))
    }.withContributions(this)

    override fun details(): NodeDetails = ToolWindowDetails.environment(env)
}

/** The group tree of one environment: `all` first, then the top-level groups, children nested. */
class GroupsNode(parent: AnsibleTreeNode, val env: EnvironmentView) : AnsibleTreeNode(parent, "groups") {
    override fun presentation() = NodePresentation(
        message("groups.name"),
        message("groups.extra", env.visibleGroups.size),
        listOf(message("groups.tooltip")),
        NodeIcon.FOLDER,
    )

    override fun children(context: TreeContext): List<AnsibleTreeNode> {
        val all = env.all?.let { listOf(GroupNode(this, env, it, emptySet())) }.orEmpty()
        return all + env.topGroups.map { GroupNode(this, env, it, setOf(EnvironmentView.ALL)) }
    }
}

/** The hosts of one environment in inventory order. */
class HostsNode(parent: AnsibleTreeNode, val env: EnvironmentView) : AnsibleTreeNode(parent, "hosts") {
    override fun presentation() = NodePresentation(message("hosts.name"), env.inventory.hosts.size.toString(), icon = NodeIcon.FOLDER)

    override fun children(context: TreeContext): List<AnsibleTreeNode> = env.inventory.hosts.values.map { HostNode(this, env, it) }
}

/**
 * A group: `app_mono  inline (1): inventory_docs_client_structure · → prod-prod1, prod-prod2` (inline var keys counted and
 * named, var files with their level, members). Children: its sources in
 * load order, its child groups (not for `all`, whose children are the siblings under "Groups"), then the hosts
 * listed directly under it. [ancestors] cut cycles, which ansible-core rejects but a half-edited file may contain.
 */
class GroupNode(
    parent: AnsibleTreeNode,
    val env: EnvironmentView,
    val group: InventoryGroup,
    private val ancestors: Set<String>,
) : AnsibleTreeNode(parent, "group:${group.name}") {
    override fun presentation(): NodePresentation {
        val parts = ArrayList<String>()
        if (group.inlineVarKeys.isNotEmpty()) parts += message("group.inline", group.inlineVarKeys.size, joinCapped(group.inlineVarKeys, MAX_INLINE))
        for (files in listOf(env.inventoryFilesOf(group.name), env.playbookFilesOf(group.name))) {
            if (files.isNotEmpty()) parts += message("group.files", ToolWindowTexts.compactPaths(files.map(env.root::displayPath)), files.first().layer.level)
        }
        val members = env.membersOf(group)
        if (members.isNotEmpty()) parts += message("group.hosts", joinCapped(members.map { it.name }, MAX_HOSTS))
        if (group.location == null && group.name != EnvironmentView.ALL) parts += message("group.implicit")
        return NodePresentation(
            group.name,
            parts.joinToString(SEPARATOR),
            listOf(message("group.tooltip", group.name, env.name, group.depth, group.priority)),
            NodeIcon.GROUP,
        )
    }

    /** The group key in `hosts.yml` (the file itself for implicit groups). */
    override val target: NavigationTarget get() = NavigationTarget.of(group.location) ?: NavigationTarget(env.hostsFile)

    override val navigatesOnDoubleClick: Boolean get() = true

    override fun children(context: TreeContext): List<AnsibleTreeNode> {
        val sources = env.sourcesOf(group).map { LayerSourceNode(this, env.root, env, it) }
        val children = if (group.name == EnvironmentView.ALL) {
            emptyList()
        } else {
            env.childrenOf(group).filter { it.name !in ancestors && it.name != group.name }.map { GroupNode(this, env, it, ancestors + group.name) }
        }
        val hosts = group.hosts.mapNotNull(env::host).map { HostNode(this, env, it) }
        return (sources + children + hosts).withContributions(this)
    }

    /** The group's details with its reach (HA7b: the plays and roles that run on its hosts) when the services are there. */
    override fun details(): NodeDetails = ToolWindowDetails.group(env, group, project?.let { GroupReach.of(it, env, group) })
}

/**
 * A host: `prod-prod1  192.0.2.29  inline (2): ansible_host, ansible_user · groups: all, app_mono, …`. The address is a
 * badge in the regular colour (HA7a): a templated `ansible_host` is evaluated with the root's playbook dir and its
 * template follows in grey, a shared address says `1 of 7 names on …`, and an address the vault-safe preview rule
 * hides (written in a vault file, or templated through a `vault_*` name) reads `🔒 address hidden`. The inline var
 * keys of `hosts.yml` are counted and named when there is one besides `ansible_host`, which the address already shows.
 * Children are its sources in load order (the inline vars as level 8), then the contributed nodes (Effective vars,
 * Targeted by).
 */
class HostNode(parent: AnsibleTreeNode, val env: EnvironmentView, val host: InventoryHost) : AnsibleTreeNode(parent, "host:${host.name}") {
    override fun presentation(): NodePresentation {
        val badge = HostBadge.of(project, env, host)
        val inline = host.inlineVarKeys.takeIf { keys -> keys.any { it != ANSIBLE_HOST } }?.let { message("host.inline", it.size, joinCapped(it, MAX_INLINE)) }
        val extra = listOfNotNull(badge.template, badge.shared, inline, message("host.groups", joinCapped(host.groups, MAX_GROUPS))).joinToString(SEPARATOR)
        return NodePresentation(host.name, extra, listOf(message("host.tooltip", host.name, env.name)), NodeIcon.HOST, badge = badge.address)
    }

    /** The host entry in `hosts.yml` (`all.hosts` preferred). */
    override val target: NavigationTarget get() = NavigationTarget.of(host.location) ?: NavigationTarget(env.hostsFile)

    override val navigatesOnDoubleClick: Boolean get() = true

    /** Effective vars and Targeted by compute this host's models, so they load when the host is expanded. */
    override val childrenOnDemand: Boolean get() = true

    override fun children(context: TreeContext): List<AnsibleTreeNode> =
        env.sourcesOf(host).map { LayerSourceNode(this, env.root, env, it) }.withContributions(this)

    override fun details(): NodeDetails = ToolWindowDetails.host(env, host, HostBadge.of(project, env, host))
}

/**
 * One level source: `group_vars/keycloak/vars.yml  L6 env group_vars/keycloak — beats group_vars/all (L4, L5)`,
 * `hosts.yml: ansible_user, ansible_port  L3 …` (a group's inline vars; `L8 …` for a host's) or
 * `L1 ansible.cfg: remote_user=…, remote_port=…`. [env] is null
 * under "Shared (playbook-level) vars", where a file applies to every environment.
 */
class LayerSourceNode(
    parent: AnsibleTreeNode,
    val root: RootSnapshot,
    val env: EnvironmentView?,
    val source: LayerSource,
) : AnsibleTreeNode(parent, "src:${source.id}") {
    override fun presentation(): NodePresentation = when (source) {
        is LayerSource.File -> NodePresentation(
            root.displayPath(source.varFile),
            LayerTexts.label(source),
            listOf(LayerTexts.tooltip(source), source.varFile.file.presentableUrl),
            if (source.isVault) NodeIcon.VAULT_FILE else NodeIcon.VAR_FILE,
        )
        is LayerSource.Inline -> NodePresentation(
            message("inline.name", source.hostsFile.name, joinCapped(source.group.inlineVarKeys, MAX_INLINE_KEYS)),
            LayerTexts.label(source),
            listOf(LayerTexts.tooltip(source)),
            NodeIcon.INLINE_VARS,
        )
        is LayerSource.HostInline -> NodePresentation(
            message("inline.name", source.hostsFile.name, joinCapped(source.host.inlineVarKeys, MAX_INLINE_KEYS)),
            LayerTexts.label(source),
            listOf(LayerTexts.tooltip(source)),
            NodeIcon.INLINE_VARS,
        )
        is LayerSource.Connection -> NodePresentation(
            message("connection.name", source.settings.text),
            LayerTexts.label(source),
            listOf(LayerTexts.tooltip(source), source.settings.file.presentableUrl),
            NodeIcon.CONFIG,
        )
    }

    override val target: NavigationTarget get() = source.target
    override val navigatesOnDoubleClick: Boolean get() = true
    override val isLeaf: Boolean get() = true

    override fun children(context: TreeContext): List<AnsibleTreeNode> = emptyList()

    /** The source's details; a var file's also say what it achieves on its hosts (HA7b) when the services are there. */
    override fun details(): NodeDetails {
        val effect = (source as? LayerSource.File)?.let { file -> project?.let { VarFileEffect.of(it, root, file.varFile) } }
        return ToolWindowDetails.source(root, env, source, effect)
    }
}

/** The playbooks of a root; each lists its plays, and each play where it runs (HA7b). */
class PlaybooksNode(parent: AnsibleTreeNode, val root: RootSnapshot) : AnsibleTreeNode(parent, "playbooks") {
    override fun presentation() = NodePresentation(message("playbooks.name"), root.playbooks.size.toString(), icon = NodeIcon.FOLDER)

    override fun children(context: TreeContext): List<AnsibleTreeNode> = root.playbooks.map { PlaybookNode(this, root, it) }
}

/** One playbook; its plays are read when it is expanded. */
class PlaybookNode(parent: AnsibleTreeNode, val root: RootSnapshot, val file: VirtualFile) : AnsibleTreeNode(parent, "playbook:${file.path}") {
    override fun presentation() = NodePresentation(root.relativePath(file), tooltip = listOf(file.presentableUrl), icon = NodeIcon.PLAYBOOK)

    override val target: NavigationTarget get() = NavigationTarget(file)
    override val navigatesOnDoubleClick: Boolean get() = true

    override fun children(context: TreeContext): List<AnsibleTreeNode> = context.playsOf(file).map { PlayNode(this, root, it) }

    /** The playbook's details with its plays and where each runs (HA7b). */
    override fun details(): NodeDetails {
        val plays = project?.let { p -> PlayGraph.getInstance(p).playsOf(file).map { it to PlayMatches.of(p, root, it.ref) } }.orEmpty()
        return ToolWindowDetails.playbook(root, file, plays)
    }
}

/**
 * One play: its name, its `hosts:` pattern as written and how many hosts it matches (HA7b). Children: the matched hosts
 * per environment (the selected environment of the Ansible context in bold), the environments it matches nothing in,
 * then the roles it applies in execution order.
 */
class PlayNode(parent: AnsibleTreeNode, val root: RootSnapshot, val play: PlayInfo) : AnsibleTreeNode(parent, "play:${play.ref.playIndex}") {
    @get:Nls
    val name: String get() = play.ref.name?.takeIf { it.isNotBlank() } ?: message("play.unnamed", play.ref.playIndex + 1)

    /** Where the play runs in the root's environments, or null without the host context (synthetic snapshots). */
    val match: PlayMatch? get() = project?.let { PlayMatches.of(it, root, play.ref) }

    override fun presentation(): NodePresentation {
        val pattern = play.ref.hostsPattern?.let { message("play.extra", it) }
        val hosts = match?.takeIf { root.environments.isNotEmpty() }?.let(PlayMatches::countText)
        return NodePresentation(name, listOfNotNull(pattern, hosts).joinToString(SEPARATOR).ifEmpty { null }, icon = NodeIcon.PLAY)
    }

    override val target: NavigationTarget get() = NavigationTarget(play.location.file, play.location.offset)
    override val navigatesOnDoubleClick: Boolean get() = true

    override fun children(context: TreeContext): List<AnsibleTreeNode> = PlayMatches.children(this, root, play.ref, match)

    override fun details(): NodeDetails = ToolWindowDetails.play(root, this, match, project?.let { PlayMatches.selectedEnvironment(it, root) })
}

/**
 * [this] built-in children followed by the children other areas contribute to [parent]
 * ([ToolWindowNodeContributor.childrenOf], CT0 seam): root, environment, group and host nodes call it. A contributed
 * node equal to a built-in or an earlier one (the same class and key) would make the tree ambiguous, so it is dropped
 * and logged; a contributor that throws or returns a node of another parent contributes nothing (the contract logs it).
 */
internal fun List<AnsibleTreeNode>.withContributions(parent: AnsibleTreeNode): List<AnsibleTreeNode> {
    val contributed = ToolWindowNodeContributor.childrenOf(parent)
    if (contributed.isEmpty()) return this
    val seen = HashSet<AnsibleTreeNode>(this)
    val out = ArrayList<AnsibleTreeNode>(size + contributed.size)
    out += this
    for (node in contributed) {
        if (seen.add(node)) out += node else CONTRIBUTION_LOG.warn("Ansibility: dropped a contributed tool-window node with a duplicate key ${node.key}")
    }
    return out
}

private val CONTRIBUTION_LOG = logger<AnsibleTreeNode>()

private const val SEPARATOR = " · "
private const val MAX_INLINE = 3
private const val MAX_INLINE_KEYS = 4
private const val MAX_HOSTS = 3
private const val MAX_GROUPS = 4
private const val ANSIBLE_HOST = "ansible_host"
