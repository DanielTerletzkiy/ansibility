package de.terletzkiy.ansibility.index

import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaIndirection
import org.jetbrains.yaml.psi.YAMLScalar
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.TreeMap
import kotlin.io.path.name

/**
 * Opt-in corpus count of what FU2 adds to `ansible.var.use` on the real infra repo (read-only), set
 * `ANSIBLE_INFRA_REPO`: `ANSIBLE_INFRA_REPO=/path/to/ansible-infrastructure ./gradlew :plugin:test
 * --tests '*VarUseIndirectCorpusTest*'`. Without the variable the test does nothing.
 *
 * Structural only: [VarUseIndexer] runs on file texts in memory, as in [IndexMeasurementTest]. `.git`, `.claude` and
 * `node_modules`, every password, key, certificate, identity and `.env` file by name (the rule of
 * `AnsibleJinjaEditorCorpusTest.isSecretName`), vault-named files and whole-file vaults are never read; nothing is
 * decrypted, and only paths, line numbers and variable names are printed. The inventory report counted 77 `hostvars`
 * members plus 1 `extract`, no `vars` reads and 33 names outside the braces of braced implicit expressions
 * (research `find-usages-inventory.md`, U14, U14b, U14c and U10b).
 */
class VarUseIndirectCorpusTest : BasePlatformTestCase() {
    private class Stats {
        var files = 0
        val hostvars = TreeMap<String, Int>()
        val vars = TreeMap<String, Int>()
        var braced = 0
        val samples = ArrayList<String>()

        val hostvarsTotal: Int get() = hostvars.values.sum()
        val varsTotal: Int get() = vars.values.sum()

        override fun toString() =
            "$files files: hostvars members $hostvarsTotal $hostvars, vars reads $varsTotal $vars, names outside braces $braced"
    }

    fun testIndirectAndBracedCountsOfTheRealRepo() {
        val repo = System.getenv(InfraTestData.INFRA_REPO_ENV)?.takeIf { it.isNotBlank() }?.let(Path::of)
        if (repo == null) {
            println("VarUseIndirectCorpusTest skipped: ${InfraTestData.INFRA_REPO_ENV} is not set")
            return
        }
        assertTrue("$repo is not a directory", Files.isDirectory(repo))
        val stats = Stats()
        for (file in corpusFiles(repo)) {
            val path = file.toAbsolutePath().toString()
            val facts = PathFacts.of(path)
            if (!AnsibleIndexInputFilter.accepts(facts, templates = true)) continue
            val text = readText(file) ?: continue
            if (text.trimStart().startsWith(VAULT_HEADER)) continue
            stats.files++
            val input = IndexInput.of(path, text, project)
            for ((name, entries) in VarUseIndexer.index(input)) {
                for (entry in entries) {
                    val where = "${repo.relativize(file)}:${StringUtil.offsetToLineNumber(text, entry.offset) + 1} $name"
                    when {
                        entry.indirect == JinjaIndirection.HOSTVARS -> stats.hostvars.merge(name, 1, Int::plus)
                        entry.indirect == JinjaIndirection.VARS -> stats.vars.merge(name, 1, Int::plus).also { stats.samples += "vars: $where" }
                        entry.container == UseContainer.YAML_EXPRESSION && inBracedScalar(input, entry.offset) -> {
                            stats.braced++
                            if (stats.samples.size < SAMPLES) stats.samples += "braced: $where"
                        }
                    }
                }
            }
        }
        println("VarUseIndirectCorpusTest: $stats")
        stats.samples.forEach { println("  $it") }
        assertTrue("about 78 hostvars members (77 + 1 extract): $stats", stats.hostvarsTotal in 70..95)
        assertTrue("no vars reads in the repo: $stats", stats.varsTotal <= 5)
        assertTrue("about 33 names outside the braces: $stats", stats.braced in 28..45)
    }

    /** Whether [offset] lies in a YAML scalar whose value has `{{`/`{%`: an implicit expression with braces. */
    private fun inBracedScalar(input: IndexInput, offset: Int): Boolean {
        val yaml = input.yaml ?: return false
        val scalar = PsiTreeUtil.findElementOfClassAtOffset(yaml, offset, YAMLScalar::class.java, false) ?: return false
        return JinjaBearing.hasTemplateMarkers(scalar.textValue)
    }

    private fun corpusFiles(root: Path): List<Path> {
        val result = ArrayList<Path>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir == root) return FileVisitResult.CONTINUE
                val skip = dir.name in SKIPPED_DIRS || dir.name in KEY_DIRS && dir.parent?.name == "files"
                return if (skip) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val name = file.name
                val skip = !attrs.isRegularFile || isSecretName(name) || "vault" in name.lowercase() ||
                    attrs.size() > AnsibleIndexInputFilter.MAX_FILE_SIZE
                if (!skip) result.add(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        return result.sorted()
    }

    /** `AnsibleJinjaEditorCorpusTest.isSecretName`: password, key, certificate, identity and `.env` files are never read. */
    private fun isSecretName(name: String): Boolean {
        val lower = name.lowercase().removeSuffix(".j2")
        return lower.startsWith(".vault") || lower.startsWith(".env") || SECRET_PARTS.any { it in lower } ||
            SECRET_SUFFIXES.any { lower.endsWith(it) }
    }

    /** UTF-8 text, or null for binary content. */
    private fun readText(file: Path): String? = try {
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString().takeIf { '\u0000' !in it }
    } catch (_: CharacterCodingException) {
        null
    }

    private companion object {
        const val SAMPLES = 8
        const val VAULT_HEADER = "\$ANSIBLE_VAULT"
        val SKIPPED_DIRS = setOf(".git", ".claude", "node_modules")
        val KEY_DIRS = setOf("ssl", "ssh")
        val SECRET_SUFFIXES = listOf(".password", ".pass", ".key", ".pem", ".pub", ".p12", ".pfx", ".crt", ".jks")
        val SECRET_PARTS = listOf("vault-pass", "vault_pass", "key-file", "keyfile", "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519")
    }
}
