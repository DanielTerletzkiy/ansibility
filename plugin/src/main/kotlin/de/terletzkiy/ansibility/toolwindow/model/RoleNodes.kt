package de.terletzkiy.ansibility.toolwindow.model

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.api.RoleRef
import de.terletzkiy.ansibility.api.RoleTestState
import de.terletzkiy.ansibility.api.RoleTests
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.api.WorkspaceScope
import de.terletzkiy.ansibility.api.WorkspaceScopeService
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.context.MoleculeView
import de.terletzkiy.ansibility.context.MoleculeVisibility
import de.terletzkiy.ansibility.model.drift.AnsibilityDriftBundle
import de.terletzkiy.ansibility.model.drift.DriftTexts
import de.terletzkiy.ansibility.model.role.RoleLayout
import de.terletzkiy.ansibility.toolwindow.AnsibilityToolWindowBundle.message

/** Which tab a tree shows (plan amendment R9, F9.2–F9.4): repos with their roles, roles with their copies, or environments with each repo's inventory. */
enum class TreeView(val segment: String) {
    REPOS("workspace"),
    ROLES("workspace-roles"),
    ENVIRONMENTS("workspace-environments"),
}

/**
 * How many plays of the role's root apply it, computed when the parent expands (the play graph is cached). Converge and
 * verify plays count only with "Show Molecule in navigation and search" on (plan amendment R20, D153; the tree starts in
 * no file).
 */
private fun appliedText(node: AnsibleTreeNode, root: RootSnapshot, role: RoleRef): String? {
    if (root.kind == RootKind.ROLE_LIBRARY) return null
    val project = node.project ?: return null
    val applying = PlayGraph.getInstance(project).playsApplying(root.root, role.name)
    val plays = MoleculeVisibility.playsInView(project, MoleculeView.of(project, null), applying).size
    return if (plays == 0) message("role.applied.none") else message("role.applied", plays)
}

/** The tab's root ([WorkspaceNode]), which holds the toolbar's toggles; null for nodes built outside one. */
internal val AnsibleTreeNode.workspace: WorkspaceNode?
    get() = generateSequence(this) { it.parent }.last() as? WorkspaceNode

/** `meta/argument_specs.yml`, else `tasks/main.yml`, else the role directory. */
private fun roleTarget(role: RoleRef): NavigationTarget {
    val file = RoleLayout.specFile(role.dir) ?: RoleLayout.taskFiles(role.dir).firstOrNull { it.nameWithoutExtension == "main" } ?: role.dir
    return NavigationTarget(file)
}

/** `Roles (35)` of one root (F9.2). */
class RolesNode(parent: AnsibleTreeNode, val root: RootSnapshot) : AnsibleTreeNode(parent, "roles-of-root") {
    override fun presentation() = NodePresentation(message("roles.name", root.ownRoles.size), icon = NodeIcon.FOLDER)

    override fun children(context: TreeContext): List<AnsibleTreeNode> =
        root.ownRoles.sortedBy { it.name }.map { RoleNode(this, root, it, appliedText(this, root, it), label = null) }
}

/**
 * One role directory: `haproxy  applied by 2 plays`. In the Roles tab the same node is a copy and names its root
 * ([label]). Its children are the role's files, listed lazily from the VFS.
 *
 * With a golden root (plan amendment R24, D179) the row carries its drift badge (`≈ molecule only · 1 file`; `…` in
 * the Roles tab while the drift is not known yet), and a copy that differs from golden gets a first child
 * "Differences from golden (n)" ([DifferencesNode]). The Repos tab shows the badge once the drift is known; only the
 * Roles tab starts computing it.
 */
class RoleNode(parent: AnsibleTreeNode, val root: RootSnapshot, val role: RoleRef, private val applied: String?, private val label: String?) :
    AnsibleTreeNode(parent, "role:${role.dir.path}"), RoleCopyData {
    override val copyDir: VirtualFile get() = role.dir

    override fun presentation(): NodePresentation {
        val path = root.relativePath(role.dir)
        val marker = project?.let { NodeMarker.of(RoleTests.getInstance(it).stateOf(role.dir)) }
        val tooltip = arrayListOf(role.dir.presentableUrl)
        var badge: String? = null
        if (snapshot?.golden?.isSet == true) {
            val drift = DriftLookup.drift(this, role.name)
            val copy = drift?.copyOf(role.dir)
            if (drift != null && copy != null) {
                val golden = DriftLookup.goldenName(this, drift)
                badge = DriftTexts.rowBadge(drift, copy, golden)
                tooltip += DriftTexts.tooltip(copy.tier, golden, drift.options.ignoreMolecule)
            } else if (drift == null && label != null) {
                badge = DriftTexts.pending()
            }
        }
        return NodePresentation(
            label ?: role.name, listOfNotNull(if (label != null) path else null, applied).joinToString(" · ").ifEmpty { null }, tooltip,
            NodeIcon.ROLE, marker = marker, badge = badge,
        )
    }

    override val target: NavigationTarget get() = roleTarget(role)
    override val childrenOnDemand: Boolean get() = true
    override val expandsWithAll: Boolean get() = false

    override fun children(context: TreeContext): List<AnsibleTreeNode> {
        val differences = DriftLookup.copy(this, role.name, role.dir)?.takeIf { it.tier.differs }?.let { DifferencesNode(this, role.name, role.dir) }
        return listOfNotNull(differences) + RoleFileNode.listing(this, role.dir, role.name, role.dir)
    }

    /** The drift details of this copy (D180) when a golden root is set. */
    override fun details(): NodeDetails? = DriftDetails.copy(this, root, role)
}

/**
 * A file or directory inside a role (F9.2 file browsing): directories first, skipped and ignored entries left out. With
 * a golden root, a file that differs from the golden copy takes the VCS status colour (changed: modified; only in this
 * copy: added), and a sensitive one says "differs (content not shown)" (plan amendment R24, D179/D181).
 */
class RoleFileNode(
    parent: AnsibleTreeNode,
    val file: VirtualFile,
    /** The role this file belongs to, and its directory (the copy). */
    val roleName: String,
    private val roleDir: VirtualFile,
) : AnsibleTreeNode(parent, "file:${file.name}"), RoleCopyData {
    override val copyDir: VirtualFile get() = roleDir

    /** The file's path inside the role ("tasks/main.yml"), for files only. */
    override val rolePath: String? get() = if (file.isDirectory) null else VfsUtilCore.getRelativePath(file, roleDir)

    override val existingFile: VirtualFile? get() = file.takeIf { !it.isDirectory && it.isValid }

    override fun presentation(): NodePresentation {
        var color: NodeColor? = null
        var extra: String? = null
        val path = rolePath
        if (path != null && snapshot?.golden?.isSet == true) {
            val paths = DriftLookup.copy(this, roleName, roleDir)?.paths
            if (paths != null) {
                color = when (path) {
                    in paths.changed -> NodeColor.MODIFIED
                    in paths.onlyHere -> NodeColor.ADDED
                    else -> null
                }
                if (color != null && path in paths.sensitive) extra = DriftTexts.contentNotShown()
            }
        }
        return NodePresentation(file.name, extra, listOf(file.presentableUrl), icon(), color = color)
    }

    /** A role's own directories by what Ansible loads from them (`tasks`, `defaults`, …); deeper ones a plain folder. */
    private fun icon(): NodeIcon = when {
        !file.isDirectory -> NodeIcon.FILE
        file.parent == roleDir -> RoleDirectoryIcons.of(file.name)
        else -> NodeIcon.FOLDER
    }

    override val target: NavigationTarget? get() = if (file.isDirectory) null else NavigationTarget(file)
    override val navigatesOnDoubleClick: Boolean get() = !file.isDirectory
    override val isLeaf: Boolean get() = !file.isDirectory
    override val childrenOnDemand: Boolean get() = file.isDirectory
    override val expandsWithAll: Boolean get() = false

    override fun children(context: TreeContext): List<AnsibleTreeNode> = if (file.isDirectory) listing(this, file, roleName, roleDir) else emptyList()

    /** With a golden root, how the file differs from golden's and each side's last change of it (plan amendment R24, D180). */
    override fun details(): NodeDetails? = DriftDetails.roleFile(this)

    companion object {
        private val SKIPPED_NAMES = setOf("__pycache__", ".DS_Store")

        /** The entries of [dir], a directory of the role [roleName] whose directory is [roleDir]. */
        fun listing(parent: AnsibleTreeNode, dir: VirtualFile, roleName: String, roleDir: VirtualFile): List<AnsibleTreeNode> {
            if (!dir.isValid) return emptyList()
            val types = FileTypeManager.getInstance()
            return dir.children.orEmpty()
                .filter { child ->
                    ProgressManager.checkCanceled()
                    child.isValid && child.name !in SKIPPED_NAMES && child.extension != "pyc" &&
                        !(child.isDirectory && child.name in AnsibleLayout.SKIPPED_DIRS) && !types.isFileIgnored(child)
                }
                .sortedWith(compareBy<VirtualFile> { !it.isDirectory }.thenBy { it.name.lowercase() })
                .map { RoleFileNode(parent, it, roleName, roleDir) }
        }
    }
}

/**
 * The Roles tab (F9.3): one row per role name with its copies, library roots first. The tab lists every copy; what the
 * row stands for in a Molecule test run, and so its marker, are the copies of the roots the scope picker covers
 * ([copiesIn]; plan amendment R19, D143), and the row says how many those are when they are fewer than all.
 */
class RoleNameNode(parent: AnsibleTreeNode, val name: String, val copies: List<Pair<RootSnapshot, RoleRef>>) : AnsibleTreeNode(parent, "role-name:$name") {
    /**
     * The tree renders on its background thread, which may work out a named scope's roots; on the EDT ("Copy Name")
     * a scope whose roots are not known yet leaves the count and marker to the next render.
     *
     * With a golden root (plan amendment R24, D179) the copies are summed up against it: `9 copies · 7 differ from
     * golden (molecule only)`, `identical everywhere`, `no golden copy`, or `…` while the drift is not known yet.
     */
    override fun presentation(): NodePresentation {
        val roots = copies.map { it.first.root.displayName }.distinct()
        val scope = project?.let { WorkspaceScopeService.getInstance(it).current() }
        val inScope = when {
            scope == null -> copies
            !scope.rootsKnown() && ApplicationManager.getApplication().isDispatchThread -> copies
            else -> copiesIn(scope)
        }
        val text = if (snapshot?.golden?.isSet == true) {
            val drift = DriftLookup.drift(this, name)
            val summary = drift?.let { DriftTexts.nameSummary(it, DriftLookup.goldenName(this, it)) } ?: DriftTexts.pending()
            AnsibilityDriftBundle.message("drift.name.copies", copies.size, summary)
        } else {
            message("role.copies", copies.size, ToolWindowTexts.joinCapped(roots, MAX_ROOTS))
        }
        val extra = if (inScope.size < copies.size) message("role.copies.scope", text, inScope.size) else text
        return NodePresentation(name, extra, icon = NodeIcon.ROLE, marker = marker(inScope))
    }

    /** Whether a golden root is set and the known drift of this name has a copy that differs from it (Drifted Only). */
    fun drifts(): Boolean = snapshot?.golden?.isSet == true && (DriftLookup.drift(this, name)?.differingCount ?: 0) > 0

    /** The copies with their tiers (D180) when a golden root is set. */
    override fun details(): NodeDetails? = DriftDetails.name(this)

    /**
     * The copies whose root [scope] covers ([RootSnapshot.isIn]): what a Molecule test run of this row runs (R19/D143,
     * superseding R17/D122's "every copy"). All roots: every copy; none when the scope holds no copy.
     */
    fun copiesIn(scope: WorkspaceScope): List<Pair<RootSnapshot, RoleRef>> = copies.filter { (root, _) -> root.isIn(scope) }

    /** The tests of [inScope] (what a test run of this row runs): running before failed before passed before never run. */
    private fun marker(inScope: List<Pair<RootSnapshot, RoleRef>>): NodeMarker? {
        val project = project ?: return null
        val states = inScope.map { (_, role) -> RoleTests.getInstance(project).stateOf(role.dir) }
        val state = listOf(RoleTestState.RUNNING, RoleTestState.FAILED, RoleTestState.PASSED, RoleTestState.NOT_RUN).firstOrNull { it in states } ?: return null
        return NodeMarker.of(state)
    }

    /**
     * The copies, in order; with Group by Variant ([WorkspaceNode.groupByVariant], plan amendment R24, X123) and a
     * known drift, the variant groups ([VariantGroupNode]) with the same copy rows inside.
     */
    override fun children(context: TreeContext): List<AnsibleTreeNode> {
        if (workspace?.groupByVariant == true && snapshot?.golden?.isSet == true) VariantGroupNode.groupsOf(this)?.let { return it }
        return copies.map { (root, role) -> copyRow(this, root, role) }
    }

    companion object {
        private const val MAX_ROOTS = 4

        /** The row of one copy of a role name below [parent] (the name, or one of its variant groups): the same node and key either way. */
        internal fun copyRow(parent: AnsibleTreeNode, root: RootSnapshot, role: RoleRef): RoleNode =
            RoleNode(parent, root, role, appliedText(parent, root, role), label = root.root.displayName)

        /**
         * Every role directory once, under the root it belongs to ([WorkspaceSnapshot.roleCopies]: the innermost root
         * that lists and contains it, the one the scoped Molecule runs use too); role libraries first.
         */
        fun all(parent: AnsibleTreeNode, snapshot: WorkspaceSnapshot): List<RoleNameNode> {
            val order = snapshot.librariesFirst.withIndex().associate { (index, root) -> root to index }
            val golden = snapshot.golden.root?.dir
            val byName = snapshot.roleCopies.groupBy { it.second.name }
            // The golden root's copy leads (plan amendment R24, D177), then role libraries, then the other roots.
            val copyOrder = compareBy<Pair<RootSnapshot, RoleRef>> { (root, _) -> root.dir != golden }.thenBy { (root, _) -> order.getValue(root) }
            return byName.keys.sorted().map { name -> RoleNameNode(parent, name, byName.getValue(name).sortedWith(copyOrder)) }
        }
    }
}

/** The Environments tab (F9.4): one row per environment name with each project root's inventory of that name. */
class EnvironmentNameNode(parent: AnsibleTreeNode, val name: String, val environments: List<EnvironmentView>) : AnsibleTreeNode(parent, "env-name:$name") {
    override fun presentation(): NodePresentation {
        val hosts = environments.sumOf { it.inventory.hosts.size }
        return NodePresentation(name, message("environment.name.extra", message("count.repos", environments.size), message("count.hosts", hosts)), icon = NodeIcon.ENVIRONMENT)
    }

    override fun children(context: TreeContext): List<AnsibleTreeNode> =
        environments.map { EnvironmentNode(this, it, label = it.root.root.displayName, segment = "env:${it.root.dir.path}") }

    companion object {
        fun all(parent: AnsibleTreeNode, snapshot: WorkspaceSnapshot): List<EnvironmentNameNode> {
            val byName = LinkedHashMap<String, MutableList<EnvironmentView>>()
            for (root in snapshot.roots) {
                if (root.kind != RootKind.PROJECT) continue
                for (env in root.environments) byName.getOrPut(env.name) { ArrayList() } += env
            }
            return byName.keys.sorted().map { EnvironmentNameNode(parent, it, byName.getValue(it)) }
        }
    }
}
