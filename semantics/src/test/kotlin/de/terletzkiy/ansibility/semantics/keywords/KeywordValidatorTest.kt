package de.terletzkiy.ansibility.semantics.keywords

import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.coerce.CoreSemantics
import de.terletzkiy.ansibility.semantics.schema.DocSnapshot
import de.terletzkiy.ansibility.semantics.schema.DocSnapshotTest
import de.terletzkiy.ansibility.semantics.testutil.YamlText
import de.terletzkiy.ansibility.semantics.yaml.SourceRange
import de.terletzkiy.ansibility.semantics.yaml.YMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * [KeywordValidator] on the plan's examples (F5.10, M4 acceptance 5 and 6): what is rejected, what stays clean, the
 * wording per target version and the rejected node. The full agreement with ansible-core is [KeywordOracleTest].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeywordValidatorTest {
    private lateinit var pinned: DocSnapshot
    private lateinit var latest: DocSnapshot

    @BeforeAll
    fun load() {
        pinned = DocSnapshotTest.dataFile("core-2.18.8.json.gz").inputStream().use { DocSnapshot.load(it) }
        latest = DocSnapshotTest.dataFile("core-latest.json.gz").inputStream().use { DocSnapshot.load(it) }
    }

    private val v218 = KeywordValidator(CoreSemantics(CoreVersion(2, 18, 8)))
    private val v221 = KeywordValidator(CoreSemantics(CoreVersion(2, 21, 4)))

    /** The rejections of `name: <yaml>` on [owner] under [validator]. */
    private fun check(owner: PlaybookObject, name: String, yaml: String, validator: KeywordValidator = v218): List<KeywordRejection> {
        val entry = (YamlText.parse("$name: $yaml") as YMap).entries.single()
        val docs = if (validator === v221) latest else pinned
        return validator.check(owner, name, docs.keyword(name), entry.value)
    }

    private fun reasons(owner: PlaybookObject, name: String, yaml: String, validator: KeywordValidator = v218) =
        check(owner, name, yaml, validator).map { it.reason }

    @Test
    fun `snapshot keyword docs carry listof`() {
        assertEquals(listOf("str", "int"), pinned.keyword("tags")!!.listOf)
        assertEquals(listOf("str"), pinned.keyword("hosts")!!.listOf)
        assertEquals(listOf("str"), latest.keyword("listen")!!.listOf)
        assertNull(pinned.keyword("notify")!!.listOf)
        assertNull(pinned.keyword("retries")!!.listOf)
    }

    @Test
    fun `plan examples are rejected with the target's wording`() {
        assertEquals(
            listOf("the field 'retries' has an invalid value ('abc'), and could not be converted to int."),
            reasons(PlaybookObject.TASK, "retries", "abc"),
        )
        assertEquals(
            listOf("Error processing keyword 'retries': The value 'abc' could not be converted to 'int'."),
            reasons(PlaybookObject.TASK, "retries", "abc", v221),
        )
        assertEquals(
            listOf("the field 'pause' has an invalid value ('x'), and could not be converted to float."),
            reasons(PlaybookObject.LOOP_CONTROL, "pause", "x"),
        )
        val serial = check(PlaybookObject.PLAY, "serial", "[a]").single()
        assertTrue(serial.crash, "a bad serial batch is a traceback")
        assertEquals("ValueError: invalid literal for int() with base 10: 'a'", serial.reason)
    }

    @Test
    fun `scalars of list keywords and convertible values stay clean`() {
        for ((name, yaml) in listOf(
            "tags" to "haproxy", "tags" to "\"a,b\"", "notify" to "Reload haproxy", "when" to "x is defined",
            "changed_when" to "false", "until" to "result is success", "serial" to "1", "serial" to "\"50%\"",
            "retries" to "\"3\"", "retries" to "3.0", "retries" to "\"1e3\"", "delay" to "\"1.5\"", "become" to "yes",
            "become" to "\"on\"", "become" to "1", "become_user" to "1000", "loop" to "abc", "environment" to "x",
        )) {
            val owner = if (name == "serial") PlaybookObject.PLAY else PlaybookObject.TASK
            assertEquals(emptyList<String>(), reasons(owner, name, yaml), "$name: $yaml")
        }
    }

    @Test
    fun `templated values and vaults are never judged, static keywords are`() {
        assertTrue(reasons(PlaybookObject.TASK, "retries", "\"{{ r }}\"").isEmpty())
        assertTrue(reasons(PlaybookObject.TASK, "become", "!vault |\n  dummy").isEmpty())
        assertEquals(
            listOf("Invalid variable name in 'register' specified: '{{ r }}'"),
            reasons(PlaybookObject.TASK, "register", "\"{{ r }}\""),
        )
        assertEquals(1, reasons(PlaybookObject.TASK, "loop_control", "\"{{ lc }}\"").size)
    }

    @Test
    fun `the rejected node is the offending item`() {
        val text = "tags: [a, 1.5]"
        val entry = (YamlText.parse(text) as YMap).entries.single()
        val rejection = v218.check(PlaybookObject.TASK, "tags", pinned.keyword("tags"), entry.value).single()
        assertEquals(SourceRange(text.indexOf("1.5"), text.indexOf("1.5") + 3), rejection.range)
        assertEquals("the field 'tags' should be a list of (str, int), but the item '1.5' is a float", rejection.reason)
        assertEquals(
            "Keyword 'tags' items must be of type 'str' or 'int', not 'float'.",
            v221.check(PlaybookObject.TASK, "tags", latest.keyword("tags"), entry.value).single().reason,
        )
    }

    @Test
    fun `objects ansible-core does not post-validate keep their values`() {
        // A play's own fact_path is never converted; label of loop_control is only checked for its shape.
        assertTrue(reasons(PlaybookObject.PLAY, "fact_path", "1").isEmpty())
        assertTrue(reasons(PlaybookObject.LOOP_CONTROL, "label", "1").isEmpty())
        assertEquals(1, reasons(PlaybookObject.LOOP_CONTROL, "label", "[a]").size)
        // Play keywords that tasks inherit are converted on the tasks.
        assertEquals(1, reasons(PlaybookObject.PLAY, "become", "maybe").size)
        assertEquals(1, reasons(PlaybookObject.BLOCK, "port", "abc").size)
        assertEquals(1, reasons(PlaybookObject.ROLE, "port", "abc").size)
    }

    @Test
    fun `import_playbook entries are post-validated from 2_19 only`() {
        assertTrue(reasons(PlaybookObject.PLAYBOOK_INCLUDE, "become", "maybe").isEmpty())
        assertEquals(1, reasons(PlaybookObject.PLAYBOOK_INCLUDE, "become", "maybe", v221).size)
        assertEquals(listOf("tags must be specified as a list"), reasons(PlaybookObject.PLAYBOOK_INCLUDE, "tags", "5"))
        assertEquals(
            listOf("vars for import_playbook statements must be specified as a dictionary"),
            reasons(PlaybookObject.PLAYBOOK_INCLUDE, "vars", "~"),
        )
        assertTrue(reasons(PlaybookObject.PLAYBOOK_INCLUDE, "vars", "~", v221).isEmpty())
        assertEquals(
            listOf("playbook import parameter must be a string indicating a file path, got <class 'int'> instead"),
            reasons(PlaybookObject.PLAYBOOK_INCLUDE, "import_playbook", "5"),
        )
    }

    @Test
    fun `variable names follow each version's rule`() {
        assertEquals(1, reasons(PlaybookObject.TASK, "register", "for").size, "2.18 rejects Python keywords")
        assertTrue(reasons(PlaybookObject.TASK, "register", "for", v221).isEmpty())
        assertTrue(reasons(PlaybookObject.TASK, "register", "\"true\"").isEmpty())
        assertEquals(1, reasons(PlaybookObject.TASK, "register", "\"true\"", v221).size, "2.21 rejects Jinja's names")
        val between = KeywordValidator(CoreSemantics(CoreVersion(2, 20, 0)))
        assertTrue(reasons(PlaybookObject.TASK, "register", "for", between).isEmpty(), "unknown rule: only what both reject")
        assertEquals(1, reasons(PlaybookObject.TASK, "register", "\"bad-name\"", between).size)
        assertEquals(1, reasons(PlaybookObject.TASK, "vars", "{bad-key: 1}").size)
        assertTrue(reasons(PlaybookObject.LOOP_CONTROL, "loop_var", "\"1a\"").isEmpty())
        assertEquals(listOf("Invalid 'loop_var'."), reasons(PlaybookObject.LOOP_CONTROL, "loop_var", "\"1a\"", v221))
    }

    @Test
    fun `2_18 lets OverflowError escape, 2_21 reports it`() {
        val crash = check(PlaybookObject.TASK, "retries", "\"inf\"").single()
        assertTrue(crash.crash)
        assertEquals("OverflowError: cannot convert Infinity to integer", crash.reason)
        assertFalse(check(PlaybookObject.TASK, "retries", "\"inf\"", v221).single().crash)
    }

    @Test
    fun `loaders reject shapes`() {
        assertEquals(1, reasons(PlaybookObject.PLAY, "tasks", "x").size)
        assertTrue(reasons(PlaybookObject.PLAY, "tasks", "~").isEmpty())
        assertEquals(1, reasons(PlaybookObject.BLOCK, "rescue", "~").size)
        assertEquals(1, reasons(PlaybookObject.PLAY, "roles", "x").size)
        assertEquals(listOf("Hosts list cannot be empty. Please check your playbook"), reasons(PlaybookObject.PLAY, "hosts", "\"\""))
        assertEquals(listOf("Hosts list contains an invalid host value: '1'"), reasons(PlaybookObject.PLAY, "hosts", "[1]"))
        assertEquals(1, reasons(PlaybookObject.TASK, "module_defaults", "\"{{ md }}\"").size)
        assertEquals(1, reasons(PlaybookObject.TASK, "args", "x").size)
        assertTrue(reasons(PlaybookObject.TASK, "args", "\"{{ a }}\"").isEmpty())
        assertEquals(listOf("Invalid 'order' specified for inventory hosts: bogus"), reasons(PlaybookObject.PLAY, "order", "bogus"))
        assertEquals(1, reasons(PlaybookObject.TASK, "debugger", "bogus").size)
        assertEquals(1, reasons(PlaybookObject.HANDLER, "listen", "[1]").size)
    }
}
