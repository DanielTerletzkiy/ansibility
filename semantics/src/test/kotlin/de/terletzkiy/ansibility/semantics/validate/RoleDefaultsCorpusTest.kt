package de.terletzkiy.ansibility.semantics.validate

import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.semantics.schema.SpecOrigin
import de.terletzkiy.ansibility.semantics.testutil.YamlText
import de.terletzkiy.ansibility.semantics.yaml.YMap
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Opt-in corpus check (set `ANSIBLE_INFRA_REPO` to the target repo; skipped otherwise). Validates every
 * `golden/roles/<role>/defaults/main.yml` against the role's `main` entry point. The repo is only read.
 */
class RoleDefaultsCorpusTest {
    @Test
    fun `golden role defaults validate without crashes and show the known documented-type findings`() {
        val repo = System.getenv("ANSIBLE_INFRA_REPO")?.let(Path::of)
        assumeTrue(repo != null && repo.resolve("golden/roles").exists(), "ANSIBLE_INFRA_REPO not set")
        val roles = Files.list(repo!!.resolve("golden/roles")).use { stream -> stream.toList() }.sorted()
        val findings = mutableMapOf<String, List<Pair<DiagnosticCode, List<String>>>>()
        for (role in roles) {
            val specFile = role.resolve("meta/argument_specs.yml")
            val defaultsFile = role.resolve("defaults/main.yml")
            if (!specFile.exists() || !defaultsFile.exists()) continue
            val spec = YamlText.parse(specFile.readText()) as? YMap ?: continue
            val options = ((spec["argument_specs"] as? YMap)?.get("main") as? YMap)?.get("options") as? YMap ?: continue
            val defaults = YamlText.parse(defaultsFile.readText()) as? YMap ?: continue
            val validation = SpecValidator().validate(SpecText.options(options, SpecOrigin.RoleSpec(role.name, "main")), defaults)
            assertTrue(validation.result.crash == null, "${role.name}: ${validation.result.crash}")
            findings[role.name] = validation.findings.map { it.code to it.path }
        }
        println(findings.filterValues { it.isNotEmpty() }.entries.joinToString("\n") { (role, list) -> "$role: $list" })
        assertTrue(DiagnosticCode.T011_COERCED_SCALAR_TO_STR to listOf("haproxy_backports_version") in findings["haproxy"].orEmpty())
        assertTrue(DiagnosticCode.T011_COERCED_SCALAR_TO_STR to listOf("grafana_version") in findings["grafana"].orEmpty())
    }

    @Test
    fun `inventory values show the plan's real-shape findings`() {
        val repo = System.getenv("ANSIBLE_INFRA_REPO")?.let(Path::of)
        assumeTrue(repo != null && repo.resolve("repos/heron/ansible").exists(), "ANSIBLE_INFRA_REPO not set")
        val keycloak = inventoryFindings(
            repo!!, "repos/heron/ansible/roles/keycloak", "repos/heron/ansible/environments/prod/group_vars/keycloak/vars.yml",
        )
        val unsupported = keycloak.filter { it.code == DiagnosticCode.T002_UNSUPPORTED_SUB_OPTION }
        println("heron keycloak: ${keycloak.map { it.code to it.path }}")
        assertTrue(unsupported.size == 2 && unsupported.all { it.path.last() == "optional_client_scopes" }, "$unsupported")
        val totp = inventoryFindings(
            repo, "repos/falcon/ansible/roles/totp-token", "repos/falcon/ansible/environments/prod/group_vars/all/vars.yml",
        )
        println("falcon totp-token: ${totp.groupingBy { it.code }.eachCount()}")
        assertTrue(totp.any { it.code == DiagnosticCode.T010_SHAPE_CONTRADICTION && it.path.first() == "totp_users" })
    }

    private fun inventoryFindings(repo: Path, role: String, varsFile: String) = run {
        val spec = YamlText.parse(repo.resolve("$role/meta/argument_specs.yml").readText()) as YMap
        val options = ((spec["argument_specs"] as YMap)["main"] as YMap)["options"] as YMap
        val values = YamlText.parse(repo.resolve(varsFile).readText()) as YMap
        SpecValidator().validate(SpecText.options(options, SpecOrigin.RoleSpec(Path.of(role).name, "main")), values).findings
    }
}
