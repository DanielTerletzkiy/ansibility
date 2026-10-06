package de.terletzkiy.ansibility.runtime

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.replaceService
import com.intellij.util.ThrowableRunnable
import de.terletzkiy.ansibility.api.LocalAnsibleRuntime
import de.terletzkiy.ansibility.semantics.CoreVersion
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Tool lookup, `ansible --version` parsing, collection versions and the cached probe of [AnsibleToolchain]. */
class AnsibleToolchainTest : RuntimeTestCase() {
    private val toolchain: AnsibleToolchain get() = AnsibleToolchain.getInstance()
    private lateinit var fake: FakeAnsible

    override fun setUp() {
        super.setUp()
        fake = FakeAnsible(tempDir("fake-ansible"))
        ApplicationManager.getApplication().replaceService(ProcessRunner::class.java, fake, testRootDisposable)
    }

    override fun runTestRunnable(testRunnable: ThrowableRunnable<Throwable>) {
        if (SystemInfo.isWindows) return // POSIX executables and paths
        super.runTestRunnable(testRunnable)
    }

    fun testParsesAnsibleVersion() {
        val text = File("src/test/testData/runtime/ansible-version-2.21.4.txt").readText()
        val install = AnsibleToolchain.parseVersion(text, "/usr/bin/ansible")!!
        assertEquals(CoreVersion(2, 21, 4), install.coreVersion)
        assertEquals("/opt/homebrew/bin/ansible", install.executable)
        assertEquals("/opt/homebrew/Cellar/ansible/14.4.0/libexec/lib/python3.14/site-packages/ansible", install.moduleLocation)
        assertEquals(listOf("/Users/dev/.ansible/collections", "/usr/share/ansible/collections"), install.collectionPaths)

        val old = AnsibleToolchain.parseVersion("ansible 2.9.27\n  config file = /etc/ansible/ansible.cfg\n", "/usr/bin/ansible")!!
        assertEquals(CoreVersion(2, 9, 27), old.coreVersion)
        assertEquals("/usr/bin/ansible", old.executable)
        assertNull(AnsibleToolchain.parseVersion("ERROR: Ansible requires blocking IO on stdin/stdout/stderr.", "/usr/bin/ansible"))
        assertNull(AnsibleToolchain.parseVersion("", "/usr/bin/ansible"))
    }

    fun testLookupOrder() {
        val candidates = toolchain.candidates(AnsibleTool.ANSIBLE_DOC, project).map(Path::toString)
        val homebrew = candidates.indexOf("/opt/homebrew/bin/ansible-doc")
        val usrLocal = candidates.indexOf("/usr/local/bin/ansible-doc")
        assertTrue("GUI launches lack /opt/homebrew/bin: $candidates", homebrew >= 0)
        val pathEntries = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }
        // A fallback that is already on PATH keeps its PATH position (Linux runners have /usr/local/bin there).
        if (AnsibleToolchain.FALLBACK_DIRECTORIES.none { it in pathEntries }) {
            assertTrue("the fallbacks keep their order: $candidates", usrLocal > homebrew)
        }
        val firstPathEntry = pathEntries.firstOrNull()
        if (firstPathEntry != null && firstPathEntry !in AnsibleToolchain.FALLBACK_DIRECTORIES) {
            assertTrue("PATH comes before the fallbacks", candidates.indexOf(Path.of(firstPathEntry, "ansible-doc").toString()) in 0 until homebrew)
        }
    }

    fun testExplicitExecutableWins() {
        options.explicitExecutable = { fake.bin.toString() }
        assertEquals("a directory holds the tools", fake.bin.resolve("ansible-doc"), toolchain.locate(AnsibleTool.ANSIBLE_DOC, project))
        options.explicitExecutable = { fake.bin.resolve("ansible-doc").toString() }
        assertEquals("a sibling of the chosen tool", fake.bin.resolve("ansible"), toolchain.locate(AnsibleTool.ANSIBLE, project))
        assertEquals(fake.bin.resolve("ansible-doc"), toolchain.candidates(AnsibleTool.ANSIBLE_DOC, project).first())
        options.explicitExecutable = { tempDir().resolve("nothing-here").toString() }
        assertFalse(toolchain.candidates(AnsibleTool.ANSIBLE, project).first().startsWith(fake.bin))
    }

    fun testCollectionVersionsFirstPathWins() {
        val second = tempDir("more-collections")
        fake.installCollection("community.general", "12.3.0")
        fake.installCollection("community.general", "13.4.0", into = fake.sitePackages)
        fake.installCollection("ansible.posix", "2.2.2", into = fake.sitePackages)
        val galaxyDir = Files.createDirectories(second.resolve("ansible_collections/acme/tools"))
        Files.writeString(galaxyDir.resolve("galaxy.yml"), "namespace: acme\nname: tools\nversion: '1.4.0'\n")
        Files.createDirectories(second.resolve("ansible_collections/community.general-12.3.0.info"))
        val install = AnsibleToolchain.parseVersion(fake.versionOutput(), fake.ansible.toString())!!
            .let { it.copy(collectionPaths = it.collectionPaths + second.toString()) }
        val versions = toolchain.collectionVersions(install)
        assertEquals("~/.ansible shadows the package's copy", "12.3.0", versions["community.general"])
        assertEquals("2.2.2", versions["ansible.posix"])
        assertEquals("1.4.0", versions["acme.tools"])
        assertEquals("2.21.4", versions["ansible.builtin"])
        assertFalse(versions.keys.any { it.contains("info") })
    }

    fun testProbeRunsAnsibleVersionOnce() {
        options.explicitExecutable = { fake.bin.toString() }
        val first = runBlocking { toolchain.probe(project) }!!
        assertEquals(CoreVersion(2, 21, 4), first.coreVersion)
        assertEquals(fake.ansible.toString(), first.executable)
        assertEquals(listOf(fake.collections.toString()), first.collectionPaths)
        assertSame(first, runBlocking { toolchain.probe(project) })
        assertEquals(1, fake.versionCalls())
        val config = fake.home.resolve("ansible.cfg")
        runBlocking { toolchain.probe(project, config) }
        assertEquals("another ansible.cfg is another probe", 2, fake.versionCalls())
        assertEquals(config, fake.calls.last().ansibleConfig)
    }

    fun testProbeWithoutAnsible() {
        toolchain.searchDirectoriesOverride = listOf(tempDir().toString())
        assertNull(toolchain.locate(AnsibleTool.ANSIBLE, project))
        assertNull(runBlocking { toolchain.probe(project) })
        assertEquals(0, fake.calls.size)
    }

    fun testPathEntriesInOrder() {
        val empty = tempDir("empty-bin")
        toolchain.searchDirectoriesOverride = listOf(empty.toString(), "", fake.bin.toString())
        assertEquals(listOf(empty.resolve("ansible"), fake.bin.resolve("ansible")), toolchain.candidates(AnsibleTool.ANSIBLE, project))
        assertEquals("the first existing one", fake.bin.resolve("ansible"), toolchain.locate(AnsibleTool.ANSIBLE, project))
    }

    fun testCachedInstallNeverBlocks() {
        options.explicitExecutable = { fake.bin.toString() }
        options.localTargetProbe = true
        val runtime = LocalAnsibleRuntime.getInstanceOrNull()!!
        val before = runtime.probeTracker.modificationCount
        val immediate = runtime.localInstall(project)
        val deadline = System.currentTimeMillis() + 10_000
        var install = immediate
        while (install == null && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(10)
            install = runtime.localInstall(project)
        }
        assertEquals(CoreVersion(2, 21, 4), install!!.coreVersion)
        assertTrue(runtime.probeTracker.modificationCount > before)
        assertEquals(1, fake.versionCalls())

        options.localTargetProbe = false
        assertNull("switched off in the settings", runtime.localInstall(project))
    }
}
