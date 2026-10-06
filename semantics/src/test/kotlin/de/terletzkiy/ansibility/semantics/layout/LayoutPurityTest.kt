package de.terletzkiy.ansibility.semantics.layout

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The layout rules are pure (plan amendment R10, D52/D53): no process environment, home directory, file system,
 * process or IntelliJ class. A source scan backs up the behavioural test in [CfgPathListTest]; the `:plugin`
 * ArchitectureTest guards the IntelliJ imports of the whole module as well.
 */
class LayoutPurityTest {
    private val forbidden = listOf(
        "System.getenv", "System.getProperty", "user.home", "java.io.", "java.nio.", "ProcessBuilder", "Runtime.getRuntime",
        "com.intellij.",
    )

    @Test
    fun `the layout package reads nothing outside its arguments`() {
        val dir = File(System.getProperty("user.dir"), "src/main/kotlin/de/terletzkiy/ansibility/semantics/layout")
        val sources = dir.listFiles { f -> f.extension == "kt" }.orEmpty().sortedBy { it.name }
        val names = sources.map { it.name }
        val expected = listOf("CfgPathList.kt", "CfgSyntax.kt", "EnvironmentIds.kt", "IgnoreExtensions.kt")
        assertTrue(names.containsAll(expected), "scanned ${dir.path}: $names")
        val hits = sources.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> forbidden.any { it in line } }
                .map { "${file.name}:${it.index + 1}: ${it.value.trim()}" }
        }
        assertTrue(hits.isEmpty(), hits.joinToString("\n"))
    }
}
