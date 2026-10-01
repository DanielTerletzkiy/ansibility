package de.terletzkiy.ansibility.context

/**
 * What a `.git` *file* (as opposed to a `.git` directory) links to.
 *
 * Git writes `gitdir: <path>` into the `.git` file of linked worktrees (the path points into
 * `<main>/.git/worktrees/<name>`) and of submodules (the path points into `<super>/.git/modules/<path>`).
 * Only worktrees make an Ansible root detached (plan A.5); submodules such as `repos/falcon` are normal roots.
 */
enum class GitLink {
    WORKTREE,
    SUBMODULE,

    /** A `gitdir:` pointing somewhere else (e.g. a separate git dir), or no `gitdir:` line at all. */
    OTHER,
    ;

    companion object {
        private val WORKTREE_PATH = Regex("""(^|/)\.git/worktrees/[^/]+""")
        private val SUBMODULE_PATH = Regex("""(^|/)\.git/modules/""")

        /** The `gitdir:` target of a `.git` file's text, or null when there is none. */
        fun gitdirOf(text: CharSequence): String? =
            text.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("gitdir:") }
                ?.removePrefix("gitdir:")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

        /** Classifies a `.git` file by its content. Windows separators are normalised first. */
        fun classify(text: CharSequence): GitLink {
            val target = gitdirOf(text)?.replace('\\', '/') ?: return OTHER
            return when {
                WORKTREE_PATH.containsMatchIn(target) -> WORKTREE
                SUBMODULE_PATH.containsMatchIn(target) -> SUBMODULE
                else -> OTHER
            }
        }
    }
}
