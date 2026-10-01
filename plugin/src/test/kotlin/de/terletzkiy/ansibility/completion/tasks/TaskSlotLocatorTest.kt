package de.terletzkiy.ansibility.completion.tasks

import com.intellij.codeInsight.completion.CompletionUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.model.task.TaskFileKind
import de.terletzkiy.ansibility.model.task.TaskSyntaxService
import org.jetbrains.yaml.YAMLFileType

/**
 * [TaskSlotLocator] on completion-copy text: the dummy identifier sits at the caret, as the platform inserts it, and
 * the slot is read from the PSI around it.
 */
class TaskSlotLocatorTest : BasePlatformTestCase() {
    private val syntax get() = TaskSyntaxService.getInstance().forVersion(null)

    /** The slot at `<caret>` of [text] read as [kind], with the dummy identifier inserted at the caret. */
    private fun slot(text: String, kind: TaskFileKind = TaskFileKind.TASKS): TaskSlot? {
        val caret = text.indexOf(CARET)
        val copy = text.replace(CARET, CompletionUtil.DUMMY_IDENTIFIER)
        val file = myFixture.configureByText(YAMLFileType.YML, copy)
        val position = file.findElementAt(caret) ?: error("nothing at $caret")
        return TaskSlotLocator(syntax, kind).locate(position, caret)
    }

    fun testTaskKeyWithoutAndWithModule() {
        assertEquals(TaskSlot.Key(KeyOwner.Task(false, null), setOf("name"), "ansible.builtin.te", false), slot("- name: x\n  ansible.builtin.te$CARET\n"))
        assertEquals(
            TaskSlot.Key(KeyOwner.Task(false, "ansible.builtin.ping"), setOf("name", "ansible.builtin.ping"), "reg", false),
            slot("- name: x\n  ansible.builtin.ping:\n  reg$CARET\n"),
        )
    }

    fun testAKeyWithItsColonIsExcludedFromTheModuleCandidates() {
        // Renaming the module key: the task has no other module, so modules are offered.
        assertEquals(TaskSlot.Key(KeyOwner.Task(false, null), setOf("name"), "ansible.builtin.fi", false), slot("- name: x\n  ansible.builtin.fi$CARET:\n    path: /x\n"))
    }

    fun testModuleOptionsAndNestedOptions() {
        assertEquals(TaskSlot.Key(KeyOwner.Options("ansible.builtin.file", emptyList()), emptySet(), "pa", false), slot("- ansible.builtin.file:\n    pa$CARET\n"))
        assertEquals(
            TaskSlot.Key(KeyOwner.Options("ansible.builtin.file", emptyList()), setOf("path"), "st", false),
            slot("- ansible.builtin.file:\n    path: /x\n    st$CARET\n"),
        )
        assertEquals(
            TaskSlot.Key(KeyOwner.Options("community.docker.docker_container", listOf("mounts")), setOf("type"), "", false),
            slot("- community.docker.docker_container:\n    mounts:\n      - type: bind\n        $CARET\n"),
        )
        assertEquals(
            TaskSlot.ListItem(KeyOwner.Options("community.docker.docker_container", listOf("mounts")), "", false),
            slot("- community.docker.docker_container:\n    mounts:\n      - $CARET\n"),
        )
    }

    fun testFlowMappings() {
        assertEquals(
            TaskSlot.Key(KeyOwner.Options("ansible.builtin.file", emptyList()), setOf("path"), "", true),
            slot("- ansible.builtin.file: {path: /x, $CARET}\n"),
        )
    }

    fun testValues() {
        assertEquals(TaskSlot.Value(KeyOwner.Options("ansible.builtin.file", emptyList()), "state", "di", false), slot("- ansible.builtin.file:\n    state: di$CARET\n"))
        assertEquals(TaskSlot.Value(KeyOwner.Options("ansible.builtin.file", emptyList()), "state", "di", true), slot("- ansible.builtin.file:\n    state: \"di$CARET\"\n"))
        assertEquals(TaskSlot.Value(KeyOwner.Task(false, "ansible.builtin.ping"), "become", "", false), slot("- ansible.builtin.ping:\n  become: $CARET\n"))
    }

    fun testArgsAndActionHoldOptions() {
        assertEquals(
            TaskSlot.Key(KeyOwner.Options("ansible.builtin.copy", emptyList()), emptySet(), "", false),
            slot("- ansible.builtin.copy:\n  args:\n    $CARET\n"),
        )
        assertEquals(
            TaskSlot.Key(KeyOwner.Options("copy", emptyList()), setOf("module"), "", false),
            slot("- action:\n    module: copy\n    $CARET\n"),
        )
    }

    fun testStructuralLevels() {
        assertEquals(TaskSlot.Key(KeyOwner.Play, setOf("hosts"), "", false), slot("- hosts: all\n  $CARET\n", TaskFileKind.PLAYBOOK))
        assertEquals(TaskSlot.Key(KeyOwner.Block(false), setOf("block"), "", false), slot("- block:\n    - ansible.builtin.ping:\n  $CARET\n"))
        assertEquals(TaskSlot.Key(KeyOwner.Task(true, null), setOf("name"), "", false), slot("- name: h\n  $CARET\n", TaskFileKind.HANDLERS))
        assertEquals(
            TaskSlot.Key(KeyOwner.Task(true, null), setOf("name"), "", false),
            slot("- hosts: all\n  handlers:\n    - name: h\n      $CARET\n", TaskFileKind.PLAYBOOK),
        )
        assertEquals(TaskSlot.Key(KeyOwner.RoleEntry, setOf("role"), "", false), slot("- hosts: all\n  roles:\n    - role: x\n      $CARET\n", TaskFileKind.PLAYBOOK))
        assertEquals(TaskSlot.Key(KeyOwner.LoopControl, emptySet(), "", false), slot("- ansible.builtin.ping:\n  loop_control:\n    $CARET\n"))
        assertEquals(
            TaskSlot.Key(KeyOwner.Apply, emptySet(), "", false),
            slot("- ansible.builtin.include_tasks:\n    file: x.yml\n    apply:\n      $CARET\n"),
        )
    }

    fun testPositionsThatAreNotOurs() {
        assertNull("vars", slot("- ansible.builtin.ping:\n  vars:\n    $CARET\n"))
        assertNull("module_defaults", slot("- hosts: all\n  module_defaults:\n    $CARET\n", TaskFileKind.PLAYBOOK))
        assertNull("a role name", slot("- hosts: all\n  roles:\n    - $CARET\n", TaskFileKind.PLAYBOOK))
        assertNull("a vars dict of include_role", slot("- ansible.builtin.include_role:\n    name: x\n    vars:\n      $CARET\n"))
        assertNull("a continuation line", slot("- ansible.builtin.debug:\n    msg: a\n      b$CARET\n"))
        assertNull("a block scalar", slot("- ansible.builtin.shell: |\n    ech$CARET\n"))
    }

    private companion object {
        const val CARET = "<caret>"
    }
}
