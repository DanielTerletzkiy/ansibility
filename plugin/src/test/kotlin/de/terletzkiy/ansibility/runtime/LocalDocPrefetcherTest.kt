package de.terletzkiy.ansibility.runtime

import com.intellij.openapi.application.runReadAction
import com.intellij.testFramework.PlatformTestUtil
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** [LocalDocPrefetcher]: module names from `ansible.module.use` per root, fed to the refresher only while D14 is on. */
class LocalDocPrefetcherTest : RuntimeTestCase() {
    private val prefetcher: LocalDocPrefetcher get() = LocalDocPrefetcher.getInstance(project)
    private val requested = Collections.synchronizedList(ArrayList<Pair<AnsibleRoot, Set<String>>>())
    private lateinit var savedSink: (AnsibleRoot, Set<String>) -> Unit
    private var savedDebounce: Duration = Duration.ZERO
    private lateinit var site: AnsibleRoot
    private lateinit var other: AnsibleRoot

    override fun setUp() {
        super.setUp()
        savedSink = prefetcher.sink
        savedDebounce = prefetcher.debounce
        prefetcher.sink = { root, names -> requested += root to names }
        prefetcher.debounce = 0.milliseconds
        site = createRoot("site", mapOf("roles/web/tasks/main.yml" to TASKS))
        other = createRoot("other", mapOf("playbook-a.yml" to PLAY))
    }

    override fun tearDown() {
        try {
            options.localDocRefresh = false
            prefetcher.sink = savedSink
            prefetcher.debounce = savedDebounce
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun byRoot(): Map<String, Set<String>> = synchronized(requested) { requested.associate { it.first.dir.name to it.second } }

    private fun waitFor(what: String, condition: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching(what, condition, 20)

    fun testModuleNamesAreCollectedPerRoot() {
        val names = runReadAction { prefetcher.moduleNamesByRoot() }.mapKeys { it.key.dir.name }
        assertEquals(setOf("ansible.builtin.copy", "apt"), names["site"])
        assertEquals("root-scoped: the other root's play only", setOf("ansible.builtin.ping"), names["other"])
    }

    fun testNothingIsRequestedWhileTheRefreshIsOff() {
        options.localDocRefresh = false
        assertEquals(0, runBlocking { prefetcher.prefetch() })
        prefetcher.schedule()
        val deadline = System.currentTimeMillis() + 300
        waitFor("a moment") { System.currentTimeMillis() > deadline }
        assertTrue(requested.isEmpty())
    }

    fun testStartPrefetchesAndFollowsStructureChanges() {
        options.localDocRefresh = true
        prefetcher.start()
        prefetcher.start()
        // The light project outlives a test, so the prefetcher may have been started before: schedule explicitly too.
        prefetcher.schedule()
        waitFor("the first prefetch") { byRoot().keys == setOf("site", "other") }
        assertEquals(setOf("ansible.builtin.copy", "apt"), byRoot()["site"])

        requested.clear()
        myFixture.tempDirFixture.createFile("site/roles/db/tasks/main.yml", "- name: Stat\n  ansible.builtin.stat:\n    path: /x\n")
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
        waitFor("a prefetch after the structure change") { byRoot()["site"]?.contains("ansible.builtin.stat") == true }
    }

    fun testBurstsAreDebounced() {
        options.localDocRefresh = true
        prefetcher.debounce = 300.milliseconds
        repeat(5) { prefetcher.schedule() }
        waitFor("one prefetch") { requested.isNotEmpty() }
        val deadline = System.currentTimeMillis() + 800
        waitFor("no further prefetch") { System.currentTimeMillis() > deadline }
        assertEquals("one query per burst: one request per root", 2, requested.size)
    }

    private companion object {
        const val TASKS = "- name: Copy\n  ansible.builtin.copy:\n    src: a\n    dest: /b\n- name: Apt\n  apt:\n    name: x\n"
        const val PLAY = "- name: Ping\n  hosts: all\n  tasks:\n    - ansible.builtin.ping:\n"
    }
}
