package de.terletzkiy.ansibility.model.role

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.RoleRef
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings

/**
 * One directory of a role, attributed to the root that owns it (plan amendment R9, F9.3).
 *
 * [ref] names the owning root ([RoleRef.rootDir] is [root]'s directory), even when the directory was found through
 * another root's roles path. [isReference] marks the drift reference of the role name: the copy in the first role
 * library (`golden`), never a project copy.
 */
data class RoleCopy(val root: AnsibleRoot, val ref: RoleRef, val isReference: Boolean) {
    /** The role name (the directory name). */
    val name: String get() = ref.name

    /** The role directory. */
    val dir: VirtualFile get() = ref.dir
}

/**
 * The role copies of every non-detached root at one moment, grouped by role name. Immutable.
 *
 * Copies of a name are ordered like the Roles tab lists them: role libraries first (the reference leads), then
 * project roots, then nested playbook roots, each group by path, segment by segment.
 */
class RoleCatalogSnapshot internal constructor(copies: List<RoleCopy>) {
    private val byName: Map<String, List<RoleCopy>> =
        copies.groupBy { it.name }.toSortedMap().mapValues { (_, list) -> list.sortedWith(COPY_ORDER) }
    private val byDir: Map<VirtualFile, RoleCopy> = copies.associateBy { it.dir }
    private val byPath: Map<String, RoleCopy> = copies.associateBy { it.dir.path }

    /** Every role name, sorted. */
    val names: List<String> = byName.keys.toList()

    /** The number of role copies (role directories). */
    val copyCount: Int = copies.size

    /** The number of names with more than one copy. */
    val sharedNameCount: Int = byName.values.count { it.size > 1 }

    /** The copies of [name] in display order, or an empty list. */
    fun copies(name: String): List<RoleCopy> = byName[name].orEmpty()

    /** The reference copy of [name] (in a role library), or null when no role library has it. */
    fun reference(name: String): RoleCopy? = copies(name).firstOrNull { it.isReference }

    /** The copy whose role directory is [dir]. */
    fun copyOf(dir: VirtualFile): RoleCopy? = byDir[dir]

    /** The copy whose role directory is [file] or one of its ancestors. VFS-only. */
    fun copyContaining(file: VirtualFile): RoleCopy? {
        var current: VirtualFile? = file
        while (current != null) {
            byDir[current]?.let { return it }
            current = current.parent
        }
        return null
    }

    /**
     * The copy whose role directory is the absolute [path] or one of its ancestors, for VFS events of files that may no
     * longer exist. Pure string work.
     */
    fun copyContainingPath(path: String): RoleCopy? {
        var current = path.trimEnd('/')
        while (current.isNotEmpty()) {
            byPath[current]?.let { return it }
            val slash = current.lastIndexOf('/')
            if (slash <= 0) return null
            current = current.substring(0, slash)
        }
        return null
    }

    /** The copies owned by [root], by name. */
    fun copiesOf(root: AnsibleRoot): List<RoleCopy> = byName.values.flatten().filter { it.root == root }

    companion object {
        /** A catalog without roles. */
        val EMPTY: RoleCatalogSnapshot = RoleCatalogSnapshot(emptyList())

        private val KIND_RANK: Map<RootKind, Int> =
            mapOf(RootKind.ROLE_LIBRARY to 0, RootKind.PROJECT to 1, RootKind.NESTED_PLAYBOOK to 2)

        /** Role libraries, then project roots, then nested roots; within a group by path, segment by segment. */
        internal val ROOT_ORDER: Comparator<AnsibleRoot> = compareBy<AnsibleRoot> { KIND_RANK.getValue(it.kind) }
            .then { a, b -> compareSegments(a.dir.path.split('/'), b.dir.path.split('/')) }

        private val COPY_ORDER: Comparator<RoleCopy> = compareBy<RoleCopy> { !it.isReference }
            .then(compareBy(ROOT_ORDER) { it.root })
            .thenBy { it.dir.path }

        private fun compareSegments(a: List<String>, b: List<String>): Int {
            for (i in 0 until minOf(a.size, b.size)) {
                val c = a[i].compareTo(b[i])
                if (c != 0) return c
            }
            return a.size.compareTo(b.size)
        }
    }
}

/**
 * The project's role catalog (plan amendment R9, A.15): role name → its copies across every non-detached root, the
 * model behind the Roles tab (F9.3), the Repos tab's Roles node (F9.2), "Same file in other repos" (F9.7) and role
 * drift (`model.drift.RoleDriftService`).
 *
 * - **Copies** come from `RoleRegistry.roles(root)` of every non-detached root. A nested playbook root's roles path
 *   also reaches its parent's `roles/` (and a project's `roles_path` may reach a role library), so each role
 *   directory is attributed once, to `AnsibleWorkspace.rootFor(dir)`, the innermost root containing it. Directories
 *   owned by a detached root (a worktree) are never listed.
 * - **Reference:** the copy in the first role library (`golden`) is the drift reference of its name
 *   ([RoleCopy.isReference]); names without a library copy have no reference.
 * - **Cache:** one snapshot until the Ansible structure, the project roots or the project settings change (role
 *   directories appear and disappear only through structure changes, which also cover every file below `roles/`).
 *
 * Traversal only: resolution stays root-scoped (`RoleRegistry`), and nothing here is ever a Ctrl+B, completion or
 * inspection target. VFS-only and DumbAware; callable from any thread (it takes a read lock when the caller holds
 * none).
 */
@Service(Service.Level.PROJECT)
class RoleCatalog(private val project: Project) {
    private val cached: CachedValue<RoleCatalogSnapshot> = CachedValuesManager.getManager(project).createCachedValue(
        {
            CachedValueProvider.Result.create(
                build(),
                AnsibleWorkspace.getInstance(project).structureTracker,
                ProjectRootManager.getInstance(project),
                AnsibilityProjectSettings.getInstance(project).modificationTracker,
            )
        },
        false,
    )

    /** Bumps whenever the catalog may have changed (structure, project roots, settings). */
    val modificationTracker: ModificationTracker = ModificationTracker {
        AnsibleWorkspace.getInstance(project).structureTracker.modificationCount +
            ProjectRootManager.getInstance(project).modificationCount +
            AnsibilityProjectSettings.getInstance(project).modificationTracker.modificationCount
    }

    /** The current catalog. */
    fun snapshot(): RoleCatalogSnapshot = if (project.isDisposed) RoleCatalogSnapshot.EMPTY else readLocked { cached.value }

    /** Every role name, sorted. */
    fun names(): List<String> = snapshot().names

    /** The copies of [name], reference first. */
    fun copies(name: String): List<RoleCopy> = snapshot().copies(name)

    /** The reference copy of [name], or null. */
    fun reference(name: String): RoleCopy? = snapshot().reference(name)

    /** The copy whose role directory is [dir]. */
    fun copyOf(dir: VirtualFile): RoleCopy? = snapshot().copyOf(dir)

    /** The copy that contains [file] (a role directory or anything below one). */
    fun copyContaining(file: VirtualFile): RoleCopy? = snapshot().copyContaining(file)

    private fun build(): RoleCatalogSnapshot {
        val workspace = AnsibleWorkspace.getInstance(project)
        val registry = RoleRegistry.getInstance(project)
        val roots = workspace.roots().filter { !it.detached }.sortedWith(RoleCatalogSnapshot.ROOT_ORDER)
        val owned = LinkedHashMap<VirtualFile, Pair<AnsibleRoot, RoleRef>>()
        for (root in roots) {
            for (ref in registry.roles(root)) {
                ProgressManager.checkCanceled()
                if (ref.dir in owned || !ref.dir.isValid) continue
                val owner = workspace.rootFor(ref.dir) ?: continue
                if (owner.detached) continue
                owned[ref.dir] = owner to RoleRef(owner.dir, ref.name, ref.dir)
            }
        }
        val referenceOf = HashMap<String, VirtualFile>()
        owned.values
            .filter { (owner, _) -> owner.kind == RootKind.ROLE_LIBRARY }
            .sortedWith(compareBy(RoleCatalogSnapshot.ROOT_ORDER) { it.first })
            .forEach { (_, ref) -> referenceOf.putIfAbsent(ref.name, ref.dir) }
        return RoleCatalogSnapshot(owned.values.map { (owner, ref) -> RoleCopy(owner, ref, referenceOf[ref.name] == ref.dir) })
    }

    private fun <T> readLocked(action: () -> T): T =
        if (ApplicationManager.getApplication().isReadAccessAllowed) action() else runReadActionBlocking(action)

    companion object {
        fun getInstance(project: Project): RoleCatalog = project.service()
    }
}
