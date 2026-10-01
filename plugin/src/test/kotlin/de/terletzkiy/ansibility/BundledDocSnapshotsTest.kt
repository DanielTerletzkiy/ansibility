package de.terletzkiy.ansibility

import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.schema.DocSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The doc snapshots are packaged as plugin resources under `/ansible-data/` and load through `:semantics`. */
class BundledDocSnapshotsTest {
    private fun load(name: String): DocSnapshot {
        val stream = javaClass.getResourceAsStream("/ansible-data/$name")
        assertNotNull("resource /ansible-data/$name", stream)
        return stream!!.use { DocSnapshot.load(it) }
    }

    @Test
    fun pinnedSnapshotIsBundled() {
        val snapshot = load("core-2.18.8.json.gz")
        assertEquals(CoreVersion.PINNED, snapshot.coreVersion)
        assertEquals("13.2.0", snapshot.collections["community.general"])
        assertEquals(25, snapshot.module("ansible.builtin.template").doc!!.options.size)
        assertEquals("ansible.mysql.mysql_user", snapshot.module("community.mysql.mysql_user").resolution.canonical)
    }

    @Test
    fun latestSnapshotIsBundled() {
        val snapshot = load("core-latest.json.gz")
        assertTrue(snapshot.coreVersion!! > CoreVersion.PINNED)
        assertNotNull(snapshot.module("ansible.builtin.systemd").doc)
    }
}
