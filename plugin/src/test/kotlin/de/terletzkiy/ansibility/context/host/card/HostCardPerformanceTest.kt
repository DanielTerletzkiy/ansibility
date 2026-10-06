package de.terletzkiy.ansibility.context.host.card

import com.intellij.testFramework.PlatformTestUtil
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.model.inventory.ModelCaches
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext

/**
 * The card's budget (plan amendment R7/R8, Testing §5: "hover with the All-hosts breakdown p95 < 50 ms"): the whole
 * card, Effective section, Effect row, ranked "Set in" and every other section included, warm, on the falcon fixture, for
 * the busiest cards (a template reference over a role's reach, a `group_vars` key hit by every play, a playbook-level
 * key over every host of every environment, a role default), in All mode and with a host selected. Warm cards reuse the
 * cached views: rebuilding them evaluates no new card view. Timing tests may flake under machine load; re-run alone.
 */
@RequiresInfraFixture
class HostCardPerformanceTest : HostCardTestCase() {
    private fun cards(): List<Pair<String, () -> String>> = listOf(
        "template" to { card(POSTFIX_TEMPLATE, 9, "postfix_relayhost") },
        "group_vars" to { card(FALCON_PROD_ALL, 471, "postfix_relayhost") },
        "playbook group_vars" to { card(FALCON_PLAYBOOK_ALL, 4, "environment_group") },
        "defaults" to { card(POSTFIX_DEFAULTS, 2, "postfix_relayhost") },
        "vault chain" to { card(ALLOY_TEMPLATE, 16, "alloy_tenant_api_key") },
    )

    /** The 95th percentile of [runs] warm builds of [build] (after two warm-up builds), in milliseconds. */
    private fun p95(build: () -> String, runs: Int = 20): Double {
        repeat(2) { build() }
        val times = (1..runs).map {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val start = System.nanoTime()
            build()
            (System.nanoTime() - start) / 1_000_000.0
        }.sorted()
        return times[(times.size * 95 + 99) / 100 - 1]
    }

    fun testWarmCardsStayUnder50Milliseconds() {
        for (selection in listOf(RootContext.DEFAULT, RootContext(EnvironmentChoice.Named("prod"), "prod-prod1"))) {
            withSelection(FALCON, selection) {
                for ((name, build) in cards()) {
                    val p95 = p95(build)
                    println("HostCardPerformanceTest: $name card ($selection) warm p95 $p95 ms")
                    assertTrue("$name card p95 $p95 ms", p95 < BUDGET_MILLIS)
                }
            }
        }
    }

    fun testWarmCardsReuseTheCachedViews() {
        cards().forEach { (_, build) -> build() }
        val caches = ModelCaches.getInstance(project)
        val snapshot = caches.snapshot()
        cards().forEach { (_, build) -> build() }
        val now = caches.snapshot()
        assertEquals("no card view is evaluated again", 0L, now.computationsOf(HostCardViews.CACHE_NAME, snapshot))
        assertEquals("no model cache recomputes", emptyMap<String, Long>(), now.computationsSince(snapshot))
    }

    private companion object {
        const val BUDGET_MILLIS = 50.0
    }
}
