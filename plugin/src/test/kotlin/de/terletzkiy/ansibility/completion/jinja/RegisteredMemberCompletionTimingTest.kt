package de.terletzkiy.ansibility.completion.jinja

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.PlatformTestUtil
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.CompletionSource
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture

/**
 * Warm latency of registered member completion (plan amendment FU, F1.12: "< 50 ms on the falcon fixture"), measured
 * inside [JinjaVarCompletionSource] (what this work unit controls) at real registers of the sanitised fixture's
 * `nginx` role: `dh_param.` (a `stat` result) and `dh_param.stat.` in its `when:`, each completion after an edit of
 * that line, as typing does. The values are printed (`REGISTERED: timing …`).
 */
@RequiresInfraFixture
class RegisteredMemberCompletionTimingTest : JinjaCompletionTestCase() {
    /** Delegates to the real source and records how long each call takes. */
    private class TimedSource(private val delegate: CompletionSource, private val times: MutableList<Double>) : CompletionSource {
        override fun complete(site: AnsibleSite?, parameters: CompletionParameters, result: CompletionResultSet) {
            val start = System.nanoTime()
            try {
                delegate.complete(site, parameters, result)
            } finally {
                times += (System.nanoTime() - start) / 1e6
            }
        }
    }

    fun testWarmMemberCompletionOnTheFalconFixture() {
        copyInfra("repos/falcon")
        val times = ArrayList<Double>()
        val others = CompletionSource.EP_NAME.extensionList.filter { it !is JinjaVarCompletionSource }
        ExtensionTestUtil.maskExtensions(CompletionSource.EP_NAME, others + TimedSource(JinjaVarCompletionSource(), times), testRootDisposable)
        val path = "repos/falcon/ansible/roles/nginx/tasks/main.yml"
        val sites = listOf("dh_param.stat." to "exists", "dh_param." to "stat")
        val measured = ArrayList<Double>()
        for ((typed, expected) in sites) {
            repeat(WARM_UP + RUNS) { run ->
                val before = times.size
                val items = completeAfterEdit(path, LINE, "dh_param.stat.exists", typed)
                assertTrue("$expected after $typed: ${strings(items)}", expected in strings(items))
                if (run >= WARM_UP) measured += times.subList(before, times.size).sum()
                reset(path)
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            }
        }
        val sorted = measured.sorted()
        val p50 = sorted[sorted.size / 2]
        val p95 = sorted[(sorted.size * 95 + 99) / 100 - 1]
        println("REGISTERED: timing: ${measured.size} member completions; source p50 ${"%.1f".format(p50)} ms, p95 ${"%.1f".format(p95)} ms, max ${"%.1f".format(sorted.last())} ms")
        assertTrue("source p95 ${"%.1f".format(p95)} ms", p95 < BUDGET_MS)
    }

    private companion object {
        /** `when: not dh_param.stat.exists` in the fixture's `nginx` role. */
        const val LINE = 21
        const val WARM_UP = 3
        const val RUNS = 15

        /** The plan's target for warm member completion. */
        const val BUDGET_MS = 50.0
    }
}
