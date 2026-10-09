package de.terletzkiy.ansibility.golden.remote

import de.terletzkiy.ansibility.golden.remote.GoldenMirrorCommands.RefKind
import de.terletzkiy.ansibility.golden.remote.GoldenMirrorCommands.RemoteRef
import junit.framework.TestCase
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/** The pure builders and parsers of the golden mirror's git commands (plan amendment R25, D194, D201, D202). */
class GoldenMirrorCommandsTest : TestCase() {
    private val hooks = Path.of("/cache/ansibility/golden/.no-hooks")
    private val commit = "0d3a044156a229cad25201cac3716ee6f50db71b"
    private val tagObject = "bbf5818a65b05c94498a7c04d00909a637f8eaf2"
    private val peeled = "daef3dd065fe5c65509642ecad7a3d5b49089fe1"

    fun testEveryInvocationIsHardened() {
        val config = GoldenMirrorCommands.safetyConfig(hooks)
        for (expected in listOf(
            "core.hooksPath=$hooks", "core.symlinks=false", "core.fsmonitor=false", "core.protectNTFS=true", "core.protectHFS=true",
            "submodule.recurse=false", "fetch.recurseSubmodules=false", "filter.lfs.smudge=", "filter.lfs.process=",
            "filter.lfs.required=false", "gc.auto=0", "maintenance.auto=false", "advice.detachedHead=false",
            "transfer.credentialsInUrl=die", "protocol.allow=never", "protocol.ssh.allow=always", "protocol.https.allow=always",
            "protocol.git.allow=always", "protocol.file.allow=always",
        )) {
            assertTrue(expected, expected in config)
        }
        assertTrue("protocol.allow=never comes before the allowed ones", config.indexOf("protocol.allow=never") < config.indexOf("protocol.ssh.allow=always"))
        assertFalse("never ext or http", config.any { it.startsWith("protocol.ext") || it.startsWith("protocol.http.") })
    }

    fun testEnvironment() {
        val background = GoldenMirrorCommands.environment(Interaction.BACKGROUND, network = true)
        assertEquals("ssh:https:git:file", background["GIT_ALLOW_PROTOCOL"])
        assertEquals("1", background["GIT_LFS_SKIP_SMUDGE"])
        assertEquals("0", background["GIT_TERMINAL_PROMPT"])
        assertEquals("never", background["GCM_INTERACTIVE"])
        assertNull(background["GIT_NO_LAZY_FETCH"])
        val explicit = GoldenMirrorCommands.environment(Interaction.EXPLICIT, network = true)
        assertNull("explicit commands may use Git Credential Manager's dialog", explicit["GCM_INTERACTIVE"])
        assertEquals("0", explicit["GIT_TERMINAL_PROMPT"])
        assertEquals("1", GoldenMirrorCommands.environment(Interaction.BACKGROUND, network = false)["GIT_NO_LAZY_FETCH"])
        assertFalse("never GIT_SSH_COMMAND on the Git plugin's path", "GIT_SSH_COMMAND" in background || "GIT_SSH_COMMAND" in explicit)
    }

    fun testTheSystemGitNeverPrompts() {
        val inherited = mapOf("PATH" to "/usr/bin", "GIT_ASKPASS" to "/x/askpass", "SSH_ASKPASS" to "/x/ssh-askpass", "HOME" to "/home/alice")
        val env = GoldenMirrorCommands.systemGitEnvironment(inherited, GoldenMirrorCommands.environment(Interaction.EXPLICIT, true), sshCommandConfigured = false)
        assertNull(env["GIT_ASKPASS"])
        assertNull(env["SSH_ASKPASS"])
        assertEquals("never", env["SSH_ASKPASS_REQUIRE"])
        assertEquals("never", env["GCM_INTERACTIVE"])
        assertEquals("0", env["GIT_TERMINAL_PROMPT"])
        assertEquals("ssh -o BatchMode=yes", env["GIT_SSH_COMMAND"])
        assertEquals("/home/alice", env["HOME"])
        assertEquals("ssh:https:git:file", env["GIT_ALLOW_PROTOCOL"])
        assertNull("the user's core.sshCommand stays", GoldenMirrorCommands.systemGitEnvironment(inherited, emptyMap(), sshCommandConfigured = true)["GIT_SSH_COMMAND"])
        assertEquals("ssh -i k", GoldenMirrorCommands.systemGitEnvironment(inherited + ("GIT_SSH_COMMAND" to "ssh -i k"), emptyMap(), false)["GIT_SSH_COMMAND"])
        assertNull(GoldenMirrorCommands.systemGitEnvironment(inherited + ("GIT_SSH" to "/x/ssh"), emptyMap(), false)["GIT_SSH_COMMAND"])
        assertEquals(listOf("core.askPass=", "credential.interactive=false"), GoldenMirrorCommands.SYSTEM_GIT_CONFIG)
    }

    fun testCloneArguments() {
        val template = Path.of("/cache/ansibility/golden/.template")
        assertEquals(
            listOf(
                "--depth", "1", "--filter=blob:none", "--sparse", "--single-branch", "--no-tags", "--no-recurse-submodules",
                "--template=$template", "--no-local", "--progress", "--branch", "main", "--", "git@git.example.org:infra/golden.git", "k.partial",
            ),
            GoldenMirrorCommands.cloneArgs("git@git.example.org:infra/golden.git", "main", 1, filter = true, progress = true, templateDir = template, target = "k.partial"),
        )
        val plain = GoldenMirrorCommands.cloneArgs("https://git.example.org/golden.git", "", 50, filter = false, progress = false, templateDir = template, target = "k.partial")
        assertEquals(listOf("--depth", "50"), plain.take(2))
        assertFalse("--filter=blob:none" in plain || "--branch" in plain || "--progress" in plain)
        assertEquals(listOf("--", "https://git.example.org/golden.git", "k.partial"), plain.takeLast(3))
    }

    fun testLsRemoteFetchAndCheckoutArguments() {
        assertEquals(listOf("--symref", "--", "u", "HEAD"), GoldenMirrorCommands.lsRemoteArgs("u", ""))
        assertEquals(listOf("--", "u", "refs/heads/v1", "refs/tags/v1", "refs/tags/v1^{}"), GoldenMirrorCommands.lsRemoteArgs("u", "v1"))
        assertEquals(listOf("--symref", "--", "u", "HEAD", "refs/heads/main", "refs/tags/main", "refs/tags/main^{}"), GoldenMirrorCommands.testConnectionArgs("u", "main"))
        assertEquals(
            listOf("--depth", "3", "--no-tags", "--no-recurse-submodules", "origin", "+refs/heads/main:refs/remotes/origin/main"),
            GoldenMirrorCommands.fetchArgs(RemoteRef(commit, RefKind.BRANCH, "main"), 3),
        )
        assertEquals("+refs/tags/v1:refs/tags/v1", GoldenMirrorCommands.fetchArgs(RemoteRef(peeled, RefKind.TAG, "v1"), 1).last())
        assertEquals(listOf("--force", "--detach", commit), GoldenMirrorCommands.checkoutArgs(commit))
        assertThrows { GoldenMirrorCommands.checkoutArgs("--orphan") }
        assertEquals(listOf("init", "--cone"), GoldenMirrorCommands.SPARSE_INIT_ARGS)
        assertEquals(listOf("set", "ansible/roles"), GoldenMirrorCommands.sparseSetArgs("ansible/roles"))
        assertThrows { GoldenMirrorCommands.sparseSetArgs("--no-cone") }
        assertThrows { GoldenMirrorCommands.sparseSetArgs("") }
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            fail("expected an exception")
        } catch (_: IllegalArgumentException) {
        }
    }

    fun testLsRemoteParsing() {
        val symref = listOf("ref: refs/heads/main\tHEAD", "$commit\tHEAD")
        assertEquals(RemoteRef(commit, RefKind.BRANCH, "main"), GoldenMirrorCommands.parseLsRemote(symref, ""))
        assertEquals("main", GoldenMirrorCommands.defaultBranch(symref))
        assertEquals(RemoteRef(commit, RefKind.HEAD, "HEAD"), GoldenMirrorCommands.parseLsRemote(listOf("$commit\tHEAD"), ""))
        val tags = listOf("$tagObject\trefs/tags/v1", "$peeled\trefs/tags/v1^{}")
        assertEquals("the peeled commit of an annotated tag", RemoteRef(peeled, RefKind.TAG, "v1"), GoldenMirrorCommands.parseLsRemote(tags, "v1"))
        assertEquals(RemoteRef(tagObject, RefKind.TAG, "light"), GoldenMirrorCommands.parseLsRemote(listOf("$tagObject\trefs/tags/light"), "light"))
        val both = listOf("$commit\trefs/heads/v1") + tags
        assertEquals("a branch wins over a tag", RemoteRef(commit, RefKind.BRANCH, "v1"), GoldenMirrorCommands.parseLsRemote(both, "v1"))
        assertNull(GoldenMirrorCommands.parseLsRemote(emptyList(), "main"))
        assertNull(GoldenMirrorCommands.parseLsRemote(listOf("$commit\trefs/heads/other/main"), "main"))
        assertNull(GoldenMirrorCommands.parseLsRemote(emptyList(), ""))
    }

    fun testCommitParsing() {
        val info = GoldenMirrorCommands.parseCommit(listOf("$commit\u0000bob\u00002026-10-09T13:45:08+02:00\u0000golden: change 5"))!!
        assertEquals(commit, info.hash)
        assertEquals("bob", info.author)
        assertEquals(Instant.parse("2026-10-09T11:45:08Z"), info.instant)
        assertEquals("golden: change 5", info.subject)
        assertNull(GoldenMirrorCommands.parseCommit(listOf("garbage")))
        assertNull(GoldenMirrorCommands.parseCommit(emptyList()))
    }

    fun testRolesPathFromAnsibleCfg() {
        assertEquals(listOf("roles"), GoldenMirrorCommands.rolesPathsFromAnsibleCfg("[defaults]\nroles_path = ./roles\n"))
        assertEquals(
            listOf("ansible/roles", "shared"),
            GoldenMirrorCommands.rolesPathsFromAnsibleCfg("# c\n[ssh_connection]\nroles_path = no\n[defaults]\ninventory = x\nroles_path: ./ansible/roles/:~/.ansible/roles:/etc/ansible/roles:\$HOME/r:../up:shared\n"),
        )
        assertEquals(emptyList<String>(), GoldenMirrorCommands.rolesPathsFromAnsibleCfg("[defaults]\ninventory = x\n"))
        assertEquals(emptyList<String>(), GoldenMirrorCommands.rolesPathsFromAnsibleCfg("[galaxy]\nroles_path = roles\n"))
    }

    fun testFailureClassification() {
        fun result(vararg lines: String) = GitResult(128, emptyList(), lines.toList())
        assertTrue(GoldenMirrorCommands.isAuthFailure(result("git@git.example.org: Permission denied (publickey).", "fatal: Could not read from remote repository.")))
        assertTrue(GoldenMirrorCommands.isAuthFailure(result("fatal: could not read Username for 'https://git.example.org': terminal prompts disabled")))
        assertTrue(GoldenMirrorCommands.isAuthFailure(result("remote: HTTP Basic: Access denied", "fatal: Authentication failed for 'https://git.example.org/x.git/'")))
        assertTrue(GoldenMirrorCommands.isAuthFailure(result("fatal: unable to access 'https://git.example.org/x.git/': The requested URL returned error: 403")))
        assertTrue(GoldenMirrorCommands.isAuthFailure(result("remote: Repository not found.", "fatal: repository 'https://git.example.org/x.git/' not found")))
        assertTrue(GoldenMirrorCommands.isAuthFailure(GitResult(128, emptyList(), emptyList(), authenticationFailed = true)))
        assertFalse(GoldenMirrorCommands.isAuthFailure(result("fatal: unable to access 'https://git.example.org/x.git/': Could not resolve host: git.example.org")))
        assertFalse(GoldenMirrorCommands.isAuthFailure(result("ssh: connect to host git.example.org port 22: Connection refused")))
        assertTrue(GoldenMirrorCommands.isFilterRefused(result("fatal: filtering not supported by the server")))
        assertFalse("git ignores an unknown filter and goes on", GoldenMirrorCommands.isFilterRefused(result("warning: filtering not recognized by server, ignoring")))
    }

    fun testShortErrorsNameTheCauseWithoutCredentials() {
        fun short(vararg lines: String) = GoldenMirrorCommands.shortError(GitResult(128, emptyList(), lines.toList()))
        assertEquals(
            "git@git.example.org: Permission denied (publickey)",
            short("Cloning into 'k.partial'...", "git@git.example.org: Permission denied (publickey).", "fatal: Could not read from remote repository.", "", "Please make sure you have the correct access rights"),
        )
        val leaked = short("fatal: unable to access 'https://alice:s3cret@git.example.org/x.git/': Could not resolve host: git.example.org")
        assertFalse(leaked, "s3cret" in leaked || "alice" in leaked)
        assertTrue(leaked, "Could not resolve host" in leaked)
        assertEquals("exit 128", short())
        assertEquals("Remote branch nope not found in upstream origin", short("Receiving objects: 100% (3/3), done.", "warning: Could not find remote branch nope to clone.", "fatal: Remote branch nope not found in upstream origin"))
        assertTrue(short("x".repeat(500)).length <= 200)
    }

    fun testVersions() {
        assertTrue(GoldenMirrorCommands.isSupportedVersion("git version 2.25.0"))
        assertTrue(GoldenMirrorCommands.isSupportedVersion("git version 2.33.0"))
        assertTrue(GoldenMirrorCommands.isSupportedVersion("git version 3.0.1"))
        assertTrue(GoldenMirrorCommands.isSupportedVersion("git version 2.39.5 (Apple Git-154)"))
        assertFalse(GoldenMirrorCommands.isSupportedVersion("git version 2.24.4"))
        assertFalse(GoldenMirrorCommands.isSupportedVersion("garbage"))
    }

    fun testBackoffDoublesUpToAnHour() {
        val thirty = Duration.ofMinutes(30)
        assertEquals(thirty, GoldenMirrorService.backoff(thirty, 0))
        assertEquals(Duration.ofMinutes(60), GoldenMirrorService.backoff(thirty, 1))
        assertEquals(Duration.ofMinutes(60), GoldenMirrorService.backoff(thirty, 5))
        val five = Duration.ofMinutes(5)
        assertEquals(listOf(5L, 10L, 20L, 40L, 60L, 60L), (0..5).map { GoldenMirrorService.backoff(five, it).toMinutes() })
        assertEquals(Duration.ofHours(1), GoldenMirrorService.backoff(five, 400))
    }

    fun testMetadataRoundTrip() {
        val dir = java.nio.file.Files.createTempDirectory("golden-meta")
        try {
            java.nio.file.Files.createDirectories(dir.resolve(".git"))
            val meta = GoldenMirrorMeta(
                url = "file:///srv/golden", ref = "", refKind = RefKind.BRANCH, branch = "main", depth = 3, rolesSetting = "",
                rolesPath = "roles", filtered = true, fetches = 2, commit = commit, author = "bob",
                commitInstant = Instant.parse("2026-10-09T11:45:08Z"), subject = "golden: change = 5", fetchedAt = Instant.parse("2026-10-09T12:00:00Z"),
            )
            meta.write(dir)
            assertEquals(meta, GoldenMirrorMeta.read(dir))
            assertNull(GoldenMirrorMeta.read(dir.resolve("missing")))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    fun testExternalSshAgentRule() {
        assertFalse(ExternalSshAgent.isExternal(null))
        assertFalse(ExternalSshAgent.isExternal(""))
        assertTrue("any other agent", ExternalSshAgent.isExternal("/Users/alice/Library/Group Containers/agent.sock"))
        val dir = java.nio.file.Files.createTempDirectory("com.apple.launchd.test")
        try {
            val plain = java.nio.file.Files.createFile(dir.resolve("Listeners"))
            assertFalse("the plain launchd socket", ExternalSshAgent.isExternal(plain.toString()))
            val other = java.nio.file.Files.createFile(dir.resolve("agent.sock"))
            val link = java.nio.file.Files.createSymbolicLink(dir.resolve("Linked"), other)
            assertTrue("a launchd socket replaced by a link", ExternalSshAgent.isExternal(link.toString()))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
