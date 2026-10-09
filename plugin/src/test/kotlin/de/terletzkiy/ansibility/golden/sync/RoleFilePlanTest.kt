package de.terletzkiy.ansibility.golden.sync

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.golden.GoldenTestSupport
import de.terletzkiy.ansibility.model.drift.DriftFixture
import de.terletzkiy.ansibility.model.drift.DriftOptions
import de.terletzkiy.ansibility.model.drift.RoleDriftService
import de.terletzkiy.ansibility.model.role.ModelFixture
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.vault.crypto.VaultCrypto

/**
 * [RoleFilePlan] on the synthetic drift tree (plan amendment R24): the three kinds, drift's skip rules, `molecule/`,
 * sensitive files and whole-file vaults (flagged, excluded by default, never decrypted), binary detection, unsaved
 * documents and stamps.
 */
class RoleFilePlanTest : BasePlatformTestCase() {
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

    private fun vf(team: String, relative: String): VirtualFile = DriftFixture.file(myFixture, "${DriftFixture.roleDir(team)}/$relative")

    private fun write(team: String, relative: String, text: String) = DriftFixture.write(myFixture, "${DriftFixture.roleDir(team)}/$relative", text)

    private fun write(team: String, relative: String, bytes: ByteArray) = DriftFixture.write(myFixture, "${DriftFixture.roleDir(team)}/$relative", bytes)

    private fun plan(source: String, target: String, options: PlanOptions = PlanOptions()): RoleFilePlan =
        GoldenTestSupport.await { RoleFilePlan.compute(project, dir(source), dir(target), options) }

    private fun RoleFilePlan.paths(kind: PlanKind): List<String> = entries.filter { it.kind == kind }.map { it.relPath }

    fun testChangedOnlyInSourceAndOnlyInTarget() {
        val plan = plan("golden", "missing")
        assertEquals(listOf("defaults/main.yml", "handlers/main.yml", "tasks/main.yml"), plan.paths(PlanKind.CHANGED))
        assertEquals(
            listOf(
                "meta/argument_specs.yml", "molecule/default/converge.yml", "molecule/default/molecule.yml",
                "molecule/default/prepare.yml", "molecule/default/verify.yml", "tasks/configure.yml", "tasks/install.yml",
                "tasks/service.yml", "templates/site.conf.j2",
            ),
            plan.paths(PlanKind.ONLY_IN_SOURCE),
        )
        assertEquals(emptyList<String>(), plan.paths(PlanKind.ONLY_IN_TARGET))
        assertEquals("sorted by path", plan.entries.map { it.relPath }.sorted(), plan.entries.map { it.relPath })
        assertEquals(16, plan.sourceFileCount)
        assertEquals(7, plan.targetFileCount)

        val reverse = plan("missing", "golden")
        assertEquals(9, reverse.count(PlanKind.ONLY_IN_TARGET))
        assertEquals(0, reverse.count(PlanKind.ONLY_IN_SOURCE))

        write("same", "files/notes/local.txt", "only here\n")
        val extra = plan("golden", "same")
        assertEquals(listOf("files/notes/local.txt"), extra.paths(PlanKind.ONLY_IN_TARGET))
        val entry = extra.entries.single()
        assertNull(entry.sourceFile)
        assertEquals(vf("same", "files/notes/local.txt"), entry.targetFile)
        assertEquals(-1L, entry.sourceSize)
        assertEquals(10L, entry.targetSize)
        assertFalse(entry.binary || entry.sensitive || entry.excluded)
    }

    fun testIdenticalCopiesHaveNoEntry() {
        val plan = plan("golden", "same")
        assertTrue(plan.isIdentical)
        assertEquals(16, plan.sourceFileCount)
        assertEquals(16, plan.targetFileCount)
        val changed = plan("golden", "tasks").entries.single()
        assertEquals("tasks/main.yml", changed.relPath)
        assertEquals(PlanKind.CHANGED, changed.kind)
        assertEquals(vf("golden", "tasks/main.yml").length, changed.sourceSize)
        assertEquals(vf("tasks", "tasks/main.yml").length, changed.targetSize)
    }

    fun testDriftSkipRulesAndIgnoredPaths() {
        write("same", "__pycache__/helper.cpython-312.pyc", byteArrayOf(0x42, 0x0d, 0x0d, 0x0a))
        write("same", "library/helper.pyc", byteArrayOf(1, 2, 3))
        write("same", "files/.DS_Store", byteArrayOf(0, 0, 0, 1))
        write("same", ".ansible/cache.json", "{}")
        write("same", ".git/config", "[core]\n")
        write("same", "files/.git", "gitdir: ../.git/modules/web\n")
        assertTrue("skipped directories and files take no part", plan("golden", "same").isIdentical)

        write("same", "files/notes/local.txt", "only here\n")
        assertEquals(1, plan("golden", "same").entries.size)
        val settings = AnsibilityProjectSettings.getInstance(project)
        val before = settings.settings
        try {
            settings.update { it.copy(paths = it.paths.copy(extraIgnoredPaths = it.paths.extraIgnoredPaths + "**/files/notes/**")) }
            assertTrue("ignored paths take no part", plan("golden", "same").isIdentical)
        } finally {
            settings.update { before }
        }
    }

    fun testMoleculeFollowsTheOption() {
        assertEquals(listOf("molecule/default/verify.yml"), plan("golden", "mol").entries.map { it.relPath })
        val ignoring = plan("golden", "mol", PlanOptions(ignoreMolecule = true))
        assertTrue(ignoring.isIdentical)
        assertEquals("16 files minus 4 molecule files", 12, ignoring.sourceFileCount)

        val drift = RoleDriftService.getInstance(project)
        val before = drift.options
        try {
            drift.options = DriftOptions(ignoreMolecule = true)
            assertEquals(PlanOptions(ignoreMolecule = true), PlanOptions.fromDrift(project))
            assertEquals(PlanOptions(ignoreMolecule = true, includeSensitive = true), PlanOptions.fromDrift(project, includeSensitive = true))
        } finally {
            drift.options = before
        }
        assertEquals(PlanOptions(), PlanOptions.fromDrift(project))
    }

    fun testSensitiveFilesAndWholeFileVaultsAreFlaggedExcludedAndNeverDecrypted() {
        val guard = GoldenTestSupport.guardVault(project, testRootDisposable)
        val decrypts = VaultCrypto.getInstance(project).decryptAttempts
        write("golden", "files/ssl/web.key", "synthetic golden key\n")
        write("spec", "files/ssl/web.key", "synthetic repo key!!\n")
        write("spec", "files/secrets.yml", DriftFixture.syntheticVault("synthetic plan vault"))
        write("golden", "files/app.conf", "plain = 1\n")
        write("spec", "files/app.conf", DriftFixture.syntheticVault("synthetic encrypted conf"))

        val plan = plan("golden", "spec")
        val key = plan.entry("files/ssl/web.key")!!
        assertEquals(PlanKind.CHANGED, key.kind)
        assertTrue(key.sensitive && key.excluded && !key.wholeFileVault)
        val vault = plan.entry("files/secrets.yml")!!
        assertEquals(PlanKind.ONLY_IN_TARGET, vault.kind)
        assertTrue("a whole-file vault is sensitive whatever its name", vault.sensitive && vault.wholeFileVault && vault.excluded)
        val encrypted = plan.entry("files/app.conf")!!
        assertTrue("a file that became a vault on one side", encrypted.sensitive && encrypted.wholeFileVault)
        assertEquals(listOf("meta/argument_specs.yml"), plan.included.map { it.relPath })
        assertEquals(listOf("files/app.conf", "files/secrets.yml", "files/ssl/web.key"), plan.excluded.map { it.relPath })

        val including = plan("golden", "spec", PlanOptions(includeSensitive = true))
        assertEquals(4, including.included.size)
        assertTrue(including.entries.none { it.excluded })
        assertTrue("still flagged", including.entry("files/ssl/web.key")!!.sensitive)

        write("same", "files/ssl/web.key", "synthetic golden key\n")
        assertEquals("an equal key file is no difference", listOf("files/app.conf"), plan("golden", "same").entries.map { it.relPath })
        assertFalse("the plan prints no content", plan.toString().contains("synthetic") || plan.entries.toString().contains("synthetic"))
        assertEquals(0, guard.calls.get())
        assertEquals("nothing is decrypted", decrypts, VaultCrypto.getInstance(project).decryptAttempts)
    }

    fun testBinaryDetection() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D)
        write("golden", "files/logo.png", png)
        write("same", "files/logo.png", png + byteArrayOf(1))
        write("golden", "files/notes.txt", "plain text\n")
        write("same", "files/notes.txt", byteArrayOf(0x6F, 0x6B, 0xC3.toByte(), 0x28, 0x0A))
        write("golden", "files/data.txt", "one\n")
        write("same", "files/data.txt", byteArrayOf(0x6F, 0, 0x6B, 0x0A))
        write("golden", "files/motd.txt", "changed text\n")

        val plan = plan("golden", "same")
        assertTrue("binary file type", plan.entry("files/logo.png")!!.binary)
        assertTrue("not valid UTF-8", plan.entry("files/notes.txt")!!.binary)
        assertTrue("a NUL byte", plan.entry("files/data.txt")!!.binary)
        assertFalse("text", plan.entry("files/motd.txt")!!.binary)
    }

    fun testUnsavedDocumentsCountAsTheUserSeesThem() {
        val file = vf("same", "tasks/main.yml")
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        val text = document.text
        val stamp = FileStamp.of(file)
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, "# typed\n") }
        assertTrue(FileDocumentManager.getInstance().isFileModified(file))

        val typed = plan("golden", "same")
        val entry = typed.entries.single()
        assertEquals("tasks/main.yml", entry.relPath)
        assertEquals(text.length + "# typed\n".length.toLong(), entry.targetSize)
        assertTrue("the unsaved document changes the stamp", entry.targetStamp != stamp && entry.targetStamp.exists)
        assertEquals(entry.targetStamp, FileStamp.of(file))

        WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }
        assertTrue(FileDocumentManager.getInstance().isFileModified(file))
        assertTrue("the reverted document equals golden again", plan("golden", "same").isIdentical)
        FileDocumentManager.getInstance().reloadFromDisk(document)
    }

    fun testUnsavedDocumentKeepsTheFileLineSeparators() {
        val crlf = "user {{ web_user }};\r\nlisten {{ web_port }};\r\n".toByteArray()
        write("golden", "templates/web.conf.j2", crlf)
        val copy = write("same", "templates/web.conf.j2", crlf)
        assertTrue(plan("golden", "same").isIdentical)
        val document = FileDocumentManager.getInstance().getDocument(copy)!!
        val text = document.text
        WriteCommandAction.runWriteCommandAction(project) { document.setText("$text# temporary\n") }
        assertEquals(1, plan("golden", "same").entries.size)
        WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }
        assertTrue("the document is compared with the file's CRLF separators", plan("golden", "same").isIdentical)
        FileDocumentManager.getInstance().reloadFromDisk(document)
    }

    fun testStampsTellAnyChange() {
        val file = vf("tasks", "tasks/main.yml")
        val entry = plan("golden", "tasks").entries.single()
        assertEquals(FileStamp.of(file), entry.targetStamp)
        assertEquals(FileStamp.of(vf("golden", "tasks/main.yml")), entry.sourceStamp)
        assertEquals(FileStamp.ABSENT, FileStamp.of(null))
        write("tasks", "tasks/main.yml", "---\n- name: Edited\n  ansible.builtin.meta: noop\n")
        assertFalse(FileStamp.of(file) == entry.targetStamp)
        DriftFixture.delete(myFixture, "${DriftFixture.roleDir("tasks")}/tasks/main.yml")
        assertEquals(FileStamp.ABSENT, FileStamp.of(file))
    }

    fun testComputeBlockingRunsOnABackgroundThread() {
        val plan = GoldenTestSupport.pooled { RoleFilePlan.computeBlocking(project, dir("golden"), dir("mol")) }
        assertEquals(listOf("molecule/default/verify.yml"), plan.entries.map { it.relPath })
        assertEquals(dir("golden"), plan.source)
        assertEquals(dir("mol"), plan.target)
    }
}
