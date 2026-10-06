package de.terletzkiy.ansibility.semantics.layout

import de.terletzkiy.ansibility.semantics.layout.LayoutRulesData.Record
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Plan amendment R10, acceptance M5.5 item 1: the 28 `ansible-config dump` records of the research oracle
 * (25 in `cfg-inventory-value-forms`, 2 in `cfg-empty-inventory`, `config-dump` in `cfg-multi-source`) compared by
 * kind on both versions:
 * - the 20 whose `DEFAULT_HOST_LIST` comes from the cfg equal [CfgPathList.inventory] of the value [CfgSyntax]
 *   reads, with `{cwd}` = the cfg's directory; `~` and `$VAR` resolve only with the follow switch and the record's
 *   environment, and are "not followed" without it (D53);
 * - the 2 rc-5 records give the [CfgSyntax] error ansible-core reports (later ANS-L004);
 * - the 2 without the key give no cfg inventory;
 * - the 3 `env: ANSIBLE_INVENTORY` records and the `/etc/ansible/hosts` default are out of scope (D52): the rules
 *   give what the cfg says, never what the environment says.
 */
class CfgDumpOracleTest {
    private enum class Kind { CFG, RC5, KEY_ABSENT, OUT_OF_SCOPE }

    private val cases = listOf("cfg-inventory-value-forms", "cfg-empty-inventory", "cfg-multi-source").map(LayoutRulesData::case)

    private fun records(version: String): List<Record> = cases.flatMap { it.records(version) }

    /** The kind of a record, decided from the record alone. */
    private fun kindOf(record: Record): Kind {
        if (record.rc == 5) return Kind.RC5
        val hostList = record.setting("DEFAULT_HOST_LIST") ?: return Kind.KEY_ABSENT
        return if (record.fromCfg(hostList)) Kind.CFG else Kind.OUT_OF_SCOPE
    }

    @Test
    fun `the records split 20 cfg, 2 rc 5, 2 without the key, 4 out of scope on both versions`() {
        for (version in LayoutRulesData.VERSIONS) {
            val byKind = records(version).groupBy(::kindOf).mapValues { (_, rs) -> rs.size }
            assertEquals(28, records(version).size, version)
            assertEquals(mapOf(Kind.CFG to 20, Kind.RC5 to 2, Kind.KEY_ABSENT to 2, Kind.OUT_OF_SCOPE to 4), byKind, version)
        }
    }

    @TestFactory
    fun `every dump record by kind`(): List<DynamicContainer> = LayoutRulesData.VERSIONS.map { version ->
        DynamicContainer.dynamicContainer(version, records(version).map { record ->
            DynamicTest.dynamicTest("${record.case.name}/${record.run}") {
                when (kindOf(record)) {
                    Kind.CFG -> checkCfg(record)
                    Kind.RC5 -> checkRc5(record)
                    Kind.KEY_ABSENT -> checkKeyAbsent(record)
                    Kind.OUT_OF_SCOPE -> checkOutOfScope(record)
                }
            }
        })
    }

    @Test
    fun `only the tilde and the environment variable spellings depend on the machine`() {
        for (version in LayoutRulesData.VERSIONS) {
            val dependent = records(version).filter { kindOf(it) == Kind.CFG }
                .filter { record -> inventory(record, follow = false).any { !it.isFollowed } }
                .map { it.run }
            assertEquals(listOf("dump-tilde", "dump-envvar"), dependent, version)
        }
    }

    private fun inventory(record: Record, follow: Boolean): List<CfgPath> {
        val read = CfgSyntax.read(record.cfgText())
        assertTrue(read.isValid, "$record: ${read.errors}")
        val value = read.document.value(CfgPathList.SECTION, CfgPathList.INVENTORY) ?: error("$record: no inventory")
        val context = CfgPathContext(record.cfgDir, LayoutRulesData.PROJECT, if (follow) record.followEnvironment() else null)
        return CfgPathList.inventory(value, context)
    }

    private fun checkCfg(record: Record) {
        val expected = record.setting("DEFAULT_HOST_LIST")!!.strings.map(record::absolute)
        val unfollowed = inventory(record, follow = false)
        if (unfollowed.all { it.isFollowed }) {
            assertEquals(expected, unfollowed.map { it.path }, "$record")
        } else {
            for (entry in unfollowed.filter { !it.isFollowed }) {
                assertNull(entry.path, "$record: ${entry.text} has no path while not followed")
                assertTrue(entry.dependsOn.any { it == MachineDependence.HOME || it == MachineDependence.ENVIRONMENT }, "$record: $entry")
            }
            val followed = inventory(record, follow = true)
            assertTrue(followed.all { it.isFollowed }, "$record: $followed")
            assertEquals(expected, followed.map { it.path }, "$record (followed)")
        }
    }

    private fun checkRc5(record: Record) {
        val read = CfgSyntax.read(record.cfgText())
        val reported = read.abortsWith ?: error("$record: ansible-core refuses the file")
        assertEquals(LayoutRulesData.reportedKind(record.stderr), reported.kind, "$record")
        assertEquals(LayoutRulesData.reportedLines(record.stderr).first(), reported.line, "$record: line")
    }

    private fun checkKeyAbsent(record: Record) {
        val read = CfgSyntax.read(record.cfgText())
        assertTrue(read.isValid, "$record: ${read.errors}")
        assertNull(read.document.value(CfgPathList.SECTION, CfgPathList.INVENTORY), "$record: no cfg inventory")
    }

    private fun checkOutOfScope(record: Record) {
        val origin = record.setting("DEFAULT_HOST_LIST")!!.origin
        assertTrue(origin == "env: ANSIBLE_INVENTORY" || origin == "default", "$record: $origin")
        if (origin == "default") {
            assertNull(record.cfg, "$record: the /etc/ansible/hosts default applies only without a cfg")
            return
        }
        assertTrue("ANSIBLE_INVENTORY" in record.env, "$record")
        if (record.cfg == null) return
        // The rules give the cfg's own value, never what ANSIBLE_INVENTORY replaced it with.
        val ours = inventory(record, follow = false).map { it.path }
        val cfgOnly = record.case.record("dump-list", record.version)
        assertEquals(cfgOnly.setting("DEFAULT_HOST_LIST")!!.strings.map(cfgOnly::absolute), ours, "$record")
        assertNotEquals(record.setting("DEFAULT_HOST_LIST")!!.strings.map(record::absolute), ours, "$record")
    }

    @Test
    fun `the cfg values carry the hints behind ANS-L002 and ANS-L003`() {
        val record = { run: String -> LayoutRulesData.case("cfg-inventory-value-forms").record(run, "2.21.4") }
        fun hints(run: String) = inventory(record(run), follow = false).map { it.hints }

        assertEquals(listOf(setOf(EntryHint.CFG_DIR)), hints("dump-empty"))
        assertEquals(listOf(setOf(EntryHint.HOST_LIST), setOf(EntryHint.CFG_DIR)), hints("dump-trailing-comma"))
        assertEquals(listOf(setOf(EntryHint.HOST_LIST), setOf(EntryHint.HOST_LIST)), hints("dump-host-list"))
        assertEquals(listOf(setOf(EntryHint.HASH_COMMENT)), hints("dump-hash-comment"))
        assertEquals(listOf(setOf(EntryHint.QUOTED)), hints("dump-quoted"))
        assertEquals(listOf(emptySet<EntryHint>(), emptySet()), hints("dump-list"))
        assertEquals(listOf(emptySet<EntryHint>()), hints("dump-semicolon-comment"))
        assertEquals(10, inventory(record("dump-hash-comment"), follow = false).single().commentStart)
        assertFalse(inventory(record("dump-dotdot"), follow = false).single().dependsOn.isNotEmpty(), "../ inside the project is followed")
    }
}
