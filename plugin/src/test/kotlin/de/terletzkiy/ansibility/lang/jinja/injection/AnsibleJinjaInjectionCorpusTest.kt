package de.terletzkiy.ansibility.lang.jinja.injection

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.lang.jinja.psi.AnsibleJinjaFile
import org.jetbrains.yaml.psi.YAMLScalar
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.name

/**
 * Opt-in corpus run of the YAML injector over the real infra repo (M5 acceptance 8, "every Jinja YAML fragment"): set
 * `ANSIBLE_INFRA_REPO`, e.g. `ANSIBLE_INFRA_REPO=/path/to/ansible-infrastructure ./gradlew :plugin:test --tests
 * '*AnsibleJinjaInjectionCorpusTest*'`. Without the variable the test does nothing.
 *
 * Every YAML file and `ansible.cfg` of the repo (`.git` and `.claude` skipped) is copied into the light project's
 * in-memory directory, so the roots and file kinds are the real ones, and the fragments are the ones the injector
 * really injects. It requires zero Jinja `PsiErrorElement`s in them and reports the counts. The repo is only read; the
 * test is structural (vault payloads are never decrypted, and no fragment text is printed unless it has an error).
 */
class AnsibleJinjaInjectionCorpusTest : JinjaInjectionTestCase() {
    fun testEveryInjectedFragmentParsesWithoutErrors() {
        val repo = System.getenv(InfraTestData.INFRA_REPO_ENV)?.takeIf { it.isNotBlank() }?.let(Path::of)
        if (repo == null) {
            println("AnsibleJinjaInjectionCorpusTest skipped: ${InfraTestData.INFRA_REPO_ENV} is not set")
            return
        }
        assertTrue("$repo is not a directory", Files.isDirectory(repo))
        val started = System.nanoTime()
        val sources = corpusFiles(repo)
        val base = myFixture.tempDirFixture.getFile("")!!
        val copied = WriteAction.computeAndWait<List<VirtualFile>, IOException> {
            sources.map { source ->
                val relative = repo.relativize(source).toString().replace('\\', '/')
                val dir = VfsUtil.createDirectoryIfMissing(base, relative.substringBeforeLast('/', ""))
                    ?: error("cannot create the directory of $relative")
                dir.createChildData(this, source.name).also { it.setBinaryContent(Files.readAllBytes(source)) }
            }
        }
        refreshRoots()
        val copiedNanos = System.nanoTime() - started

        val manager = InjectedLanguageManager.getInstance(project)
        var yamlFiles = 0
        var filesWithFragments = 0
        var templates = 0
        var expressions = 0
        val errors = ArrayList<String>()
        val injecting = System.nanoTime()
        for (file in copied) {
            if (file.name == "ansible.cfg") continue
            yamlFiles++
            val relative = file.path.removePrefix(base.path).removePrefix("/")
            val fragments = inBackgroundReadAction {
                val psi = PsiManager.getInstance(project).findFile(file) ?: return@inBackgroundReadAction emptyList()
                PsiTreeUtil.findChildrenOfType(psi, YAMLScalar::class.java).flatMap { scalar ->
                    manager.getInjectedPsiFiles(scalar).orEmpty().map { it.first }.distinct().mapNotNull { element ->
                        val injected = element as? AnsibleJinjaFile ?: return@mapNotNull null
                        val problems = PsiTreeUtil.findChildrenOfType(injected, PsiErrorElement::class.java).map { error ->
                            val text = injected.text
                            val at = error.textRange.startOffset
                            val line = psi.viewProvider.contents.subSequence(0, scalar.textRange.startOffset).count { it == '\n' } + 1
                            "$relative:$line: ${error.errorDescription} at <${text.substring(maxOf(0, at - 30), minOf(text.length, at + 30)).replace("\n", "\\n")}>"
                        }
                        injected.getUserData(JinjaInjectionMode.KEY) to problems
                    }
                }
            }
            if (fragments.isNotEmpty()) filesWithFragments++
            templates += fragments.count { it.first == JinjaInjectionMode.TEMPLATE }
            expressions += fragments.count { it.first == JinjaInjectionMode.EXPRESSION }
            fragments.flatMapTo(errors) { it.second }
        }
        println(
            "AnsibleJinjaInjectionCorpusTest: $yamlFiles YAML files copied in ${copiedNanos / 1_000_000} ms; " +
                "$filesWithFragments with Jinja; $templates template fragments and $expressions expression fragments " +
                "injected in ${(System.nanoTime() - injecting) / 1_000_000} ms; Jinja errors ${errors.size}",
        )
        assertTrue("expected Jinja fragments in the corpus", templates > 1000 && expressions > 1000)
        assertEmpty(errors.take(50).joinToString("\n"), errors)
    }

    /** The YAML files and `ansible.cfg` files of [root], `.git` and `.claude` skipped, sorted. */
    private fun corpusFiles(root: Path): List<Path> {
        val files = ArrayList<Path>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir != root && dir.name in SKIPPED_DIRS) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val name = file.name
                if (attrs.isRegularFile && (name.endsWith(".yml") || name.endsWith(".yaml") || name == "ansible.cfg")) files.add(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        return files.sorted()
    }

    private companion object {
        val SKIPPED_DIRS = setOf(".git", ".claude")
    }
}
