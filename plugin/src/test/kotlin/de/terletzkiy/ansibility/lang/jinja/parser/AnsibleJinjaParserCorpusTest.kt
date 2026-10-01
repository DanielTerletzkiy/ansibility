package de.terletzkiy.ansibility.lang.jinja.parser

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.WriteAction
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaFileType
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaLanguage
import de.terletzkiy.ansibility.lang.jinja.psi.AnsibleJinjaFile
import de.terletzkiy.ansibility.lang.jinja.template.AnsibleJinjaFileViewProvider
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
 * Opt-in corpus run of the parser over the real infra repo (M5 acceptance 8): set `ANSIBLE_INFRA_REPO`, e.g.
 * `ANSIBLE_INFRA_REPO=/path/to/ansible-infrastructure ./gradlew :plugin:test --tests '*AnsibleJinjaParserCorpusTest*'`.
 * The repo is only read; without the variable the test does nothing.
 *
 * Parses every `*.j2` and every Jinja-bearing file under `roles/<role>/templates` (`.git` and `.claude` skipped) as a
 * template file, and every Jinja fragment of the YAML files as the injector will: scalars with `{{`/`{%` as templates,
 * implicit-expression values wrapped in `{{ ` and ` }}`. It requires zero Jinja `PsiErrorElement`s, reports how many
 * outer-language syntax errors the template view provider hides, and highlights every template as a role template of
 * the light project: zero visible errors (M5 acceptance 2: the outer-language noise is filtered).
 */
class AnsibleJinjaParserCorpusTest : BasePlatformTestCase() {
    private class Stats {
        var templates = 0
        var templatesWithoutJ2 = 0
        var templateNanos = 0L
        var yamlTemplates = 0
        var hiddenOuterErrors = 0
        var templatesWithHiddenOuterErrors = 0
        var fragments = 0
        var expressions = 0
        var highlighted = 0
        var highlightNanos = 0L
        val problems = ArrayList<String>()
        val visibleErrors = ArrayList<String>()
    }

    fun testInfraRepoParsesWithoutErrors() {
        val repo = System.getenv(ENV_VAR)?.takeIf { it.isNotBlank() }?.let(Path::of)
        if (repo == null) {
            println("AnsibleJinjaParserCorpusTest skipped: $ENV_VAR is not set")
            return
        }
        assertTrue("$ENV_VAR=$repo is not a directory", Files.isDirectory(repo))
        val started = System.nanoTime()
        val stats = Stats()
        val templates = ArrayList<Pair<String, String>>()
        for (file in corpusFiles(repo)) {
            val relative = repo.relativize(file).toString()
            val name = file.name
            when {
                name.endsWith(".j2") -> templates += relative to read(file)
                name.endsWith(".yml") || name.endsWith(".yaml") -> yaml(relative, read(file), stats)
                isUnderRoleTemplates(relative) -> read(file).takeIf { "{{" in it || "{%" in it || "{#" in it }?.let {
                    stats.templatesWithoutJ2++
                    templates += relative to it
                }
            }
        }
        for ((relative, text) in templates) template(relative, text, stats)
        // Visible errors as the editor shows them: each template as a role template of the light project.
        myFixture.tempDirFixture.createFile("roles/corpus/tasks/main.yml", "- ansible.builtin.debug:\n    msg: corpus\n")
        for ((index, entry) in templates.withIndex()) highlight(index, entry.first, entry.second, stats)
        println(
            "AnsibleJinjaParserCorpusTest: ${stats.templates} templates (${stats.templatesWithoutJ2} without .j2) parsed in " +
                "${stats.templateNanos / 1_000_000} ms; ${stats.fragments} YAML template fragments and ${stats.expressions} " +
                "implicit expressions; Jinja errors ${stats.problems.size}; hidden outer-language errors " +
                "${stats.hiddenOuterErrors} in ${stats.templatesWithHiddenOuterErrors} of ${stats.yamlTemplates} YAML-outer " +
                "templates; visible errors ${stats.visibleErrors.size} in ${stats.highlighted} highlighted templates " +
                "(${stats.highlightNanos / 1_000_000} ms); total ${(System.nanoTime() - started) / 1_000_000} ms",
        )
        assertTrue("expected a corpus, found ${stats.templates} templates", stats.templates > 0)
        assertEmpty(stats.problems.take(50).joinToString("\n"), stats.problems)
        assertEmpty(stats.visibleErrors.take(50).joinToString("\n"), stats.visibleErrors)
    }

    /** Highlights [text] as `roles/corpus/templates/<index>/<name>` and records every visible error. */
    private fun highlight(index: Int, relative: String, text: String, stats: Stats) {
        val started = System.nanoTime()
        val file = myFixture.tempDirFixture.createFile("roles/corpus/templates/$index/${relative.substringAfterLast('/')}", text)
        try {
            if (file.fileType != AnsibleJinjaFileType) {
                stats.visibleErrors += "$relative: not typed as Ansible Jinja (${file.fileType.name})"
                return
            }
            myFixture.configureFromExistingVirtualFile(file)
            for (info in myFixture.doHighlighting(HighlightSeverity.ERROR)) {
                val context = text.substring(maxOf(0, info.startOffset - 30), minOf(text.length, info.endOffset + 30)).replace("\n", "\\n")
                stats.visibleErrors += "$relative: ${info.description} at <$context>"
            }
            stats.highlighted++
        } finally {
            WriteAction.runAndWait<Throwable> { file.parent.delete(this) }
            stats.highlightNanos += System.nanoTime() - started
        }
    }

    private fun template(relative: String, text: String, stats: Stats) {
        stats.templates++
        val started = System.nanoTime()
        val file = parse(relative.substringAfterLast('/'), text)
        file.node.firstChildNode // parses the Jinja tree (the file node itself is lazy)
        stats.templateNanos += System.nanoTime() - started
        errors(relative, text, 0, file, stats)
        val provider = file.viewProvider as? AnsibleJinjaFileViewProvider ?: return
        if (provider.templateDataLanguage == YAMLLanguage.INSTANCE) {
            stats.yamlTemplates++
            val outer = PsiTreeUtil.findChildrenOfType(provider.getPsi(YAMLLanguage.INSTANCE), PsiErrorElement::class.java).size
            stats.hiddenOuterErrors += outer
            if (outer > 0) stats.templatesWithHiddenOuterErrors++
        }
    }

    private fun yaml(relative: String, text: String, stats: Stats) {
        val file = PsiFileFactory.getInstance(project).createFileFromText("corpus.yml", YAMLLanguage.INSTANCE, text)
        for (scalar in PsiTreeUtil.findChildrenOfType(file, YAMLScalar::class.java)) {
            if (scalar.tag?.text == "!vault") continue
            val value = scalar.textValue
            val line = text.substring(0, scalar.textRange.startOffset).count { it == '\n' } + 1
            when {
                "{{" in value || "{%" in value -> {
                    stats.fragments++
                    errors("$relative:$line", value, 0, parse("fragment.j2", value), stats)
                }
                isImplicitExpression(scalar) -> {
                    stats.expressions++
                    val wrapped = "{{ $value }}"
                    errors("$relative:$line", wrapped, 3, parse("expression.j2", wrapped), stats)
                }
            }
        }
    }

    private fun parse(name: String, text: String): PsiFile =
        PsiFileFactory.getInstance(project).createFileFromText(name, AnsibleJinjaLanguage, text)

    private fun errors(where: String, text: String, prefix: Int, file: PsiFile, stats: Stats) {
        assertInstanceOf(file, AnsibleJinjaFile::class.java)
        for (error in PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java)) {
            val offset = error.textRange.startOffset
            val context = text.substring(maxOf(prefix, offset - 40), minOf(text.length, offset + 40)).replace("\n", "\\n")
            stats.problems += "$where: ${error.errorDescription} at <$context>"
        }
    }

    private fun isImplicitExpression(scalar: YAMLScalar): Boolean {
        val keyValue = when (val parent = scalar.parent) {
            is YAMLKeyValue -> parent
            is YAMLSequenceItem -> parent.parent?.parent as? YAMLKeyValue
            else -> null
        } ?: return false
        val key = keyValue.keyText
        if (key in IMPLICIT_KEYS) return true
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
                if (attrs.isRegularFile && !isSecretName(file.name)) files.add(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        return files.sorted()
    }

    /** Never opens secret-bearing files (DEV.md rule 2), matching the editor corpus test. */
    private fun isSecretName(name: String): Boolean {
        val lower = name.lowercase().removeSuffix(".j2")
        return lower.startsWith(".vault") || lower.startsWith(".env") || SECRET_PARTS.any { it in lower } ||
            SECRET_SUFFIXES.any { lower.endsWith(it) }
    }

    private companion object {
        const val ENV_VAR = "ANSIBLE_INFRA_REPO"
        val SKIPPED_DIRS = setOf(".git", ".claude")
        val SECRET_SUFFIXES = listOf(".password", ".pass", ".key", ".pem", ".pub", ".p12", ".pfx", ".crt", ".jks")
        val SECRET_PARTS = listOf("vault-pass", "vault_pass", "key-file", "keyfile", "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519")
        val IMPLICIT_KEYS = setOf("when", "changed_when", "failed_when", "until", "that")
        val DEBUG_MODULES = setOf("debug", "ansible.builtin.debug", "ansible.legacy.debug")
    }
}
