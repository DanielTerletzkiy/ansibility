package de.terletzkiy.ansibility.inspections.modules

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import java.nio.file.Files

/**
 * The fixture budget (plan A.6 "Corpus red budget", M4 acceptance 8 for this WU's codes): the six module option and
 * keyword inspections over every task, handler, playbook and molecule file of the sanitised infra fixture report
 * exactly the findings in `testData/modules/red-budget.txt`, each reviewed. The research measured 0 unknown options
 * besides `repos/platform/ansible/roles/mysql-databases/molecule/default/verify.yml:34` and 0 errors on literal module
 * options; the opt-in run against the real repo (`ANSIBLE_INFRA_REPO`) prints its findings and expects the same
 * single error.
 */
@RequiresInfraFixture
class ModuleChecksRedBudgetTest : ModuleChecksTestCase() {

    fun testWholeFixtureMatchesTheBudget() {
        myFixture.copyDirectoryToProject(InfraTestData.INFRA, "")
        completeMysqlDatabasesRole()
        val base = myFixture.tempDirFixture.getFile("")!!
        val found = sweep(base)
        val budget = Files.readAllLines(InfraTestData.testDataPath.resolve("modules/red-budget.txt"))
            .filter { it.isNotBlank() && !it.startsWith("#") }
        println("module option and keyword findings on the fixture: ${found.size}\n${found.joinToString("\n")}")
        assertEquals(budget.joinToString("\n"), found.joinToString("\n"))
    }

    fun testWholeRealRepoHasOnlyTheKnownErrors() {
        val repo = System.getenv(InfraTestData.INFRA_REPO_ENV)?.let { LocalFileSystem.getInstance().refreshAndFindFileByPath(it) } ?: return
        PsiTestUtil.addContentRoot(module, repo)
        try {
            refreshRoots()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            val found = sweep(repo)
            println("module option and keyword findings on the real repo: ${found.size}\n${found.joinToString("\n")}")
            assertEquals(listOf(VERIFY_M001), found.filter { " GENERIC_ERROR " in it }.map { it.substringBefore(" GENERIC_ERROR") })
        } finally {
            PsiTestUtil.removeContentEntry(module, repo)
            refreshRoots()
        }
    }

    /** Every finding below [base] outside detached worktrees, as `path:line: HIGHLIGHT shortName: message`, sorted. */
    private fun sweep(base: VirtualFile): List<String> {
        val files = runReadActionBlocking {
            val workspace = AnsibleWorkspace.getInstance(project)
            val result = ArrayList<VirtualFile>()
            VfsUtilCore.visitChildrenRecursively(
                base,
                object : VirtualFileVisitor<Unit>() {
                    override fun visitFile(file: VirtualFile): Boolean {
                        if (file.isDirectory) return file.name !in SKIPPED && file.name != AnsibleLayout.DOT_GIT
                        val context = workspace.contextOf(file) ?: return true
                        if (!context.root.detached && context.kind in TaskCheckEnvironment.CHECKED_KINDS) result += file
                        return true
                    }
                },
            )
            result
        }
        return files.flatMap { file -> check(file).map { "${VfsUtilCore.getRelativePath(file, base)}:$it" } }.sorted()
    }

    private companion object {
        val SKIPPED = setOf("node_modules", ".idea", ".gradle", "build")
        const val VERIFY_M001 = "$VERIFY:34:"
    }
}
