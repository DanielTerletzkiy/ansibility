package de.terletzkiy.ansibility.context

import com.intellij.openapi.vfs.VfsUtilCore
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.RootKind
import org.jetbrains.annotations.Nls

/**
 * The X01 onboarding summary of a project's roots, e.g.
 * `8 project roots · golden · 2 danger-zone · 1 detached worktree`.
 *
 * Counts exclude detached roots, which are summarised as worktrees instead. Nested playbook roots are grouped
 * by the first directory below their parent root (`danger_zone/database` → `danger-zone`).
 */
data class RootsSummary(
    val projectRoots: Int,
    val roleLibraries: List<String>,
    /** Nested playbook roots per category label, in first-seen order. */
    val nestedByCategory: Map<String, Int>,
    val detachedWorktrees: List<DetachedWorktree>,
) {
    val isEmpty: Boolean
        get() = projectRoots == 0 && roleLibraries.isEmpty() && nestedByCategory.isEmpty() && detachedWorktrees.isEmpty()

    @Nls
    fun text(): String {
        val parts = mutableListOf<String>()
        if (projectRoots > 0) parts += AnsibilityCoreBundle.message("onboarding.summary.projects", projectRoots)
        parts += roleLibraries
        nestedByCategory.forEach { (category, count) -> parts += "$count $category" }
        if (detachedWorktrees.isNotEmpty()) {
            parts += AnsibilityCoreBundle.message("onboarding.summary.worktrees", detachedWorktrees.size)
        }
        return parts.joinToString(" · ")
    }

    companion object {
        fun of(roots: List<AnsibleRoot>, worktrees: List<DetachedWorktree>): RootsSummary {
            val normal = roots.filter { !it.detached }
            val nested = LinkedHashMap<String, Int>()
            normal.filter { it.kind == RootKind.NESTED_PLAYBOOK }.forEach { root ->
                val category = categoryOf(root)
                nested[category] = (nested[category] ?: 0) + 1
            }
            return RootsSummary(
                projectRoots = normal.count { it.kind == RootKind.PROJECT },
                roleLibraries = normal.filter { it.kind == RootKind.ROLE_LIBRARY }.map { it.displayName },
                nestedByCategory = nested,
                detachedWorktrees = worktrees,
            )
        }

        private fun categoryOf(root: AnsibleRoot): String {
            val relative = root.parentDir?.let { VfsUtilCore.getRelativePath(root.dir, it) } ?: root.dir.name
            return relative.substringBefore('/').replace('_', '-')
        }
    }
}
