package de.terletzkiy.ansibility.model.task

import de.terletzkiy.ansibility.semantics.schema.DocSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [TaskSyntax] built from the bundled snapshots, and its hand-written fallback table. */
class TaskSyntaxTest {
    private val pinned: TaskSyntax by lazy { load(TaskSyntaxService.PINNED_RESOURCE) }
    private val latest: TaskSyntax by lazy { load(TaskSyntaxService.LATEST_RESOURCE) }

    private fun load(resource: String): TaskSyntax =
        TaskSyntax.fromSnapshot(javaClass.getResourceAsStream(resource)!!.use { DocSnapshot.load(it) })

    @Test
    fun keywordSetsPerOwner() {
        assertEquals("2.18.8", pinned.coreVersion)
        val task = pinned.keywords(KeywordOwner.TASK)
        assertTrue(task.containsAll(listOf("name", "when", "loop", "loop_control", "register", "notify", "args", "action", "local_action", "delegate_to", "become", "vars", "tags")))
        assertFalse("listen is a handler keyword", "listen" in task)
        assertFalse("the with_<lookup> placeholder is not a key", DocSnapshot.WITH_LOOKUP in task)
        assertTrue("listen" in pinned.keywords(KeywordOwner.HANDLER))
        assertTrue(pinned.keywords(KeywordOwner.BLOCK).containsAll(listOf("block", "rescue", "always", "when", "notify")))
        assertFalse("loop" in pinned.keywords(KeywordOwner.BLOCK))
        assertTrue(pinned.keywords(KeywordOwner.PLAY).containsAll(listOf("hosts", "roles", "pre_tasks", "tasks", "post_tasks", "handlers", "vars_files")))
        assertTrue(pinned.keywords(KeywordOwner.ROLE).containsAll(listOf("role", "name", "tags", "when", "vars")))
        assertEquals(setOf("break_when", "extended", "extended_allitems", "index_var", "label", "loop_var", "pause"), pinned.keywords(KeywordOwner.LOOP_CONTROL))
    }

    @Test
    fun keywordSetsFollowTheCoreVersion() {
        assertFalse("validate_argspec is a 2.19+ play keyword", pinned.isKeyword(KeywordOwner.PLAY, "validate_argspec"))
        assertTrue(latest.isKeyword(KeywordOwner.PLAY, "validate_argspec"))
    }

    @Test
    fun fallbackTableMatchesThePinnedSnapshot() {
        val fallback = TaskSyntax.fallback()
        for (owner in KeywordOwner.entries) {
            assertEquals("keywords of $owner", pinned.keywords(owner), fallback.keywords(owner))
        }
    }

    @Test
    fun modulesRouteAndKnowTheirOptions() {
        assertEquals("ansible.builtin.systemd_service", pinned.canonicalModule("ansible.builtin.systemd"))
        assertEquals("ansible.mysql.mysql_user", pinned.canonicalModule("community.mysql.mysql_user"))
        assertEquals("ansible.builtin.copy", pinned.canonicalModule("copy"))
        assertTrue(pinned.isKnownModule("ansible.builtin.template"))
        assertTrue(pinned.isKnownModule("community.mysql.mysql_user"))
        assertFalse(pinned.isKnownModule("hosts"))
        assertEquals(true, pinned.moduleHasOption("community.general.jenkins_plugin", "with_dependencies"))
        assertEquals(false, pinned.moduleHasOption("ansible.builtin.template", "with_dependencies"))
        assertNull(pinned.moduleHasOption("acme.custom.module", "x"))
        assertTrue(pinned.isFreeFormModule("ansible.builtin.command"))
        assertTrue(pinned.isFreeFormModule("shell"))
        assertFalse(pinned.isFreeFormModule("ansible.builtin.copy"))
    }

    @Test
    fun withLookupLoopsAndModuleOptions() {
        assertTrue(pinned.isLookup("items"))
        assertTrue(pinned.isLookup("ansible.builtin.fileglob"))
        assertFalse(pinned.isLookup("dependencies"))
        assertTrue(pinned.isLoopKey("with_items", "ansible.builtin.debug"))
        assertTrue(pinned.isLoopKey("with_dict", null))
        assertTrue(pinned.isLoopKey("with_nested", "ansible.builtin.template"))
        assertFalse(
            "with_dependencies is an option of jenkins_plugin (golden/roles/jenkins-controller/tasks/jenkins.yml:221)",
            pinned.isLoopKey("with_dependencies", "community.general.jenkins_plugin"),
        )
        assertTrue("an unknown with_ key on another module is a (custom lookup) loop", pinned.isLoopKey("with_dependencies", "ansible.builtin.debug"))
        assertFalse(pinned.isLoopKey("with_", null))
        assertFalse(pinned.isLoopKey("loop", null))
    }

    @Test
    fun fallbackStillSplitsLoops() {
        val fallback = TaskSyntax.fallback()
        assertTrue(fallback.isLoopKey("with_items", "ansible.builtin.debug"))
        assertEquals("ansible.builtin.copy", fallback.canonicalModule("copy"))
        assertFalse(fallback.isKnownModule("ansible.builtin.copy"))
    }
}
