package de.terletzkiy.ansibility.typeflow

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.PsiManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.inspections.templated.AnsibleTemplatedValueTypeInspection
import org.jetbrains.yaml.psi.YAMLFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * The ANS-T020 part of the corpus red budget (plan A.6, M4 acceptance 8): every finding on the whole sanitised infra
 * fixture, pinned in `testData/templated/red-budget.txt` (reviewed: each line is a real documented-type mismatch that
 * ansible-core 2.18.8 coerces or rejects as the message says). The opt-in run against the real repo
 * (`ANSIBLE_INFRA_REPO`) prints its list and expects the haproxy chain in each of the 9 roots that carry the role.
 */
class TemplatedRedBudgetTest : TemplatedTestCase() {

    fun testWholeFixtureMatchesThePinnedRedBudget() {
        myFixture.copyDirectoryToProject(InfraTestData.INFRA, "")
        refreshRoots()
        val actual = sweep(myFixture.tempDirFixture.getFile("")!!)
        val expectedFile = InfraTestData.testDataPath.resolve("templated").resolve("red-budget.txt")
        val actualFile = Path.of("build", "templated-red-budget.actual.txt").toAbsolutePath()
        Files.createDirectories(actualFile.parent)
        Files.writeString(actualFile, actual.joinToString("\n", postfix = "\n"))
        val expected = Files.readAllLines(expectedFile).filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals("ANS-T020 on the fixture (actual list written to $actualFile)", expected.joinToString("\n"), actual.joinToString("\n"))
        assertTrue(
            "M4 acceptance 4 is part of the budget",
            actual.any { it.startsWith("$HAPROXY_DEFAULTS:15 ERROR ") && "haproxy_settings_maximum_connections: 65535" in it },
        )
    }

    fun testWholeRealRepo() {
        val repo = System.getenv(InfraTestData.INFRA_REPO_ENV)?.let { LocalFileSystem.getInstance().refreshAndFindFileByPath(it) } ?: return
        PsiTestUtil.addContentRoot(module, repo)
        try {
            refreshRoots()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            val found = sweep(repo)
            println("ANS-T020 on the real repo: ${found.size}\n${found.joinToString("\n")}")
            // Today's repo: the haproxy chain in each of the 9 roots that carry the role, nothing else.
            assertEquals(found.joinToString("\n"), 9, found.size)
            assertTrue(found.joinToString("\n"), found.all { it.substringBefore(' ').endsWith("roles/haproxy/defaults/main.yml:15") && " ERROR " in it })
        } finally {
            PsiTestUtil.removeContentEntry(module, repo)
            refreshRoots()
        }
    }

    /** `path:line LEVEL message` for every finding below [base] outside detached worktrees, sorted. */
    private fun sweep(base: VirtualFile): List<String> = runReadActionBlocking {
        val workspace = AnsibleWorkspace.getInstance(project)
        val found = ArrayList<String>()
        VfsUtilCore.visitChildrenRecursively(
            base,
            object : VirtualFileVisitor<Unit>() {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (file.isDirectory) return file.name !in SKIPPED && file.name != AnsibleLayout.DOT_GIT
                    val context = workspace.contextOf(file) ?: return true
                    if (context.root.detached || context.kind !in TemplatedValueTypes.CHECKED_FILE_KINDS) return true
                    val yaml = PsiManager.getInstance(project).findFile(file) as? YAMLFile ?: return true
                    for (finding in TemplatedValueTypes(project, yaml, context).findings()) {
                        val level = AnsibleTemplatedValueTypeInspection.level(project, context.root, finding)
                        val line = lineOf(file, finding.range.startOffset)
                        found += "${VfsUtilCore.getRelativePath(file, base)}:$line $level ${finding.message}"
                    }
                    return true
                }
            },
        )
        found.sorted()
    }

    private fun lineOf(file: VirtualFile, offset: Int): Int =
        StringUtil.offsetToLineNumber(VfsUtilCore.loadText(file), offset) + 1

    private companion object {
        val SKIPPED = setOf("node_modules", ".idea", ".gradle", "build")
    }
}
