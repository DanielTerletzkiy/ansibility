package de.terletzkiy.ansibility.golden.sync

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.model.drift.DriftFixture
import de.terletzkiy.ansibility.model.role.ModelFixture
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings

/**
 * [RoleWriter] rules that depend on the project directory (the light project's root): ignored paths are never
 * written (D191), and a target that is gone writes nothing.
 */
class RoleWriterProjectPathsTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = ModelFixture.testDataPath

    override fun setUp() {
        super.setUp()
        DriftFixture.copy(myFixture)
    }

    fun testIgnoredPathsAreNeverTouched() {
        val keep = DriftFixture.write(myFixture, "${DriftFixture.roleDir("same")}/files/notes/keep.txt", "keep\n")
        val settings = AnsibilityProjectSettings.getInstance(project)
        val before = settings.settings
        try {
            settings.update { it.copy(paths = it.paths.copy(extraIgnoredPaths = it.paths.extraIgnoredPaths + "**/files/notes/**")) }
            val ops = listOf(FileOp.Delete("files/notes/keep.txt"), FileOp.Create("files/notes/new.txt", OpContent.Text("new\n")), FileOp.Create(".ansible/x.json", OpContent.Text("{}")))
            val result = RoleWriter.apply(project, DriftFixture.file(myFixture, DriftFixture.roleDir("same")), ops, "Before", "Write")
            assertEquals(WriteResult.Status.NOTHING_TO_DO, result.status)
            assertEquals(listOf(WriteResult.SkipReason.NEVER_TOUCHED, WriteResult.SkipReason.NEVER_TOUCHED, WriteResult.SkipReason.NEVER_TOUCHED), result.skipped.map { it.reason })
            assertTrue(keep.isValid)
            assertNull(DriftFixture.file(myFixture, DriftFixture.roleDir("same")).findFileByRelativePath("files/notes/new.txt"))
        } finally {
            settings.update { before }
        }
    }

    fun testAGoneTargetWritesNothing() {
        val dir = DriftFixture.file(myFixture, DriftFixture.roleDir("tasks", "solo"))
        DriftFixture.delete(myFixture, DriftFixture.roleDir("tasks", "solo"))
        val result = RoleWriter.apply(project, dir, listOf(FileOp.Create("tasks/new.yml", OpContent.Text("---\n"))), "Before", "Write")
        assertEquals(WriteResult.Status.INVALID_TARGET, result.status)
        assertNotNull(result.message)
        assertEquals(emptyList<String>(), result.created)
    }

    fun testTheSummaryNamesOnlyWhatHappened() {
        val dir = DriftFixture.file(myFixture, DriftFixture.roleDir("same"))
        val result = RoleWriter.apply(project, dir, listOf(FileOp.Create("files/a.txt", OpContent.Text("a\n")), FileOp.Create("files/b.txt", OpContent.Text("b\n"))), "Before", "Write")
        assertEquals("2 added", result.summary())
        assertEquals("nothing changed", RoleWriter.apply(project, dir, emptyList(), "Before", "Write").summary())
    }
}
