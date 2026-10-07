package de.terletzkiy.ansibility.run

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** The freshness check and the run metadata against real repositories: an upstream, a clone, a second clone that pushes. */
class GitChecksTest {
    private lateinit var base: Path
    private lateinit var git: Path

    @Before
    fun setUp() {
        val found = GitChecks.locateGit()
        assumeTrue("git is not installed", found != null)
        git = found!!
        base = Files.createTempDirectory("ansibility-git").toRealPath()
    }

    @After
    fun tearDown() {
        if (::base.isInitialized) base.toFile().deleteRecursively()
    }

    @Test
    fun `a clone behind its upstream lists the missing commits, an up to date one passes`() {
        val upstream = base.resolve("upstream.git")
        git(base, "init", "--bare", "-b", "main", upstream.toString())
        val work = base.resolve("work")
        git(base, "clone", "-q", upstream.toString(), work.toString())
        git(work, "symbolic-ref", "HEAD", "refs/heads/main")
        commit(work, "first")
        git(work, "push", "origin", "HEAD:main")
        val root = Files.createDirectories(work.resolve("ansible"))

        val checks = GitChecks(git, root)
        assertEquals(Freshness.UpToDate("main", "origin/main"), checks.freshness(""))
        val info = checks.info()
        assertEquals(upstream.toString(), info.url)
        assertEquals("main", info.branch)
        assertEquals(40, info.commit.length)

        val other = base.resolve("other")
        git(base, "clone", "-q", upstream.toString(), other.toString())
        commit(other, "second change")
        commit(other, "third change")
        git(other, "push", "origin", "HEAD:main")

        val behind = checks.freshness("") as Freshness.Behind
        assertEquals("main", behind.branch)
        assertEquals("origin/main", behind.upstream)
        assertEquals(2, behind.count)
        assertTrue(behind.commits.first(), behind.commits.first().endsWith("third change"))
        assertEquals(Freshness.Behind::class, checks.freshness("origin/main")::class)
        assertEquals(Freshness.Behind::class, checks.freshness("main")::class)

        assertEquals(Freshness.NoRemote("origin"), GitChecks(git, Files.createDirectories(base.resolve("lonely")).also { git(it, "init") }).freshness(""))
        assertEquals(Freshness.NotARepository, GitChecks(git, Files.createDirectories(base.resolve("plain"))).freshness(""))
    }

    @Test
    fun `a fetch that fails leaves the decision to the user`() {
        val work = base.resolve("work")
        git(base, "init", "-b", "main", work.toString())
        commit(work, "first")
        git(work, "remote", "add", "origin", base.resolve("missing.git").toString())
        val result = GitChecks(git, work).freshness("")
        assertTrue(result.toString(), result is Freshness.Unverified)
    }

    @Test
    fun `the remote url is passed without credentials`() {
        assertEquals("https://git.example.test/team/infra.git", GitChecks.withoutCredentials("https://jane:t0ken@git.example.test/team/infra.git"))
        assertEquals("https://git.example.test/infra.git", GitChecks.withoutCredentials("https://t0ken@git.example.test/infra.git"))
        assertEquals("ssh://git@git.example.test:7999/team/infra.git", GitChecks.withoutCredentials("ssh://git@git.example.test:7999/team/infra.git"))
        assertEquals("git@git.example.test:team/infra.git", GitChecks.withoutCredentials("git@git.example.test:team/infra.git"))
        assertEquals("/srv/infra.git", GitChecks.withoutCredentials("/srv/infra.git"))
    }

    private fun commit(dir: Path, message: String) {
        Files.writeString(dir.resolve("file.txt"), message)
        git(dir, "add", "file.txt")
        git(dir, "-c", "user.name=Test", "-c", "user.email=test@example.test", "commit", "-q", "-m", message)
    }

    private fun git(dir: Path, vararg args: String) {
        val process = ProcessBuilder(listOf(git.toString()) + args).directory(dir.toFile()).redirectErrorStream(true).start()
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        assertEquals(out, 0, process.exitValue())
    }
}
