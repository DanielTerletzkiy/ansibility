package de.terletzkiy.ansibility.yaml

import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import org.jetbrains.yaml.YAMLLanguage
import org.jetbrains.yaml.psi.YAMLFile
import java.io.File
import java.security.MessageDigest

/**
 * Opt-in: runs the adapter over every YAML file of an infra checkout named by `ANSIBLE_INFRA_REPO` (read only;
 * the test passes without doing anything when the variable is unset).
 *
 * Every file must convert, with ranges inside the file and each scalar's range ending in its source spelling.
 * When `ANSIBILITY_PYYAML_HASHES` names a file written by `golden/generate.py --hashes`, every file's dump must
 * also hash to what PyYAML produced (all 3791 files of the repo agreed when this test was written).
 */
class PsiYValueAdapterInfraCorpusTest : BasePlatformTestCase() {
    private val skippedDirs = setOf(".git", ".claude", ".ansible", "node_modules", "patches")

    fun testEveryYamlFileConvertsConsistently() {
        val repo = System.getenv("ANSIBLE_INFRA_REPO")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val problems = ArrayList<String>()
        val files = yamlFiles(repo)
        for (file in files) {
            val relative = file.relativeTo(repo).path
            val text = file.readText().replace("\r\n", "\n")
            val value = PsiYValueAdapter.documentValue(parse(file.name, text))
            value?.let { checkRanges(it, text, relative, problems) }
        }
        assertTrue("no YAML files under $repo", files.isNotEmpty())
        assertEquals(emptyList<String>(), problems.take(20))
    }

    fun testDumpsMatchPyYamlHashes() {
        val repo = System.getenv("ANSIBLE_INFRA_REPO")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val hashes = System.getenv("ANSIBILITY_PYYAML_HASHES")?.let(::File)?.takeIf { it.isFile } ?: return
        val mismatches = ArrayList<String>()
        for (line in hashes.readLines().filter { it.isNotBlank() }) {
            val (relative, expected) = line.split('\t', limit = 2)
            if (expected == "unparsable") continue
            val text = File(repo, relative).readText().replace("\r\n", "\n")
            val dump = YValueDump.render(PsiYValueAdapter.documentValue(parse(File(relative).name, text)))
            if (sha1(dump) != expected) mismatches += relative
        }
        assertEquals(emptyList<String>(), mismatches.take(20))
    }

    private fun parse(name: String, text: String): YAMLFile =
        PsiFileFactory.getInstance(project).createFileFromText(name, YAMLLanguage.INSTANCE, text) as YAMLFile

    private fun yamlFiles(repo: File): List<File> =
        repo.walkTopDown()
            .onEnter { it == repo || it.name !in skippedDirs }
            .filter { it.isFile && (it.name.endsWith(".yml") || it.name.endsWith(".yaml")) }
            .sortedBy { it.path }
            .toList()

    private fun checkRanges(value: YValue, text: String, file: String, problems: MutableList<String>) {
        val range = value.range
        if (range != null && (range.start < 0 || range.end > text.length || range.start > range.end)) {
            problems += "$file: range $range outside the file"
            return
        }
        when (value) {
            is YScalar -> if (range != null && !text.substring(range.start, range.end).endsWith(value.sourceText)) {
                problems += "$file: scalar at ${range.start} does not end with its source text"
            }
            is YSeq -> value.items.forEach { checkRanges(it, text, file, problems) }
            is YMap -> value.entries.forEach {
                checkRanges(it.key, text, file, problems)
                checkRanges(it.value, text, file, problems)
            }
            else -> Unit
        }
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
