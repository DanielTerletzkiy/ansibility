package de.terletzkiy.ansibility.context

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.RootKind
import java.io.IOException

/** A git worktree (or `.claude/worktrees/<name>` copy) inside the project whose roots are detached. */
data class DetachedWorktree(val dir: VirtualFile, val name: String)

/**
 * The result of one root walk: every [AnsibleRoot] found, plus the worktree each detached root belongs to.
 * Immutable; [AnsibleWorkspaceImpl] caches it until the structure tracker or the project roots change.
 */
class RootScan(
    /** Roots sorted by path, detached ones included. */
    val roots: List<AnsibleRoot>,
    private val worktreesByRootDir: Map<VirtualFile, DetachedWorktree>,
    /** The ansible.cfg parsed for each PROJECT root dir. */
    val configs: Map<VirtualFile, AnsibleCfg>,
    /** True when the walk stopped at its directory budget, so some roots may be missing. */
    val truncated: Boolean,
) {
    private val byDir: Map<VirtualFile, AnsibleRoot> = roots.associateBy { it.dir }

    /** The innermost root whose directory is [file] or one of its ancestors. */
    fun rootFor(file: VirtualFile): AnsibleRoot? {
        var current: VirtualFile? = file
        while (current != null) {
            byDir[current]?.let { return it }
            current = current.parent
        }
        return null
    }

    fun worktreeOf(root: AnsibleRoot): DetachedWorktree? = worktreesByRootDir[root.dir]

    /** Distinct detached worktrees, sorted by path. */
    val worktrees: List<DetachedWorktree>
        get() = worktreesByRootDir.values.distinctBy { it.dir }.sortedBy { it.dir.path }

    companion object {
        val EMPTY = RootScan(emptyList(), emptyMap(), emptyMap(), truncated = false)
    }
}

/**
 * Finds Ansible roots by walking the VFS below the given base directories (plan A.5):
 * - **PROJECT**: a directory containing an `ansible.cfg` file;
 * - **ROLE_LIBRARY**: a directory outside every PROJECT root, without `ansible.cfg`, whose `roles/` holds real
 *   roles ([RoleDirectories.isRole]: `tasks/`, `meta/argument_specs.yml` or `defaults/main`), e.g. `golden/`;
 * - **PROJECT** too: a directory without `ansible.cfg` outside every root, with an inventory marker or with roles or
 *   vars dirs next to a top-level playbook, unless another root is found below it ([isCfgLessProject]);
 * - **NESTED_PLAYBOOK**: inside a PROJECT root, a directory other than the root with `playbook-*.yml` files
 *   and its own `roles/`, e.g. `repos/pelican/ansible/danger_zone/database`.
 *
 * A root is **detached** when it lies below `.claude/worktrees/<name>` or below a directory whose `.git` *file*
 * points into `…/.git/worktrees/…`. Submodule `.git` files point into `…/.git/modules/…` and keep a root normal.
 * Only the part of the path below the base directory counts, so a project opened on a worktree is not detached.
 * With [detachedRule] off (the X01 path setting), no root is detached and no worktree is reported.
 *
 * Roles are recognised by [RoleDirectories.isRole], the rule the file classifier and the role registry use too.
 *
 * The walk is cheap: it never descends into [AnsibleLayout.SKIPPED_DIRS], directories [isExcluded] rejects (the
 * project model's excluded folders and the ignored paths of the settings), or the content directories of a root
 * (`roles/`, `environments/`, …); it stops at [maxDepth] and at [maxDirectories].
 */
class RootDetector(
    private val isExcluded: (VirtualFile) -> Boolean = { false },
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
    private val maxDirectories: Int = DEFAULT_MAX_DIRECTORIES,
    private val detachedRule: Boolean = true,
    private val playbookProbe: (VirtualFile) -> Boolean = PlaybookProbe::looksLikePlaybook,
) {
    private class Candidate(
        val dir: VirtualFile,
        val kind: RootKind,
        val base: VirtualFile,
        val parentProject: VirtualFile?,
    )

    /** Mutable state of one [detect] call, so a detector instance holds no state between calls. */
    private inner class Walk {
        val candidates = LinkedHashMap<VirtualFile, Candidate>()
        var visited = 0
        var truncated = false

        fun walk(base: VirtualFile, dir: VirtualFile, depth: Int, enclosingProject: VirtualFile?, insideRoot: Boolean) {
            ProgressManager.checkCanceled()
            if (++visited > maxDirectories) {
                truncated = true
                return
            }
            val children = dir.children ?: return
            val today = kindOf(children, enclosingProject)
            val provisional = today != RootKind.PROJECT && enclosingProject == null && !insideRoot && isCfgLessProject(dir, children)
            val kind = if (provisional) RootKind.PROJECT else today
            if (kind != null) {
                candidates[dir] = Candidate(dir, kind, base, enclosingProject.takeIf { kind == RootKind.NESTED_PLAYBOOK })
            }
            if (depth >= maxDepth) return
            val nextProject = if (kind == RootKind.PROJECT && !provisional) dir else enclosingProject
            val nextInsideRoot = insideRoot || kind != null
            val before = candidates.size
            for (child in children) {
                if (!child.isDirectory || !shouldDescend(child, nextInsideRoot)) continue
                walk(base, child, depth + 1, nextProject, nextInsideRoot)
                if (truncated) return
            }
            if (provisional && candidates.size > before) {
                if (today == null) candidates.remove(dir) else candidates[dir] = Candidate(dir, today, base, null)
            }
        }
    }

    /** Walks every base directory and returns the roots found. Bases nested in other bases are walked once. */
    fun detect(bases: Collection<VirtualFile>): RootScan {
        val distinctBases = bases.filter { it.isValid && it.isDirectory }.distinct()
        val topLevel = distinctBases.filter { base ->
            distinctBases.none { it != base && VfsUtilCore.isAncestor(it, base, true) }
        }
        val walk = Walk()
        for (base in topLevel.sortedBy { it.path }) {
            walk.walk(base, base, depth = 0, enclosingProject = null, insideRoot = false)
            if (walk.truncated) break
        }
        if (walk.truncated) LOG.warn("Ansible root scan stopped after $maxDirectories directories; some roots may be missing")
        return build(walk.candidates.values.toList(), walk.truncated)
    }

    private fun shouldDescend(child: VirtualFile, insideRoot: Boolean): Boolean {
        val name = child.name
        if (name in AnsibleLayout.SKIPPED_DIRS) return false
        if (insideRoot && name in AnsibleLayout.ROOT_CONTENT_DIRS) return false
        if (!insideRoot && name == AnsibleLayout.PATCHES) return false
        return !isExcluded(child)
    }

    /**
     * F10.1 (plan amendment R10): a directory without `ansible.cfg`, outside every root and not itself a role, is a
     * PROJECT when it has an inventory marker, or when it has real roles or a top-level vars dir and a top-level
     * playbook. Provisional: the walk reverts it when it finds a root below (the super-repo guard).
     */
    private fun isCfgLessProject(dir: VirtualFile, children: Array<VirtualFile>): Boolean {
        if (RoleDirectories.isRole(dir)) return false
        val marker = children.any { child ->
            if (child.isDirectory) child.name in INVENTORY_MARKER_DIRS else child.name in INVENTORY_MARKER_FILES
        }
        if (marker) return true
        val candidate = children.any { it.isDirectory && (it.name == AnsibleLayout.GROUP_VARS || it.name == AnsibleLayout.HOST_VARS) } ||
            children.firstOrNull { it.isDirectory && it.name == AnsibleLayout.ROLES }?.let(::hasRealRoles) == true
        return candidate && children.any { !it.isDirectory && AnsibleLayout.isYamlName(it.name) && playbookProbe(it) }
    }

    private fun kindOf(children: Array<VirtualFile>, enclosingProject: VirtualFile?): RootKind? {
        if (children.any { !it.isDirectory && it.name == AnsibleLayout.ANSIBLE_CFG }) return RootKind.PROJECT
        val roles = children.firstOrNull { it.isDirectory && it.name == AnsibleLayout.ROLES } ?: return null
        return if (enclosingProject == null) {
            RootKind.ROLE_LIBRARY.takeIf { hasRealRoles(roles) }
        } else {
            RootKind.NESTED_PLAYBOOK.takeIf { children.any { !it.isDirectory && AnsibleLayout.isPlaybookName(it.name) } }
        }
    }

    private fun build(candidates: List<Candidate>, truncated: Boolean): RootScan {
        val byDir = candidates.associateBy { it.dir }
        val configs = candidates.filter { it.kind == RootKind.PROJECT }.mapNotNull { c ->
            val cfg = c.dir.findChild(AnsibleLayout.ANSIBLE_CFG)?.let(::readCfg) ?: return@mapNotNull null
            c.dir to cfg
        }.toMap()
        val worktrees = HashMap<VirtualFile, DetachedWorktree>()
        val built = candidates.map { c ->
            val worktree = if (detachedRule) detachedWorktreeOf(c.dir, c.base) else null
            if (worktree != null) worktrees[c.dir] = worktree
            val ownRoles = listOfNotNull(c.dir.childDirectory(AnsibleLayout.ROLES))
            val cfgDir = if (c.kind == RootKind.NESTED_PLAYBOOK) c.parentProject else c.dir
            val cfgRoles = cfgDir?.let { dir -> configs[dir]?.let { rolesPathDirs(dir, it) } }.orEmpty()
            AnsibleRoot(
                dir = c.dir,
                kind = c.kind,
                detached = worktree != null,
                parentDir = c.parentProject,
                rolesDirs = (ownRoles + cfgRoles).distinct(),
                environmentsDir = c.dir.childDirectory(AnsibleLayout.ENVIRONMENTS)
                    ?: c.parentProject?.takeIf { c.kind == RootKind.NESTED_PLAYBOOK }?.childDirectory(AnsibleLayout.ENVIRONMENTS),
                displayName = displayName(c, byDir, worktree),
            )
        }
        return RootScan(disambiguate(built, byDir, worktrees).sortedBy { it.dir.path }, worktrees, configs, truncated)
    }

    private fun displayName(c: Candidate, byDir: Map<VirtualFile, Candidate>, worktree: DetachedWorktree?): String {
        val plain = plainName(c, byDir)
        return if (worktree != null) "[${worktree.name}] $plain" else plain
    }

    private fun plainName(c: Candidate, byDir: Map<VirtualFile, Candidate>): String = when (c.kind) {
        RootKind.PROJECT -> if (c.dir.name == "ansible") c.dir.parent?.name ?: c.dir.name else c.dir.name
        RootKind.ROLE_LIBRARY -> c.dir.name
        RootKind.NESTED_PLAYBOOK -> {
            val parent = c.parentProject?.let(byDir::get)
            val relative = c.parentProject?.let { VfsUtilCore.getRelativePath(c.dir, it) } ?: c.dir.name
            if (parent != null) "${plainName(parent, byDir)} $NESTED_SEPARATOR $relative" else relative
        }
    }

    /** Two roots with the same label (e.g. two `…/x/ansible`) fall back to their path below the base dir. */
    private fun disambiguate(
        roots: List<AnsibleRoot>,
        byDir: Map<VirtualFile, Candidate>,
        worktrees: Map<VirtualFile, DetachedWorktree>,
    ): List<AnsibleRoot> {
        val duplicates = roots.groupBy { it.displayName }.filterValues { it.size > 1 }.keys
        if (duplicates.isEmpty()) return roots
        return roots.map { root ->
            if (root.displayName !in duplicates) return@map root
            val base = byDir.getValue(root.dir).base
            val path = VfsUtilCore.getRelativePath(root.dir, base)?.takeIf { it.isNotEmpty() } ?: root.dir.path
            val worktree = worktrees[root.dir]
            root.copy(displayName = if (worktree != null) "[${worktree.name}] $path" else path)
        }
    }

    private fun detachedWorktreeOf(dir: VirtualFile, base: VirtualFile): DetachedWorktree? {
        claudeWorktreeOf(dir, base)?.let { return it }
        var current: VirtualFile? = dir
        while (current != null && current != base) {
            val git = current.findChild(AnsibleLayout.DOT_GIT)
            if (git != null && !git.isDirectory && readText(git)?.let(GitLink::classify) == GitLink.WORKTREE) {
                return DetachedWorktree(current, current.name)
            }
            current = current.parent
        }
        return null
    }

    private fun claudeWorktreeOf(dir: VirtualFile, base: VirtualFile): DetachedWorktree? {
        val segments = VfsUtilCore.getRelativePath(dir, base)?.split('/') ?: return null
        val at = segments.indices.firstOrNull { i ->
            segments[i] == ".claude" && segments.getOrNull(i + 1) == "worktrees" && i + 2 < segments.size
        } ?: return null
        val worktreeDir = base.findFileByRelativePath(segments.subList(0, at + 3).joinToString("/")) ?: return null
        return DetachedWorktree(worktreeDir, segments[at + 2])
    }

    private fun rolesPathDirs(cfgDir: VirtualFile, cfg: AnsibleCfg): List<VirtualFile> =
        cfg.rolesPath.mapNotNull { entry ->
            when {
                entry.startsWith("~") || entry.startsWith("$") -> null
                entry.startsWith("/") -> cfgDir.fileSystem.findFileByPath(entry)
                else -> cfgDir.findFileByRelativePath(entry.removePrefix("./"))
            }?.takeIf { it.isDirectory }
        }

    private fun readCfg(file: VirtualFile): AnsibleCfg? = readText(file)?.let(AnsibleCfg::parse)

    companion object {
        /** Deep enough for `.claude/worktrees/<wt>/repos/<r>/ansible/danger_zone/<cat>` plus slack. */
        const val DEFAULT_MAX_DEPTH: Int = 12
        const val DEFAULT_MAX_DIRECTORIES: Int = 50_000
        const val NESTED_SEPARATOR: String = "›"

        /** Files that mark a directory as holding an inventory (F10.1). */
        val INVENTORY_MARKER_FILES: Set<String> =
            setOf("hosts", "hosts.ini", "hosts.yml", "hosts.yaml", "inventory", "inventory.ini", "inventory.yml", "inventory.yaml")

        /** Directories that mark a directory as holding inventories (F10.1). */
        val INVENTORY_MARKER_DIRS: Set<String> = setOf("inventory", "inventories", AnsibleLayout.ENVIRONMENTS)

        private val LOG = logger<RootDetector>()

        /** Whether [rolesDir] holds at least one real role (not only ghosts such as `role-state`); see [RoleDirectories]. */
        fun hasRealRoles(rolesDir: VirtualFile): Boolean = RoleDirectories.hasRoles(rolesDir)

        internal fun readText(file: VirtualFile): String? = try {
            VfsUtilCore.loadText(file)
        } catch (e: IOException) {
            LOG.debug("Cannot read ${file.path}", e)
            null
        }
    }
}

/** The child directory called [name], or null when it is missing or is a file. */
internal fun VirtualFile.childDirectory(name: String): VirtualFile? = findChild(name)?.takeIf { it.isDirectory }
