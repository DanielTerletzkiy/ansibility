package de.terletzkiy.ansibility.golden.remote

import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.util.io.NioFiles
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.golden.GoldenTestSupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.readLines

/**
 * The system git without the Git plugin (plan amendment R25, D202): it never prompts, adds the safety configuration,
 * refuses old or missing git, ends git and its children when cancelled, and the protocol allow-list holds
 * (`ext::` refused, `file://` allowed). A fake git script records what it was given.
 */
class SystemGitTransportTest : BasePlatformTestCase() {
    private lateinit var temp: Path

    override fun setUp() {
        super.setUp()
        temp = Files.createTempDirectory("ansibility-system-git").toRealPath()
    }

    override fun tearDown() {
        try {
            SystemGitTransport.resetForTests()
            NioFiles.deleteRecursively(temp)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun script(name: String, body: String): Path {
        val file = temp.resolve(name)
        Files.writeString(file, "#!/bin/sh\n$body\n")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"))
        return file
    }

    private fun request(op: GitOp, args: List<String>, url: String?, interaction: Interaction = Interaction.BACKGROUND, timeout: Long = 60) = GitRequest(
        project = project,
        workDir = temp,
        op = op,
        args = args,
        remoteUrl = url,
        interaction = interaction,
        config = GoldenMirrorCommands.safetyConfig(temp.resolve(".no-hooks")),
        env = GoldenMirrorCommands.environment(interaction, network = url != null),
        timeoutSeconds = timeout,
    )

    private fun run(request: GitRequest): GitResult = GoldenTestSupport.await {
        withContext(Dispatchers.IO) { coroutineToIndicator { _ -> SystemGitTransport.run(request) } }
    }

    fun testTheSystemGitNeverPrompts() {
        val out = temp.resolve("out")
        Files.createDirectories(out)
        val git = script(
            "git",
            """
            if [ "${'$'}1" = "--version" ]; then echo "git version 2.40.0"; exit 0; fi
            if [ "${'$'}1" = "config" ]; then exit 1; fi
            printf '%s\n' "${'$'}@" > "$out/args"
            env > "$out/env"
            echo answered
            """.trimIndent(),
        )
        SystemGitTransport.useGitForTests { git }
        val result = run(request(GitOp.LS_REMOTE, listOf("--", "https://git.example.org/golden.git", "HEAD"), "https://git.example.org/golden.git"))
        assertTrue(result.toString(), result.success)
        assertEquals(listOf("answered"), result.output)
        val args = out.resolve("args").readLines()
        assertEquals("the safety configuration comes first", "-c", args.first())
        for (expected in listOf("core.askPass=", "credential.interactive=false", "protocol.allow=never", "core.hooksPath=${temp.resolve(".no-hooks")}")) {
            assertTrue(expected, expected in args)
        }
        assertEquals(listOf("ls-remote", "--", "https://git.example.org/golden.git", "HEAD"), args.takeLast(4))
        val env = out.resolve("env").readLines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        assertNull(env["GIT_ASKPASS"])
        assertNull(env["SSH_ASKPASS"])
        assertEquals("never", env["SSH_ASKPASS_REQUIRE"])
        assertEquals("never", env["GCM_INTERACTIVE"])
        assertEquals("0", env["GIT_TERMINAL_PROMPT"])
        assertEquals("ssh:https:git:file", env["GIT_ALLOW_PROTOCOL"])
        assertEquals("1", env["GIT_LFS_SKIP_SMUDGE"])
        if (System.getenv("GIT_SSH_COMMAND").isNullOrBlank() && System.getenv("GIT_SSH").isNullOrBlank()) {
            assertEquals("ssh -o BatchMode=yes", env["GIT_SSH_COMMAND"])
        }
    }

    fun testOldOrMissingGitIsRefused() {
        val old = script("git", "echo \"git version 2.20.1\"")
        SystemGitTransport.useGitForTests { old }
        val tooOld = run(request(GitOp.LS_REMOTE, listOf("--", "file:///x", "HEAD"), "file:///x"))
        assertTrue(tooOld.startFailed)
        assertEquals(listOf(GoldenMirrorTexts.gitTooOld()), tooOld.errorOutput)

        SystemGitTransport.useGitForTests { null }
        val missing = run(request(GitOp.LS_REMOTE, listOf("--", "file:///x", "HEAD"), "file:///x"))
        assertTrue(missing.startFailed)
        assertEquals(listOf(GoldenMirrorTexts.gitMissing()), missing.errorOutput)
    }

    fun testCancellationEndsGitAndItsChildren() {
        val pidFile = temp.resolve("pid")
        val git = script(
            "git",
            """
            if [ "${'$'}1" = "--version" ]; then echo "git version 2.40.0"; exit 0; fi
            if [ "${'$'}1" = "config" ]; then exit 1; fi
            sleep 60 &
            echo ${'$'}! > "$pidFile"
            wait
            """.trimIndent(),
        )
        SystemGitTransport.useGitForTests { git }
        val started = System.nanoTime()
        val cancelled = GoldenTestSupport.await {
            coroutineScope {
                val job = async(Dispatchers.IO) { coroutineToIndicator { _ -> SystemGitTransport.run(request(GitOp.FETCH, listOf("origin"), "file:///x")) } }
                while (!Files.exists(pidFile) || Files.readString(pidFile).isBlank()) delay(20)
                job.cancel()
                try {
                    job.await()
                    false
                } catch (_: CancellationException) {
                    true
                }
            }
        }
        assertTrue(cancelled)
        assertTrue("cancelled at once", System.nanoTime() - started < 30_000_000_000L)
        val child = Files.readString(pidFile).trim().toLong()
        val deadline = System.currentTimeMillis() + 5000
        while (ProcessHandle.of(child).map { it.isAlive }.orElse(false) && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertFalse("the child (ssh, a credential helper…) is gone too", ProcessHandle.of(child).map { it.isAlive }.orElse(false))
    }

    fun testTheTimeoutEndsGit() {
        val git = script(
            "git",
            """
            if [ "${'$'}1" = "--version" ]; then echo "git version 2.40.0"; exit 0; fi
            if [ "${'$'}1" = "config" ]; then exit 1; fi
            sleep 60
            """.trimIndent(),
        )
        SystemGitTransport.useGitForTests { git }
        val result = run(request(GitOp.FETCH, listOf("origin"), "file:///x", timeout = 1))
        assertTrue(result.startFailed)
        assertEquals(listOf(GoldenMirrorTexts.gitDidNotFinish()), result.errorOutput)
    }

    fun testTheProtocolAllowListHolds() {
        val golden = GitTestRepo.standard(temp.resolve("golden"))
        val listed = run(request(GitOp.LS_REMOTE, listOf("--", golden.url, "HEAD"), golden.url))
        assertTrue("file:// is allowed (protocol.file.allow=always after protocol.allow=never)", listed.success)
        assertEquals("${golden.head()}\tHEAD", listed.output.single())

        val marker = temp.resolve("pwned")
        val ext = run(request(GitOp.LS_REMOTE, listOf("--", "ext::sh -c touch% $marker", "HEAD"), "ext::sh"))
        assertFalse(ext.success)
        assertFalse("ext:: never runs a command (D201)", Files.exists(marker))
        assertTrue(ext.errorOutput.toString(), ext.errorOutput.any { "not allowed" in it || "unsupported" in it.lowercase() })
    }
}
