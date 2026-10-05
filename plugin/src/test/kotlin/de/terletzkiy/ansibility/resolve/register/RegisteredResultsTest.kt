package de.terletzkiy.ansibility.resolve.register

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.model.inventory.ModelCaches
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.resolve.loop.LoopItemTyper
import de.terletzkiy.ansibility.semantics.registered.MemberKind
import de.terletzkiy.ansibility.semantics.schema.OptionType

/**
 * The plugin side of typed register results (plan amendment FU, F1.12) on [RegisteredFixture]: which `register:`
 * definitions a position sees, the module through routing (short names, `ansible.legacy`), the task features (until,
 * loop, async), unions with per-member provenance, templates, handlers, root scoping, the loop typer hook and the
 * content-stamp cache.
 */
class RegisteredResultsTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        RegisteredFixture.create { path, text -> myFixture.tempDirFixture.createFile(path, text) }
        (AnsibleWorkspaceImpl.getInstance(project) ?: error("AnsibleWorkspace is not AnsibleWorkspaceImpl")).structureChanged()
    }

    private fun vf(path: String): VirtualFile = myFixture.findFileInTempDir(path) ?: error("missing $path")

    /** The offset of [marker] on the first line containing it (after the line with [after]). */
    private fun offsetOf(path: String, marker: String, after: String? = null, delta: Int = 0): Int {
        val text = VfsUtilCore.loadText(vf(path))
        val line = RegisteredFixture.lineOf(text, marker, after)
        val start = StringUtil.lineColToOffset(text, line - 1, 0)
        return start + text.substring(start).indexOf(marker) + delta
    }

    private fun at(path: String, marker: String, name: String, after: String? = null): RegisteredResult? = runReadActionBlocking {
        RegisteredResults.getInstance(project).at(vf(path), offsetOf(path, marker, after), name)
    }

    fun testTheUntilOfTheTaskSeesItsOwnCommandResult() {
        val result = at(RegisteredFixture.TASKS, "in keepalived_floating_ip_check.stdout", "keepalived_floating_ip_check")!!
        val task = result.tasks.single()
        assertEquals("ansible.builtin.command", task.canonical)
        assertEquals("Wait for keepalived to assign the floating IP", task.taskName)
        assertTrue(task.source.retried)
        val keys = result.shape.members.keys
        for (key in listOf("stdout", "stdout_lines", "stderr", "stderr_lines", "rc", "cmd", "delta", "start", "end", "msg", "attempts", "changed", "failed", "skipped")) {
            assertTrue("$key in $keys", key in keys)
        }
        assertEquals(OptionType.Int, result.member(listOf("rc"))!!.type)
        assertEquals(MemberKind.RETRY, result.member(listOf("attempts"))!!.primary!!.kind)
        assertEquals("keepalived_floating_ip_check", result.option(emptyList())!!.name)
    }

    fun testARegisterIsNotVisibleBeforeItsTaskNorInAnotherTasksExpressions() {
        assertNull(at(RegisteredFixture.TASKS, "name: keepalived", "keepalived_floating_ip_check"))
        assertNull(at(RegisteredFixture.TASKS, "changed_when: false", "keepalived_floating_ip_check"))
        assertNotNull(at(RegisteredFixture.TASKS, "msg: \"{{ keepalived_conf", "keepalived_floating_ip_check"))
    }

    fun testStatAndUriReturnValues() {
        val stat = at(RegisteredFixture.TASKS, "keepalived_conf.stat.exists", "keepalived_conf")!!
        assertEquals(OptionType.Bool, stat.member(listOf("stat", "exists"))!!.type)
        assertEquals(listOf("stat", "exists"), stat.member(listOf("stat", "exists"))!!.primary!!.returnPath)
        val uri = at(RegisteredFixture.TASKS, "keepalived_api.status", "keepalived_api")!!
        assertEquals(OptionType.Int, uri.member(listOf("status"))!!.type)
        assertEquals(MemberKind.SUPPLEMENT, uri.member(listOf("json"))!!.primary!!.kind)
    }

    fun testShortModuleNamesAndLoopsGiveResultsPerItem() {
        val pings = at(RegisteredFixture.TASKS, "loop: \"{{ keepalived_pings.results }}\"", "keepalived_pings")!!
        val task = pings.tasks.single()
        assertEquals("command", task.module)
        assertEquals("ansible.builtin.command", task.canonical)
        assertEquals(listOf("results", "msg", "changed", "failed", "skipped"), pings.shape.members.keys.toList())
        assertEquals(OptionType.Str, pings.member(listOf("results", "0", "stdout"))!!.type)
        assertEquals(OptionType.Str, pings.member(listOf("results", "0", "item"))!!.type)
        assertEquals(OptionType.Dict, pings.option(listOf("results"))!!.elements)
    }

    fun testTheLoopTyperTypesItemsOfALoopOverResults() {
        val item = runReadActionBlocking {
            val yaml = YamlFiles.yamlFile(project, vf(RegisteredFixture.TASKS))!!
            LoopItemTyper.typeAt(project, yaml, offsetOf(RegisteredFixture.TASKS, "msg: \"{{ item.stdout"))?.loop?.item
        }!!
        assertEquals(OptionType.Dict, item.type)
        assertEquals(OptionType.Str, item.options!!.getValue("stdout").type)
        assertTrue(item.options!!.keys.containsAll(listOf("rc", "item", "ansible_loop_var", "changed")))
    }

    fun testTwoTasksRegisteringOneNameGiveTheUnionWithProvenance() {
        val either = at(RegisteredFixture.TASKS, "msg: \"{{ PLACEHOLDER }}\"", "keepalived_either")!!
        assertEquals(listOf("ansible.legacy.shell", "ansible.builtin.command"), either.tasks.map { it.module })
        assertEquals(listOf("ansible.builtin.shell", "ansible.builtin.command"), either.tasks.map { it.canonical })
        val stdout = either.member(listOf("stdout"))!!
        assertEquals(listOf("Shell variant", "Command variant"), either.tasksOf(stdout).map { it.taskName })
        assertEquals(listOf("ansible.builtin.shell", "ansible.builtin.command"), stdout.docs.map { it.module })
        assertEquals(1, either.member(listOf("changed"))!!.docs.size)
    }

    fun testAModuleWithoutReturnsHasTheCommonKeysOnly() {
        val service = at(RegisteredFixture.TASKS, "msg: \"{{ PLACEHOLDER }}\"", "keepalived_service")!!
        assertEquals("ansible.builtin.service", service.tasks.single().canonical)
        assertTrue(service.shape.members.values.all { it.kinds == setOf(MemberKind.COMMON) })
        assertTrue("changed" in service.shape.members)
    }

    fun testAnAsyncJobWithoutPollingHasTheJobKeysOnly() {
        val job = at(RegisteredFixture.TASKS, "msg: \"{{ PLACEHOLDER }}\"", "keepalived_job")!!
        val keys = job.shape.members.keys
        assertFalse("stdout" in keys)
        assertTrue(keys.containsAll(listOf("ansible_job_id", "started", "finished", "results_file", "changed")))
    }

    fun testATemplateRenderedByALaterTaskSeesTheRegistersBeforeIt() {
        assertNotNull(at(RegisteredFixture.TEMPLATE, "keepalived_conf.stat", "keepalived_conf"))
        assertNotNull(at(RegisteredFixture.TEMPLATE, "keepalived_pings.results", "keepalived_pings"))
        assertNull("registered after the template task", at(RegisteredFixture.TEMPLATE, "keepalived_conf.stat", "keepalived_either"))
    }

    fun testHandlersSeeEveryRegisterOfTheRole() {
        assertNotNull(at(RegisteredFixture.HANDLERS, "PLACEHOLDER", "keepalived_job"))
    }

    fun testResultsStayInTheirRoot() {
        val other = at(RegisteredFixture.OTHER_TASKS, "PLACEHOLDER", "keepalived_conf")!!
        assertEquals("ansible.builtin.uri", other.tasks.single().canonical)
        assertNull(other.member(listOf("stat")))
        val root = runReadActionBlocking { AnsibleWorkspace.getInstance(project).contextOf(vf(RegisteredFixture.OTHER_TASKS))!!.root }
        assertEquals(root.dir, other.root.dir)
        assertNull(at(RegisteredFixture.OTHER_TASKS, "PLACEHOLDER", "keepalived_api"))
    }

    fun testResultsAreCachedUntilTheTaskFileChanges() {
        val first = at(RegisteredFixture.TASKS, "keepalived_conf.stat.exists", "keepalived_conf")!!
        val before = ModelCaches.getInstance(project).snapshot()
        assertSame(first, at(RegisteredFixture.TASKS, "keepalived_conf.stat.exists", "keepalived_conf"))
        val after = ModelCaches.getInstance(project).snapshot()
        assertEquals(0L, after.computationsOf("registered.results", before))
        assertEquals(0L, after.computationsOf("registered.tasks", before))
        val document = runReadActionBlocking { FileDocumentManager.getInstance().getDocument(vf(RegisteredFixture.TASKS))!! }
        WriteCommandAction.runWriteCommandAction(project) {
            val start = document.text.indexOf("ansible.builtin.stat:")
            document.replaceString(start, start + "ansible.builtin.stat:".length, "ansible.builtin.uri:")
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val edited = at(RegisteredFixture.TASKS, "keepalived_conf.stat.exists", "keepalived_conf")!!
        assertNotSame(first, edited)
        assertEquals("ansible.builtin.uri", edited.tasks.single().canonical)
    }

    fun testTheTypeFlowScopeIsTheRole() {
        val result = runReadActionBlocking {
            val context = AnsibleWorkspace.getInstance(project).contextOf(vf(RegisteredFixture.TASKS))!!
            RegisteredResults.getInstance(project).inScope(context.root, context.roleDir, "keepalived_either")
        }!!
        assertEquals(2, result.tasks.size)
    }
}
