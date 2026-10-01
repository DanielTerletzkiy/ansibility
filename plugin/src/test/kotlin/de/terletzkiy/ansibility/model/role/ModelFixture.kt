package de.terletzkiy.ansibility.model.role

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.fixtures.InfraTestData
import org.jetbrains.yaml.psi.YAMLFile

/**
 * Shared set-up of the model tests: copies sub-trees of the sanitised infra fixture (paths keep their infra-repo
 * layout, so `golden/roles/haproxy` lands at `golden/roles/haproxy`) and of `testData/model-roles`, then re-scans
 * the Ansible roots. Tests override `getTestDataPath()` with [testDataPath].
 */
object ModelFixture {
    /** Directory of the synthetic model test data below [testDataPath]. */
    const val MODEL_ROLES: String = "model-roles"

    val testDataPath: String get() = InfraTestData.testDataPath.toString()

    /** Copies the infra sub-trees [paths] (relative to the fixture root) to the same paths in the project. */
    fun copyInfra(fixture: CodeInsightTestFixture, vararg paths: String) {
        for (path in paths) fixture.copyDirectoryToProject("${InfraTestData.INFRA}/$path", path)
        rescan(fixture.project)
    }

    /** Copies `testData/model-roles` to the project's root directory. */
    fun copyModelRoles(fixture: CodeInsightTestFixture) {
        fixture.copyDirectoryToProject(MODEL_ROLES, "")
        rescan(fixture.project)
    }

    /** Bumps the structure tracker so the roots and file kinds are computed from the current tree. */
    fun rescan(project: Project) {
        AnsibleWorkspaceImpl.getInstance(project)?.structureChanged()
    }

    fun file(fixture: CodeInsightTestFixture, path: String): VirtualFile =
        fixture.findFileInTempDir(path) ?: error("missing $path in the test project")

    fun yaml(fixture: CodeInsightTestFixture, path: String): YAMLFile =
        PsiManager.getInstance(fixture.project).findFile(file(fixture, path)) as? YAMLFile ?: error("$path is not YAML")

    /** The root whose directory is [path]. */
    fun root(fixture: CodeInsightTestFixture, path: String): AnsibleRoot {
        val dir = file(fixture, path)
        return AnsibleWorkspace.getInstance(fixture.project).roots().singleOrNull { it.dir == dir }
            ?: error("no root at $path; roots: ${AnsibleWorkspace.getInstance(fixture.project).roots().map { it.dir.path }}")
    }
}
