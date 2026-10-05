package de.terletzkiy.ansibility.semantics.render.oracle

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The case reader: how a version resolves to expected values, on the real corpus and on a synthetic overlay. */
class OracleCorpusTest {

    @Test
    fun `an extra core takes its own file and falls back to common`() {
        val case = corpus.case("02_template_env_defaults")
        val dir = File(case.dir, "expected")
        val ownOn219 = check(case, "2.19", "file:crlf/only_block.j2.txt").expected as Expected.Bytes
        assertArrayEquals(File(dir, "2.19/crlf/only_block.j2.txt").readBytes(), ownOn219.bytes)
        val commonOn219 = check(case, "2.19", "file:crlf/blocks.j2.txt").expected as Expected.Bytes
        assertArrayEquals(File(dir, "common/crlf/blocks.j2.txt").readBytes(), commonOn219.bytes)
    }

    @Test
    fun `a file one primary core does not write has no check on the other`() {
        val case = corpus.case("02_template_env_defaults")
        assertTrue(case.checks(case.core("2.18")).any { it.id == "file:default/hdr_bad.j2.txt" })
        assertTrue(case.checks(case.core("2.21")).none { it.id == "file:default/hdr_bad.j2.txt" })
    }

    @Test
    fun `task names resolve per core`() {
        val case = corpus.case("13_loop_items")
        val on221 = nameOf(case, "2.21", "W16 ")
        val on218 = nameOf(case, "2.18", "W16 ")
        assertEquals("W16 undefined item in a task name << error 1 - 'item' is undefined >> / localhost / NO-ITEM", on221)
        assertEquals("W16 undefined item in a task name {{ item }} / {{ inventory_hostname }} / {{ item | default('NO-ITEM') }}", on218)
    }

    @Test
    fun `a templated condition with backslashes is pinned on 2_18 and 2_21`() {
        val case = corpus.case("09_backslash_escaping")
        for (core in listOf("2.18", "2.21")) {
            assertEquals(Expected.Succeeded, taskCheck(case, core, "BS:when_curly_single", TaskField.RESULT).expected, core)
            assertEquals(Expected.Value("ran"), taskCheck(case, core, "BS:when_curly_single", TaskField.MSG).expected, core)
            assertEquals(Expected.Skipped, taskCheck(case, core, "BS:when_curly_double", TaskField.RESULT).expected, core)
        }
    }

    @Test
    fun `loop items get item, result and msg checks`() {
        val case = corpus.case("01_user_example_fileglob")
        val ids = case.checks(case.core("2.21")).map { it.id }
        val items = ids.filter { it.matches(Regex("task:\\d+:host-a:0:item")) }.map { it.removeSuffix(":item") }
        assertTrue(items.isNotEmpty(), ids.toString())
        assertTrue(items.all { "$it:result" in ids }, items.toString())
        assertTrue(items.any { "$it:msg" in ids }, items.toString())
    }

    @Test
    fun `expression rows give template, arg and type checks per core`() {
        val case = corpus.case("04_value_rendering")
        assertEquals(Expected.Text("(1, 'a')"), check(case, "2.18", "expr:r_tuple:template").expected)
        assertEquals(Expected.Text("[1, 'a']"), check(case, "2.21", "expr:r_tuple:template").expected)
        assertEquals(Expected.Text("[1, 'a']"), check(case, "2.19", "expr:r_tuple:template").expected)
        assertEquals(Expected.Value("tuple"), check(case, "2.21", "expr:r_tuple:type").expected)
    }

    @Test
    fun `set results on strings vary with the hash seed, the others do not`() {
        val case = corpus.case("14_set_filter_order")
        val checks = case.checks(case.core("2.21"))
        val byTask = checks.filter { (it.subject as CheckSubject.Task).field == TaskField.MSG }
            .associateBy { (it.subject as CheckSubject.Task).task }
        assertTrue(byTask.getValue("union").orderVaries)
        assertTrue(byTask.getValue("symmetric_difference").orderVaries)
        assertFalse(byTask.getValue("intersect").orderVaries)
        assertFalse(byTask.getValue("unique (order-preserving)").orderVaries)
        assertFalse(byTask.getValue("union of ints").orderVaries)
    }

    @Test
    fun `case json order_varies marks expression rows`() {
        val case = corpus.case("06_filters_more")
        assertTrue(check(case, "2.18", "expr:union_str:arg").orderVaries)
        assertFalse(check(case, "2.18", "expr:unique_str:arg").orderVaries)
    }

    @Test
    fun `a regex row that depends on the controller's Python is unprovable on every core`() {
        val case = corpus.case("15_regex_python_vs_java")
        for (core in listOf("2.18", "2.21")) {
            assertTrue(check(case, core, "regex:small_z").expected is Expected.Unprovable, core)
            assertEquals(Expected.Text("a\r\n"), check(case, core, "regex:dollar_crlf").expected, core)
        }
        val count = check(case, "2.21", "regex:count_2").subject as CheckSubject.RegexSub
        assertEquals(2, count.count)
        assertEquals("python_3_14", OracleChecks.pythonKey("3.14.7"))
    }

    @Test
    fun `an overlay resolves own files, common files, missing files and keys`(@TempDir dir: File) {
        val case = File(dir, "99_overlay")
        write(case, "case.json", caseJson("99_overlay", extra = listOf("2.19")))
        write(case, "input/site.yml", "- hosts: localhost\n")
        write(case, "expected/common/a.txt", "common a")
        write(case, "expected/common/b.txt", "common b")
        write(case, "expected/2.19/a.txt", "own a")
        write(
            case, "expected/summary.json",
            """{"extra": {"2.19": {"rc": "rc=0", "core": "2.19.13", "differs": ["a.txt"], "missing": ["b.txt"]}}}""",
        )
        write(
            case, "expected/tasks.json",
            """[
              {"play": "p", "task": "t0", "both": {"h": {"msg": "same"}}, "2.19": {"h": {"error": "boom"}}},
              {"play": "p", "task": "t1", "2.21": {"h": {"skipped": true}}, "2.18": {"h": {"ok": true}}, "2.19": null},
              {"play": "p", "task": "t2", "task_2.19": "t2 on 2.19", "both": {"h": {"ok": true}}},
              {"play": "p", "task": "t3", "2.21": null, "2.18": null, "2.19": {"h": {"msg": 1}}}
            ]""",
        )
        val loaded = OracleCase.load(case)
        val on219 = loaded.checks(loaded.core("2.19")).associateBy { it.id }
        val on218 = loaded.checks(loaded.core("2.18")).associateBy { it.id }
        assertEquals("own a", text(on219.getValue("file:a.txt")))
        assertNull(on219["file:b.txt"])
        assertEquals("common b", text(on218.getValue("file:b.txt")))
        assertEquals(Expected.Failure("boom"), on219.getValue("task:0:h:result").expected)
        assertNull(on219["task:0:h:msg"])
        assertEquals(Expected.Value("same"), on218.getValue("task:0:h:msg").expected)
        assertNull(on219["task:1:name"])
        assertEquals(Expected.Succeeded, on218.getValue("task:1:h:result").expected)
        assertEquals(Expected.Value("t2 on 2.19"), on219.getValue("task:2:name").expected)
        assertEquals(Expected.Value("t2"), on218.getValue("task:2:name").expected)
        assertEquals(Expected.Value(1L), on219.getValue("task:3:h:msg").expected)
        assertNull(on218["task:3:name"])
        assertTrue(loaded.cores.single { it.label == "2.19" }.let { !it.primary && it.python == "3.13.16" })
    }

    @Test
    fun `case json must match its directory and its versions`(@TempDir dir: File) {
        val case = File(dir, "98_named")
        write(case, "case.json", caseJson("97_other"))
        assertThrows(IllegalArgumentException::class.java) { OracleCase.load(case) }
        write(case, "case.json", caseJson("98_named").replace("\"2.18.8\"", "\"2.19.1\""))
        assertThrows(IllegalArgumentException::class.java) { OracleCase.load(case) }
    }

    @Test
    fun `an unknown case or core is reported with the known ones`() {
        val e = assertThrows(IllegalArgumentException::class.java) { corpus.case("00_none") }
        assertTrue("01_user_example_fileglob" in e.message!!)
        assertThrows(IllegalArgumentException::class.java) { corpus.case("15_regex_python_vs_java").core("2.19") }
    }

    private fun check(case: OracleCase, core: String, id: String): OracleCheck =
        case.checks(case.core(core)).firstOrNull { it.id == id } ?: error("$case × $core has no check $id")

    private fun taskCheck(case: OracleCase, core: String, task: String, field: TaskField): OracleCheck =
        case.checks(case.core(core)).first { (it.subject as? CheckSubject.Task)?.let { s -> s.task == task && s.field == field } == true }

    private fun nameOf(case: OracleCase, core: String, prefix: String): Any? =
        (case.checks(case.core(core)).first {
            (it.subject as? CheckSubject.Task)?.let { s -> s.field == TaskField.NAME && s.task.startsWith(prefix) } == true
        }.expected as Expected.Value).value

    private fun text(check: OracleCheck): String = String((check.expected as Expected.Bytes).bytes, Charsets.UTF_8)

    private fun write(dir: File, path: String, text: String) {
        File(dir, path).apply { parentFile.mkdirs() }.writeText(text)
    }

    private fun caseJson(id: String, extra: List<String> = emptyList()): String {
        val versions = mutableListOf(
            """"2.21": {"core": "2.21.4", "python": "3.14.7", "jinja2": "3.1.6", "where": "local"}""",
            """"2.18": {"core": "2.18.8", "python": "3.13.15", "jinja2": "3.1.6", "where": "docker"}""",
        )
        if ("2.19" in extra) versions += """"2.19": {"core": "2.19.13", "python": "3.13.16", "jinja2": "3.1.6", "where": "docker"}"""
        return """{"case": "$id", "title": "t", "kind": "files", "pins": ["p"], "versions": {${versions.joinToString()}},
            "primary": ["2.21", "2.18"], "extra": [${extra.joinToString { "\"$it\"" }}]}"""
    }

    private companion object {
        val corpus by lazy { OracleCorpus.load() }
    }
}
