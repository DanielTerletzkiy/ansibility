package de.terletzkiy.ansibility.golden.remote

import de.terletzkiy.ansibility.golden.remote.GoldenGitUrls.FieldProblem
import de.terletzkiy.ansibility.golden.remote.GoldenGitUrls.Problem
import junit.framework.TestCase

/** D193/D201: which golden repository URLs, refs and roles paths are accepted, and how they are shown. */
class GoldenGitUrlsTest : TestCase() {
    fun testAcceptedUrls() {
        val accepted = listOf(
            "git@git.example.org:infra/golden.git",
            "git.example.org:infra/golden.git",
            "ssh://git@git.example.org/infra/golden.git",
            "ssh://git@git.example.org:2222/infra/golden.git",
            "git+ssh://git@git.example.org/infra/golden.git",
            "https://git.example.org/infra/golden.git",
            "git://git.example.org/infra/golden.git",
            "file:///srv/git/golden.git",
            "/srv/git/golden.git",
            "~/src/golden",
            "  https://git.example.org/infra/golden.git  ",
        )
        for (url in accepted) assertNull(url, GoldenGitUrls.problem(url))
    }

    fun testCredentialsAreRefused() {
        for (url in listOf(
            "https://alice:token@git.example.org/infra/golden.git",
            "https://token@git.example.org/infra/golden.git",
            "http://alice:secret@git.example.org/golden.git",
            "ssh://alice:secret@git.example.org/golden.git",
            "alice:secret@git.example.org:infra/golden.git",
        )) {
            assertEquals(url, Problem.CREDENTIALS, GoldenGitUrls.problem(url))
        }
    }

    fun testCommandTransportsOptionsAndOtherFormsAreRefused() {
        assertEquals(Problem.TRANSPORT, GoldenGitUrls.problem("ext::sh -c touch% /tmp/pwned"))
        assertEquals(Problem.TRANSPORT, GoldenGitUrls.problem("fd::17"))
        assertEquals(Problem.TRANSPORT, GoldenGitUrls.problem("transport::https://git.example.org/x"))
        assertEquals(Problem.OPTION, GoldenGitUrls.problem("--upload-pack=touch /tmp/pwned"))
        assertEquals(Problem.OPTION, GoldenGitUrls.problem("-u"))
        assertEquals(Problem.BLANK, GoldenGitUrls.problem(""))
        assertEquals(Problem.BLANK, GoldenGitUrls.problem("   "))
        for (url in listOf("http://git.example.org/golden.git", "ftp://git.example.org/golden.git", "rsync://git.example.org/x", "golden", "relative/path", "https:///x", "ssh://", "line\nbreak")) {
            assertEquals(url, Problem.UNSUPPORTED, GoldenGitUrls.problem(url))
        }
    }

    fun testTheCredentialsMessageSaysWhy() {
        val text = GoldenMirrorTexts.urlProblem(Problem.CREDENTIALS)
        assertTrue(text, "contains credentials — they would be stored in the shared settings; use SSH or a credential helper" in text)
    }

    fun testSshUrls() {
        assertTrue(GoldenGitUrls.isSsh("git@git.example.org:infra/golden.git"))
        assertTrue(GoldenGitUrls.isSsh("ssh://git@git.example.org/infra/golden.git"))
        assertTrue(GoldenGitUrls.isSsh("git.example.org:golden.git"))
        assertFalse(GoldenGitUrls.isSsh("https://git.example.org/infra/golden.git"))
        assertFalse(GoldenGitUrls.isSsh("git://git.example.org/infra/golden.git"))
        assertFalse(GoldenGitUrls.isSsh("file:///srv/golden.git"))
        assertFalse(GoldenGitUrls.isSsh("/srv/golden.git"))
        assertFalse(GoldenGitUrls.isSsh("C:\\src\\golden"))
    }

    fun testDisplayAndScrubRemoveCredentials() {
        assertEquals("https://git.example.org/x.git", GoldenGitUrls.display("https://alice:token@git.example.org/x.git"))
        assertEquals("git@git.example.org:infra/golden.git", GoldenGitUrls.display("git@git.example.org:infra/golden.git"))
        val scrubbed = GoldenGitUrls.scrub("fatal: unable to access 'https://alice:s3cret@git.example.org/x.git/': 403; ssh://bob:pw@git.example.org/y")
        assertFalse(scrubbed, "s3cret" in scrubbed || "pw@" in scrubbed || "alice" in scrubbed)
        assertTrue(scrubbed, "https://git.example.org/x.git/" in scrubbed)
    }

    fun testRepositoryNames() {
        assertEquals("golden", GoldenGitUrls.repositoryName("git@git.example.org:infra/golden.git"))
        assertEquals("golden", GoldenGitUrls.repositoryName("git@git.example.org:golden.git"))
        assertEquals("golden", GoldenGitUrls.repositoryName("https://git.example.org/infra/golden.git/"))
        assertEquals("golden", GoldenGitUrls.repositoryName("file:///srv/git/golden"))
        assertEquals("golden", GoldenGitUrls.repositoryName("/srv/git/golden"))
    }

    fun testMirrorKeyIsSha256OfUrlAndRef() {
        val key = GoldenGitUrls.mirrorKey("git@git.example.org:infra/golden.git", "main")
        assertEquals(16, key.length)
        assertTrue(key.all { it in "0123456789abcdef" })
        assertEquals(key, GoldenGitUrls.mirrorKey(" git@git.example.org:infra/golden.git ", " main"))
        assertFalse(key == GoldenGitUrls.mirrorKey("git@git.example.org:infra/golden.git", ""))
        assertFalse(key == GoldenGitUrls.mirrorKey("git@git.example.org:infra/heron.git", "main"))
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest("git@git.example.org:infra/golden.git\nmain".toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        assertEquals(expected, key)
    }

    fun testLocalPathsExpandTheHome() {
        val home = System.getProperty("user.home")
        assertEquals("$home/src/golden", GoldenGitUrls.effective("~/src/golden"))
        assertEquals("git@git.example.org:x.git", GoldenGitUrls.effective(" git@git.example.org:x.git "))
    }

    fun testRefs() {
        for (ok in listOf("", "main", "release/2026", "v1.2", "feature-x")) assertNull(ok, GoldenGitUrls.refProblem(ok))
        for (bad in listOf("-x", "refs/heads/main", "a..b", "a b", "x~1", "x^", "x:y", "x?", "x*", "[x", "x\\y", "x.lock", "x/", "/x", "x@{1}", ".hidden", "a/.b")) {
            assertEquals(bad, FieldProblem.REF, GoldenGitUrls.refProblem(bad))
        }
    }

    fun testRolesPaths() {
        for (ok in listOf("", "roles", "./roles/", "ansible/roles")) assertNull(ok, GoldenGitUrls.rolesPathProblem(ok))
        for (bad in listOf("/roles", "../roles", "roles/../x", "-roles", "rol*", "a\\b", "a//b", "!roles")) {
            assertEquals(bad, FieldProblem.ROLES_PATH, GoldenGitUrls.rolesPathProblem(bad))
        }
    }
}
