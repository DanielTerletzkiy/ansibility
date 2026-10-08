package de.terletzkiy.ansibility.vault.vcs

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
 * VCS support is optional (`com.intellij.modules.vcs`, plan amendment R21, D162): classes that use the VCS API live in
 * a `vcs/impl` package and are referenced only from the optional fragment `ansibility-vcs.xml`, so the plugin loads in
 * an IDE without VCS support. Gradle runs the plugin tests in the `plugin` module directory.
 */
class VcsIsolationTest {
    private val main: Path = Paths.get("src/main")

    private fun sources(): List<Path> = Files.walk(main.resolve("kotlin")).use { paths ->
        paths.filter { it.isRegularFile() && it.extension == "kt" }.toList()
    }

    @Test
    fun onlyVcsImplPackagesImportTheVcsApi() {
        val importing = sources().filter { file -> file.readText().lines().any { it.startsWith("import com.intellij.openapi.vcs.") } }
        assertTrue("the status lookup uses the VCS API", importing.any { it.name == "VcsTrackedStatusLookup.kt" })
        for (file in importing) {
            assertTrue("$file imports the VCS API outside a vcs/impl package", "/vcs/impl/" in file.invariantSeparatorsPathString)
        }
    }

    @Test
    fun onlyTheOptionalVcsFragmentNamesVcsImplClasses() {
        val descriptors = Files.list(main.resolve("resources/META-INF")).use { paths -> paths.filter { it.extension == "xml" }.toList() }
        val naming = descriptors.filter { ".vcs.impl." in it.readText() }.map { it.name }
        assertEquals(listOf("ansibility-vcs.xml"), naming)
        val plugin = main.resolve("resources/META-INF/plugin.xml").readText()
        assertTrue(plugin, "<depends optional=\"true\" config-file=\"ansibility-vcs.xml\">com.intellij.modules.vcs</depends>" in plugin)
    }
}
