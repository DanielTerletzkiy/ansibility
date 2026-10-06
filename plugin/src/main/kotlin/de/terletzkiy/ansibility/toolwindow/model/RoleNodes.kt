package de.terletzkiy.ansibility.toolwindow.model

import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.api.RoleRef
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.model.role.RoleLayout
import de.terletzkiy.ansibility.toolwindow.AnsibilityToolWindowBundle.message

/** Which tab a tree shows (plan amendment R9, F9.2–F9.4): repos with their roles, roles with their copies, or environments with each repo's inventory. */
enum class TreeView(val segment: String) {
    REPOS("workspace"),
    ROLES("workspace-roles"),
    ENVIRONMENTS("workspace-environments"),
}

/** How many plays of the role's root apply it, computed when the parent expands (the play graph is cached). */
private fun appliedText(node: AnsibleTreeNode, root: RootSnapshot, role: RoleRef): String? {
    if (root.kind == RootKind.ROLE_LIBRARY) return null
    val project = node.project ?: return null
    val plays = PlayGraph.getInstance(project).playsApplying(root.root, role.name).size
    return if (plays == 0) message("role.applied.none") else message("role.applied", plays)
}

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
 */
class RoleNode(parent: AnsibleTreeNode, val root: RootSnapshot, val role: RoleRef, private val applied: String?, private val label: String?) :
    AnsibleTreeNode(parent, "role:${role.dir.path}") {
    override fun presentation(): NodePresentation {
        val path = root.relativePath(role.dir)
        return NodePresentation(label ?: role.name, listOfNotNull(if (label != null) path else null, applied).joinToString(" · ").ifEmpty { null }, listOf(role.dir.presentableUrl), NodeIcon.ROLE)
    }

    override val target: NavigationTarget get() = roleTarget(role)
    override val childrenOnDemand: Boolean get() = true
    override val expandsWithAll: Boolean get() = false

    override fun children(context: TreeContext): List<AnsibleTreeNode> = RoleFileNode.listing(this, role.dir)
}

/** A file or directory inside a role (F9.2 file browsing): directories first, skipped and ignored entries left out. */
class RoleFileNode(parent: AnsibleTreeNode, val file: VirtualFile) : AnsibleTreeNode(parent, "file:${file.name}") {
    override fun presentation() = NodePresentation(file.name, icon = if (file.isDirectory) NodeIcon.FOLDER else NodeIcon.FILE, tooltip = listOf(file.presentableUrl))

    override val target: NavigationTarget? get() = if (file.isDirectory) null else NavigationTarget(file)
    override val navigatesOnDoubleClick: Boolean get() = !file.isDirectory
    override val isLeaf: Boolean get() = !file.isDirectory
    override val childrenOnDemand: Boolean get() = file.isDirectory
    override val expandsWithAll: Boolean get() = false

    override fun children(context: TreeContext): List<AnsibleTreeNode> = if (file.isDirectory) listing(this, file) else emptyList()

    companion object {
        private val SKIPPED_NAMES = setOf("__pycache__", ".DS_Store")

        fun listing(parent: AnsibleTreeNode, dir: VirtualFile): List<AnsibleTreeNode> {
            if (!dir.isValid) return emptyList()
            val types = FileTypeManager.getInstance()
            return dir.children.orEmpty()
                .filter { child ->
                    ProgressManager.checkCanceled()
                    child.isValid && child.name !in SKIPPED_NAMES && child.extension != "pyc" &&
                        !(child.isDirectory && child.name in AnsibleLayout.SKIPPED_DIRS) && !types.isFileIgnored(child)
                }
                .sortedWith(compareBy<VirtualFile> { !it.isDirectory }.thenBy { it.name.lowercase() })
                .map { RoleFileNode(parent, it) }
        }
    }
}

/** The Roles tab (F9.3): one row per role name with its copies, library roots first. */
class RoleNameNode(parent: AnsibleTreeNode, val name: String, val copies: List<Pair<RootSnapshot, RoleRef>>) : AnsibleTreeNode(parent, "role-name:$name") {
    override fun presentation(): NodePresentation {
        val roots = copies.map { it.first.root.displayName }.distinct()
        return NodePresentation(name, message("role.copies", copies.size, ToolWindowTexts.joinCapped(roots, MAX_ROOTS)), icon = NodeIcon.ROLE)
    }

    override fun children(context: TreeContext): List<AnsibleTreeNode> =
        copies.map { (root, role) -> RoleNode(this, root, role, appliedText(this, root, role), label = root.root.displayName) }

    companion object {
        private const val MAX_ROOTS = 4

        /** Every role directory once, attributed to the innermost root that lists it; role libraries first. */
        fun all(parent: AnsibleTreeNode, snapshot: WorkspaceSnapshot): List<RoleNameNode> {
            val owner = LinkedHashMap<String, Pair<RootSnapshot, RoleRef>>()
            val ordered = snapshot.roots.sortedBy { if (it.kind == RootKind.ROLE_LIBRARY) 0 else 1 }
            for (root in ordered) for (role in root.roles) {
                val current = owner[role.dir.path]
                if (current == null || root.dir.path.length > current.first.dir.path.length) owner[role.dir.path] = root to role
            }
            val byName = owner.values.groupBy { it.second.name }
            return byName.keys.sorted().map { name ->
                RoleNameNode(parent, name, byName.getValue(name).sortedBy { (root, _) -> ordered.indexOf(root) })
            }
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
