package de.terletzkiy.ansibility.types

import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture

/**
 * The X79 loop reads of the type checks ([LoopReads]) and the FU2 indirect entries of `ansible.var.use`: reads by
 * name never count as reads of a loop's items, so the findings keep the attributes of the direct reads only (the same
 * expectation as `TypeCheckServiceTest.testLoopReadsNameTheReadingFiles`).
 */
@RequiresInfraFixture
class LoopReadsIndirectTest : TypeCheckTestCase() {
    override fun setUp() {
        super.setUp()
        copyInfra("repos/falcon/ansible")
    }

    fun testReadsByNameAreNoLoopItemReads() {
        createFile(
            "repos/falcon/ansible/roles/totp-token/tasks/extra.yml",
            """
            - name: Read the items, and other hosts' and named variables called item
              ansible.builtin.debug:
                msg: >-
                  {{ item.name }} {{ item.mode }} {{ hostvars[groups['all'][0]].item.from_other_host }}
                  {{ vars['item'].by_name }} {{ groups['all'] | map('extract', hostvars, ['item', 'extracted']) | list }}
              loop: "{{ totp_users | default([]) }}"
            - name: Loop over another host's variable
              ansible.builtin.debug:
                msg: "{{ item.through_hostvars }}"
              loop: "{{ hostvars[groups['all'][0]].totp_users }}"
            - name: Loop by name
              ansible.builtin.debug:
                msg: "{{ item.through_vars }}"
              loop: "{{ vars['totp_users'] }}"
            """.trimIndent() + "\n",
        )
        val inventory = "repos/falcon/ansible/environments/prod/group_vars/all/vars.yml"
        val finding = findings(inventory).first()
        assertEquals(listOf("name", "secret_file_src", "mode"), finding.usageAttributes)
        assertTrue(finding.message, finding.message.contains(
            "; tasks in `playbook-initial-setup.yml`, `roles/totp-token/tasks/extra.yml` read `item.name`/`item.secret_file_src`/`item.mode`;",
        ))
    }
}
