package de.terletzkiy.ansibility.completion.keys

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.ExtensionTestUtil
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.CompletionSource
import java.util.Collections

/**
 * Plan M3 acceptance 10 (completion p95 under 100 ms) for key and value completion on the falcon fixture: each round
 * edits the file first (so every cache that depends on the YAML PSI is rebuilt, as after a keystroke), then completes.
 * Reports p50/p95/max of this package's sources alone and of the whole `completeBasic` (all contributors, the popup);
 * asserts the sources' p95 with head room for slow CI machines.
 */
class KeyCompletionTimingTest : KeyCompletionTestCase() {
    /** Wraps a source and records how long each call takes. */
    private class Timed(private val delegate: CompletionSource) : CompletionSource {
        val nanos: MutableList<Long> = Collections.synchronizedList(ArrayList())

        override fun complete(site: AnsibleSite?, parameters: CompletionParameters, result: CompletionResultSet) {
            val start = System.nanoTime()
            try {
                delegate.complete(site, parameters, result)
            } finally {
                nanos += System.nanoTime() - start
            }
        }
    }

    private val keys = Timed(VarsKeyCompletionSource())
    private val values = Timed(VarsValueCompletionSource())

    override fun setUp() {
        super.setUp()
        copyInfra(FALCON)
        val disposable = Disposer.newDisposable(testRootDisposable, "timed sources")
        ExtensionTestUtil.maskExtensions(CompletionSource.EP_NAME, listOf(keys, values), disposable)
    }

    fun testTopLevelKeyCompletionTime() = measure("top-level postfix_", PROD_VARS, 472, "postfix_$CARET")

    fun testNestedKeyCompletionTime() = measure("haproxy_servers item", PROD_VARS, 546, "  - $CARET")

    fun testValueCompletionTime() = measure("system_apt_debian_version value", PROD_VARS, 546, "system_apt_debian_version: $CARET")

    fun testGoldenDefaultsCompletionTime() {
        copyInfra("golden/roles", "golden/playbooks")
        measure("golden haproxy defaults", "golden/roles/haproxy/defaults/main.yml", 2, "haproxy_$CARET")
    }

    /** The first completion after a structure change rebuilds every cache (reported, not budgeted). */
    fun testColdTopLevelCompletionTime() {
        assertTrue(completeInsertingLine(PROD_VARS, 472, "postfix_$CARET").isNotEmpty())
        keys.nanos.clear()
        values.nanos.clear()
        repeat(COLD_ROUNDS) {
            LookupManager.hideActiveLookup(project)
            refreshRoots()
            myFixture.completeBasic()
            assertTrue(ours().isNotEmpty())
        }
        val cold = stats(keys.nanos.zip(values.nanos) { a, b -> a + b })
        println("[w4-keycomp timing] cold top-level postfix_ (after a structure change): sources $cold (n=$COLD_ROUNDS)")
    }

    private fun measure(label: String, path: String, line: Int, text: String) {
        assertTrue("items at $label", completeInsertingLine(path, line, text).isNotEmpty())
        val caret = myFixture.editor.caretModel.offset
        val total = ArrayList<Long>()
        keys.nanos.clear()
        values.nanos.clear()
        repeat(WARM_UP + ROUNDS) { round ->
            LookupManager.hideActiveLookup(project)
            // A keystroke elsewhere in the file: every PSI-dependent cache (roles, plays, variables) is dropped.
            val document = myFixture.editor.document
            WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "#\n") }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            myFixture.editor.caretModel.moveToOffset(caret + 2 * (round + 1))
            val start = System.nanoTime()
            myFixture.completeBasic()
            val elapsed = System.nanoTime() - start
            if (round >= WARM_UP) total += elapsed
            assertTrue("items at $label in round $round", ours().isNotEmpty())
        }
        val own = (keys.nanos.drop(WARM_UP).zip(values.nanos.drop(WARM_UP)) { a, b -> a + b })
        val ownStats = stats(own)
        val totalStats = stats(total)
        println("[w4-keycomp timing] $label: sources $ownStats; completeBasic $totalStats (n=$ROUNDS)")
        assertTrue("p95 of the key and value sources at $label: $ownStats", ownStats.p95 < SOURCE_P95_BUDGET_MS)
    }

    private data class Stats(val p50: Double, val p95: Double, val max: Double) {
        override fun toString(): String = "p50 %.1f ms, p95 %.1f ms, max %.1f ms".format(p50, p95, max)
    }

    private fun stats(nanos: List<Long>): Stats {
        val sorted = nanos.map { it / 1_000_000.0 }.sorted()
        fun percentile(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
        return Stats(percentile(0.5), percentile(0.95), sorted.last())
    }

    companion object {
        private const val WARM_UP = 3
        private const val ROUNDS = 40
        private const val COLD_ROUNDS = 10

        /** The plan's budget is 100 ms for the whole popup; the sources alone get the same budget with CI head room. */
        private const val SOURCE_P95_BUDGET_MS = 100.0
    }
}
