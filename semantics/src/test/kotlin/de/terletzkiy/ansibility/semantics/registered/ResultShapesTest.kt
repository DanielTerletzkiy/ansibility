package de.terletzkiy.ansibility.semantics.registered

import de.terletzkiy.ansibility.semantics.schema.DocSnapshot
import de.terletzkiy.ansibility.semantics.schema.DocSnapshotTest
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.schema.OptionType
import de.terletzkiy.ansibility.semantics.schema.ReturnSpec
import de.terletzkiy.ansibility.semantics.typeflow.AValue
import de.terletzkiy.ansibility.semantics.typeflow.BaseType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The shape builder of typed register results (plan amendment FU, F1.12) on the bundled documentation of the pinned
 * (2.18.8) and latest lines: module returns, `contains`, the common keys, retries, async, loops, unions, supplements and
 * the logical types for ANS-T020.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResultShapesTest {
    private lateinit var pinned: DocSnapshot
    private lateinit var latest: DocSnapshot

    @BeforeAll
    fun load() {
        pinned = DocSnapshotTest.dataFile("core-2.18.8.json.gz").inputStream().use { DocSnapshot.load(it) }
        latest = DocSnapshotTest.dataFile("core-latest.json.gz").inputStream().use { DocSnapshot.load(it) }
    }

    private fun source(module: String, snapshot: DocSnapshot = pinned, loop: LoopFeature? = null, retried: Boolean = false, async: AsyncFeature? = null): ResultSource {
        val lookup = snapshot.module(module)
        val doc = lookup.doc
        return ResultSource(lookup.resolution.canonical, doc?.returns, doc?.options?.containsKey("backup") == true, loop, retried, async)
    }

    private fun shape(vararg sources: ResultSource) = ResultShapes.build("x", sources.toList())

    private val commonKeys = listOf("changed", "failed", "skipped", "invocation", "warnings", "deprecations", "diff")

    @Test
    fun `command documents its returns and every task adds the common keys after them`() {
        val shape = shape(source("ansible.builtin.command"))
        val keys = shape.members.keys.toList()
        assertEquals(
            listOf("cmd", "delta", "end", "msg", "rc", "start", "stderr", "stderr_lines", "stdout", "stdout_lines") + commonKeys,
            keys,
        )
        val rc = shape.member(listOf("rc"))!!
        assertEquals(OptionType.Int, rc.type)
        assertEquals(MemberKind.MODULE, rc.primary!!.kind)
        assertEquals("ansible.builtin.command", rc.primary!!.module)
        assertEquals(listOf("rc"), rc.primary!!.returnPath)
        assertEquals("always", rc.primary!!.returned)
        assertEquals("0", rc.primary!!.sample)
        assertEquals(listOf("The command return code (0 means success)."), rc.primary!!.description)
        assertEquals(OptionType.List, shape.member(listOf("stdout_lines"))!!.type)
        assertEquals(MemberKind.COMMON, shape.member(listOf("changed"))!!.primary!!.kind)
        assertEquals("common.changed", shape.member(listOf("changed"))!!.primary!!.textKey)
        // The module's own documentation of `msg` wins over the common key.
        assertEquals(listOf(MemberKind.MODULE), shape.member(listOf("msg"))!!.docs.map { it.kind })
        assertNull(shape.member(listOf("attempts")), "no attempts without until/retries")
        assertNull(shape.member(listOf("backup_file")), "command has no backup option")
        assertEquals(listOf(0), shape.member(listOf("stdout"))!!.tasks)
    }

    @Test
    fun `until and retries add attempts and retries`() {
        val shape = shape(source("ansible.builtin.command", retried = true))
        assertEquals(OptionType.Int, shape.member(listOf("attempts"))!!.type)
        assertEquals("retry.attempts", shape.member(listOf("attempts"))!!.primary!!.textKey)
        assertEquals(MemberKind.RETRY, shape.member(listOf("retries"))!!.primary!!.kind)
        val keys = shape.members.keys.toList()
        assertTrue(keys.indexOf("attempts") > keys.indexOf("stdout_lines") && keys.indexOf("attempts") < keys.indexOf("changed"), "$keys")
    }

    @Test
    fun `stat exists comes from contains`() {
        val shape = shape(source("stat"))
        val exists = shape.member(listOf("stat", "exists"))!!
        assertEquals(OptionType.Bool, exists.type)
        assertEquals(listOf("stat", "exists"), exists.primary!!.returnPath)
        assertEquals("ansible.builtin.stat", exists.primary!!.module)
        assertEquals(OptionType.Dict, shape.member(listOf("stat"))!!.type)
        val option = shape.root.option
        assertEquals(OptionType.Bool, option.options!!.getValue("stat").options!!.getValue("exists").type)
        assertTrue("isdir" in shape.member(listOf("stat"))!!.members!!.keys)
    }

    @Test
    fun `uri returns status and the undocumented json body`() {
        val shape = shape(source("ansible.builtin.uri"))
        assertEquals(OptionType.Int, shape.member(listOf("status"))!!.type)
        assertEquals(OptionType.Str, shape.member(listOf("content"))!!.type)
        val json = shape.member(listOf("json"))!!
        assertEquals(OptionType.Raw, json.type)
        assertEquals(MemberKind.SUPPLEMENT, json.primary!!.kind)
        assertEquals("supplement.uri.json", json.primary!!.textKey)
        assertEquals("ansible.builtin.uri", json.primary!!.module)
    }

    @Test
    fun `a module without returns has the common keys only and backup_file with a backup option`() {
        val service = shape(source("ansible.builtin.service"))
        assertEquals(listOf("changed", "failed", "skipped", "msg") + commonKeys.drop(3), service.members.keys.toList())
        assertTrue(service.members.values.all { it.kinds == setOf(MemberKind.COMMON) })
        val lineinfile = shape(source("ansible.builtin.lineinfile"))
        assertEquals(MemberKind.COMMON, lineinfile.member(listOf("backup_file"))!!.primary!!.kind)
        val unknown = ResultShapes.build("x", listOf(ResultSource(null, null)))
        assertEquals(service.members.keys, unknown.members.keys)
        assertEquals(listOf("module_args"), unknown.member(listOf("invocation"))!!.members!!.keys.toList())
    }

    @Test
    fun `raw and script return the command keys their documentation omits`() {
        for (module in listOf("ansible.builtin.raw", "ansible.builtin.script")) {
            val shape = shape(source(module))
            assertEquals(MemberKind.SUPPLEMENT, shape.member(listOf("rc"))!!.primary!!.kind, module)
            assertEquals(MemberKind.DERIVED, shape.member(listOf("stdout_lines"))!!.primary!!.kind, module)
            assertEquals(OptionType.Str, shape.member(listOf("stdout_lines", "0"))!!.type, module)
            assertEquals(MemberKind.DERIVED, shape.member(listOf("stderr_lines"))!!.primary!!.kind, module)
        }
    }

    @Test
    fun `stdout_lines is derived from a documented stdout`() {
        val apt = shape(source("ansible.builtin.apt"))
        val lines = apt.member(listOf("stdout_lines"))!!
        assertEquals(MemberKind.DERIVED, lines.primary!!.kind)
        assertEquals(listOf("stdout"), lines.primary!!.returnPath)
        assertEquals("derived.stdout_lines", lines.primary!!.textKey)
        assertEquals(OptionType.Str, lines.elements)
        // command documents its own stdout_lines: nothing is derived, but its bare `list` gets the lines' `str` elements.
        for (snapshot in listOf(pinned, latest)) {
            val command = shape(source("ansible.builtin.command", snapshot))
            val own = command.member(listOf("stdout_lines"))!!
            assertEquals(MemberKind.MODULE, own.primary!!.kind)
            assertNull(own.primary!!.elements, "the documentation names no element type")
            assertEquals(OptionType.Str, own.elements)
            assertEquals(OptionType.Str, command.member(listOf("stdout_lines", "0"))!!.type)
            assertEquals(OptionType.Str, command.member(listOf("stderr_lines", "-1"))!!.type)
            assertEquals(OptionType.Str, own.option.elements)
        }
    }

    @Test
    fun `shell and command share their keys and keep one documentation each`() {
        val shape = shape(source("ansible.builtin.command"), source("ansible.legacy.shell"))
        val stdout = shape.member(listOf("stdout"))!!
        assertEquals(listOf(0, 1), stdout.tasks)
        assertEquals(listOf("ansible.builtin.command", "ansible.builtin.shell"), stdout.docs.map { it.module })
        assertEquals(OptionType.Str, stdout.type)
        val changed = shape.member(listOf("changed"))!!
        assertEquals(1, changed.docs.size, "identical common documentation is merged")
        assertEquals(listOf(0, 1), changed.docs.single().tasks)
    }

    @Test
    fun `the union keeps every task's keys with per-member provenance`() {
        val shape = shape(source("ansible.builtin.stat"), source("ansible.builtin.command"))
        assertEquals(listOf(0), shape.member(listOf("stat"))!!.tasks)
        assertEquals(listOf(1), shape.member(listOf("rc"))!!.tasks)
        assertEquals(listOf(0, 1), shape.member(listOf("failed"))!!.tasks)
        val keys = shape.members.keys.toList()
        assertTrue(keys.indexOf("rc") < keys.indexOf("changed"), "module returns before common keys: $keys")
    }

    @Test
    fun `members the tasks type differently become raw`() {
        val a = ResultSource("ns.c.a", mapOf("value" to ReturnSpec("value", OptionType.Int)))
        val b = ResultSource("ns.c.b", mapOf("value" to ReturnSpec("value", OptionType.Str)))
        val value = shape(a, b).member(listOf("value"))!!
        assertEquals(OptionType.Raw, value.type)
        assertEquals(listOf(OptionType.Int, OptionType.Str), value.docs.map { it.type })
        assertNull(ResultShapes.logicalType(value))
    }

    @Test
    fun `a loop turns the result into results with the item keys`() {
        val shape = shape(source("ansible.builtin.command", loop = LoopFeature(ResultShapes.DEFAULT_LOOP_VAR)))
        assertEquals(listOf("results", "msg", "changed", "failed", "skipped"), shape.members.keys.toList())
        assertNull(shape.member(listOf("stdout")), "no module keys at the top of a looped result")
        val results = shape.member(listOf("results"))!!
        assertEquals(OptionType.List, results.type)
        assertEquals(OptionType.Dict, results.elements)
        assertEquals(MemberKind.LOOP, results.primary!!.kind)
        assertEquals(OptionType.Str, shape.member(listOf("results", "0", "stdout"))!!.type)
        assertEquals(MemberKind.LOOP_ITEM, shape.member(listOf("results", "0", "item"))!!.primary!!.kind)
        assertEquals(OptionType.Str, shape.member(listOf("results", "0", "ansible_loop_var"))!!.type)
        assertNull(shape.member(listOf("results", "0", "ansible_index_var")))
        assertNull(shape.member(listOf("results", "stdout")), "a list is read through an index")
        assertEquals("loop.msg", shape.member(listOf("msg"))!!.primary!!.textKey)
        val option = shape.root.option.options!!.getValue("results")
        assertEquals(OptionType.Dict, option.elements)
        assertTrue("stdout" in option.options!!.keys)
    }

    @Test
    fun `loop_control names the item, the index and ansible_loop`() {
        val item = OptionSpec("item", OptionType.Dict, options = linkedMapOf("port" to OptionSpec("port", OptionType.Int, description = listOf("The port."))))
        val loop = LoopFeature("srv", indexVar = "idx", extended = true, item = item)
        val shape = shape(source("ansible.builtin.command", loop = loop, retried = true))
        val keys = shape.member(listOf("results", "0"))!!.members!!.keys
        assertTrue(keys.containsAll(listOf("srv", "ansible_loop_var", "idx", "ansible_index_var", "ansible_loop", "attempts")), "$keys")
        assertFalse("item" in keys)
        val port = shape.member(listOf("results", "0", "srv", "port"))!!
        assertEquals(OptionType.Int, port.type)
        assertEquals(listOf("The port."), port.primary!!.description)
        assertEquals(OptionType.Int, shape.member(listOf("results", "0", "idx"))!!.type)
        assertEquals(OptionType.Bool, shape.member(listOf("results", "0", "ansible_loop", "first"))!!.type)
        assertNull(shape.member(listOf("attempts")), "attempts belong to each item")
    }

    @Test
    fun `async jobs carry the job keys typed by async_status of the target line`() {
        val polled = shape(source("ansible.builtin.command", async = AsyncFeature(polled = true, statusReturns = pinned.module(ResultShapes.ASYNC_STATUS).doc!!.returns)))
        assertEquals(OptionType.Str, polled.member(listOf("stdout"))!!.type)
        assertEquals(OptionType.Int, polled.member(listOf("finished"))!!.type)
        assertEquals(ResultShapes.ASYNC_STATUS, polled.member(listOf("finished"))!!.primary!!.module)
        assertEquals(MemberKind.ASYNC, polled.member(listOf("results_file"))!!.primary!!.kind)
        assertEquals(MemberKind.ASYNC, polled.member(listOf("ansible_job_id"))!!.primary!!.kind)

        val fireAndForget = shape(source("ansible.builtin.command", latest, async = AsyncFeature(polled = false, statusReturns = latest.module(ResultShapes.ASYNC_STATUS).doc!!.returns)))
        assertNull(fireAndForget.member(listOf("stdout")), "poll: 0 returns before the module ran")
        assertEquals(OptionType.Bool, fireAndForget.member(listOf("started"))!!.type, "latest async_status documents bools")
        assertTrue("changed" in fireAndForget.members)

        val builtIn = shape(source("ansible.builtin.command", async = AsyncFeature(polled = false)))
        assertEquals("async.started", builtIn.member(listOf("started"))!!.primary!!.textKey)
    }

    @Test
    fun `member paths step into list elements`() {
        val shape = shape(source("ansible.builtin.find"))
        assertEquals(OptionType.List, shape.member(listOf("files"))!!.type)
        assertNull(shape.member(listOf("files", "0")), "find documents neither keys nor an element type of its files")
        assertNull(shape.member(listOf("rc")))
        assertNull(shape.member(listOf("stat", "exists")))
        assertEquals(shape.root, shape.member(emptyList()))
    }

    @Test
    fun `logical types follow the documented types`() {
        val shape = shape(source("ansible.builtin.command"))
        assertEquals(AValue.of(BaseType.INT), ResultShapes.logicalType(shape.member(listOf("rc"))!!))
        assertEquals(AValue.of(BaseType.STR), ResultShapes.logicalType(shape.member(listOf("stdout"))!!))
        assertEquals(AValue.of(BaseType.LIST), ResultShapes.logicalType(shape.member(listOf("stdout_lines"))!!))
        assertEquals(AValue.of(BaseType.BOOL), ResultShapes.logicalType(shape.member(listOf("changed"))!!))
        assertNull(ResultShapes.logicalType(shape.member(listOf("diff"))!!))
    }

    @Test
    fun `a shape needs a registering task`() {
        assertThrows(IllegalArgumentException::class.java) { ResultShapes.build("x", emptyList()) }
        assertNotNull(ResultShapes.resultOf(ResultSource(null, null), 0).members)
    }
}
