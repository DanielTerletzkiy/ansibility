package de.terletzkiy.ansibility.completion.jinja

import com.intellij.openapi.application.runReadActionBlocking
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.resolve.loop.LoopItemTyper
import de.terletzkiy.ansibility.resolve.register.RegisteredResults
import de.terletzkiy.ansibility.semantics.schema.OptionType

/**
 * Two rules of typed register results (plan amendment FU, F1.12) that follow ansible-core's task executor and
 * variable precedence:
 * - a looping task's own `until`/`changed_when`/`failed_when` run once per item, with the register holding that
 *   item's result (`pings.rc`), not the loop result (`pings.results`);
 * - a registered result ranks above task and block vars (`VariableManager.get_vars`: the set_fact/register layer is
 *   merged after the task vars), so a task's `vars:` of the same name do not shadow it; an include task's `vars:`
 *   (include parameters) do.
 */
class RegisteredRunAndPrecedenceTest : JinjaCompletionTestCase() {
    private val tasks = "loops/roles/demo/tasks/main.yml"
    private val included = "loops/roles/demo/tasks/other.yml"

    private val text = """
        - name: Echo
          ansible.builtin.command: echo 1
          register: out
        - name: Use with task vars
          ansible.builtin.debug:
            msg: "{{ PLACEHOLDER }}"
          vars:
            out: {foo: 1}
        - name: Wait per item
          ansible.builtin.command: ping -c1 {{ item }}
          loop: [a, b]
          register: pings
          until: pings.rc == 0
          changed_when: pings.stdout != ''
        - name: Each line
          ansible.builtin.debug:
            msg: "{{ item }}"
          loop: "{{ out.stdout_lines }}"
          vars:
            out: {stdout_lines: [{a: 1}]}
        - name: Include with vars
          ansible.builtin.include_tasks: other.yml
          when: out.bar
          vars:
            out: {bar: 1}
        - name: After the loop
          ansible.builtin.debug:
            msg: "{{ pings.results }}"
    """.trimIndent() + "\n"

    override fun setUp() {
        super.setUp()
        myFixture.tempDirFixture.createFile("loops/ansible.cfg", "[defaults]\n")
        myFixture.tempDirFixture.createFile("loops/playbook.yml", "- hosts: all\n  roles:\n    - demo\n")
        myFixture.tempDirFixture.createFile(tasks, text)
        myFixture.tempDirFixture.createFile(included, "- name: Included\n  ansible.builtin.debug:\n    msg: \"{{ PLACEHOLDER }}\"\n")
        refreshRoots()
    }

    private fun lineOf(marker: String): Int = text.lines().indexOfFirst { marker in it }.also { check(it >= 0) { "no '$marker'" } } + 1

    fun testTheUntilOfALoopingTaskSeesOneItemsResult() {
        val until = strings(completeAfterEdit(tasks, lineOf("until:"), "pings.rc", "pings."))
        assertContainsElements(until, "rc", "stdout", "stdout_lines", "attempts", "changed", "failed")
        assertDoesntContain(until, "results", "item", "ansible_loop_var")
        reset(tasks)
        val changedWhen = strings(completeAfterEdit(tasks, lineOf("changed_when:"), "pings.stdout", "pings."))
        assertContainsElements(changedWhen, "rc", "stdout")
        assertDoesntContain(changedWhen, "results")
        reset(tasks)
        assertEquals(
            "after the task the register holds the loop result",
            listOf("results", "msg", "changed", "failed", "skipped"),
            strings(completeAfterEdit(tasks, lineOf("{{ pings.results }}"), "pings.results", "pings.")),
        )
    }

    fun testTheServiceGivesTheCurrentRunOnlyInsideTheTasksOwnResultKeys() {
        val (inside, after) = runReadActionBlocking {
            val file = vf(tasks)
            val results = RegisteredResults.getInstance(project)
            results.at(file, text.indexOf("pings.rc"), "pings") to results.at(file, text.indexOf("{{ pings.results }}") + 3, "pings")
        }
        assertEquals(OptionType.Int, inside!!.member(listOf("rc"))!!.type)
        assertNull(inside.member(listOf("results")))
        assertNotNull(after!!.member(listOf("results", "0", "item")))
        assertNull(after.member(listOf("rc")))
    }

    fun testTaskVarsDoNotShadowARegisteredResult() {
        val items = strings(completeAfterEdit(tasks, lineOf("PLACEHOLDER"), "PLACEHOLDER", "out."))
        assertContainsElements(items, "stdout", "rc", "changed")
        assertDoesntContain(items, "foo")
        reset(tasks)
        val item = runReadActionBlocking {
            val yaml = YamlFiles.yamlFile(project, vf(tasks))!!
            LoopItemTyper.typeAt(project, yaml, text.indexOf("name: Each line"))?.loop?.item
        }
        assertEquals("the loop reads the registered stdout_lines", OptionType.Str, item!!.type)
    }

    fun testIncludeParametersShadowARegisteredResult() {
        val items = strings(completeAfterEdit(included, 3, "PLACEHOLDER", "out."))
        assertDoesntContain(items, "stdout", "rc")
        reset(included)
        val own = strings(completeAfterEdit(tasks, lineOf("when: out.bar"), "out.bar", "out."))
        assertContainsElements("the include task's own vars are include parameters", own, "bar")
        assertDoesntContain(own, "stdout", "rc")
    }
}
