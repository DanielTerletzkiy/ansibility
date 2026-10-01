package de.terletzkiy.ansibility.inspections.references

import com.intellij.codeInspection.InspectionManager
import com.intellij.openapi.application.runReadActionBlocking
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
import de.terletzkiy.ansibility.navigation.RefsTestCase

/**
 * ANS-R001 over every checked file of the whole infra fixture (plan A.6 corpus red budget: 1 × R001 on today's repo):
 * the only certain failure of the real repo is `golden/playbooks/playbook-setup-keycloak.yml:51`. The sanitised
 * fixture copies every role and handler file the copied plays reference (the molecule prepare roles `nginx` and
 * `java21-jre` and the app-wren-mono handlers were added for this), so it yields exactly the same finding; the opt-in
 * run against the real repo (`ANSIBLE_INFRA_REPO`) expects the keycloak reference alone too.
 */
class UnresolvedReferenceSweepTest : RefsTestCase() {

    fun testWholeFixtureHasOnlyTheKnownKeycloakReference() {
        myFixture.copyDirectoryToProject(InfraTestData.INFRA, "")
        refreshRoots()
        val base = myFixture.tempDirFixture.getFile("")!!
        val found = sweep(base)
        assertEquals(listOf("$KEYCLOAK_PLAYBOOK:51 'configure' does not exist in tasks/ of role 'keycloak'"), found)
        for (added in FORMER_SUBSET_GAPS) assertNotNull("$added is part of the fixture", base.findFileByRelativePath(added))
    }

    fun testWholeRealRepoHasOnlyTheKnownKeycloakReference() {
        val repo = System.getenv(InfraTestData.INFRA_REPO_ENV)?.let { LocalFileSystem.getInstance().refreshAndFindFileByPath(it) } ?: return
        PsiTestUtil.addContentRoot(module, repo)
        try {
            refreshRoots()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            val found = sweep(repo)
            println("ANS-R001 on the real repo: ${found.size}\n${found.joinToString("\n")}")
            assertEquals(listOf("$KEYCLOAK_PLAYBOOK:51 'configure' does not exist in tasks/ of role 'keycloak'"), found)
        } finally {
            PsiTestUtil.removeContentEntry(module, repo)
            refreshRoots()
        }
    }

    /** Runs the inspection's `checkFile` on every checked file below [base] outside detached worktrees. */
    private fun sweep(base: VirtualFile): List<String> = runReadActionBlocking {
        val workspace = AnsibleWorkspace.getInstance(project)
        val manager = InspectionManager.getInstance(project)
        val inspection = AnsibleUnresolvedReferenceInspection()
        val found = ArrayList<String>()
        VfsUtilCore.visitChildrenRecursively(
            base,
            object : VirtualFileVisitor<Unit>() {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (file.isDirectory) return file.name !in SKIPPED && file.name != AnsibleLayout.DOT_GIT
                    val context = workspace.contextOf(file) ?: return true
                    if (context.root.detached || context.kind !in AnsibleUnresolvedReferenceInspection.CHECKED_KINDS) return true
                    val psi = PsiManager.getInstance(project).findFile(file) ?: return true
                    for (problem in inspection.checkFile(psi, manager, false).orEmpty()) {
                        val offset = problem.psiElement.textRange.startOffset + (problem.textRangeInElement?.startOffset ?: 0)
                        found += "${VfsUtilCore.getRelativePath(file, base)}:${lineOf(file, offset)} ${problem.descriptionTemplate}"
                    }
                    return true
                }
            },
        )
        found.sorted()
    }

    private companion object {
        val SKIPPED = setOf("node_modules", ".idea", ".gradle", "build")

        /**
         * What the molecule prepare plays (`golden/roles/loki`, `golden/roles/jenkins-controller`, falcon's `loki`) and
         * app-wren-mono's `notify` reference; the fixture once left these out.
         */
        val FORMER_SUBSET_GAPS: List<String> = listOf(
            "golden/roles/nginx/tasks/main.yml",
            "golden/roles/java21-jre/tasks/main.yml",
            "repos/falcon/ansible/roles/nginx/tasks/main.yml",
            "repos/wren/ansible/roles/app-wren-mono/handlers/main.yml",
        )
    }
}
