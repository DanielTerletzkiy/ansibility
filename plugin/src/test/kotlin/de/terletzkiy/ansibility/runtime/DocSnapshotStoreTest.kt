package de.terletzkiy.ansibility.runtime

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.semantics.CoreVersion

class DocSnapshotStoreTest : BasePlatformTestCase() {
    fun testLineSelectionByTargetVersion() {
        assertEquals(BundledLine.PINNED, DocSnapshotStore.lineFor(CoreVersion(2, 18, 8)))
        assertEquals(BundledLine.PINNED, DocSnapshotStore.lineFor(CoreVersion(2, 18, 0)))
        assertEquals("older cores are closest to the pinned line", BundledLine.PINNED, DocSnapshotStore.lineFor(CoreVersion(2, 16, 3)))
        assertEquals(BundledLine.LATEST, DocSnapshotStore.lineFor(CoreVersion(2, 19, 0)))
        assertEquals(BundledLine.LATEST, DocSnapshotStore.lineFor(CoreVersion(2, 21, 4)))
        assertEquals(BundledLine.LATEST, DocSnapshotStore.lineFor(CoreVersion(2, 25, 0)))
        assertEquals("unknown → pinned", BundledLine.PINNED, DocSnapshotStore.lineFor(null))
        assertEquals(BundledLine.LATEST, BundledLine.PINNED.other)
        assertEquals(BundledLine.PINNED, BundledLine.LATEST.other)
    }

    fun testSnapshotsLoadOnceAndAreShared() {
        val store = DocSnapshotStore.getInstance()
        val pinned = store.snapshot(BundledLine.PINNED)
        assertEquals(CoreVersion.PINNED, pinned.coreVersion)
        assertEquals("13.2.0", pinned.collections["community.general"])
        assertTrue(store.isLoaded(BundledLine.PINNED))
        assertSame(pinned, store.snapshot(BundledLine.PINNED))
        assertSame(pinned, store.snapshotFor(CoreVersion(2, 18, 3)))
        assertSame(pinned, store.snapshotFor(null))

        val latest = store.snapshotFor(CoreVersion(2, 20, 0))
        assertTrue(latest.coreVersion!! >= DocSnapshotStore.LATEST_FROM)
        assertSame(latest, store.snapshot(BundledLine.LATEST))
        assertNotSame(pinned, latest)
    }

    fun testWarmUpLoadsTheRequestedLines() {
        val store = DocSnapshotStore.getInstance()
        store.warmUp(listOf(BundledLine.LATEST))
        assertTrue(store.isLoaded(BundledLine.LATEST))
    }
}
