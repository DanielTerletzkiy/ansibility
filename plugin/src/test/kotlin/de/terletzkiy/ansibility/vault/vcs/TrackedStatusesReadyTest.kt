package de.terletzkiy.ansibility.vault.vcs

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.vault.vcs.impl.VcsTrackedStatusLookup
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the monitoring needs from `TrackedStatuses` beyond the inspection (plan amendment R21, D165/D166): listeners for
 * every status change, and the moment the VCS knows the statuses.
 */
class TrackedStatusesReadyTest : BasePlatformTestCase() {
    private class Lookup : TrackedStatusLookup {
        val watchers = CopyOnWriteArrayList<(VirtualFile?) -> Unit>()
        var ready: (() -> Unit)? = null

        override fun status(project: Project, file: VirtualFile) = TrackedStatus.TRACKED

        override fun watch(project: Project, parent: Disposable, changed: (VirtualFile?) -> Unit) {
            watchers += changed
        }

        override fun whenReady(project: Project, ready: () -> Unit) {
            this.ready = ready
        }
    }

    fun testListenersHearEveryChangeUntilTheirParentIsDisposed() {
        val lookup = Lookup()
        ExtensionTestUtil.maskExtensions(TrackedStatusLookup.EP_NAME, listOf<TrackedStatusLookup>(lookup), testRootDisposable)
        val statuses = TrackedStatuses.getInstance(project)
        val heard = CopyOnWriteArrayList<VirtualFile?>()
        val parent = Disposer.newDisposable(testRootDisposable, "listener")
        statuses.addListener(parent) { heard += it }
        val file = myFixture.tempDirFixture.createFile("site/web.key", "x\n")
        lookup.watchers.forEach { it(file) }
        lookup.watchers.forEach { it(null) }
        assertEquals("also for files without findings, and for many at once", listOf(file, null), heard.toList())
        Disposer.dispose(parent)
        lookup.watchers.forEach { it(file) }
        assertEquals(2, heard.size)
    }

    fun testReadyIsTheLookupsAnswer() {
        val lookup = Lookup()
        ExtensionTestUtil.maskExtensions(TrackedStatusLookup.EP_NAME, listOf<TrackedStatusLookup>(lookup), testRootDisposable)
        val ready = AtomicBoolean()
        TrackedStatuses.getInstance(project).whenReady { ready.set(true) }
        assertFalse(ready.get())
        lookup.ready!!.invoke()
        assertTrue(ready.get())
    }

    fun testWithoutALookupStatusesAreReadyAtOnce() {
        ExtensionTestUtil.maskExtensions(TrackedStatusLookup.EP_NAME, emptyList<TrackedStatusLookup>(), testRootDisposable)
        val ready = AtomicBoolean()
        TrackedStatuses.getInstance(project).whenReady { ready.set(true) }
        assertTrue(ready.get())
    }

    /** The VCS fragment's lookup answers in a project without any VCS once the VCS manager is initialized. */
    fun testTheVcsLookupAnswersWithoutAVcs() {
        val ready = AtomicBoolean()
        VcsTrackedStatusLookup().whenReady(project) { ready.set(true) }
        PlatformTestUtil.waitWithEventsDispatching("no answer without a VCS", { ready.get() }, 30)
    }
}
