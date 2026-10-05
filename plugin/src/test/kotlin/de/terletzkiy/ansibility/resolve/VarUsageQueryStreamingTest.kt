package de.terletzkiy.ansibility.resolve

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileBasedIndex
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.index.VarUseIndex
import de.terletzkiy.ansibility.vars.VarsTestCase

/**
 * The streaming API of [VarUsageQuery] that Find Usages uses (plan amendment FU): [VarUsageQuery.process] with a
 * dialog scope and early stop, and [VarUsageQuery.usagesIn] (one file's own index data, for caret highlighting), on
 * `testData/vars/site` (`web_port` is read in the role's tasks, its template, the other role and the playbook).
 */
class VarUsageQueryStreamingTest : VarsTestCase() {

    override fun setUp() {
        super.setUp()
        copyVarsData("site")
    }

    private val root: AnsibleRoot get() = runReadActionBlocking { AnsibleWorkspace.getInstance(project).contextOf(vf(TASKS))!!.root }

    private val query: VarUsageQuery get() = VarUsageQuery.getInstance(project)

    fun testProcessStreamsWhatUsagesLists() {
        val streamed = ArrayList<VarUsage>()
        assertTrue("a consumer that never stops walks to the end", query.process(root, NAME, null) { streamed += it; true })
        assertEquals(query.usages(root, NAME).toSet(), streamed.toSet())
        assertEquals(7, streamed.size)
    }

    fun testProcessNarrowsToTheGivenScope() {
        val inTasks = ArrayList<VarUsage>()
        query.process(root, NAME, GlobalSearchScope.fileScope(project, vf(TASKS))) { inTasks += it; true }
        assertEquals(listOf(9, 31, 34), inTasks.map { describe(it.location).substringAfterLast(':').toInt() }.sorted())
        assertTrue("a scope outside the root finds nothing", runReadActionBlocking {
            var none = true
            query.process(root, NAME, GlobalSearchScope.EMPTY_SCOPE) { none = false; true }
            none
        })
    }

    fun testProcessStopsWhenTheConsumerSaysSo() {
        var seen = 0
        assertFalse(query.process(root, NAME, null) { seen++; false })
        assertEquals(1, seen)
    }

    fun testUsagesInReadsOneFile() {
        assertEquals(
            listOf("site/roles/web/tasks/main.yml:9", "site/roles/web/tasks/main.yml:31", "site/roles/web/tasks/main.yml:34"),
            query.usagesIn(root, vf(TASKS), NAME).map { describe(it.location) },
        )
        assertEmpty("argument specs are never templated", query.usagesIn(root, vf("site/roles/web/meta/argument_specs.yml"), NAME))
        assertEmpty("no use of the name there", query.usagesIn(root, vf("site/roles/web/defaults/main.yml"), NAME))
    }

    /** The overload for entries the caller already has (an open file's indexer output): the same filter and order. */
    fun testUsagesInOverGivenEntries() {
        val entries = runReadActionBlocking { FileBasedIndex.getInstance().getFileData(VarUseIndex.NAME, vf(TASKS), project)[NAME].orEmpty() }
        assertEquals(query.usagesIn(root, vf(TASKS), NAME), query.usagesIn(root, vf(TASKS), NAME, entries.reversed()))
        assertEmpty("a file that does not count stays empty", query.usagesIn(root, vf("site/roles/web/meta/argument_specs.yml"), NAME, entries))
    }

    private companion object {
        const val TASKS = "site/roles/web/tasks/main.yml"
        const val NAME = "web_port"
    }
}
