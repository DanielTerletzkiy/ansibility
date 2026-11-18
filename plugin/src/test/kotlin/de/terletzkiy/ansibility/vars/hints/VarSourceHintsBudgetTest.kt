package de.terletzkiy.ansibility.vars.hints

import com.intellij.openapi.application.runReadActionBlocking
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext
import de.terletzkiy.ansibility.settings.SettingsTestSupport
import de.terletzkiy.ansibility.vars.VarsTestCase
import java.util.Locale

/**
 * Plan amendment R31, D237: the variable source hints of a task file with 300 variable references take under 100 ms
 * warm, from cached data only. The project is shaped like the infra fixture's roots: a role with 30 declared variables,
 * four environments with `group_vars` (all and a group) and `host_vars`, playbook-level `group_vars`, and a play applying
 * the role; the task file reads each variable ten times (values, bare conditions, several per line).
 *
 * The build machine may be busy (parallel builds): a series over budget is measured again, the best median counts.
 */
class VarSourceHintsBudgetTest : VarsTestCase() {
    override fun setUp() {
        super.setUp()
        val names = (1..NAMES).map { "app_var_$it" }
        create("b/ansible.cfg", "[defaults]\nroles_path = roles\n")
        create("b/site.yml", "---\n- name: Web\n  hosts: web\n  roles:\n    - app\n")
        create("b/roles/app/meta/argument_specs.yml", "---\nargument_specs:\n  main:\n    options:\n" + names.joinToString("") { "      $it:\n        type: str\n" })
        create("b/roles/app/defaults/main.yml", "---\n" + names.joinToString("") { "$it: default-$it\n" })
        create("b/group_vars/all.yml", "---\n" + names.filterIndexed { i, _ -> i % 3 == 0 }.joinToString("") { "$it: playbook-$it\n" })
        for (environment in ENVIRONMENTS) {
            val hosts = (1..HOSTS).map { "$environment-web$it" }
            create("b/environments/$environment/hosts.yml", "---\nall:\n  children:\n    web:\n      hosts:\n" + hosts.joinToString("") { "        $it:\n" })
            create("b/environments/$environment/group_vars/all.yml", "---\n" + names.joinToString("") { "$it: $environment-$it\n" })
            create("b/environments/$environment/group_vars/web.yml", "---\n" + names.filterIndexed { i, _ -> i % 2 == 0 }.joinToString("") { "$it: web-$it\n" })
            create("b/environments/$environment/host_vars/${hosts.first()}.yml", "---\n" + names.filterIndexed { i, _ -> i % 5 == 0 }.joinToString("") { "$it: host-$it\n" })
        }
        val tasks = StringBuilder("---\n")
        var uses = 0
        var task = 0
        while (uses < USES) {
            val name = names[task % names.size]
            val other = names[(task + 7) % names.size]
            task++
            tasks.append("- name: Task $task\n  ansible.builtin.debug:\n    msg: \"{{ $name }} and {{ $other }} and {{ $name | default('') }}\"\n  when: $other | length > 0\n")
            uses += 4
        }
        create(TASKS, tasks.toString())
        refreshRoots()
    }

    override fun tearDown() {
        try {
            SettingsTestSupport.resetAll(project)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun create(path: String, text: String) {
        myFixture.tempDirFixture.createFile(path, text)
    }

    private fun collect(): List<VarSourceHints.Hint> = inBackgroundReadAction { VarSourceHints.collect(project, psi(TASKS), false) }

    private fun timed(): Long = inBackgroundReadAction {
        val start = System.nanoTime()
        VarSourceHints.collect(project, psi(TASKS), false)
        (System.nanoTime() - start) / 1_000_000
    }

    private fun measure(label: String, budget: Long) {
        val hints = collect()
        assertTrue("at least 300 hints: ${hints.size}", hints.size >= USES)
        var best = Long.MAX_VALUE
        for (series in 1..MAX_SERIES) {
            val samples = (1..ROUNDS).map { timed() }.sorted()
            best = minOf(best, samples[samples.size / 2])
            println(String.format(Locale.ROOT, "Variable source hints %s (%d hints), series %d: median %d ms, max %d ms", label, hints.size, series, samples[samples.size / 2], samples.last()))
            if (best < budget) break
        }
        assertTrue("$label: median $best ms (budget $budget ms)", best < budget)
    }

    fun testThreeHundredUsagesUnderOneHundredMillisecondsWarm() {
        measure("All", BUDGET_MS)
    }

    /** With an environment selected each hint also names the winner (the card's cached single-name evaluation). */
    fun testWithASelectedEnvironmentStillUnderBudgetWarm() {
        val root = runReadActionBlocking { AnsibleWorkspace.getInstance(project).contextOf(vf(TASKS))!!.root }
        AnsibleContextService.getInstance(project).setSelection(root, RootContext(environment = EnvironmentChoice.Named("prod")))
        assertTrue(collect().first().text.startsWith("→ "))
        measure("prod", BUDGET_MS)
    }

    private companion object {
        const val TASKS = "b/roles/app/tasks/main.yml"
        const val NAMES = 30
        const val USES = 300
        const val HOSTS = 3
        val ENVIRONMENTS = listOf("dev", "prod", "stage", "test")
        const val BUDGET_MS = 100L
        const val ROUNDS = 7
        const val MAX_SERIES = 4
    }
}
