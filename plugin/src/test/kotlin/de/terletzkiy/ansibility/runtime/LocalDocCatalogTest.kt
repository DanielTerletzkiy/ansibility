package de.terletzkiy.ansibility.runtime

import de.terletzkiy.ansibility.semantics.CoreVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class LocalDocCatalogTest {
    private val core = CoreVersion(2, 21, 4)
    private val collections = mapOf("ansible.builtin" to "2.21.4", "community.general" to "12.3.0")

    @Test
    fun keyDependsOnCoreAndCollectionVersionsOnly() {
        val key = LocalDocCatalog.keyOf(core, collections)
        assertEquals(key, LocalDocCatalog.keyOf(core, collections.toList().reversed().toMap()))
        assertNotEquals(key, LocalDocCatalog.keyOf(CoreVersion(2, 21, 5), collections))
        assertNotEquals(key, LocalDocCatalog.keyOf(core, collections + ("community.general" to "13.4.0")))
        assertNotEquals(key, LocalDocCatalog.keyOf(core, collections + ("community.docker" to "5.0.6")))
        assertEquals(32, key.length)
    }

    @Test
    fun mergeAndRoundTrip() {
        val added = AnsibleDocNormalizer.Modules(
            modules = mapOf("ansible.builtin.systemd_service" to mapOf("short_description" to "Manage systemd units", "options" to emptyMap<String, Any?>())),
            aliases = mapOf("ansible.builtin.systemd" to "ansible.builtin.systemd_service"),
        )
        val catalog = LocalDocCatalog.empty(core, collections).merge(added, listOf("acme.tools.gone", "ansible.builtin.systemd"))
        assertTrue(catalog.knows("ansible.builtin.systemd"))
        assertTrue(catalog.knows("ansible.builtin.systemd_service"))
        assertTrue(catalog.knows("acme.tools.gone"))
        assertFalse(catalog.knows("acme.tools.other"))
        assertEquals("a documented name is never 'missing'", setOf("acme.tools.gone"), catalog.missing)
        assertEquals("ansible.builtin.systemd_service", catalog.snapshot.module("systemd").resolution.canonical)
        assertEquals("local 2.21.4", catalog.snapshot.module("systemd").doc!!.source)

        val file = Files.createTempDirectory("catalog").resolve("docs").resolve("${catalog.key}.json.gz")
        LocalDocCatalog.write(file, catalog)
        val read = LocalDocCatalog.read(file)!!
        assertEquals(catalog.key, read.key)
        assertEquals(catalog.core, read.core)
        assertEquals(catalog.collections, read.collections)
        assertEquals(catalog.modules, read.modules)
        assertEquals(catalog.aliases, read.aliases)
        assertEquals(catalog.missing, read.missing)
        file.parent.toFile().deleteRecursively()
    }

    @Test
    fun unreadableCachesAreIgnored() {
        val dir = Files.createTempDirectory("catalog")
        assertNull(LocalDocCatalog.read(dir.resolve("absent.json.gz")))
        val garbage = dir.resolve("garbage.json.gz").also { Files.writeString(it, "not gzip") }
        assertNull(LocalDocCatalog.read(garbage))
        val future = dir.resolve("future.json.gz")
        GZIPOutputStream(Files.newOutputStream(future)).use { it.write("""{"format": 99, "core": "2.21.4"}""".toByteArray()) }
        assertNull("another format is ignored", LocalDocCatalog.read(future))
        dir.toFile().deleteRecursively()
    }
}
