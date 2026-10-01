package de.terletzkiy.ansibility.lang.jinja

import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes
import de.terletzkiy.ansibility.lang.jinja.lexer.JinjaLexMode
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaRefs
import org.jetbrains.yaml.YAMLLanguage
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequenceItem
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.name

/**
 * Opt-in corpus run over the real infra repo: set `ANSIBLE_INFRA_REPO` to its root, e.g.
 * `ANSIBLE_INFRA_REPO=/path/to/ansible-infrastructure ./gradlew :plugin:test --tests '*AnsibleJinjaCorpusTest*' --rerun`.
 * The repo is only read. Without the variable the test does nothing.
 *
 * It lexes every `*.j2` (and every Jinja-bearing file under `roles/<role>/templates` without `.j2`) in template mode,
 * and every YAML scalar with `{{`/`{%` in template mode, or the value of an implicit-expression key (`when`,
 * `changed_when`, `failed_when`, `until`, `that`, `debug.var`) in expression mode. It requires zero
 * [AnsibleJinjaTokenTypes.BAD_CHARACTER] tokens and zero exceptions from the lexer and from [JinjaRefs].
 */
class AnsibleJinjaCorpusTest : BasePlatformTestCase() {
    private class Stats {
        var templateFiles = 0
        var templatesWithoutJ2 = 0
        var templateTokens = 0L
        var yamlFiles = 0
        var templateScalars = 0
        var expressionScalars = 0
        var yamlTokens = 0L
        var references = 0L
        var lexNanos = 0L
        val problems = ArrayList<String>()
    }

    fun testInfraRepoLexesWithoutBadCharacters() {
        val repo = System.getenv(ENV_VAR)?.takeIf { it.isNotBlank() }?.let(Path::of)
        if (repo == null) {
            println("AnsibleJinjaCorpusTest skipped: $ENV_VAR is not set")
            return
        }
        assertTrue("$ENV_VAR=$repo is not a directory", Files.isDirectory(repo))
        val started = System.nanoTime()
        val stats = Stats()
        for (file in corpusFiles(repo)) {
            val relative = repo.relativize(file).toString()
            val name = file.name
            when {
                name.endsWith(".j2") -> template(relative, read(file), stats)
                name.endsWith(".yml") || name.endsWith(".yaml") -> yaml(relative, read(file), stats)
                isUnderRoleTemplates(relative) -> read(file).takeIf { "{{" in it || "{%" in it }?.let {
                    stats.templatesWithoutJ2++
                    template(relative, it, stats)
                }
            }
        }
        val totalMillis = (System.nanoTime() - started) / 1_000_000
        println(
            "AnsibleJinjaCorpusTest: ${stats.templateFiles} template files (${stats.templatesWithoutJ2} without .j2; " +
                "${stats.templateTokens} tokens), " +
                "${stats.yamlFiles} YAML files with ${stats.templateScalars} template scalars and " +
                "${stats.expressionScalars} expression scalars (${stats.yamlTokens} tokens), ${stats.references} references; " +
                "lexing+refs ${stats.lexNanos / 1_000_000} ms, total ${totalMillis} ms, problems ${stats.problems.size}",
        )
        assertTrue("expected a corpus, found ${stats.templateFiles} templates", stats.templateFiles > 0)
        assertEmpty(stats.problems.take(50).joinToString("\n"), stats.problems)
    }

    private fun template(relative: String, text: String, stats: Stats) {
        stats.templateFiles++
        stats.templateTokens += check(relative, text, JinjaLexMode.TEMPLATE, stats)
    }

    private fun yaml(relative: String, text: String, stats: Stats) {
        stats.yamlFiles++
        val file = PsiFileFactory.getInstance(project).createFileFromText("corpus.yml", YAMLLanguage.INSTANCE, text)
        for (scalar in PsiTreeUtil.findChildrenOfType(file, YAMLScalar::class.java)) {
            if (scalar.tag?.text == "!vault") continue
            val value = scalar.textValue
            val jinja = "{{" in value || "{%" in value
            val mode = when {
                jinja -> JinjaLexMode.TEMPLATE
                isImplicitExpression(scalar) -> JinjaLexMode.EXPRESSION
                else -> continue
            }
            if (mode == JinjaLexMode.TEMPLATE) stats.templateScalars++ else stats.expressionScalars++
            val line = text.substring(0, scalar.textRange.startOffset).count { it == '\n' } + 1
            stats.yamlTokens += check("$relative:$line", value, mode, stats)
        }
    }

    /** Lexes and analyses [text]; returns the token count and records bad characters and exceptions. */
    private fun check(where: String, text: String, mode: JinjaLexMode, stats: Stats): Int {
        val started = System.nanoTime()
        try {
            val tokens = lex(text, mode)
            for (bad in tokens.filter { it.type == AnsibleJinjaTokenTypes.BAD_CHARACTER }) {
                val context = text.substring(maxOf(0, bad.start - 30), minOf(text.length, bad.end + 30)).replace("\n", "\\n")
                stats.problems += "$where: BAD_CHARACTER '${text.substring(bad.start, bad.end)}' in <$context> ($mode)"
            }
            val result = JinjaRefs.analyze(text, mode)
            stats.references += result.references.size + result.localReferences.size
            return tokens.size
        } catch (e: Exception) {
            stats.problems += "$where: ${e.javaClass.simpleName}: ${e.message} ($mode)"
            return 0
        } finally {
            stats.lexNanos += System.nanoTime() - started
        }
    }

    private fun isImplicitExpression(scalar: YAMLScalar): Boolean {
        val keyValue = when (val parent = scalar.parent) {
            is YAMLKeyValue -> parent
            // `that:` and `when:` lists
            is YAMLSequenceItem -> parent.parent?.parent as? YAMLKeyValue
            else -> null
        } ?: return false
        val key = keyValue.keyText
        if (key in IMPLICIT_KEYS) return true
        // `debug: var: …`
        val module = keyValue.parentMapping?.parent as? YAMLKeyValue
        return key == "var" && module?.keyText in DEBUG_MODULES
    }

    private fun isUnderRoleTemplates(relative: String): Boolean {
        val segments = relative.split('/', '\\')
        val templates = segments.indexOf("templates")
        return templates >= 2 && segments[templates - 2] == "roles"
    }

    private fun read(file: Path): String = String(Files.readAllBytes(file), Charsets.UTF_8)

    private fun corpusFiles(root: Path): List<Path> {
        val files = ArrayList<Path>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir != root && dir.name in SKIPPED_DIRS) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile) files.add(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        return files.sorted()
    }

    private companion object {
        const val ENV_VAR = "ANSIBLE_INFRA_REPO"
        val SKIPPED_DIRS = setOf(".git", ".claude")
        val IMPLICIT_KEYS = setOf("when", "changed_when", "failed_when", "until", "that")
        val DEBUG_MODULES = setOf("debug", "ansible.builtin.debug", "ansible.legacy.debug")
    }
}
