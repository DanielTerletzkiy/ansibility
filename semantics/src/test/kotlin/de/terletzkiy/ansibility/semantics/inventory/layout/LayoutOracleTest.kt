package de.terletzkiy.ansibility.semantics.inventory.layout

import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.inventory.FileStatus
import de.terletzkiy.ansibility.semantics.inventory.InventoryFileFormat
import de.terletzkiy.ansibility.semantics.inventory.ProblemSeverity
import de.terletzkiy.ansibility.semantics.precedence.VarLayer
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.nio.file.Files

/**
 * The parser side of the layout oracle (plan amendment R10, acceptance M5.5 item 1): for the 16 cases under
 * `layout-oracle/`, every `ansible-inventory` record of every ansible-core version recorded there (2.18.8 and
 * 2.21.4, plus the 2.18.19 and 2.19.0 records of spike S-L1) must come out of [InventorySources] and the precedence
 * engine exactly as the real tool printed it:
 *
 * - `--host` and `--list` records: the group and host structure and every host's variables;
 * - `--list --export` records: the group and host structure only (`--export` folds the layers together);
 * - `--graph` records: the printed tree, character for character.
 *
 * The `ansible-config dump` records are the layout rules' (R10-2, `CfgPathList`/`CfgSyntax`): they are counted
 * here, not evaluated. The runs' own `ansible.cfg` values are read by the test-only [OracleCfg].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LayoutOracleTest {
    private val work: File = Files.createTempDirectory("ansibility-layout-oracle-").toFile()
    private val neutralCwd = File(work, "cwd").apply { mkdirs() }

    @AfterAll
    fun cleanUp() {
        work.deleteRecursively()
    }

    private fun run(case: String, version: String, name: String): OracleRun {
        val caseDir = File(LayoutOracleCases.root, case)
        val record = LayoutOracleCases.records(File(caseDir, "expected/$version")).single { it.first == name }.second
        return OracleRun(caseDir, record, neutralCwd)
    }

    @TestFactory
    fun `every ansible-inventory record matches the parser and the engine`(): List<DynamicContainer> =
        LayoutOracleCases.cases().map { case ->
            DynamicContainer.dynamicContainer(
                case.name,
                LayoutOracleCases.versions(case).flatMap { version ->
                    LayoutOracleCases.records(version).filter { it.second["tool"] == "ansible-inventory" }.map { (name, record) ->
                        DynamicTest.dynamicTest("${version.name} $name") {
                            val mismatches = OracleRun(case, record, neutralCwd).mismatches()
                            assertTrue(mismatches.isEmpty(), "${case.name} ${version.name} $name:\n" + mismatches.joinToString("\n"))
                        }
                    }
                },
            )
        }

    @Test
    fun `the oracle has 16 cases with 116 runs per measured version and the dump records belong to the layout rules`() {
        val cases = LayoutOracleCases.cases()
        assertEquals(16, cases.size, cases.map { it.name }.toString())
        for (version in listOf("2.18.8", "2.21.4")) {
            val records = cases.flatMap { case -> LayoutOracleCases.records(File(case, "expected/$version")) }
            assertEquals(116, records.size, version)
            val dumps = records.filter { it.second["tool"] == "ansible-config" }
            // 25 in cfg-inventory-value-forms, 2 in cfg-empty-inventory, config-dump in cfg-multi-source.
            assertEquals(28, dumps.size, version)
            assertTrue(dumps.all { (name, _) -> name.startsWith("dump-") || name == "config-dump" })
        }
        // Spike S-L1: the version-dependent cases were also recorded on the last 2.18 and the first 2.19 release.
        for (version in listOf("2.18.19", "2.19.0")) {
            val recorded = cases.filter { File(it, "expected/$version").isDirectory }.map { it.name }
            assertEquals(listOf("cfg-empty-inventory", "inventory-directory"), recorded, version)
        }
    }

    @Test
    fun `the 31 ini-grammar-edges runs accept or reject exactly like ini py`() {
        val case = File(LayoutOracleCases.root, "ini-grammar-edges")
        for (version in listOf("2.18.8", "2.21.4")) {
            val records = LayoutOracleCases.records(File(case, "expected/$version"))
            assertEquals(31, records.size)
            for ((name, record) in records) {
                val run = OracleRun(case, record, neutralCwd)
                val files = run.parse.files
                when {
                    name.startsWith("error-") -> {
                        assertEquals(listOf(FileStatus.FAILED), files.map { it.status }, name)
                        assertTrue(run.graph.problems.any { it.severity == ProblemSeverity.ERROR }, name)
                        assertTrue(files.single().failures.all { it.line != null || it.plugin != "ini" }, name)
                    }
                    name.startsWith("accepted-") -> assertEquals(listOf(FileStatus.PARSED), files.map { it.status }, name)
                    name == "partial-alone" -> assertEquals(listOf(FileStatus.FAILED), files.map { it.status }, name)
                    name == "partial-then-good" -> assertEquals(listOf(FileStatus.FAILED, FileStatus.PARSED), files.map { it.status }, name)
                }
                assertTrue(run.mismatches().isEmpty(), name)
            }
        }
    }

    @Test
    fun `a source that fails half-way leaks its groups only once another source parses`() {
        val alone = run("ini-grammar-edges", "2.21.4", "partial-alone")
        assertEquals(setOf("all", "ungrouped"), alone.graph.groups.keys)
        assertEquals(emptySet<String>(), alone.graph.hosts.keys)

        val withGood = run("ini-grammar-edges", "2.21.4", "partial-then-good")
        assertEquals(listOf("ungrouped", "early", "demo"), withGood.graph.group("all")!!.children)
        assertEquals("yes", withGood.view("early1")["kept"]!!.value.let { (it as YScalar).text })
        val failure = withGood.parse.files[0].failures.single()
        assertEquals("ini", failure.plugin)
        assertEquals(3, failure.line) // `[bad section]`
    }

    @Test
    fun `cfg-empty-inventory and inventory-directory differ per version exactly at 2_19`() {
        for ((version, readsIni) in listOf("2.18.8" to false, "2.18.19" to false, "2.19.0" to true, "2.21.4" to true)) {
            val dir = run("inventory-directory", version, "list")
            val names = dir.parse.files.map { it.file!!.name }
            assertEquals(readsIni, "10-static.ini" in names, version)
            assertEquals(readsIni, dir.graph.host("ini-ext") != null, version)
            // dup: the later file (50-dup.yml) wins over 05-dup.ini wherever both are read.
            assertEquals("from-50-dup.yml", (dir.view("dup")["v"]!!.value as YScalar).text)

            val root = run("cfg-empty-inventory", version, "graph-empty")
            assertEquals(readsIni, root.graph.host("from-hosts-ini") != null, version)
        }
    }

    @Test
    fun `flat-ini-basic web-a gets the documented effective values and layers`() {
        val run = run("flat-ini-basic", "2.21.4", "host-web-a")
        val view = run.view("web-a")
        fun text(name: String) = (view[name]!!.value as YScalar).text
        assertEquals("dc1-from-group_vars", text("datacenter"))
        assertEquals("8080", text("http_port"))
        assertEquals("ntp1.example.test", text("ntp_server"))
        assertEquals("base", text("tier"))
        val port = view["http_port"]!!
        assertEquals(VarLayer.INVENTORY_FILE_HOST, port.winner.source.layer)
        // group_vars/web.yml loads twice (inventory and play stage, the same directory), [web:vars] lowest.
        assertEquals(
            listOf(VarLayer.PLAYBOOK_GROUP_VARS, VarLayer.INVENTORY_GROUP_VARS, VarLayer.INVENTORY_FILE_GROUP),
            port.shadowed.map { it.source.layer },
        )
        val tier = view["tier"]!!
        assertEquals(VarLayer.PLAYBOOK_GROUP_VARS_ALL, tier.winner.source.layer)
        assertEquals(VarLayer.INVENTORY_FILE_GROUP, tier.shadowed.last().source.layer)
        assertEquals(InventoryFileFormat.INI, run.parse.files.single().format)
    }

    @Test
    fun `the ini port of db1 applies only where the host is created`() {
        val run = run("flat-ini-basic", "2.18.8", "host-db1")
        val port = run.view("db1")["ansible_port"]!!
        assertEquals("2222", (port.value as YScalar).text)
        assertEquals(1, run.graph.host("db1")!!.varSections.count { "ansible_port" in it.entries })
    }

    @Test
    fun `the comparison detects a wrong target version, precedence and record`() {
        fun mismatches(case: String, version: String, name: String, change: (MutableMap<String, Any?>) -> Unit): List<String> {
            val caseDir = File(LayoutOracleCases.root, case)
            val record = LayoutOracleCases.records(File(caseDir, "expected/$version")).single { it.first == name }.second
            return OracleRun(caseDir, record.toMutableMap().also(change), neutralCwd).mismatches()
        }
        // 2.21.4's directory walk evaluated with 2.18.8's ignore list loses the .ini hosts.
        assertTrue(mismatches("inventory-directory", "2.21.4", "graph") { it["ansible_core"] = "2.18.8" }.isNotEmpty())
        // Without the custom precedence the double-loaded group_vars/all.yml no longer wins.
        assertTrue(mismatches("same-dir-double-load", "2.21.4", "host-custom-precedence") { it["env"] = emptyMap<String, String>() }.isNotEmpty())
        // A host-line value typed as the :vars line would type it.
        assertTrue(mismatches("flat-ini-typing", "2.21.4", "host-typed") { r ->
            @Suppress("UNCHECKED_CAST")
            r["stdout"] = (r["stdout"] as Map<String, Any?>) + ("i_dq" to "5")
        }.isNotEmpty())
    }

    @Test
    fun `typing records hold on both versions`() {
        for (version in listOf("2.18.8", "2.21.4")) {
            val typed = run("flat-ini-typing", version, "host-typed")
            assertTrue(typed.mismatches().isEmpty())
            assertEquals(CoreVersion.parse(version), typed.core)
            assertTrue(run("flat-ini-typing", version, "host-h1").mismatches().isEmpty())
        }
    }
}
