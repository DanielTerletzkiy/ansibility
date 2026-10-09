package de.terletzkiy.ansibility.golden.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * The bundled Git plugin is optional (plan amendment R25, D202), like VCS support (`VcsIsolationTest`): classes that
 * import `git4idea` (or the auth helper's `AuthenticationMode`) live in a `git4idea/impl` package and are named only by
 * the optional fragment `ansibility-git.xml`, so the plugin loads in an IDE without the Git plugin. Gradle runs the plugin
 * tests in the `plugin` module directory.
 */
class GitIsolationTest {
    private val main: Path = Paths.get("src/main")

    private fun sources(): List<Path> = Files.walk(main.resolve("kotlin")).use { paths ->
        paths.filter { it.isRegularFile() && it.extension == "kt" }.toList()
    }

    @Test
    fun onlyGit4IdeaImplPackagesImportTheGitPlugin() {
        val importing = sources().filter { file ->
            file.readText().lines().any { it.startsWith("import git4idea.") || it.startsWith("import com.intellij.externalProcessAuthHelper.") }
        }
        assertTrue("the transport uses the Git plugin", importing.any { it.name == "Git4IdeaGoldenGitTransport.kt" })
        for (file in importing) {
            assertTrue("$file imports the Git plugin outside a git4idea/impl package", "/git4idea/impl/" in file.invariantSeparatorsPathString)
        }
    }

    @Test
    fun onlyTheOptionalGitFragmentNamesGit4IdeaImplClasses() {
        val descriptors = Files.list(main.resolve("resources/META-INF")).use { paths -> paths.filter { it.extension == "xml" }.toList() }
        val naming = descriptors.filter { ".git4idea.impl." in it.readText() }.map { it.name }
        assertEquals(listOf("ansibility-git.xml"), naming)
        val plugin = main.resolve("resources/META-INF/plugin.xml").readText()
        assertTrue(plugin, "<depends optional=\"true\" config-file=\"ansibility-git.xml\">Git4Idea</depends>" in plugin)
        val build = Paths.get("build.gradle.kts").readText()
        assertTrue("Git4Idea is a bundled plugin of the build", Regex("""bundledPlugins\([^)]*"Git4Idea"""").containsMatchIn(build))
    }
}
