package de.terletzkiy.ansibility.golden.remote

import com.intellij.openapi.util.io.NioFiles
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.golden.GoldenTestSupport
import de.terletzkiy.ansibility.golden.remote.GitTestRepo.Companion.files
import de.terletzkiy.ansibility.golden.remote.GitTestRepo.Companion.revCount
import de.terletzkiy.ansibility.golden.remote.GoldenMirrorState.Kind
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.SettingsTestSupport
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * The golden mirror (plan amendment R25, D193–D196, D201–D203, X125, X127) against real repositories in temp
 * directories (`file://` URLs, the system git): clone, refresh, scheduling gates, backoff, pauses, consent, clean-up
 * and the folder kind. The cache directory, clock, trust, power-save mode and SSH agent are test seams.
 */
class GoldenMirrorServiceTest : BasePlatformTestCase() {
    private lateinit var temp: Path
    private lateinit var cache: Path
    private lateinit var service: GoldenMirrorService
    private lateinit var transport: RecordingTransport
    private lateinit var balloons: RecordedBalloons

    @Volatile
    private var now: Instant = Instant.parse("2026-10-09T10:00:00Z")

    override fun setUp() {
        super.setUp()
        temp = Files.createTempDirectory("ansibility-golden-mirror").toRealPath()
        cache = temp.resolve("cache")
        transport = RecordingTransport()
        service = GoldenMirrorService.getInstance(project)!!
        service.resetForTests()
        service.configureForTests(base = cache, transport = transport, now = { now }, trusted = true, powerSave = false, externalAgent = false)
        GoldenMirrorConsents.getInstance().resetForTests()
        balloons = RecordedBalloons(project, testRootDisposable)
    }

    override fun tearDown() {
        try {
            SettingsTestSupport.resetAll(project)
            GoldenTestSupport.await { service.reconcileForTests() }
            service.resetForTests()
            GoldenMirrorConsents.getInstance().resetForTests()
            NioFiles.deleteRecursively(temp)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private fun useGit(url: String, ref: String = "", rolesPath: String = "", refreshMinutes: Int = 30, depth: Int = 1, consent: Boolean? = true) {
        consent?.let { GoldenMirrorConsents.getInstance().set(url, it) }
        MirrorSettings.useGit(project, url, ref, rolesPath, refreshMinutes, depth)
        GoldenTestSupport.await { service.reconcileForTests() }
    }

    private fun state(): GoldenMirrorState = service.state() ?: error("no external golden root")

    private fun tick(): Boolean = GoldenTestSupport.await { service.tick() }

    private fun fetchNow(interactive: Boolean = true) = GoldenTestSupport.await { service.fetchNowAndWait(interactive) }

    private fun later(duration: Duration) {
        now = now.plus(duration)
    }

    private fun repo(name: String = "golden"): GitTestRepo = GitTestRepo.standard(temp.resolve(name))

    private fun cacheEntries(): List<String> = Files.list(cache).use { it.map(Path::name).sorted().toList() }

    private fun failNetwork(vararg lines: String) {
        transport.answer = { request -> if (request.op.network) RecordingTransport.failure(*lines) else null }
    }

    // ------------------------------------------------------------------------------------------------ clone

    fun testTheFirstCloneIsSparseShallowAndInTheCache() {
        val golden = repo()
        useGit(golden.url)
        assertTrue(tick())

        val state = state()
        assertNull(state.error)
        assertFalse(state.fetching)
        assertEquals(Kind.GIT, state.kind)
        assertEquals(golden.head(), state.commit)
        assertEquals("alice", state.commitAuthor)
        assertEquals("golden: first", state.commitSubject)
        assertNotNull(state.commitInstant)
        assertEquals(now, state.fetchedAt)
        assertEquals("golden", state.name)
        assertEquals("${golden.url} (main)", state.source)
        assertEquals("main", state.ref)
        val dir = state.baseDir!!
        assertEquals("<system>/ansibility/golden/<sha256(url, ref)>", cache.resolve(GoldenGitUrls.mirrorKey(golden.url, "")), dir)
        assertEquals(dir.resolve("roles"), state.rolesDir)
        val files = files(dir)
        assertTrue(files.toString(), files.containsAll(listOf("roles/web/tasks/main.yml", "roles/web/defaults/main.yml", "roles/db/tasks/main.yml")))
        assertTrue("roles only (approved): $files", files.none { it.startsWith("playbooks/") })
        assertEquals("shallow", 1, revCount(dir))
        assertEquals("no partial clone is left", listOf(".no-hooks", ".template", dir.name), cacheEntries())
        assertEquals(emptyList<String>(), Files.list(cache.resolve(".no-hooks")).use { it.toList() }.map { it.name })
        if (Files.getFileStore(cache).supportsFileAttributeView("posix")) {
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(cache)))
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)))
        }

        val clone = transport.requests.first()
        assertEquals(GitOp.CLONE, clone.op)
        assertEquals("the first clone may ask (D202)", Interaction.EXPLICIT, clone.interaction)
        assertTrue(clone.progress)
        assertTrue(clone.args.containsAll(listOf("--depth", "1", "--filter=blob:none", "--sparse", "--single-branch", "--no-tags", "--no-recurse-submodules")))
        assertEquals(golden.url, clone.remoteUrl)
        assertTrue("core.hooksPath=${cache.resolve(".no-hooks")}" in clone.config)
        assertTrue("protocol.allow=never" in clone.config && "protocol.file.allow=always" in clone.config)
        assertEquals("ssh:https:git:file", clone.env["GIT_ALLOW_PROTOCOL"])
        val local = transport.requests.filter { it.op == GitOp.LOG || it.op == GitOp.LS_TREE || it.op == GitOp.REV_PARSE }
        assertTrue("local commands never get the URL (no credential callbacks)", local.isNotEmpty() && local.all { it.remoteUrl == null && it.env["GIT_NO_LAZY_FETCH"] == "1" })
        assertTrue("network commands get it", transport.requests.filter { it.op.network }.all { it.remoteUrl == golden.url })
    }

    fun testHistoryDepth() {
        val golden = repo()
        repeat(4) { golden.write("roles/web/tasks/main.yml", "- debug: msg=$it\n").commit("golden: change $it", author = "bob") }
        useGit(golden.url, depth = 3)
        fetchNow()
        assertEquals("X127: three commits", 3, revCount(state().baseDir!!))
        assertEquals(3, state().historyDepth)

        useGit(golden.url, depth = 1)
        transport.clear()
        fetchNow()
        assertEquals("a new depth re-clones", GitOp.CLONE, transport.ops().first())
        assertEquals(1, revCount(state().baseDir!!))
        assertEquals(golden.head(), state().commit)
    }

    fun testARefreshWithoutChangeOnlyAsksTheRemote() {
        val golden = repo()
        useGit(golden.url)
        assertTrue(tick())
        transport.clear()

        later(Duration.ofMinutes(10))
        assertFalse("not due before the interval", tick())
        assertEquals(emptyList<GitOp>(), transport.ops())

        later(Duration.ofMinutes(21))
        assertTrue(tick())
        assertEquals("ls-remote first, no fetch when the commit did not move", listOf(GitOp.LS_REMOTE, GitOp.REV_PARSE, GitOp.LOG), transport.ops())
        assertEquals(Interaction.BACKGROUND, transport.requests.first().interaction)
        assertEquals(now, state().fetchedAt)
        assertEquals(golden.head(), state().commit)
    }

    fun testARefreshFetchesAndChecksOutWhenTheBranchMoved() {
        val golden = repo().allowFilter()
        useGit(golden.url)
        fetchNow()
        val first = state().commit
        golden.write("roles/web/tasks/main.yml", "- name: web v2\n").write("playbooks/new.yml", "- hosts: all\n")
        val moved = golden.commit("golden: web v2", author = "bob")
        transport.clear()

        later(Duration.ofMinutes(31))
        assertTrue(tick())
        assertTrue(transport.ops().toString(), transport.ops().containsAll(listOf(GitOp.LS_REMOTE, GitOp.FETCH, GitOp.CHECKOUT)))
        assertFalse(GitOp.CLONE in transport.ops())
        val state = state()
        assertNull(state.error)
        assertFalse(first == state.commit)
        assertEquals(moved, state.commit)
        assertEquals("bob", state.commitAuthor)
        assertEquals("- name: web v2\n", state.rolesDir!!.resolve("web/tasks/main.yml").readText())
        assertTrue(files(state.baseDir!!).none { it.startsWith("playbooks/") })
        assertEquals(1, revCount(state.baseDir!!))
        assertTrue("a partial clone where the server filters", GoldenMirrorMeta.read(state.baseDir!!)!!.filtered)
    }

    fun testABranchSetting() {
        val golden = repo()
        golden.git("checkout", "-q", "-b", "develop")
        val develop = golden.write("roles/db/tasks/main.yml", "- name: db develop\n").commit("golden: develop")
        golden.git("checkout", "-q", "main")
        useGit(golden.url, ref = "develop")
        fetchNow()
        assertEquals(develop, state().commit)
        assertEquals("${golden.url} (develop)", state().source)
        assertEquals("- name: db develop\n", state().rolesDir!!.resolve("db/tasks/main.yml").readText())

        golden.git("checkout", "-q", "develop")
        val moved = golden.write("roles/db/tasks/main.yml", "- name: db develop 2\n").commit("golden: develop 2")
        later(Duration.ofMinutes(31))
        assertTrue(tick())
        assertEquals("a moved branch updates the commit", moved, state().commit)
    }

    fun testATagSetting() {
        val golden = repo()
        val tagged = golden.head()
        golden.git("-c", "user.name=alice", "-c", "user.email=alice@example.org", "tag", "-a", "v1", "-m", "release 1")
        golden.write("roles/web/tasks/main.yml", "- name: after the tag\n").commit("golden: after")
        useGit(golden.url, ref = "v1")
        fetchNow()
        assertEquals("the tagged commit (peeled)", tagged, state().commit)
        assertEquals("v1", state().ref)

        transport.clear()
        later(Duration.ofMinutes(31))
        assertTrue(tick())
        assertFalse("an unchanged tag fetches nothing", GitOp.FETCH in transport.ops())

        golden.git("-c", "user.name=alice", "-c", "user.email=alice@example.org", "tag", "-f", "-a", "v1", "-m", "release 1 again", "HEAD")
        later(Duration.ofMinutes(31))
        assertTrue(tick())
        assertTrue(GitOp.FETCH in transport.ops())
        assertEquals(golden.head(), state().commit)
        assertNull(state().error)
    }

    fun testANewDefaultBranchReclones() {
        val golden = repo()
        useGit(golden.url)
        fetchNow()
        golden.git("checkout", "-q", "-b", "trunk")
        val trunk = golden.write("roles/web/tasks/main.yml", "- name: trunk\n").commit("golden: trunk")
        transport.clear()
        later(Duration.ofMinutes(31))
        assertTrue(tick())
        assertTrue(transport.ops().toString(), GitOp.CLONE in transport.ops())
        assertEquals(trunk, state().commit)
        assertEquals("${golden.url} (trunk)", state().source)
    }

    fun testTheRolesPathComesFromAnsibleCfgOrTheTopLevel() {
        val nested = GitTestRepo.init(temp.resolve("nested"))
            .write("ansible/roles/web/tasks/main.yml", "- name: web\n")
            .write("docs/readme.md", "golden\n")
            .write("ansible.cfg", "[defaults]\nroles_path = ./ansible/roles:~/.ansible/roles\n")
        nested.commit("golden: nested")
        useGit(nested.url)
        fetchNow()
        assertEquals(state().baseDir!!.resolve("ansible/roles"), state().rolesDir)
        assertTrue(files(state().baseDir!!).none { it.startsWith("docs/") })

        val flat = GitTestRepo.init(temp.resolve("flat"))
            .write("web/tasks/main.yml", "- name: web\n")
            .write("db/tasks/main.yml", "- name: db\n")
        flat.commit("golden: flat")
        useGit(flat.url)
        fetchNow()
        assertNull(state().error)
        assertEquals("the top-level directories are the roles", state().baseDir, state().rolesDir)
        assertEquals(listOf("db/tasks/main.yml", "web/tasks/main.yml"), files(state().baseDir!!))
    }

    fun testAnExplicitRolesPath() {
        val nested = GitTestRepo.init(temp.resolve("nested"))
            .write("ansible/roles/web/tasks/main.yml", "- name: web\n")
            .write("other/x.yml", "x: 1\n")
        nested.commit("golden: nested")
        useGit(nested.url, rolesPath = "missing")
        fetchNow()
        assertEquals(GoldenMirrorTexts.rolesPathNotFound("missing"), state().error)
        assertNull(state().baseDir)
        assertEquals("the failed clone is gone", listOf(".no-hooks", ".template"), cacheEntries())

        useGit(nested.url, rolesPath = "ansible/roles/")
        fetchNow()
        assertNull(state().error)
        assertEquals(state().baseDir!!.resolve("ansible/roles"), state().rolesDir)
        assertTrue(files(state().baseDir!!).none { it.startsWith("other/") })
    }

    fun testAServerThatRefusesTheFilterGetsTheWholeShallowTree() {
        val golden = repo()
        var refused = false
        transport.answer = { request ->
            if (request.op == GitOp.CLONE && "--filter=blob:none" in request.args) {
                refused = true
                RecordingTransport.failure("fatal: filtering not supported by server")
            } else {
                null
            }
        }
        useGit(golden.url)
        fetchNow()
        assertTrue(refused)
        assertEquals(2, transport.ops().count { it == GitOp.CLONE })
        assertNull(state().error)
        assertFalse(GoldenMirrorMeta.read(state().baseDir!!)!!.filtered)
        assertTrue(files(state().baseDir!!).none { it.startsWith("playbooks/") })
    }

    fun testACheckoutThatFailsTwiceReclones() {
        val golden = repo()
        useGit(golden.url)
        fetchNow()
        val moved = golden.write("roles/web/tasks/main.yml", "- name: moved\n").commit("golden: moved")
        transport.answer = { request -> if (request.op == GitOp.CHECKOUT) RecordingTransport.failure("error: unable to read sha1 file") else null }
        transport.clear()
        fetchNow()
        assertEquals(2, transport.ops().count { it == GitOp.CHECKOUT })
        assertTrue(GitOp.CLONE in transport.ops())
        assertEquals(moved, state().commit)
        assertNull(state().error)
    }

    fun testAMirrorIsReclonedAfterManyFetches() {
        val golden = repo()
        useGit(golden.url)
        fetchNow()
        val dir = state().baseDir!!
        GoldenMirrorMeta.read(dir)!!.copy(fetches = GoldenMirrorMeta.RECLONE_AFTER_FETCHES).write(dir)
        transport.clear()
        fetchNow()
        assertEquals(GitOp.CLONE, transport.ops().first())
        assertEquals(0, GoldenMirrorMeta.read(dir)!!.fetches)
    }

    fun testAFailedRecloneKeepsTheLastGoodMirror() {
        val golden = repo()
        useGit(golden.url)
        fetchNow()
        val good = state()
        transport.answer = { request -> if (request.op == GitOp.SPARSE_CHECKOUT) RecordingTransport.failure("fatal: sparse checkout broke") else null }
        useGit(golden.url, depth = 2)
        fetchNow()
        val state = state()
        assertEquals("sparse checkout broke", state.error)
        assertEquals("the last good mirror stays (D196)", good.commit, state.commit)
        assertEquals(good.rolesDir, state.rolesDir)
        assertTrue(state.rolesDir!!.resolve("web/tasks/main.yml").let(Files::isRegularFile))
        assertEquals(1, GoldenMirrorMeta.read(state.baseDir!!)!!.depth)
        assertEquals("no *.partial is left", listOf(".no-hooks", ".template", state.baseDir!!.name), cacheEntries())
    }

    // ------------------------------------------------------------------------------------------------ gates

    fun testAUrlFromSharedSettingsAsksOncePerMachine() {
        val golden = repo()
        useGit(golden.url, consent = null)
        assertTrue(state().needsConsent)
        assertFalse(tick())
        assertFalse(tick())
        assertEquals("nothing contacts the server unasked (D203)", emptyList<GitOp>(), transport.ops())
        val asked = balloons.golden.filter { it.actions.isNotEmpty() }
        assertEquals("one notification, never a modal", 1, asked.size)
        assertTrue(asked.single().content, golden.url in asked.single().content)
        assertEquals(listOf("Fetch", "Not on this machine"), asked.single().actions.map { it.templateText })

        service.consent(false)
        assertEquals(false, GoldenMirrorConsents.getInstance().answer(golden.url))
        assertTrue(state().consentDeclined)
        assertFalse(state().needsConsent)
        assertEquals(GoldenMirrorTexts.declined(), state().pausedReason)
        assertFalse(tick())
        assertEquals(emptyList<GitOp>(), transport.ops())

        service.consent(true)
        PlatformTestUtil.waitWithEventsDispatching("the fetch after consent", { state().commit != null }, 60)
        assertEquals(golden.head(), state().commit)
        assertEquals(true, GoldenMirrorConsents.getInstance().answer(golden.url))
        assertFalse(state().consentDeclined)
        assertNull("the answer is no longer a pause", state().pausedReason)
    }

    fun testFetchNowIsConsent() {
        val golden = repo()
        useGit(golden.url, consent = null)
        fetchNow()
        assertEquals(true, GoldenMirrorConsents.getInstance().answer(golden.url))
        assertEquals(golden.head(), state().commit)
        assertFalse(state().needsConsent)
    }

    fun testUntrustedProjectsNeverFetch() {
        service.configureForTests(trusted = false)
        val golden = repo()
        useGit(golden.url)
        assertFalse(tick())
        assertEquals(GoldenMirrorTexts.untrusted(), state().pausedReason)
        fetchNow()
        assertEquals(GoldenMirrorTexts.untrusted(), state().error)
        assertEquals(ConnectionResult(false, GoldenMirrorTexts.untrusted()), GoldenTestSupport.await { service.testConnection(golden.url, "") })
        assertEquals(emptyList<GitOp>(), transport.ops())

        GoldenMirrorConsents.getInstance().resetForTests()
        useGit(repo("other").url, consent = null)
        assertFalse(state().needsConsent)
        assertFalse(tick())
        assertFalse("no question in untrusted projects", state().needsConsent)
        assertTrue(balloons.golden.isEmpty())
    }

    fun testPowerSaveModeAndOnDemandOnly() {
        val golden = repo()
        service.configureForTests(powerSave = true)
        useGit(golden.url)
        assertFalse(tick())
        assertEquals(GoldenMirrorTexts.powerSave(), state().pausedReason)
        assertEquals(emptyList<GitOp>(), transport.ops())
        fetchNow()
        assertEquals("Fetch Now still works", golden.head(), state().commit)

        service.configureForTests(powerSave = false)
        useGit(golden.url, refreshMinutes = 0)
        golden.write("roles/web/tasks/main.yml", "- name: moved\n").commit("golden: moved")
        transport.clear()
        later(Duration.ofDays(1))
        assertFalse(tick())
        assertEquals("0 = on demand only", emptyList<GitOp>(), transport.ops())
        assertNull(state().pausedReason)
    }

    fun testBackoffAfterFailuresAndOneBalloonPerStreak() {
        val golden = repo()
        failNetwork("fatal: unable to access 'https://alice:s3cret@git.example.org/golden.git/': Could not resolve host: git.example.org")
        useGit(golden.url, refreshMinutes = 5)
        assertTrue(tick())
        assertEquals(1, service.failuresForTests())
        val error = state().error!!
        assertTrue(error, "Could not resolve host" in error)
        assertFalse("never a credential in the state", "s3cret" in error || "alice" in error)
        assertNull(state().baseDir)

        later(Duration.ofMinutes(5))
        assertFalse("backoff: 5 × 2 minutes", tick())
        later(Duration.ofMinutes(5))
        assertTrue(tick())
        assertEquals(2, service.failuresForTests())
        later(Duration.ofMinutes(19))
        assertFalse("backoff: 5 × 4 minutes", tick())
        later(Duration.ofMinutes(1))
        assertTrue(tick())
        val failures = balloons.golden.filter { it.actions.isEmpty() }
        assertEquals("one balloon per failure streak (D196)", 1, failures.size)
        assertFalse(failures.single().content, "s3cret" in failures.single().content)

        transport.answer = null
        later(Duration.ofMinutes(40))
        assertTrue(tick())
        assertNull(state().error)
        assertEquals(0, service.failuresForTests())
        failNetwork("fatal: unable to access 'https://git.example.org/golden.git/': Could not resolve host: git.example.org")
        later(Duration.ofMinutes(5))
        assertTrue(tick())
        assertEquals("a new streak, a new balloon", 2, balloons.golden.count { it.actions.isEmpty() })
    }

    fun testABackgroundAuthenticationFailurePausesUntilAnExplicitSuccess() {
        val golden = repo()
        useGit(golden.url)
        fetchNow()
        failNetwork("git@git.example.org: Permission denied (publickey).", "fatal: Could not read from remote repository.")
        later(Duration.ofMinutes(31))
        assertTrue(tick())
        assertEquals(Interaction.BACKGROUND, transport.requests.last().interaction)
        assertEquals(GoldenMirrorTexts.authPaused(), state().pausedReason)
        assertEquals("git@git.example.org: Permission denied (publickey)", state().error)
        assertEquals("the last good mirror stays", golden.head(), state().commit)

        transport.clear()
        later(Duration.ofHours(3))
        assertFalse(tick())
        assertEquals("paused for the URL", emptyList<GitOp>(), transport.ops())

        fetchNow(interactive = true)
        assertEquals("an explicit failure does not lift the pause", GoldenMirrorTexts.authPaused(), state().pausedReason)

        transport.answer = null
        fetchNow(interactive = false)
        assertNull(state().pausedReason)
        assertNull(state().error)
        transport.clear()
        later(Duration.ofMinutes(31))
        assertTrue(tick())
        assertEquals(GitOp.LS_REMOTE, transport.ops().first())
    }

    fun testAnExternalSshAgentStopsAutomaticRefreshesOfSshUrls() {
        service.configureForTests(externalAgent = true)
        failNetwork("ssh: Could not resolve hostname git.example.org")
        useGit("git@git.example.org:infra/golden.git")
        assertFalse(tick())
        assertEquals(GoldenMirrorTexts.sshAgent(), state().pausedReason)
        assertEquals(emptyList<GitOp>(), transport.ops())

        useGit("https://git.example.org/infra/golden.git")
        assertTrue("other URLs are refreshed", tick())
        assertNull(state().pausedReason)
    }

    fun testAnInvalidUrlIsNeverFetched() {
        useGit("https://alice:token@git.example.org/infra/golden.git")
        assertFalse(tick())
        assertEquals(GoldenMirrorTexts.urlProblem(GoldenGitUrls.Problem.CREDENTIALS), state().error)
        fetchNow()
        assertEquals(emptyList<GitOp>(), transport.ops())
        assertFalse("the source shows no credentials", "token" in state().source)
        useGit("ext::sh -c touch% /tmp/pwned")
        fetchNow()
        assertEquals(GoldenMirrorTexts.urlProblem(GoldenGitUrls.Problem.TRANSPORT), state().error)
        assertEquals(emptyList<GitOp>(), transport.ops())
    }

    fun testTestConnection() {
        val golden = repo()
        golden.git("tag", "light")
        val head = golden.head().take(7)
        fun test(url: String, ref: String) = GoldenTestSupport.await { service.testConnection(url, ref) }
        assertEquals(ConnectionResult(true, GoldenMirrorTexts.connectedDefault("main", head)), test(golden.url, ""))
        assertEquals(ConnectionResult(true, GoldenMirrorTexts.connectedRef(false, "main", head)), test(golden.url, "main"))
        assertEquals(ConnectionResult(true, GoldenMirrorTexts.connectedRef(true, "light", head)), test(golden.url, "light"))
        assertEquals(ConnectionResult(false, GoldenMirrorTexts.connectedNoRef("nope", "main")), test(golden.url, "nope"))
        assertTrue(transport.requests.all { it.op == GitOp.LS_REMOTE && it.interaction == Interaction.EXPLICIT })
        transport.clear()
        assertEquals(ConnectionResult(false, GoldenMirrorTexts.urlProblem(GoldenGitUrls.Problem.CREDENTIALS)), test("https://a:b@git.example.org/x.git", ""))
        assertFalse(test(temp.resolve("nothing").toUri().toString(), "").success)
        assertEquals(1, transport.requests.size)
    }

    // ------------------------------------------------------------------------------------------------ clean-up

    fun testTheOldMirrorGoesWhenTheSettingChanges() {
        val first = repo("first")
        val second = repo("second")
        useGit(first.url)
        fetchNow()
        val old = state().baseDir!!
        useGit(second.url)
        assertFalse("the old mirror is deleted on change", Files.exists(old))
        fetchNow()
        assertEquals(listOf(".no-hooks", ".template", GoldenGitUrls.mirrorKey(second.url, "")), cacheEntries())
        val current = state().baseDir!!
        MirrorSettings.useNone(project)
        GoldenTestSupport.await { service.reconcileForTests() }
        assertNull(service.state())
        assertFalse(Files.exists(current))
    }

    fun testLeftoversAndStaleMirrorsGoAtStartNeverTheCurrentOne() {
        val golden = repo()
        useGit(golden.url)
        fetchNow()
        val current = state().baseDir!!
        fun mirror(name: String, age: Duration?): Path {
            val dir = cache.resolve(name)
            Files.createDirectories(dir.resolve(".git"))
            if (age != null) {
                val stamp = Files.writeString(GoldenMirrorMeta.fileOf(dir), "url=x\ndepth=1\n")
                Files.setLastModifiedTime(stamp, FileTime.from(now.minus(age)))
            }
            return dir
        }
        val stale = mirror("aaaaaaaaaaaaaaaa", Duration.ofDays(30))
        val recent = mirror("bbbbbbbbbbbbbbbb", Duration.ofDays(1))
        val partial = mirror("cccccccccccccccc.partial", null)
        val old = mirror("dddddddddddddddd.old", null)
        val currentPartial = mirror(current.name + ".partial", null)
        GoldenMirrorMeta.fileOf(current).let { Files.setLastModifiedTime(it, FileTime.from(now.minus(Duration.ofDays(60)))) }

        GoldenTestSupport.await { service.cleanUpForTests(atStart = true) }
        assertFalse(Files.exists(stale))
        assertTrue("another project may still use it", Files.exists(recent))
        assertFalse(Files.exists(partial))
        assertFalse(Files.exists(old))
        assertTrue("never the current one", current.isDirectory())
        assertTrue("nor its clone in progress", Files.exists(currentPartial))
    }

    // ------------------------------------------------------------------------------------------------ folder, listener

    fun testAFolderOutsideTheProject() {
        val withRoles = Files.createDirectories(temp.resolve("checkout/golden/roles/web/tasks"))
        MirrorSettings.useFolder(project, temp.resolve("checkout/golden").toString())
        GoldenTestSupport.await { service.reconcileForTests() }
        var state = state()
        assertEquals(Kind.FOLDER, state.kind)
        assertEquals(temp.resolve("checkout/golden"), state.baseDir)
        assertEquals(withRoles.parent.parent, state.rolesDir)
        assertEquals("golden", state.name)
        assertEquals(temp.resolve("checkout/golden").toString(), state.source)
        assertNull(state.commit)
        assertNull(state.error)
        assertFalse(state.needsConsent)
        assertFalse(tick())
        fetchNow()
        assertEquals("a folder is never fetched", emptyList<GitOp>(), transport.ops())

        val flat = Files.createDirectories(temp.resolve("flat/web"))
        MirrorSettings.useFolder(project, flat.parent.toString())
        GoldenTestSupport.await { service.reconcileForTests() }
        assertEquals("without roles/ the folder itself", flat.parent, state().rolesDir)

        MirrorSettings.useFolder(project, temp.resolve("missing").toString())
        GoldenTestSupport.await { service.reconcileForTests() }
        state = state()
        assertNull(state.rolesDir)
        assertNull(state.baseDir)
        assertEquals(GoldenMirrorTexts.folderMissing(temp.resolve("missing").toString()), state.error)

        MirrorSettings.useFolder(project, "relative/golden")
        GoldenTestSupport.await { service.reconcileForTests() }
        assertEquals(GoldenMirrorTexts.folderInvalid(), state().error)
    }

    fun testListenersHearEveryChange() {
        val events = CopyOnWriteArrayList<GoldenMirrorState?>()
        project.messageBus.connect(testRootDisposable).subscribe(GoldenMirrorListener.TOPIC, GoldenMirrorListener { events += it })
        val golden = repo()
        useGit(golden.url)
        fetchNow()
        MirrorSettings.useNone(project)
        GoldenTestSupport.await { service.reconcileForTests() }
        PlatformTestUtil.waitWithEventsDispatching("the null state", { events.lastOrNull() == null && events.size >= 3 }, 30)
        assertTrue("fetching was published", events.any { it?.fetching == true })
        assertTrue("the result was published", events.any { it?.commit == golden.head() && !it.fetching })
        assertNull(events.last())
    }

    fun testTheSettingIsDriftOnly() {
        val settings = AnsibilityProjectSettings.getInstance(project)
        val main = settings.modificationTracker.modificationCount
        val drift = settings.driftModificationTracker.modificationCount
        useGit("git@git.example.org:infra/golden.git", consent = null)
        MirrorSettings.useFolder(project, "/srv/golden")
        assertEquals(main, settings.modificationTracker.modificationCount)
        assertTrue(settings.driftModificationTracker.modificationCount > drift)
    }
}
