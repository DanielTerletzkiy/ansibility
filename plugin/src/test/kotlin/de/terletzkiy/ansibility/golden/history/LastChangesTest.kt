package de.terletzkiy.ansibility.golden.history

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.golden.GoldenTestSupport
import de.terletzkiy.ansibility.golden.vcs.impl.VcsLastChangeLookup
import de.terletzkiy.ansibility.model.drift.DriftFixture
import de.terletzkiy.ansibility.model.role.ModelFixture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/**
 * [LastChanges] (plan amendment R24, D183): caching per file stamp and per directory, invalidation by the lookup's
 * change events, the time budget of diff titles, never blocking the EDT, and no lookup at all (an IDE without VCS).
 * Each test uses a fresh service over a fake [LastChangeLookup].
 */
class LastChangesTest : BasePlatformTestCase() {
    private lateinit var changes: LastChanges

    override fun getTestDataPath(): String = ModelFixture.testDataPath

    override fun setUp() {
        super.setUp()
        DriftFixture.copy(myFixture)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        changes = LastChanges(project, scope)
        Disposer.register(testRootDisposable, changes)
        Disposer.register(testRootDisposable) { scope.cancel() }
    }

    private fun vf(relative: String): VirtualFile = DriftFixture.file(myFixture, "${DriftFixture.roleDir("golden")}/$relative")

    private fun use(vararg lookups: LastChangeLookup) = ExtensionTestUtil.maskExtensions(LastChangeLookup.EP_NAME, lookups.toList(), testRootDisposable)

    private fun lastChange(file: VirtualFile): LastChange? = GoldenTestSupport.pooled { changes.lastChange(file) }

    private fun lastChangeUnder(dir: VirtualFile): LastChange? = GoldenTestSupport.pooled { changes.lastChangeUnder(dir) }

    fun testWithoutALookupThereIsNoLastChange() {
        use()
        val file = vf("tasks/main.yml")
        assertNull(lastChange(file))
        assertNull(lastChangeUnder(file.parent.parent))
        assertNull(GoldenTestSupport.pooled { changes.lastChanges(listOf(file, null), 100.milliseconds) }.first())
        assertNull(changes.cached(file))
        assertEquals(0, changes.lookupCount)
    }

    fun testAnswersAreCachedPerFileStamp() {
        val fake = CountingLookup()
        use(fake)
        val file = vf("tasks/main.yml")
        assertEquals(ALICE, lastChange(file))
        assertEquals(ALICE, lastChange(file))
        assertEquals(ALICE, changes.cached(file))
        assertEquals("one lookup for two queries", 1, fake.files.get())

        DriftFixture.write(myFixture, "${DriftFixture.roleDir("golden")}/tasks/main.yml", "---\n# edited\n")
        assertNull("the cached answer was for the old stamp", changes.cached(file))
        assertEquals(ALICE, lastChange(file))
        assertEquals(2, fake.files.get())
        assertEquals(2, changes.lookupCount)
    }

    fun testDirectoriesAreCachedUntilTheLookupReportsAChange() {
        val fake = CountingLookup()
        use(fake)
        val role = DriftFixture.file(myFixture, DriftFixture.roleDir("golden"))
        val file = vf("tasks/main.yml")
        assertEquals(BOB, lastChangeUnder(role))
        assertEquals(BOB, lastChangeUnder(role))
        assertEquals(1, fake.dirs.get())
        assertEquals(ALICE, lastChange(file))

        fake.changed!!(file)
        assertNull("a change below the role drops the role's answer", changes.cachedUnder(role))
        assertNull(changes.cached(file))
        assertEquals(BOB, lastChangeUnder(role))
        assertEquals(2, fake.dirs.get())

        fake.changed!!(null)
        assertNull("a change of many files drops everything", changes.cachedUnder(role))
        assertEquals(BOB, lastChangeUnder(role))
        assertEquals(3, fake.dirs.get())
    }

    fun testTheTitleBudgetDoesNotWaitForASlowLookupButItsAnswerIsKept() {
        val release = CountDownLatch(1)
        val fake = CountingLookup(release)
        use(fake)
        val file = vf("tasks/main.yml")
        val other = vf("defaults/main.yml")
        val started = System.nanoTime()
        val answers = GoldenTestSupport.pooled { changes.lastChanges(listOf(file, null, other), 200.milliseconds) }
        assertEquals(listOf(null, null, null), answers)
        assertTrue("returned within the budget", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5_000)
        release.countDown()
        GoldenTestSupport.waitFor("the slow answer arrives") { changes.cached(file) != null && changes.cached(other) != null }
        assertEquals(listOf(ALICE, null, ALICE), GoldenTestSupport.pooled { changes.lastChanges(listOf(file, null, other), 200.milliseconds) })
        assertEquals("the waits did not start new lookups", 2, fake.files.get())
    }

    fun testTheEdtGetsTheCacheOnlyAndNeverStartsALookup() {
        val fake = CountingLookup()
        use(fake)
        val file = vf("tasks/main.yml")
        assertNull(changes.lastChange(file))
        assertNull(changes.lastChanges(listOf(file), 1000.milliseconds).single())
        assertEquals(0, fake.files.get())
        assertEquals(ALICE, lastChange(file))
        assertEquals("cached answers are fine on the EDT", ALICE, changes.lastChange(file))
    }

    fun testAFailingLookupGivesNoAnswerAndTheNextOneIsAsked() {
        val failing = object : LastChangeLookup {
            override fun lastChange(project: Project, file: VirtualFile): LastChange = throw IllegalStateException("no repository")
            override fun lastChangeUnder(project: Project, dir: VirtualFile): LastChange? = null
        }
        use(failing, CountingLookup())
        assertEquals(ALICE, lastChange(vf("tasks/main.yml")))
    }

    // ------------------------------------------------------------------ review fix P2: status refreshes

    fun testAReportedChangeKeepsTheAnswerKnownWhileItIsRevalidated() {
        val fake = CountingLookup()
        use(fake)
        val role = DriftFixture.file(myFixture, DriftFixture.roleDir("golden"))
        assertEquals(BOB, lastChangeUnder(role))
        repeat(3) { fake.changed!!(null) }
        assertNull("not fresh after the report", changes.cachedUnder(role))
        assertEquals("but still known: the details keep showing it", KnownLastChange(BOB, fresh = false), changes.known(role, directory = true))
        assertEquals(BOB, lastChangeUnder(role))
        assertEquals("revalidated once", 2, fake.dirs.get())
        assertEquals(KnownLastChange(BOB, fresh = true), changes.known(role, directory = true))
        assertEquals("fresh answers are not looked up again", BOB, lastChangeUnder(role))
        assertEquals(2, fake.dirs.get())
    }

    fun testAnAnswerThatArrivesAfterAReportIsKeptAsTheLatestKnown() {
        val fake = CountingLookup()
        fake.duringLookup = { fake.changed!!(null) }
        use(fake)
        val role = DriftFixture.file(myFixture, DriftFixture.roleDir("golden"))
        assertEquals(BOB, lastChangeUnder(role))
        assertEquals("a status refresh during the lookup leaves the answer known, not lost", KnownLastChange(BOB, fresh = false), changes.known(role, directory = true))
    }

    fun testTitlesShowTheLastKnownAnswerWhileItIsRevalidated() {
        val release = CountDownLatch(1)
        val fake = CountingLookup()
        use(fake)
        val file = vf("tasks/main.yml")
        assertEquals(ALICE, lastChange(file))
        fake.changed!!(file)
        fake.release = release
        try {
            val answers = GoldenTestSupport.pooled { changes.lastChanges(listOf(file), 200.milliseconds) }
            assertEquals("the slow revalidation does not blank the title", listOf(ALICE), answers)
        } finally {
            release.countDown()
        }
    }

    // ------------------------------------------------------------------ review fix P3: no backlog

    fun testACancelledWaitCancelsALookupNobodyElseWaitsFor() {
        val fake = BlockingLookup()
        use(fake)
        val dirs = listOf("golden", "missing", "mol", "mol2", "same", "spec", "specmol", "tasks").map { DriftFixture.file(myFixture, DriftFixture.roleDir(it)) } +
            listOf("base", "solo").map { DriftFixture.file(myFixture, DriftFixture.roleDir("same", it)) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            // Arrow-key navigation: ten selections, each waiting for its role's last change, each cancelled by the next.
            val waits = dirs.map { dir -> scope.launch { coroutineToIndicator { _ -> changes.lastChangeUnder(dir) } } }
            GoldenTestSupport.waitFor("two lookups run") { fake.started.size >= 2 }
            waits.forEach { it.cancel() }
            val last = DriftFixture.file(myFixture, DriftFixture.roleDir("tasks", "solo"))
            fake.answered += last
            val answer = ApplicationManager.getApplication().executeOnPooledThread<LastChange?> { changes.lastChangeUnder(last) }
            assertEquals("the newest selection is answered while the old lookups still block", BOB, answer.get(10, TimeUnit.SECONDS))
            GoldenTestSupport.waitFor("the running lookups were cancelled with their waiters") { fake.running.get() == 0 }
        } finally {
            scope.cancel()
            fake.open()
        }
    }

    fun testTitleLookupsDoNotWaitBehindDetailLookups() {
        val fake = BlockingLookup()
        use(fake)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val details = listOf("golden", "mol").map { DriftFixture.file(myFixture, DriftFixture.roleDir(it)) }
            details.forEach { dir -> scope.launch { coroutineToIndicator { _ -> changes.lastChangeUnder(dir) } } }
            GoldenTestSupport.waitFor("both detail lanes are busy") { fake.started.size >= 2 }
            val file = vf("tasks/main.yml")
            fake.answered += file
            val titles = GoldenTestSupport.pooled { changes.lastChanges(listOf(file), 5000.milliseconds) }
            assertEquals("the title lookup ran on its own lane", listOf(ALICE), titles)
        } finally {
            scope.cancel()
            fake.open()
        }
    }

    fun testTheVcsLookupHasNoAnswerOutsideAnyRepository() {
        val lookup = VcsLastChangeLookup()
        assertNull(GoldenTestSupport.pooled { lookup.lastChange(project, vf("tasks/main.yml")) })
        assertNull(GoldenTestSupport.pooled { lookup.lastChangeUnder(project, DriftFixture.file(myFixture, DriftFixture.roleDir("golden"))) })
        assertTrue("the VCS lookup is registered by ansibility-vcs.xml", LastChangeLookup.EP_NAME.extensionList.any { it is VcsLastChangeLookup })
    }

    /** Answers [ALICE] for files and [BOB] for directories, counting calls; waits for [release] when given. */
    private class CountingLookup(@Volatile var release: CountDownLatch? = null) : LastChangeLookup {
        val files = AtomicInteger()
        val dirs = AtomicInteger()
        private val seen = ConcurrentHashMap.newKeySet<VirtualFile>()

        @Volatile
        var changed: ((VirtualFile?) -> Unit)? = null

        /** Runs inside every lookup (a VCS status refresh while git runs). */
        @Volatile
        var duringLookup: () -> Unit = {}

        override fun lastChange(project: Project, file: VirtualFile): LastChange {
            files.incrementAndGet()
            seen += file
            duringLookup()
            release?.await(30, TimeUnit.SECONDS)
            return ALICE
        }

        override fun lastChangeUnder(project: Project, dir: VirtualFile): LastChange {
            dirs.incrementAndGet()
            duringLookup()
            return BOB
        }

        override fun watch(project: Project, parent: Disposable, changed: (VirtualFile?) -> Unit) {
            this.changed = changed
        }
    }

    /**
     * Blocks every lookup, cancellably (like git, whose process ends when the indicator is cancelled), until [open];
     * answers the files in [answered] at once. Records what started and how many run.
     */
    private class BlockingLookup : LastChangeLookup {
        val started: MutableSet<VirtualFile> = ConcurrentHashMap.newKeySet()
        val answered: MutableSet<VirtualFile> = ConcurrentHashMap.newKeySet()
        val running = AtomicInteger()
        private val gate = CountDownLatch(1)

        fun open() = gate.countDown()

        private fun block(file: VirtualFile, answer: LastChange): LastChange {
            if (file in answered) return answer
            started += file
            running.incrementAndGet()
            try {
                while (!gate.await(10, TimeUnit.MILLISECONDS)) ProgressManager.checkCanceled()
                return answer
            } finally {
                running.decrementAndGet()
            }
        }

        override fun lastChange(project: Project, file: VirtualFile): LastChange = block(file, ALICE)

        override fun lastChangeUnder(project: Project, dir: VirtualFile): LastChange = block(dir, BOB)
    }

    private companion object {
        val ALICE = LastChange("alice", Instant.parse("2026-09-12T10:00:00Z"), "Fix the verify step", "0123abcd")
        val BOB = LastChange("bob", Instant.parse("2026-08-01T08:00:00Z"), "Add web role", "89abcdef")
    }
}
