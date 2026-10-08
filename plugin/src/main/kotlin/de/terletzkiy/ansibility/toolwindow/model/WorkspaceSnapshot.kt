package de.terletzkiy.ansibility.toolwindow.model

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.Inventory
import de.terletzkiy.ansibility.api.InventoryService
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.api.RoleRef
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.api.RoleTests
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.api.ScopeChoice
import de.terletzkiy.ansibility.api.VarFile
import de.terletzkiy.ansibility.api.WorkspaceScope
import de.terletzkiy.ansibility.context.AnsibleCfg
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.context.TargetVersion
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.semantics.precedence.PrecedenceEntry
import de.terletzkiy.ansibility.workspace.WorkspaceScopeImpl
import java.io.IOException

/**
 * `remote_user`/`remote_port` of an `ansible.cfg` `[defaults]` section: precedence level 1, connection settings that
 * are not variables. The tool window shows them on the `all` group (plan F6.1, `repos/platform/ansible/ansible.cfg:3-4`).
 */
data class ConnectionSettings(
    val file: VirtualFile,
    val remoteUser: String?,
    val remotePort: String?,
    /** Offset of the first of the two keys in [file]. */
    val offset: Int,
) {
    /** `remote_user=deploy, remote_port=2222`, leaving out the unset one. */
    val text: String
        get() = listOfNotNull(remoteUser?.let { "$REMOTE_USER=$it" }, remotePort?.let { "$REMOTE_PORT=$it" }).joinToString(", ")

    companion object {
        const val REMOTE_USER: String = "remote_user"
        const val REMOTE_PORT: String = "remote_port"
        private const val DEFAULTS = "defaults"
        private val SECTION = Regex("""^\s*\[([^]]+)]\s*$""")
        private val KEY = Regex("""^\s*(remote_user|remote_port)\s*[=:]""", RegexOption.IGNORE_CASE)

        /** The settings of the `ansible.cfg` [file] with content [text], or null when it sets neither key. */
        fun parse(file: VirtualFile, text: CharSequence): ConnectionSettings? {
            val cfg = AnsibleCfg.parse(text)
            val user = cfg.value(DEFAULTS, REMOTE_USER)?.takeIf { it.isNotEmpty() }
            val port = cfg.value(DEFAULTS, REMOTE_PORT)?.takeIf { it.isNotEmpty() }
            if (user == null && port == null) return null
            return ConnectionSettings(file, user, port, keyOffset(text))
        }

        /** The offset of the first `remote_user`/`remote_port` key inside `[defaults]`, or 0. */
        private fun keyOffset(text: CharSequence): Int {
            var section: String? = null
            var start = 0
            while (start <= text.length) {
                val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
                val line = text.substring(start, end)
                val header = SECTION.matchEntire(line)
                if (header != null) {
                    section = header.groupValues[1].trim()
                } else if (section == DEFAULTS) {
                    val key = KEY.find(line)
                    if (key != null) return start + key.groups[1]!!.range.first
                }
                start = end + 1
            }
            return 0
        }
    }
}

/**
 * One non-detached root as the tool window shows it: the root, its target version and every model fact the tree
 * needs, captured in one read action. Environments are derived lazily ([environments]); everything is immutable.
 */
class RootSnapshot(
    val root: AnsibleRoot,
    val target: TargetVersion,
    val roleCount: Int,
    /**
     * The root's inventories. For a NESTED_PLAYBOOK root these are its parent's (shared); the tree shows them under
     * the parent only.
     */
    val inventories: List<Inventory>,
    /** `<root>/group_vars` and `<root>/host_vars` in load order (levels 5, 7, 10). */
    val playbookVarFiles: List<VarFile>,
    /** The files of kind `PLAYBOOK` (molecule playbooks left out), sorted by path. */
    val playbooks: List<VirtualFile>,
    /** The `ansible.cfg` that applies: the root's own, or for a nested root its parent's; null for a role library. */
    val cfgFile: VirtualFile?,
    val connection: ConnectionSettings?,
    /** `VARIABLE_PRECEDENCE` from [cfgFile] (`precedence`), or ansible-core's default order. */
    val precedence: List<PrecedenceEntry>,
    /** The enclosing PROJECT root of a NESTED_PLAYBOOK root. */
    val parent: AnsibleRoot?,
    /** The roles the root resolves (`RoleRegistry.roles`), for the Roles node and tab (R9 F9.2, F9.3). */
    val roles: List<RoleRef> = emptyList(),
    /**
     * The directories of [roles] that have tests ([RoleTests.hasTests]: Molecule scenarios, with "Run Molecule tests"
     * on), so the Molecule run UI decides without reading the VFS (plan amendments R19, D140, and R20, D152).
     */
    val testedRoles: Set<VirtualFile> = emptySet(),
) {
    val dir: VirtualFile get() = root.dir

    val kind: RootKind get() = root.kind

    /**
     * The roles listed under this root in the Repos tab: a nested playbook root's own only, since its parent's are
     * listed there. Which root a role directory belongs to for the scope is [WorkspaceSnapshot.roleCopies].
     */
    val ownRoles: List<RoleRef> by lazy {
        if (kind == RootKind.NESTED_PLAYBOOK) roles.filter { contains(it.dir) } else roles
    }

    /** Whether [file] lies in this root's directory. */
    fun contains(file: VirtualFile): Boolean = VfsUtilCore.isAncestor(dir, file, false)

    /**
     * Whether the workspace [scope] covers this root, wholly or in part: the one rule by which the tool window's
     * scoped actions pick roots (plan amendment R19: the Molecule toolbar button, D140, and what a role-name row runs,
     * D143). All roots covers every root without asking; any other scope reads [WorkspaceScope.roots], which for a
     * named scope walks the roots' files on first use unless its coverage is known ([rootsKnown]): off the EDT then.
     */
    fun isIn(scope: WorkspaceScope): Boolean = scope.choice == ScopeChoice.AllRoots || scope.roots.any { it.dir == dir }

    val environments: List<EnvironmentView> by lazy { inventories.map { EnvironmentView(this, it) } }

    fun environment(name: String?): EnvironmentView? = name?.let { n -> environments.firstOrNull { it.name == n } }

    /** True when `ansible.cfg` reorders levels 3–7. */
    val customPrecedence: Boolean get() = precedence != PrecedenceEntry.DEFAULT

    /**
     * How a var file is written in the tree: an environment's file relative to its environment directory
     * (`group_vars/keycloak/vars.yml`), a playbook-level file relative to the root's parent
     * (`ansible/group_vars/all/vars.yml`), which keeps the two apart at a glance.
     */
    fun displayPath(varFile: VarFile): String {
        val base = environment(varFile.environment)?.inventory?.hostsFile?.parent ?: root.dir.parent ?: root.dir
        return VfsUtilCore.getRelativePath(varFile.file, base) ?: varFile.file.name
    }

    /** [file] relative to the root directory (its name when outside). */
    fun relativePath(file: VirtualFile): String = VfsUtilCore.getRelativePath(file, root.dir) ?: file.name

    /** The directory name playbook-level vars live in, e.g. `ansible/group_vars` (plan F6.1 wording). */
    fun playbookVarsLabel(dir: VirtualFile = root.dir): String = "${dir.name}/${AnsibleLayout.GROUP_VARS}"
}

/**
 * Whether this scope's [WorkspaceScope.roots] are known without a walk over the roots' files: always, except for a
 * named scope whose coverage the workspace scope service has not computed yet. Code on the EDT asks before
 * [RootSnapshot.isIn] (plan amendment R19, D143).
 */
fun WorkspaceScope.rootsKnown(): Boolean = this !is WorkspaceScopeImpl || coverageIfComputed() != null

/** A detached worktree (X01): shown as a single node, never expanded into roots. */
class WorktreeSnapshot(val name: String, val dir: VirtualFile, val roots: List<AnsibleRoot>)

/**
 * Everything the tool window shows, captured in one read action; immutable. [project] is the project it was built for
 * (null for synthetic snapshots): nodes reach it through [AnsibleTreeNode.project] to ask the Ansible services.
 */
class WorkspaceSnapshot(val roots: List<RootSnapshot>, val worktrees: List<WorktreeSnapshot>, val project: Project? = null) {
    val isEmpty: Boolean get() = roots.isEmpty() && worktrees.isEmpty()

    fun root(dir: VirtualFile): RootSnapshot? = roots.firstOrNull { it.dir == dir }

    /** [roots] with the role libraries first: the order of a role name's copies in the Roles tab. */
    val librariesFirst: List<RootSnapshot> by lazy { roots.sortedBy { if (it.kind == RootKind.ROLE_LIBRARY) 0 else 1 } }

    /**
     * Every role directory the roots list, once, with the root it belongs to (plan amendment R19, D143): the innermost
     * root that lists it and contains it. A nested playbook root's roles path may also reach its parent's `roles/`,
     * and a project's `roles_path` a role library's: those copies stay the parent's or the library's. A directory
     * outside every root that lists it (a `roles_path` beyond the roots) goes to the first of them, role libraries
     * first. The Roles tab names a copy by this root, and the scoped Molecule runs (a role-name row, the toolbar
     * with nothing selected) take a copy when this root is in the scope, so the row and the button agree.
     */
    val roleCopies: List<Pair<RootSnapshot, RoleRef>> by lazy {
        val owners = LinkedHashMap<VirtualFile, Pair<RootSnapshot, RoleRef>>()
        for (root in librariesFirst) for (role in root.roles) {
            val current = owners[role.dir]?.first
            val better = current == null ||
                root.contains(role.dir) && (!current.contains(role.dir) || root.dir.path.length > current.dir.path.length)
            if (better) owners[role.dir] = root to role
        }
        owners.values.toList()
    }

    private val rolesByOwner: Map<RootSnapshot, List<RoleRef>> by lazy { roleCopies.groupBy({ it.first }, { it.second }) }

    /** The roles that belong to [root] ([roleCopies]): what a Molecule run of the scope takes from it. */
    fun ownedRoles(root: RootSnapshot): List<RoleRef> = rolesByOwner[root].orEmpty()

    /** Whether a role that belongs to [root] ([ownedRoles]) has tests ([RootSnapshot.testedRoles]). */
    fun hasTestedRoles(root: RootSnapshot): Boolean = ownedRoles(root).any { it.dir in root.testedRoles }

    companion object {
        val EMPTY: WorkspaceSnapshot = WorkspaceSnapshot(emptyList(), emptyList())
    }
}

/**
 * Builds the [WorkspaceSnapshot] from the shared services: [AnsibleWorkspace], [InventoryService], [RoleRegistry]
 * (role counts), [RoleTests] (which roles have tests), [PlayGraph] (playbook names), [TargetVersionDetector] and the
 * roots' `ansible.cfg`.
 *
 * Runs inside a read action on a background thread; every service is cached, so a rebuild after a change costs
 * little more than the changed inventory. Loops check for cancellation.
 */
object WorkspaceSnapshotBuilder {
    private val LOG = logger<WorkspaceSnapshotBuilder>()

    fun build(project: Project): WorkspaceSnapshot {
        if (project.isDisposed) return WorkspaceSnapshot.EMPTY
        val workspace = AnsibleWorkspace.getInstance(project)
        val all = workspace.roots()
        // A nested root also lists its parent's roles: ask about each role directory once.
        val tests = RoleTests.getInstance(project)
        val tested = HashMap<VirtualFile, Boolean>()
        val hasTests = { dir: VirtualFile -> tested.getOrPut(dir) { tests.hasTests(dir) } }
        val roots = displayOrder(all.filter { !it.detached }).map { root ->
            ProgressManager.checkCanceled()
            rootSnapshot(project, workspace, root, all, hasTests)
        }
        return WorkspaceSnapshot(roots, worktrees(project, all.filter { it.detached }), project)
    }

    /**
     * Project and nested playbook roots by path (a nested root right after its parent), then role libraries. Paths
     * compare segment by segment, so `ansible/danger_zone` stays next to `ansible` and before `ansible-x`.
     */
    fun displayOrder(roots: List<AnsibleRoot>): List<AnsibleRoot> {
        val bySegments = Comparator<AnsibleRoot> { a, b -> compareSegments(a.dir.path.split('/'), b.dir.path.split('/')) }
        val (libraries, others) = roots.partition { it.kind == RootKind.ROLE_LIBRARY }
        return others.sortedWith(bySegments) + libraries.sortedWith(bySegments)
    }

    private fun compareSegments(a: List<String>, b: List<String>): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val c = a[i].compareTo(b[i])
            if (c != 0) return c
        }
        return a.size.compareTo(b.size)
    }

    private fun rootSnapshot(
        project: Project,
        workspace: AnsibleWorkspace,
        root: AnsibleRoot,
        all: List<AnsibleRoot>,
        hasTests: (VirtualFile) -> Boolean,
    ): RootSnapshot {
        val cfgFile = cfgFileOf(root)
        val cfgText = cfgFile?.let(::read)
        val precedence = cfgText?.let { AnsibleCfg.parse(it).value("defaults", "precedence") }
            ?.let { PrecedenceEntry.parse(it).entries }
            ?: PrecedenceEntry.DEFAULT
        val playbooks = PlayGraph.getInstance(project).playbooks(root).filter { playbook ->
            ProgressManager.checkCanceled()
            workspace.contextOf(playbook)?.kind == FileKind.PLAYBOOK
        }
        val inventories = InventoryService.getInstance(project)
        val roles = RoleRegistry.getInstance(project).roles(root)
        return RootSnapshot(
            root = root,
            target = TargetVersionDetector.getInstance(project).targetVersion(root),
            roleCount = roles.size,
            inventories = inventories.inventories(root),
            playbookVarFiles = inventories.playbookVarFiles(root),
            playbooks = playbooks,
            cfgFile = cfgFile,
            connection = cfgText?.let { ConnectionSettings.parse(cfgFile, it) },
            precedence = precedence,
            parent = root.parentDir?.let { dir -> all.firstOrNull { it.dir == dir && !it.detached } },
            roles = roles,
            testedRoles = roles.filter { role ->
                ProgressManager.checkCanceled()
                hasTests(role.dir)
            }.mapTo(HashSet()) { it.dir },
        )
    }

    /** One entry per detached worktree; roots whose worktree is unknown count as their own worktree. */
    private fun worktrees(project: Project, detached: List<AnsibleRoot>): List<WorktreeSnapshot> {
        if (detached.isEmpty()) return emptyList()
        val impl = AnsibleWorkspaceImpl.getInstance(project)
        val byWorktree = LinkedHashMap<VirtualFile, MutableList<AnsibleRoot>>()
        val names = HashMap<VirtualFile, String>()
        for (root in detached.sortedBy { it.dir.path }) {
            val worktree = impl?.worktreeOf(root)
            val dir = worktree?.dir ?: root.dir
            names.putIfAbsent(dir, worktree?.name ?: root.displayName)
            byWorktree.getOrPut(dir) { ArrayList() } += root
        }
        return byWorktree.map { (dir, roots) -> WorktreeSnapshot(names.getValue(dir), dir, roots) }.sortedBy { it.dir.path }
    }

    private fun cfgFileOf(root: AnsibleRoot): VirtualFile? {
        val dir = when (root.kind) {
            RootKind.PROJECT -> root.dir
            RootKind.NESTED_PLAYBOOK -> root.parentDir ?: return null
            RootKind.ROLE_LIBRARY -> return null
        }
        return dir.findChild(AnsibleLayout.ANSIBLE_CFG)?.takeIf { it.isValid && !it.isDirectory }
    }

    private fun read(file: VirtualFile): String? = try {
        VfsUtilCore.loadText(file)
    } catch (e: IOException) {
        LOG.debug("Cannot read ${file.path}", e)
        null
    }
}
