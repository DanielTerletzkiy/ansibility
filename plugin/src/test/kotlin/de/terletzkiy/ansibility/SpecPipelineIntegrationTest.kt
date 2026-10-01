package de.terletzkiy.ansibility

import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.semantics.diagnostics.Finding
import de.terletzkiy.ansibility.semantics.schema.ArgSpecParser
import de.terletzkiy.ansibility.semantics.validate.SpecKind
import de.terletzkiy.ansibility.semantics.validate.SpecValidator
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.psi.YAMLFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Integration of WU0.4 (fixture), WU1.1 (PSI adapter), WU1.3 (ArgSpecParser) and WU1.2 (SpecValidator): the
 * pipeline the type-check inspections will run, on the sanitised fixture, must reproduce the plan's real-shape
 * findings with ranges that point into the PSI text.
 */
class SpecPipelineIntegrationTest : BasePlatformTestCase() {
    private fun load(rel: String): Pair<String, YMap> {
        val text = String(Files.readAllBytes(InfraTestData.root.resolve(rel)), StandardCharsets.UTF_8)
        val psi = PsiFileFactory.getInstance(project).createFileFromText("main.yml", YAMLFileType.YML, text) as YAMLFile
        val value = PsiYValueAdapter.documentValue(psi)
        assertTrue("$rel is not a mapping", value is YMap)
        return text to value as YMap
    }

    private fun findings(roleDir: String, roleName: String, valuesFile: String): Pair<String, List<Finding>> {
        val (_, spec) = load("$roleDir/meta/argument_specs.yml")
        val parsed = ArgSpecParser.parse(spec, roleName)
        assertEquals("spec issues in $roleDir", emptyList<Any>(), parsed.issues)
        val options = parsed.entryPoints.getValue("main").options
        val (text, values) = load(valuesFile)
        val validation = SpecValidator(kind = SpecKind.ROLE).validate(options, values)
        assertNull("crash in $valuesFile", validation.result.crash)
        return text to validation.primary
    }

    private fun String.at(finding: Finding): String = finding.range!!.let { substring(it.start, it.end) }

    fun testRoleDefaultsGetTheDocumentedTypeFindings() {
        val (haproxyText, haproxy) = findings("golden/roles/haproxy", "haproxy", "golden/roles/haproxy/defaults/main.yml")
        val backports = haproxy.single { it.path == listOf("haproxy_backports_version") }
        assertEquals(DiagnosticCode.T011_COERCED_SCALAR_TO_STR, backports.code)
        assertEquals("3.2", haproxyText.at(backports))
        assertTrue(backports.fixHints.toString(), "quote" in backports.fixHints)

        val (grafanaText, grafana) = findings("golden/roles/grafana", "grafana", "golden/roles/grafana/defaults/main.yml")
        val version = grafana.single { it.path == listOf("grafana_version") }
        assertEquals(DiagnosticCode.T011_COERCED_SCALAR_TO_STR, version.code)
        assertEquals("12.4", grafanaText.at(version))
    }

    fun testInventoryValuesGetThePlansRealShapeFindings() {
        val (_, keycloak) = findings(
            "repos/heron/ansible/roles/keycloak", "keycloak", "repos/heron/ansible/environments/prod/group_vars/keycloak/vars.yml",
        )
        val unsupported = keycloak.filter { it.code == DiagnosticCode.T002_UNSUPPORTED_SUB_OPTION }
        assertEquals(unsupported.toString(), 2, unsupported.size)
        assertTrue(unsupported.toString(), unsupported.all { it.path.last() == "optional_client_scopes" })

        val (_, totp) = findings(
            "repos/falcon/ansible/roles/totp-token", "totp-token", "repos/falcon/ansible/environments/prod/group_vars/all/vars.yml",
        )
        assertTrue(totp.toString(), totp.any { it.code == DiagnosticCode.T010_SHAPE_CONTRADICTION && it.path.first() == "totp_users" })
    }
}
