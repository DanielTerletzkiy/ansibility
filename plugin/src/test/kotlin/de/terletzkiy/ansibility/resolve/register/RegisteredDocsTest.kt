package de.terletzkiy.ansibility.resolve.register

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleDocService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.semantics.registered.AsyncFeature
import de.terletzkiy.ansibility.semantics.registered.LoopFeature
import de.terletzkiy.ansibility.semantics.registered.MemberKind
import de.terletzkiy.ansibility.semantics.registered.ResultMember
import de.terletzkiy.ansibility.semantics.registered.ResultShape
import de.terletzkiy.ansibility.semantics.registered.ResultShapes
import de.terletzkiy.ansibility.semantics.registered.ResultSource

/**
 * Texts and pages of registered result members ([RegisteredDocs]): every built-in member the shape builder can produce
 * has a description in the bundle, the kinds and tails name the origin, and each kind links to its page.
 */
class RegisteredDocsTest : BasePlatformTestCase() {
    private lateinit var root: AnsibleRoot

    override fun setUp() {
        super.setUp()
        RegisteredFixture.create { path, text -> myFixture.tempDirFixture.createFile(path, text) }
        (AnsibleWorkspaceImpl.getInstance(project) ?: error("AnsibleWorkspace is not AnsibleWorkspaceImpl")).structureChanged()
        root = runReadActionBlocking { AnsibleWorkspace.getInstance(project).contextOf(myFixture.findFileInTempDir(RegisteredFixture.TASKS)!!)!!.root }
    }

    private fun doc(module: String) = runReadActionBlocking { AnsibleDocService.getInstance(project).moduleDoc(root, module)!!.doc!! }

    /** Every member below [member], itself included. */
    private fun walk(member: ResultMember): List<ResultMember> = listOf(member) + member.members.orEmpty().values.flatMap(::walk)

    fun testEveryBuiltInMemberHasADescription() {
        val loop = LoopFeature("srv", indexVar = "idx", extended = true)
        val sources = listOf(
            ResultSource("ansible.builtin.raw", null, backup = true, loop = loop, retried = true, async = AsyncFeature(polled = true)),
            ResultSource("ansible.builtin.uri", doc("ansible.builtin.uri").returns, retried = true, async = AsyncFeature(polled = false)),
            ResultSource("ansible.builtin.lineinfile", doc("ansible.builtin.lineinfile").returns, backup = true),
        )
        val members = sources.flatMap { walk(ResultShapes.build("x", listOf(it)).root) } + walk(ResultShapes.resultOf(sources.first().copy(loop = null), 0))
        val keys = members.flatMap { it.docs }.mapNotNull { it.textKey }.toSortedSet()
        assertTrue(keys.toString(), keys.containsAll(listOf("common.changed", "loop.results", "item.ansible_loop.first", "retry.attempts", "async.results_file", "supplement.command.rc", "derived.stdout_lines", "common.backup_file")))
        for (key in keys) {
            assertNotNull("member.$key.description", AnsibilityRegisteredBundle.messageOrNull("member.$key.description"))
        }
        for (doc in members.flatMap { it.docs }) {
            if (doc.textKey != null) assertTrue("$doc has a text", RegisteredDocs.description(doc).isNotEmpty())
            assertTrue(RegisteredDocs.kind(doc).isNotBlank())
        }
    }

    fun testKindsTailsAndPages() {
        val docs = AnsibleDocService.getInstance(project)
        val command = ResultShapes.build("x", listOf(ResultSource("ansible.builtin.command", doc("ansible.builtin.command").returns, retried = true)))
        val looped = ResultShapes.build("x", listOf(ResultSource("ansible.builtin.command", doc("ansible.builtin.command").returns, loop = LoopFeature("item"))))
        fun pages(shape: ResultShape, vararg path: String) = runReadActionBlocking {
            RegisteredDocs.pages(docs, root, path.last { it.toIntOrNull() == null }, shape.member(path.toList())!!)
        }
        val stdout = command.member(listOf("stdout"))!!
        assertEquals("return value of ansible.builtin.command", RegisteredDocs.kind(stdout.primary!!))
        assertEquals("command", RegisteredDocs.tail(stdout))
        assertEquals("always", RegisteredDocs.returned(stdout.primary!!))
        val stdoutPage = pages(command, "stdout").single()
        assertEquals("ansible.builtin.command docs", stdoutPage.label)
        assertEquals("ansible.builtin.command", stdoutPage.module)
        assertTrue(stdoutPage.url, stdoutPage.url.endsWith("collections/ansible/builtin/command_module.html#return-stdout"))
        assertEquals("common", RegisteredDocs.tail(command.member(listOf("changed"))!!))
        assertEquals("always", RegisteredDocs.returned(command.member(listOf("changed"))!!.primary!!))
        assertTrue(pages(command, "changed").single().url.endsWith("reference_appendices/common_return_values.html#changed"))
        assertEquals("until", RegisteredDocs.tail(command.member(listOf("attempts"))!!))
        assertTrue(pages(command, "attempts").single().url.endsWith("playbook_guide/playbooks_loops.html#retrying-a-task-until-a-condition-is-met"))
        assertEquals("loop", RegisteredDocs.tail(looped.member(listOf("results"))!!))
        assertTrue(pages(looped, "results").single().url.endsWith("playbook_guide/playbooks_loops.html#registering-variables-with-a-loop"))
        assertEquals("loop item", RegisteredDocs.tail(looped.member(listOf("results", "0", "item"))!!))
        assertTrue(pages(looped, "results", "0", "stdout").single().url.endsWith("command_module.html#return-stdout"))

        val raw = ResultShapes.build("x", listOf(ResultSource("ansible.builtin.raw", null)))
        assertEquals(MemberKind.DERIVED, raw.member(listOf("stdout_lines"))!!.primary!!.kind)
        assertEquals("from ansible-core", RegisteredDocs.tail(raw.member(listOf("stdout_lines"))!!))
        assertTrue(pages(raw, "stdout_lines").single().url.endsWith("common_return_values.html#stdout-lines"))
        assertTrue(pages(raw, "rc").single().url.endsWith("collections/ansible/builtin/raw_module.html"))

        val job = ResultShapes.build("x", listOf(ResultSource("ansible.builtin.command", null, async = AsyncFeature(polled = false, statusReturns = doc(ResultShapes.ASYNC_STATUS).returns))))
        assertTrue(pages(job, "finished").single().url.endsWith("async_status_module.html#return-finished"))
        val builtInJob = ResultShapes.build("x", listOf(ResultSource("ansible.builtin.command", null, async = AsyncFeature(polled = false))))
        assertTrue(pages(builtInJob, "results_file").single().url.endsWith("playbook_guide/playbooks_async.html"))
        assertEquals("async", RegisteredDocs.tail(builtInJob.member(listOf("results_file"))!!))
    }
}
