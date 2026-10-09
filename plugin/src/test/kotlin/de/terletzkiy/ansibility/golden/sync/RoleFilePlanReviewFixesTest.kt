package de.terletzkiy.ansibility.golden.sync

import de.terletzkiy.ansibility.golden.GoldenLocalTestCase
import de.terletzkiy.ansibility.golden.GoldenTestSupport
import de.terletzkiy.ansibility.golden.compare.RoleCompare
import de.terletzkiy.ansibility.inspections.vault.TestKeys
import de.terletzkiy.ansibility.model.drift.DriftRules
import java.nio.file.Files

/**
 * The review fixes of [RoleFilePlan] (plan amendment R24, review S5, S7, S9, P5) on real files: a path below a linked
 * directory of one side is no difference (so a mirror never deletes it; ignored paths: [RoleFilePlanSkipRulesTest]),
 * private keys are sensitive by content and by name in
 * any case, paths that differ only in case take no part on a file system that ignores case, and a plan reads only what
 * it needs.
 */
class RoleFilePlanReviewFixesTest : GoldenLocalTestCase() {
    private fun plan(source: String, target: String, options: PlanOptions = PlanOptions()): RoleFilePlan =
        GoldenTestSupport.await { RoleFilePlan.compute(project, roleDir(source), roleDir(target), options) }

    private fun mirror(plan: RoleFilePlan): List<FileOp> = GoldenTestSupport.await { SyncOps.mirror(project, plan) }

    // ------------------------------------------------------------------ S5: one side's rules hide a path

    fun testAPathBelowALinkedSourceDirectoryIsNeverDeleted() {
        val outside = Files.createDirectories(base.resolve("outside/shared"))
        Files.writeString(outside.resolve("a.txt"), "shared\n")
        Files.createSymbolicLink(path("golden", "files/shared"), outside)
        writeOnDisk("same", "files/shared/a.txt", "a real folder in same\n".toByteArray())
        val plan = plan("golden", "same")
        assertNull("golden's files/shared is a link: same's real folder is no difference", plan.entry("files/shared/a.txt"))
        assertTrue(plan.isIdentical)
        assertTrue(plan("same", "golden").isIdentical)
    }

    // ------------------------------------------------------------------ S7: private keys by content and name

    fun testPrivateKeysAreSensitiveByContentAndByNameInAnyCase() {
        GoldenTestSupport.guardVault(project, testRootDisposable)
        writeOnDisk("same", "files/id_rsa", TestKeys.plaintextKey().toByteArray())
        writeOnDisk("same", "files/deploy/deploy_key_one", TestKeys.plaintextKey().toByteArray())
        writeOnDisk("golden", "files/deploy/deploy_key_one", TestKeys.plaintextKey().toByteArray())
        writeOnDisk("same", "files/SSH/config_backup", "Host *\n  User alice\n".toByteArray())
        writeOnDisk("same", "files/notes/readme.txt", "no key here\n".toByteArray())

        val plan = plan("golden", "same")
        val byName = plan.entry("files/id_rsa")!!
        assertTrue("an OpenSSH key file name, and a key inside", byName.sensitive && byName.excluded)
        val byContent = plan.entry("files/deploy/deploy_key_one")!!
        assertEquals(PlanKind.CHANGED, byContent.kind)
        assertTrue("a plaintext key under a plain name is sensitive by its content", byContent.sensitive && byContent.excluded)
        val upper = plan.entry("files/SSH/config_backup")!!
        assertTrue("files/SSH is files/ssh", upper.sensitive && upper.excluded)
        assertFalse(plan.entry("files/notes/readme.txt")!!.sensitive)
        assertEquals(listOf("files/notes/readme.txt"), plan.included.map { it.relPath })
        assertFalse("never printed", plan.entries.toString().contains("PRIVATE"))

        // Compare shows the key file as a placeholder, never its content (it follows the plan's flag).
        val chain = GoldenTestSupport.await { RoleCompare.getInstance(project).chain(roleCopy("golden"), roleCopy("same"), null) }
        assertTrue("the sensitive key is in Compare's list", chain.plan.entry("files/deploy/deploy_key_one")!!.sensitive)
    }

    fun testSensitiveNamesIgnoreCaseAndKnowKeyFileNames() {
        assertTrue(DriftRules.isSensitivePath("files/SSH/id_rsa"))
        assertTrue(DriftRules.isSensitivePath("files/Ssl/web.conf"))
        assertTrue(DriftRules.isSensitivePath("files/id_ed25519"))
        assertTrue(DriftRules.isSensitivePath("files/deploy/id_ecdsa"))
        assertTrue(DriftRules.isSensitivePath("files/app.KEY"))
        assertTrue(DriftRules.isSensitivePath("files/store.p12"))
        assertTrue(DriftRules.isSensitivePath("files/mysql/users/alice.password"))
        assertFalse("a public key is not sensitive", DriftRules.isSensitivePath("files/id_rsa.pub"))
        assertFalse(DriftRules.isSensitivePath("files/sshd_config"))
        assertFalse(DriftRules.isSensitivePath("tasks/main.yml"))
    }

    // ------------------------------------------------------------------ S9: case-only differences

    fun testPathsThatDifferOnlyInCaseTakeNoPartOnACaseInsensitiveFileSystem() {
        if (roleDir("tasks").fileSystem.isCaseSensitive) return // Linux: Tasks/ and tasks/ are two folders, a mirror is right.
        Files.move(path("tasks", "templates"), path("tasks", "Templates.tmp"))
        Files.move(path("tasks", "Templates.tmp"), path("tasks", "Templates"))
        refresh()
        val plan = plan("golden", "tasks")
        assertEquals(
            listOf("Templates/site.conf.j2", "Templates/web.conf.j2", "templates/site.conf.j2", "templates/web.conf.j2"),
            plan.caseConflicts,
        )
        assertNull(plan.entry("templates/web.conf.j2"))
        assertNull(plan.entry("Templates/web.conf.j2"))
        assertEquals(listOf("tasks/main.yml"), plan.entries.map { it.relPath })

        val before = bytes("tasks", "Templates/web.conf.j2")
        val result = RoleWriter.apply(project, roleDir("tasks"), mirror(plan), "Before", "Push web to tasks")
        assertEquals(result.toString(), WriteResult.Status.APPLIED, result.status)
        assertEquals(listOf("tasks/main.yml"), result.written)
        assertTrue("the file that differs in case is left as it is", before.contentEquals(bytes("tasks", "Templates/web.conf.j2")))
    }

    // ------------------------------------------------------------------ P5: read only what is needed

    fun testFilesOfDifferentLengthsAreNotReadAndHeadsAreShort() {
        val big = ByteArray(5 * 1024 * 1024) { 'a'.code.toByte() }
        writeOnDisk("golden", "files/big.txt", big)
        writeOnDisk("same", "files/big.txt", big + 'b'.code.toByte())
        val fullReads = RoleFiles.Counters.fullReads.get()
        val compared = RoleFiles.Counters.comparedBytes.get()
        val heads = RoleFiles.Counters.headBytes.get()

        val plan = plan("golden", "same")
        assertEquals(listOf("files/big.txt"), plan.entries.map { it.relPath })
        assertEquals("no file is read whole", 0, RoleFiles.Counters.fullReads.get() - fullReads)
        assertTrue("only the heads of the differing file", RoleFiles.Counters.headBytes.get() - heads <= 2L * RoleFiles.HEAD)
        val read = RoleFiles.Counters.comparedBytes.get() - compared
        assertTrue("the identical files of the same length are compared, the big ones never: $read", read < big.size)
        assertEquals(5L * 1024 * 1024, plan.entries.single().sourceSize)
        assertFalse(plan.entries.single().binary)
    }

    fun testIdenticalFilesAreComparedInChunksAndAFileDifferingLateIsFound() {
        val big = ByteArray(300 * 1024) { (it % 251).toByte() }
        writeOnDisk("golden", "files/data.bin", big)
        val late = big.copyOf().also { it[it.size - 1] = 7 }
        writeOnDisk("same", "files/data.bin", late)
        writeOnDisk("mol", "files/data.bin", big)
        assertEquals(listOf("files/data.bin"), plan("golden", "same").entries.map { it.relPath })
        assertNull(plan("golden", "mol").entry("files/data.bin"))
    }

    fun testABinaryFileIsToldFromItsFirstBytes() {
        writeOnDisk("golden", "files/blob.dat", byteArrayOf(1, 0, 2, 3))
        writeOnDisk("same", "files/blob.dat", byteArrayOf(1, 0, 2, 4))
        val text = ("é".repeat(5000) + "\n").toByteArray()
        writeOnDisk("golden", "files/accents.txt", text)
        writeOnDisk("same", "files/accents.txt", text + "x\n".toByteArray())
        val plan = plan("golden", "same")
        assertTrue(plan.entry("files/blob.dat")!!.binary)
        assertFalse("a UTF-8 character cut at the end of the window is no error", plan.entry("files/accents.txt")!!.binary)
    }

    fun testThePlanRemembersEveryStampAndTellsChangesSince() {
        val plan = plan("golden", "mol")
        assertTrue(GoldenTestSupport.await { plan.changesSince(project) }.isEmpty)
        writeOnDisk("golden", "tasks/main.yml", "---\n# golden edited, identical before\n".toByteArray())
        writeOnDisk("mol", "files/new.txt", "new\n".toByteArray())
        val changes = GoldenTestSupport.await { plan.changesSince(project) }
        assertEquals(listOf("tasks/main.yml"), changes.source)
        assertEquals(listOf("files/new.txt"), changes.target)
    }
}
