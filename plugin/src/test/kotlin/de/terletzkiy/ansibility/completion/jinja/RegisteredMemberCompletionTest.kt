package de.terletzkiy.ansibility.completion.jinja

import de.terletzkiy.ansibility.resolve.register.RegisteredFixture
import de.terletzkiy.ansibility.resolve.register.RegisteredFixture.TASKS
import de.terletzkiy.ansibility.resolve.register.RegisteredFixture.TASKS_TEXT
import de.terletzkiy.ansibility.resolve.register.RegisteredFixture.TEMPLATE
import de.terletzkiy.ansibility.resolve.register.RegisteredFixture.TEMPLATE_TEXT

/**
 * Member completion of registered variables (plan amendment FU, F1.12) on [RegisteredFixture]: the user's keepalived
 * example inside the injected `until:` expression, `stat` keys via `contains`, `uri`, loops (`results[0]`, `item`,
 * `{% for r in x.results %}`), a template rendered later, unions, modules without returns, async jobs, quoted keys,
 * the popup card and root scoping.
 */
class RegisteredMemberCompletionTest : JinjaCompletionTestCase() {
    override fun setUp() {
        super.setUp()
        RegisteredFixture.create { path, text -> myFixture.tempDirFixture.createFile(path, text) }
        refreshRoots()
    }

    private val untilLine get() = RegisteredFixture.lineOf(TASKS_TEXT, "until:")
    private val placeholderLine get() = RegisteredFixture.lineOf(TASKS_TEXT, "PLACEHOLDER")

    private fun atPlaceholder(new: String): List<String> {
        val items = strings(completeAfterEdit(TASKS, placeholderLine, "PLACEHOLDER", new))
        reset(TASKS)
        return items
    }

    fun testTheUntilExpressionOffersTheCommandResultWithTypesAndOrigins() {
        val items = completeAfterEdit(TASKS, untilLine, "keepalived_floating_ip_check.stdout", "keepalived_floating_ip_check.")
        val names = strings(items)
        assertContainsElements(
            names, "stdout", "stdout_lines", "stderr", "stderr_lines", "rc", "cmd", "delta", "start", "end", "msg", "attempts",
            "changed", "failed", "skipped",
        )
        assertEquals("str | · command", describe(items, "stdout"))
        assertEquals("int | · command", describe(items, "rc"))
        assertEquals("list[str] | · command", describe(items, "stdout_lines"))
        assertEquals("int | · until", describe(items, "attempts"))
        assertEquals("bool | · common", describe(items, "changed"))
        assertTrue("module returns before the common keys", priority(item(items, "stdout")) > priority(item(items, "changed")))
        assertTrue("the retry keys before the common keys", priority(item(items, "attempts")) > priority(item(items, "failed")))

        val card = plain(html(popupDocumentation(item(items, "stdout"))))
        assertTrue(card, "keepalived_floating_ip_check.stdout : str" in card)
        assertTrue(card, "return value of ansible.builtin.command" in card)
        assertTrue(card, "The command standard output." in card)
        assertTrue(card, "Returned always" in card)
        assertTrue(card, "Set by task “Wait for keepalived to assign the floating IP” (roles/keepalived/tasks/main.yml:10)" in card)
        assertTrue(card, "Documentation ansible.builtin.command docs" in card)
        val attempts = plain(html(popupDocumentation(item(items, "attempts"))))
        assertTrue(attempts, "The number of attempts the task made" in attempts)
        assertTrue(attempts, "Retrying a task until a condition is met" in attempts)
    }

    fun testChangedWhenAndFailedWhenSeeTheTasksOwnResultButOtherKeysDoNot() {
        val line = RegisteredFixture.lineOf(TASKS_TEXT, "changed_when: false")
        assertContainsElements(strings(completeAfterEdit(TASKS, line, "false", "keepalived_floating_ip_check.")), "rc", "stdout")
        reset(TASKS)
        assertContainsElements(strings(completeAfterEdit(TASKS, line, "changed_when: false", "failed_when: keepalived_floating_ip_check.")), "rc", "stderr")
        reset(TASKS)
        val command = RegisteredFixture.lineOf(TASKS_TEXT, "ip -4 -o addr show")
        assertEquals(emptyList<String>(), strings(completeAfterEdit(TASKS, command, "ip -4 -o addr show", "echo {{ keepalived_floating_ip_check.<caret> }}")))
        reset(TASKS)
        assertContainsElements(strings(completeAfterEdit(TASKS, command, "ip -4 -o addr show", "echo {{ keepalived_conf.<caret> }}")), "stat")
    }

    fun testStatKeysComeFromContains() {
        val items = completeAfterEdit(TASKS, placeholderLine, "PLACEHOLDER", "keepalived_conf.stat.")
        assertContainsElements(strings(items), "exists", "isdir", "mode", "path")
        assertEquals("bool | · stat", describe(items, "exists"))
        reset(TASKS)
        assertContainsElements(atPlaceholder("keepalived_conf."), "stat", "changed", "failed")
    }

    fun testUriReturnsStatusAndJson() {
        val items = completeAfterEdit(TASKS, placeholderLine, "PLACEHOLDER", "keepalived_api.")
        assertContainsElements(strings(items), "status", "json", "content", "url", "changed")
        assertEquals("int | · uri", describe(items, "status"))
        val json = plain(html(popupDocumentation(item(items, "json"))))
        assertTrue(json, "returned by ansible.builtin.uri (undocumented)" in json)
    }

    fun testLoopsGiveResultsPerItem() {
        assertEquals(listOf("results", "msg", "changed", "failed", "skipped"), atPlaceholder("keepalived_pings."))
        assertEquals("a list needs an index", emptyList<String>(), atPlaceholder("keepalived_pings.results."))
        assertContainsElements(atPlaceholder("keepalived_pings.results[0]."), "stdout", "rc", "item", "ansible_loop_var", "changed")
        val loopLine = RegisteredFixture.lineOf(TASKS_TEXT, "msg: \"{{ item.stdout")
        val items = completeAfterEdit(TASKS, loopLine, "item.stdout", "item.")
        assertContainsElements(strings(items), "stdout", "rc", "item", "ansible_loop_var")
        val card = plain(html(popupDocumentation(item(items, "stdout"))))
        assertTrue(card, "keepalived_pings.results[0].stdout : str" in card)
    }

    fun testATemplateRenderedLaterSeesTheRegistersAndTypesForTargets() {
        val conf = completeAfterEdit(TEMPLATE, 1, "keepalived_conf.stat.exists", "keepalived_conf.stat.")
        assertContainsElements(strings(conf), "exists", "isdir")
        reset(TEMPLATE)
        val forLine = RegisteredFixture.lineOf(TEMPLATE_TEXT, "{% for r in")
        val item = completeAfterEdit(TEMPLATE, forLine, "r.stdout", "r.")
        assertContainsElements(strings(item), "item", "stdout", "rc", "ansible_loop_var")
        assertEquals("str | · command", describe(item, "stdout"))
        reset(TEMPLATE)
        assertDoesntContain(strings(completeAfterEdit(TEMPLATE, 1, "keepalived_conf.stat.exists", "keepalived_either.")), "stdout")
    }

    fun testTwoRegisteringTasksGiveTheUnion() {
        val items = completeAfterEdit(TASKS, placeholderLine, "PLACEHOLDER", "keepalived_either.")
        assertEquals("str | · shell, command", describe(items, "stdout"))
        val card = plain(html(popupDocumentation(item(items, "stdout"))))
        assertTrue(card, "registered by 2 tasks" in card)
        assertTrue(card, "task “Shell variant”" in card && "task “Command variant”" in card)
    }

    fun testAModuleWithoutReturnsOffersTheCommonKeysAndAsyncTheJobKeys() {
        assertEquals(
            listOf("changed", "failed", "skipped", "msg", "invocation", "warnings", "deprecations", "diff"),
            atPlaceholder("keepalived_service."),
        )
        val job = atPlaceholder("keepalived_job.")
        assertContainsElements(job, "ansible_job_id", "started", "finished", "results_file")
        assertDoesntContain(job, "stdout")
    }

    fun testQuotedKeys() {
        val items = completeAfterEdit(TASKS, placeholderLine, "PLACEHOLDER", "keepalived_conf['")
        assertContainsElements(strings(items), "stat", "changed")
    }

    fun testNoMembersLeakIntoOtherRoots() {
        val line = RegisteredFixture.lineOf(RegisteredFixture.OTHER_TEXT, "PLACEHOLDER")
        val items = strings(completeAfterEdit(RegisteredFixture.OTHER_TASKS, line, "PLACEHOLDER", "keepalived_conf."))
        assertContainsElements(items, "status", "json")
        assertDoesntContain(items, "stat")
        reset(RegisteredFixture.OTHER_TASKS)
        assertEquals(emptyList<String>(), strings(completeAfterEdit(RegisteredFixture.OTHER_TASKS, line, "PLACEHOLDER", "keepalived_api.")))
    }

    fun testNothingBeforeTheRegisteringTask() {
        val line = RegisteredFixture.lineOf(TASKS_TEXT, "name: keepalived")
        assertEquals(emptyList<String>(), strings(completeAfterEdit(TASKS, line, "name: keepalived", "name: \"{{ keepalived_conf.<caret> }}\"")))
    }
}
