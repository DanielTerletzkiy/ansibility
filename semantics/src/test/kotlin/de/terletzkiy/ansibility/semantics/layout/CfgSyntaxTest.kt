package de.terletzkiy.ansibility.semantics.layout

import de.terletzkiy.ansibility.semantics.layout.LayoutRulesData.Record
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * [CfgSyntax] against `probe-cfg-syntax` (34 cfg files beyond the research's three rc-5 kinds, measured on 2.18.8
 * and 2.21.4 with identical results): every refused file gives the kind and the line ansible-core names, every
 * accepted one has no error and gives the values `ansible-config dump` shows.
 */
class CfgSyntaxTest {
    private val probe = LayoutRulesData.case("probe-cfg-syntax")

    /** The dump settings the probe checks, with the `(section, key)` they come from. */
    private val keys = mapOf(
        "DEFAULT_HOST_LIST" to ("defaults" to "inventory"),
        "DEFAULT_FORKS" to ("defaults" to "forks"),
        "DEFAULT_TIMEOUT" to ("defaults" to "timeout"),
    )

    @TestFactory
    fun `probe records`(): List<DynamicContainer> = LayoutRulesData.VERSIONS.map { version ->
        DynamicContainer.dynamicContainer(version, probe.records(version).map { record ->
            DynamicTest.dynamicTest(record.run) { check(record) }
        })
    }

    private fun check(record: Record) {
        val fileError = CfgSyntax.checkFileName(record.cfgFile().name)
        if (record.rc == 5 && LayoutRulesData.reportedKind(record.stderr).let {
                it == CfgErrorKind.UNSUPPORTED_EXTENSION || it == CfgErrorKind.UNSUPPORTED_TYPE
            }) {
            assertEquals(LayoutRulesData.reportedKind(record.stderr), fileError?.kind, "$record")
            return
        }
        assertNull(fileError, "$record: ${record.cfgFile().name} is a supported name")
        val read = CfgSyntax.read(record.cfgText())
        if (record.rc == 5) {
            val kind = LayoutRulesData.reportedKind(record.stderr)
            val reported = read.abortsWith ?: error("$record: no error, ansible-core says ${record.stderr.lineSequence().first()}")
            assertEquals(kind, reported.kind, "$record")
            val lines = LayoutRulesData.reportedLines(record.stderr)
            if (kind == CfgErrorKind.PARSING_ERROR) {
                assertEquals(lines, read.errors.filter { it.kind == CfgErrorKind.PARSING_ERROR }.map { it.line }, "$record: every bad line")
            } else {
                assertEquals(lines.first(), reported.line, "$record: line")
            }
            return
        }
        assertTrue(read.isValid, "$record: ${read.errors}")
        for ((name, at) in keys) {
            val setting = record.setting(name) ?: continue
            val value = read.document.value(at.first, at.second)
            if (!record.fromCfg(setting)) {
                assertNull(value, "$record: $name is not set by the cfg")
                continue
            }
            if (name == "DEFAULT_HOST_LIST") {
                val paths = CfgPathList.inventory(value ?: error("$record: no inventory"), CfgPathContext(record.cfgDir))
                assertEquals(setting.strings.map(record::absolute), paths.map { it.path }, "$record: $name")
            } else {
                assertEquals((setting.value as Number).toInt(), value?.toInt(), "$record: $name")
            }
        }
    }

    @Test
    fun `values keep a hash, lose an inline semicolon comment and join continuation lines`() {
        val doc = CfgSyntax.read(
            """
            |# comment
            |; comment
            |[defaults]
            |inventory = hosts.ini, ; local
            |  extra.yml
            |  # a comment line inside the value
            |
            |  last.yml
            |roles_path: roles # kept
            |INVENTORY_IGNORE_EXTENSIONS = .md;not-a-comment
            |""".trimMargin(),
        ).also { assertTrue(it.isValid, "${it.errors}") }.document

        assertEquals(CfgEntry("defaults", "inventory", "hosts.ini,\nextra.yml\n\nlast.yml", 4, 8), doc.entry("defaults", "inventory"))
        assertEquals("roles # kept", doc.value("defaults", "ROLES_PATH"))
        assertEquals(".md;not-a-comment", doc.value("defaults", "inventory_ignore_extensions"))
    }

    @Test
    fun `continuation needs deeper indentation than the key line`() {
        val doc = CfgSyntax.read("[defaults]\n  inventory = a.ini\n  forks = 3\n    b.ini\n").document
        assertEquals("a.ini", doc.value("defaults", "inventory"))
        assertEquals("3\nb.ini", doc.value("defaults", "forks"))
    }

    @Test
    fun `a blank line ends nothing but a trailing one is stripped`() {
        val doc = CfgSyntax.read("[defaults]\ninventory = a.ini\n\n\n[inventory]\nenable_plugins = ini\n").document
        assertEquals("a.ini", doc.value("defaults", "inventory"))
        assertEquals(CfgEntry("defaults", "inventory", "a.ini", 2, 2), doc.entry("defaults", "inventory"))
        assertEquals("ini", doc.value("inventory", "enable_plugins"))
    }

    @Test
    fun `sections are case-sensitive and DEFAULT is inherited only by existing sections`() {
        val doc = CfgSyntax.read("[DEFAULT]\ninventory = d.ini\nforks = 9\n[defaults]\nforks = 3\n[DEFAULTS]\ninventory = x\n").document
        assertEquals("d.ini", doc.value("defaults", "inventory"))
        assertEquals("3", doc.value("defaults", "forks"))
        assertEquals("x", doc.value("DEFAULTS", "inventory"))
        assertNull(doc.value("inventory", "forks"), "no [inventory] section, so nothing is inherited")
        assertEquals(listOf("defaults", "DEFAULTS"), doc.sectionNames)
        assertEquals(listOf("inventory", "forks"), doc.entries(CfgSyntax.DEFAULT_SECTION).map { it.key })
        assertEquals(listOf("forks"), doc.entries("defaults").map { it.key })
    }

    @Test
    fun `header forms`() {
        fun section(text: String) = CfgSyntax.read("$text\nk = v\n").document.sectionNames
        assertEquals(listOf("defaults"), section("[defaults] trailing text"))
        assertEquals(listOf("defaults"), section("[defaults] # c"))
        assertEquals(listOf("defaults"), section("[defaults] ; c"))
        assertEquals(listOf(" defaults "), section("[ defaults ]"))
        assertEquals(listOf("a]b"), section("[a]b]"))
        assertEquals(listOf("defaults"), section("   [defaults]"))
        val empty = CfgSyntax.read("[defaults]\n[]\n")
        assertEquals(listOf(CfgSyntaxError(CfgErrorKind.PARSING_ERROR, 2, section = "defaults")), empty.errors)
    }

    @Test
    fun `keys split at the first delimiter and are lower-cased`() {
        val doc = CfgSyntax.read("[defaults]\ninventory:x = hosts.ini\nA b = c=d\nRoles_Path   =   r  \n").document
        assertEquals("x = hosts.ini", doc.value("defaults", "inventory"))
        assertEquals("c=d", doc.value("defaults", "a b"))
        assertEquals("r", doc.value("defaults", "roles_path"))
    }

    @Test
    fun `every error is listed in line order while the document stays usable`() {
        val read = CfgSyntax.read(
            """
            |inventory = early.ini
            |  still before the header
            |[defaults]
            |bogus line
            |inventory = a.ini
            |INVENTORY = b.ini
            |= orphan
            |[defaults]
            |forks = 3
            |""".trimMargin(),
        )
        assertEquals(
            listOf(
                CfgSyntaxError(CfgErrorKind.MISSING_SECTION_HEADER, 1),
                CfgSyntaxError(CfgErrorKind.PARSING_ERROR, 4, section = "defaults"),
                CfgSyntaxError(CfgErrorKind.DUPLICATE_OPTION, 6, "defaults", "inventory", previousLine = 5),
                CfgSyntaxError(CfgErrorKind.PARSING_ERROR, 7, section = "defaults"),
                CfgSyntaxError(CfgErrorKind.DUPLICATE_SECTION, 8, section = "defaults", previousLine = 3),
            ),
            read.errors,
        )
        assertFalse(read.isValid)
        assertEquals(CfgErrorKind.MISSING_SECTION_HEADER, read.abortsWith?.kind)
        assertEquals("b.ini", read.document.value("defaults", "inventory"), "the later duplicate wins, as without strict mode")
        assertEquals("3", read.document.value("defaults", "forks"), "a repeated section merges")
    }

    @Test
    fun `ansible-core reports a duplicate before an earlier parsing error`() {
        val read = CfgSyntax.read("[defaults]\nbogus line\ninventory = a.ini\ninventory = b.ini\n")
        assertEquals(CfgSyntaxError(CfgErrorKind.DUPLICATE_OPTION, 4, "defaults", "inventory", 3), read.abortsWith)
        assertEquals(CfgSyntaxError(CfgErrorKind.PARSING_ERROR, 2, section = "defaults"), CfgSyntax.read("[defaults]\nbogus\n").abortsWith)
    }

    @Test
    fun `DEFAULT may repeat but its keys may not`() {
        assertTrue(CfgSyntax.read("[DEFAULT]\nforks = 7\n[DEFAULT]\ntimeout = 9\n").isValid)
        assertEquals(
            listOf(CfgSyntaxError(CfgErrorKind.DUPLICATE_OPTION, 4, "DEFAULT", "forks", 2)),
            CfgSyntax.read("[DEFAULT]\nforks = 7\n[DEFAULT]\nforks = 9\n").errors,
        )
    }

    @Test
    fun `a parse error keeps the previous key open for deeper lines`() {
        val doc = CfgSyntax.read("[defaults]\ninventory = a.ini,\nbogus\n  b.ini\n").document
        assertEquals("a.ini,\nb.ini", doc.value("defaults", "inventory"))
    }

    @Test
    fun `a byte order mark hides the first header, CRLF line ends do not matter`() {
        assertEquals(CfgErrorKind.MISSING_SECTION_HEADER, CfgSyntax.read("﻿[defaults]\ninventory = h\n").abortsWith?.kind)
        val crlf = CfgSyntax.read("[defaults]\r\ninventory = hosts.ini\r\n  extra\r\n")
        assertTrue(crlf.isValid)
        assertEquals("hosts.ini\nextra", crlf.document.value("defaults", "inventory"))
    }

    @Test
    fun `an empty or comment-only text is a valid empty document`() {
        for (text in listOf("", "\n", "# only\n; comments\n\n")) {
            val read = CfgSyntax.read(text)
            assertTrue(read.isValid, text)
            assertTrue(read.document.sectionNames.isEmpty(), text)
        }
    }

    @Test
    fun `file names`() {
        assertNull(CfgSyntax.checkFileName("ansible.cfg"))
        assertNull(CfgSyntax.checkFileName("/p/conf/settings.ini"))
        assertEquals(CfgErrorKind.UNSUPPORTED_TYPE, CfgSyntax.checkFileName("ansible.yml")?.kind)
        assertEquals(CfgErrorKind.UNSUPPORTED_TYPE, CfgSyntax.checkFileName("ansible.yaml")?.kind)
        for (name in listOf("ansible.conf", "ansible", "ansible.CFG", ".cfg", "/p/a.cfg/ansible")) {
            assertEquals(CfgErrorKind.UNSUPPORTED_EXTENSION, CfgSyntax.checkFileName(name)?.kind, name)
        }
    }
}
