package de.terletzkiy.ansibility.context.host

import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.model.effective.ExecutionSources
import de.terletzkiy.ansibility.model.effective.HostViews
import de.terletzkiy.ansibility.model.inventory.InventoryModels
import de.terletzkiy.ansibility.model.inventory.InventoryTestCase
import java.util.Locale

/**
 * Timing and memory report of the model caches on the whole infra fixture (plan amendment R7/R8, Testing §5; the
 * real-repo numbers come from the opt-in corpus run of the same measurements):
 * - every inventory view (env, host, playbook dir) computed by the engine, JIT-warm, and read from the cache;
 * - every reachable (play, host) execution view on top of the cached views and inputs;
 * - a [RootEffectiveSummary] per root built on a warm model;
 * - the estimated resident size of the views and summaries (budget ≤ 5 MB).
 *
 * The numbers are printed; the assertions are an order of magnitude above the amendment's per-view budgets, so a loaded
 * build machine does not make them flaky.
 */
class CachingReportTest : InventoryTestCase() {
    private val impl: AnsibleContextServiceImpl get() = AnsibleContextServiceImpl.getInstance(project)!!

    private val views: HostViews get() = HostViews.getInstance(project)

    override fun setUp() {
        super.setUp()
        myFixture.copyDirectoryToProject(InfraTestData.INFRA, "")
        refreshRoots()
    }

    private class ViewSet(val root: AnsibleRoot, val environment: String, val playbookDir: VirtualFile)

    private fun inventoryRoots(): List<AnsibleRoot> = AnsibleWorkspace.getInstance(project).roots()
        .filter { it.kind == RootKind.PROJECT && !it.detached && InventoryModels.getInstance(project).environments(it).isNotEmpty() }

    private fun viewSets(): List<ViewSet> = inventoryRoots().flatMap { root ->
        val dirs = (listOf(root.dir) + impl.model.playHits(root).map { it.play.playbookDir }).distinct()
        InventoryModels.getInstance(project).environments(root).flatMap { environment -> dirs.map { ViewSet(root, environment.name, it) } }
    }

    /** Reads every view of [sets]; the number of host views. */
    private fun readAll(sets: List<ViewSet>): Int = sets.sumOf { views.views(it.root, it.environment, it.playbookDir)!!.hosts.size }

    private fun millis(nanos: Long): String = "%.2f".format(Locale.ROOT, nanos / 1e6)

    private inline fun timed(action: () -> Unit): Long {
        val start = System.nanoTime()
        action()
        return System.nanoTime() - start
    }

    fun testTimingAndFootprintOfEveryViewOfTheFixture() {
        val sets = viewSets()
        var count = 0
        val cold = timed { count = readAll(sets) }
        assertTrue("the fixture has views of several roots: $count", count >= 10)

        // JIT-warm engine time: drop the view entries (the environments and documents stay cached) and compute again.
        val engine = ArrayList<Long>()
        repeat(WARMUP + ROUNDS) { round ->
            views.clear()
            val nanos = timed { assertEquals(count, readAll(sets)) }
            if (round >= WARMUP) engine += nanos
        }
        val lookups = ArrayList<Long>()
        repeat(ROUNDS) { lookups += timed { assertEquals(count, readAll(sets)) } }

        // Every reachable (play, host) execution view on the cached views and inputs.
        val sources = ExecutionSources.getInstance(project)
        var executionCount = 0
        val execution = ArrayList<Long>()
        repeat(WARMUP + ROUNDS) { round ->
            var n = 0
            val nanos = timed {
                for (root in inventoryRoots()) {
                    for (hit in impl.model.playHits(root)) {
                        val inputs = sources.inputs(impl.evaluator.executionRoot(hit.play, root), hit.play, null)
                        for ((environment, hosts) in hit.hostsByEnvironment) {
                            val set = views.views(root, environment, hit.play.playbookDir)!!
                            for (host in hosts) {
                                val view = set[host]!!
                                view.engine.executionView(view.view, inputs.sources)
                                n++
                            }
                        }
                    }
                }
            }
            executionCount = n
            if (round >= WARMUP) execution += nanos
        }

        // A summary per root on the warm model.
        val summaries = RootEffectiveSummaries.getInstance(project)
        inventoryRoots().forEach(summaries::compute)
        val summaryTimes = ArrayList<Long>()
        repeat(WARMUP + ROUNDS) { round ->
            summaries.clear()
            val nanos = timed { inventoryRoots().forEach(summaries::compute) }
            if (round >= WARMUP) summaryTimes += nanos
        }
        val summaryBytes = inventoryRoots().sumOf { summaries.compute(it).estimatedBytes() }
        val viewBytes = views.estimatedBytes()

        val e = engine.sorted()
        val l = lookups.sorted()
        val x = execution.sorted()
        val s = summaryTimes.sorted()
        println(
            "Model caches on the infra fixture (${inventoryRoots().size} roots, ${sets.size} view entries, $count inventory views, " +
                "$executionCount execution views): inventory views cold ${millis(cold)} ms, warm engine median ${millis(e[e.size / 2])} ms " +
                "(${millis(e[e.size / 2] / count)} ms/view), cached lookups median ${millis(l[l.size / 2])} ms; execution views warm median " +
                "${millis(x[x.size / 2])} ms (${millis(x[x.size / 2] / maxOf(executionCount, 1))} ms/view); summaries of all roots median " +
                "${millis(s[s.size / 2])} ms; estimated memory: views ${viewBytes / 1024} KB + summaries ${summaryBytes / 1024} KB",
        )
        assertTrue("warm inventory views ${millis(e[e.size / 2] / count)} ms/view", e[e.size / 2] / count < VIEW_BUDGET_NANOS)
        assertTrue("cached lookups ${millis(l[l.size / 2])} ms", l[l.size / 2] < LOOKUP_BUDGET_NANOS)
        assertTrue("execution views", x[x.size / 2] / maxOf(executionCount, 1) < EXECUTION_BUDGET_NANOS)
        assertTrue("summaries", s[s.size / 2] / inventoryRoots().size < SUMMARY_BUDGET_NANOS)
        assertTrue("views + summaries ${(viewBytes + summaryBytes) / 1024} KB ≤ 5 MB", viewBytes + summaryBytes <= MEMORY_BUDGET_BYTES)
    }

    private companion object {
        const val WARMUP = 5
        const val ROUNDS = 15

        /** 10 × the amendment's 0.11 ms per inventory view (88 views ≤ 10 ms) and 0.16 ms per execution view. */
        const val VIEW_BUDGET_NANOS = 1_100_000L
        const val EXECUTION_BUDGET_NANOS = 1_600_000L

        /** Reading every cached view of the fixture. */
        const val LOOKUP_BUDGET_NANOS = 20_000_000L

        /** 5 × the amendment's 50 ms per root. */
        const val SUMMARY_BUDGET_NANOS = 250_000_000L

        const val MEMORY_BUDGET_BYTES = 5L * 1024 * 1024
    }
}
