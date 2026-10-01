package de.terletzkiy.ansibility.inspections.undefined

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.PsiManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.semantics.diagnostics.Level
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.TreeMap
import kotlin.io.path.name

/**
 * Opt-in corpus run of ANS-V003 over the real infra repo (read-only), set `ANSIBLE_INFRA_REPO`:
 * `ANSIBLE_INFRA_REPO=/path/to/ansible-infrastructure ./gradlew :plugin:test --tests '*PossiblyUndefinedCorpusTest*'`.
 * Without the variable the test does nothing.
 *
 * Structural only. The repo is never opened as a project: its Ansible files (`*.yml`, `*.yaml`, `*.j2`, `ansible.cfg`
 * and role templates) are copied into the light test project's in-memory file system, skipping `.git`, `.claude`, every
 * password, key and `.env` file by name (the rule of `AnsibleJinjaEditorCorpusTest.isSecretName`), `files/ssl` and
 * `files/ssh`, and whole-file vaults; the payload lines of `!vault` values are replaced by dummy hex of the same length
 * before anything is written. Nothing is decrypted. It prints the ANS-V003 counts per root as ERROR/WARNING and the
 * first sample hits for review (path, line, variable, kind).
 */
class PossiblyUndefinedCorpusTest : BasePlatformTestCase() {
    fun testCountsPerRoot() {
        val repo = System.getenv(InfraTestData.INFRA_REPO_ENV)?.takeIf { it.isNotBlank() }?.let(Path::of)
        if (repo == null) {
            println("PossiblyUndefinedCorpusTest skipped: ${InfraTestData.INFRA_REPO_ENV} is not set")
            return
        }
        assertTrue("$repo is not a directory", Files.isDirectory(repo))
        val started = System.nanoTime()
        val files = collect(repo)
        val base = myFixture.tempDirFixture.getFile("")!!
        WriteAction.runAndWait<Throwable> {
            for ((relative, text) in files) {
                val parent = VfsUtil.createDirectoryIfMissing(base, relative.substringBeforeLast('/', ""))
                    ?: error("cannot create the directory of $relative")
                VfsUtil.saveText(parent.createChildData(this, relative.substringAfterLast('/')), text)
            }
        }
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val copied = System.nanoTime()

        val counts = TreeMap<String, IntArray>()
        val samples = ArrayList<String>()
        val all = ArrayList<String>()
        var analysed = 0
        runReadActionBlocking {
            val workspace = AnsibleWorkspace.getInstance(project)
            VfsUtilCore.visitChildrenRecursively(
                base,
                object : VirtualFileVisitor<Unit>() {
                    override fun visitFile(file: VirtualFile): Boolean {
                        if (file.isDirectory) return true
                        val context = workspace.contextOf(file) ?: return true
                        if (context.root.detached) return true
                        val psi = PsiManager.getInstance(project).findFile(file) ?: return true
                        val findings = PossiblyUndefined(project, psi, context).findings()
                        analysed++
                        for (finding in findings) {
                            val level = AnsiblePossiblyUndefinedInspection.level(project, context.root, finding)
                            val row = counts.getOrPut(context.root.displayName) { IntArray(2) }
                            when (level) {
                                Level.ERROR -> row[0]++
                                Level.WARNING -> row[1]++
                                else -> Unit
                            }
                            val line = StringUtil.offsetToLineNumber(psi.viewProvider.contents, finding.use.nameRange.startOffset) + 1
                            val hit = "${VfsUtilCore.getRelativePath(file, base)}:$line $level ${finding.kind} ${finding.name}"
                            all += "$hit ${finding.missing.joinToString(",") { "${it.environment}/${it.host}" }}"
                            if (samples.size < SAMPLES || samples.none { it.contains(" ${finding.kind} ") }) samples += hit
                        }
                        return true
                    }
                },
            )
        }
        val total = counts.values.fold(IntArray(2)) { acc, row -> intArrayOf(acc[0] + row[0], acc[1] + row[1]) }
        println(
            "PossiblyUndefinedCorpusTest: ${files.size} files copied in ${(copied - started) / 1_000_000} ms, $analysed analysed in " +
                "${(System.nanoTime() - copied) / 1_000_000} ms; ANS-V003 ERROR ${total[0]}, WARNING ${total[1]}",
        )
        for ((root, row) in counts) println("  $root: ERROR ${row[0]}, WARNING ${row[1]}")
        println("  samples:\n    " + samples.take(SAMPLES + 3).joinToString("\n    "))
        // the full list (paths, lines, names and host names only) for review, next to the other corpus reports
        val report = Path.of("build", "possibly-undefined-corpus.txt").toAbsolutePath()
        Files.createDirectories(report.parent)
        Files.writeString(report, all.sorted().joinToString("\n", postfix = "\n"))
        println("  full list: $report")
        assertTrue("expected Ansible files", analysed > 0)
    }

    /** The Ansible text files of [root] as (relative path, sanitised text). */
    private fun collect(root: Path): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir == root) return FileVisitResult.CONTINUE
                val skip = dir.name in SKIPPED_DIRS || dir.name in KEY_DIRS && dir.parent?.name == "files"
                return if (skip) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val name = file.name
                if (!attrs.isRegularFile || isSecretName(name)) return FileVisitResult.CONTINUE
                val relative = root.relativize(file).toString().replace('\\', '/')
                val wanted = name == "ansible.cfg" || EXTENSIONS.any { name.endsWith(it) } || isUnderRoleTemplates(relative)
                if (!wanted || attrs.size() > MAX_SIZE) return FileVisitResult.CONTINUE
                val text = String(Files.readAllBytes(file), Charsets.UTF_8)
                if (text.trimStart().startsWith(VAULT_HEADER)) return FileVisitResult.CONTINUE
                result += relative to dummyVaultPayloads(text)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        return result.sortedBy { it.first }
    }

    /** Every hex payload line after a `$ANSIBLE_VAULT` header line, replaced by dummy hex of the same length. */
    private fun dummyVaultPayloads(text: String): String {
        if (VAULT_HEADER !in text) return text
        val lines = text.split('\n').toMutableList()
        var inVault = false
        var indent = -1
        for (i in lines.indices) {
            val line = lines[i]
            val trimmed = line.trimStart()
            val lineIndent = line.length - trimmed.length
            when {
                trimmed.startsWith(VAULT_HEADER) -> {
                    inVault = true
                    indent = lineIndent
                }
                inVault && trimmed.isNotEmpty() && lineIndent == indent && trimmed.all { it.isLetterOrDigit() } -> {
                    lines[i] = line.substring(0, lineIndent) + InfraTestData.dummyHex(trimmed.length)
                }
                else -> inVault = false
            }
        }
        return lines.joinToString("\n")
    }

    /** `AnsibleJinjaEditorCorpusTest.isSecretName`: password, key, certificate, identity and `.env` files are never read. */
    private fun isSecretName(name: String): Boolean {
        val lower = name.lowercase().removeSuffix(".j2")
        return lower.startsWith(".vault") || lower.startsWith(".env") || SECRET_PARTS.any { it in lower } ||
            SECRET_SUFFIXES.any { lower.endsWith(it) }
    }

    private fun isUnderRoleTemplates(relative: String): Boolean {
        val segments = relative.split('/')
        val templates = segments.indexOf("templates")
        return templates >= 2 && segments[templates - 2] == "roles"
    }

    private companion object {
        const val SAMPLES = 5
        const val VAULT_HEADER = "\$ANSIBLE_VAULT"
        const val MAX_SIZE = 2L * 1024 * 1024
        val EXTENSIONS = listOf(".yml", ".yaml", ".j2")
        val SKIPPED_DIRS = setOf(".git", ".claude", "node_modules")
        val KEY_DIRS = setOf("ssl", "ssh")
        val SECRET_SUFFIXES = listOf(".password", ".pass", ".key", ".pem", ".pub", ".p12", ".pfx", ".crt", ".jks")
        val SECRET_PARTS = listOf("vault-pass", "vault_pass", "key-file", "keyfile", "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519")
    }
}
