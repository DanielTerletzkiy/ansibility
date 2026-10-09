package de.terletzkiy.ansibility.golden.remote

import com.intellij.notification.Notification
import de.terletzkiy.ansibility.golden.GoldenTestSupport
import de.terletzkiy.ansibility.golden.align.AlignTestSupport
import de.terletzkiy.ansibility.golden.history.LastChange
import de.terletzkiy.ansibility.golden.history.LastChangeTexts
import de.terletzkiy.ansibility.golden.history.LastChanges
import de.terletzkiy.ansibility.model.drift.DriftFixture
import de.terletzkiy.ansibility.model.drift.DriftTier
import de.terletzkiy.ansibility.model.role.ExternalGoldenRoot
import de.terletzkiy.ansibility.model.role.RoleCatalog
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * The git mirror as the golden root (plan amendment R25 step 2) through the real mirror service and `file://`
 * repositories: the golden copies follow a fetch, the golden side's last change (D200, X127 with depth 1 and 3), X128's
 * "golden moved" notification (once, only when a tier of the project's copies changed, never for the first clone), the
 * banner, and the SSH-agent opt-in that lifts the background pause.
 */
class ExternalGoldenMirrorTest : ExternalGoldenTestCase() {
    private lateinit var service: GoldenMirrorService
    private lateinit var transport: RecordingTransport
    private lateinit var notifications: MutableList<Notification>
    private val shown = ArrayList<String>()

    @Volatile
    private var now: Instant = Instant.parse("2026-10-09T10:00:00Z")

    override fun setUp() {
        super.setUp()
        transport = RecordingTransport()
        service = GoldenMirrorService.getInstance(project)!!
        service.resetForTests()
        service.configureForTests(base = temp.resolve("cache"), transport = transport, now = { now }, trusted = true, powerSave = false, externalAgent = false)
        notifications = AlignTestSupport.notifications(project, testRootDisposable)
        GoldenMovedNotifier.getInstance(project).show = { shown += "roles" }
    }

    /** A repository with the drift tree's golden roles (`roles/web`, `roles/base`), one commit by alice. */
    private fun goldenRepo(): GitTestRepo {
        val repo = GitTestRepo.init(temp.resolve("remote").resolve("golden"))
        copyTree(Paths.get(de.terletzkiy.ansibility.model.role.ModelFixture.testDataPath, DriftFixture.DRIFT, "golden"), repo.dir)
        repo.commit("golden: first", author = "alice")
        return repo
    }

    private fun useMirror(repo: GitTestRepo, depth: Int = 1) {
        GoldenMirrorConsents.getInstance().set(repo.url, true)
        MirrorSettings.useGit(project, repo.url, depth = depth)
        GoldenTestSupport.await { service.reconcileForTests() }
        fetch()
    }

    /** Fetch Now (silent) and the resolution that follows it. */
    private fun fetch() {
        now = now.plus(Duration.ofMinutes(31))
        GoldenTestSupport.await { service.fetchNowAndWait(false) }
        assertNull("fetch failed: ${service.state()?.error}", service.state()?.error)
        sync()
    }

    private fun mirrorDir(): Path = service.state()?.baseDir ?: error("no mirror")

    private fun moved(): List<Notification> = notifications.filter { it.content.contains(" moved to ") }

    private fun awaitReport(after: Long) {
        DriftFixture.waitFor("the golden-moved report") { GoldenMovedNotifier.getInstance(project).reportCount > after }
    }

    private fun tier(team: String, role: String = "web"): DriftTier = DriftFixture.drift(drift, role).copyOf(localCopy(team, role).dir)!!.tier

    // ------------------------------------------------------------------------------------------------ catalog and drift

    fun testTheMirrorsRolesAreTheGoldenCopiesAndFollowAFetch() {
        val repo = goldenRepo()
        useMirror(repo)
        val catalog = RoleCatalog.getInstance(project).snapshot()
        val external = catalog.external ?: error("no external golden")
        assertEquals(mirrorDir().resolve("roles").toString(), external.rolesDir.path)
        assertTrue(catalog.copies("web").first().isExternal)
        assertEquals(DriftTier.IDENTICAL, tier("same"))
        assertEquals(DriftTier.BEHAVIOUR, tier("tasks"))

        repo.write("roles/web/tasks/main.yml", "- name: new golden task\n  debug: msg=new\n").commit("golden: new task", author = "bob")
        fetch()
        assertEquals("the new commit reaches drift", DriftTier.BEHAVIOUR, tier("same"))
        val banner = GoldenMirrorBanner.text(project, goldenVf("roles/web/tasks/main.yml", mirrorDir())) ?: error("no banner")
        assertEquals("Golden mirror of ${repo.url} (main @ ${repo.head().take(7)}) — read-only; change it in the golden repository", banner)
    }

    // ------------------------------------------------------------------------------------------------ X127 / D200

    fun testDepthOneGivesTheFetchedCommit() {
        val repo = goldenRepo()
        useMirror(repo)
        val file = goldenVf("roles/web/tasks/main.yml", mirrorDir())
        LastChanges.getInstance(project).clearForTests()
        val change = GoldenTestSupport.pooled { LastChanges.getInstance(project).lastChange(file) } ?: error("no last change")
        assertEquals(LastChange.Kind.FETCHED_COMMIT, change.kind)
        assertEquals("alice", change.author)
        assertEquals("golden: first", change.subject)
        assertEquals(repo.head().take(8), change.shortHash)
        assertTrue(LastChangeTexts.line("golden", change), LastChangeTexts.line("golden", change).startsWith("golden: fetched commit · "))
        assertTrue("no git ran for it", transport.requests.none { it.op == GitOp.LOG && "--" in it.args })
        val dir = GoldenTestSupport.pooled { LastChanges.getInstance(project).lastChangeUnder(goldenVf("roles/web", mirrorDir())) }
        assertEquals(LastChange.Kind.FETCHED_COMMIT, dir?.kind)
    }

    fun testDepthThreeGivesTheRealLastChangeWithinTheFetchedHistory() {
        val repo = goldenRepo()
        repo.write("roles/web/tasks/main.yml", "- debug: msg=two\n").commit("golden: two", author = "bob")
        repo.write("roles/base/defaults/main.yml", "base_value: 3\n").commit("golden: three", author = "carol")
        repo.write("roles/web/defaults/main.yml", "web_port: 8080\n").commit("golden: four", author = "dave")
        useMirror(repo, depth = 3)
        assertEquals(3, GitTestRepo.revCount(mirrorDir()))
        val changes = LastChanges.getInstance(project)
        changes.clearForTests()

        val defaults = GoldenTestSupport.pooled { changes.lastChange(goldenVf("roles/web/defaults/main.yml", mirrorDir())) } ?: error("none")
        assertEquals(LastChange.Kind.COMMIT, defaults.kind)
        assertEquals("dave", defaults.author)
        assertEquals("golden: four", defaults.subject)
        val base = GoldenTestSupport.pooled { changes.lastChange(goldenVf("roles/base/defaults/main.yml", mirrorDir())) } ?: error("none")
        assertEquals("carol", base.author)
        assertEquals(LastChange.Kind.COMMIT, base.kind)

        val meta = GoldenTestSupport.pooled { changes.lastChange(goldenVf("roles/web/meta/main.yml", mirrorDir())) } ?: error("none")
        assertEquals("not changed in the 3 fetched commits", LastChange.Kind.BEFORE_HISTORY, meta.kind)
        val boundary = repo.git("log", "-1", "--format=%aI", "HEAD~2").trim()
        assertEquals(OffsetDateTime.parse(boundary).toInstant(), meta.date)
        assertTrue(LastChangeTexts.line("golden", meta), LastChangeTexts.line("golden", meta).startsWith("golden: older than the fetched history ("))
        val local = transport.requests.filter { it.op == GitOp.LOG && "--" in it.args }
        assertTrue("local only: $local", local.isNotEmpty() && local.all { it.remoteUrl == null && it.env["GIT_NO_LAZY_FETCH"] == "1" && it.env["GIT_LITERAL_PATHSPECS"] == "1" })
    }

    fun testTheDirectionHintTrustsAnUpperBoundOnlyWhenTheOtherSideIsLater() {
        val bound = LastChange("alice", Instant.parse("2026-09-12T10:00:00Z"), "s", "abc", LastChange.Kind.FETCHED_COMMIT)
        val later = LastChange("bob", Instant.parse("2026-10-01T10:00:00Z"), "s", "def")
        val earlier = LastChange("bob", Instant.parse("2026-08-01T10:00:00Z"), "s", "def")
        assertEquals("heron changed this file more recently (2026-10-01) than golden (not after 2026-09-12)", LastChangeTexts.direction("golden", bound, "heron", later, file = true))
        assertNull("golden's real change may be later", LastChangeTexts.direction("golden", bound, "heron", earlier, file = true))
        assertNull(LastChangeTexts.direction("golden", bound, "other", bound.copy(kind = LastChange.Kind.BEFORE_HISTORY), file = false))
        assertEquals("golden changed this role more recently (2026-09-12) than heron (2026-08-01)", LastChangeTexts.direction("golden", bound.copy(kind = LastChange.Kind.COMMIT), "heron", earlier, file = false))
    }

    // ------------------------------------------------------------------------------------------------ X128

    fun testGoldenMovedFiresOnceAndOnlyWhenATierChanged() {
        val repo = goldenRepo()
        useMirror(repo)
        assertEquals(DriftTier.IDENTICAL, tier("same"))
        assertEquals(DriftTier.IDENTICAL, tier("same", "base"))
        val notifier = GoldenMovedNotifier.getInstance(project)
        assertTrue("never for the first clone", moved().isEmpty())
        assertEquals(0L, notifier.reportCount)

        // A commit outside the roles: no tier changes, no notification.
        repo.write("README.md", "golden\n").commit("docs")
        var reports = notifier.reportCount
        fetch()
        awaitReport(reports)
        assertTrue(moved().isEmpty())

        // web changes: the project's identical copy now differs.
        repo.write("roles/web/tasks/main.yml", "- name: new golden task\n  debug: msg=new\n").commit("golden: web")
        reports = notifier.reportCount
        fetch()
        awaitReport(reports)
        val commit = repo.head().take(7)
        assertEquals(moved().map { it.content }.toString(), 1, moved().size)
        assertEquals("golden main moved to $commit: 1 role changed — web now differs here", moved().single().content)
        assertEquals(listOf("Show"), AlignTestSupport.buttons(moved().single()))
        AlignTestSupport.click(project, moved().single(), "Show")
        assertEquals(listOf("roles"), shown)

        // A refresh without a new commit: nothing more.
        reports = notifier.reportCount
        now = now.plus(Duration.ofMinutes(31))
        GoldenTestSupport.await { service.fetchNowAndWait(false) }
        sync()
        assertEquals("no new commit, no report", reports, notifier.reportCount)
        assertEquals(1, moved().size)
    }

    fun testGoldenMovedNamesCopiesThatNowMatchAndOnePhrasePerRole() {
        val repo = goldenRepo()
        useMirror(repo)
        assertEquals("golden has no app-same yet", DriftTier.NO_REFERENCE, tier("same", "app-same"))
        assertEquals(DriftTier.BEHAVIOUR, tier("tasks"))
        // golden takes the project's app-same, and the tasks repo's tasks/main.yml for web.
        copyTree(projectDir.resolve(DriftFixture.roleDir("same", "app-same")), repo.dir.resolve("roles/app-same"))
        repo.write("roles/web/tasks/main.yml", text(projectDir.resolve("${DriftFixture.roleDir("tasks")}/tasks/main.yml")))
        repo.commit("golden: take app-same and the tasks repo's web task")
        val reports = GoldenMovedNotifier.getInstance(project).reportCount
        fetch()
        awaitReport(reports)
        assertEquals(DriftTier.IDENTICAL, tier("same", "app-same"))
        assertEquals("the tasks copy matches now", DriftTier.IDENTICAL, tier("tasks"))
        assertEquals("the same copy differs now", DriftTier.BEHAVIOUR, tier("same"))
        assertEquals(
            "web: one phrase, the weightiest (a copy now differs)",
            "golden main moved to ${repo.head().take(7)}: 2 roles changed — web now differs here; app-same now matches golden here",
            moved().single().content,
        )
    }

    // ------------------------------------------------------------------------------------------------ SSH agent

    fun testTheSshAgentOptInLiftsThePause() {
        service.configureForTests(externalAgent = true)
        val url = "ssh://git@example.invalid/infra/golden.git"
        GoldenMirrorConsents.getInstance().set(url, true)
        MirrorSettings.useGit(project, url)
        GoldenTestSupport.await { service.reconcileForTests() }
        transport.answer = { request -> if (request.op.network) RecordingTransport.failure("ssh: Could not resolve hostname example.invalid") else null }

        assertFalse("paused for the agent", GoldenTestSupport.await { service.tick() })
        assertEquals(GoldenMirrorTexts.sshAgent(), service.state()?.pausedReason)
        assertTrue(transport.requests.isEmpty())

        GoldenMirrorConsents.getInstance().sshAgentRefresh = true
        assertTrue("a background refresh runs through the agent", GoldenTestSupport.await { service.tick() })
        assertTrue(transport.requests.any { it.op.network })
        assertFalse(GoldenMirrorTexts.sshAgent() == service.state()?.pausedReason)
        assertNotNull(ExternalGoldenRoot.getInstance(project))
    }
}
