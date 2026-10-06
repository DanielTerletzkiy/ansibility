package de.terletzkiy.ansibility.completion.tasks

import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture

/** Option keys and values of modules (plan F5.7; M3 acceptance 8). */
@RequiresInfraFixture
class TaskOptionCompletionTest : TaskCompletionTestCase() {

    override fun setUp() {
        super.setUp()
        copyInfra(HAPROXY)
    }

    /** A new `ansible.builtin.file` task appended to configure.yml (after its last line, 47). */
    private fun newFileTask(options: String = ""): List<String> =
        completeInserted(CONFIGURE, 48, "\n- name: Create a directory\n  ansible.builtin.file:\n$options    $CARET\n")

    fun testPathAndStateComeFirstInsideFile() {
        newFileTask()
        val items = ourStrings()
        assertEquals("M3 acceptance 8: $items", listOf("path", "state"), items.take(2))
        assertTrue("required options are bold", presentation("path").isItemTextBold)
        assertFalse(presentation("state").isItemTextBold)
        assertEquals("path · required", presentation("path").typeText)
        // mode, owner and group are set by the other file tasks of configure.yml: they follow.
        val rest = items.drop(2)
        assertEquals(setOf("group", "mode", "owner"), rest.take(3).toSet())
        assertContainsElements(rest, "recurse", "follow", "src", "attributes")
        assertDoesntContain(items, "free_form", "dest", "name")
    }

    fun testOptionTailIsTheFirstSentenceOfTheDescription() {
        newFileTask()
        assertEquals(" Path to the file being managed.", presentation("path").tailText)
        assertTrue(presentation("recurse").tailText.orEmpty().startsWith(" Recursively set the specified file attributes"))
    }

    fun testAlreadySetOptionsAreExcluded() {
        // configure.yml:11-17 sets path, state, mode, owner and group on ansible.builtin.file.
        completeInserted(CONFIGURE, 18, "    $CARET\n")
        val items = ourStrings()
        assertDoesntContain(items, "path", "state", "mode", "owner", "group")
        assertDoesntContain("aliases of a set option stay out", items, "dest", "name")
        assertContainsElements(items, "recurse", "follow", "src", "access_time")
    }

    fun testAliasesOnlyWhenTheirPrefixIsTyped() {
        newFileTask()
        assertDoesntContain(ourStrings(), "dest", "name")

        complete(SCRATCH_TASKS, "- name: Create a directory\n  ansible.builtin.file:\n    state: directory\n    de$CARET\n")
        assertContainsElements(ourStrings(), "dest")
        assertDoesntContain("the other alias does not start with the prefix", ourStrings(), "name")
        assertEquals(" alias of path", presentation("dest").tailText)
        assertTrue("an alias of a required option is bold too", presentation("dest").isItemTextBold)
    }

    fun testTypingAnAliasPrefixInTheOpenPopupRestartsCompletion() {
        newFileTask()
        assertDoesntContain(ourStrings(), "dest")
        myFixture.type("de")
        assertContainsElements(myFixture.lookupElementStrings.orEmpty(), "dest")
    }

    fun testStateChoices() {
        completeInserted(CONFIGURE, 48, "\n- name: Link\n  ansible.builtin.file:\n    path: /tmp/x\n    state: $CARET\n")
        assertSameElements(ourStrings(), "absent", "directory", "file", "hard", "link", "touch")
        assertEquals("state", presentation("link").typeText)
    }

    fun testBoolOptionValues() {
        completeInserted(CONFIGURE, 48, "\n- name: Link\n  ansible.builtin.file:\n    path: /tmp/x\n    follow: $CARET\n")
        assertSameElements(ourStrings(), "true", "false")
    }

    fun testDescribedChoicesShowTheirDescription() {
        complete(SCRATCH_TASKS, "- name: Call\n  ansible.builtin.uri:\n    url: https://example.org\n    follow_redirects: $CARET\n")
        assertContainsElements(ourStrings(), "all", "none", "safe", "urllib2")
        assertTrue(presentation("safe").tailText.orEmpty().isNotBlank())
    }

    fun testStringChoicesThatYamlWouldRetypeAreQuoted() {
        complete(SCRATCH_TASKS, "- name: Upgrade\n  ansible.builtin.apt:\n    upgrade: $CARET\n")
        assertContainsElements(ourStrings(), "dist", "full", "no", "safe", "yes")
        select("yes")
        assertTrue(textWithCaret(), textWithCaret().contains("upgrade: 'yes'$CARET"))
    }

    fun testOptionKeysUnderArgs() {
        complete(SCRATCH_TASKS, "- name: Copy\n  ansible.builtin.copy:\n  args:\n    dest: /tmp/x\n    $CARET\n")
        assertContainsElements(ourStrings(), "src", "content", "mode")
        assertDoesntContain(ourStrings(), "dest")
    }

    fun testDockerContainerMountsSubOptions() {
        val text = "- name: Run\n  community.docker.docker_container:\n    name: web\n    image: nginx\n    mounts:\n      - $CARET\n"
        complete(SCRATCH_TASKS, text)
        assertContainsElements(ourStrings(), "type", "source", "target", "read_only", "volume_driver")
        assertDoesntContain("no top-level options in a mounts item", ourStrings(), "image", "healthcheck")

        complete("$HAPROXY/tasks/completion2.yml", "- name: Run\n  community.docker.docker_container:\n    mounts:\n      - type: bind\n        $CARET\n")
        assertContainsElements(ourStrings(), "source", "target", "read_only")
        assertDoesntContain(ourStrings(), "type")

        complete("$HAPROXY/tasks/completion3.yml", "- name: Run\n  community.docker.docker_container:\n    mounts:\n      - type: $CARET\n")
        assertSameElements(ourStrings(), "bind", "npipe", "tmpfs", "volume", "cluster", "image")
        assertEquals(" (default)", presentation("volume").tailText)
    }

    fun testDictSubOptions() {
        complete(SCRATCH_TASKS, "- name: Run\n  community.docker.docker_container:\n    name: web\n    healthcheck:\n      $CARET\n")
        assertContainsElements(ourStrings(), "interval", "retries", "test", "timeout")
    }

    fun testFreeFormPseudoOptionIsNoKey() {
        complete(SCRATCH_TASKS, "- name: Run\n  ansible.builtin.command:\n    $CARET\n")
        assertContainsElements(ourStrings(), "cmd", "argv", "chdir", "creates")
        assertDoesntContain(ourStrings(), "free_form")
    }

    fun testOptionInsertHandlers() {
        newFileTask()
        select("path")
        assertTrue(textWithCaret(), textWithCaret().endsWith("  ansible.builtin.file:\n    path: $CARET\n"))

        complete(SCRATCH_TASKS, "- name: Run\n  community.docker.docker_container:\n    name: web\n    moun$CARET\n")
        select("mounts")
        assertTrue(textWithCaret(), textWithCaret().endsWith("    mounts:\n      - $CARET\n"))

        complete("$HAPROXY/tasks/completion2.yml", "- name: Run\n  community.docker.docker_container:\n    name: web\n    healthch$CARET\n")
        select("healthcheck")
        assertTrue(textWithCaret(), textWithCaret().endsWith("    healthcheck:\n      $CARET\n"))
    }

    fun testExistingColonIsKept() {
        complete(SCRATCH_TASKS, "- name: Link\n  ansible.builtin.file:\n    pa$CARET: /tmp/x\n")
        select("path")
        assertTrue(textWithCaret(), textWithCaret().contains("    path: ${CARET}/tmp/x"))
    }

    fun testVarsAndJinjaAreNotOurs() {
        complete(SCRATCH_TASKS, "- name: Debug\n  ansible.builtin.debug:\n    msg: hi\n  vars:\n    $CARET\n")
        assertEmpty(ours())
        complete("$HAPROXY/tasks/completion2.yml", "- name: Debug\n  ansible.builtin.file:\n    path: \"{{ haproxy_$CARET }}\"\n")
        assertEmpty(ours())
        complete("$HAPROXY/tasks/completion3.yml", "- name: Debug\n  ansible.builtin.debug:\n    msg: hi\n  when: $CARET\n")
        assertEmpty(ours())
    }

    fun testIncludeApplyHoldsTaskKeywords() {
        complete(SCRATCH_TASKS, "- name: Include\n  ansible.builtin.include_tasks:\n    file: other.yml\n    apply:\n      $CARET\n")
        assertContainsElements(ourStrings(), "become", "tags", "when")
        assertDoesntContain(ourStrings(), "hosts", "file")
    }
}
