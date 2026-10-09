package de.terletzkiy.ansibility.toolwindow

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.golden.remote.GoldenMirrorState
import de.terletzkiy.ansibility.golden.remote.GoldenMirrorTexts
import de.terletzkiy.ansibility.toolwindow.ExternalGoldenHeader.Link
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * The Roles tab's header line for the external golden root in each state of the mirror (plan amendment R25,
 * D195/D196/D203): fetched, folder, fetching (first clone and refresh), failed with the last good mirror in use,
 * paused (SSH agent: Allow background refreshes), waiting for this machine's OK; the relative times move on.
 */
class ExternalGoldenHeaderTest : BasePlatformTestCase() {
    private val now = Instant.parse("2026-10-09T10:00:00Z")
    private val commit = "3f2a1c9d0e1f2a3b4c5d6e7f8091a2b3c4d5e6f7"

    private fun git(
        commit: String? = this.commit,
        fetchedAt: Instant? = now.minus(Duration.ofMinutes(2)),
        fetching: Boolean = false,
        error: String? = null,
        paused: String? = null,
        consent: Boolean = false,
    ) = GoldenMirrorState(
        kind = GoldenMirrorState.Kind.GIT,
        rolesDir = Path.of("/cache/golden/roles").takeIf { commit != null },
        baseDir = Path.of("/cache/golden").takeIf { commit != null },
        name = "golden",
        source = "git@git.example.org:infra/golden.git (main)",
        commit = commit,
        commitInstant = now.minus(Duration.ofDays(1)),
        commitAuthor = "alice",
        commitSubject = "fix verify",
        fetchedAt = fetchedAt,
        fetching = fetching,
        error = error,
        pausedReason = paused,
        needsConsent = consent,
        ref = "main",
        historyDepth = 1,
    )

    private fun line(state: GoldenMirrorState?, names: Int? = 41, drifting: String? = "6 drifting", errorSince: Instant? = null, at: Instant = now) =
        ExternalGoldenHeader.compose(state, names, drifting, errorSince, at)

    private fun links(line: ExternalGoldenHeader.Line): List<String> = line.links.map(ExternalGoldenHeader::text)

    fun testFetched() {
        val line = line(git())
        assertEquals("Golden root: golden (git, main @ 3f2a1c9, fetched 2 min ago) · 41 names · 6 drifting", line.text)
        assertEquals(listOf("Fetch Now", "Change…"), links(line))
        assertEquals("the time moves on", "Golden root: golden (git, main @ 3f2a1c9, fetched 1 h ago) · 41 names · 6 drifting", line(git(), at = now.plus(Duration.ofHours(1))).text)
    }

    fun testFolder() {
        val home = System.getProperty("user.home")
        val folder = GoldenMirrorState(GoldenMirrorState.Kind.FOLDER, Path.of(home, "src/golden/roles"), Path.of(home, "src/golden"), "golden", "$home/src/golden", null, null, null, null, null, false, null, null, false)
        val line = line(folder)
        assertEquals("Golden root: ~/src/golden (folder) · 41 names · 6 drifting", line.text)
        assertEquals(listOf("Change…"), links(line))
        val missing = folder.copy(rolesDir = null, baseDir = null, error = GoldenMirrorTexts.folderMissing("~/src/gone"))
        assertEquals("Golden root: Folder not found: ~/src/gone", line(missing, names = null, drifting = null).text)
    }

    fun testFetching() {
        val first = line(git(commit = null, fetchedAt = null, fetching = true), names = null, drifting = null)
        assertEquals("Golden root: fetching golden…", first.text)
        assertEquals(listOf("Change…"), links(first))
        val refresh = line(git(fetching = true))
        assertEquals("Golden root: golden (git, main @ 3f2a1c9, fetched 2 min ago) · fetching golden… · 41 names · 6 drifting", refresh.text)
        assertEquals(listOf("Change…"), links(refresh))
        val none = line(git(commit = null, fetchedAt = null), names = null, drifting = null)
        assertEquals("Golden root: golden (git, not fetched yet)", none.text)
        assertEquals(listOf("Fetch Now", "Change…"), links(none))
    }

    fun testFailedKeepsTheLastGoodMirror() {
        val line = line(git(fetchedAt = now.minus(Duration.ofHours(2)), error = "Permission denied (publickey)"), errorSince = now.minus(Duration.ofMinutes(3)))
        assertEquals(
            "Golden root: golden (git, as of 3f2a1c9, 2 h ago) · 41 names · 6 drifting · golden: fetch failed 3 min ago (Permission denied (publickey))",
            line.text,
        )
        assertEquals(listOf("Retry", "Change…"), links(line))
        val never = line(git(commit = null, fetchedAt = null, error = "Could not resolve host: git.example.org"), names = null, drifting = null, errorSince = now)
        assertEquals("Golden root: golden (git, not fetched yet) · golden: fetch failed just now (Could not resolve host: git.example.org)", never.text)
    }

    fun testPaused() {
        val agent = line(git(paused = GoldenMirrorTexts.sshAgent()))
        assertEquals("Golden root: golden (git, main @ 3f2a1c9, fetched 2 min ago) · 41 names · 6 drifting · ${GoldenMirrorTexts.sshAgent()}", agent.text)
        assertEquals(listOf("Fetch Now", "Allow background refreshes", "Change…"), links(agent))
        assertEquals(listOf(Link.FETCH_NOW, Link.CHANGE), line(git(paused = GoldenMirrorTexts.authPaused())).links)
        assertEquals(listOf(Link.FETCH_NOW, Link.CHANGE), line(git(paused = GoldenMirrorTexts.declined())).links)
    }

    fun testWaitingForConsent() {
        val line = line(git(commit = null, fetchedAt = null, consent = true), names = null, drifting = null)
        assertEquals("Waiting for your OK to fetch from git@git.example.org:infra/golden.git (main)", line.text)
        assertEquals(listOf("Fetch", "Not on this machine", "Change…"), links(line))
    }
}
