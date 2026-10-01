package de.terletzkiy.ansibility.model.inventory

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.semantics.yaml.YMap

/**
 * [ModelCache] and [ModelInputs] (DEV.md rule 9; plan amendment R7/R8, A.9): values depend exactly on the files,
 * caches and external values they read, by content stamp; the counters count computations, invalidations and hits.
 */
class ModelCacheTest : BasePlatformTestCase() {
    private var counter = 0

    private fun <K : Any, V> cache(kind: ModelCacheKind = ModelCacheKind.MODEL): ModelCache<K, V> =
        ModelCache(project, "test.${getTestName(true)}.${counter++}", kind)

    private fun file(path: String, text: String): VirtualFile = myFixture.addFileToProject(path, text).virtualFile

    /** Appends [text] to the document of [file] and commits it, as typing does. */
    private fun type(file: VirtualFile, text: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            val document = FileDocumentManager.getInstance().getDocument(file)!!
            document.insertString(document.textLength, text)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }

    private fun keys(file: VirtualFile): List<String> = (VarsDocuments.load(project, file) as? YMap)?.keys.orEmpty()

    private fun <T> read(action: () -> T): T = runReadActionBlocking(action)

    fun testComputesOnceAndCountsHits() {
        val cache = cache<String, Int>()
        var computed = 0
        repeat(3) { assertEquals(42, read { cache.get("k") { computed++; 42 } }) }
        assertEquals(1, computed)
        assertEquals(1L, cache.stats.computations)
        assertEquals(0L, cache.stats.invalidations)
        assertEquals(2L, cache.stats.hits)
        assertEquals(1, cache.size)
    }

    fun testATypedEditOfAnInputInvalidatesOnlyTheEntriesThatReadIt() {
        val a = file("a/vars.yml", "a: 1\n")
        val b = file("b/vars.yml", "b: 1\n")
        val cache = cache<VirtualFile, List<String>>()
        val first = read { cache.get(a) { keys(a) } }
        val other = read { cache.get(b) { keys(b) } }
        type(b, "b2: 2\n")
        assertSame("an edit of another file keeps the entry", first, read { cache.get(a) { keys(a) } })
        val edited = read { cache.get(b) { keys(b) } }
        assertNotSame(other, edited)
        assertEquals(listOf("b", "b2"), edited)
        assertEquals(3L, cache.stats.computations)
        assertEquals(1L, cache.stats.invalidations)
    }

    fun testInvalidationIsTransitive() {
        val a = file("t/a.yml", "a: 1\n")
        val upstream = cache<VirtualFile, List<String>>()
        val downstream = cache<String, Int>()
        fun count() = read { downstream.get("count") { upstream.get(a) { keys(a) }.size } }
        assertEquals(1, count())
        assertEquals(1, count())
        assertEquals(1L, downstream.stats.computations)
        type(a, "b: 2\n")
        assertEquals("the downstream value read the upstream entry, so it follows its file", 2, count())
        assertEquals(2L, downstream.stats.computations)
        assertEquals(2L, upstream.stats.computations)
    }

    fun testUnrelatedEditsMoveTheEpochButKeepEveryEntry() {
        val a = file("e/a.yml", "a: 1\n")
        val unrelated = file("e/tasks.yml", "- debug: msg=hi\n")
        val cache = cache<String, List<String>>()
        val first = read { cache.get("a") { keys(a) } }
        val epoch = read { ModelCaches.getInstance(project).epoch() }
        type(unrelated, "- debug: msg=again\n")
        assertTrue("a PSI change moves the epoch", read { ModelCaches.getInstance(project).epoch() } != epoch)
        assertSame(first, read { cache.get("a") { keys(a) } })
        assertEquals(1L, cache.stats.computations)
    }

    fun testExternalValuesAreComparedByEquality() {
        var external = "one"
        val cache = cache<String, String>()
        val probe = file("x/probe.yml", "p: 1\n")
        fun value() = read {
            cache.get("k") {
                ModelInputs.external(external) { external }
                external.uppercase()
            }
        }
        assertEquals("ONE", value())
        external = "one"
        type(probe, "q: 2\n")
        assertEquals("ONE", value())
        assertEquals("an equal external value keeps the entry", 1L, cache.stats.computations)
        external = "two"
        type(probe, "r: 3\n")
        assertEquals("TWO", value())
        assertEquals(2L, cache.stats.computations)
    }

    fun testSavedFileInputsFollowTheSavedContentOnly() {
        val cfg = file("cfg/ansible.cfg", "[defaults]\nhash_behaviour = merge\n")
        val cache = cache<String, String>()
        fun value() = read { cache.get("cfg") { VarsConfig.load(cfg).hashBehaviour.name } }
        assertEquals("MERGE", value())
        val document = FileDocumentManager.getInstance().getDocument(cfg)!!
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText("[defaults]\nhash_behaviour = replace\n")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        assertEquals("MERGE", value())
        assertEquals("an unsaved document does not change what is read from disk", 1L, cache.stats.computations)
        WriteCommandAction.runWriteCommandAction(project) { FileDocumentManager.getInstance().saveDocument(document) }
        assertEquals("REPLACE", value())
        assertEquals(2L, cache.stats.computations)
    }

    fun testCreatingAFileChangesTheLayout() {
        val cache = cache<String, Int>()
        read { cache.get("k") { 1 } }
        val layout = read { ModelCaches.getInstance(project).layout() }
        file("new/dir/file.yml", "a: 1\n")
        assertTrue(read { ModelCaches.getInstance(project).layout() } > layout)
        read { cache.get("k") { 2 } }
        assertEquals(2L, cache.stats.computations)
    }

    fun testToolDirectoriesAreNoFileTreeChange() {
        val before = read { ModelCaches.getInstance(project).fileTree.modificationCount }
        myFixture.tempDirFixture.createFile(".git/objects/ab/cdef", "x")
        myFixture.tempDirFixture.createFile("node_modules/pkg/index.js", "x")
        assertEquals(before, read { ModelCaches.getInstance(project).fileTree.modificationCount })
    }

    fun testUntrackedReadsAreNoInputs() {
        val a = file("u/a.yml", "a: 1\n")
        val cache = cache<String, Int>()
        fun value() = read { cache.get("k") { ModelInputs.untracked { keys(a) }.size } }
        assertEquals(1, value())
        type(a, "b: 2\n")
        assertEquals("the untracked read did not become an input", 1, value())
    }

    fun testPeekAndLast() {
        val a = file("p/a.yml", "a: 1\n")
        val cache = cache<String, List<String>>()
        assertNull(read { cache.peek("k") })
        val first = read { cache.get("k") { keys(a) } }
        assertSame(first, read { cache.peek("k") })
        type(a, "b: 2\n")
        assertNull("a stale entry is not current", read { cache.peek("k") })
        assertSame("but it is still the last complete value", first, cache.last("k"))
        assertEquals("peek computes nothing", 1L, cache.stats.computations)
    }

    fun testSnapshotDeltasPerKind() {
        val caches = ModelCaches.getInstance(project)
        val model = cache<String, Int>()
        val presentation = cache<String, Int>(ModelCacheKind.PRESENTATION)
        val before = caches.snapshot()
        read {
            model.get("a") { 1 }
            presentation.get("a") { 1 }
            presentation.get("b") { 2 }
        }
        val after = caches.snapshot()
        assertEquals(mapOf(model.stats.name to 1L), after.computationsSince(before))
        assertEquals(mapOf(presentation.stats.name to 2L), after.computationsSince(before, ModelCacheKind.PRESENTATION))
        assertEquals(2L, after.computationsOf(presentation.stats.name, before))
        assertTrue(after.invalidationsSince(before).isEmpty())
    }
}
