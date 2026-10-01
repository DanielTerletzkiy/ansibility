package de.terletzkiy.ansibility.docs

import com.intellij.openapi.util.TextRange
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.KeywordLevel

/** What [TaskSiteClassifier] reports at positions of fixture task files, synthetic tasks and playbooks. */
class TaskSiteClassifierTest : DocsTestCase() {

    override fun setUp() {
        super.setUp()
        copyInfra(HAPROXY)
        copyDocsData()
    }

    private fun keyword(name: String, level: KeywordLevel, offset: Int) = AnsibleSite.KeywordKey(name, level, TextRange.from(offset, name.length))

    private fun lastOffsetOf(path: String, marker: String): Int = psi(path).text.lastIndexOf(marker).also { check(it >= 0) }

    fun testModuleKeyAtConfigureLine3() {
        // golden/roles/haproxy/tasks/configure.yml:3
        val range = rangeAt(CONFIGURE, 3, "ansible.builtin.template")
        assertEquals(AnsibleSite.ModuleKey("ansible.builtin.template", range), classify(CONFIGURE, range.startOffset + 4))
        assertEquals("caret right after the key", AnsibleSite.ModuleKey("ansible.builtin.template", range), classify(CONFIGURE, range.endOffset))
    }

    fun testOptionKeyAtConfigureLine5() {
        val range = rangeAt(CONFIGURE, 5, "dest")
        assertEquals(AnsibleSite.ModuleOptionKey("ansible.builtin.template", listOf("dest"), range), classify(CONFIGURE, range.startOffset + 1))
    }

    fun testTaskKeywords() {
        assertEquals(keyword("name", KeywordLevel.TASK, at(CONFIGURE, 2, "name")), classify(CONFIGURE, at(CONFIGURE, 2, "name")))
        assertEquals(keyword("notify", KeywordLevel.TASK, at(CONFIGURE, 9, "notify")), classify(CONFIGURE, at(CONFIGURE, 9, "notify", 2)))
        // golden/roles/haproxy/tasks/main.yml:10
        assertEquals(keyword("when", KeywordLevel.TASK, at(HAPROXY_MAIN, 10, "when")), classify(HAPROXY_MAIN, at(HAPROXY_MAIN, 10, "when", 1)))
    }

    fun testHandlerKeywords() {
        assertEquals(
            keyword("listen", KeywordLevel.HANDLER, at(HAPROXY_HANDLERS, 8, "listen")),
            classify(HAPROXY_HANDLERS, at(HAPROXY_HANDLERS, 8, "listen")),
        )
        val module = rangeAt(HAPROXY_HANDLERS, 3, "ansible.builtin.systemd")
        assertEquals(AnsibleSite.ModuleKey("ansible.builtin.systemd", module), classify(HAPROXY_HANDLERS, module.startOffset))
    }

    fun testValuesAreNotOurs() {
        assertNull("module option value", classify(CONFIGURE, at(CONFIGURE, 5, "/etc/haproxy", 3)))
        assertNull("notify value (a handler reference)", classify(CONFIGURE, at(CONFIGURE, 9, "Reload", 2)))
        assertNull("include_tasks file (a task file reference)", classify(HAPROXY_MAIN, at(HAPROXY_MAIN, 3, "apt.yml", 1)))
        assertNull("task name", classify(CONFIGURE, at(CONFIGURE, 2, "Configure", 2)))
    }

    fun testNestedOptionPaths() {
        val type = offsetOf(DEMO_TASKS, "- type: bind", 2)
        assertEquals(
            AnsibleSite.ModuleOptionKey("community.docker.docker_container", listOf("mounts", "type"), TextRange.from(type, 4)),
            classify(DEMO_TASKS, type + 1),
        )
        val mounts = offsetOf(DEMO_TASKS, "mounts:")
        assertEquals(AnsibleSite.ModuleOptionKey("community.docker.docker_container", listOf("mounts"), TextRange.from(mounts, 6)), classify(DEMO_TASKS, mounts))
        val interval = offsetOf(DEMO_TASKS, "interval:")
        assertEquals(listOf("healthcheck", "interval"), (classify(DEMO_TASKS, interval) as AnsibleSite.ModuleOptionKey).path)
        assertEquals("keys of a dict option are classified structurally", listOf("env", "FOO"), (classify(DEMO_TASKS, offsetOf(DEMO_TASKS, "FOO:")) as AnsibleSite.ModuleOptionKey).path)
    }

    fun testArgsLoopsAndLoopControl() {
        val args = offsetOf(DEMO_TASKS, "  args:", 2)
        assertEquals(AnsibleSite.KeywordKey("args", KeywordLevel.TASK, TextRange.from(args, 4)), classify(DEMO_TASKS, args))
        val path = offsetOf(DEMO_TASKS, "path: \"/srv")
        assertEquals(AnsibleSite.ModuleOptionKey("ansible.builtin.file", listOf("path"), TextRange.from(path, 4)), classify(DEMO_TASKS, path))
        val withItems = offsetOf(DEMO_TASKS, "with_items:")
        assertEquals(AnsibleSite.KeywordKey("with_items", KeywordLevel.TASK, TextRange.from(withItems, 10)), classify(DEMO_TASKS, withItems + 3))
        val loopControl = offsetOf(DEMO_TASKS, "loop_control:")
        assertEquals(AnsibleSite.KeywordKey("loop_control", KeywordLevel.TASK, TextRange.from(loopControl, 12)), classify(DEMO_TASKS, loopControl))
        val loopVar = offsetOf(DEMO_TASKS, "loop_var:")
        assertEquals(AnsibleSite.KeywordKey("loop_var", KeywordLevel.LOOP_CONTROL, TextRange.from(loopVar, 8)), classify(DEMO_TASKS, loopVar))
        assertEquals(KeywordLevel.LOOP_CONTROL, (classify(DEMO_TASKS, offsetOf(DEMO_TASKS, "label:")) as AnsibleSite.KeywordKey).level)
    }

    fun testVarsBoundary() {
        val vars = offsetOf(DEMO_TASKS, "  vars:", 2)
        assertEquals("the vars keyword itself", AnsibleSite.KeywordKey("vars", KeywordLevel.TASK, TextRange.from(vars, 4)), classify(DEMO_TASKS, vars))
        assertNull("a key under task vars: is the vars track's", classify(DEMO_TASKS, offsetOf(DEMO_TASKS, "demo_dirs:", 2)))
        assertNull("a play vars key", classify(DEMO_PLAYBOOK, offsetOf(DEMO_PLAYBOOK, "play_var:")))
        assertNull("a role parameter", classify(DEMO_PLAYBOOK, offsetOf(DEMO_PLAYBOOK, "demo_param:")))
    }

    fun testSetFactKeysAreVariables() {
        assertNull(classify(DEMO_TASKS, offsetOf(DEMO_TASKS, "demo_fact:")))
        val cacheable = offsetOf(DEMO_TASKS, "cacheable:")
        assertEquals(AnsibleSite.ModuleOptionKey("ansible.builtin.set_fact", listOf("cacheable"), TextRange.from(cacheable, 9)), classify(DEMO_TASKS, cacheable))
    }

    fun testJinjaNamesAndVariables() {
        val basename = offsetOf(DEMO_TASKS, "basename")
        assertEquals(AnsibleSite.JinjaFilter("basename", TextRange.from(basename, 8)), classify(DEMO_TASKS, basename + 2))
        assertNull("a Jinja variable is the vars track's", classify(DEMO_TASKS, offsetOf(DEMO_TASKS, "item | basename", 1)))
        val upper = offsetOf(DEMO_TASKS, "upper")
        assertEquals("inside a free-form string", AnsibleSite.JinjaFilter("upper", TextRange.from(upper, 5)), classify(DEMO_TASKS, upper))
        val defined = offsetOf(DEMO_TASKS, "is defined", 3)
        assertEquals("a test in an implicit expression", AnsibleSite.JinjaTest("defined", TextRange.from(defined, 7)), classify(DEMO_TASKS, defined + 1))
        val length = offsetOf(DEMO_TASKS, "length >")
        assertEquals(AnsibleSite.JinjaFilter("length", TextRange.from(length, 6)), classify(DEMO_TASKS, length))
        assertNull("a variable in an implicit expression", classify(DEMO_TASKS, offsetOf(DEMO_TASKS, "when: demo_dirs", 7)))
        assertNull("haproxy main.yml:10 variable", classify(HAPROXY_MAIN, at(HAPROXY_MAIN, 10, "haproxy_apply_kernel_params", 3)))
        val bool = at(HAPROXY_MAIN, 10, "bool")
        assertEquals(AnsibleSite.JinjaFilter("bool", TextRange.from(bool, 4)), classify(HAPROXY_MAIN, bool))
    }

    fun testKeyValueArgumentsAndActionForm() {
        val dest = offsetOf(DEMO_TASKS, "dest=/tmp")
        assertEquals(AnsibleSite.ModuleOptionKey("ansible.builtin.copy", listOf("dest"), TextRange.from(dest, 4)), classify(DEMO_TASKS, dest + 1))
        assertNull("a k=v value", classify(DEMO_TASKS, offsetOf(DEMO_TASKS, "a.txt dest", 1)))
        val ping = offsetOf(DEMO_TASKS, "ansible.builtin.ping data")
        assertEquals(AnsibleSite.ModuleKey("ansible.builtin.ping", TextRange.from(ping, 20)), classify(DEMO_TASKS, ping + 3))
        val data = offsetOf(DEMO_TASKS, "data=pong")
        assertEquals(AnsibleSite.ModuleOptionKey("ansible.builtin.ping", listOf("data"), TextRange.from(data, 4)), classify(DEMO_TASKS, data))
        val action = offsetOf(DEMO_TASKS, "action:")
        assertEquals(AnsibleSite.KeywordKey("action", KeywordLevel.TASK, TextRange.from(action, 6)), classify(DEMO_TASKS, action))
    }

    fun testApplyHoldsTaskKeywords() {
        val tags = offsetOf(DEMO_TASKS, "tags: [demo]")
        assertEquals(AnsibleSite.KeywordKey("tags", KeywordLevel.TASK, TextRange.from(tags, 4)), classify(DEMO_TASKS, tags))
        val file = offsetOf(DEMO_TASKS, "file: other.yml")
        assertEquals(AnsibleSite.ModuleOptionKey("ansible.builtin.include_tasks", listOf("file"), TextRange.from(file, 4)), classify(DEMO_TASKS, file))
    }

    fun testBlocks() {
        val block = offsetOf(DEMO_TASKS, "  block:", 2)
        assertEquals(AnsibleSite.KeywordKey("block", KeywordLevel.BLOCK, TextRange.from(block, 5)), classify(DEMO_TASKS, block))
        assertEquals(KeywordLevel.BLOCK, (classify(DEMO_TASKS, offsetOf(DEMO_TASKS, "rescue:")) as AnsibleSite.KeywordKey).level)
        val blockBecome = lastOffsetOf(DEMO_TASKS, "become:")
        assertEquals(AnsibleSite.KeywordKey("become", KeywordLevel.BLOCK, TextRange.from(blockBecome, 6)), classify(DEMO_TASKS, blockBecome))
        val inner = offsetOf(DEMO_TASKS, "ansible.builtin.debug")
        assertEquals(AnsibleSite.ModuleKey("ansible.builtin.debug", TextRange.from(inner, 21)), classify(DEMO_TASKS, inner))
        val msg = offsetOf(DEMO_TASKS, "msg: failed")
        assertEquals(AnsibleSite.ModuleOptionKey("ansible.builtin.debug", listOf("msg"), TextRange.from(msg, 3)), classify(DEMO_TASKS, msg))
    }

    fun testPlaybookLevels() {
        val hosts = offsetOf(DEMO_PLAYBOOK, "hosts:")
        assertEquals(AnsibleSite.KeywordKey("hosts", KeywordLevel.PLAY, TextRange.from(hosts, 5)), classify(DEMO_PLAYBOOK, hosts))
        val role = offsetOf(DEMO_PLAYBOOK, "role: docs_demo")
        assertEquals(AnsibleSite.KeywordKey("role", KeywordLevel.ROLE_ENTRY, TextRange.from(role, 4)), classify(DEMO_PLAYBOOK, role))
        val tags = offsetOf(DEMO_PLAYBOOK, "tags: [demo]")
        assertEquals(AnsibleSite.KeywordKey("tags", KeywordLevel.ROLE_ENTRY, TextRange.from(tags, 4)), classify(DEMO_PLAYBOOK, tags))
        val ping = offsetOf(DEMO_PLAYBOOK, "ansible.builtin.ping")
        assertEquals(AnsibleSite.ModuleKey("ansible.builtin.ping", TextRange.from(ping, 20)), classify(DEMO_PLAYBOOK, ping))
        val importKey = offsetOf(DEMO_PLAYBOOK, "ansible.builtin.import_playbook")
        assertEquals(
            AnsibleSite.KeywordKey("import_playbook", KeywordLevel.PLAYBOOK_INCLUDE, TextRange.from(importKey, 31)),
            classify(DEMO_PLAYBOOK, importKey + 5),
        )
        val whenKey = offsetOf(DEMO_PLAYBOOK, "when: true")
        assertEquals(AnsibleSite.KeywordKey("when", KeywordLevel.PLAYBOOK_INCLUDE, TextRange.from(whenKey, 4)), classify(DEMO_PLAYBOOK, whenKey))
        assertNull("a role entry name", classify(DEMO_PLAYBOOK, offsetOf(DEMO_PLAYBOOK, "docs_demo", 2)))
    }

    fun testOnlyTaskLikeFiles() {
        assertNull("role defaults", classify(DEMO_DEFAULTS, offsetOf(DEMO_DEFAULTS, "docs_demo_name")))
        assertNull("role defaults", classify(HAPROXY_DEFAULTS, 5))
        val outside = myFixture.tempDirFixture.createFile("outside/tasks/main.yml", "- name: Outside\n  ansible.builtin.template:\n    src: a\n")
        refreshRoots()
        assertNull("outside every root", classify("outside/tasks/main.yml", 20))
        assertNotNull(outside)
    }

    fun testUnknownKeysAreNotClassified() {
        val file = myFixture.tempDirFixture.createFile(
            "golden/roles/docs_demo/tasks/typo.yml",
            "- name: Typo\n  ansible.builtin.file:\n    path: /tmp/x\n  become_usr: root\n",
        )
        assertNotNull(file)
        refreshRoots()
        assertNull(classify("golden/roles/docs_demo/tasks/typo.yml", offsetOf("golden/roles/docs_demo/tasks/typo.yml", "become_usr")))
        assertEquals(
            AnsibleSite.ModuleKey("ansible.builtin.file", TextRange.from(offsetOf("golden/roles/docs_demo/tasks/typo.yml", "ansible.builtin.file"), 20)),
            classify("golden/roles/docs_demo/tasks/typo.yml", offsetOf("golden/roles/docs_demo/tasks/typo.yml", "ansible.builtin.file")),
        )
    }
}
