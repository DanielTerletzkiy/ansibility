package de.terletzkiy.ansibility.context.host.card

import com.intellij.testFramework.IndexingTestUtil
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext

/**
 * Block and task vars (level 15) at the card's position: the model's single-name evaluation has no level 15, so a
 * reference inside a task whose `vars:` set the name was shown with the inventory's winner although the task's own value
 * wins for every host that runs the task ([TaskVars]). The Effective section, the ranked "Set in" rows and the Explain
 * card now agree on the task var, which still loses to role params (L18).
 */
class TaskVarsCardTest : HostCardTestCase() {
    override fun addFixtureFiles() {
        super.addFixtureFiles()
        add(
            TASKS,
            """
            ---
            - name: Task vars win at the caret
              vars:
                postfix_relayhost: from-task
              ansible.builtin.debug:
                msg: "{{ postfix_relayhost }}"
            - name: No vars here
              ansible.builtin.debug:
                msg: "{{ postfix_relayhost }}"
            - name: A block
              vars:
                postfix_relayhost: from-block
              block:
                - name: Inside the block
                  ansible.builtin.debug:
                    msg: "{{ postfix_relayhost }}"
                - name: Task vars beat block vars
                  vars:
                    postfix_relayhost: from-inner-task
                  ansible.builtin.debug:
                    msg: "{{ postfix_relayhost }}"
            - name: Self reference
              vars:
                postfix_relayhost: "{{ postfix_relayhost }}"
              ansible.builtin.debug:
                msg: ha4
            - name: Render with task vars
              ansible.builtin.template:
                src: ha4t.j2
                dest: /tmp/ha4t
              vars:
                postfix_relayhost: from-template-task
            - name: Render partly with task vars
              ansible.builtin.template:
                src: ha4p.j2
                dest: /tmp/ha4p
              vars:
                postfix_relayhost: from-partial-task
            - name: Render without task vars
              ansible.builtin.template:
                src: ha4p.j2
                dest: /tmp/ha4p-plain
            """,
        )
        add("$FALCON/roles/postfix/templates/ha4t.j2", "relay {{ postfix_relayhost }}")
        add("$FALCON/roles/postfix/templates/ha4p.j2", "relay {{ postfix_relayhost }}")
    }

    /** The reference on [line] of the task file. */
    private fun taskCard(line: Int): String = card(TASKS, line, "postfix_relayhost")

    fun testTaskVarsWinOverEveryStaticLayer() {
        val html = taskCard(6)
        val section = effective(html)
        assertTrue(section, section.startsWith("Effective on 4 hosts (play System) — 1 value"))
        assertTrue(section, "= from-task · roles/postfix/tasks/ha4.yml:4 · L15 block/task vars" in section)
        assertTrue("the inventory's winner is the runner-up: $section", "shadowed: group_vars/all/vars.yml:156 (L5, ops ×1, prod ×2, test ×1) · " in section)
        assertTrue("molecule runs the role's tasks too: $section", "molecule default: = from-task · roles/postfix/tasks/ha4.yml:4" in section)
        val setIn = row(html, "Set in")!!
        assertTrue("the task var ranks first: $setIn", setIn.startsWith("roles and plays roles/postfix/tasks/ha4.yml:4 · block/task vars · level 15 · from-task · wins on 4 of 4 hosts"))
        assertTrue(setIn, "group_vars/all/vars.yml:156 · playbook group_vars/all · level 5 · $relayHost · shadowed on 4 of 4 hosts" in setIn)
    }

    fun testOtherTasksKeepTheStaticWinner() {
        val section = effective(taskCard(9))
        assertTrue(section, "= $relayHost · group_vars/all/vars.yml:156 · L5 playbook group_vars/all" in section)
        assertFalse(section, "from-task" in section)
        val self = effective(card(TASKS, 24, "{{ postfix_relayhost", delta = 4))
        assertTrue("a var's own value does not see itself: $self", "= $relayHost · group_vars/all/vars.yml:156" in self)
    }

    fun testBlockVarsAndTheInnermostTaskVars() {
        assertTrue(effective(taskCard(16)), "= from-block · roles/postfix/tasks/ha4.yml:12 · L15 block/task vars" in effective(taskCard(16)))
        assertTrue(effective(taskCard(21)), "= from-inner-task · roles/postfix/tasks/ha4.yml:19 · L15 block/task vars" in effective(taskCard(21)))
    }

    fun testTheTaskVarKeyCardShowsItWinning() {
        val html = card(TASKS, 4, "postfix_relayhost", delta = 0)
        assertTrue(effective(html), "= from-task · roles/postfix/tasks/ha4.yml:4 · L15 block/task vars" in effective(html))
        assertEquals("Block and task vars apply to their tasks only", row(html, "Effect"))
    }

    fun testATemplateRenderedWithTaskVars() {
        val all = effective(card("$FALCON/roles/postfix/templates/ha4t.j2", 1, "postfix_relayhost"))
        assertTrue("its only render sets it: $all", "= from-template-task · roles/postfix/tasks/ha4.yml:32 · L15 block/task vars" in all)

        val partly = effective(card("$FALCON/roles/postfix/templates/ha4p.j2", 1, "postfix_relayhost"))
        assertTrue("one of two renders sets it: the static winner stays: $partly", "= $relayHost · group_vars/all/vars.yml:156" in partly)
        assertTrue(partly, "some renders set it in their task vars (L15): roles/postfix/tasks/ha4.yml:38" in partly)
    }

    fun testExplainShowsTheTaskVarAtLevel15() {
        withSelection(FALCON, RootContext(EnvironmentChoice.Named("prod"), "prod-prod1")) {
            val card = hover(TASKS, offsetAt(TASKS, 6, "postfix_relayhost", 1))
            val html = html(card)
            assertTrue(effective(html), effective(html).startsWith("Effective on prod › prod-prod1 (play System) = from-task"))
            val link = Regex("href=\"(psi_element://ansibility-host/explain[^\"]*)\"").find(html)!!.groupValues[1].replace("&amp;", "&")
            val text = text(html(inBackgroundReadAction { HostCardLinkHandler().resolveTarget(card, link) }!!))
            val l5 = text.indexOf("L5 playbook group_vars/all · group_vars/all/vars.yml:156 · = $relayHost · shadowed")
            val l15 = text.indexOf("L15 block/task vars · roles/postfix/tasks/ha4.yml:4 · = from-task · ✓ wins")
            assertTrue("the task var is the last step and wins: $text", l5 in 0 until l15)
        }
    }

    /** The params of a `roles:` entry (L18) beat the task var for the hosts of that play. */
    fun testRoleParamsBeatTaskVars() {
        add(
            "$FALCON/playbook-ha4-params.yml",
            """
            ---
            - name: HA4 params
              hosts: prod-prod1
              roles:
                - role: postfix
                  postfix_relayhost: from-params
            """,
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val section = effective(taskCard(6))
        assertTrue(section, section.startsWith("Effective on 4 hosts (2 plays) — 2 values"))
        assertTrue(section, "prod-prod1 = from-params · playbook-ha4-params.yml:6 · L18 role params" in section)
        assertTrue("the task var loses there: $section", "roles/postfix/tasks/ha4.yml:4 (L15)" in section)
        assertTrue(section, "= from-task · roles/postfix/tasks/ha4.yml:4 · L15 block/task vars" in section)
    }

    private companion object {
        const val TASKS = "$FALCON/roles/postfix/tasks/ha4.yml"
    }
}
