package de.terletzkiy.ansibility.golden.sync

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.golden.GoldenTestSupport
import de.terletzkiy.ansibility.model.drift.DriftFixture
import de.terletzkiy.ansibility.model.role.ModelFixture
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings

/**
 * S5 (plan amendment R24 review): a path one side's rules hide is no difference, so a mirror never deletes or creates
 * it there. Ignored paths are project-relative globs, so this runs in the light project, whose directory holds the
 * synthetic tree.
 */
class RoleFilePlanSkipRulesTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = ModelFixture.testDataPath

    override fun setUp() {
        super.setUp()
        DriftFixture.copy(myFixture)
        GoldenTestSupport.useFirstLibraryAsGolden(project, testRootDisposable)
    }

    override fun tearDown() {
        try {
            FileDocumentManager.getInstance().saveAllDocuments()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun dir(team: String): VirtualFile = DriftFixture.file(myFixture, DriftFixture.roleDir(team))

    private fun write(team: String, relative: String, text: String) = DriftFixture.write(myFixture, "${DriftFixture.roleDir(team)}/$relative", text)

    private fun plan(source: String, target: String): RoleFilePlan =
        GoldenTestSupport.await { RoleFilePlan.compute(project, dir(source), dir(target), PlanOptions()) }

    private fun withIgnored(glob: String, block: () -> Unit) {
        val settings = AnsibilityProjectSettings.getInstance(project)
        val before = settings.settings
        settings.update { it.copy(paths = it.paths.copy(extraIgnoredPaths = it.paths.extraIgnoredPaths + glob)) }
        try {
            block()
        } finally {
            settings.update { before }
        }
    }

    fun testAPathTheSourceIgnoresIsNoDifferenceAndIsNeverDeleted() {
        write("golden", "files/notes/a.txt", "golden notes\n")
        write("same", "files/notes/a.txt", "the repo's notes\n")
        write("same", "files/notes/only-here.txt", "only in same\n")
        assertEquals("without the rule they differ", listOf("files/notes/a.txt", "files/notes/only-here.txt"), plan("golden", "same").entries.map { it.relPath })
        withIgnored("golden/roles/web/files/notes/**") {
            val plan = plan("golden", "same")
            assertEquals("golden hides files/notes: no entry, so no Delete", emptyList<String>(), plan.entries.map { it.relPath })
            assertTrue(GoldenTestSupport.await { SyncOps.mirror(project, plan) }.none { it is FileOp.Delete })
            // The other way round: what the target hides is never created there either.
            assertEquals(emptyList<String>(), plan("same", "golden").entries.map { it.relPath })
        }
    }

    fun testAnIgnoredFolderOfTheTargetIsNeverWrittenNorAnEntry() {
        write("golden", "files/thirdparty/lib.txt", "golden's vendored file\n")
        withIgnored("repos/same/**/files/thirdparty") {
            assertTrue("same ignores files/thirdparty, even before it exists there", plan("golden", "same").isIdentical)
        }
        assertEquals(listOf("files/thirdparty/lib.txt"), plan("golden", "same").entries.map { it.relPath })
    }
}
