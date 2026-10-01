package de.terletzkiy.ansibility.semantics.precedence.oracle

import de.terletzkiy.ansibility.semantics.inventory.ProblemSeverity
import de.terletzkiy.ansibility.semantics.precedence.VarLayer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Opt-in corpus check against the real target repository (set `ANSIBLE_INFRA_REPO`; skipped otherwise).
 *
 * Every `repos/<r>/ansible/environments/<env>/hosts.yml` must parse without errors and evaluate for every host.
 * When `ansible-inventory` is available (`ANSIBLE_INVENTORY_BIN` or `PATH`), the engine is also compared with
 * `ansible-inventory -i <env>/hosts.yml --playbook-dir <repo>/ansible --list`, run read-only from a temporary
 * directory with an empty config. Nothing is written into the repository.
 *
 * Run with `ANSIBLE_INFRA_REPO=<repo> ./gradlew :semantics:test --rerun --tests '*InfraCorpusOracleTest*'`:
 * environment variables are not task inputs, so without `--rerun` Gradle may report an up-to-date result.
 */
@EnabledIfEnvironmentVariable(named = "ANSIBLE_INFRA_REPO", matches = ".+")
class InfraCorpusOracleTest {
    private val repo = File(System.getenv("ANSIBLE_INFRA_REPO") ?: "")

    private fun inventories(): List<File> = File(repo, "repos").listFiles().orEmpty().sortedBy { it.name }.flatMap { r ->
        File(r, "ansible/environments").listFiles().orEmpty().sortedBy { it.name }.map { File(it, "hosts.yml") }.filter { it.isFile }
    }

    /** `environments/<env>/hosts.yml` → the `ansible/` playbook directory. */
    private fun playbookDir(inventory: File): File = inventory.parentFile.parentFile.parentFile

    private fun ansibleInventory(): String? {
        System.getenv("ANSIBLE_INVENTORY_BIN")?.let { return it }
        return System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, "ansible-inventory") }.firstOrNull { it.canExecute() }?.path
    }

    @Suppress("UNCHECKED_CAST")
    private fun runAnsibleInventory(binary: String, inventory: File, playbookDir: File): Map<String, Any?> {
        val tmp = Files.createTempDirectory("ansibility-corpus-").toFile()
        try {
            val cfg = File(tmp, "empty.cfg").apply { writeText("") }
            val out = File(tmp, "out.json")
            val err = File(tmp, "err.txt")
            val pb = ProcessBuilder(binary, "-i", inventory.path, "--playbook-dir", playbookDir.path, "--list")
                .directory(tmp)
                .redirectInput(File("/dev/null"))
                .redirectOutput(out)
                .redirectError(err)
            pb.environment().keys.removeIf { it.startsWith("ANSIBLE_") }
            pb.environment()["ANSIBLE_CONFIG"] = cfg.path
            pb.environment()["ANSIBLE_HOME"] = File(tmp, "home").path
            pb.environment()["ANSIBLE_INVENTORY_ENABLED"] = "yaml"
            val process = pb.start()
            check(process.waitFor(180, TimeUnit.SECONDS)) { "ansible-inventory timed out" }
            check(process.exitValue() == 0) { "ansible-inventory failed: ${err.readText()}" }
            return MiniJson.parse(out.readText()) as Map<String, Any?>
        } finally {
            tmp.deleteRecursively()
        }
    }

    @TestFactory
    fun `every environment matches ansible-inventory`(): List<DynamicTest> {
        val inventories = inventories()
        assertTrue(inventories.isNotEmpty(), "no environments/*/hosts.yml under $repo/repos")
        val binary = ansibleInventory()
        return inventories.map { inventory ->
            DynamicTest.dynamicTest(inventory.relativeTo(repo).path) {
                val fixture = InventoryFixture(inventory, playbookDir(inventory))
                val errors = fixture.graph.problems.filter { it.severity == ProblemSeverity.ERROR }
                assertTrue(errors.isEmpty(), "parse errors: $errors")
                fixture.graph.hosts.keys.forEach { fixture.view(it) }
                if (binary != null) {
                    val mismatches = OracleCompare.mismatches(fixture, runAnsibleInventory(binary, inventory, playbookDir(inventory)))
                    assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
                }
            }
        }
    }

    @Test
    fun `falcon prod relay host comes from playbook group_vars all over the environment value`() {
        val inventory = File(repo, "repos/falcon/ansible/environments/prod/hosts.yml")
        assumeTrue(inventory.isFile)
        val fixture = InventoryFixture(inventory, playbookDir(inventory))
        val relay = fixture.view("prod-prod1")["postfix_relayhost"]
        assumeTrue(relay != null)
        assertEquals(VarLayer.PLAYBOOK_GROUP_VARS_ALL, relay!!.winner.source.layer)
        assertTrue(relay.shadowed.any { it.source.layer == VarLayer.INVENTORY_GROUP_VARS_ALL })
    }
}
