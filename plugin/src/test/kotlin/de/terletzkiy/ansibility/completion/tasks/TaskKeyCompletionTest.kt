package de.terletzkiy.ansibility.completion.tasks

import com.intellij.codeInsight.lookup.LookupElementPresentation
import de.terletzkiy.ansibility.api.AnsibleWorkspace

/** Module names and keywords by structural level (plan F5.7; M3 acceptance 7, 8). */
class TaskKeyCompletionTest : TaskCompletionTestCase() {

    override fun setUp() {
        super.setUp()
        copyInfra(HAPROXY)
    }

    fun testModuleFqcnAtATaskKey() {
        completeInserted(CONFIGURE, 48, "\n- name: Render\n  ansible.builtin.tem$CARET\n")
        assertContainsElements(ourStrings(), "ansible.builtin.template")
        val template = presentation("ansible.builtin.template")
        assertEquals(" Template a file out to a target host", template.tailText)
        assertEquals("ansible.builtin", template.typeText)
        assertNotNull(template.icon)
        assertFalse(template.isStrikeout)
    }

    fun testShortNamesFindTheirModule() {
        completeInserted(CONFIGURE, 48, "\n- name: Render\n  templ$CARET\n")
        assertContainsElements(ourStrings(), "ansible.builtin.template")
    }

    fun testTaskWithoutModuleOffersModulesKeywordsAndBlock() {
        complete(SCRATCH_TASKS, "- name: New task\n  $CARET\n")
        val items = ourStrings()
        assertContainsElements(items, "ansible.builtin.copy", "community.docker.docker_container", "register", "when", "loop", "block")
        assertDoesntContain("play keywords are not task keywords", items, "hosts", "roles", "gather_facts", "pre_tasks")
        assertDoesntContain("handler-only keywords stay in handlers", items, "listen")
        assertDoesntContain("set keys are excluded", items, "name")
        assertDoesntContain(items, "with_<lookup>")
    }

    fun testTaskWithModuleOffersOnlyKeywords() {
        complete(SCRATCH_TASKS, "- name: Ping\n  ansible.builtin.ping:\n  $CARET\n")
        val items = ourStrings()
        assertContainsElements(items, "register", "when", "notify", "become")
        assertDoesntContain(items, "ansible.builtin.copy", "block", "name")
        val register = presentation("register")
        assertEquals("str", register.typeText)
        assertTrue(register.tailText.orEmpty().isNotBlank())
    }

    fun testNewListItemIsATaskKey() {
        complete(SCRATCH_TASKS, "- name: First\n  ansible.builtin.ping:\n\n- $CARET\n")
        assertContainsElements(ourStrings(), "name", "ansible.builtin.copy", "block", "when")
    }

    fun testBuiltinModulesFirstThenModulesUsedInTheRootThenTheRest() {
        copyInfra("golden/roles/coolify", "golden/roles/keycloak")
        complete(SCRATCH_TASKS, "- name: New task\n  $CARET\n")
        val items = ourStrings()
        val modules = ours().filter { it.`object` is TaskLookupObject.Module }
        val root = AnsibleWorkspace.getInstance(project).rootFor(vf(SCRATCH_TASKS))!!
        val used = TaskCompletionCatalog.getInstance(project).usedModules(root)
        assertContainsElements(used, "community.docker.docker_compose_v2", "ansible.builtin.template")
        val struck = modules.filter { LookupElementPresentation.renderElement(it).isStrikeout }.map { it.lookupString }.toSet()
        val live = modules.map { it.lookupString }.filter { it !in struck }
        val lastBuiltin = live.indexOfLast { it.startsWith("ansible.builtin.") }
        val firstOther = live.indexOfFirst { !it.startsWith("ansible.builtin.") }
        assertTrue("ansible.builtin first: $live", lastBuiltin < firstOther)
        val compose = live.indexOf("community.docker.docker_compose_v2")
        val unused = live.indexOf("community.general.aix_devices").takeIf { it >= 0 } ?: live.indexOf("community.docker.docker_image")
        assertTrue("modules used in the root come before unused ones: $live", compose in (lastBuiltin + 1) until unused)
        assertTrue("keywords follow the used modules", items.indexOf("register") > compose)
        val positions = modules.map { it.lookupString }
        val lastUsed = positions.indexOf("community.docker.docker_compose_v2")
        assertTrue("deprecated and redirected names follow the builtin and used modules", struck.all { positions.indexOf(it) > lastUsed })
    }

    fun testRedirectedAndDeprecatedModulesAreStruckOut() {
        complete(SCRATCH_TASKS, "- name: User\n  mysql_us$CARET\n")
        val redirected = presentation("community.mysql.mysql_user")
        assertTrue(redirected.isStrikeout)
        assertEquals(" → ansible.mysql.mysql_user", redirected.tailText)
        assertFalse(presentation("ansible.mysql.mysql_user").isStrikeout)
    }

    fun testModuleInsertHandlers() {
        complete(SCRATCH_TASKS, "- name: Render\n  ansible.builtin.templ$CARET\n")
        select("ansible.builtin.template")
        assertEquals("- name: Render\n  ansible.builtin.template:\n    $CARET\n", textWithCaret())

        complete("$HAPROXY/tasks/completion2.yml", "- name: Run\n  ansible.builtin.comma$CARET\n")
        select("ansible.builtin.command")
        assertEquals("free-form modules take their value on the key's line", "- name: Run\n  ansible.builtin.command: $CARET\n", textWithCaret())

        complete("$HAPROXY/tasks/completion3.yml", "- name: Facts\n  ansible.builtin.service_fa$CARET\n")
        select("ansible.builtin.service_facts")
        assertEquals("modules without options get only the colon", "- name: Facts\n  ansible.builtin.service_facts:$CARET\n", textWithCaret())
    }

    fun testKeywordInsertHandlers() {
        complete(SCRATCH_TASKS, "- name: Ping\n  ansible.builtin.ping:\n  loop_con$CARET\n")
        select("loop_control")
        assertEquals("- name: Ping\n  ansible.builtin.ping:\n  loop_control:\n    $CARET\n", textWithCaret())

        complete("$HAPROXY/tasks/completion2.yml", "- name: Ping\n  ansible.builtin.ping:\n  regis$CARET\n")
        select("register")
        assertEquals("- name: Ping\n  ansible.builtin.ping:\n  register: $CARET\n", textWithCaret())
    }

    fun testPlayAndTaskKeywordSetsDiffer() {
        copyInfra("repos/falcon")
        // repos/falcon/ansible/playbook-setup-system.yml:5-11: a play (name, gather_facts, hosts, serial, tasks) with a ping task.
        completeInserted(PLAYBOOK_SYSTEM, 7, "  $CARET\n")
        val play = ourStrings().toSet()
        assertContainsElements(play, "pre_tasks", "post_tasks", "handlers", "roles", "vars_files", "strategy", "become")
        assertDoesntContain("set play keys are excluded", play, "name", "gather_facts", "hosts", "serial", "tasks")
        assertDoesntContain("task keywords are not play keywords", play, "register", "loop", "when", "notify", "until")
        assertDoesntContain("no modules in a play", play, "ansible.builtin.copy")

        completeInserted(PLAYBOOK_SYSTEM, 13, "      $CARET\n")
        val task = ourStrings().toSet()
        assertContainsElements(task, "register", "loop", "when", "notify", "until", "become")
        assertDoesntContain(task, "hosts", "roles", "gather_facts", "pre_tasks", "vars_files", "name")
        assertFalse("the two sets differ", play == task)
    }

    fun testImportPlaybookItems() {
        copyInfra("repos/falcon")
        complete("repos/falcon/ansible/playbook-completion.yml", "- name: Import\n  $CARET\n")
        assertContainsElements("an item with only a name may still become an import", ourStrings(), "import_playbook", "hosts")

        complete("repos/falcon/ansible/playbook-completion2.yml", "- import_playbook: playbook-setup-system.yml\n  $CARET\n")
        val include = ourStrings()
        assertContainsElements(include, "when", "tags", "vars")
        assertDoesntContain(include, "hosts", "tasks", "import_playbook")
    }

    fun testHandlerOnlyKeywordsInHandlers() {
        completeInserted(HAPROXY_HANDLERS, 48, "\n- name: Reload\n  ansible.builtin.systemd:\n    name: haproxy\n  $CARET\n")
        assertContainsElements(ourStrings(), "listen", "notify", "when")

        complete(SCRATCH_TASKS, "- name: Reload\n  ansible.builtin.systemd:\n    name: haproxy\n  $CARET\n")
        assertDoesntContain(ourStrings(), "listen")
    }

    fun testBlockKeywords() {
        complete(SCRATCH_TASKS, "- name: Guarded\n  block:\n    - ansible.builtin.ping:\n  $CARET\n")
        val items = ourStrings()
        assertContainsElements(items, "rescue", "always", "when", "become")
        assertDoesntContain(items, "block", "register", "loop", "ansible.builtin.copy")

        complete("$HAPROXY/tasks/completion2.yml", "- name: Guarded\n  block:\n    - name: Inner\n      $CARET\n")
        assertContainsElements("tasks inside a block are tasks", ourStrings(), "register", "ansible.builtin.copy")
    }

    fun testLoopControlSubKeys() {
        complete(SCRATCH_TASKS, "- name: Loop\n  ansible.builtin.debug:\n    msg: hi\n  loop: [1, 2]\n  loop_control:\n    label: x\n    $CARET\n")
        assertSameElements(ourStrings(), "break_when", "extended", "extended_allitems", "index_var", "loop_var", "pause")
    }

    fun testRoleEntryKeys() {
        copyInfra("repos/falcon")
        complete("repos/falcon/ansible/playbook-completion.yml", "- hosts: all\n  roles:\n    - role: haproxy\n      $CARET\n")
        val items = ourStrings()
        assertContainsElements(items, "tags", "when", "vars", "become")
        assertDoesntContain(items, "role", "hosts", "tasks", "register", "loop")

        complete("repos/falcon/ansible/playbook-completion2.yml", "- hosts: all\n  roles:\n    - { role: haproxy, ta$CARET }\n")
        select("tags")
        assertTrue(textWithCaret(), textWithCaret().contains("- { role: haproxy, tags: $CARET }"))

        complete("repos/falcon/ansible/playbook-completion3.yml", "- hosts: all\n  roles:\n    - $CARET\n")
        assertEmpty("a bare role list item is a role name, not ours", ours())
    }

    fun testBoolKeywordValues() {
        complete(SCRATCH_TASKS, "- name: Ping\n  ansible.builtin.ping:\n  become: $CARET\n")
        assertSameElements(ourStrings(), "true", "false")
        complete("$HAPROXY/tasks/completion2.yml", "- name: Ping\n  ansible.builtin.ping:\n  register: $CARET\n")
        assertEmpty(ours())
    }

}
