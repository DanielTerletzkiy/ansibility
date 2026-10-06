package de.terletzkiy.ansibility.fixtures

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.yaml.YAMLFileType
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Checks that the infra fixture works the way platform tests use it (PLAN.md, Testing strategy 3):
 * `copyDirectoryToProject("infra", "")` must carry the dot-directories and the synthetic `.git` link files
 * that the detached-root rule reads, and every sanitised YAML file must still parse.
 */
@RequiresInfraFixture
class InfraFixtureCopyTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = InfraTestData.testDataPath.toString()

    fun testCopyDirectoryToProjectKeepsGitLinksAndTheWorktree() {
        val projectRoot = myFixture.copyDirectoryToProject(InfraTestData.INFRA, "")

        for (name in InfraTestData.SUBMODULES) {
            val link = projectRoot.findFileByRelativePath("repos/$name/.git")
            assertNotNull("repos/$name/.git was not copied into the test project", link)
            assertEquals(InfraTestData.submoduleGitLink(name), VfsUtilCore.loadText(link!!))
        }
        val worktree = projectRoot.findFileByRelativePath("${InfraTestData.WORKTREE_DIR}/.git")
        assertNotNull("the detached worktree .git file was not copied", worktree)
        assertEquals(InfraTestData.WORKTREE_GIT_LINK, VfsUtilCore.loadText(worktree!!))

        for (rel in listOf(
            "golden/roles/haproxy/defaults/main.yml",
            "${InfraTestData.WORKTREE_DIR}/golden/roles/haproxy/defaults/main.yml",
            "repos/heron/ansible/environments/prod/group_vars/keycloak/vars.yml",
        )) {
            val file = projectRoot.findFileByRelativePath(rel)
            assertNotNull("$rel was not copied", file)
            val onDisk = String(Files.readAllBytes(InfraTestData.root.resolve(rel)), StandardCharsets.UTF_8)
            assertEquals("$rel changed while copying", onDisk.lines().size, VfsUtilCore.loadText(file!!).lines().size)
        }
    }

    fun testEverySanitisedYamlFileParses() {
        val yamlFiles = Files.walk(InfraTestData.root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().let { n -> n.endsWith(".yml") || n.endsWith(".yaml") } }
                .sorted()
                .toList()
        }
        assertTrue("expected YAML files in the fixture", yamlFiles.size > 400)
        val factory = PsiFileFactory.getInstance(project)
        val broken = yamlFiles.mapNotNull { path ->
            val text = String(Files.readAllBytes(path), StandardCharsets.UTF_8)
            val psi = factory.createFileFromText(path.fileName.toString(), YAMLFileType.YML, text)
            InfraTestData.root.relativize(path).joinToString("/").takeIf { PsiTreeUtil.hasErrorElements(psi) }
        }
        assertEquals("sanitised YAML files with parse errors", emptyList<String>(), broken)
    }
}
