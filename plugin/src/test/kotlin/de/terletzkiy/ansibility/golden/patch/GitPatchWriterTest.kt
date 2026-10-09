package de.terletzkiy.ansibility.golden.patch

import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.golden.patch.GitPatchWriter.FileChange
import de.terletzkiy.ansibility.golden.remote.GitTestRepo
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * The git-style unified diff of plan amendment R25, X126 ([GitPatchWriter]): exact text for changed, added and
 * deleted files, hunks with three lines of context (joined when close), a missing final newline, CRLF and other bytes
 * kept, mode lines, quoted names; and a round trip through the system git (`git apply` in a temp repository makes the
 * old files byte-identical to the new ones).
 */
class GitPatchWriterTest : BasePlatformTestCase() {
    private fun diff(vararg changes: FileChange): String {
        val out = GitPatchWriter.Output()
        changes.forEach { GitPatchWriter.write(out, it) }
        return String(out.bytes(), Charsets.ISO_8859_1)
    }

    private fun bytes(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

    private fun lines(range: IntRange, change: Map<Int, String> = emptyMap()): String =
        range.joinToString("") { (change[it] ?: "l$it") + "\n" }

    // ------------------------------------------------------------------ text

    fun testAChangedFileHasHunksWithThreeLinesOfContext() {
        val patch = diff(FileChange("roles/web/tasks/main.yml", bytes(lines(1..15)), bytes(lines(1..15, mapOf(2 to "L2", 13 to "L13")))))
        assertEquals(
            """
            diff --git a/roles/web/tasks/main.yml b/roles/web/tasks/main.yml
            --- a/roles/web/tasks/main.yml
            +++ b/roles/web/tasks/main.yml
            @@ -1,5 +1,5 @@
             l1
            -l2
            +L2
             l3
             l4
             l5
            @@ -10,6 +10,6 @@
             l10
             l11
             l12
            -l13
            +L13
             l14
             l15

            """.trimIndent(),
            patch,
        )
    }

    fun testChangesCloserThanTwiceTheContextShareOneHunk() {
        val patch = diff(FileChange("f", bytes(lines(1..15)), bytes(lines(1..15, mapOf(2 to "L2", 8 to "L8")))))
        assertEquals(listOf("@@ -1,11 +1,11 @@"), patch.lines().filter { it.startsWith("@@") })
        val six = diff(FileChange("f", bytes(lines(1..15)), bytes(lines(1..15, mapOf(2 to "L2", 9 to "L9")))))
        assertEquals("six lines apart: still one", listOf("@@ -1,12 +1,12 @@"), six.lines().filter { it.startsWith("@@") })
        val apart = diff(FileChange("f", bytes(lines(1..15)), bytes(lines(1..15, mapOf(2 to "L2", 10 to "L10")))))
        assertEquals("seven lines apart: two", listOf("@@ -1,5 +1,5 @@", "@@ -7,7 +7,7 @@"), apart.lines().filter { it.startsWith("@@") })
    }

    fun testInsertionsAndDeletionsCountTheirLines() {
        val patch = diff(FileChange("f", bytes("a\nb\nc\n"), bytes("a\nb\nx\ny\nc\n")))
        assertTrue(patch, patch.endsWith("@@ -1,3 +1,5 @@\n a\n b\n+x\n+y\n c\n"))
        val removed = diff(FileChange("f", bytes(lines(1..10)), bytes(lines(1..10).replace("l5\n", ""))))
        assertTrue(removed, removed.endsWith("@@ -2,7 +2,6 @@\n l2\n l3\n l4\n-l5\n l6\n l7\n l8\n"))
    }

    fun testAnAddedFileIsANewFileDiff() {
        assertEquals(
            "diff --git a/roles/web/templates/extra.j2 b/roles/web/templates/extra.j2\nnew file mode 100644\n" +
                "--- /dev/null\n+++ b/roles/web/templates/extra.j2\n@@ -0,0 +1,2 @@\n+x\n+y\n",
            diff(FileChange("roles/web/templates/extra.j2", null, bytes("x\ny\n"))),
        )
        assertEquals(
            "an executable script, one line",
            "diff --git a/s.sh b/s.sh\nnew file mode 100755\n--- /dev/null\n+++ b/s.sh\n@@ -0,0 +1 @@\n+run\n",
            diff(FileChange("s.sh", null, bytes("run\n"), newExecutable = true)),
        )
    }

    fun testAFileOnlyGoldenHasIsADeletion() {
        assertEquals(
            "diff --git a/roles/web/meta/argument_specs.yml b/roles/web/meta/argument_specs.yml\ndeleted file mode 100644\n" +
                "--- a/roles/web/meta/argument_specs.yml\n+++ /dev/null\n@@ -1,2 +0,0 @@\n-a\n-b\n",
            diff(FileChange("roles/web/meta/argument_specs.yml", bytes("a\nb\n"), null)),
        )
    }

    fun testEmptyFilesNeedNoHunk() {
        assertEquals("diff --git a/e b/e\nnew file mode 100644\n", diff(FileChange("e", null, ByteArray(0))))
        assertEquals("diff --git a/e b/e\ndeleted file mode 100755\n", diff(FileChange("e", ByteArray(0), null, oldExecutable = true)))
        assertEquals("one empty line", "diff --git a/e b/e\n--- a/e\n+++ b/e\n@@ -0,0 +1 @@\n+\n", diff(FileChange("e", ByteArray(0), bytes("\n"))))
    }

    fun testAMissingFinalNewlineIsMarked() {
        assertTrue(
            "golden has none, the copy adds it",
            diff(FileChange("f", bytes("a\nb"), bytes("a\nb\n"))).endsWith("@@ -1,2 +1,2 @@\n a\n-b\n\\ No newline at end of file\n+b\n"),
        )
        assertTrue(
            "the copy drops it",
            diff(FileChange("f", bytes("a\nb\n"), bytes("a\nb"))).endsWith("@@ -1,2 +1,2 @@\n a\n-b\n+b\n\\ No newline at end of file\n"),
        )
        assertTrue(
            "neither side has one: the context line is marked once",
            diff(FileChange("f", bytes("a\nb\nc"), bytes("a\nB\nc"))).endsWith("@@ -1,3 +1,3 @@\n a\n-b\n+B\n c\n\\ No newline at end of file\n"),
        )
        assertTrue(
            "a new file without one",
            diff(FileChange("f", null, bytes("x"))).endsWith("@@ -0,0 +1 @@\n+x\n\\ No newline at end of file\n"),
        )
    }

    fun testCrlfAndOtherBytesAreKept() {
        assertTrue("CRLF lines keep their CR", diff(FileChange("f", bytes("a\r\nb\r\n"), bytes("a\r\nc\r\n"))).endsWith("@@ -1,2 +1,2 @@\n a\r\n-b\r\n+c\r\n"))
        assertTrue("CRLF to LF is a change", diff(FileChange("f", bytes("a\r\n"), bytes("a\n"))).endsWith("@@ -1 +1 @@\n-a\r\n+a\n"))
        val latin1 = byteArrayOf('x'.code.toByte(), 0xE9.toByte(), '\n'.code.toByte())
        val out = GitPatchWriter.Output()
        GitPatchWriter.write(out, FileChange("f", bytes("x\n"), latin1))
        val written = out.bytes()
        val plus = written.indexOfLast { it == '+'.code.toByte() }
        assertEquals("a byte that is no UTF-8 passes through", latin1.toList(), written.copyOfRange(plus + 1, written.size).toList())
    }

    fun testModeChangesAreWritten() {
        assertEquals(
            "diff --git a/s.sh b/s.sh\nold mode 100644\nnew mode 100755\n--- a/s.sh\n+++ b/s.sh\n@@ -1 +1 @@\n-a\n+b\n",
            diff(FileChange("s.sh", bytes("a\n"), bytes("b\n"), oldExecutable = false, newExecutable = true)),
        )
    }

    fun testSpecialNamesAreQuotedLikeGit() {
        assertEquals("a/x y", GitPatchWriter.quote("a/x y"))
        assertEquals("\"a/x\\ty\"", GitPatchWriter.quote("a/x\ty"))
        assertEquals("\"a/q\\\"b\\\\c\"", GitPatchWriter.quote("a/q\"b\\c"))
        assertEquals("\"a/\\001\"", GitPatchWriter.quote("a/\u0001"))
        assertTrue(diff(FileChange("x\ty", null, bytes("1\n"))).startsWith("diff --git \"a/x\\ty\" \"b/x\\ty\"\nnew file mode 100644\n--- /dev/null\n+++ \"b/x\\ty\"\n"))
        val umlaut = GitPatchWriter.Output().also { GitPatchWriter.write(it, FileChange("ä.txt", null, bytes("1\n"))) }.bytes()
        assertTrue("other names as their UTF-8 bytes", String(umlaut, Charsets.UTF_8).startsWith("diff --git a/ä.txt b/ä.txt\n"))
    }

    fun testBinaryLinesAndTextDetection() {
        assertEquals("Binary files a/p.png and b/p.png differ", GitPatchWriter.binaryLine("p.png", oldExists = true, newExists = true))
        assertEquals("Binary files /dev/null and b/p.png differ", GitPatchWriter.binaryLine("p.png", oldExists = false, newExists = true))
        assertEquals("Binary files a/p.png and /dev/null differ", GitPatchWriter.binaryLine("p.png", oldExists = true, newExists = false))
        assertFalse(GitPatchWriter.isText(byteArrayOf(1, 0, 2)))
        assertTrue(GitPatchWriter.isText(bytes("a\r\né")))
    }

    fun testRanges() {
        assertEquals("0,0", GitPatchWriter.range(0, 0))
        assertEquals("4,0", GitPatchWriter.range(4, 0))
        assertEquals("1", GitPatchWriter.range(0, 1))
        assertEquals("3,5", GitPatchWriter.range(2, 5))
    }

    // ------------------------------------------------------------------ git apply

    /**
     * Every kind of change at once through the system git: changed (many hunks, CRLF, a missing final newline, a
     * non-UTF-8 byte, a mode change), added (empty, executable, without a final newline), deleted (empty too). After
     * `git apply`, the repository holds exactly the new files.
     */
    fun testGitApplyMakesTheOldFilesByteIdenticalToTheNewOnes() {
        val dir = FileUtil.createTempDirectory("ansibility-x126-writer", null, true).toPath()
        val old = mapOf(
            "roles/web/tasks/main.yml" to lines(1..40),
            "roles/web/crlf.txt" to "a\r\nb\r\nc\r\n",
            "roles/web/noeol.txt" to "x\ny",
            "roles/web/dropeol.txt" to "x\ny\n",
            "roles/web/latin1.txt" to "café\nend\n",
            "roles/web/gone.yml" to "bye\n",
            "roles/web/gone-empty" to "",
            "roles/web/run.sh" to "echo a\n",
            "roles/web/to-empty.txt" to "something\n",
        )
        val new = mapOf(
            "roles/web/tasks/main.yml" to lines(1..40, mapOf(1 to "L1", 3 to "L3", 20 to "L20", 40 to "L40")).replace("l30\n", "") + "l41\n",
            "roles/web/crlf.txt" to "a\r\nB\r\nc\n",
            "roles/web/noeol.txt" to "x\ny\n",
            "roles/web/dropeol.txt" to "x\nY",
            "roles/web/latin1.txt" to "cafè\nend\n",
            "roles/web/new.txt" to "fresh\nno newline",
            "roles/web/new-empty" to "",
            "roles/web/run.sh" to "echo b\n",
            "roles/web/to-empty.txt" to "",
        )
        val repo = GitTestRepo.init(dir.resolve("golden"))
        repo.git("config", "core.autocrlf", "false")
        for ((path, text) in old) write(repo.dir.resolve(path), bytes(text))
        repo.commit("golden")

        val out = GitPatchWriter.Output()
        for (path in (old.keys + new.keys).sorted()) {
            GitPatchWriter.write(
                out,
                FileChange(path, old[path]?.let(::bytes), new[path]?.let(::bytes), oldExecutable = false, newExecutable = path.endsWith(".sh") || path == "roles/web/new.txt"),
            )
        }
        PatchGit.apply(repo, out.bytes())

        val expected = new.mapValues { (_, text) -> bytes(text).toList() }.toSortedMap()
        assertEquals(expected, PatchGit.files(repo.dir).filterKeys { it.startsWith("roles/") })
        assertTrue("the mode change applied", PatchGit.executable(repo.dir.resolve("roles/web/run.sh")))
        assertTrue("a new executable file", PatchGit.executable(repo.dir.resolve("roles/web/new.txt")))
        assertFalse(PatchGit.executable(repo.dir.resolve("roles/web/crlf.txt")))
    }

    private fun write(path: Path, bytes: ByteArray) {
        Files.createDirectories(path.parent)
        Files.write(path, bytes)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"))
    }
}
