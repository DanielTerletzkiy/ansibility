package de.terletzkiy.ansibility.lint

import de.terletzkiy.ansibility.fixtures.InfraTestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** [LintSarif] on ansible-lint 25.9.2's SARIF of a synthetic role (`testData/lint/basic.sarif`, SRCROOT `/project`). */
class LintSarifTest {
    private val sarif = Files.readString(InfraTestData.testDataPath.resolve("lint/basic.sarif"))

    @Test
    fun findingsWithRulesLevelsLinesAndHostPaths() {
        val findings = LintSarif.parse(sarif) { Path.of(it) }
        assertEquals(8, findings.size)
        val casing = findings.single { it.rule == "name[casing]" }
        assertEquals(Path.of("/project/roles/web/tasks/main.yml"), casing.file)
        assertEquals(1, casing.line)
        assertEquals(9, casing.column)
        assertEquals(LintLevel.ERROR, casing.level)
        assertEquals("All names should start with an uppercase letter.", casing.description)
        assertEquals("https://ansible.readthedocs.io/projects/lint/rules/name/", casing.url)
        assertEquals("name", casing.baseRule)
        assertTrue("name is a transform rule", casing.autofix)
        assertFalse(findings.first { it.rule == "no-changed-when" }.autofix)
        assertEquals(listOf(2, 3), findings.filter { it.rule == "fqcn[action-core]" }.map { it.line })
    }

    @Test
    fun textAroundTheDocumentIsIgnoredAndUnresolvedPathsDropped() {
        val wrapped = "WARNING something\n$sarif\nansible-lint: no violations (profile: production)\n"
        assertEquals(8, LintSarif.parse(wrapped) { Path.of(it) }.size)
        assertTrue(LintSarif.parse(sarif) { null }.isEmpty())
    }

    @Test
    fun containerPathsGoThroughTheResolver() {
        val inContainer = sarif.replace("file:///project/", "file:///ansible/")
        val findings = LintSarif.parse(inContainer) { path -> if (path.startsWith("/ansible/")) Path.of("/host/repo/ansible", path.removePrefix("/ansible/")) else null }
        assertEquals(Path.of("/host/repo/ansible/roles/web/tasks/main.yml"), findings.first().file)
    }

    @Test(expected = LintOutputException::class)
    fun noDocumentIsAnError() {
        LintSarif.parse("ERROR: Couldn't parse task at roles/x.yml") { Path.of(it) }
    }
}
