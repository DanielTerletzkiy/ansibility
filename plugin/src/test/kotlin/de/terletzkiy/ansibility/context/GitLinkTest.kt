package de.terletzkiy.ansibility.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitLinkTest {
    @Test
    fun submoduleOfTheTargetRepo() {
        // repos/falcon/.git in the real repo
        assertEquals(GitLink.SUBMODULE, GitLink.classify("gitdir: ../../.git/modules/repos/falcon\n"))
    }

    @Test
    fun linkedWorktree() {
        assertEquals(
            GitLink.WORKTREE,
            GitLink.classify("gitdir: /Users/dev/ansible-infrastructure/.git/worktrees/feature-x-1a2b3c\n"),
        )
        assertEquals(GitLink.WORKTREE, GitLink.classify("gitdir: ../.git/worktrees/feature"))
        assertEquals(GitLink.WORKTREE, GitLink.classify("gitdir: C:\\work\\repo\\.git\\worktrees\\wt"))
    }

    @Test
    fun submoduleInsideAWorktreeIsDetached() {
        assertEquals(GitLink.WORKTREE, GitLink.classify("gitdir: /r/.git/worktrees/wt/modules/repos/falcon"))
    }

    @Test
    fun otherLinks() {
        assertEquals(GitLink.OTHER, GitLink.classify("gitdir: /srv/git/repo.git"))
        assertEquals(GitLink.OTHER, GitLink.classify("not a git link"))
        assertEquals(GitLink.OTHER, GitLink.classify(""))
        assertEquals(GitLink.OTHER, GitLink.classify("gitdir: /r/.gitworktrees/x"))
    }

    @Test
    fun gitdirParsing() {
        assertEquals("../../.git/modules/repos/falcon", GitLink.gitdirOf("\n  gitdir:   ../../.git/modules/repos/falcon  \n"))
        assertNull(GitLink.gitdirOf("gitdir:"))
    }
}
