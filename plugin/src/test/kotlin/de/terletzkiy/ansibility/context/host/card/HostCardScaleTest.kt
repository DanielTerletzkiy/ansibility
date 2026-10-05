package de.terletzkiy.ansibility.context.host.card

import com.intellij.testFramework.PlatformTestUtil
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext

/**
 * The card's budget when one variable has a value per host (plan amendment R7/R8, Testing §5): a synthetic environment
 * of [HOSTS] hosts in falcon, each with its own `host_vars` value, so there is one outcome per host and "Set in" ranks
 * [HOSTS] definitions. The Effective section and the Effect row list the largest outcomes and count the rest, and the
 * median warm card (the whole card, built as the platform builds it) stays under 50 ms in All mode, with the environment
 * and with one host selected. Timing tests may flake under machine load; re-run alone.
 */
class HostCardScaleTest : HostCardTestCase() {
    override fun addFixtureFiles() {
        super.addFixtureFiles()
        add("$BIG/hosts.yml", "all:\n  hosts:\n" + hosts.joinToString("") { "    $it:\n" })
        add(BIG_ALL, "---\nha4_port: 1000")
        hosts.forEachIndexed { index, host -> add("$BIG/host_vars/$host/vars.yml", "---\nha4_port: ${2000 + index}") }
    }

    private val hosts = (1..HOSTS).map { "big-%03d".format(it) }

    /** The median of [runs] warm builds of [build] (after two warm-up builds), in milliseconds. */
    private fun median(build: () -> String, runs: Int = 21): Double {
        repeat(2) { build() }
        val times = (1..runs).map {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val start = System.nanoTime()
            build()
            (System.nanoTime() - start) / 1_000_000.0
        }.sorted()
        return times[times.size / 2]
    }

    fun testAValuePerHostStaysUnderBudget() {
        val html = card(BIG_ALL, 2, "ha4_port")
        val section = effective(html)
        assertTrue(section, section.startsWith("Effective on $HOSTS hosts"))
        assertTrue(section, "big-001 = 2000 · environments/big/host_vars/big-001/vars.yml:2" in section)
        assertTrue("the largest outcomes, then a count: $section", "big-010 = 2009" in section && "big-011" !in section)
        assertTrue(section, "+140 more values on 140 hosts" in section)
        val setIn = row(html, "Set in")!!
        assertTrue("Set in lists every definition: $setIn", "environments/big/host_vars/big-150/vars.yml:2 · inventory host_vars/big-150 · level 9 · 2149 · wins on 1 of $HOSTS hosts" in setIn)
        val effect = row(html, "Effect")!!
        assertTrue(effect, effect.startsWith("✗ ineffective, shadowed on every host that loads it:"))
        assertTrue(effect, "for big-008 by environments/big/host_vars/big-008/vars.yml:2 (L9 beats L4)" in effect && "big-009" !in effect)
        assertTrue(effect, effect.endsWith("+142 more definitions win on 142 hosts"))
        for (selection in listOf(RootContext.DEFAULT, RootContext(EnvironmentChoice.Named("big")), RootContext(EnvironmentChoice.Named("big"), "big-042"))) {
            withSelection(FALCON, selection) {
                val median = median({ card(BIG_ALL, 2, "ha4_port") })
                println("HostCardScaleTest: $HOSTS hosts ($selection) warm median $median ms")
                assertTrue("$selection: median $median ms", median < BUDGET_MILLIS)
            }
        }
    }

    private companion object {
        const val HOSTS = 150
        const val BIG = "$FALCON/environments/big"
        const val BIG_ALL = "$BIG/group_vars/all/vars.yml"
        const val BUDGET_MILLIS = 50.0
    }
}
