package de.terletzkiy.ansibility.typeflow

import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.schema.OptionType

/**
 * ANS-T020 end to end on members of registered results (plan amendment FU, F1.12: "`{{ x.rc }}` is an int"): the
 * evaluator asks [ChainResolver.memberType] for `r.stdout` and `r.rc`, so a documented `str` member templated into an
 * `int` option is a finding, an `int` member into it is not, and undocumented members stay unknown. ANS-T020 judges
 * values ansible-core validates against role argument specs, so the `int` option is the role's `waiter_port`, passed
 * to `ansible.builtin.wait_for` by the same task.
 */
class TemplatedRegisteredTypesTest : TemplatedTestCase() {
    private val tasks = "site/roles/waiter/tasks/main.yml"

    override fun setUp() {
        super.setUp()
        createFile("site/ansible.cfg", "[defaults]")
        createFile("site/playbook.yml", "- hosts: all\n  roles: [waiter]")
        createFile(
            "site/roles/waiter/meta/argument_specs.yml",
            """
            argument_specs:
              main:
                short_description: Waits for a port
                options:
                  waiter_port:
                    type: int
                  waiter_host:
                    type: str
            """,
        )
        createFile(
            tasks,
            """
            - name: Echo the port
              ansible.builtin.command: echo 1
              register: r
            - name: Wait for the echoed port
              ansible.builtin.wait_for:
                port: "{{ waiter_port }}"
              vars:
                waiter_port: "{{ r.stdout }}"
            - name: Wait for the exit code
              ansible.builtin.wait_for:
                port: "{{ waiter_port }}"
              vars:
                waiter_port: "{{ r.rc }}"
            - name: Wait for an undocumented key
              ansible.builtin.wait_for:
                port: "{{ waiter_port }}"
              vars:
                waiter_port: "{{ r.nope }}"
            - name: Wait on a host named by the exit code
              ansible.builtin.wait_for:
                host: "{{ waiter_host }}"
              vars:
                waiter_host: "{{ r.rc }}"
            """,
        )
    }

    private fun lines(): Map<Int, TemplatedFinding> = findings(tasks).associateBy { lineOf(tasks, it.range.startOffset) }

    fun testADocumentedStrMemberIntoAnIntOptionIsAFinding() {
        for (core in listOf(CoreVersion(2, 18, 8), CoreVersion(2, 21, 4))) {
            target(core)
            val stdout = lines()[8] ?: error("no finding for r.stdout on $core: ${lines()}")
            assertEquals(OptionType.Int, stdout.documented)
            assertEquals(listOf("waiter_port"), stdout.path)
            assertEquals("str", stdout.types.logical.types.single().pyName)
            assertTrue(stdout.message, "but this is str via `r.stdout` (tasks/main.yml:3)" in stdout.message)
        }
    }

    fun testAnIntMemberIsAnIntAndUndocumentedMembersStayUnknown() {
        target(CoreVersion(2, 18, 8))
        assertEquals("r.rc and r.nope into the int option, r.rc into the str option on 2.18: silent", setOf(8), lines().keys)
        target(CoreVersion(2, 21, 4))
        val findings = lines()
        assertEquals("2.21 hands the int over as an int", setOf(8, 23), findings.keys)
        val host = findings.getValue(23)
        assertEquals(OptionType.Str, host.documented)
        assertEquals("int", host.types.logical.types.single().pyName)
        assertTrue(host.message, "but this is int via `r.rc`" in host.message)
    }

    fun testTheEditorShowsTheFinding() {
        target(CoreVersion(2, 18, 8))
        val infos = highlights(tasks)
        assertEquals(listOf(8), infos.map(::line))
        assertTrue(lineText(8), "r.stdout" in lineText(8))
    }
}
