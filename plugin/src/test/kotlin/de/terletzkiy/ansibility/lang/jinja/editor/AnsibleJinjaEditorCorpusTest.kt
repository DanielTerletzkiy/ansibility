package de.terletzkiy.ansibility.lang.jinja.editor

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaFileType
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaLanguage
import de.terletzkiy.ansibility.lang.jinja.editor.coexist.AnsiblePlaybookYamlOverrider
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes
import de.terletzkiy.ansibility.lang.jinja.psi.AnsibleJinjaFile
import de.terletzkiy.ansibility.lang.jinja.template.AnsibleJinjaFileViewProvider
import de.terletzkiy.ansibility.settings.JinjaSettings
import org.jetbrains.yaml.inspections.YAMLDuplicatedKeysInspection
import org.jetbrains.yaml.inspections.YAMLRecursiveAliasInspection
import org.jetbrains.yaml.inspections.YAMLUnresolvedAliasInspection
import org.jetbrains.yaml.inspections.YAMLUnusedAnchorInspection
import org.jetbrains.yaml.psi.YAMLMapping
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.TreeMap
import kotlin.io.path.name

/**
 * Opt-in corpus run of the editor features over the real infra repo (read-only): set `ANSIBLE_INFRA_REPO`, e.g.
 * `ANSIBLE_INFRA_REPO=/path/to/ansible-infrastructure ./gradlew :plugin:test --tests '*AnsibleJinjaEditorCorpusTest*'`.
 * Without the variable the tests do nothing.
 *
 * Over every template (`*.j2` and Jinja-bearing files under `roles/<role>/templates`; `.git` and `.claude` skipped, and
 * never a password, key or `.env` file: those are not read at all):
 * - every block kind is balanced by [JinjaTagScanner] (so Enter never inserts an end tag a real template already has);
 * - the folding regions are valid;
 * - every template with a real outer language (YAML and JSON in the test IDE), highlighted as a role template with the
 *   YAML inspections on, shows no error and no outer-language warning (M5 acceptance 2: the outer-language noise is
 *   hidden; the parser corpus test covers the Jinja errors of all templates). It reports how many outer-language
 *   syntax errors there were to hide.
 */
class AnsibleJinjaEditorCorpusTest : BasePlatformTestCase() {
    fun testEditorFeaturesOnInfraRepo() {
        val repo = infraRepo() ?: return
        myFixture.enableInspections(
            YAMLDuplicatedKeysInspection::class.java,
            YAMLUnresolvedAliasInspection::class.java,
            YAMLRecursiveAliasInspection::class.java,
            YAMLUnusedAnchorInspection::class.java,
        )
        val started = System.nanoTime()
        val templates = templates(repo)
        val unbalanced = ArrayList<String>()
        val badFolds = ArrayList<String>()
        var tags = 0
        var folds = 0
        val realOuter = ArrayList<Pair<String, String>>()
        val byOuterLanguage = TreeMap<String, Int>()
        var outerSyntaxErrors = 0
        var duplicateKeys = 0
        for ((relative, text) in templates) {
            val scanned = JinjaTagScanner.tags(text, 0, text.length)
            tags += scanned.size
            for (kind in JinjaBlockKind.entries) {
                val open = JinjaTagScanner.unclosed(scanned, kind)
                if (open > 0) unbalanced += "$relative: $open unclosed ${kind.name.lowercase()}"
            }
            val file = PsiFileFactory.getInstance(project).createFileFromText(relative.substringAfterLast('/'), AnsibleJinjaLanguage, text)
            val provider = file.viewProvider as? AnsibleJinjaFileViewProvider ?: error("$relative: not a template view provider")
            val outer = provider.templateDataLanguage
            byOuterLanguage.merge(outer.displayName, 1, Int::plus)
            if (outer != PlainTextLanguage.INSTANCE) {
                realOuter += relative to text
                val outerTree = provider.getPsi(outer)
                outerSyntaxErrors += PsiTreeUtil.findChildrenOfType(outerTree, PsiErrorElement::class.java).size
                duplicateKeys += PsiTreeUtil.findChildrenOfType(outerTree, YAMLMapping::class.java).sumOf { mapping ->
                    mapping.keyValues.groupBy { it.keyText }.values.sumOf { it.size - 1 }
                }
            }
            val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: error("no document for $relative")
            for (descriptor in AnsibleJinjaFoldingBuilder().buildFoldRegions(file as AnsibleJinjaFile, document, false)) {
                folds++
                val range = descriptor.range
                if (range.isEmpty || range.endOffset > text.length) badFolds += "$relative: $range"
            }
        }
        myFixture.tempDirFixture.createFile("roles/corpus/tasks/main.yml", "- ansible.builtin.debug:\n    msg: corpus\n")
        val visible = ArrayList<String>()
        var highlightNanos = 0L
        for ((index, entry) in realOuter.withIndex()) {
            val highlightStarted = System.nanoTime()
            visible += highlight(index, entry.first, entry.second)
            highlightNanos += System.nanoTime() - highlightStarted
        }
        println(
            "AnsibleJinjaEditorCorpusTest: ${templates.size} templates, $tags tags, ${unbalanced.size} unbalanced block kinds, " +
                "$folds folding regions (${badFolds.size} invalid); outer languages $byOuterLanguage; ${realOuter.size} templates with a " +
                "real outer language highlighted with the YAML inspections in ${highlightNanos / 1_000_000} ms: $outerSyntaxErrors " +
                "outer-language syntax errors and $duplicateKeys duplicate YAML keys to hide, ${visible.size} visible problems; total " +
                "${(System.nanoTime() - started) / 1_000_000} ms",
        )
        assertTrue("expected a corpus", templates.isNotEmpty())
        assertEmpty(unbalanced.take(30).joinToString("\n"), unbalanced)
        assertEmpty(badFolds.take(30).joinToString("\n"), badFolds)
        assertEmpty(visible.take(30).joinToString("\n"), visible)
    }

    /**
     * X05 on the real layout: every `*-playbook.y*ml` docker-compose file (the eight `repos/<team>/docker-compose.ansible-playbook.yaml`)
     * keeps YAML. Only names and directory listings are read.
     */
    fun testPlaybookComposeFilesOnInfraRepo() {
        val repo = infraRepo() ?: return
        val composeFiles = ArrayList<Path>()
        Files.walkFileTree(repo, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir != repo && dir.name in SKIPPED_DIRS) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val name = file.name
                if (attrs.isRegularFile && AnsiblePlaybookYamlOverrider.isPlaybookName(name) && AnsiblePlaybookYamlOverrider.isComposeName(name)) {
                    composeFiles.add(file) // `+=` would add the path's name segments (a Path is Iterable<Path>)
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        VfsRootAccess.allowRootAccess(testRootDisposable, repo.toString())
        val notKept = composeFiles.filterNot { path ->
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: error("not in the VFS: $path")
            AnsiblePlaybookYamlOverrider.keepsYaml(file, JinjaSettings())
        }.map { repo.relativize(it).toString() }
        println("AnsibleJinjaEditorCorpusTest: ${composeFiles.size} *-playbook.y*ml compose files, ${composeFiles.size - notKept.size} keep YAML")
        assertTrue("expected the docker-compose.ansible-playbook.yaml files", composeFiles.isNotEmpty())
        assertEmpty(notKept.joinToString("\n"), notKept)
    }

    /** The infra repo of [InfraTestData.INFRA_REPO_ENV], or null (the test is skipped). */
    private fun infraRepo(): Path? {
        val repo = System.getenv(InfraTestData.INFRA_REPO_ENV)?.takeIf { it.isNotBlank() }?.let(Path::of)
        if (repo == null) {
            println("AnsibleJinjaEditorCorpusTest skipped: ${InfraTestData.INFRA_REPO_ENV} is not set")
            return null
        }
        assertTrue("$repo is not a directory", Files.isDirectory(repo))
        return repo
    }

    /** The visible problems of [text] highlighted as `roles/corpus/templates/<index>/<name>`. */
    private fun highlight(index: Int, relative: String, text: String): List<String> {
        val file = myFixture.tempDirFixture.createFile("roles/corpus/templates/$index/${relative.substringAfterLast('/')}", text)
        try {
            if (file.fileType != AnsibleJinjaFileType) return listOf("$relative: not typed as Ansible Jinja (${file.fileType.name})")
            myFixture.configureFromExistingVirtualFile(file)
            val jinja = myFixture.file.viewProvider.getPsi(AnsibleJinjaLanguage) as AnsibleJinjaFile
            return myFixture.doHighlighting().filter { info ->
                val outer = jinja.node.findLeafElementAt(info.startOffset)?.elementType in AnsibleJinjaTokenTypes.OUTER_TEXT
                info.severity >= HighlightSeverity.ERROR || info.severity >= HighlightSeverity.WEAK_WARNING && outer
            }.map { info ->
                val context = text.substring(maxOf(0, info.startOffset - 30), minOf(text.length, info.endOffset + 30)).replace("\n", "\\n")
                "$relative: ${info.severity} ${info.description} at <$context>"
            }
        } finally {
            WriteAction.runAndWait<Throwable> { file.parent.delete(this) }
        }
    }

    /** The templates of [root] as (relative path, text). */
    private fun templates(root: Path): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir != root && dir.name in SKIPPED_DIRS) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isRegularFile || isSecretName(file.name)) return FileVisitResult.CONTINUE
                val relative = root.relativize(file).toString()
                val text = { String(Files.readAllBytes(file), Charsets.UTF_8) }
                when {
                    file.name.endsWith(".j2") -> result += relative to text()
                    isUnderRoleTemplates(relative) -> text().takeIf { "{{" in it || "{%" in it || "{#" in it }?.let { result += relative to it }
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        return result.sortedBy { it.first }
    }

    /**
     * Names of secret or key files, which a corpus run never opens (DEV.md rule 2): vault password files, `.env` files
     * (`.env.local`), `*.password`, keys and certificates (public keys too), SSH identities, `key-file`s.
     */
    private fun isSecretName(name: String): Boolean {
        val lower = name.lowercase().removeSuffix(".j2")
        return lower.startsWith(".vault") || lower.startsWith(".env") || SECRET_PARTS.any { it in lower } ||
            SECRET_SUFFIXES.any { lower.endsWith(it) }
    }

    private fun isUnderRoleTemplates(relative: String): Boolean {
        val segments = relative.split('/', '\\')
        val templates = segments.indexOf("templates")
        return templates >= 2 && segments[templates - 2] == "roles"
    }

    private companion object {
        val SKIPPED_DIRS = setOf(".git", ".claude")
        val SECRET_SUFFIXES = listOf(".password", ".pass", ".key", ".pem", ".pub", ".p12", ".pfx", ".crt", ".jks")
        val SECRET_PARTS = listOf("vault-pass", "vault_pass", "key-file", "keyfile", "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519")
    }
}
