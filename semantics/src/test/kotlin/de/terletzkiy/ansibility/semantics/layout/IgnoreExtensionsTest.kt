package de.terletzkiy.ansibility.semantics.layout

import de.terletzkiy.ansibility.semantics.CoreVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * D62's two measured default lists (the full `ansible-config dump` of each version, `dump-no-cfg-default`), the
 * provisional cut-over, explicit lists and their effect on a directory walk (`probe-ignore-walk`).
 */
class IgnoreExtensionsTest {
    @Test
    fun `the defaults are the measured ones of each version`() {
        val case = LayoutRulesData.case("cfg-inventory-value-forms")
        for (version in LayoutRulesData.VERSIONS) {
            val record = case.record("dump-no-cfg-default", version)
            val setting = record.setting("INVENTORY_IGNORE_EXTS")!!
            assertEquals("default", setting.origin)
            assertEquals(setting.strings, IgnoreExtensions.defaultFor(record.core), version)
        }
        assertEquals(IgnoreExtensions.SKIPS_INI, IgnoreExtensions.READS_INI.toMutableList().apply { add(indexOf(".cfg"), ".ini") })
    }

    @Test
    fun `targets below 2_19 skip ini files, provisionally`() {
        assertTrue(".ini" in IgnoreExtensions.defaultFor(CoreVersion(2, 18, 99)))
        assertTrue(".ini" in IgnoreExtensions.defaultFor(CoreVersion(2, 17, 0)))
        assertFalse(".ini" in IgnoreExtensions.defaultFor(CoreVersion(2, 19, 0)))
        assertFalse(".ini" in IgnoreExtensions.defaultFor(CoreVersion(2, 21, 4)))
        assertEquals(CoreVersion(2, 19), IgnoreExtensions.INI_READ_SINCE)
    }

    @Test
    fun `an explicit list replaces the default and the inventory section wins`() {
        val old = CoreVersion(2, 18, 8)
        assertEquals(listOf(".md"), IgnoreExtensions.resolve(".yml", ".md", old))
        assertEquals(listOf(".yml"), IgnoreExtensions.resolve(".yml", null, old))
        assertEquals(listOf(".txt", ".ini"), IgnoreExtensions.resolve(null, ".txt,.ini", CoreVersion(2, 21, 4)))
        assertEquals(listOf(".yml", ".ini", ".bak"), IgnoreExtensions.resolve("\".yml\", '.ini' , .bak", null, old))
        assertEquals(listOf(""), IgnoreExtensions.resolve("", null, old))
        assertEquals(IgnoreExtensions.SKIPS_INI, IgnoreExtensions.resolve(null, null, old))
    }

    @Test
    fun `cfg lists split at commas, strip and unquote`() {
        assertEquals(listOf("host_list", "ini", "yaml"), CfgValues.list(" host_list ,ini,  'yaml' "))
        assertEquals(listOf(""), CfgValues.list(""))
        assertEquals(listOf("", ""), CfgValues.list(","))
        assertEquals(listOf("\"a"), CfgValues.list("\"a"), "an unbalanced quote stays")
    }

    @Test
    fun `only the final suffix counts and an empty entry ignores everything`() {
        val defaults = IgnoreExtensions.defaultFor(CoreVersion(2, 18, 8))
        assertTrue(IgnoreExtensions.isIgnored("10-static.ini", defaults))
        assertTrue(IgnoreExtensions.isIgnored("hosts.yml~", defaults))
        assertFalse(IgnoreExtensions.isIgnored("x.ini.disabled", defaults))
        assertFalse(IgnoreExtensions.isIgnored("hosts.j2", defaults))
        assertFalse(IgnoreExtensions.isIgnored("hosts.INI", defaults), "matched as written")
        assertTrue(IgnoreExtensions.isIgnored("anything", listOf("")))
    }

    @Test
    fun `the walk reads what ansible-inventory read`() {
        val case = LayoutRulesData.case("probe-ignore-walk")
        val hostOf = mapOf("a.yml" to "yaml1", "b.ini" to "ini1", "c.txt" to "txt1")
        for (version in LayoutRulesData.VERSIONS) {
            for (record in case.records(version, tool = "ansible-inventory")) {
                val doc = CfgSyntax.read(record.cfgText()).document
                val extensions = IgnoreExtensions.resolve(
                    doc.value("defaults", IgnoreExtensions.DEFAULTS_KEY),
                    doc.value(IgnoreExtensions.INVENTORY_SECTION, IgnoreExtensions.INVENTORY_KEY),
                    record.core,
                )
                val read = hostOf.filterKeys { !IgnoreExtensions.isIgnored(it, extensions) }.values.sorted()
                @Suppress("UNCHECKED_CAST")
                val ungrouped = (record.stdout as Map<String, Any?>)["ungrouped"] as Map<String, Any?>?
                @Suppress("UNCHECKED_CAST")
                val listed = (ungrouped?.get("hosts") as List<String>?).orEmpty()
                assertEquals(listed.sorted(), read, "$record")
            }
        }
    }
}
