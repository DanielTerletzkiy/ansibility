package de.terletzkiy.ansibility.completion.jinja

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.PlatformTestUtil
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.CompletionSource
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture

/**
 * Completion latency on the whole sanitised infra fixture (M3 acceptance 10: p95 < 100 ms) at the acceptance sites,
 * each run after an edit of the file, so no PSI-dependent cache of the previous run is reused:
 * - **source**: the time [JinjaVarCompletionSource] takes inside the dispatcher (what this work unit controls);
 * - **end to end**: the platform's basic completion with every contributor and the lookup.
 *
 * The values are printed (`JINJACOMP: timing …`). Between runs the IDE event queue is drained, as the IDE does between
 * keystrokes: each completion leaves `invokeLater` work behind that holds its `CompletionProgressIndicator` and, with
 * it, range markers at the caret of the host document. Left queued (the test thread never yields), they pile up at
 * one interval, and above 30 markers the platform's unit-test check runs `System.gc()` on every new marker there; in
 * Jinja injected into YAML (M5) each completion leaves markers in the host document twice (fragment and host offsets),
 * which pushed end-to-end runs past a second. The end-to-end bound still leaves room for the occasional run that hits
 * that check before the queued work has released its markers.
 */
@RequiresInfraFixture
class JinjaCompletionTimingTest : JinjaCompletionTestCase() {
    private class Site(val path: String, val line: Int, val old: String, val new: String, val expected: String)

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

    fun testCompletionLatencyOnTheFixture() {
        myFixture.copyDirectoryToProject(InfraTestData.INFRA, "")
        refreshRoots()
        val sourceTimes = ArrayList<Double>()
        val others = CompletionSource.EP_NAME.extensionList.filter { it !is JinjaVarCompletionSource }
        ExtensionTestUtil.maskExtensions(CompletionSource.EP_NAME, others + TimedSource(JinjaVarCompletionSource(), sourceTimes), testRootDisposable)
        val sites = listOf(
            Site("golden/roles/haproxy/tasks/configure.yml", 13, "haproxy_log_path", "haproxy_", "haproxy_servers"),
            Site("repos/falcon/ansible/roles/postfix/templates/main.cf.j2", 9, "postfix_relayhost", "postfix_", "postfix_relayhost"),
            Site("golden/roles/grafana/templates/nginx/main.site.conf.j2", 14, "item.floating.ssl.cert_file", "item.floating.ssl.", "cert_file"),
            Site("golden/roles/haproxy/templates/haproxy.cfg.j2", 76, "server.name", "server.", "port"),
            Site("golden/roles/chronod/tasks/nginx.yml", 15, "chronod_nginx_ssl_cert_name", "_chronod_", "_chronod_nginx_cert"),
            Site("repos/falcon/ansible/roles/postfix/tasks/main.yml", 4, "name: postfix", "name: \"{{ <caret> }}\"", "postfix_relayhost"),
        )
        val endToEnd = ArrayList<Double>()
        val measuredSource = ArrayList<Double>()
        for (site in sites) {
            repeat(RUNS + WARM_UP) { run ->
                if (run > 0) touch(site.path)
                var millis = 0.0
                val before = sourceTimes.size
                val items = completeAfterEdit(site.path, site.line, site.old, site.new) { complete ->
                    val start = System.nanoTime()
                    complete().also { millis = (System.nanoTime() - start) / 1e6 }
                }
                assertTrue("${site.expected} at ${site.path}:${site.line}: ${strings(items).take(20)}", site.expected in strings(items))
                if (run >= WARM_UP) {
                    endToEnd += millis
                    measuredSource += sourceTimes.subList(before, sourceTimes.size).sum()
                }
                reset(site.path)
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            }
        }
        val source = stats(measuredSource)
        val total = stats(endToEnd)
        println("JINJACOMP: timing: ${endToEnd.size} completions on the fixture; source $source; end to end $total")
        assertTrue("source p95 ${"%.1f".format(source.p95)} ms", source.p95 < SOURCE_P95_BUDGET_MS)
        assertTrue("end-to-end p50 ${"%.1f".format(total.p50)} ms", total.p50 < SOURCE_P95_BUDGET_MS)
        assertTrue("end-to-end p95 ${"%.1f".format(total.p95)} ms", total.p95 < END_TO_END_P95_BUDGET_MS)
    }

    private class Stats(val p50: Double, val p95: Double, val max: Double) {
        override fun toString(): String = "p50 ${"%.1f".format(p50)} ms, p95 ${"%.1f".format(p95)} ms, max ${"%.1f".format(max)} ms"
    }

    private fun stats(values: List<Double>): Stats {
        val sorted = values.sorted()
        return Stats(sorted[sorted.size / 2], sorted[(sorted.size * 95 + 99) / 100 - 1], sorted.last())
    }

    /** Appends a blank at the end of [path] and restores the file, so PSI and every cache that depends on it change. */
    private fun touch(path: String) {
        myFixture.configureFromExistingVirtualFile(vf(path))
        val document = myFixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, " ") }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        reset(path)
    }

    private companion object {
        const val WARM_UP = 2
        const val RUNS = 15

        /** The plan's target (M3 acceptance 10). */
        const val SOURCE_P95_BUDGET_MS = 100.0

        /** See the class comment: the harness's range-marker check can still add a `System.gc()` to a run. */
        const val END_TO_END_P95_BUDGET_MS = 250.0
    }
}
