package de.terletzkiy.ansibility.runtime

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.replaceService
import com.intellij.util.ThrowableRunnable
import de.terletzkiy.ansibility.api.AnsibleDocService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.DocSourceKind
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.json.Json
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.time.Duration.Companion.milliseconds

/** The runtime refresh with a fake local install: fetching, caching, merge order per root and request batching. */
class LocalDocRefresherTest : RuntimeTestCase() {
    private val refresher: LocalDocRefresher get() = LocalDocRefresher.getInstance(project)
    private val service: AnsibleDocService get() = AnsibleDocService.getInstance(project)
    private lateinit var fake: FakeAnsible
    private lateinit var cache: Path
    private lateinit var root: AnsibleRoot

    override fun setUp() {
        super.setUp()
        fake = FakeAnsible(tempDir("fake-ansible"))
        fake.installCollection("community.general", "99.1.0")
        fake.addModule("acme.tools.thing", "Local thing")
        fake.addModule("ansible.builtin.copy", "LOCAL COPY")
        fake.addModule("community.general.appimage", "LOCAL APPIMAGE")
        ApplicationManager.getApplication().replaceService(ProcessRunner::class.java, fake, testRootDisposable)
        options.explicitExecutable = { fake.bin.toString() }
        options.localDocRefresh = true
        cache = tempDir("doc-cache")
        refresher.cacheDirectoryOverride = cache
        root = createRoot()
        target(root, CoreVersion.PINNED)
    }

    override fun tearDown() {
        try {
            refresher.cacheDirectoryOverride = null
            refresher.debounce = 500.milliseconds
        } finally {
            super.tearDown()
        }
    }

    override fun runTestRunnable(testRunnable: ThrowableRunnable<Throwable>) {
        if (SystemInfo.isWindows) return // POSIX executables and paths
        super.runTestRunnable(testRunnable)
    }

    private fun refresh(vararg fqcns: String): Int = runBlocking { refresher.refresh(root, fqcns.toList()) }

    fun testRefreshAddsLocalOnlyModules() {
        assertNull(service.moduleDoc(root, "acme.tools.thing"))
        val tracker = service.docsTracker.modificationCount
        assertEquals(1, refresh("acme.tools.thing", "acme.tools.gone"))
        assertTrue("new docs bump the tracker", service.docsTracker.modificationCount > tracker)
        assertEquals(listOf(listOf("acme.tools.gone", "acme.tools.thing")), fake.docCalls())

        val doc = service.moduleDoc(root, "acme.tools.thing")!!
        assertEquals("Local thing", doc.doc!!.shortDescription)
        assertEquals("local 2.21.4", doc.doc.source)
        val source = doc.source!!
        assertEquals(DocSourceKind.LOCAL, source.kind)
        assertEquals("local ansible-core 2.21.4", source.label)
        assertEquals(CoreVersion(2, 21, 4), source.core)
        assertFalse("2.21 docs for a 2.18 target", source.matchesTarget)
        assertEquals("99.1.0", source.collections["community.general"])
        assertEquals("https://docs.ansible.com/ansible/11/collections/acme/tools/thing_module.html", doc.docsUrl)
        assertTrue("acme.tools.thing" in service.allModules(root))

        val docs = refresher.localDocs(root)!!
        assertTrue(docs.catalog.knows("acme.tools.gone"))
        assertEquals(setOf("acme.tools.gone"), docs.catalog.missing)
        assertEquals(1, cache.listDirectoryEntries("*.json.gz").size)
    }

    fun testKnownAndMissingNamesAreNotFetchedAgain() {
        refresh("acme.tools.thing", "acme.tools.gone")
        assertEquals(0, refresh("acme.tools.thing", "acme.tools.gone"))
        assertEquals(1, fake.docCalls().size)

        // a new session: memory is empty, the disk cache answers
        refresher.resetForTests()
        AnsibleToolchain.getInstance().resetForTests()
        val tracker = refresher.docsTracker.modificationCount
        assertEquals(0, refresh("acme.tools.thing", "acme.tools.gone"))
        assertEquals("no ansible-doc run for cached names", 1, fake.docCalls().size)
        assertTrue("docs loaded from disk count as new for the root", refresher.docsTracker.modificationCount > tracker)
        assertEquals("Local thing", service.moduleDoc(root, "acme.tools.thing")!!.doc!!.shortDescription)
    }

    fun testCacheKeyFollowsCoreAndCollectionVersions() {
        refresh("acme.tools.thing")
        fake.installCollection("community.docker", "5.3.0")
        refresher.resetForTests()
        refresh("acme.tools.thing")
        assertEquals("another collection set is another key", 2, fake.docCalls().size)
        val keys = cache.listDirectoryEntries("*.json.gz").map { it.fileName.toString().removeSuffix(".json.gz") }.toSet()
        assertEquals(2, keys.size)
        assertTrue(refresher.localDocs(root)!!.catalog.key in keys)
        assertEquals("99.1.0", refresher.localDocs(root)!!.catalog.collections["community.general"])
        assertEquals("5.3.0", refresher.localDocs(root)!!.catalog.collections["community.docker"])
    }

    fun testTheTargetLineBeatsTheLocalInstall() {
        refresh("ansible.builtin.copy")
        val copy = service.moduleDoc(root, "ansible.builtin.copy")!!
        assertEquals(DocSourceKind.BUNDLED, copy.source!!.kind)
        assertFalse(copy.doc!!.shortDescription == "LOCAL COPY")
        assertEquals(CoreVersion.PINNED, service.primarySource(root).core)
    }

    fun testTheLocalInstallBeatsTheOtherLine() {
        assertEquals("before the refresh the latest line fills the gap", CoreVersion(2, 21, 4), service.moduleDoc(root, "community.general.appimage")!!.source!!.core)
        assertEquals(DocSourceKind.BUNDLED, service.moduleDoc(root, "community.general.appimage")!!.source!!.kind)
        refresh("community.general.appimage")
        val appimage = service.moduleDoc(root, "community.general.appimage")!!
        assertEquals(DocSourceKind.LOCAL, appimage.source!!.kind)
        assertEquals("LOCAL APPIMAGE", appimage.doc!!.shortDescription)
    }

    fun testALocalInstallOnTheTargetLineGoesFirst() {
        fake.core = "2.20.1"
        target(root, CoreVersion(2, 20, 0))
        refresh("ansible.builtin.copy")
        val copy = service.moduleDoc(root, "ansible.builtin.copy")!!
        assertEquals(DocSourceKind.LOCAL, copy.source!!.kind)
        assertTrue(copy.source.matchesTarget)
        assertEquals("LOCAL COPY", copy.doc!!.shortDescription)
        assertEquals(DocSourceKind.LOCAL, service.primarySource(root).kind)
        assertEquals("https://docs.ansible.com/ansible/13/collections/ansible/builtin/copy_module.html", copy.docsUrl)
        // modules the local install lacks still come from the bundled lines, with their routing
        val mysqlUser = service.moduleDoc(root, "community.mysql.mysql_user")!!
        assertEquals("ansible.mysql.mysql_user", mysqlUser.canonical)
        assertEquals(DocSourceKind.BUNDLED, mysqlUser.source!!.kind)
    }

    fun testFailedBatchesAreRetriedOneByOne() {
        fake.failMultiNameBatches = true
        assertEquals(2, refresh("acme.tools.thing", "community.general.appimage"))
        assertEquals(
            listOf(listOf("acme.tools.thing", "community.general.appimage"), listOf("acme.tools.thing"), listOf("community.general.appimage")),
            fake.docCalls(),
        )
    }

    fun testLookupsQueueABatchedRefresh() {
        refresher.debounce = 50.milliseconds
        fake.addModule("acme.tools.other", "Other")
        assertNull(service.moduleDoc(root, "acme.tools.thing"))
        assertNull(service.moduleDoc(root, "acme.tools.other"))
        assertNotNull("documented by the target line: nothing to fetch", service.moduleDoc(root, "ansible.builtin.template"))
        val deadline = System.currentTimeMillis() + 10_000
        while (service.moduleDoc(root, "acme.tools.other") == null && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        assertNotNull(service.moduleDoc(root, "acme.tools.thing"))
        assertNotNull(service.moduleDoc(root, "acme.tools.other"))
        assertEquals("one batched ansible-doc run", listOf(listOf("acme.tools.other", "acme.tools.thing")), fake.docCalls().map { it.sorted() })
    }

    /** `moduleSummaries` lists what `allModules` + `moduleDoc` give, but never queues a refresh (w4-taskcomp request). */
    fun testModuleSummariesQueueNoRefresh() {
        refresher.debounce = 50.milliseconds
        val summaries = service.moduleSummaries(root)
        assertEquals(service.allModules(root).toList(), summaries.map { it.name })
        val undocumented = summaries.filter { it.doc?.doc == null }
        assertTrue("some redirect targets have no docs in the snapshot", undocumented.isNotEmpty())
        for (summary in summaries.filter { it.name.startsWith("ansible.builtin.") || it.name.startsWith("community.mysql.") }) {
            assertEquals(summary.name, service.moduleDoc(root, summary.name), summary.doc)
        }
        fake.calls.clear()
        service.moduleSummaries(root)
        val quiet = System.currentTimeMillis() + 500
        while (System.currentTimeMillis() < quiet) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        assertEquals("listing summaries runs nothing", emptyList<List<String>>(), fake.docCalls())

        service.moduleDoc(root, undocumented.first().name)
        val deadline = System.currentTimeMillis() + 10_000
        while (fake.docCalls().isEmpty() && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        assertFalse("moduleDoc still queues the refresh", fake.docCalls().isEmpty())
    }

    fun testNothingRunsWhenSwitchedOff() {
        options.localDocRefresh = false
        assertEquals(0, refresh("acme.tools.thing"))
        assertNull(service.moduleDoc(root, "acme.tools.thing"))
        assertTrue(fake.calls.isEmpty())
        options.localDocRefresh = true
        refresh("acme.tools.thing")
        assertNotNull(service.moduleDoc(root, "acme.tools.thing"))
        options.localDocRefresh = false
        assertNull("local docs are not used while switched off", service.moduleDoc(root, "acme.tools.thing"))
    }

    fun testNoLocalAnsible() {
        options.explicitExecutable = { null }
        AnsibleToolchain.getInstance().searchDirectoriesOverride = emptyList()
        assertEquals(0, refresh("acme.tools.thing"))
        assertTrue(fake.calls.isEmpty())
        assertNull(refresher.localDocs(root))
    }

    fun testUsesTheRootsAnsibleCfg() {
        val dir = tempDir("real-root")
        Files.writeString(dir.resolve("ansible.cfg"), "[defaults]\n")
        val vDir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)!!
        val realRoot = AnsibleRoot(vDir, RootKind.PROJECT, false, null, emptyList(), null, "real")
        assertEquals(dir.resolve("ansible.cfg"), LocalDocRefresher.ansibleConfigOf(realRoot))
        runBlocking { refresher.refresh(realRoot, listOf("acme.tools.thing")) }
        assertTrue(fake.calls.isNotEmpty())
        assertTrue(fake.calls.all { it.ansibleConfig == dir.resolve("ansible.cfg") })
        assertNull("the light fixture's in-memory root has no local ansible.cfg", LocalDocRefresher.ansibleConfigOf(root))
    }

    fun testCacheFileIsSnapshotFormat() {
        refresh("acme.tools.thing", "acme.tools.gone")
        val file = cache.listDirectoryEntries("*.json.gz").single()
        val json = java.util.zip.GZIPInputStream(Files.newInputStream(file)).reader().use { Json.parseObject(it) }
        assertEquals(1L, json["format"])
        assertEquals("2.21.4", json["core"])
        assertEquals(listOf("acme.tools.gone"), json["missing"])
        assertTrue((json["modules"] as Map<*, *>).containsKey("acme.tools.thing"))
        assertFalse("never written into the project", File(project.basePath ?: "/nonexistent").resolve("ansibility").exists())
    }
}
