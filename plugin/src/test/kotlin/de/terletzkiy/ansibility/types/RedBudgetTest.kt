package de.terletzkiy.ansibility.types

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.semantics.diagnostics.Preset
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.RootKeys
import java.nio.file.Files

/**
 * The fixture red budget (plan A.6, M4 E5): all eleven type inspections over every YAML file of the infra fixture
 * report exactly the ERRORs listed in `testData/types/red-budget.txt`, each a reviewed, genuine contradiction. Under
 * Runtime-faithful only the rejections stay red. The opt-in run against the real repo (`ANSIBLE_INFRA_REPO`) prints
 * the counts per code and checks the reviewed corpus baseline `testData/types/red-budget-corpus.txt`.
 */
class RedBudgetTest : TypeCheckTestCase() {

    fun testFixtureErrorsMatchTheReviewedBaseline() {
        val base = copyWholeFixture()
        val all = sweep(base)
        val errors = all.filter { it.endsWith(" ${ProblemHighlightType.GENERIC_ERROR.name}") }.map { it.removeSuffix(" ${ProblemHighlightType.GENERIC_ERROR.name}") }.sorted()
        println("Type inspections on the fixture: ${countsByCode(all)}")
        assertEquals(baseline(), errors)
        assertEquals(
            "the weak warnings (ANS-T015): nulls for optional options without a spec default",
            listOf(
                "golden/roles/chronod/defaults/main.yml:161 AnsibleNullForOptional",
                "golden/roles/chronod/defaults/main.yml:163 AnsibleNullForOptional",
                "golden/roles/chronod/defaults/main.yml:164 AnsibleNullForOptional",
                "golden/roles/grafana/defaults/main.yml:71 AnsibleNullForOptional",
                "repos/falcon/ansible/roles/grafana/defaults/main.yml:69 AnsibleNullForOptional",
            ),
            all.filterNot { it.endsWith(" ${ProblemHighlightType.GENERIC_ERROR.name}") }.map { it.substringBeforeLast(" ") },
        )
    }

    fun testRuntimeFaithfulKeepsOnlyRejectionsRed() {
        val base = copyWholeFixture()
        val settings = AnsibilityProjectSettings.getInstance(project)
        for (root in runReadActionBlocking { AnsibleWorkspace.getInstance(project).roots() }) {
            settings.updateRoot(RootKeys.keyOf(project, root.dir)) { it.copy(preset = Preset.RUNTIME_FAITHFUL) }
        }
        val errors = sweep(base, setOf(ProblemHighlightType.GENERIC_ERROR)).map { it.substringBeforeLast(" ") }
        assertEquals(baseline().filter { "AnsibleUnsupportedSubOption" in it || "AnsibleNullForTypedOption" in it }, errors)
    }

    fun testWholeRealRepo() {
        val repo = System.getenv(InfraTestData.INFRA_REPO_ENV)?.let { LocalFileSystem.getInstance().refreshAndFindFileByPath(it) } ?: return
        PsiTestUtil.addContentRoot(module, repo)
        try {
            refreshRoots()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            val all = sweep(repo)
            println("Type inspections on the real repo: ${countsByCode(all)}")
            println(all.joinToString("\n"))
            val errors = all.filter { it.endsWith(" ${ProblemHighlightType.GENERIC_ERROR.name}") }.map { it.removeSuffix(" ${ProblemHighlightType.GENERIC_ERROR.name}") }.sorted()
            assertEquals(baseline("types/red-budget-corpus.txt"), errors)
        } finally {
            PsiTestUtil.removeContentEntry(module, repo)
            refreshRoots()
        }
    }

    private fun copyWholeFixture(): VirtualFile {
        myFixture.copyDirectoryToProject(InfraTestData.INFRA, "")
        refreshRoots()
        return myFixture.tempDirFixture.getFile("")!!
    }

    /** `ShortName LEVEL` → count, from sweep lines. */
    private fun countsByCode(lines: List<String>): Map<String, Int> =
        lines.groupingBy { it.substringAfter(" ") }.eachCount().toSortedMap()

    private fun baseline(file: String = "types/red-budget.txt"): List<String> =
        Files.readAllLines(InfraTestData.testDataPath.resolve(file))
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .sorted()
}
